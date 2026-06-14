package cloud.openlog.replay.sink

import cloud.openlog.replay.wire.Event

/**
 * Fans out every event to several [SessionSink]s at once — e.g. a local NDJSON
 * file AND the HTTP upload sink, so a session can be kept offline and ingested
 * simultaneously.
 *
 * Each delegate call is isolated in its own [runCatching]: one sink throwing must
 * never stop the others from receiving the event (or being flushed/closed). The
 * capture engine already swallows errors at its boundary, but that guards the call
 * as a whole — without per-sink isolation a throw in the first sink would skip the
 * rest.
 */
class CompositeSessionSink(val sinks: List<SessionSink>) : SessionSink {

    override fun write(event: Event) {
        sinks.forEach { runCatching { it.write(event) } }
    }

    override fun flush() {
        sinks.forEach { runCatching { it.flush() } }
    }

    override fun close() {
        sinks.forEach { runCatching { it.close() } }
    }
}
