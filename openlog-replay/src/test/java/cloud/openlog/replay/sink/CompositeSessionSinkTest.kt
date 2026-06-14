package cloud.openlog.replay.sink

import cloud.openlog.replay.wire.Event
import cloud.openlog.replay.wire.Events
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fan-out to multiple sinks, with one sink's failure isolated from the others. */
class CompositeSessionSinkTest {

    private class RecordingSink : SessionSink {
        val written = mutableListOf<Event>()
        var flushed = false
        var closed = false
        override fun write(event: Event) { written.add(event) }
        override fun flush() { flushed = true }
        override fun close() { closed = true }
    }

    private fun event(ts: Long) = Events.meta(ts, "Screen", 411, 915)

    @Test
    fun fansOutWriteFlushCloseToEverySink() {
        val a = RecordingSink()
        val b = RecordingSink()
        val composite = CompositeSessionSink(listOf(a, b))

        composite.write(event(1))
        composite.write(event(2))
        composite.flush()
        composite.close()

        assertEquals(listOf(1L, 2L), a.written.map { it.timestamp })
        assertEquals(listOf(1L, 2L), b.written.map { it.timestamp })
        assertTrue(a.flushed && b.flushed)
        assertTrue(a.closed && b.closed)
    }

    @Test
    fun oneSinkThrowingDoesNotStopTheOthers() {
        val exploding = object : SessionSink {
            override fun write(event: Event) = throw RuntimeException("boom")
            override fun flush() = throw RuntimeException("boom")
            override fun close() = throw RuntimeException("boom")
        }
        val good = RecordingSink()
        val composite = CompositeSessionSink(listOf(exploding, good))

        // None of these may propagate; the healthy sink still gets everything.
        composite.write(event(7))
        composite.flush()
        composite.close()

        assertEquals(listOf(7L), good.written.map { it.timestamp })
        assertTrue(good.flushed)
        assertTrue(good.closed)
    }
}
