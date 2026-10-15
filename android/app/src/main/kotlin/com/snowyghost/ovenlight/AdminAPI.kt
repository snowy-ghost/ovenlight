package com.snowyghost.ovenlight

import kotlinx.serialization.Serializable

/**
 * What went wrong, as the connector names it: `code` in its JSON errors, or the
 * `Ovenlight-Error` header. Only a code this version knows acts; anything else is an unknown
 * failure that never ends access or deletes anything.
 */
enum class ConnectorError(val code: String) {
    NotInvited("not_invited"),
    UsedElsewhere("used_elsewhere"),
    OtherPerson("other_person"),
    OwnerOnly("owner_only"),
    NotFound("not_found"),
    BadRequest("bad_request"),
    RateLimited("rate_limited"),
    TooLarge("too_large"),
    Unavailable("unavailable"),
    ;

    companion object {
        const val header = "Ovenlight-Error"

        /** The code in a JSON error body, else the header's. */
        operator fun invoke(header: String?, body: ByteArray): ConnectorError? {
            val code = errorBody(body)?.code ?: header ?: return null
            val trimmed = code.trimmingWhitespace()
            return entries.firstOrNull { it.code == trimmed }
        }

        private fun errorBody(body: ByteArray): ErrorBody? = try {
            ovenlightJSON.decodeFromString(ErrorBody.serializer(), strictUTF8(body))
        } catch (_: Exception) {
            null
        }
    }
}

/** The connector's JSON error: its words, and the code to act on. */
@Serializable
private class ErrorBody(val error: String? = null, val code: String? = null)
