package cloud.openlog.replay.sink

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import cloud.openlog.replay.net.HttpRequest
import cloud.openlog.replay.net.HttpTransport
import cloud.openlog.replay.net.OpenLogTransports
import cloud.openlog.replay.net.UrlConnectionTransport
import cloud.openlog.replay.sink.HttpSessionSink.Companion.META_FILE
import cloud.openlog.replay.sink.HttpSessionSink.Companion.QUEUE_DIR
import cloud.openlog.replay.sink.HttpSessionSink.Companion.seqOf
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Drains queued batch files to the OpenLog ingest API (docs/INGEST_API_SPEC.md).
 *
 * API calls go through the configured [HttpTransport] ([OpenLogTransports.current],
 * the host's OkHttp client if supplied, else the built-in [UrlConnectionTransport]).
 * The raw object-storage PUT (§4.2) always uses [UrlConnectionTransport]: its URL
 * is presigned and must carry NO `Authorization` header, so routing it through a
 * host client (whose interceptors/cert-pinning target the API domain) would break
 * the signature.
 *
 * Per session dir, batches upload **in seq order**; the worker stops a session on
 * the first retryable failure to preserve order (a later batch must never land
 * before an earlier one). Sessions are independent of each other.
 *
 * Routing per batch (§3 / §4):
 *  - ≤ [MAX_DIRECT_BYTES]  → `POST /api/ingest` (NDJSON body).
 *  - larger, or a direct 413 → presign → PUT to object storage → commit.
 *
 * Retry policy (§3.4): network errors and 5xx retry with the **same** batch seq
 * (the server dedupes by seq, so this never double-counts); 4xx are permanent and
 * the poison batch is dropped to avoid blocking the queue forever.
 */
class ReplayUploadWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun doWork(): Result {
        val queueRoot = File(applicationContext.filesDir, QUEUE_DIR)
        val sessionDirs = queueRoot.listFiles()?.filter { it.isDirectory } ?: return Result.success()

        var anyRetry = false
        for (dir in sessionDirs) {
            val meta = readMeta(dir) ?: continue // can't build requests without metadata
            val batches = dir.listFiles()
                ?.filter { seqOf(it.name) != null }
                ?.sortedBy { it.name } // zero-padded name == seq order
                ?: emptyList()

            for (batch in batches) {
                val seq = seqOf(batch.name) ?: continue
                when (uploadBatch(meta, batch, seq)) {
                    Outcome.SUCCESS -> batch.delete()
                    Outcome.DROP -> {
                        // Permanently rejected (e.g. 4xx) — drop so it can't block the
                        // queue, but say so: this is data loss, not a successful upload.
                        Log.w(TAG, "Dropping batch seq=$seq for session ${meta.sessionId}: permanently rejected by server.")
                        batch.delete()
                    }
                    Outcome.RETRY -> {
                        anyRetry = true
                        break // hold the rest of THIS session until the retry; preserve order
                    }
                }
            }
            cleanupIfDrained(dir)
        }
        return if (anyRetry) Result.retry() else Result.success()
    }

    private fun uploadBatch(meta: SessionMeta, batch: File, seq: Int): Outcome {
        val body = runCatching { batch.readBytes() }.getOrElse { return Outcome.DROP }
        return if (body.size > MAX_DIRECT_BYTES) {
            uploadLarge(meta, body, seq)
        } else {
            when (val r = uploadDirect(meta, body, seq)) {
                is Response.Code -> if (r.code == 413) uploadLarge(meta, body, seq) else classify(r.code)
                Response.NetworkError -> Outcome.RETRY
            }
        }
    }

    // ---- §3 direct path ----------------------------------------------------

    private fun uploadDirect(meta: SessionMeta, body: ByteArray, seq: Int): Response {
        val headers = ingestHeaders(meta, seq) + ("Content-Type" to "application/x-ndjson")
        return send(apiTransport, "POST", "${meta.baseUrl}/api/ingest", headers, body)
    }

    // ---- §4 large-batch path: presign → PUT → commit -----------------------

    private fun uploadLarge(meta: SessionMeta, body: ByteArray, seq: Int): Outcome {
        // 4.1 presign. sessionId matches ^[\w.-]+$ (no JSON escaping needed).
        val presignBody = """{"sessionId":"${meta.sessionId}","batchSeq":$seq}""".toByteArray(Charsets.UTF_8)
        val presignHeaders = authHeaders(meta) + ("Content-Type" to "application/json")
        val presignResp = send(apiTransport, "POST", "${meta.baseUrl}/api/ingest/presign", presignHeaders, presignBody)
        when (presignResp) {
            Response.NetworkError -> return Outcome.RETRY
            is Response.Code -> when {
                presignResp.code == 501 -> {
                    // Storage backend can't presign (local-fs dev deployment). The
                    // batch is a single event too large for the direct cap — one
                    // NDJSON line, so it can't be split — and presign is unavailable,
                    // so it's undeliverable. Drop it, but say so: this loses data.
                    Log.w(
                        TAG,
                        "Dropping ${body.size}-byte batch (session=${meta.sessionId}, seq=$seq): " +
                            "presign unavailable (501) and a single oversized event cannot be split.",
                    )
                    return Outcome.DROP
                }
                presignResp.code in 200..299 -> Unit
                presignResp.code in 500..599 -> return Outcome.RETRY
                else -> return Outcome.DROP // 4xx — permanent
            }
        }
        val presigned = parsePresign((presignResp as Response.Code).bodyText()) ?: return Outcome.DROP

        // 4.2 PUT raw NDJSON straight to object storage. Always via the built-in
        // transport — no auth (the signature is in the URL), and a host client's
        // interceptors/pinning would corrupt the signed request.
        val putHeaders = mapOf("Content-Type" to "application/x-ndjson")
        when (val putResp = send(UrlConnectionTransport, "PUT", presigned.url, putHeaders, body)) {
            Response.NetworkError -> return Outcome.RETRY
            is Response.Code -> when {
                putResp.code in 200..299 -> Unit
                putResp.code in 500..599 -> return Outcome.RETRY
                else -> return Outcome.DROP
            }
        }

        // 4.3 commit (not deduped server-side — commit exactly once per object;
        // SUCCESS deletes the batch immediately so we never re-commit it).
        val commitBody =
            """{"sessionId":"${meta.sessionId}","objectKey":"${presigned.objectKey}"}""".toByteArray(Charsets.UTF_8)
        val commitHeaders = ingestHeaders(meta, seq) + ("Content-Type" to "application/json")
        return when (val commitResp = send(apiTransport, "POST", "${meta.baseUrl}/api/ingest/commit", commitHeaders, commitBody)) {
            Response.NetworkError -> Outcome.RETRY
            is Response.Code -> classify(commitResp.code)
        }
    }

    // ---- headers -----------------------------------------------------------

    private fun authHeaders(meta: SessionMeta): Map<String, String> =
        mapOf("Authorization" to "Bearer ${meta.token}")

    /**
     * The shared ingest headers. The device header rides on every batch — the spec
     * permits repeating it ("only persisted when the session row is created"), and
     * it makes session creation robust even if an earlier batch was dropped by the
     * queue quota.
     */
    private fun ingestHeaders(meta: SessionMeta, seq: Int): Map<String, String> =
        mapOf(
            "Authorization" to "Bearer ${meta.token}",
            "X-OpenLog-Session-Id" to meta.sessionId,
            "X-OpenLog-App" to meta.appId,
            "X-OpenLog-Sdk" to meta.sdkVersion,
            "X-OpenLog-Batch-Seq" to seq.toString(),
            "X-OpenLog-Device" to meta.device.toHeaderJson(),
        ) + meta.extraHeaders

    // ---- HTTP --------------------------------------------------------------

    /** The host-configured transport (or the default) for OpenLog API calls. */
    private val apiTransport: HttpTransport get() = OpenLogTransports.current()

    /** Run a request on [transport]; a thrown call becomes a retryable network error. */
    private fun send(
        transport: HttpTransport,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): Response = try {
        val resp = transport.execute(HttpRequest(method, url, headers, body))
        Response.Code(resp.code, resp.body)
    } catch (_: Throwable) {
        Response.NetworkError
    }

    /** 2xx → success; 5xx/408/429 → retry (same seq); other 4xx → permanent drop. */
    private fun classify(code: Int): Outcome = when {
        code in 200..299 -> Outcome.SUCCESS
        code in 500..599 || code == 408 || code == 429 -> Outcome.RETRY
        else -> Outcome.DROP
    }

    // ---- metadata / cleanup ------------------------------------------------

    private fun readMeta(dir: File): SessionMeta? {
        val file = File(dir, META_FILE)
        if (!file.exists()) return null
        return runCatching { SinkJson.decodeFromString(SessionMeta.serializer(), file.readText()) }.getOrNull()
    }

    /** Remove a session dir once every batch is delivered (only session.json remains). */
    private fun cleanupIfDrained(dir: File) {
        val hasBatches = dir.listFiles()?.any { seqOf(it.name) != null } ?: false
        if (!hasBatches) {
            File(dir, META_FILE).delete()
            dir.delete()
        }
    }

    private fun parsePresign(text: String): Presigned? = runCatching {
        val obj: JsonObject = json.parseToJsonElement(text).jsonObject
        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val key = obj["objectKey"]?.jsonPrimitive?.contentOrNull ?: return null
        Presigned(url, key)
    }.getOrNull()

    private data class Presigned(val url: String, val objectKey: String)

    private sealed interface Response {
        class Code(val code: Int, val body: ByteArray) : Response {
            fun bodyText(): String = String(body, Charsets.UTF_8)
        }
        data object NetworkError : Response
    }

    private enum class Outcome { SUCCESS, RETRY, DROP }

    companion object {
        private const val TAG = "OpenLog"

        /**
         * Route batches above this to the large-batch path. Below the server's
         * 3,670,016-byte (3.5 MiB) hard cap, with margin for header overhead — the
         * sink size-flushes under this, so direct batches normally never hit 413.
         */
        const val MAX_DIRECT_BYTES = 3_400_000
    }
}
