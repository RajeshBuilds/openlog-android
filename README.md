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

## Quick start

```kotlin
// Application.onCreate()
OpenLog.init(
    context = this,
    config = OpenLog.Config(
        maskAllText = true,
        maskAllImages = true,
        sampleRate = 1.0,
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
