# OpenLog — Android Capture SDK · Build Spec for Coding Agents

> **Scope:** the **Android session-capture SDK only**. The web replay engine, lean backend, AI/agentic layer, and Jetpack Compose support are explicitly **out of scope for this spec** (separate specs follow).
> **Agents:** written to be handed to **Claude Code, Codex, or Cursor**. Implement Part 2 first (the wire contract), then the Part 3 tasks in order, running the Part 5 validation gate after each.
> **Reference (read-only):** `PostHog/posthog-android` → `posthog-android/src/main/java/com/posthog/android/replay/`. MIT-licensed. **Reimplement against this spec; do not copy files verbatim** (clean-room for the bank's codebase).

---

## PART 1 — HOW TO DRIVE THIS WITH A CODING AGENT

### 1.1 Set up the agent's rules file
Drop this spec in the repo as `SPEC.md`, and paste the **golden rules** below into the agent's rules file:
- **Claude Code** → `CLAUDE.md` (repo root)
- **Codex** → `AGENTS.md` (repo root)
- **Cursor** → `.cursor/rules/openlog.mdc` (or legacy `.cursorrules`)

```text
# OpenLog Android Capture SDK — golden rules (do not violate)
1. Mask by DEFAULT. Text/inputs/images are masked unless explicitly unmasked. Never emit raw PII.
2. NEVER implement screenshot mode. Wireframe capture only (banking).
3. All coordinates/sizes are density-normalized integers (device px / displayDensity).
4. Wireframe `id` MUST be stable across frames (use View.identityHashCode). The diff depends on it.
5. Emit EXACTLY the rr-mobile wire schema in SPEC.md Part 2. Validate every event against
   rr-mobile-schema.json in CI before considering a task done.
6. All capture work runs OFF the main thread. Never block the UI thread.
7. minSdk = 26. Guard for it.
8. Reference PostHog/posthog-android for mechanism, but DO NOT copy code verbatim. Reimplement.
9. Package root: cloud.openlog.replay. Mask tags: "openlog-no-capture", "openlog-no-mask".
10. Implement tasks in SPEC.md order; each task must pass its acceptance criteria before the next.
```

### 1.2 Recommended workflow for the agent
1. Implement **Part 2** (the wire model) and its serialization first; add the schema-conformance test (Part 5.1).
2. Implement **Part 3** tasks **T0 → T8 in order**. Write the unit test alongside each task.
3. After each task, run the Part 5 validation gate. Do not advance on a red gate.
4. Keep `PostHog/posthog-android` checked out locally as a read-only reference (Part 6 map tells the agent which file answers which question).

### 1.3 What "done" means (whole SDK)
Record a multi-screen flow in a host app → the SDK writes a **schema-valid** NDJSON stream → sensitive fields are masked → the stream is structurally correct (Meta→FullSnapshot→Incrementals; stable ids; density-normalized coords). Visual fidelity is confirmed later when the web replay engine consumes the same file.

---

## PART 2 — THE WIRE CONTRACT (single source of truth)

A recording is newline-delimited JSON; each line is one event: `{ "type": <int>, "timestamp": <epoch-ms>, "data": { ... } }`.

### 2.1 Numeric enums (do not change)
- **Event type:** `2` FullSnapshot · `3` IncrementalSnapshot · `4` Meta · `5` Custom.
- **Incremental source:** `0` Mutation · `2` MouseInteraction (touch).
- **Touch interaction type:** `7` TouchStart · `9` TouchEnd. `pointerType` = `2`.

### 2.2 Event shapes (exact)
```jsonc
// Meta (4) — once, before the first FullSnapshot of a screen
{ "type": 4, "timestamp": 1719000000000, "data": { "href": "CheckoutActivity", "width": 412, "height": 915 } }

// FullSnapshot (2)
{ "type": 2, "timestamp": 1719000000001, "data": { "wireframes": [ <wireframe> ], "initialOffset": { "top": 0, "left": 0 } } }

// IncrementalSnapshot — mutation (3). Omit empty arrays. An UPDATE = full wireframe of the changed node.
{ "type": 3, "timestamp": 1719000000500, "data": {
    "source": 0,
    "adds":    [ { "parentId": 11, "wireframe": <wireframe> } ],
    "removes": [ { "parentId": 11, "id": 42 } ],
    "updates": [ { "parentId": 11, "wireframe": <wireframe> } ] } }

// IncrementalSnapshot — touch (3). id = touched wireframe id (root id 5 if unknown).
{ "type": 3, "timestamp": 1719000000600, "data": { "source": 2, "type": 7, "id": 42, "x": 120, "y": 340, "pointerType": 2 } }

// Custom — keyboard (5)
{ "type": 5, "timestamp": 1719000000700, "data": { "tag": "keyboard", "payload": { "open": true, "height": 320 } } }
{ "type": 5, "timestamp": 1719000001200, "data": { "tag": "keyboard", "payload": { "open": false } } }
```

### 2.3 The wireframe node
Base (all wireframes): `{ id:int (stable), x?:int=0, y?:int=0, width:int|"100vw", height:int, type:MobileNodeType, style?:Style, childWireframes?:[] }` — **all geometry density-normalized**.

| `type` | Extra fields | Notes |
|---|---|---|
| `div` | — | default container |
| `text` | `text:string` | masked at source if required |
| `image` | `base64?:string` | omit ⇒ placeholder (how masked images appear) |
| `rectangle` | — | solid via `style` |
| `web_view` | `url?:string` | renders as labelled placeholder |
| `placeholder` | `label?:string` | |
| `status_bar` / `navigation_bar` | — | chrome; no children |
| `radio_group` | — | wraps radios |
| `input` (+`inputType`, `disabled:boolean`) | per below | |
| → `checkbox`/`toggle`/`radio` | `checked:boolean`, `label?` | |
| → `text`/`password`/`email`/`number`/`search`/`tel`/`url` | `value?:string` | masked at source |
| → `text_area` | `value?:string` | |
| → `select` | `value?:string`, `options?:string[]` | |
| → `button` | `value?:string` | or `childWireframes` |
| → `progress` | `value?:number`, `max?:number`, `style.bar:"horizontal"\|"circular"\|"rating"` | |

`Style` (all optional): `color, backgroundColor (#hex), backgroundImage (base64), backgroundSize(contain|cover|auto), borderWidth, borderRadius, borderColor, verticalAlign(top|bottom|center), horizontalAlign(left|right|center), fontSize(px), fontFamily, paddingLeft|Right|Top|Bottom(px)`.

### 2.4 Invariants (enforce + CI)
1. Geometry density-normalized integers. 2. `id` stable per node across frames. 3. **Masking at capture** — wire format never carries unmasked PII. 4. Per screen: `Meta` → `FullSnapshot`, then `Incremental`/`Custom` until the screen changes. 5. Validate every event against `rr-mobile-schema.json`.

---

## PART 3 — IMPLEMENTATION

**minSdk 26 · Kotlin.** Deps: `com.squareup.curtains:curtains`, `org.jetbrains.kotlinx:kotlinx-serialization-json`, `androidx.work:work-runtime-ktx`, `androidx.core:core-ktx`; OkHttp from host for the network interceptor.

**Module layout**
```
openlog-replay/
 ├─ wire/    Event.kt, Wireframe.kt, Style.kt, Envelope.kt
 ├─ capture/ SessionCaptureEngine.kt, NextDrawListener.kt, Throttler.kt, SnapshotStatus.kt
 ├─ graph/   ScreenGraphProvider.kt, ViewScreenGraphProvider.kt
 ├─ mask/    MaskPolicy.kt
 ├─ sink/    SessionSink.kt, FileSessionSink.kt, HttpSessionSink.kt
 ├─ correlate/ Correlation.kt
 └─ OpenLog.kt   // public init/start/stop
```

### T0 — Wire model (Part 2) — *implement fully*
```kotlin
package cloud.openlog.replay.wire
import kotlinx.serialization.*; import kotlinx.serialization.json.*

object EventType { const val FULL = 2; const val INCREMENTAL = 3; const val META = 4; const val CUSTOM = 5 }
object Source    { const val MUTATION = 0; const val MOUSE = 2 }
object Touch     { const val START = 7; const val END = 9; const val POINTER = 2 }

@Serializable data class Style(
    val color: String? = null, val backgroundColor: String? = null, val backgroundImage: String? = null,
    val backgroundSize: String? = null, val borderWidth: Int? = null, val borderRadius: Int? = null,
    val borderColor: String? = null, val verticalAlign: String? = null, val horizontalAlign: String? = null,
    val fontSize: Int? = null, val fontFamily: String? = null,
    val paddingLeft: Int? = null, val paddingRight: Int? = null, val paddingTop: Int? = null, val paddingBottom: Int? = null,
    val bar: String? = null,
)
@Serializable data class Wireframe(
    val id: Int, val x: Int = 0, val y: Int = 0, val width: Int, val height: Int, val type: String,
    val inputType: String? = null, val text: String? = null, val value: JsonElement? = null,
    val label: String? = null, val base64: String? = null, val url: String? = null,
    val checked: Boolean? = null, val disabled: Boolean? = null, val options: List<String>? = null,
    val max: Int? = null, val style: Style? = null, val childWireframes: List<Wireframe>? = null,
    @Transient val parentId: Int? = null,   // diffing only, not serialized
)
```
**Acceptance:** round-trips a hand-written FullSnapshot JSON byte-identical (minus key order) and passes `rr-mobile-schema.json`.

### T1 — Envelope + data builders — *implement*
Build `{type,timestamp,data}` for Meta, Full (`wireframes`+`initialOffset`), Incremental mutation (`source:0`, omit empty add/remove/update arrays; entries `{parentId,wireframe}` / `{parentId,id}`), touch (`source:2`), keyboard. **Acceptance:** each builder’s output validates against the schema; empty mutation arrays are omitted.

### T2 — Window discovery (Curtains) — *implement*
Use Curtains: iterate `Curtains.rootViews` and subscribe to `Curtains.onRootViewsChangedListeners` to attach/detach **every window** (dialogs/popups/toasts/bottom sheets), not just the Activity. Track per-decorView `SnapshotStatus` in a `WeakHashMap`. **Acceptance:** opening a dialog produces capture for the dialog window; closing it detaches cleanly (no leak — verify with LeakCanary/Memory Profiler).

### T3 — Draw loop + throttle + executor — *implement*
Per decorView register a `ViewTreeObserver.OnDrawListener`; throttle to ~1s (`Throttler`, leading+trailing); submit snapshots to a single-thread background executor with a `WeakReference` to the decorView. **Acceptance:** rapid redraws produce ≤ ~1 snapshot/sec; zero work on the main thread (verify with a StrictMode/main-thread assert).

### T4 — View→wireframe walk — *implement*
```kotlin
class ViewScreenGraphProvider : ScreenGraphProvider {
  override fun snapshot(root: View, density: Float, policy: MaskPolicy) =
    root.toWireframe(density, policy, parentId = null, ancestorUnmasked = false)
  private fun View.toWireframe(density: Float, policy: MaskPolicy, parentId: Int?, ancestorUnmasked: Boolean): Wireframe? {
    if (!isAttachedToWindow || width == 0 || height == 0 || visibility != View.VISIBLE) return null
    val unmasked = ancestorUnmasked || policy.isUnmasked(this)
    val id = System.identityHashCode(this)
    val loc = IntArray(2).also { getLocationOnScreen(it) }
    // map widget -> type/inputType/text/value/base64/checked/options/style (table below)
    // recurse ViewGroup children: parentId = id, ancestorUnmasked = unmasked
    TODO("widget mapping per the table; coords ÷ density")
  }
}
```
**Widget → wireframe mapping** (mirror `PostHogReplayIntegration.toWireframe`):
`EditText`→`input/text_area` (value masked) · other `TextView`→`text` (text masked) · `Button`→`input/button` (value=text) · `CheckBox`→`input/checkbox`+checked · `RadioButton`→`input/radio`+checked · `Switch`→`input/toggle`+checked · `RadioGroup`→`radio_group` · `Spinner`→`input/select`+value+options (masked) · `ImageView`→`image` (base64 only if `!maskImage`) · `ProgressBar`/`RatingBar`→`input/progress`+`style.bar` · `WebView`→`web_view` · decor status/nav bar backgrounds→`status_bar`/`navigation_bar` · else→`div`. Build `style` from bg/textColor/fontSize/alignment/padding/border. All geometry `÷ density`.
**Acceptance:** a known screen produces a wireframe tree whose node count, types, and (normalized) bounds match expectation; invisible views are absent.

### T5 — Masking (mask-by-default) — *implement*
```kotlin
class MaskPolicy(val maskAllText: Boolean = true, val maskAllImages: Boolean = true) {
  fun isUnmasked(v: View) = v.hasTag(NO_MASK)
  fun maskText(v: View, ancestorUnmasked: Boolean): Boolean {
    if (ancestorUnmasked || isUnmasked(v)) return false
    if (v is TextView && v.isPasswordInput()) return true
    return v.hasTag(NO_CAPTURE) || maskAllText
  }
  fun maskImage(v: View, ancestorUnmasked: Boolean) =
    !(ancestorUnmasked || isUnmasked(v)) && (v.hasTag(NO_CAPTURE) || maskAllImages)
  companion object { const val NO_CAPTURE = "openlog-no-capture"; const val NO_MASK = "openlog-no-mask" }
}
// hasTag(label): (tag as? String)?.contains(label,true)==true || contentDescription?.contains(label,true)==true
// masked text => "*".repeat(len); masked image => omit base64
```
Precedence: **unmask > force-mask > default**; passwords always masked. **Acceptance:** a screen with a password field, a balance `TextView`, and a photo emits asterisks for text, no base64 for the image, and unmasked content only where a view is tagged `openlog-no-mask`.

### T6 — Snapshot orchestration (diff) — *implement*
First capture of a screen: emit `Meta` then `FullSnapshot`. After: flatten previous+current trees by `id`; diff → adds (new ids), removes (gone ids), updates (same id, props changed — compare with `childWireframes` nulled). Emit `Incremental`. Store `last`. **Acceptance:** moving one element emits a single update (not a new Full); adding/removing a view emits the matching add/remove.

### T7 — Touch + keyboard — *implement*
Touch via Curtains `touchEventInterceptors` (non-consuming): on `ACTION_DOWN`/`ACTION_UP` emit `source:2` `type:7/9` with density-normalized x/y. **Copy `MotionEvent` via `obtain()` before going off-thread; `recycle()` after.** Keyboard: `WindowInsets` IME show/hide → `Custom` keyboard event. **Acceptance:** a tap produces TouchStart+TouchEnd at correct normalized coords; opening the soft keyboard emits `{open:true,height}`.

### T8 — Sinks + correlation — *implement*
`FileSessionSink` (append NDJSON to `<dir>/<sessionId>.ndjson`) first — enables validation. Then `HttpSessionSink` (batch, disk-persist, WorkManager upload with backoff, drop-oldest when full; mirror `PostHogReplayQueue`/`PostHogReplayBufferQueue`). Correlation: mint `session_id` + W3C `traceparent`; OkHttp interceptor injects `traceparent`; set `session_id`/`trace_id` as Crashlytics custom keys. **Acceptance:** a session writes a valid NDJSON file; with HTTP sink, events upload in batches and survive an offline→online transition.

---

## PART 4 — HARD REQUIREMENTS (gotchas — do not skip)
API 26+ gate · **discard the snapshot if a draw fires mid-walk** · detect animation-only redraws (`hasTransientState()`) so animating screens don't spam · `isAlive()`/`isAttachedToWindow()` + try/catch around every off-thread view access (prevents native crashes) · `WeakReference`/`WeakHashMap` for roots · `MotionEvent.obtain()`/`recycle()` for off-thread touch · all capture off the main thread · session-level sampling · **capture gated on explicit user consent**.

---

## PART 5 — VALIDATION GATE (capture-side; no player needed yet)
1. **Schema conformance (CI):** validate every emitted event against `rr-mobile-schema.json` (from `PostHog/posthog` → `frontend/src/scenes/session-recordings/mobile-replay/schema/mobile/`). Fail the build on any violation.
2. **Structural assertions (unit/instrumented):** first events are `Meta`→`Full`; ids stable across frames; coords are normalized integers; **no raw PII** (assert masked fields are asterisks / images have no base64 unless explicitly unmasked).
3. **Performance gates:** RAM peak and leaks (Monkey + Memory Profiler / LeakCanary); data volume kB/min within budget; main-thread time ≈ 0.
4. **Manual round-trip (later):** when the web replay engine exists, feed `<sessionId>.ndjson` through `transformToWeb` + rrweb and eyeball fidelity. (Out of scope here; this is the eventual visual gate.)

---

## PART 6 — PostHog reference map (read-only)
- Window discovery / touch / draw loop → `PostHogReplayIntegration.install` / `addView`, `internal/NextDrawListener.kt`, `internal/Throttler.kt`.
- View→wireframe → `PostHogReplayIntegration.toWireframe`.
- Masking → `isTextInputSensitive` / `shouldMaskTextView` / `findMaskableWidgets` / `isNoCapture` / `isUnmasked`.
- Diff → `generateSnapshot` + `findAddedAndRemovedItems` + `flattenChildren`.
- Transport → `PostHogReplayQueue` / `PostHogReplayBufferQueue`.
- Wire model → `posthog/src/main/java/com/posthog/internal/replay/RR*.kt`.
- Contract spec → `rr-mobile-schema.json`, `common/replay-shared/src/mobile/mobile.types.ts`.

---

## PART 7 — LICENSING
Reimplement the SDK against this spec (do not copy PostHog Kotlin files verbatim). Produce a `THIRD_PARTY_NOTICES` listing runtime deps (Curtains, kotlinx-serialization, AndroidX). Do not reference anything under PostHog's `ee/` directory.
