package dev.k9o.gate

/**
 * Thrown when the client is configured wrongly: no `orgBaseUrl` and no `orgSlug` (`K9O_INVALID_BASE_URL`, context
 * `{field: "baseUrl"}`), or an `orgSlug` that is not a valid slug (`K9O_INVALID_FIELD_VALUE`, context
 * `{field: "orgSlug", slug}`), or an `errors` value that is not `"full"` or `"redacted"` (`K9O_INVALID_FIELD_VALUE`,
 * context `{field: "errors", value}`). It extends [IllegalArgumentException], so code that catches that type keeps working.
 */
class K9OGateConfigException(
    val code: String,
    message: String,
    val context: Map<String, Any?> = emptyMap(),
) : IllegalArgumentException(message) {
    override fun toString(): String = "K9OGateConfigException($code): $message"
}
