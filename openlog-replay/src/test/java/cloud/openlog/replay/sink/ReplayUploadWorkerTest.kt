package cloud.openlog.replay.sink

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import cloud.openlog.replay.net.HttpRequest
import cloud.openlog.replay.net.HttpResponse
import cloud.openlog.replay.net.HttpTransport
import cloud.openlog.replay.net.OpenLogTransports
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end coverage of [ReplayUploadWorker.doWork]: seq-ordered upload, header
 * construction, retry-holds-order, permanent-drop-continues, and the §4
 * presign→PUT→commit path. API calls run through an injected fake [HttpTransport]
 * (via [OpenLogTransports]); the raw object-storage PUT — which the worker forces
 * through the built-in transport — lands on an in-process [HttpServer].
 */
@RunWith(RobolectricTestRunner::class)
class ReplayUploadWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val queueRoot = File(context.filesDir, HttpSessionSink.QUEUE_DIR)
    private val baseUrl = "https://openlog.test"

    @After
    fun tearDown() {
        OpenLogTransports.set(null)
        queueRoot.deleteRecursively()
    }

    // ---- helpers -----------------------------------------------------------

    private class FakeTransport(val responder: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            return responder(request)
        }
    }

    private fun device() = DeviceInfo("Android", "15", "Pixel 9", 2.625f, 411, 923, "1.0.0")

    private fun seedSession(sessionId: String, batches: List<Pair<Int, String>>): File {
        val dir = File(queueRoot, sessionId).apply { mkdirs() }
        val meta = SessionMeta(sessionId, baseUrl, "tok", "com.example", "0.1.0", device())
        File(dir, HttpSessionSink.META_FILE)
            .writeText(SinkJson.encodeToString(SessionMeta.serializer(), meta))
        batches.forEach { (seq, body) -> File(dir, HttpSessionSink.batchName(seq)).writeText(body) }
        return dir
    }

    private fun runWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<ReplayUploadWorker>(context).build().startWork().get()

    private fun seqsRequested(fake: FakeTransport) =
        fake.requests.map { it.headers["X-OpenLog-Batch-Seq"] }

    private fun path(req: HttpRequest) = req.url.removePrefix(baseUrl)

    // ---- tests -------------------------------------------------------------

    @Test
    fun directBatchesUploadInSeqOrderWithSpecHeaders() {
        val fake = FakeTransport { HttpResponse(202, "{\"received\":1}".toByteArray()) }
        OpenLogTransports.set(fake)
        seedSession("sess-1", listOf(1 to "{\"type\":4}\n", 2 to "{\"type\":2}\n"))

        assertEquals(ListenableWorker.Result.success(), runWorker())

        // Both delivered → session dir cleaned up.
        assertFalse(File(queueRoot, "sess-1").exists())

        val ingest = fake.requests.filter { path(it) == "/api/ingest" }
        assertEquals(listOf("1", "2"), ingest.map { it.headers["X-OpenLog-Batch-Seq"] })
        ingest.forEach { req ->
            assertEquals("POST", req.method)
            assertEquals("Bearer tok", req.headers["Authorization"])
            assertEquals("sess-1", req.headers["X-OpenLog-Session-Id"])
            assertEquals("com.example", req.headers["X-OpenLog-App"])
            assertEquals("0.1.0", req.headers["X-OpenLog-Sdk"])
            assertEquals("application/x-ndjson", req.headers["Content-Type"])
            // Device header rides on every batch.
            assertTrue(req.headers["X-OpenLog-Device"]!!.contains("\"os\":\"Android\""))
        }
    }

    @Test
    fun retryableFailureHoldsRemainingBatchesInOrder() {
        val fake = FakeTransport { req ->
            if (req.headers["X-OpenLog-Batch-Seq"] == "2") HttpResponse(500, ByteArray(0))
            else HttpResponse(202, ByteArray(0))
        }
        OpenLogTransports.set(fake)
        val dir = seedSession("sess-2", listOf(1 to "a\n", 2 to "b\n", 3 to "c\n"))

        assertEquals(ListenableWorker.Result.retry(), runWorker())

        assertFalse(File(dir, HttpSessionSink.batchName(1)).exists()) // delivered
        assertTrue(File(dir, HttpSessionSink.batchName(2)).exists())  // failed → retained, same seq
        assertTrue(File(dir, HttpSessionSink.batchName(3)).exists())  // never attempted (order held)
        assertEquals(listOf("1", "2"), seqsRequested(fake))
    }

    @Test
    fun permanentFailureDropsPoisonBatchAndContinues() {
        val fake = FakeTransport { req ->
            if (req.headers["X-OpenLog-Batch-Seq"] == "2") HttpResponse(400, "{}".toByteArray())
            else HttpResponse(202, ByteArray(0))
        }
        OpenLogTransports.set(fake)
        seedSession("sess-3", listOf(1 to "a\n", 2 to "b\n", 3 to "c\n"))

        assertEquals(ListenableWorker.Result.success(), runWorker())

        // 1 ok, 2 dropped (permanent), 3 ok → all gone, dir cleaned.
        assertFalse(File(queueRoot, "sess-3").exists())
        assertEquals(listOf("1", "2", "3"), seqsRequested(fake))
    }

    @Test
    fun oversizeBatchUsesPresignPutCommit() {
        val put = PutRecorder().also { it.start() }
        val objectUrl = "http://127.0.0.1:${put.port}/obj"
        val objectKey = "sessions/sess-4/batch-0000001.ndjson"

        val fake = FakeTransport { req ->
            when (path(req)) {
                "/api/ingest" -> HttpResponse(413, "{\"error\":\"Batch too large\"}".toByteArray())
                "/api/ingest/presign" ->
                    HttpResponse(200, """{"url":"$objectUrl","objectKey":"$objectKey"}""".toByteArray())
                "/api/ingest/commit" -> HttpResponse(202, "{\"received\":1}".toByteArray())
                else -> HttpResponse(500, ByteArray(0))
            }
        }
        OpenLogTransports.set(fake)
        seedSession("sess-4", listOf(1 to "{\"type\":2}\n"))

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertFalse(File(queueRoot, "sess-4").exists())

        // 413 on direct → presign → (PUT to storage) → commit.
        assertEquals(
            listOf("/api/ingest", "/api/ingest/presign", "/api/ingest/commit"),
            fake.requests.map { path(it) },
        )
        val commit = fake.requests.last()
        assertTrue(String(commit.body).contains("\"objectKey\":\"$objectKey\""))

        // Raw PUT: no auth (signed URL), ndjson content-type, exact body.
        assertEquals("PUT", put.method)
        assertNull(put.auth)
        assertEquals("application/x-ndjson", put.contentType)
        assertEquals("{\"type\":2}\n", put.body)

        put.stop()
    }

    @Test
    fun presignUnavailableDropsBatchWithoutPutOrCommit() {
        // 413 routes to the large path; presign then returns 501 (storage can't
        // presign). The single oversized event can't be split → drop (logged).
        val fake = FakeTransport { req ->
            when (path(req)) {
                "/api/ingest" -> HttpResponse(413, "{}".toByteArray())
                "/api/ingest/presign" -> HttpResponse(501, "{}".toByteArray())
                else -> HttpResponse(202, ByteArray(0))
            }
        }
        OpenLogTransports.set(fake)
        seedSession("sess-5", listOf(1 to "{\"type\":2}\n"))

        // DROP is terminal-success for the worker (nothing left to retry).
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertFalse(File(queueRoot, "sess-5").exists())

        // Stopped after presign: no PUT-to-storage, no commit.
        assertEquals(listOf("/api/ingest", "/api/ingest/presign"), fake.requests.map { path(it) })
    }

    /** A tiny in-process server standing in for object storage on the presigned PUT. */
    private class PutRecorder {
        private lateinit var server: HttpServer
        var method: String? = null
        var auth: String? = null
        var contentType: String? = null
        var body: String? = null
        val port get() = server.address.port

        fun start() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/obj") { exchange: HttpExchange ->
                method = exchange.requestMethod
                auth = exchange.requestHeaders.getFirst("Authorization")
                contentType = exchange.requestHeaders.getFirst("Content-Type")
                body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            server.start()
        }

        fun stop() = server.stop(0)
    }
}
