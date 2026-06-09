package cloud.openlog.replay.sink

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cloud.openlog.replay.wire.Event
import cloud.openlog.replay.wire.toNdjsonLine
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Batching, disk-persisting HTTP sink (SPEC.md T8). Mirrors the intent of
 * PostHog's replay queue, reimplemented clean-room:
 *
 *  - events buffer in memory until [config].batchSize, then a batch file is
 *    written to a private queue dir;
 *  - [ReplayUploadWorker] is enqueued (unique work, NETWORK_CONNECTED constraint,
 *    exponential backoff) to POST batch files, so an offline→online transition
 *    drains the queue automatically;
 *  - when the on-disk queue exceeds [config].maxQueueBytes the oldest batches are
 *    dropped (drop-oldest, bounded footprint).
 */
class HttpSessionSink(
    context: Context,
    private val config: Config,
) : SessionSink {

    data class Config(
        val endpoint: String,
        val headers: Map<String, String> = emptyMap(),
        val batchSize: Int = 50,
        val maxQueueBytes: Long = 5L * 1024 * 1024,
    )

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val queueDir = File(appContext.filesDir, QUEUE_DIR).apply { mkdirs() }

    private val lock = Any()
    private val buffer = StringBuilder()
    private var buffered = 0

    override fun write(event: Event) {
        synchronized(lock) {
            buffer.append(event.toNdjsonLine()).append('\n')
            if (++buffered >= config.batchSize) persistBatchLocked()
        }
    }

    override fun flush() {
        synchronized(lock) { if (buffered > 0) persistBatchLocked() }
    }

    override fun close() = flush()

    private fun persistBatchLocked() {
        val payload = buffer.toString()
        buffer.setLength(0)
        buffered = 0
        if (payload.isEmpty()) return

        val batch = File(queueDir, "batch-${System.currentTimeMillis()}-${System.nanoTime()}.ndjson")
        runCatching { batch.writeText(payload) }
        enforceQuota()
        scheduleUpload()
    }

    /** Drop oldest batch files until the queue is within [Config.maxQueueBytes]. */
    private fun enforceQuota() {
        val files = queueDir.listFiles()?.sortedBy { it.name } ?: return
        var total = files.sumOf { it.length() }
        var i = 0
        while (total > config.maxQueueBytes && i < files.size) {
            total -= files[i].length()
            files[i].delete()
            i++
        }
    }

    private fun scheduleUpload() {
        val data = Data.Builder()
            .putString(KEY_ENDPOINT, config.endpoint)
            .putStringArray(KEY_HEADER_NAMES, config.headers.keys.toTypedArray())
            .putStringArray(KEY_HEADER_VALUES, config.headers.values.toTypedArray())
            .build()

        val request = OneTimeWorkRequestBuilder<ReplayUploadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(data)
            .build()

        // KEEP: if an upload is already pending/running, let it drain the whole dir.
        workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val QUEUE_DIR = "openlog/queue"
        const val UNIQUE_WORK = "openlog-replay-upload"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_HEADER_NAMES = "headerNames"
        const val KEY_HEADER_VALUES = "headerValues"
    }
}
