package cloud.openlog.replay.mask

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.TextView

/**
 * Mask-by-default policy (SPEC.md T5). Precedence: **unmask > force-mask > default**.
 * Passwords are ALWAYS masked regardless of tags.
 *
 *  - A view is *unmasked* only if it (or an ancestor) carries the
 *    [NO_MASK] tag/content-description.
 *  - Otherwise text and images are masked when [maskAllText]/[maskAllImages]
 *    are on (the default) or the view is tagged [NO_CAPTURE] or [MASK].
 *
 * [MASK] (`openlog-mask`) is the opt-in counterpart to mask-by-default: tag a
 * view with it to force-mask that view even when the global defaults are turned
 * off (e.g. an app running unmasked in debug that still wants to hide a balance).
 *
 * Masked text is replaced at the source with asterisks; masked images omit their
 * base64 so the player renders a placeholder. The wire format therefore never
 * carries unmasked PII (golden rule #1, invariant 2.4.3).
 */
class MaskPolicy(
    val maskAllText: Boolean = true,
    val maskAllImages: Boolean = true,
) {
    fun isUnmasked(v: View): Boolean = v.hasTag(NO_MASK)

    /**
     * Whether a view (and its whole subtree) must be excluded from capture entirely
     * — not emitted as a wireframe at all. Use [IGNORE] for content that shouldn't
     * even be a placeholder: video surfaces, huge dynamic text, or anything that
     * would otherwise cause a self-capture feedback loop (e.g. a screen that renders
     * the recording itself). Distinct from masking, which still emits a node.
     */
    fun isIgnored(v: View): Boolean = v.hasTag(IGNORE)

    /** Whether a view's text content must be masked. */
    fun maskText(v: View, ancestorUnmasked: Boolean): Boolean {
        if (ancestorUnmasked || isUnmasked(v)) return false
        if (v is TextView && v.isPasswordInput()) return true
        return v.hasTag(NO_CAPTURE) || v.hasTag(MASK) || maskAllText
    }

    /** Whether a view's image content must be masked (base64 omitted). */
    fun maskImage(v: View, ancestorUnmasked: Boolean): Boolean =
        !(ancestorUnmasked || isUnmasked(v)) && (v.hasTag(NO_CAPTURE) || v.hasTag(MASK) || maskAllImages)

    companion object {
        const val NO_CAPTURE = "openlog-no-capture"
        const val NO_MASK = "openlog-no-mask"
        const val MASK = "openlog-mask"
        const val IGNORE = "openlog-ignore"
    }
}

/** A tag matches if either the String `tag` or the contentDescription contains [label]. */
internal fun View.hasTag(label: String): Boolean {
    val tagMatch = (tag as? String)?.contains(label, ignoreCase = true) == true
    val descMatch = contentDescription?.contains(label, ignoreCase = true) == true
    return tagMatch || descMatch
}

/** True when this TextView holds a password (by input type or transformation method). */
internal fun TextView.isPasswordInput(): Boolean {
    if (transformationMethod is PasswordTransformationMethod) return true
    val klass = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (klass) {
        InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}

/** Replace a string with asterisks of the same visible length (min length 1 when non-empty). */
internal fun maskedOf(text: CharSequence?): String {
    val len = text?.length ?: 0
    return "*".repeat(len)
}
