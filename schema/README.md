# Wire contract schema

`rr-mobile-schema.json` is the **canonical** rr-mobile JSON Schema, vendored
read-only from PostHog:

```
PostHog/posthog → frontend/src/scenes/session-recordings/mobile-replay/schema/mobile/rr-mobile-schema.json
```

It is the single source of truth for the wire contract (SPEC.md Part 2). Every
event the SDK emits MUST validate against this file (golden rule #5, Part 5.1).

The pure-JVM harness in `tools/wire-verify` validates the wire model and every
event builder against this schema and fails the build on any violation.

## Known deviations from the SPEC.md prose

- SPEC.md Part 2.3 mentions `style.bar` for progress widgets. The canonical
  schema declares `MobileStyles` with `additionalProperties: false` and has no
  `bar` property, so emitting it would fail validation. The SDK therefore does
  **not** emit `bar`; progress rendering is driven by `value`/`max`. See
  `wire/Style.kt`.
- `screenshot` wireframes exist in the schema but are never emitted (banking is
  wireframe-only — golden rule #2).
- `Wireframe.name` (the source view's resource-id name, e.g. `"balanceValue"`) is a
  **debug-only** development aid enabled by `OpenLog.Config.debugResourceNames`.
  Because every wireframe is `additionalProperties: false`, this field is
  non-canonical, so it is **off by default** and omitted from production
  recordings — which therefore stay byte-for-byte schema-valid. The CI gate only
  validates default (canonical) output. See `wire/Wireframe.kt`.
