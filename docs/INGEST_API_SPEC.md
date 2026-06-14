# OpenLog Ingest API — SDK Contract

Spec for the `HttpSessionSink` in the OpenLog Android SDK. This documents the
**implemented** behavior of the OpenLog web app's ingest layer (Next.js route
handlers), not a proposal — the SDK must match it exactly.

- Base URL: the OpenLog web app deployment -> https://openlog.sh
- All three endpoints require the same bearer token
- All request/response bodies are UTF-8
- Event timestamps are **epoch milliseconds** (numbers, not strings)

---

## 1. Authentication

Every request carries:

```http
Authorization: Bearer <INGEST_TOKEN>
```

`INGEST_TOKEN` is provisioned out-of-band (one per app deployment). A missing
or wrong token returns:

```http
401 {"error": "Invalid or missing bearer token"}
```

There are no other auth mechanisms (no cookies, no signatures) on the ingest
side.

## 2. Wire format: events

Events are **rr-mobile** events (rrweb mobile wire schema, vendored from
PostHog) with OpenLog extensions:

- The server validates each event against the rr-mobile JSON Schema with
  `additionalProperties` **relaxed**: extra fields anywhere in an event are
  accepted and stored verbatim (e.g. OpenLog's `className` / `idName` on
  wireframes).
- **Required fields and types are still strictly enforced.** A full snapshot
  without `data.wireframes`, a wireframe without `id`/`type`/`width`/`height`,
  a non-numeric `timestamp`, etc. are rejected.

Top-level event shape:

```json
{ "type": <number>, "timestamp": <epoch ms>, "data": { ... } }
```

Event types in use (rrweb numbering):

| type | meaning                | notes                                          |
|------|------------------------|------------------------------------------------|
| 2    | FullSnapshot           | `data.wireframes` — the mobile view hierarchy  |
| 3    | IncrementalSnapshot    | touches, scrolls, mutations (`data.source`)    |
| 4    | Meta                   | one per screen; `data.href/width/height`. Drives the server-side `screenCount` |
| 5    | Custom                 | logs: `data.tag` + `data.payload` (e.g. `screen`, `tap_target`, `keyboard`, `app_lifecycle`) |

Masking happens **at capture** in the SDK (masked text already arrives as
`***`). The server never unmasks and never logs event bodies.

## 3. `POST /api/ingest` — direct batch upload

The primary path. One request = one batch of ordered events.

### Request

```http
POST /api/ingest
Authorization: Bearer <INGEST_TOKEN>
Content-Type: application/x-ndjson        (or application/json, see below)
X-OpenLog-Session-Id: <sessionId>         REQUIRED
X-OpenLog-App: <applicationId>            recommended (defaults to "unknown")
X-OpenLog-Sdk: <sdkVersion>               recommended (defaults to "unknown")
X-OpenLog-Batch-Seq: <n>                  STRONGLY recommended, 1-based
X-OpenLog-Device: <json>                  first batch of a session

<event>\n
<event>\n
...
```

- **`X-OpenLog-Session-Id`** must match `^[\w.-]+$` (letters, digits, `_`,
  `.`, `-`). Anything else → `400`.
- **`X-OpenLog-Device`** is a single-line JSON object; send it on the first
  batch (harmless to repeat — it is only persisted when the session row is
  created). Fields the web UI reads:

  ```json
  {"os":"Android","osVersion":"15","model":"Pixel 9","density":2.625,"w":411,"h":923,"appVersion":"1.0.0"}
  ```

  A malformed device header → `400`.
- **`X-OpenLog-Batch-Seq`**: the batch's sequence number within the session,
  starting at 1, assigned by the SDK and never reused. This is the
  **idempotency key**: re-sending a batch the server already has returns
  `202 {"received": 0, "duplicate": true}` without double-counting, which
  makes network retries safe. If omitted, the server derives the next number
  itself — only safe when batches are sent strictly one at a time and never
  retried; **always send it**.

### Body

Preferred: `Content-Type: application/x-ndjson` — one JSON event per line
(exactly what `FileSessionSink` writes). Blank lines are ignored.

Also accepted: `Content-Type: application/json` with a JSON **array** of
events.

Events must be in chronological order within the batch, and batches must be
flushed in order (batch 1's events all precede batch 2's).

### Size cap (hard)

The deployment target (Vercel) caps request bodies at 4.5 MB. The server
enforces a batch cap of **3,670,016 bytes (3.5 MiB)** and rejects larger
bodies:

```http
413 {"error": "Batch too large", "maxBatchBytes": 3670016}
```

**SDK requirement:** `HttpSessionSink` must flush by *size as well as count* —
flush when the pending batch would exceed ~3 MiB (leave margin), regardless
of event count. A single batch that cannot fit (e.g. a huge full snapshot
burst) must use the large-batch path (§4).

### Responses

| status | body | meaning |
|--------|------|---------|
| 202 | `{"sessionId": "...", "received": <n>}` | batch stored |
| 202 | `{"sessionId": "...", "received": 0, "duplicate": true}` | batch seq already ingested — treat as success |
| 400 | `{"error": "Invalid event: <reason>", "index": <i>}` | event at 0-based index `i` failed schema validation. Do not retry unchanged |
| 400 | `{"error": "Invalid JSON on NDJSON line", "index": <i>}` | line `i` is not parseable JSON |
| 400 | `{"error": "..."}` | missing/invalid session id header, bad device header, empty batch, non-array JSON body |
| 401 | `{"error": "Invalid or missing bearer token"}` | fix token; do not retry with same credentials |
| 413 | `{"error": "Batch too large", "maxBatchBytes": 3670016}` | split the batch or use the presign path |
| 500 | `{"error": "Internal error"}` | server fault — safe to retry with the same `X-OpenLog-Batch-Seq` |

**Retry policy for the SDK:** retry only on network errors and `5xx`, with the
same `X-OpenLog-Batch-Seq` (dedupe makes this safe). `4xx` responses are
permanent for that batch.

### Server-side effects (for reference)

- Events are appended (ordered, per batch seq) to the session's blob storage.
- The session row is created on first batch (using the device header) and
  updated on every batch: `eventCount` += received, `screenCount` += number of
  type-4 events, `endedAt` = max timestamp seen, `durationMs` = `endedAt` −
  session `startedAt` (min timestamp of the first batch).

### Example

```bash
curl -X POST "$BASE_URL/api/ingest" \
  --data-binary @session.ndjson \
  -H "Authorization: Bearer $INGEST_TOKEN" \
  -H "Content-Type: application/x-ndjson" \
  -H "X-OpenLog-Session-Id: 1f3a2b-9c..." \
  -H "X-OpenLog-App: com.example.app" \
  -H "X-OpenLog-Sdk: 0.1.0" \
  -H "X-OpenLog-Batch-Seq: 1" \
  -H 'X-OpenLog-Device: {"os":"Android","osVersion":"15","model":"Pixel 9","density":2.625,"w":411,"h":923,"appVersion":"1.0.0"}'
# → 202 {"sessionId":"1f3a2b-9c...","received":59}
```

## 4. Large-batch path — `presign` + direct PUT + `commit`

For batches over the 3.5 MiB cap. The batch bytes go **directly to object
storage (Cloudflare R2)** via a presigned URL, bypassing the function body
limit entirely. Three steps:

### 4.1 `POST /api/ingest/presign`

```http
POST /api/ingest/presign
Authorization: Bearer <INGEST_TOKEN>
Content-Type: application/json

{"sessionId": "<sessionId>", "batchSeq": <n>}
```

`batchSeq` is optional but, as with §3, the SDK should always send it.

Response:

```http
200 {"url": "https://<presigned R2 PUT url>", "objectKey": "sessions/<id>/batch-00000<n>.ndjson", "batchSeq": <n>}
```

The URL expires after **10 minutes**. Errors: `400` bad/missing `sessionId`,
`401` bad token, `501` when the deployment's storage backend cannot presign
(local-fs dev deployments — fall back to splitting into ≤3.5 MiB direct
batches).

### 4.2 Upload

PUT the raw NDJSON batch to `url`:

```http
PUT <url>
Content-Type: application/x-ndjson

<event>\n<event>\n...
```

The `Content-Type` must be `application/x-ndjson` (it is part of the
signature). No `Authorization` header — the signature is in the URL.

### 4.3 `POST /api/ingest/commit`

Registers the uploaded object. The server reads it back from storage,
validates every event, and folds the batch into the session metadata.

```http
POST /api/ingest/commit
Authorization: Bearer <INGEST_TOKEN>
Content-Type: application/json
X-OpenLog-Session-Id: <sessionId>         REQUIRED (same as ingest)
X-OpenLog-App / X-OpenLog-Sdk / X-OpenLog-Device   as in §3 (first batch)

{"sessionId": "<sessionId>", "objectKey": "<objectKey from presign>"}
```

| status | meaning |
|--------|---------|
| 202 `{"sessionId","received"}` | object validated and registered |
| 400 | `objectKey` missing, doesn't belong to `sessionId`, or an event failed validation (`index` names the NDJSON line) |
| 404 | nothing was uploaded at `objectKey` |

**Commit exactly once per uploaded object.** Unlike §3, commit does not
currently dedupe — retry a commit only if you got a network error / `5xx`
*and* no `202` was ever received for that object. The body's `sessionId` must
equal the `X-OpenLog-Session-Id` header, and the `objectKey` must be the one
presign returned for that session.

## 5. Session lifecycle summary (SDK view)

1. Generate a session id matching `^[\w.-]+$` (UUID is fine).
2. Buffer captured events; flush a batch when **either** the event-count
   threshold **or** the ~3 MiB size threshold is reached, and on session end.
3. Batch 1 carries `X-OpenLog-Device`. Every batch carries
   `X-OpenLog-Session-Id`, `X-OpenLog-App`, `X-OpenLog-Sdk`, and a
   monotonically increasing `X-OpenLog-Batch-Seq` (1, 2, 3, …).
4. ≤3.5 MiB → `POST /api/ingest`. Larger → presign → PUT → commit.
5. On retryable failure, re-send with the same batch seq. Never reorder or
   renumber batches.

## 6. Read-side endpoints (context only — not used by the SDK)

| endpoint | purpose |
|----------|---------|
| `GET /api/sessions?cursor=&limit=` | paginated session list |
| `GET /api/sessions/[id]` | session metadata |
| `GET /api/sessions/[id]/snapshots?block=N` | event block N (0-based); `X-OpenLog-Total-Blocks` header gives the count |

These currently have no auth (app-level auth is stubbed; RBAC lands later).
The replay player consumes exactly what the SDK uploaded — bytes in, bytes
out — which is why event order and schema conformance at ingest time matter.
