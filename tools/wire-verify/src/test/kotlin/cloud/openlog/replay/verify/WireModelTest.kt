package cloud.openlog.replay.verify

import cloud.openlog.replay.wire.Event
import cloud.openlog.replay.wire.FullSnapshotData
import cloud.openlog.replay.wire.OpenLogJson
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Test

/** T0 acceptance: round-trips a hand-written FullSnapshot byte-identical (minus key order) and passes the schema. */
class WireModelTest {

    private val mapper = ObjectMapper()

    private fun assertJsonEquals(a: String, b: String) {
        assertEquals(mapper.readTree(a), mapper.readTree(b))
    }

    @Test
    fun fullSnapshotRoundTripsAndValidates() {
        // A hand-written FullSnapshot `data` covering several wireframe variants.
        val handData = """
        {
          "wireframes": [
            {
              "id": 5, "x": 0, "y": 0, "width": 412, "height": 915, "type": "div",
              "style": { "backgroundColor": "#ffffff" },
              "childWireframes": [
                { "id": 10, "x": 0, "y": 0, "width": 412, "height": 24, "type": "status_bar" },
                { "id": 11, "x": 16, "y": 40, "width": 380, "height": 28, "type": "text",
                  "text": "Account balance", "style": { "color": "#111111", "fontSize": 16 } },
                { "id": 12, "x": 16, "y": 80, "width": 380, "height": 48, "type": "input",
                  "inputType": "password", "disabled": false, "value": "******" },
                { "id": 13, "x": 16, "y": 140, "width": 380, "height": 48, "type": "input",
                  "inputType": "checkbox", "disabled": false, "checked": true, "label": "Remember me" },
                { "id": 14, "x": 16, "y": 200, "width": 380, "height": 48, "type": "input",
                  "inputType": "button", "disabled": false, "value": "Continue" },
                { "id": 15, "x": 16, "y": 260, "width": 380, "height": 8, "type": "input",
                  "inputType": "progress", "disabled": false, "value": 30, "max": 100 },
                { "id": 16, "x": 16, "y": 300, "width": 380, "height": 200, "type": "image" },
                { "id": 17, "x": 0, "y": 891, "width": 412, "height": 24, "type": "navigation_bar" }
              ]
            }
          ],
          "initialOffset": { "top": 0, "left": 0 }
        }
        """.trimIndent()

        // Decode into the typed model, then re-encode — must be byte-identical (modulo key order).
        val decoded = OpenLogJson.decodeFromString(FullSnapshotData.serializer(), handData)
        val reencoded = OpenLogJson.encodeToString(FullSnapshotData.serializer(), decoded)
        assertJsonEquals(handData, reencoded)

        // And the full event must pass the canonical schema.
        val event = Event(2, 1719000000001L, OpenLogJson.encodeToJsonElement(FullSnapshotData.serializer(), decoded))
        SchemaValidator.assertValid(OpenLogJson.encodeToString(Event.serializer(), event))
    }
}
