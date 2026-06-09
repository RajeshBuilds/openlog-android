package cloud.openlog.replay.wire

import kotlinx.serialization.Serializable

/**
 * `MobileStyles` from the wire contract (Part 2.3). All fields optional; all
 * geometry/size values are density-normalized integers (px ÷ displayDensity).
 *
 * NOTE on `bar`: the SPEC.md Part 2.3 prose mentions `style.bar` for progress
 * widgets, but the canonical `rr-mobile-schema.json` declares `MobileStyles` with
 * `additionalProperties: false` and has no `bar` property. Emitting it would FAIL
 * the schema gate (golden rule #5), so `bar` is intentionally NOT part of the wire
 * `Style`. Progress rendering is driven by `value`/`max` on the wireframe instead.
 *
 * `borderWidth`, `borderRadius`, `fontSize` and the padding fields are typed as
 * `Int` here; the schema accepts number-or-string for them and a number is valid.
 */
@Serializable
data class Style(
    val color: String? = null,
    val backgroundColor: String? = null,
    val backgroundImage: String? = null,
    val backgroundSize: String? = null,
    val borderWidth: Int? = null,
    val borderRadius: Int? = null,
    val borderColor: String? = null,
    val verticalAlign: String? = null,
    val horizontalAlign: String? = null,
    val fontSize: Int? = null,
    val fontFamily: String? = null,
    val paddingLeft: Int? = null,
    val paddingRight: Int? = null,
    val paddingTop: Int? = null,
    val paddingBottom: Int? = null,
) {
    /** True when no style attribute is set, so callers can omit an empty style. */
    fun isEmpty(): Boolean = this == EMPTY

    companion object {
        val EMPTY = Style()

        // backgroundSize values
        const val CONTAIN = "contain"
        const val COVER = "cover"
        const val AUTO = "auto"

        // alignment values
        const val TOP = "top"
        const val BOTTOM = "bottom"
        const val CENTER = "center"
        const val LEFT = "left"
        const val RIGHT = "right"
    }
}
