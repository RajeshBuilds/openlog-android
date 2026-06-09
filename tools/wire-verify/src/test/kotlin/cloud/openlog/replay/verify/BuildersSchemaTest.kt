package cloud.openlog.replay.verify

import cloud.openlog.replay.wire.Event
import cloud.openlog.replay.wire.Events
import cloud.openlog.replay.wire.InputType
import cloud.openlog.replay.wire.MobileNodeType
import cloud.openlog.replay.wire.NodeMutation
import cloud.openlog.replay.wire.NodeRemoved
import cloud.openlog.replay.wire.OpenLogJson
import cloud.openlog.replay.wire.Style
import cloud.openlog.replay.wire.Touch
import cloud.openlog.replay.wire.Wireframe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1 acceptance: every builder validates against the schema; empty mutation arrays are omitted. */
class BuildersSchemaTest {

    private fun line(event: Event) = OpenLogJson.encodeToString(Event.serializer(), event)

    private fun textWireframe(id: Int) = Wireframe(
        id = id, x = 0, y = 0, width = 100, height = 20,
        type = MobileNodeType.TEXT, text = "****", style = Style(color = "#000000"),
    )

    private fun inputWireframe(id: Int) = Wireframe(
        id = id, x = 0, y = 0, width = 100, height = 40,
        type = MobileNodeType.INPUT, inputType = InputType.TEXT,
        disabled = false, value = JsonPrimitive("****"),
    )

    @Test
    fun metaValidates() {
        SchemaValidator.assertValid(line(Events.meta(1, "CheckoutActivity", 412, 915)))
        // href is optional
        SchemaValidator.assertValid(line(Events.meta(1, null, 412, 915)))
    }

    @Test
    fun fullSnapshotValidates() {
        val root = Wireframe(
            id = 5, width = 412, height = 915, type = MobileNodeType.DIV,
            childWireframes = listOf(textWireframe(11), inputWireframe(12)),
        )
        SchemaValidator.assertValid(line(Events.fullSnapshot(2, listOf(root))))
    }

    @Test
    fun mutationValidatesAndOmitsEmptyArrays() {
        val event = Events.mutation(
            timestamp = 3,
            adds = listOf(NodeMutation(5, textWireframe(20))),
            removes = emptyList(),
            updates = listOf(NodeMutation(5, inputWireframe(12))),
        )
        val json = line(event)
        SchemaValidator.assertValid(json)
        assertTrue("adds present", json.contains("\"adds\""))
        assertTrue("updates present", json.contains("\"updates\""))
        assertFalse("empty removes omitted", json.contains("\"removes\""))
    }

    @Test
    fun mutationWithOnlyRemovesValidates() {
        val event = Events.mutation(timestamp = 4, removes = listOf(NodeRemoved(5, 12)))
        val json = line(event)
        SchemaValidator.assertValid(json)
        assertFalse("empty adds omitted", json.contains("\"adds\""))
        assertFalse("empty updates omitted", json.contains("\"updates\""))
        assertTrue("removes present", json.contains("\"removes\""))
    }

    @Test
    fun touchValidates() {
        SchemaValidator.assertValid(line(Events.touch(5, Touch.START, 42, 120, 340)))
        SchemaValidator.assertValid(line(Events.touch(6, Touch.END, 42, 120, 340)))
    }

    @Test
    fun keyboardValidates() {
        SchemaValidator.assertValid(line(Events.keyboardOpen(7, 320)))
        SchemaValidator.assertValid(line(Events.keyboardClosed(8)))
    }

    @Test
    fun customEventWithObjectPayloadValidates() {
        // A generic Custom event's payload is schema-unconstrained.
        val payload = buildJsonObject {
            put("key", "value")
            put("n", 1)
        }
        SchemaValidator.assertValid(line(Events.custom(9, "demo-tag", payload)))
    }

    @Test
    fun allInputVariantsValidate() {
        val variants = listOf(
            Wireframe(id = 1, width = 10, height = 10, type = "input", inputType = "text", disabled = false, value = JsonPrimitive("x")),
            Wireframe(id = 2, width = 10, height = 10, type = "input", inputType = "password", disabled = true, value = JsonPrimitive("***")),
            Wireframe(id = 3, width = 10, height = 10, type = "input", inputType = "email", disabled = false, value = JsonPrimitive("a@b")),
            Wireframe(id = 4, width = 10, height = 10, type = "input", inputType = "number", disabled = false),
            Wireframe(id = 5, width = 10, height = 10, type = "input", inputType = "search", disabled = false),
            Wireframe(id = 6, width = 10, height = 10, type = "input", inputType = "tel", disabled = false),
            Wireframe(id = 7, width = 10, height = 10, type = "input", inputType = "url", disabled = false),
            Wireframe(id = 8, width = 10, height = 10, type = "input", inputType = "text_area", disabled = false, value = JsonPrimitive("multi")),
            Wireframe(id = 9, width = 10, height = 10, type = "input", inputType = "select", disabled = false, value = JsonPrimitive("A"), options = listOf("A", "B")),
            Wireframe(id = 10, width = 10, height = 10, type = "input", inputType = "button", disabled = false, value = JsonPrimitive("OK")),
            Wireframe(id = 11, width = 10, height = 10, type = "input", inputType = "checkbox", disabled = false, checked = true),
            Wireframe(id = 12, width = 10, height = 10, type = "input", inputType = "radio", disabled = false, checked = false, label = "opt"),
            Wireframe(id = 13, width = 10, height = 10, type = "input", inputType = "toggle", disabled = false, checked = true),
            Wireframe(id = 14, width = 10, height = 10, type = "input", inputType = "progress", disabled = false, value = JsonPrimitive(50), max = 100),
        )
        val root = Wireframe(id = 100, width = 412, height = 915, type = "div", childWireframes = variants)
        SchemaValidator.assertValid(line(Events.fullSnapshot(9, listOf(root))))
    }

    @Test
    fun nonInputVariantsValidate() {
        val nodes = listOf(
            Wireframe(id = 1, width = 412, height = 24, type = MobileNodeType.STATUS_BAR),
            Wireframe(id = 2, width = 412, height = 24, type = MobileNodeType.NAVIGATION_BAR),
            Wireframe(id = 3, width = 100, height = 100, type = MobileNodeType.RECTANGLE, style = Style(backgroundColor = "#ff0000")),
            Wireframe(id = 4, width = 100, height = 100, type = MobileNodeType.IMAGE),
            Wireframe(id = 5, width = 100, height = 100, type = MobileNodeType.WEB_VIEW, url = "https://example.com"),
            Wireframe(id = 6, width = 100, height = 100, type = MobileNodeType.PLACEHOLDER, label = "redacted"),
            Wireframe(id = 7, width = 100, height = 100, type = MobileNodeType.RADIO_GROUP),
            Wireframe(id = 8, width = 100, height = 20, type = MobileNodeType.TEXT, text = "hello"),
        )
        val root = Wireframe(id = 100, width = 412, height = 915, type = "div", childWireframes = nodes)
        SchemaValidator.assertValid(line(Events.fullSnapshot(10, listOf(root))))
    }
}
