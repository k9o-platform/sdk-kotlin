package dev.k9o.gate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Kilimanjaro Gate SDK for Android and Kotlin
 *
 * `GateClient` is the primary entry point. Initialise once per project
 * and call `signIn`, `refreshToken`, and `signOut`.
 *
 * Refresh tokens are automatically persisted via [tokenStore] (backed by
 * Android Keystore `EncryptedSharedPreferences` when using the default
 * [KeystoreTokenStore]), and each refresh stores the next one. Pass `null` to manage token persistence yourself.
 *
 * [orgBaseUrl] is optional when [orgSlug] is given. With only [orgSlug] the client uses `https://<orgSlug>.api.k9o.io`;
 * with the platform host `https://api.k9o.io` it adds the slug to the host. A full org address or a custom domain is
 * used as given and [orgSlug] is ignored for the address. With neither, construction throws
 * [K9OGateConfigException] (`K9O_INVALID_BASE_URL`, context `{field: "baseUrl"}`); a malformed [orgSlug] throws it with
 * `K9O_INVALID_FIELD_VALUE` (context `{field: "orgSlug", slug}`).
 *
 * [errors] is `"full"` (default) or `"redacted"`. Any other value throws [K9OGateConfigException] (`K9O_INVALID_FIELD_VALUE`,
 * context `{field: "errors", value}`). Every error carries `publicMessage`, the sentence an app shows its users;
 * `"redacted"` also drops the gateway's text (`message`, `recommendation`, `context`, `body`) from the error.
 *
 * [orgBaseUrl] may be the region-free org address (`https://acme.api.k9o.io`) or the regional one
 * (`https://acme.api-de01.k9o.io`). The client follows the region-free address: the first request makes one
 * credential-free `HEAD` to learn the regional address, and every request then goes there.
 * Regional, localhost and other addresses are used as given.
 *
 * Example:
 * ```kotlin
 * val gate = GateClient(
 *     orgBaseUrl = "https://acme.api.k9o.io",
 *     projectSlug = "my-project",
 *     tokenStore = KeystoreTokenStore(applicationContext),
 * )
 * val result = gate.signIn(email = "user@example.com", password = "s3cr3t")
 * ```
 */
class GateClient(
    orgBaseUrl: String? = null,
    projectSlug: String,
    private val tokenStore: TokenStore? = null,
    httpClient: OkHttpClient = OkHttpClient(),
    orgSlug: String? = null,
    errors: String = "full",
) {
    private val redacted: Boolean = when (errors) {
        "full" -> false
        "redacted" -> true
        else -> throw K9OGateConfigException(
            code = "K9O_INVALID_FIELD_VALUE",
            message = "errors \"$errors\" must be \"full\" or \"redacted\"",
            context = mapOf("field" to "errors", "value" to errors),
        )
    }

    private val client = GateAuthClient(
        orgBaseUrl = resolveOrgBaseUrl(orgBaseUrl, orgSlug),
        projectSlug = projectSlug,
        httpClient = httpClient,
        redacted = redacted,
    )

    /** Passkeys: `passkeys.register.begin/finish`, `passkeys.signIn.begin/finish`. A wire layer; see [GatePasskeys]. */
    val passkeys = GatePasskeys(client)

    /** Authenticator apps: `mfa.totp.enroll`, `mfa.totp.confirm`, `mfa.methods.list`, `mfa.methods.delete`. */
    val mfa = GateMfa(client)

    /** `passwordReset.request`, `passwordReset.redeem`. */
    val passwordReset = GatePasswordReset(client)

    /** `invitations.redeem`. */
    val invitations = GateInvitations(client)

    /** The refresh of the stored token in flight, if any. Loading, sending and storing are one step, so a late caller never sends the old token. */
    private var storedFlight: CompletableDeferred<RefreshResult>? = null
    /** Refreshes of a token passed in by the caller, by token. */
    private val flights = HashMap<String, CompletableDeferred<RefreshResult>>()
    private val flightLock = Mutex()

    // MARK: - Public API surface

    /**
     * Sign in an existing end-user with email and password.
     *
     * Persists the refresh token in the token store on success.
     * Throws [GateError.InvalidCredentials] on bad credentials.
     *
     * Asks the project which region holds the email first (`POST /@<projectSlug>/gate/locate`, email only) and
     * sends the password to that region's host. A 429 or 503 from that step is raised as [GateError.RateLimited]
     * or [GateError.RegionUnavailable], and no password is sent.
     *
     * Mirrors: `POST /@<projectSlug>/gate/signin`
     */
    suspend fun signIn(email: String, password: String): AuthResult {
        val origin = client.locate(email)
        val result: AuthResult = client.post("signin", SignInBody(email, password), origin)
        SessionOrigins.remember(result.session.refreshToken, origin)
        tokenStore?.save(result.session.refreshToken)
        return result
    }

    /**
     * Mint a new access token from the stored (or supplied) refresh token.
     *
     * If [refreshToken] is null, loads the token from the store. Each refresh replaces the refresh token: the result
     * carries the next one, the client stores it before this call returns, and the one you sent stops working.
     * Refreshes run one at a time: a call made while another is in flight for the same token shares that answer, so a
     * token is never sent twice (the gateway treats a second use as a replay and ends the session). The result has no
     * `user`; read the subject from the access token with [AccessToken.decode].
     *
     * When the gateway has ended the session this throws [GateError.Unauthenticated] with [GateError.sessionEndReason]
     * set (`inactive`, `max_length`, `revoked` or `replay`), and the stored token is cleared: the user must sign in
     * again. [GateError.InvalidCredentials] (an unknown token or a locked user) clears it the same way.
     *
     * Mirrors: `POST /@<projectSlug>/gate/refresh` with body `{ refreshToken }`.
     */
    suspend fun refreshToken(refreshToken: String? = null): RefreshResult {
        val mine = CompletableDeferred<RefreshResult>()
        val shared = flightLock.withLock {
            val running = if (refreshToken == null) storedFlight else flights[refreshToken]
            if (running == null) {
                if (refreshToken == null) storedFlight = mine else flights[refreshToken] = mine
            }
            running
        }
        if (shared != null) return shared.await()
        try {
            mine.complete(refreshOnce(refreshToken))
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
        } finally {
            flightLock.withLock { if (refreshToken == null) storedFlight = null else flights.remove(refreshToken) }
        }
        return mine.await()
    }

    private suspend fun refreshOnce(explicit: String?): RefreshResult {
        val stored = tokenStore?.load()
        val token = explicit ?: stored ?: throw noSessionError(redacted)
        val origin = client.originFor(SessionOrigins.hint(token))
        val result: RefreshResult = try {
            client.post("refresh", RefreshTokenBody(token), origin)
        } catch (e: GateError) {
            if (e.status == 401 && (e is GateError.Unauthenticated || e is GateError.InvalidCredentials)) {
                SessionOrigins.forget(token)
                if (token == stored) tokenStore?.delete()
            }
            throw e
        }
        // The gateway has replaced the token: store the new one even if the caller is cancelled now.
        withContext(NonCancellable) {
            val next: String? = result.refreshToken
            if (!next.isNullOrEmpty()) {
                SessionOrigins.forget(token)
                SessionOrigins.remember(next, origin)
                if (token == stored || explicit == null) tokenStore?.save(next)
            }
        }
        return result
    }

    /**
     * Sign out the current session. Clears the token store.
     *
     * If [refreshToken] is null, loads the token from the store; with none, nothing is sent.
     * Returns true when the gateway revoked a live session and false when the token was unknown
     * or already revoked (sign-out is idempotent; the store is cleared either way).
     *
     * Mirrors: `POST /@<projectSlug>/gate/signout` with body `{ refreshToken }`.
     */
    suspend fun signOut(refreshToken: String? = null): Boolean {
        // A refresh in flight is about to replace the stored token; sign out the new one.
        if (refreshToken == null) flightLock.withLock { storedFlight }?.let { runCatching { it.await() } }
        val token = refreshToken ?: tokenStore?.load() ?: return false
        val result: SignOutResponse = client.post("signout", SignOutBody(token), client.originFor(SessionOrigins.hint(token)))
        SessionOrigins.forget(token)
        tokenStore?.delete()
        return result.revoked
    }

    /**
     * Check an end user's access token from your backend. The call itself is authenticated with [bearerToken]; the token to
     * check is [token] and goes in the body. The answer is `valid = true` with the token's claims, or `valid = false` with a
     * reason for an invalid or expired token, a token for another project, or an end user who is no longer active. A refused
     * token is an answer, not an error.
     *
     * Mirrors: `POST /@<projectSlug>/gate/verify-session`
     */
    suspend fun verifySession(token: String, bearerToken: String): VerifySessionResult =
        client.postBearer<VerifySessionBody, VerifySessionResult>("verify-session", VerifySessionBody(token), bearerToken)

    /**
     * End every live session of this end user, on every device, this one included, and clear the token store.
     *
     * Call it with the end user's [accessToken] (the one [signIn] or [refreshToken] returned), not a refresh token.
     * Returns how many sessions ended. Each ended session's next refresh is refused with [GateError.sessionEndReason]
     * `revoked`; access tokens already issued keep working until they expire, so drop yours too. An operator's token
     * throws [GateError.Unknown] with code `K9O_TRUST_ZONE_VIOLATION` (403), and a token issued for another project
     * one with code `K9O_PROJECT_SCOPE_MISMATCH` (403); the store is left as it was.
     *
     * A refresh in flight finishes first, so it cannot store a token for a session that is about to end.
     *
     * Mirrors: `POST /@<projectSlug>/auth/sessions/revoke-all`
     */
    suspend fun revokeAllSessions(accessToken: String): Int {
        flightLock.withLock { storedFlight }?.let { runCatching { it.await() } }
        val stored = tokenStore?.load()
        val result: RevokeAllResponse = client.postAuthorized(
            "sessions/revoke-all", accessToken, client.originFor(stored?.let { SessionOrigins.hint(it) }),
        )
        if (stored != null) SessionOrigins.forget(stored)
        tokenStore?.delete()
        return result.revokedCount
    }
}

/**
 * Minimal token-store interface for injection/testing.
 * [KeystoreTokenStore] provides the Android Keystore-backed implementation.
 */
interface TokenStore {
    fun save(token: String)
    fun load(): String?
    fun delete()
}
