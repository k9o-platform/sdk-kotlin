package dev.k9o.gate

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

// MARK: - Models for the end-user flows

/**
 * WebAuthn options as the gateway returns them. [json] is the complete options object as a JSON string (the form
 * `PublicKeyCredentialCreationOptions` and `PublicKeyCredentialRequestOptions` take on the wire): hand it to the
 * platform's passkey API, for example the `requestJson` of a Credential Manager request. [challenge] is the single-use,
 * short-lived challenge it contains.
 */
data class PasskeyOptions(val challenge: String, val json: String)

/** A passkey the gateway stored. */
data class Passkey(val id: String, val credentialId: String)

/** Returned by [GatePasskeysRegister.finish]. */
data class FinishPasskeyRegistrationResult(val passkey: Passkey)

/** Returned by [GatePasskeysSignIn.finish]. */
data class FinishPasskeySignInResult(val session: Session)

/** An authenticator app being set up. [secret] and [uri] (an `otpauth://` uri for a QR code) are shown once. */
data class TotpEnrollment(val id: String, val secret: String, val uri: String)

/** Returned by [GateMfaTotp.enroll]. The factor does nothing until [GateMfaTotp.confirm] succeeds. */
data class EnrollTotpResult(val factor: TotpEnrollment)

/** A confirmed authenticator. */
data class TotpFactor(val id: String, val verifiedAt: String)

/**
 * Returned by [GateMfaTotp.confirm]. When this is the person's first confirmed authenticator, their other sessions are
 * ended and [session] is a new session for this one; otherwise it is null.
 */
data class ConfirmTotpResult(val factor: TotpFactor, val session: Session?)

/** A second factor the end user has. [type] is `totp` or `webauthn`; [verifiedAt] is null while an authenticator is not yet confirmed. [label] may be null. */
data class MfaMethod(val id: String, val type: String, val label: String?, val verifiedAt: String?, val createdAt: String)

/** Returned by [GateMfaMethods.list], oldest first. */
data class ListMfaMethodsResult(val methods: List<MfaMethod>)

/** Returned by [GateMfaMethods.delete]. [deleted] is false when the end user has no method with that id. */
data class DeleteMfaMethodResult(val deleted: Boolean)

/** The reset request. The answer is the same whether or not the email has an account. */
data class PasswordResetRequested(val requested: Boolean, val expiresAt: String?)

/** Returned by [GatePasswordReset.request]. [debugToken] is present only on a deployment set to return the token (development). */
data class RequestPasswordResetResult(val reset: PasswordResetRequested, val debugToken: String?)

/** Where a person's account lives. */
data class MfaResident(val region: String, val origin: String)

/** A sign-in that needs a second step: a confirmed authenticator code, redeemed with [challengeId]. */
data class MfaChallenge(
    val required: Boolean,
    val challengeId: String,
    val expiresAt: String,
    val methodsAvailable: List<String>,
    val resident: MfaResident,
)

/** Returned by [GatePasswordReset.redeem]: a [session], or, when the account has a confirmed authenticator, an [mfa] challenge and no session. */
data class RedeemPasswordResetResult(
    val redeemed: Boolean,
    val endUserId: String,
    val session: Session?,
    val mfa: MfaChallenge?,
)

/** The user an invitation created. */
data class InvitedUser(val id: String, val email: String)

/** Returned by [GateInvitations.redeem]. */
data class RedeemInvitationResult(val redeemed: Boolean, val user: InvitedUser, val session: Session)

/** The claims of a token [GateClient.verifySession] accepted. */
data class VerifiedClaims(val subjectId: String, val projectId: String, val expiresAt: String)

/**
 * Returned by [GateClient.verifySession]. When [valid] is true, [claims] is set; when it is false, [reason] says why the
 * token was refused (an invalid or expired token, a token for another project, or an end user who is no longer active).
 * A refused token is an answer, not an error.
 */
data class VerifySessionResult(val valid: Boolean, val claims: VerifiedClaims?, val reason: String?)

// MARK: - Request bodies (internal)

internal class EmptyBody
internal data class BeginPasskeySignInBody(val email: String?)
internal data class TotpEnrollBody(val label: String?)
internal data class TotpConfirmBody(val factorId: String, val code: String, val currentCode: String?)
internal data class MfaMethodDeleteBody(val code: String)
internal data class PasswordResetRequestBody(val email: String)
internal data class PasswordResetRedeemBody(val token: String, val newPassword: String)
internal data class InvitationRedeemBody(val token: String, val password: String, val displayName: String?)
internal data class VerifySessionBody(val token: String)

/** `{ "response": <credential JSON>, "label": ... }`. The credential is passed through as the authenticator made it. */
private fun credentialBody(field: String, responseJson: String, label: String? = null): JsonObject {
    val parsed: JsonElement = try {
        JsonParser.parseString(responseJson)
    } catch (e: JsonParseException) {
        throw K9OGateConfigException(
            code = "K9O_INVALID_FIELD_VALUE",
            message = "$field must be the credential as a JSON object",
            context = mapOf("field" to field),
        )
    }
    if (!parsed.isJsonObject) {
        throw K9OGateConfigException(
            code = "K9O_INVALID_FIELD_VALUE",
            message = "$field must be the credential as a JSON object",
            context = mapOf("field" to field),
        )
    }
    return JsonObject().apply {
        add("response", parsed)
        if (label != null) addProperty("label", label)
    }
}

private fun optionsOf(options: JsonObject): PasskeyOptions =
    PasskeyOptions(challenge = options.get("challenge")?.takeIf { it.isJsonPrimitive }?.asString ?: "", json = options.toString())

// MARK: - Passkeys

/**
 * Passkeys (WebAuthn). These methods carry the options and credentials between your app and the gateway; they do not call
 * the platform's passkey API. Hand `begin`'s [PasskeyOptions.json] to that API and pass the credential JSON it makes to
 * `finish`.
 */
class GatePasskeys internal constructor(client: GateAuthClient) {
    val register = GatePasskeysRegister(client)
    val signIn = GatePasskeysSignIn(client)
}

class GatePasskeysRegister internal constructor(private val client: GateAuthClient) {
    /**
     * Start adding a passkey to the signed-in end user. Send the end user's access token as [bearerToken]. The answer is the
     * registration options; the challenge is single-use and short-lived.
     *
     * Mirrors: `POST /@<projectSlug>/gate/passkeys/register/begin`
     */
    suspend fun begin(bearerToken: String): PasskeyOptions =
        optionsOf(client.postBearer<EmptyBody, JsonObject>("passkeys/register/begin", null, bearerToken))

    /**
     * Finish adding a passkey. Send the end user's access token as [bearerToken] and the credential the authenticator made from
     * `begin`'s options as [responseJson] (its JSON form, `RegistrationResponseJSON`). [label] names the passkey (1 to 256
     * characters).
     *
     * Throws [GateError] with code `INVALID_FIELD_VALUE` (400) when the challenge is invalid, expired or already used, or
     * the credential does not verify.
     *
     * Mirrors: `POST /@<projectSlug>/gate/passkeys/register/finish`
     */
    suspend fun finish(responseJson: String, bearerToken: String, label: String? = null): FinishPasskeyRegistrationResult =
        client.postBearer<JsonObject, FinishPasskeyRegistrationResult>(
            "passkeys/register/finish", credentialBody("responseJson", responseJson, label), bearerToken,
        )
}

class GatePasskeysSignIn internal constructor(private val client: GateAuthClient) {
    /**
     * Start a passkey sign-in. No bearer token is sent. Pass [email] to name the account, or null to let the authenticator
     * offer the passkeys it holds for this site (3 to 254 characters).
     *
     * Mirrors: `POST /@<projectSlug>/gate/passkeys/signin/begin`
     */
    suspend fun begin(email: String? = null): PasskeyOptions =
        optionsOf(client.postBearer<BeginPasskeySignInBody, JsonObject>("passkeys/signin/begin", BeginPasskeySignInBody(email), null))

    /**
     * Finish a passkey sign-in. No bearer token is sent. [responseJson] is the assertion the authenticator made from
     * `begin`'s options (its JSON form, `AuthenticationResponseJSON`). The answer carries the [Session]. The client does not
     * store its refresh token; pass it to [GateClient.refreshToken] or keep it yourself.
     *
     * Throws [GateError] with code `INVALID_FIELD_VALUE` (400) when the challenge is invalid, expired or already used, the
     * passkey is unknown, or the assertion does not verify.
     *
     * Mirrors: `POST /@<projectSlug>/gate/passkeys/signin/finish`
     */
    suspend fun finish(responseJson: String): FinishPasskeySignInResult =
        client.postBearer<JsonObject, FinishPasskeySignInResult>(
            "passkeys/signin/finish", credentialBody("responseJson", responseJson), null,
        )
}

// MARK: - Authenticator app (TOTP)

/** Second factors for the signed-in end user. Every call send the end user's access token as the bearer. */
class GateMfa internal constructor(client: GateAuthClient) {
    val totp = GateMfaTotp(client)
    val methods = GateMfaMethods(client)
}

class GateMfaMethods internal constructor(private val client: GateAuthClient) {
    /**
     * The signed-in end user's second factors, oldest first, including authenticators not yet confirmed. Send the end user's
     * access token as [bearerToken].
     *
     * Mirrors: `GET /@<projectSlug>/gate/mfa/methods`
     */
    suspend fun list(bearerToken: String): ListMfaMethodsResult =
        client.get<ListMfaMethodsResult>("mfa/methods", bearerToken)

    /**
     * Remove the second factor [methodId]. Send the end user's access token as [bearerToken]. Removing a confirmed
     * authenticator takes its current [code] (4 to 10 characters); a passkey or an unconfirmed authenticator needs none.
     * [methodId] is placed in the path as one segment.
     *
     * Throws [GateError] with code `K9O_MFA_REQUIRED` when a confirmed authenticator is removed without [code],
     * [GateError.InvalidCredentials] (401) on a wrong or expired code, and `RATE_LIMITED` (429) after too many wrong codes.
     *
     * Mirrors: `DELETE /@<projectSlug>/gate/mfa/methods/{methodId}`
     */
    suspend fun delete(methodId: String, bearerToken: String, code: String? = null): DeleteMfaMethodResult =
        client.deleteBearer<MfaMethodDeleteBody, DeleteMfaMethodResult>(
            "mfa/methods/" + java.net.URLEncoder.encode(methodId, "UTF-8").replace("+", "%20"),
            code?.let { MfaMethodDeleteBody(it) },
            bearerToken,
        )
}

class GateMfaTotp internal constructor(private val client: GateAuthClient) {
    /**
     * Start setting up an authenticator app. The answer is the factor's `id`, the base32 `secret` and the `otpauth://` `uri` for
     * a QR code. The secret is shown only in this answer. [label] names the authenticator (1 to 256 characters; the gateway
     * uses `TOTP` when it is null).
     *
     * Mirrors: `POST /@<projectSlug>/gate/mfa/totp/enroll`
     */
    suspend fun enroll(bearerToken: String, label: String? = null): EnrollTotpResult =
        client.postBearer<TotpEnrollBody, EnrollTotpResult>("mfa/totp/enroll", TotpEnrollBody(label), bearerToken)

    /**
     * Finish setting up an authenticator app with its first [code] (4 to 10 characters). To add a second authenticator, also
     * pass [currentCode] from one already confirmed. When this is the person's first confirmed authenticator, their other
     * sessions are ended and the answer carries a new session.
     *
     * Throws [GateError] with code `K9O_MFA_REQUIRED` when a second authenticator is added without [currentCode], and
     * [GateError.InvalidCredentials] (401) on a wrong or expired code.
     *
     * Mirrors: `POST /@<projectSlug>/gate/mfa/totp/confirm`
     */
    suspend fun confirm(factorId: String, code: String, bearerToken: String, currentCode: String? = null): ConfirmTotpResult =
        client.postBearer<TotpConfirmBody, ConfirmTotpResult>(
            "mfa/totp/confirm", TotpConfirmBody(factorId, code, currentCode), bearerToken,
        )
}

// MARK: - Password reset

/** Password reset by email. No bearer token is sent on either call. */
class GatePasswordReset internal constructor(private val client: GateAuthClient) {
    /**
     * Email a reset link to [email]. The answer is the same whether or not the address has an account, so it does not reveal
     * which do. The token is valid for one hour and works once. A deployment without a mail relay answers 503
     * `EMAIL_NOT_CONFIGURED`. Like [GateClient.signIn], it first asks the project which region holds the email and sends the
     * request there.
     *
     * Mirrors: `POST /@<projectSlug>/gate/password-reset/request`
     */
    suspend fun request(email: String): RequestPasswordResetResult {
        val origin = client.locate(email)
        return client.postBearer<PasswordResetRequestBody, RequestPasswordResetResult>(
            "password-reset/request", PasswordResetRequestBody(email), null, origin,
        )
    }

    /**
     * Set a new password with the [token] from the email. The answer carries a session, or, when the account has a confirmed
     * authenticator, an [MfaChallenge] and no session.
     *
     * Throws [GateError] with code `INVALID_FIELD_VALUE` (400) on a wrong, expired or used token, or a password the project
     * refuses.
     *
     * Mirrors: `POST /@<projectSlug>/gate/password-reset/redeem`
     */
    suspend fun redeem(token: String, newPassword: String): RedeemPasswordResetResult =
        client.postBearer<PasswordResetRedeemBody, RedeemPasswordResetResult>(
            "password-reset/redeem", PasswordResetRedeemBody(token, newPassword), null,
        )
}

// MARK: - Invitations

/** An operator creates an invitation from the Console or the API; the invited person redeems it here. */
class GateInvitations internal constructor(private val client: GateAuthClient) {
    /**
     * Accept an invitation. No bearer token is sent. The [token] from the invitation and a [password] create the end user;
     * [displayName] is optional (1 to 256 characters). The answer is the user's `id` and `email`, and a session.
     *
     * Throws [GateError] with code `INVALID_FIELD_VALUE` (400) on a wrong, expired or used token, or a password the project
     * refuses.
     *
     * Mirrors: `POST /@<projectSlug>/gate/invitations/redeem`
     */
    suspend fun redeem(token: String, password: String, displayName: String? = null): RedeemInvitationResult =
        client.postBearer<InvitationRedeemBody, RedeemInvitationResult>(
            "invitations/redeem", InvitationRedeemBody(token, password, displayName), null,
        )
}
