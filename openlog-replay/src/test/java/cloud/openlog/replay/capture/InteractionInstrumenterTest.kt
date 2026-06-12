package cloud.openlog.replay.capture

import android.app.Activity
import android.widget.EditText
import android.widget.FrameLayout
import cloud.openlog.replay.mask.MaskPolicy
import cloud.openlog.replay.wire.Event
import cloud.openlog.replay.wire.InputData
import cloud.openlog.replay.wire.OpenLogJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Real-time input capture (rrweb source 5): text changes emit masked input events. */
@RunWith(RobolectricTestRunner::class)
class InteractionInstrumenterTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun decode(event: Event): InputData =
        OpenLogJson.decodeFromJsonElement(InputData.serializer(), event.data)

    private fun setup(
        policy: MaskPolicy,
        captureInputs: Boolean = true,
    ): Pair<EditText, MutableList<Event>> {
        val events = mutableListOf<Event>()
        val edit = EditText(activity)
        val root = FrameLayout(activity).apply { addView(edit) }
        activity.setContentView(root)
        val instrumenter = InteractionInstrumenter(
            density = 1f,
            policy = policy,
            captureScrolls = false,
            captureInputs = captureInputs,
            scrollThrottleMs = 100L,
            emitOffThread = { events.add(it) },
        )
        instrumenter.onDraw(root) // scans the tree and attaches the watcher
        return edit to events
    }

    @Test
    fun textChangeEmitsMaskedInputByDefault() {
        val (edit, events) = setup(MaskPolicy(maskAllText = true))
        edit.setText("hello")
        assertEquals(1, events.size)
        val data = decode(events.first())
        assertEquals(System.identityHashCode(edit), data.id)
        assertEquals("*****", data.text)
    }

    @Test
    fun textChangeEmitsRealValueWhenUnmasked() {
        val (edit, events) = setup(MaskPolicy(maskAllText = false))
        edit.setText("hello")
        assertEquals("hello", decode(events.first()).text)
    }

    @Test
    fun noInputEventsWhenInputCaptureDisabled() {
        val (edit, events) = setup(MaskPolicy(maskAllText = true), captureInputs = false)
        edit.setText("hello")
        assertTrue("disabled input capture emits nothing", events.isEmpty())
    }
}
