package cloud.openlog.replay.sink

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cloud.openlog.replay.wire.Event
import cloud.openlog.replay.wire.toNdjsonLine
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Batching, disk-persisting HTTP sink implementing the OpenLog ingest contract
 * (docs/INGEST_API_SPEC.md). Reimplemented clean-room (golden rule #8):
 *
 *  - events buffer in memory until **either** [Config.maxBatchEvents] **or**
 *    [Config.maxBatchBytes] (~3 MiB, under the server's 3.5 MiB hard cap) is
 *    reached, then a batch file is written to the session's queue dir;
 *  - each batch carries a monotonic, never-reused `X-OpenLog-Batch-Seq` encoded
 *    in its filename — the ingest idempotency key that makes retries safe;
 *  - a per-session `session.json` records the device/auth/endpoint metadata so
 *    [ReplayUploadWorker] can build requests after this process is gone;
 *  - [ReplayUploadWorker] is enqueued (unique work, NETWORK_CONNECTED constraint,
 *    exponential backoff) to drain the queue, so an offline→online transition
 *    uploads automatically;
 *  - when the on-disk queue exceeds [Config.maxQueueBytes] the oldest batches are
 *    dropped (drop-oldest, bounded footprint).
 *
 * All disk work runs on the single capture thread that calls [write]/[flush]
 * (golden rule #6); uploads run off-process in WorkManager.
 */
class HttpSessionSink(
    context: Context,
    private val config: Config,
    private val sessionId: String,
    device: DeviceInfo,
    sdkVersion: String,
) : SessionSink {

    /**
     * @param baseUrl        ingest deployment root, e.g. `https://openlog.sh`
     *                       (the `/api/ingest*` paths are derived from it).
     * @param token          bearer token sent as `Authorization: Bearer <token>`.
     * @param appId          `X-OpenLog-App`; defaults to the host package name.
     * @param maxBatchEvents flush after this many buffered events.
     * @param maxBatchBytes  flush before the pending batch would exceed this many
     *                       bytes. Kept under the server's 3,670,016-byte hard cap
     *                       so direct batches are never rejected with 413. Held to a
     *                       ceiling of [ReplayUploadWorker.MAX_DIRECT_BYTES] — a higher
     *                       value is clamped, so multi-event batches never overflow to
     *                       the large-batch path.
     * @param maxQueueBytes  on-disk cap for undelivered batches; oldest dropped.
     * @param extraHeaders   additional static headers (e.g. routing), applied to
     *                       the direct/commit ingest requests.
     */
    data class Config(
        val baseUrl: String,
        val token: String,
        val appId: String? = null,
        val maxBatchEvents: Int = 50,
        val maxBatchBytes: Long = 3L * 1024 * 1024,
        val maxQueueBytes: Long = 5L * 1024 * 1024,
        val extraHeaders: Map<String, String> = emptyMap(),
    ) {
        init {
            // Fail fast at integration time. A blank token would otherwise 401 on
            // every upload, and the worker drops 4xx batches — so the data loss
            // would be silent and only visible server-side, long after init.
            require(token.isNotBlank()) {
                "OpenLog HTTP upload requires a non-blank ingest token (Config.token); " +
                    "the server rejects requests without it (401)."
            }
            require(baseUrl.isNotBlank()) {
                "OpenLog HTTP upload requires a non-blank baseUrl (Config.baseUrl), " +
                    "e.g. \"https://openlog.sh\"."
            }
        }
    }

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    private val queueRoot = File(appContext.filesDir, QUEUE_DIR)
    private val sessionDir = File(queueRoot, sessionId).apply { mkdirs() }

    /**
     * Effective size-flush threshold, clamped to [ReplayUploadWorker.MAX_DIRECT_BYTES].
     * This guarantees a *multi-event* batch is always under the direct-upload cap, so
     * it can never be routed to the large-batch path — leaving a single oversized
     * event (one unsplittable NDJSON line) as the only thing that can exceed it. A
     * host that sets [Config.maxBatchBytes] higher is quietly held to this ceiling.
     */
    private val maxBatchBytes: Long =
        config.maxBatchBytes.coerceAtMost(ReplayUploadWorker.MAX_DIRECT_BYTES.toLong())

    private val lock = Any()
    private val buffer = StringBuilder()
    private var bufferedEvents = 0
    private var bufferedBytes = 0L

    /**
     * Next batch sequence. Recovered from any batch files already on disk so a
     * sink rebuilt for an existing session never reuses a seq (the ingest
     * idempotency key must be unique per session, forever).
     */
    private val nextSeq = AtomicInteger(recoverNextSeq())

    init {
        // Persist the upload metadata once; the worker reads it back per session.
        val appId = config.appId ?: appContext.packageName
        val meta = SessionMeta(
            sessionId = sessionId,
            baseUrl = config.baseUrl.trimEnd('/'),
            token = config.token,
            appId = appId,
            sdkVersion = sdkVersion,
            device = device,
            extraHeaders = config.extraHeaders,
        )
        runCatching { File(sessionDir, META_FILE).writeText(SinkJson.encodeToString(SessionMeta.serializer(), meta)) }
    }

    override fun write(event: Event) {
        val line = event.toNdjsonLine()
        val bytes = line.toByteArray(Charsets.UTF_8).size + 1L // +1 for '\n'
        synchronized(lock) {
            // Flush first when adding this event would push the batch over the size
            // cap, so a normal batch stays just under it (a lone oversized event
            // still lands in its own batch, routed to the large-batch path).
            if (bufferedEvents > 0 && bufferedBytes + bytes > maxBatchBytes) persistBatchLocked()
            buffer.append(line).append('\n')
            bufferedEvents++
            bufferedBytes += bytes
            if (bufferedEvents >= config.maxBatchEvents || bufferedBytes >= maxBatchBytes) {
                persistBatchLocked()
            }
        }
    }

    override fun flush() {
        synchronized(lock) { if (bufferedEvents > 0) persistBatchLocked() }
    }

    override fun close() = flush()

    private fun persistBatchLocked() {
        val payload = buffer.toString()
        buffer.setLength(0)
        bufferedEvents = 0
        bufferedBytes = 0
        if (payload.isEmpty()) return

        val seq = nextSeq.getAndIncrement()
        val batch = File(sessionDir, batchName(seq))
        runCatching { batch.writeText(payload) }
        enforceQuota()
        scheduleUpload()
    }

    /** Drop oldest batch files (across all sessions) until within [Config.maxQueueBytes]. */
    private fun enforceQuota() {
        val batches = queueRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith(BATCH_PREFIX) }
            .sortedBy { it.lastModified() }
            .toList()
        var total = batches.sumOf { it.length() }
        var i = 0
        while (total > config.maxQueueBytes && i < batches.size) {
            total -= batches[i].length()
            batches[i].delete()
            i++
        }
    }

    /** Highest existing seq in this session dir + 1 (1-based), so seqs never repeat. */
    private fun recoverNextSeq(): Int {
        val maxSeq = sessionDir.listFiles()
            ?.mapNotNull { seqOf(it.name) }
            ?.maxOrNull()
            ?: 0
        return maxSeq + 1
    }

    private fun scheduleUpload() {
        val request = OneTimeWorkRequestBuilder<ReplayUploadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        // APPEND_OR_REPLACE: chain after any in-flight drain so a batch flushed
        // while the worker is mid-run still gets its own pass (KEEP would drop this
        // enqueue and could strand the batch until the next flush). When idle, this
        // just runs immediately.
        workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val QUEUE_DIR = "openlog/queue"
        const val UNIQUE_WORK = "openlog-replay-upload"
        const val META_FILE = "session.json"
        const val BATCH_PREFIX = "batch-"

        /** 1-based, zero-padded so lexical sort == seq order. */
        fun batchName(seq: Int): String = "%s%07d.ndjson".format(BATCH_PREFIX, seq)

        /** Parse the seq out of a batch filename, or null if it isn't one. */
        fun seqOf(name: String): Int? {
            if (!name.startsWith(BATCH_PREFIX) || !name.endsWith(".ndjson")) return null
            return name.removePrefix(BATCH_PREFIX).removeSuffix(".ndjson").toIntOrNull()
        }
    }
}
