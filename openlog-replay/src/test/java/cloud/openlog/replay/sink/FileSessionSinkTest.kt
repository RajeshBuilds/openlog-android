package cloud.openlog.replay.sink

import cloud.openlog.replay.wire.Events
import cloud.openlog.replay.wire.OpenLogJson
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-disk NDJSON must always end on a complete event, even read mid-session (no close). */
class FileSessionSinkTest {

    @Test
    fun fileEndsOnCompleteLineMidSession() {
        val dir = Files.createTempDirectory("openlog-sink").toFile()
        val sink = FileSessionSink(dir, "session")

        sink.write(Events.meta(1, "ScreenA", 411, 915))
        sink.write(Events.screenEnter(2, "ScreenA"))
        sink.write(Events.touch(3, 7, 42, 10, 20))

        // Read WITHOUT closing — simulates exporting while recording is active.
        val text = sink.file.readText()

        // File must end with a newline (no dangling partial line).
        assertTrue("file must end on a line boundary", text.endsWith("\n"))

        val lines = text.split("\n").filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        // Every line must be complete, valid JSON.
        lines.forEach { OpenLogJson.parseToJsonElement(it) }

        sink.close()
    }
}
