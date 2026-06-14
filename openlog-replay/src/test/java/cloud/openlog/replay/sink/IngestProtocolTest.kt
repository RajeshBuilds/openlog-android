package cloud.openlog.replay.sink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the ingest protocol's correctness-critical, Android-free
 * pieces: the batch-seq filename scheme (the `X-OpenLog-Batch-Seq` idempotency
 * key) and the persisted [SessionMeta] / [DeviceInfo] that the upload worker
 * reconstructs requests from. (Sink batching and worker uploads run through
 * WorkManager / HTTP and are exercised by instrumented tests.)
 */
class IngestProtocolTest {

    @Test
    fun batchNameAndSeqRoundTrip() {
        for (seq in intArrayOf(1, 2, 9, 10, 99, 100, 1_000_000)) {
            assertEquals(seq, HttpSessionSink.seqOf(HttpSessionSink.batchName(seq)))
        }
    }

    @Test
    fun batchNamesSortLexicallyInSeqOrder() {
        // The worker relies on sortedBy { name } == seq order to preserve batch
        // ordering, so zero-padding must keep lexical and numeric order aligned.
        val names = listOf(1, 2, 10, 11, 100, 99).map { HttpSessionSink.batchName(it) }
        val seqsInLexicalOrder = names.sorted().map { HttpSessionSink.seqOf(it) }
        assertEquals(listOf(1, 2, 10, 11, 99, 100), seqsInLexicalOrder)
    }

    @Test
    fun seqOfRejectsNonBatchNames() {
        assertNull(HttpSessionSink.seqOf(HttpSessionSink.META_FILE))
        assertNull(HttpSessionSink.seqOf("batch-.ndjson"))
        assertNull(HttpSessionSink.seqOf("batch-0000001.txt"))
        assertNull(HttpSessionSink.seqOf("random.ndjson"))
    }

    @Test
    fun deviceHeaderIsSingleLineJsonWithSpecFields() {
        val json = DeviceInfo(
            osVersion = "15",
            manufacturer = "Google",
            model = "Pixel 9",
            density = 2.625f,
            w = 411,
            h = 923,
            appVersion = "1.0.0",
        ).toHeaderJson()

        assertTrue("header must be single-line", !json.contains('\n'))
        assertTrue(json.contains("\"os\":\"Android\""))
        assertTrue(json.contains("\"osVersion\":\"15\""))
        assertTrue(json.contains("\"manufacturer\":\"Google\""))
        assertTrue(json.contains("\"model\":\"Pixel 9\""))
        assertTrue(json.contains("\"w\":411"))
        assertTrue(json.contains("\"h\":923"))
        assertTrue(json.contains("\"appVersion\":\"1.0.0\""))
    }

    @Test
    fun configRejectsBlankTokenAndBaseUrl() {
        // Required credential — fail fast at config time, not with a silent 401 later.
        assertThrows(IllegalArgumentException::class.java) {
            HttpSessionSink.Config(baseUrl = "https://openlog.sh", token = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpSessionSink.Config(baseUrl = "https://openlog.sh", token = "   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpSessionSink.Config(baseUrl = "", token = "tok")
        }
        // A valid pair constructs fine.
        HttpSessionSink.Config(baseUrl = "https://openlog.sh", token = "tok")
    }

    @Test
    fun sessionMetaRoundTripsThroughDisk() {
        val meta = SessionMeta(
            sessionId = "1f3a2b-9c.0",
            baseUrl = "https://openlog.sh",
            token = "secret-token",
            appId = "com.example.app",
            sdkVersion = "0.1.0",
            device = DeviceInfo("Android", "15", "Google", "Pixel 9", 2.625f, 411, 923, "1.0.0"),
            extraHeaders = mapOf("X-Tenant" to "acme"),
        )

        val encoded = SinkJson.encodeToString(SessionMeta.serializer(), meta)
        val decoded = SinkJson.decodeFromString(SessionMeta.serializer(), encoded)

        assertEquals(meta, decoded)
        assertEquals("acme", decoded.extraHeaders["X-Tenant"])
    }
}
