package cloud.openlog.replay.graph

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cloud.openlog.replay.diff.SnapshotDiff
import cloud.openlog.replay.mask.MaskPolicy
import cloud.openlog.replay.wire.InputType as WireInputType
import cloud.openlog.replay.wire.MobileNodeType
import cloud.openlog.replay.wire.Wireframe
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** T4/T5 acceptance: a known screen produces the expected wireframe tree; sensitive content is masked; invisible views are absent. */
@RunWith(RobolectricTestRunner::class)
class ViewScreenGraphProviderTest {

    private val provider = ViewScreenGraphProvider()

    private fun flatten(root: Wireframe): List<Wireframe> =
        SnapshotDiff.flatten(listOf(root)).values.map { it.node }

    private fun buildAndWalk(maskAllText: Boolean = true, maskAllImages: Boolean = true): List<Wireframe> {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)

            addView(TextView(activity).apply {
                id = ID_BALANCE
                text = "Balance: \$1,234.56"
            })
            addView(EditText(activity).apply {
                id = ID_PASSWORD
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText("hunter2")
            })
            addView(CheckBox(activity).apply {
                id = ID_CHECK
                isChecked = true
                text = "Agree"
            })
            addView(ImageView(activity).apply {
                id = ID_IMAGE
                setImageDrawable(ColorDrawable(Color.RED))
                layoutParams = ViewGroup.LayoutParams(100, 100)
            })
            addView(TextView(activity).apply {
                id = ID_PUBLIC
                tag = MaskPolicy.NO_MASK
                text = "Public label"
            })
            addView(TextView(activity).apply {
                id = ID_GONE
                text = "Invisible"
                visibility = View.GONE
            })
        }

        activity.setContentView(root)
        controller.start().resume().visible()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 1920)

        val tree = provider.snapshot(root, density = 1f, policy = MaskPolicy(maskAllText, maskAllImages))
        assertNotNull(tree)
        return flatten(tree!!)
    }

    @Test
    fun masksTextPasswordAndImageByDefault() {
        val nodes = buildAndWalk()

        val balance = nodes.first { it.type == MobileNodeType.TEXT && it.text?.contains("*") == true && it.text != "Public label" }
        assertTrue("balance must be all asterisks", balance.text!!.all { it == '*' })

        val password = nodes.first { it.type == MobileNodeType.INPUT && it.inputType == WireInputType.PASSWORD }
        assertTrue("password value must be all asterisks", password.value!!.jsonPrimitive.content.all { it == '*' })

        val checkbox = nodes.first { it.inputType == WireInputType.CHECKBOX }
        assertEquals(true, checkbox.checked)

        val image = nodes.first { it.type == MobileNodeType.IMAGE }
        assertNull("masked image must omit base64", image.base64)

        val publicLabel = nodes.first { it.text == "Public label" }
        assertNotNull("openlog-no-mask view stays unmasked", publicLabel)
    }

    @Test
    fun resourceIdNameIsCaptured() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val root = LinearLayout(activity).apply {
            // no id -> idName must be null
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            addView(TextView(activity).apply { id = android.R.id.text1; text = "hi" })
        }
        activity.setContentView(root)
        controller.start().resume().visible()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 200, 200)
        val policy = MaskPolicy(maskAllText = false, maskAllImages = false)

        val tree = ViewScreenGraphProvider().snapshot(root, 1f, policy)!!
        val nodes = flatten(tree)
        assertEquals("text1", nodes.first { it.type == MobileNodeType.TEXT }.idName)
        assertNull("a view without an id has null idName", nodes.first { it.type == MobileNodeType.DIV }.idName)
        assertTrue("className omitted by default", nodes.all { it.className == null })

        val debugTree = ViewScreenGraphProvider(includeClassNames = true).snapshot(root, 1f, policy)!!
        val debugNodes = flatten(debugTree)
        assertEquals("TextView", debugNodes.first { it.type == MobileNodeType.TEXT }.className)
        assertEquals("LinearLayout", debugNodes.first { it.type == MobileNodeType.DIV }.className)
    }

    @Test
    fun invisibleViewsAreAbsent() {
        val nodes = buildAndWalk()
        assertTrue("GONE view must not be captured", nodes.none { it.text == "Invisible" })
    }

    @Test
    fun geometryIsDensityNormalizedIntegers() {
        val nodes = buildAndWalk()
        // density = 1f, so values equal raw px but the path through ÷density must yield ints.
        val root = nodes.first { it.type == MobileNodeType.DIV }
        assertEquals(1080, root.width)
        assertTrue(nodes.all { it.height >= 0 && it.width >= 0 })
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val ID_BALANCE = 1001
        private const val ID_PASSWORD = 1002
        private const val ID_CHECK = 1003
        private const val ID_IMAGE = 1004
        private const val ID_PUBLIC = 1005
        private const val ID_GONE = 1006
    }
}
