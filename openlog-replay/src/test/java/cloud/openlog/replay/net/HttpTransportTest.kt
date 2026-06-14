package cloud.openlog.replay.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Both transports are exercised against an in-process [HttpServer] (JDK built-in,
 * no extra deps) so the SPI contract — headers/body sent verbatim, status + body
 * returned — is verified identically for the default and the OkHttp plugin.
 */
class HttpTransportTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private data class Captured(
        var method: String? = null,
        var contentType: String? = null,
        var auth: String? = null,
        var body: String? = null,
    )

    private val captured = Captured()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/ingest") { exchange: HttpExchange ->
            captured.method = exchange.requestMethod
            captured.contentType = exchange.requestHeaders.getFirst("Content-Type")
            captured.auth = exchange.requestHeaders.getFirst("Authorization")
            captured.body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val payload = """{"received":1}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(202, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
        OpenLogTransports.set(null)
    }

    private fun request() = HttpRequest(
        method = "POST",
        url = "$baseUrl/api/ingest",
        headers = mapOf(
            "Content-Type" to "application/x-ndjson",
            "Authorization" to "Bearer tok",
        ),
        body = "{\"type\":4}\n".toByteArray(Charsets.UTF_8),
    )

    private fun assertContract(transport: HttpTransport) {
        val resp = transport.execute(request())
        assertEquals(202, resp.code)
        assertEquals("""{"received":1}""", String(resp.body, Charsets.UTF_8))
        assertEquals("POST", captured.method)
        assertEquals("application/x-ndjson", captured.contentType)
        assertEquals("Bearer tok", captured.auth)
        assertEquals("{\"type\":4}\n", captured.body)
    }

    @Test
    fun urlConnectionTransportSendsAndReceives() {
        assertContract(UrlConnectionTransport)
    }

    @Test
    fun okHttpTransportSendsAndReceives() {
        assertContract(OkHttpTransport(OkHttpClient()))
    }

    @Test
    fun registryDefaultsToUrlConnectionAndHonorsOverride() {
        assertSame(UrlConnectionTransport, OpenLogTransports.current())

        val custom = HttpTransport { HttpResponse(200, ByteArray(0)) }
        OpenLogTransports.set(custom)
        assertSame(custom, OpenLogTransports.current())

        OpenLogTransports.set(null)
        assertSame(UrlConnectionTransport, OpenLogTransports.current())
    }

    @Test
    fun urlConnectionTransportFollows308PreservingPostAndBody() {
        // Deployments 308-redirect apex->www; the transport must re-POST to the new
        // location with the method and body intact, not drop it as a 3xx.
        server.createContext("/api/ingest-apex") { exchange: HttpExchange ->
            exchange.responseHeaders.add("Location", "$baseUrl/api/ingest")
            exchange.sendResponseHeaders(308, -1)
            exchange.close()
        }

        val resp = UrlConnectionTransport.execute(
            HttpRequest(
                method = "POST",
                url = "$baseUrl/api/ingest-apex",
                headers = mapOf("Content-Type" to "application/x-ndjson", "Authorization" to "Bearer tok"),
                body = "{\"type\":4}\n".toByteArray(Charsets.UTF_8),
            ),
        )

        assertEquals(202, resp.code)
        // The followed request hit the real handler with method + body + headers intact.
        assertEquals("POST", captured.method)
        assertEquals("{\"type\":4}\n", captured.body)
        assertEquals("Bearer tok", captured.auth)
    }

    @Test
    fun networkErrorSurfacesAsThrownException() {
        // Nothing listening on this port → connect refused → IOException.
        var threw = false
        try {
            UrlConnectionTransport.execute(
                HttpRequest("POST", "http://127.0.0.1:1/api/ingest", emptyMap(), ByteArray(0)),
            )
        } catch (_: Throwable) {
            threw = true
        }
        assertTrue("transport must throw on connect failure", threw)
    }
}
