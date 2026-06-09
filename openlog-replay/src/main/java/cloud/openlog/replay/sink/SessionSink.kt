package cloud.openlog.replay.sink

import cloud.openlog.replay.wire.Event

/** Destination for emitted recording events. Implementations must be thread-safe. */
interface SessionSink {
    /** Append a single event to the session stream. */
    fun write(event: Event)

    /** Flush any buffered events (best-effort). */
    fun flush() {}

    /** Release resources; the session is finished. */
    fun close() {}
}
