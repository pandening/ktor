package io.ktor.server.testing

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.network.util.*
import io.ktor.server.engine.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.future.*
import kotlinx.coroutines.io.*
import java.util.concurrent.*
import kotlin.coroutines.*

class TestApplicationEngine(
    environment: ApplicationEngineEnvironment = createTestEnvironment(),
    configure: Configuration.() -> Unit = {}
) : BaseApplicationEngine(environment, EnginePipeline()), CoroutineScope {

    private val testEngineJob = Job()

    override val coroutineContext: CoroutineContext
        get() = testEngineJob

    class Configuration : BaseApplicationEngine.Configuration() {
        var dispatcher: CoroutineContext = Dispatchers.IO
    }

    private val configuration = Configuration().apply(configure)

    init {
        pipeline.intercept(EnginePipeline.Call) {
            call.application.execute(call)
        }
    }

    override fun start(wait: Boolean): ApplicationEngine {
        environment.start()
        return this
    }

    override fun stop(gracePeriod: Long, timeout: Long, timeUnit: TimeUnit) {
        try {
            environment.monitor.raise(ApplicationStopPreparing, environment)
            environment.stop()
        } finally {
            testEngineJob.cancel()
        }
    }

    private var processRequest: TestApplicationRequest.(setup: TestApplicationRequest.() -> Unit) -> Unit = { it() }
    private var processResponse: TestApplicationCall.() -> Unit = { }

    fun hookRequests(
        processRequest: TestApplicationRequest.(setup: TestApplicationRequest.() -> Unit) -> Unit,
        processResponse: TestApplicationCall.() -> Unit,
        block: () -> Unit
    ) {
        val oldProcessRequest = processRequest
        val oldProcessResponse = processResponse
        this.processRequest = {
            oldProcessRequest {
                processRequest(it)
            }
        }
        this.processResponse = {
            oldProcessResponse()
            processResponse()
        }
        try {
            block()
        } finally {
            this.processResponse = oldProcessResponse
            this.processRequest = oldProcessRequest
        }
    }

    @UseExperimental(InternalAPI::class)
    fun handleRequest(setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
        val call = createCall(readResponse = true, setup = { processRequest(setup) })

        val context = configuration.dispatcher + SupervisorJob() + CoroutineName("request")
        val pipelineJob = GlobalScope.async(context) {
            pipeline.execute(call)
        }

        runBlocking(coroutineContext) {
            pipelineJob.await()
            call.response.flush()
            context.cancel()
        }
        processResponse(call)

        return call
    }

    fun handleWebSocket(uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
        val call = createCall {
            this.uri = uri
            addHeader(HttpHeaders.Connection, "Upgrade")
            addHeader(HttpHeaders.Upgrade, "websocket")
            addHeader(HttpHeaders.SecWebSocketKey, encodeBase64("test".toByteArray()))

            processRequest(setup)
        }

        // we can't simply do runBlocking here because runBlocking is not completing
        // until all children completion (writer is the most dangerous example that can cause deadlock here)
        val pipelineExecuted = CompletableDeferred<Unit>(coroutineContext[Job])
        launch(configuration.dispatcher) {
            try {
                pipeline.execute(call)
                pipelineExecuted.complete(Unit)
            } catch (cause: Throwable) {
                pipelineExecuted.cancel(cause)
            }
        }
        processResponse(call)

        pipelineExecuted.asCompletableFuture().join()

        return call
    }

    @UseExperimental(WebSocketInternalAPI::class)
    fun handleWebSocketConversation(
        uri: String, setup: TestApplicationRequest.() -> Unit = {},
        callback: suspend TestApplicationCall.(incoming: ReceiveChannel<Frame>, outgoing: SendChannel<Frame>) -> Unit
    ): TestApplicationCall {
        val websocketChannel = ByteChannel(true)
        val call = handleWebSocket(uri) {
            processRequest(setup)
            bodyChannel = websocketChannel
        }

        val pool = KtorDefaultPool
        val engineContext = Dispatchers.Unconfined
        val job = Job()
        val webSocketContext = engineContext + job

        val writer = WebSocketWriter(websocketChannel, webSocketContext, pool = pool)
        val reader = WebSocketReader(call.response.websocketChannel()!!, webSocketContext, Int.MAX_VALUE.toLong(), pool)

        runBlocking(configuration.dispatcher) {
            call.callback(reader.incoming, writer.outgoing)
            writer.flush()
            writer.close()
            job.cancelAndJoin()
        }

        processResponse(call)

        return call
    }

    fun createCall(readResponse: Boolean = false, setup: TestApplicationRequest.() -> Unit): TestApplicationCall =
        TestApplicationCall(application, readResponse, Dispatchers.IO).apply { setup(request) }
}

/**
 * Keep cookies between requests inside the [callback].
 *
 * This processes [HttpHeaders.SetCookie] from the responses and produce [HttpHeaders.Cookie] in subsequent requests.
 */
fun TestApplicationEngine.cookiesSession(callback: () -> Unit) {
    var trackedCookies: List<Cookie> = listOf()

    hookRequests(
        processRequest = { setup ->
            addHeader(HttpHeaders.Cookie, trackedCookies.joinToString("; ") {
                (it.name).encodeURLParameter() + "=" + (it.value).encodeURLParameter()
            })
            setup() // setup after setting the cookie so the user can override cookies
        },
        processResponse = {
            trackedCookies = response.headers.values(HttpHeaders.SetCookie).map { parseServerSetCookieHeader(it) }
        }
    ) {
        callback()
    }
}
