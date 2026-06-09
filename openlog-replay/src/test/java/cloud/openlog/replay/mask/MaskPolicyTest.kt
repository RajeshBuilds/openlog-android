package cloud.openlog.replay.mask

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** T5 acceptance: mask-by-default with unmask > force-mask > default precedence; passwords always masked. */
@RunWith(RobolectricTestRunner::class)
class MaskPolicyTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).create().get()

    @Test
    fun passwordAlwaysMaskedEvenWhenTextMaskingDisabled() {
        val policy = MaskPolicy(maskAllText = false)
        val edit = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        assertTrue(policy.maskText(edit, ancestorUnmasked = false))
    }

    @Test
    fun noMaskTagUnmasks() {
        val policy = MaskPolicy(maskAllText = true)
        val tv = TextView(activity).apply { tag = MaskPolicy.NO_MASK }
        assertTrue(policy.isUnmasked(tv))
        assertFalse(policy.maskText(tv, ancestorUnmasked = false))
    }

    @Test
    fun noCaptureForcesMaskWhenDefaultOff() {
        val policy = MaskPolicy(maskAllText = false)
        val tv = TextView(activity).apply { contentDescription = "openlog-no-capture balance" }
        assertTrue(policy.maskText(tv, ancestorUnmasked = false))
    }

    @Test
    fun defaultMasksText() {
        val policy = MaskPolicy(maskAllText = true)
        val tv = TextView(activity)
        assertTrue(policy.maskText(tv, ancestorUnmasked = false))
    }

    @Test
    fun ancestorUnmaskWins() {
        val policy = MaskPolicy(maskAllText = true)
        val tv = TextView(activity)
        assertFalse(policy.maskText(tv, ancestorUnmasked = true))
        assertFalse(policy.maskImage(tv, ancestorUnmasked = true))
    }

    @Test
    fun maskedOfReplacesWithAsterisks() {
        assertEquals("*****", maskedOf("12345"))
        assertEquals("", maskedOf(""))
        assertEquals("", maskedOf(null))
    }
}
