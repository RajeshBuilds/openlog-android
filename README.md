# OpenLog — Android Capture SDK

A privacy-first **session-capture SDK** for Android apps. It records each
screen as a **wireframe** (never a screenshot), masks all text and images by
default, and emits a schema-valid [rr-mobile](schema/rr-mobile-schema.json) NDJSON
stream that a web replay engine can later play back.

> Scope: the Android capture SDK only. The web replay engine, backend, and
> Compose support are separate efforts. Built to [`SPEC.md`](SPEC.md).

## Highlights

- **Wireframe capture only** — no screenshots, ever (PII-safe).
- **Mask by default** — text → asterisks, images → placeholders. Opt out per view
  with `openlog-no-mask`; force-mask with `openlog-no-capture`. Passwords are
  always masked.
- **Off the main thread** — the draw loop only enqueues; all walking, diffing and
  serialization run on a single background thread.
- **Schema-conformant wire format** — every event validates against the canonical
  `rr-mobile-schema.json` in CI.
- **Consent-gated + sampled** — capture only runs with explicit consent and within
  the configured session sample rate.
- **Action-level signals** — beyond the wireframe tree and diffs, the stream carries
  `screen` enter/exit (with names → nav flow), `app_lifecycle` foreground/background,
  `tap_target` (what was tapped), and `keyboard` events, all as rrweb Custom events.
- **Real-time scroll & input** — optional rrweb `scroll` (source 3) and `input`
  (source 5) events for smooth scroll playback and per-keystroke input (masked),
  instead of only sampling at the snapshot cadence. Toggle via `Config.captureScrolls`
  / `Config.captureInputs`; main-thread cost stays negligible (scroll is throttled
  and all emission is off-thread).
- **Fragment-aware screen names** — single-Activity / multi-Fragment apps report the
  resumed **Fragment** class as the screen (Activity name as the fallback). androidx
  .fragment is an optional, runtime-guarded dependency.

## Quick start

```kotlin
// Application.onCreate()
OpenLog.init(
    context = this,
    config = OpenLog.Config(
        maskAllText = true,
        maskAllImages = true,
        sampleRate = 1.0,
        captureScrolls = true,     // rrweb scroll events (source 3); throttled
        captureInputs = true,      // rrweb input events (source 5); masked
        scrollThrottleMs = 100,    // min interval between scroll events per container
        // Omit `http` to write a local NDJSON file (great for validation);
        // provide it to batch-upload via WorkManager.
        http = HttpSessionSink.Config(
            endpoint = "https://ingest.example.com/replay",
            headers = mapOf("Authorization" to "Bearer …"),
        ),
    ),
)

OpenLog.setConsent(true)   // explicit user consent
OpenLog.start()            // begin capturing every window

// Tie network calls to the session (optional, requires OkHttp on the host):
val client = baseClient.newBuilder().addInterceptor(OpenLog.traceInterceptor()).build()

// …later
OpenLog.stop()
```

## Demo app

The `:app` module is a small host app that exercises the SDK end to end:

- **Home** (`MainActivity`) — masked-by-default content (an account balance, an
  avatar image, plus one `openlog-no-mask`-tagged line captured as-is) and a
  button to toggle recording.
- **Login** (`LoginActivity`) — email / password / checkbox / button form that
  drives touch, soft-keyboard and input-mutation events (values stay masked).
- **Recording viewer** (`RecordingViewerActivity`) — flushes the live session and
  pretty-prints the on-disk NDJSON stream so you can see exactly what was emitted.
  Each node carries `id` (stable number) and `idName` (the XML resource-id name).

```bash
./gradlew :app:assembleDebug          # build the demo APK
# install on a device/emulator:
./gradlew :app:installDebug
```

It reads the active session via `OpenLog.currentSessionFile()` and `OpenLog.flush()`.

## Architecture (maps to SPEC tasks)

| Package | Responsibility | SPEC |
|---|---|---|
| `wire/` | `Event`, `Wireframe`, `Style`, builders — the wire contract | T0, T1 |
| `diff/` | `SnapshotDiff` — flatten-by-id structural diff | T6 |
| `capture/` | window discovery, throttled draw loop, orchestration, touch/keyboard | T2, T3, T6, T7 |
| `graph/` | `ViewScreenGraphProvider` — view → wireframe walk | T4 |
| `mask/` | `MaskPolicy` — mask-by-default | T5 |
| `sink/` | `FileSessionSink`, `HttpSessionSink` + `ReplayUploadWorker` | T8 |
| `correlate/` | `Correlation`, `TraceparentInterceptor` | T8 |
| `OpenLog.kt` | public init/start/stop, consent, sampling | — |

`wire/` and `diff/` are intentionally **Android-free** so the wire contract can be
validated on a plain JVM.

## Building & testing

```bash
# 1) Wire-contract gate — schema conformance + diff (no Android SDK required)
gradle -p tools/wire-verify test

# 2) Android library: build the AAR and run Robolectric unit tests
#    (requires the Android SDK; set sdk.dir in local.properties or ANDROID_HOME)
./gradlew :openlog-replay:assembleRelease
./gradlew :openlog-replay:testReleaseUnitTest
```

The Part 5.1 gate validates **every** emitted event shape against
`schema/rr-mobile-schema.json` and fails the build on any violation.

## License & notices

This is a clean-room implementation against `SPEC.md`. PostHog was studied for
*mechanism* only; no source was copied verbatim. Runtime dependencies and their
licenses are listed in [`THIRD_PARTY_NOTICES`](THIRD_PARTY_NOTICES).
