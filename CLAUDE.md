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

---

## Project layout

```
openlog-replay/                         # the Android library (com.android.library)
 └─ src/main/java/cloud/openlog/replay/
    ├─ wire/      Event.kt, Wireframe.kt, Style.kt, Envelope.kt   (T0/T1 — Android-free)
    ├─ diff/      SnapshotDiff.kt                                  (T6 core — Android-free)
    ├─ capture/   SessionCaptureEngine, NextDrawListener, Throttler, SnapshotStatus
    ├─ graph/     ScreenGraphProvider, ViewScreenGraphProvider     (T4 view→wireframe walk)
    ├─ mask/      MaskPolicy                                       (T5 mask-by-default)
    ├─ sink/      SessionSink, FileSessionSink, HttpSessionSink    (T8 transport)
    ├─ correlate/ Correlation                                      (T8 trace correlation)
    └─ OpenLog.kt                                                  (public init/start/stop)
schema/rr-mobile-schema.json            # canonical contract (vendored from PostHog)
tools/wire-verify/                      # pure-JVM Part-5.1 gate (no Android SDK needed)
```

## Validation gate

The wire contract (`wire/`) and the diff (`diff/`) are deliberately Android-free so they
can be compiled and validated on a plain JVM. Run the schema-conformance + structural gate:

```bash
gradle -p tools/wire-verify test
```

This validates the wire model and every event builder against `schema/rr-mobile-schema.json`
and fails on any violation (golden rule #5).

The Android library itself (`./gradlew :openlog-replay:assembleRelease`) additionally requires
the Android SDK to be installed and `sdk.dir` set in `local.properties` (or `ANDROID_HOME`).

## Deviations from the SPEC prose (intentional, schema-driven)

- The canonical `MobileStyles` has `additionalProperties: false` and no `bar` field, so
  `style.bar` (mentioned in SPEC 2.3) is NOT emitted. Progress uses `value`/`max`. See
  `wire/Style.kt` and `schema/README.md`.
