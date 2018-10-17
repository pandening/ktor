package io.ktor.server.testing

import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.jetty.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.features.*
import io.ktor.network.tls.certificates.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.util.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.internal.runners.statements.*
import org.junit.rules.*
import org.junit.runner.*
import org.junit.runners.model.*
import org.slf4j.*
import java.io.*
import java.net.*
import java.security.*
import java.util.concurrent.*
import javax.net.ssl.*
import kotlin.concurrent.*
import kotlin.coroutines.*
import kotlin.test.*


abstract class EngineTestBase<TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration>(
    val applicationEngineFactory: ApplicationEngineFactory<TEngine, TConfiguration>
) : CoroutineScope {
    private val testJob = Job()
    protected val testDispatcher by lazy { newFixedThreadPoolContext(32, "dispatcher-${test.methodName}") }

    protected val isUnderDebugger: Boolean =
        java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.orEmpty()
            .any { "-agentlib:jdwp" in it }

    protected var port = findFreePort()
    protected var sslPort = findFreePort()
    protected var server: TEngine? = null
    protected var callGroupSize = -1
        private set
    protected val exceptions = ArrayList<Throwable>()
    protected var enableHttp2: Boolean = System.getProperty("enable.http2") == "true"
    protected var enableSsl: Boolean = System.getProperty("enable.ssl") != "false"

    private val allConnections = CopyOnWriteArrayList<HttpURLConnection>()

    val testLog: Logger = LoggerFactory.getLogger("EngineTestBase")

    @Target(AnnotationTarget.FUNCTION)
    @Retention
    protected annotation class Http2Only

    @Target(AnnotationTarget.FUNCTION)
    @Retention
    protected annotation class NoHttp2

    override val coroutineContext: CoroutineContext
        get() = testJob + testDispatcher

    @get:Rule
    val test = TestName()

    @get:Rule
    open val timeout = PublishedTimeout(
        if (isUnderDebugger) 1000000L else (System.getProperty("host.test.timeout.seconds")?.toLong() ?: 240L)
    )

    protected val socketReadTimeout: Int by lazy { TimeUnit.SECONDS.toMillis(timeout.seconds).toInt() }

    @Before
    fun setUpBase() {
        val method = this.javaClass.getMethod(test.methodName) ?: fail("Method ${test.methodName} not found")

        if (method.isAnnotationPresent(Http2Only::class.java)) {
            Assume.assumeTrue("http2 is not enabled", enableHttp2)
        }
        if (method.isAnnotationPresent(NoHttp2::class.java)) {
            enableHttp2 = false
        }

        if (enableHttp2) {
            Class.forName("sun.security.ssl.ALPNExtension", true, null)
        }

        testLog.trace("Starting server on port $port (SSL $sslPort)")
        exceptions.clear()
    }

    @After
    fun tearDownBase() {
        try {
            allConnections.forEach { it.disconnect() }
            testLog.trace("Disposing server on port $port (SSL $sslPort)")
            (server as? ApplicationEngine)?.stop(1000, 5000, TimeUnit.MILLISECONDS)
            if (exceptions.isNotEmpty()) {
                fail("Server exceptions logged, consult log output for more information")
            }
        } finally {
            testJob.cancel()
            val closeThread = thread(start = false, name = "shutdown-test-${test.methodName}") {
                testDispatcher.close()
            }
            testJob.invokeOnCompletion {
                closeThread.start()
            }
            closeThread.join(TimeUnit.SECONDS.toMillis(timeout.seconds))
        }
    }

    protected open fun createServer(log: Logger?, module: Application.() -> Unit): TEngine {
        val _port = this.port
        val environment = applicationEngineEnvironment {
            val delegate = LoggerFactory.getLogger("ktor.test")
            this.log = log ?: object : Logger by delegate {
                override fun error(msg: String?, t: Throwable?) {
                    t?.let {
                        exceptions.add(it)
                        println("Critical test exception: $it")
                        it.printStackTrace()
                        println("From origin:")
                        Exception().printStackTrace()
                    }
                    delegate.error(msg, t)
                }
            }

            connector { port = _port }
            if (enableSsl) {
                sslConnector(keyStore, "mykey", { "changeit".toCharArray() }, { "changeit".toCharArray() }) {
                    this.port = sslPort
                    this.keyStorePath = keyStoreFile.absoluteFile
                }
            }

            module(module)
        }

        return embeddedServer(applicationEngineFactory, environment) {
            configure(this)
            this@EngineTestBase.callGroupSize = callGroupSize
        }
    }

    protected open fun configure(configuration: TConfiguration) {
        // Empty, intended to be override in derived types when necessary
    }

    protected open fun features(application: Application, routingConfigurer: Routing.() -> Unit) {
        application.install(CallLogging)
        application.install(Routing, routingConfigurer)
    }

    protected fun createAndStartServer(log: Logger? = null, routingConfigurer: Routing.() -> Unit): TEngine {
        var lastFailures = emptyList<Throwable>()
        for (attempt in 1..5) {
            val server = createServer(log) {
                features(this, routingConfigurer)
            }

            val failures = startServer(server)
            when {
                failures.isEmpty() -> return server
                failures.any { it is BindException } -> {
                    port = findFreePort()
                    sslPort = findFreePort()
                    server.stop(1L, 1L, TimeUnit.SECONDS)
                    lastFailures = failures
                }
                else -> {
                    server.stop(1L, 1L, TimeUnit.SECONDS)
                    throw MultipleFailureException(failures)
                }
            }
        }

        throw MultipleFailureException(lastFailures)
    }

    private fun startServer(server: TEngine): List<Throwable> {
        this.server = server

        // we start it on the global scope because we don't want it to fail the whole test
        // as far as we have retry loop on call side
        val starting = GlobalScope.async(testDispatcher) {
            server.start(wait = false)

            withTimeout(TimeUnit.SECONDS.toMillis(minOf(10, timeout.seconds))) {
                server.environment.connectors.forEach { connector ->
                    waitForPort(connector.port)
                }
            }
        }

        return try {
            runBlocking {
                starting.join()
                starting.getCompletionExceptionOrNull()?.let { listOf(it) } ?: emptyList()
            }
        } catch (t: Throwable) { // InterruptedException?
            starting.cancel()
            listOf(t)
        }
    }

    protected fun findFreePort() = ServerSocket(0).use { it.localPort }
    protected fun withUrl(
        path: String, builder: HttpRequestBuilder.() -> Unit = {}, block: suspend HttpResponse.(Int) -> Unit
    ) {
        withUrl(URL("http://127.0.0.1:$port$path"), port, builder, block)

        if (enableSsl) {
            withUrl(URL("https://127.0.0.1:$sslPort$path"), sslPort, builder, block)
        }

        if (enableHttp2 && enableSsl) {
            withHttp2(URL("https://127.0.0.1:$sslPort$path"), sslPort, builder, block)
        }
    }

    protected inline fun socket(block: Socket.() -> Unit) {
        Socket("localhost", port).use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = socketReadTimeout

            block(socket)
        }
    }

    private fun withUrl(
        url: URL, port: Int, builder: HttpRequestBuilder.() -> Unit,
        block: suspend HttpResponse.(Int) -> Unit
    ) = runBlocking {
        withTimeout(TimeUnit.SECONDS.toMillis(timeout.seconds)) {
            HttpClient(CIO.config {
                https.also {
                    it.trustManager = trustManager
                }
            }) {
                followRedirects = false
                expectSuccess = false
            }.use { client ->
                client.call(url, builder).response.use { response ->
                    block(response, port)
                }
            }
        }
    }

    private fun withHttp2(
        url: URL, port: Int,
        builder: HttpRequestBuilder.() -> Unit, block: suspend HttpResponse.(Int) -> Unit
    ): Unit = runBlocking {
        withTimeout(TimeUnit.SECONDS.toMillis(timeout.seconds)) {
            HttpClient(Jetty) {
                followRedirects = false
                expectSuccess = false
            }.use { httpClient ->
                httpClient.call(url, builder).response.use { response ->
                    block(response, port)
                }
            }
        }
    }

    class PublishedTimeout(val seconds: Long) : Timeout(seconds, TimeUnit.SECONDS)

    companion object {
        val keyStoreFile = File("build/temp.jks")
        lateinit var keyStore: KeyStore
        lateinit var sslContext: SSLContext
        lateinit var trustManager: X509TrustManager

        @BeforeClass
        @JvmStatic
        fun setupAll() {
            keyStore = generateCertificate(keyStoreFile, algorithm = "SHA256withECDSA", keySizeInBits = 256)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, tmf.trustManagers, null)
            trustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        }

        private suspend fun waitForPort(port: Int) {
            do {
                delay(50)
                try {
                    Socket("localhost", port).close()
                    break
                } catch (expected: IOException) {
                }
            } while (true)
        }
    }
}
