# Changelog

## 1.5.2

### Changed

- `K9O_TIER_FEATURE_GATED` is no longer documented as an error to expect: the platform no longer gates features by plan.
- README: Architecture at a glance, illustrations, and the package table lists only the packages for that SDK.

## 1.5.0

### Added

- `K9OCore(orgSlug = ...)`: pass your organization's slug instead of `baseUrl` to use `https://<orgSlug>.api.k9o.io`. A full organization address or a custom domain in `baseUrl` is used as given. With neither, construction throws `KError` `K9O_INVALID_BASE_URL` with `context` `{ field: "baseUrl" }`. A malformed `orgSlug` throws `KError` `K9O_INVALID_FIELD_VALUE` with `context` `{ field: "orgSlug", slug }`.
- `metadata.*`: `filter`, `set`, `search`, `vectors.nearest`, `index.get` and `index.declare` for the columns you declare on a collection. Column names in `where`, `search` and `nearest` get the `metadata.` prefix when you leave it out; `set` writes declared columns only. The client checks the surface grammar, limits, cursor rule and the 64-column limit before sending. Gateway codes such as `K9O_METADATA_FIELD_NOT_INDEXED`, `K9O_UNKNOWN_SURFACE` and `K9O_QUERY_RESULT_TOO_LARGE` arrive as `KError`. There is no `subscribe` on `metadata`.
- `realtime.subscribe(topic, filter?, onResync?)`: a reconnecting change feed over server-sent events (`GET /@<slug>/realtime/subscribe`) as a `Flow<RealtimeEvent>`. It reconnects with `Last-Event-ID`, drops events already delivered and calls `onResync` when the gateway cannot resume.
- `realtime.replay(topic, fromSequence?, untilSequence?, limit?, afterAuditEventId?)`: `POST /@<slug>/realtime/replay`, answering `RealtimeReplayPage` (`topic`, `events`, `nextCursor`, `hasMore`, `resync`). `resync` is `"reread-source"` when the topic keeps no ledger; only `audit.project.*` topics keep one. The client checks topic length, sequences, `limit` and `afterAuditEventId` before sending.
- `vectorSearch` on the select builder sends the embedding in the request body.
- `KError.retryAfterSec`: seconds the gateway asks you to wait, when the error carries it.
- `KError.requestId` is now filled from the gateway's `x-k9o-request-id` response header when the error body carries none; a body value still wins. `HttpResponse` and `HttpStreamResponse` take an optional `headers` map so a custom transport can pass response headers.
- `from(c).insertForApproval(record)` and `requireApproval()` on `update(...)` and `delete()`: queue a single-record write for approval (HTTP 202) instead of applying it. `execute()` returns `ApprovalGatedWrite.Queued` with the `QueuedChange` or, when an `auto_approve` policy applies the write at once, `ApprovalGatedWrite.Applied`. Upserts and bulk writes do not take it.
- `ai.generate(..., usageType)`: an optional label recorded with the call.
- `vectors.nearest` and `vectors.upsert`: search an embedding collection with the vector in the request body, and store rows in it.
- `K9OCore(readTimeoutMillis = ...)`: the read timeout of the default transport.
- `rampart.classify`, `rampart.jwks` and `rampart.verifyReceipt`: classify content as clear, suspicious or malicious and check the signed receipt. Receipts are verified with an in-house Ed25519 verifier (no platform provider needed), so it runs on every JVM and on Android API 23 and later. Also `Jcs`, the platform's canonical JSON form. A long input is scored in overlapping windows and the highest injection score decides; past 16 windows (about 6,000 tokens) the answer is 413 `K9O_RAMPART_INPUT_TOO_LONG` with no verdict. 503 `K9O_RAMPART_CLASSIFIER_UNAVAILABLE` still means the classifier could not answer. `RampartVerified.windows` is the number of windows scored (1 when the input fits one window; a present claim that is not a whole number of at least 1 is refused as `malformed`).
- `Moat`: pin your system prompt and check what goes in and what comes out, locally and with no network call. Refuses role and system markers, enforces input and output schemas (a JSON Schema subset) and runs a PII filter you supply.
- `Moat` matches role markers on normalised text: NFKC, combining marks removed, default-ignorable and format characters removed (including combining grapheme joiner, variation selectors and tag characters), a small set of Cyrillic and Greek look-alikes of the marker letters mapped to Latin, case-folded, horizontal whitespace collapsed; checked against the shared marker vectors.

### Fixed

- `KError.recommendation` is now filled when the gateway sends it as an object: the object's `action` text, or its `note`. It was `null` before.

### Changed

- A region-free address (`https://<org>.api.<domain>`) now works with credentials: the client asks it once, without credentials, for the regional address and sends every request there. It also retries once on the regional address a `400 K9O_INVALID_REQUEST_SHAPE` names. Regional, localhost and other addresses are used as given.
- The default read timeout is 60 seconds (was 10). Event streams still have no total read timeout.
- The README example uses the regional address.

## 1.4.0 — 2026-10-26

### Added

- `query(sql, params?)`: run one read-only SQL statement with positional (`List`) or named (`Map`, `:name` placeholders) parameters.
- `search(collection, column, q, ...)`: full-text search over one column, with `language`, `type`, `limit` and `cursor`.
- `from(collection)` data builder: `select`, `insert`, `update` and `delete`, the filters `eq neq gt gte lt lte in notIn like ilike is`, `limit`, `cursor`, `count`, `textSearch` and `vectorSearch`.
- `storage`: buckets (`listBuckets`, `getBucket`, `createBucket`, `updateBucket`, `deleteBucket`) and objects (`upload`, `list`, `remove`, `createSignedUrl`).
- `streams`: `create`, `list`, `get`, `delete`, `publish`, `replay` and `subscribe` (a `Flow` of events that reconnects from the last sequence).
- `ai.generate`: generate text from a prompt (the org's default model unless `model` is given) and read the generated text, finish reason and token usage.

### Changed

- README description.

## 1.3.0 — 2026-10-26

### Changed

- The README now covers the install requirements and shows the expected output of the example.
- Version aligned with the other SDKs. `ai.embed` is still the only call, and the public API is unchanged.


## 1.2.0

First release on Maven Central as `dev.k9o:core-kotlin`.

- `K9OCore` project client with `ai.embed(model?, input)`.
- Errors are thrown as `KError` (`code`, `message`, `context`, `recommendation`, `status`).
- Pure Kotlin on JVM 17, so it also runs on Android. No retries.
