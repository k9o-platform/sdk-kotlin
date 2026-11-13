package dev.k9o.gate

import java.net.URI

/*
 * Locating the end user's region for password sign-in.
 *
 * The probe in GateAuthClient finds the org's regional API host. An end user can live in another region of the
 * project, so sign-in first asks the project which region holds the address
 * (`POST /@<projectSlug>/gate/locate { email }` at that regional base) and sends the credential to the origin it
 * names. The answer is the same for a known and an unknown address.
 *
 * Rules:
 *   - The located origin is used for that one attempt. It is never cached as authority. The one thing kept is a hint,
 *     per refresh token, so that refresh and sign-out reach the region that issued the session; a stale hint gets a 401.
 *   - An origin is used only if it is `https` (`http` only when the base is itself `http` on a loopback host), has no
 *     userinfo, port, path, query or fragment, and its host is exactly the base host's org, family and apex with the
 *     region code `[a-z]{2}\d{2}` added or swapped in the service label, and that code equals the answer's `region`.
 *     Anything else, or `origin: null`, means the base is used. A password never goes to a host that fails this.
 */

/** Request body of the locate door: the email only. */
internal data class LocateBody(val email: String)

/** The `data` of the locate answer. Typed loosely on purpose: [chooseLocatedOrigin] decides what is acceptable. */
internal data class LocateAnswer(val region: Any? = null, val origin: Any? = null)

private val REGION_CODE = Regex("^[a-z]{2}\\d{2}$")

private fun isLoopback(host: String): Boolean {
    val h = host.lowercase()
    return h == "localhost" || h.endsWith(".localhost") || h == "127.0.0.1" || h == "[::1]" || h == "::1"
}

/**
 * The origin the credential goes to: the located one if it passes the rule, else [base]. Pure; never throws.
 * [region] and [origin] are the fields of the locate answer's `data`.
 */
internal fun chooseLocatedOrigin(base: String, region: Any?, origin: Any?): String {
    try {
        if (origin !is String || region !is String || !REGION_CODE.matches(region)) return base
        val b = URI(base)
        val u = URI(origin)
        val bHost = b.host ?: return base
        val uHost = u.host ?: return base
        val bScheme = b.scheme?.lowercase() ?: return base
        val uScheme = u.scheme?.lowercase() ?: return base
        if (u.rawUserInfo != null || u.port != -1 || u.rawQuery != null || u.rawFragment != null) return base
        if (u.rawPath != "" && u.rawPath != "/") return base
        if (uScheme != "https" && !(uScheme == "http" && bScheme == "http" && isLoopback(bHost) && isLoopback(uHost))) return base
        if (uScheme == bScheme && uHost.equals(bHost, ignoreCase = true) && b.port == -1) return "$bScheme://${bHost.lowercase()}"
        val from = parseApiHost(bHost) ?: return base
        val to = parseApiHost(uHost) ?: return base
        if (to.region != region) return base
        if (from.orgSlug != to.orgSlug || from.family != to.family || from.apex != to.apex) return base
        return "$uScheme://${uHost.lowercase()}"
    } catch (e: Exception) {
        return base
    }
}

/** Per-refresh-token origin hints, bounded to [HINT_LIMIT]; oldest dropped first. A hint, never authority. */
internal object SessionOrigins {
    const val HINT_LIMIT = 64
    private val hints = LinkedHashMap<String, String>()

    /** Remember which origin issued the session with this refresh token. */
    @Synchronized fun remember(refreshToken: String?, origin: String) {
        if (refreshToken.isNullOrEmpty()) return
        hints.remove(refreshToken)
        hints[refreshToken] = origin
        while (hints.size > HINT_LIMIT) hints.remove(hints.keys.first())
    }

    @Synchronized fun hint(refreshToken: String): String? = hints[refreshToken]

    @Synchronized fun forget(refreshToken: String) { hints.remove(refreshToken) }
}
