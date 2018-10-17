package io.ktor.client.engine.cio

import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlin.test.*

class CIORequestTest : TestWithKtor() {
    private val testSize = 2 * 1024

    override val server: ApplicationEngine = embeddedServer(Netty, serverPort) {
        routing {
            get("/") {
                val longHeader = call.request.headers["LongHeader"]!!
                call.respond(object : OutgoingContent.NoContent() {
                    override val headers: Headers = headersOf("LongHeader", longHeader)
                })
            }
            get("/echo") {
                call.respond("OK")
            }
        }
    }

    @Test
    fun longHeadersTest() = clientTest(CIO) {
        test { client ->
            val headerValue = "x".repeat(testSize)

            val response = client.get<HttpResponse>(port = serverPort) {
                header("LongHeader", headerValue)
            }

            assertEquals(headerValue, response.headers["LongHeader"])
        }
    }

    @Test
    @Ignore
    fun testClose() = runBlocking {
        val client = HttpClient(CIO)
        client.get<String>(path = "/echo", port = serverPort)
        client.close()

        client.coroutineContext[Job]?.join()
        Unit
    }
}