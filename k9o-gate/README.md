# dev.k9o:gate

Official Kotlin SDK for [Kilimanjaro](#71-about-kilimanjaro) Gate.
Sign users in from your Android and Kotlin app with Gate: sign in, refresh the session and sign out,
with the refresh token kept in the platform's secure storage.
A Gate token also scopes each data request to the signed-in user.

Requires Android API 23 or later (or JVM 17) and Kotlin 1.9 or later.

## Contents

**A. Get started**

1. [Install](#1-install)
2. [Choose the right package](#2-choose-the-right-package)
3. [Quick start](#3-quick-start)

**B. Sessions**

4. [Token storage](#4-token-storage)

**C. Use the token**

5. [Custom claims](#5-custom-claims)

**D. Troubleshoot**

6. [Errors](#6-errors)

**E. Reference**

7. [Appendix](#7-appendix)
   - 7.1 [About Kilimanjaro](#71-about-kilimanjaro)

## A. Get started

### 1. Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.k9o:gate:1.5.0")
}
```

### 2. Choose the right package

| Package | Use it when you are… | Registry | Source |
| --- | --- | --- | --- |
| `dev.k9o:core-kotlin` | Calling the platform from an Android or Kotlin app as a signed-in user. | [Maven Central](https://central.sonatype.com/artifact/dev.k9o/core-kotlin) | [sdk-kotlin](https://github.com/k9o-platform/sdk-kotlin) |
| `dev.k9o:gate` | Signing users in: sign in, refresh and sign out. | [Maven Central](https://central.sonatype.com/artifact/dev.k9o/gate) | [sdk-kotlin](https://github.com/k9o-platform/sdk-kotlin) |
| `dev.k9o:kibo-vault` | Storing, listing and revealing secrets in the vault. | [Maven Central](https://central.sonatype.com/artifact/dev.k9o/kibo-vault) | [sdk-kibo-kotlin](https://github.com/k9o-platform/sdk-kibo-kotlin) |

### 3. Quick start

Gate functions are `suspend` functions. Call them from a coroutine, for example `lifecycleScope.launch { ... }`.

#### Sign in

```kotlin
import dev.k9o.gate.GateClient
import dev.k9o.gate.KeystoreTokenStore

val gate = GateClient(
    orgBaseUrl = "https://acme.api.k9o.io",
    projectSlug = "my-project",
    tokenStore = KeystoreTokenStore(applicationContext),
)

val result = gate.signIn(email = "user@example.com", password = "password")
println(result.user.id)
println(result.session.expiresAt)
```

```text
eu_8fK2mXpQ
2026-01-01T12:00:00Z
```

`signIn` saves the refresh token in the token store.

`signIn` first asks the project which region holds the email: one extra request that carries only the email. The SDK sends the password only to a region host of your own organization and apex, and otherwise to the base URL. Nothing from that step is cached, apart from a note of which region issued the session so that refresh and sign-out go to the same place. A `429` or `503` from that step is raised as the usual `GateError` (`GateError.RateLimited` or `GateError.RegionUnavailable`) and is safe to retry. If the project's locate step answers 404, the client uses the base address.

#### Organization address

`orgBaseUrl` is your organization's address, `https://<org>.api.k9o.io`. It is optional when you pass `orgSlug`.
Pass `orgSlug = "acme"` to use your organization's host, `https://acme.api.k9o.io`.
A full organization address or a custom domain in `orgBaseUrl` is used as given, and `orgSlug` is then ignored for the address.
With neither `orgBaseUrl` nor `orgSlug`, construction throws `K9OGateConfigException` (an `IllegalArgumentException`) with `code` `K9O_INVALID_BASE_URL` and `context` `{ field: "baseUrl" }`.
A malformed `orgSlug` throws it with `code` `K9O_INVALID_FIELD_VALUE` and `context` `{ field: "orgSlug", slug }`.
That short address does not accept requests that carry a body or credentials, so the client follows it:
on the first request it sends one `HEAD` to the address, with no credentials and no default headers, learns the regional address, and sends every request there.
The answer is kept for the life of the client. If that first `HEAD` fails because of the network, nothing is kept and the next request asks again.

You can use the regional address instead, `https://<org>.api-de01.k9o.io`.
It is used as given, with no extra request, as are `localhost`, IP addresses and any other host.

#### Refresh and sign out

`refreshToken()` reads the stored refresh token and returns a new access token, the next refresh token and the access token's expiry.
Each refresh replaces the refresh token: the one you sent stops working. The client saves the new one to the token store before `refreshToken()` returns, and it refreshes one at a time, so calls made together share one request and a token is never sent twice.

```kotlin
val refreshed = gate.refreshToken()
println(refreshed.expiresAt)

val revoked = gate.signOut()
println(revoked)
```

```text
2026-01-01T13:00:00Z
true
```

`signOut()` clears the stored refresh token. It returns `true` when a live session was revoked.

#### Session lifetime

Three settings control how long an end user's session lasts. A project administrator sets them for the project in the Console, under the project's Gate settings, Sessions. The values below are the defaults, and each has a range:

| Setting | Default | Range |
|---|---|---|
| Access token lifetime | 30 minutes | 5 to 60 minutes |
| Sign out after inactivity | 30 days | 1 hour to 90 days |
| Maximum session length | 90 days | 1 hour to 365 days |

Refresh is where the limits are checked. Inactivity counts from the last successful refresh, so it is measured to within one access-token lifetime. A refresh token never outlasts either limit. A changed setting applies to existing sessions at their next refresh, and a shorter maximum ends the sessions already past it.

- **A refresh token works once.** Each refresh answers a new refresh token and the old one stops working. Sending an old one again counts as a replay: the gateway ends the session, and `refreshToken()` throws `GateError.Unauthenticated` with `status` 401 and `sessionEndReason` `"replay"`. The new token then fails the same way, with `"revoked"`. The client clears the token store on any of these, and the user must sign in again.
- **A session that has ended says why.** `sessionEndReason` is `"inactive"` (past the sign-out-after-inactivity limit), `"max_length"` (past the maximum session length), `"revoked"` (signed out, ended by a password reset, or ended by `revokeAllSessions`) or `"replay"` (see above). It is kept when `errors` is `"redacted"`. A token the gateway does not know, or one for a locked user, throws `GateError.InvalidCredentials` and also clears the store.
- **Keep your own refresh token only if you must.** If your app keeps a copy outside the token store, replace it with the `refreshToken` that each refresh returns, and never refresh from two places at once.
- **`signOut` ends this device's session.** It revokes the session that the stored refresh token belongs to. The same user's sessions on other devices stay valid.
- **`revokeAllSessions(accessToken)` ends every session of the user.** Pass the access token that `signIn` or `refreshToken` returned, not a refresh token. It ends the user's sessions on every device, this one included, deletes the stored refresh token and returns how many sessions ended. Each ended session's next refresh is refused with `K9O_SESSION_EXPIRED`; `GateError.sessionEndReason` is `"revoked"`. An operator's token is refused with `K9O_TRUST_ZONE_VIOLATION` (403), and a token issued for another project with `K9O_PROJECT_SCOPE_MISMATCH` (403). The stored token is left as it was when the call fails. Access tokens already issued keep working until they expire, so drop yours too.
- **An access token outlives sign-out.** One that was already issued keeps working until it expires, up to the access token lifetime you set, because it carries no session id and Gate keeps no list of revoked access tokens. The exception is a user who is no longer active: a locked or erased user's access tokens are refused at once.
- **A session that has passed a limit is refused.** The refresh answers 401 `K9O_SESSION_EXPIRED`, and `context.reason` is `inactive`, `max_length`, `revoked` (after sign-out) or `replay` (a used refresh token sent again). Send the user through sign-in again.
- **A password reset ends every existing session of that user.** The session the reset itself opens stays. Erasing a user locks them and revokes every session; an administrator's reset of a user's second factor also revokes every session.

#### Passkeys, authenticator apps, password reset and invitations

These calls carry data between your app and the gateway. They do not call the platform's passkey API or draw a QR code: you pass the passkey options to the platform and the credential it makes back to the gateway.

| Call | Sends the bearer token | Answer |
| --- | --- | --- |
| `passkeys.register.begin(bearerToken)` | the end user's access token | `PasskeyOptions` (`challenge`, `json`) |
| `passkeys.register.finish(responseJson, bearerToken, label)` | the end user's access token | `passkey.id`, `passkey.credentialId` |
| `passkeys.signIn.begin(email)` | no | `PasskeyOptions` |
| `passkeys.signIn.finish(responseJson)` | no | `session` |
| `mfa.totp.enroll(bearerToken, label)` | the end user's access token | `factor.id`, `factor.secret`, `factor.uri` |
| `mfa.totp.confirm(factorId, code, bearerToken, currentCode)` | the end user's access token | `factor`, and a `session` for the first authenticator |
| `mfa.methods.list(bearerToken)` | the end user's access token | `methods`: `id`, `type`, `label`, `verifiedAt`, `createdAt` |
| `mfa.methods.delete(methodId, bearerToken, code)` | the end user's access token | `deleted` |
| `passwordReset.request(email)` | no | `reset.requested` |
| `passwordReset.redeem(token, newPassword)` | no | a `session`, or an `mfa` challenge and no session |
| `invitations.redeem(token, password, displayName)` | no | `user`, `session` |
| `verifySession(token, bearerToken)` | your backend's bearer token | `valid` with `claims`, or `valid = false` with a `reason` |

`PasskeyOptions.json` is the complete options object as a JSON string, the form a Credential Manager request takes as `requestJson`. `responseJson` is the credential the platform returns, as a JSON string (`registrationResponseJson` or `authenticationResponseJson`).
The calls do not store a refresh token; pass `session.refreshToken` to `refreshToken(...)` or keep it yourself.

```kotlin
val options = gate.passkeys.signIn.begin("user@example.com")
// Pass options.json to the platform's passkey API, then send the credential it returns:
val result = gate.passkeys.signIn.finish(credentialJson)
println(result.session.expiresAt)
```

`passwordReset.request` answers the same whether or not the email has an account, and a deployment without a mail relay answers 503 `EMAIL_NOT_CONFIGURED`.
`verifySession` answers `valid = false` for an invalid or expired token, a token for another project, or an end user who is no longer active; that is an answer, not an error.
`mfa.totp.confirm` throws `GateError` with code `K9O_MFA_REQUIRED` when a second authenticator is added without `currentCode`, and `GateError.InvalidCredentials` on a wrong code.

`mfa.methods.delete` needs the authenticator's current `code` to remove a confirmed authenticator; without it the call throws `GateError` with code `K9O_MFA_REQUIRED`, and a wrong code throws `GateError.InvalidCredentials`. A passkey or an unconfirmed authenticator needs no code.

## B. Sessions

### 4. Token storage

`KeystoreTokenStore` keeps the refresh token in `EncryptedSharedPreferences`, protected by a key in the Android Keystore (AES-256-GCM).
The key never leaves the device.
To store tokens somewhere else, implement `TokenStore` and pass it as `tokenStore`.

## C. Use the token

### 5. Custom claims

Custom claims are flat keys of the form `ext.<namespace>.<name>`. `customClaim(namespace, name)` reads one.

```kotlin
import dev.k9o.gate.AccessToken

val token = AccessToken.decode(result.session.accessToken)
val partnerRole = token?.customClaim("partners", "partner_role")
println(partnerRole)
```

```text
extension_org_admin
```

`customClaims` returns every `ext.` claim keyed by its full name, for example `ext.partners.partner_role`.
`decode` does not verify the signature, so do not use it for security decisions.

## D. Troubleshoot

### 6. Errors

```kotlin
import dev.k9o.gate.GateError

try {
    gate.signIn(email, password)
} catch (e: GateError.InvalidCredentials) {
    // wrong email or password
} catch (e: GateError.RateLimited) {
    // too many attempts: safe to retry shortly
} catch (e: GateError.RegionUnavailable) {
    // the region is down: safe to retry shortly
} catch (e: GateError.Network) {
    // no connection
} catch (e: GateError.Unknown) {
    // anything else: e.code, e.statusCode, e.recommendation, e.body
}
```

Every error has a `publicMessage`: a fixed sentence chosen by status class, the same in every language. An app shows `publicMessage` to its users and logs `requestId`, the gateway's `x-k9o-request-id`, to find the request. `message`, `recommendation` and `context` are for logs. A `K9O_*` code that the SDK does not list is kept as `code` on `GateError.Unknown`; it is not collapsed into a generic error.

| Field or option | Use |
|---|---|
| `publicMessage` | The sentence to show your users. Never built from the gateway's text. |
| `requestId` | The gateway's request id, or `null` when the response had none. Log it. |
| `errors` (client option) | `"full"` (default) keeps the gateway's text on the error. `"redacted"` sets `message` to `publicMessage` and drops `recommendation`, `context` and `body`. Use it when the logs you keep must not hold gateway text. |

## E. Reference

### 7. Appendix

- [About Kilimanjaro](#71-about-kilimanjaro)

#### 7.1 About Kilimanjaro

Kilimanjaro is a modular developer platform for building secure enterprise applications.
You define AI agents, functions and data pipelines on the platform.
The SDKs let you deploy and invoke them, read and write your data, publish to and subscribe to streams, sign users in, and encrypt with keys you hold.

It provides four building blocks:

- **Keep**: AI security and governance. Guardrails and policy for the agents you run.
- **Pulse**: data intelligence and real-time stream processing. Streams, analytics and live feeds.
- **Gate**: customer identity and access management. Sign-in and sessions, and automatic data scoping so a signed-in user reaches only their own data.
- **Kibo**: cryptographic keys and secrets management. Client-side encryption with keys you hold, and a secrets vault.
