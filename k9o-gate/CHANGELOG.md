# Changelog

## 1.5.0 (unreleased)

### Added

- End-user flows, as a wire layer: `passkeys.register.begin` and `finish`, `passkeys.signIn.begin` and `finish`, `mfa.totp.enroll` and `confirm`, `mfa.methods.list` and `delete`, `passwordReset.request` and `redeem`, `invitations.redeem`, and `verifySession`. Passkey options and credentials are passed as JSON strings; the calls do not use the platform's passkey API and do not store the refresh token they return. `passwordReset.request` asks the project which region holds the email first, like `signIn`.
- `GateClient(orgSlug = ...)`: pass your organization's slug instead of `orgBaseUrl` to use `https://<orgSlug>.api.k9o.io`. A full organization address or a custom domain in `orgBaseUrl` is used as given. With neither, construction throws `K9OGateConfigException` (a subclass of `IllegalArgumentException`) with `code` `K9O_INVALID_BASE_URL` and `context` `{ field: "baseUrl" }`. A malformed `orgSlug` throws it with `K9O_INVALID_FIELD_VALUE` and `context` `{ field: "orgSlug", slug }`.
- `signIn` first asks the project which region holds the email (one extra request with only the email) and sends the password to that region's host, if it is a region host of your own organization and apex; otherwise to the base URL. `refreshToken` and `signOut` go to the region that issued the session.
- `GateError.RateLimited` and `GateError.RegionUnavailable`, raised when that step answers `429` or `503`. Nothing is retried and no password is sent. If that step answers `404`, the client uses the base address instead.
- `GateError` fields on every case: `code` (the wire code, kept for codes the SDK does not list), `status`, `message`, `recommendation`, `retryAfterSec`, `context`, `requestId` (from the `x-k9o-request-id` header), `body` and `publicMessage`, a fixed sentence by status class that an app can show its users.
- `GateClient(errors = ...)`: `"full"` (default) or `"redacted"`. Redacted mode sets `message` to `publicMessage` and drops `recommendation`, `context` and `body`. An invalid value throws `K9OGateConfigException` with `code` `K9O_INVALID_FIELD_VALUE` and `context` `{ field: "errors", value }`.

### Changed

- `GateError.Unknown` keeps the wire code the gateway sent (`code`) and its `recommendation`, instead of exposing only `statusCode` and `body`. `statusCode` and `body` are unchanged.
- `GateError.Unauthenticated`, `GateError.Forbidden` and `GateError.InvalidCredentials` are classes, not objects, because each carries the status, request id and context of its response. `is` and `catch` checks are unchanged. A bare value such as `GateError.Unauthenticated` no longer compiles: the SDK raises these cases, and an app does not construct them.
- The organization address `https://<org>.api.<domain>` now works for sign-in, refresh and sign-out. The client makes one credential-free `HEAD` to it, learns the regional address (`https://<org>.api-de01.<domain>`) and sends every request there. The answer is kept for the life of the client, and a network failure is not kept. Regional, localhost, IP and other addresses are used as given.
- The probe is sent on a copy of the `OkHttpClient` without its interceptors, so no credential or default header they add reaches the short address.

## 1.4.0 — 2026-10-26

### Changed

- README description.

## 1.3.0 — 2026-10-26

### Changed

- `refreshToken(refreshToken)` takes only the refresh token and returns a `RefreshResult` with `accessToken` and `expiresAt`.
- `refreshToken` no longer replaces the stored refresh token. The gateway does not rotate it.
- `signOut(refreshToken)` returns a `Boolean`: `true` when a live session was revoked.
- `AccessToken.customClaims` returns the flat `ext.<namespace>.<name>` keys of the token.

### Removed

- The `projectHint` argument of `refreshToken`. The gateway rejects it.

### Added

- `RefreshResult`.
- `AccessToken.customClaim(namespace, name)` reads one custom claim.


## 1.2.0

First release on Maven Central as `dev.k9o:gate`.

- `GateClient` with `signIn`, `refreshToken` and `signOut` (suspend functions).
- `KeystoreTokenStore` keeps the refresh token in `EncryptedSharedPreferences`, backed by the Android Keystore.
- `TokenStore` interface for custom storage, `AccessToken` payload decoder with `customClaims`, and a `GateError` hierarchy.
- Android API 23 or later, or JVM 17.
