package cloud.openlog.replay.sink

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads queued batch files (SPEC.md T8). Uses [HttpURLConnection] so the SDK has
 * no runtime dependency on OkHttp (OkHttp is `compileOnly`, only for the host's
 * trace interceptor). WorkManager handles retries/backoff: a batch that fails to
 * upload leaves [doWork] returning [Result.retry], and the work re-runs once the
 * network is back.
 */
class ReplayUploadWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val endpoint = inputData.getString(HttpSessionSink.KEY_ENDPOINT) ?: return Result.success()
        val headerNames = inputData.getStringArray(HttpSessionSink.KEY_HEADER_NAMES) ?: emptyArray()
        val headerValues = inputData.getStringArray(HttpSessionSink.KEY_HEADER_VALUES) ?: emptyArray()
        val headers = headerNames.zip(headerValues).toMap()

        val queueDir = File(applicationContext.filesDir, HttpSessionSink.QUEUE_DIR)
        val batches = queueDir.listFiles()?.sortedBy { it.name } ?: return Result.success()

        for (batch in batches) {
            when (upload(endpoint, headers, batch)) {
                UploadOutcome.SUCCESS -> batch.delete()
                UploadOutcome.DROP -> batch.delete() // permanent (4xx) — drop to avoid poison-pill loop
                UploadOutcome.RETRY -> return Result.retry()
            }
        }
        return Result.success()
    }

    private fun upload(endpoint: String, headers: Map<String, String>, batch: File): UploadOutcome {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/x-ndjson")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            connection.outputStream.use { it.write(batch.readBytes()) }
            val code = connection.responseCode
            when {
                code in 200..299 -> UploadOutcome.SUCCESS
                code in 400..499 && code != 408 && code != 429 -> UploadOutcome.DROP
                else -> UploadOutcome.RETRY // 5xx, 408, 429, etc.
            }
        } catch (_: Throwable) {
            UploadOutcome.RETRY
        } finally {
            connection?.disconnect()
        }
    }

    private enum class UploadOutcome { SUCCESS, RETRY, DROP }
}
