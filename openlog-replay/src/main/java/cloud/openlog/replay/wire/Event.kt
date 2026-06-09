package cloud.openlog.replay.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The rr-mobile wire contract (SPEC.md Part 2) — the single source of truth.
 *
 * Every emitted event is a single NDJSON line of the shape
 * `{ "type": <int>, "timestamp": <epoch-ms>, "data": { ... } }`. Each event MUST
 * validate against `rr-mobile-schema.json` (the canonical PostHog schema). The
 * pure-JVM harness in `tools/wire-verify` enforces this in CI.
 *
 * This whole `wire` package is intentionally free of any Android dependency so it
 * can be compiled and validated on a plain JVM.
 */

/** Event type numeric enums — do not change (Part 2.1). */
object EventType {
    const val FULL = 2
    const val INCREMENTAL = 3
    const val META = 4
    const val CUSTOM = 5
}

/** Incremental snapshot source enums (Part 2.1). */
object Source {
    const val MUTATION = 0
    const val MOUSE = 2
}

/** Touch interaction enums (Part 2.1). `POINTER` is the touch pointer type. */
object Touch {
    const val START = 7
    const val END = 9
    const val POINTER = 2
}

/**
 * A single recording event. `data` is kept as a [JsonElement] so that every event
 * shape (Meta / FullSnapshot / Incremental / Custom) can be serialized uniformly
 * into one NDJSON stream. Builders in [Events] produce these from typed payloads.
 */
@Serializable
data class Event(
    val type: Int,
    val timestamp: Long,
    val data: JsonElement,
)

/**
 * The canonical JSON encoder for the wire contract.
 *
 *  - [Json.encodeDefaults] = true so positional defaults like `x`/`y` (= 0) are
 *    still emitted (the schema permits them and the player expects geometry).
 *  - [Json.explicitNulls] = false so optional/absent fields are omitted entirely
 *    rather than serialized as `null`. This is what keeps `div`/`text` wireframes
 *    from carrying input-only keys, and lets mutation builders drop empty arrays.
 */
val OpenLogJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

/** Serialize an [Event] to a single NDJSON line (no trailing newline). */
fun Event.toNdjsonLine(): String = OpenLogJson.encodeToString(Event.serializer(), this)
