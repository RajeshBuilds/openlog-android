package cloud.openlog.replay.sink

import cloud.openlog.replay.wire.Event
import cloud.openlog.replay.wire.toNdjsonLine
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Writes the session as newline-delimited JSON to `<dir>/<sessionId>.ndjson`
 * (SPEC.md T8). This sink is what the Part 5 validation gate consumes.
 *
 * Writes are serialized on a lock; in practice all events arrive on the single
 * capture thread, so contention is negligible.
 */
class FileSessionSink(
    dir: File,
    sessionId: String,
) : SessionSink {

    val file: File = File(dir.apply { mkdirs() }, "$sessionId.ndjson")

    private val lock = Any()
    private var writer: BufferedWriter? = null

    private fun writerLocked(): BufferedWriter =
        writer ?: BufferedWriter(FileWriter(file, /* append = */ true)).also { writer = it }

    override fun write(event: Event) {
        synchronized(lock) {
            val w = writerLocked()
            w.write(event.toNdjsonLine())
            w.write("\n")
        }
    }

    override fun flush() {
        synchronized(lock) { writer?.flush() }
    }

    override fun close() {
        synchronized(lock) {
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Throwable) {
            } finally {
                writer = null
            }
        }
    }
}
