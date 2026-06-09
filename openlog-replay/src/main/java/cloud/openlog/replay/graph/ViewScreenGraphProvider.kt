package cloud.openlog.replay.graph

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RatingBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import cloud.openlog.replay.mask.MaskPolicy
import cloud.openlog.replay.mask.maskedOf
import cloud.openlog.replay.wire.InputType
import cloud.openlog.replay.wire.MobileNodeType
import cloud.openlog.replay.wire.Style
import cloud.openlog.replay.wire.Wireframe
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonPrimitive

/**
 * Walks an Android view hierarchy into a wireframe tree (SPEC.md T4). Mirrors the
 * mechanism of PostHog's `toWireframe` but is a clean-room reimplementation.
 *
 * Hard requirements honoured here (Part 4):
 *  - every off-thread view access is guarded by attach/visibility checks and a
 *    try/catch so a recycled/detached view can never crash the capture thread;
 *  - `id` is `System.identityHashCode(view)` — stable across frames (golden rule #4);
 *  - all geometry is divided by [density] to density-normalized integers.
 *
 * Each wireframe also carries [Wireframe.idName] — the source view's Android
 * resource-id name (e.g. `"balanceValue"`) — when the view has an id, so a
 * recording can be traced back to the XML.
 */
class ViewScreenGraphProvider : ScreenGraphProvider {

    override fun snapshot(root: View, density: Float, policy: MaskPolicy): Wireframe? =
        root.toWireframe(density, policy, parentId = null, ancestorUnmasked = false)

    private fun View.toWireframe(
        density: Float,
        policy: MaskPolicy,
        parentId: Int?,
        ancestorUnmasked: Boolean,
    ): Wireframe? {
        return try {
            if (!isAttachedToWindow || width == 0 || height == 0 || visibility != View.VISIBLE) return null

            val unmasked = ancestorUnmasked || policy.isUnmasked(this)
            val id = System.identityHashCode(this)
            val idName = resourceEntryName()

            val location = IntArray(2).also { getLocationOnScreen(it) }
            val x = (location[0] / density).roundToInt()
            val y = (location[1] / density).roundToInt()
            val w = (width / density).roundToInt()
            val h = (height / density).roundToInt()

            val style = buildStyle(density)
            val base = Wireframe(
                id = id, idName = idName, x = x, y = y, width = w, height = h,
                type = MobileNodeType.DIV, style = style, parentId = parentId,
            )

            // ----- widget mapping (order matters due to class inheritance) -----
            val mapped: Wireframe = when {
                isSystemBar(android.R.id.statusBarBackground) ->
                    base.copy(type = MobileNodeType.STATUS_BAR)

                isSystemBar(android.R.id.navigationBarBackground) ->
                    base.copy(type = MobileNodeType.NAVIGATION_BAR)

                this is Spinner -> base.copy(
                    type = MobileNodeType.INPUT, inputType = InputType.SELECT, disabled = !isEnabled,
                    value = JsonPrimitive(maskContent(selectedItem?.toString(), policy, ancestorUnmasked)),
                    options = collectSpinnerOptions(policy, ancestorUnmasked),
                )

                this is RadioGroup -> base.copy(type = MobileNodeType.RADIO_GROUP)

                this is WebView -> base.copy(type = MobileNodeType.WEB_VIEW, url = url)

                this is RatingBar -> base.copy(
                    type = MobileNodeType.INPUT, inputType = InputType.PROGRESS, disabled = !isEnabled,
                    value = JsonPrimitive(rating), max = numStars,
                )

                this is ProgressBar -> base.copy(
                    type = MobileNodeType.INPUT, inputType = InputType.PROGRESS, disabled = !isEnabled,
                    value = JsonPrimitive(progress), max = max,
                )

                this is CompoundButton -> base.copy(
                    type = MobileNodeType.INPUT, inputType = compoundInputType(this),
                    disabled = !isEnabled, checked = isChecked,
                    label = maskContent(text, policy, ancestorUnmasked),
                )

                this is Button -> base.copy(
                    type = MobileNodeType.INPUT, inputType = InputType.BUTTON, disabled = !isEnabled,
                    value = JsonPrimitive(maskContent(text, policy, ancestorUnmasked)),
                )

                this is EditText -> base.copy(
                    type = MobileNodeType.INPUT,
                    inputType = if (isMultiline()) InputType.TEXT_AREA else editTextInputType(),
                    disabled = !isEnabled,
                    value = JsonPrimitive(maskContent(text, policy, ancestorUnmasked)),
                )

                this is ImageView -> base.copy(
                    type = MobileNodeType.IMAGE,
                    base64 = if (policy.maskImage(this, ancestorUnmasked)) null else encodeImage(),
                )

                this is TextView -> base.copy(
                    type = MobileNodeType.TEXT,
                    text = maskContent(text, policy, ancestorUnmasked),
                )

                else -> base // plain container / unknown view -> div
            }

            // Recurse only for real containers we did not map to a specific widget.
            if (this is ViewGroup &&
                (mapped.type == MobileNodeType.DIV || mapped.type == MobileNodeType.RADIO_GROUP)
            ) {
                val children = ArrayList<Wireframe>(childCount)
                for (i in 0 until childCount) {
                    val child = getChildAt(i) ?: continue
                    child.toWireframe(density, policy, parentId = id, ancestorUnmasked = unmasked)
                        ?.let { children.add(it) }
                }
                if (children.isNotEmpty()) mapped.copy(childWireframes = children) else mapped
            } else {
                mapped
            }
        } catch (t: Throwable) {
            // Never let an off-thread view read crash capture (Part 4).
            null
        }
    }

    // ---- helpers -----------------------------------------------------------

    /** The view's Android resource-id entry name (e.g. "balanceValue"), or null when it has no id. */
    private fun View.resourceEntryName(): String? {
        val viewId = id
        if (viewId == View.NO_ID) return null
        return runCatching { resources.getResourceEntryName(viewId) }.getOrNull()
    }

    private fun View.isSystemBar(barId: Int): Boolean = id == barId

    /** Mask [raw] iff the policy says this view's text content must be masked. */
    private fun View.maskContent(raw: CharSequence?, policy: MaskPolicy, ancestorUnmasked: Boolean): String =
        if (policy.maskText(this, ancestorUnmasked)) maskedOf(raw) else (raw?.toString() ?: "")

    private fun Spinner.collectSpinnerOptions(policy: MaskPolicy, ancestorUnmasked: Boolean): List<String>? {
        val adapter = adapter ?: return null
        if (adapter.count == 0) return null
        val mask = policy.maskText(this, ancestorUnmasked)
        return (0 until adapter.count).map {
            val item = adapter.getItem(it)?.toString()
            if (mask) maskedOf(item) else (item ?: "")
        }
    }

    private fun compoundInputType(v: CompoundButton): String = when {
        v is RadioButton -> InputType.RADIO
        v is Switch || v.javaClass.name.contains("Switch", ignoreCase = true) -> InputType.TOGGLE
        v is CheckBox -> InputType.CHECKBOX
        else -> InputType.CHECKBOX // ToggleButton and other compound buttons
    }

    private fun EditText.editTextInputType(): String {
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val klass = inputType and android.text.InputType.TYPE_MASK_CLASS
        return when {
            klass == android.text.InputType.TYPE_CLASS_NUMBER -> InputType.NUMBER
            klass == android.text.InputType.TYPE_CLASS_PHONE -> InputType.TEL
            variation == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> InputType.EMAIL
            variation == android.text.InputType.TYPE_TEXT_VARIATION_URI -> InputType.URL
            variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> InputType.PASSWORD
            else -> InputType.TEXT
        }
    }

    private fun EditText.isMultiline(): Boolean =
        (inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

    /** Build a style from background/text/font/alignment/padding. Null when empty. */
    private fun View.buildStyle(density: Float): Style? {
        var color: String? = null
        var fontSize: Int? = null
        var horizontalAlign: String? = null
        var verticalAlign: String? = null
        if (this is TextView) {
            color = colorHex(currentTextColor)
            fontSize = (textSize / density).roundToInt()
            when (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
                Gravity.CENTER_HORIZONTAL -> horizontalAlign = Style.CENTER
                Gravity.END, Gravity.RIGHT -> horizontalAlign = Style.RIGHT
                Gravity.START, Gravity.LEFT -> horizontalAlign = Style.LEFT
            }
            when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
                Gravity.CENTER_VERTICAL -> verticalAlign = Style.CENTER
                Gravity.BOTTOM -> verticalAlign = Style.BOTTOM
                Gravity.TOP -> verticalAlign = Style.TOP
            }
        }
        val bg = (background as? ColorDrawable)?.let { if (it.alpha == 0) null else colorHex(it.color) }
        val style = Style(
            color = color,
            backgroundColor = bg,
            fontSize = fontSize,
            horizontalAlign = horizontalAlign,
            verticalAlign = verticalAlign,
            paddingLeft = normalize(paddingLeft, density),
            paddingRight = normalize(paddingRight, density),
            paddingTop = normalize(paddingTop, density),
            paddingBottom = normalize(paddingBottom, density),
        )
        return if (style.isEmpty()) null else style
    }

    private fun normalize(px: Int, density: Float): Int? =
        if (px <= 0) null else (px / density).roundToInt()

    private fun colorHex(argb: Int): String {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return String.format("#%02x%02x%02x", r, g, b)
    }

    /** Render an ImageView's drawable to a base64 PNG (only for explicitly unmasked images). */
    private fun ImageView.encodeImage(): String? {
        return try {
            val drawable: Drawable = drawable ?: return null
            val bmpW = if (width > 0) width else drawable.intrinsicWidth
            val bmpH = if (height > 0) height else drawable.intrinsicHeight
            if (bmpW <= 0 || bmpH <= 0) return null
            val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, bmpW, bmpH)
            drawable.draw(canvas)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            null
        }
    }
}
