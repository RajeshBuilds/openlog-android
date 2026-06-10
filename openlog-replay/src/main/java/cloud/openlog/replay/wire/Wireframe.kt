package cloud.openlog.replay.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

/** `MobileNodeType` enum values (Part 2.3 / schema). `screenshot` is deliberately omitted — banking is wireframe-only (golden rule #2). */
object MobileNodeType {
    const val TEXT = "text"
    const val IMAGE = "image"
    const val RECTANGLE = "rectangle"
    const val PLACEHOLDER = "placeholder"
    const val WEB_VIEW = "web_view"
    const val INPUT = "input"
    const val DIV = "div"
    const val RADIO_GROUP = "radio_group"
    const val STATUS_BAR = "status_bar"
    const val NAVIGATION_BAR = "navigation_bar"
}

/**
 * `inputType` discriminator for `type == "input"` wireframes. The schema requires
 * `inputType` (and `disabled`) on every input component; checkbox/radio/toggle also
 * require `checked`.
 */
object InputType {
    const val TEXT = "text"
    const val PASSWORD = "password"
    const val EMAIL = "email"
    const val NUMBER = "number"
    const val SEARCH = "search"
    const val TEL = "tel"
    const val URL = "url"
    const val TEXT_AREA = "text_area"
    const val SELECT = "select"
    const val BUTTON = "button"
    const val CHECKBOX = "checkbox"
    const val RADIO = "radio"
    const val TOGGLE = "toggle"
    const val PROGRESS = "progress"
}

/**
 * A single wireframe node (Part 2.3). One flat data class covers every variant;
 * fields irrelevant to a given `type` are left null and omitted at serialization
 * (see [OpenLogJson]). This keeps each emitted node within its schema variant
 * (every wireframe definition has `additionalProperties: false`).
 *
 * Invariants:
 *  - [id] MUST be stable across frames for the same View (use `View.identityHashCode`).
 *  - All geometry ([x], [y], [width], [height]) is density-normalized integers.
 *  - [value] is a [JsonElement] because the schema types it as `string` for inputs
 *    but `number` for `progress`.
 *
 * [parentId] is `@Transient` — it exists only for the diff (T6) and is never
 * serialized to the wire.
 *
 * [idName] is the Android resource-id name of the source view (e.g.
 * `"balanceValue"`), or null when the view has no id. It is an OpenLog extension
 * to the rr-mobile schema (the vendored `rr-mobile-schema.json` declares it on
 * every wireframe) that makes a recording traceable back to the XML.
 *
 * [className] is the source view's platform class name (e.g. `"MaterialButton"`).
 * Also declared in the vendored schema, but it is a DEBUG aid: only populated when
 * `OpenLog.Config.debugClassNames` is on (raw-tree-style fidelity for debugging
 * capture issues). Off by default to keep production volume down.
 */
@Serializable
data class Wireframe(
    val id: Int,
    val idName: String? = null,
    val className: String? = null,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int,
    val height: Int,
    val type: String,
    val inputType: String? = null,
    val text: String? = null,
    val value: JsonElement? = null,
    val label: String? = null,
    val base64: String? = null,
    val url: String? = null,
    val checked: Boolean? = null,
    val disabled: Boolean? = null,
    val options: List<String>? = null,
    val max: Int? = null,
    val style: Style? = null,
    val childWireframes: List<Wireframe>? = null,
    @Transient val parentId: Int? = null,
) {
    /** A copy with children removed — used by the diff to compare a node's own props. */
    fun withoutChildren(): Wireframe = if (childWireframes == null) this else copy(childWireframes = null)
}
