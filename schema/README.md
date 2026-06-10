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

## OpenLog extensions to the vendored schema

This `rr-mobile-schema.json` is **intentionally forked** from upstream PostHog —
OpenLog uses its own web replay player, so the contract is ours to extend.

- **`idName`** is declared (optional, `string`) on every wireframe definition. It
  carries the source view's Android resource-id name (e.g. `"balanceValue"`) so a
  recording is traceable back to the XML. It is emitted on every node that has an
  id. Recordings are therefore **not** interchangeable with PostHog's player (by
  design). See `wire/Wireframe.kt`.

- **`className`** is likewise declared (optional, `string`) on every wireframe
  definition: the source view's platform class name (e.g. `"MaterialButton"`).
  Unlike `idName` it is a **debug aid**, only emitted when
  `OpenLog.Config.debugClassNames` is on — it gives raw-view-tree-style fidelity
  for debugging capture issues without a separate raw-tree export. Off by default
  to keep production volume down.

- **Custom (type 5) event tags** — these ride on the *schema-unconstrained* Custom
  `payload`, so they need no schema change and the rrweb player passes them through
  unchanged. They give a reader the "what happened" story (and let a tool draw a nav
  graph from the screen sequence):
  - `screen` — `{ action: "enter"|"exit", name }` as the foreground screen changes.
  - `app_lifecycle` — `{ state: "foreground"|"background" }` (process lifecycle).
  - `tap_target` — `{ type, idName, label, x, y }`: what the user tapped (label is
    mask-aware). The accompanying touch event's `id` points at the tapped node.
  - `keyboard` — `{ open, height? }` (SPEC Part 2.2).
