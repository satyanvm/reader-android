package io.github.satyanvm.reader.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A signed-in session: the bearer token for the sync API and who it belongs to. */
data class AccountSessionInfo(val token: String, val email: String)

/** What the server said went wrong, with its stable [code] (e.g. "verification_required"). */
class AccountException(val code: String, message: String) : Exception(message)

/**
 * The account endpoints of the sync server (server/src/worker.js):
 *
 *   POST /v1/auth/signup           { email, password }        -> 202, code emailed
 *   POST /v1/auth/verify           { email, code }            -> session
 *   POST /v1/auth/resend           { email }
 *   POST /v1/auth/signin           { email, password }        -> session | 403 verification_required
 *   POST /v1/auth/password/forgot  { email }                  -> code emailed
 *   POST /v1/auth/password/reset   { email, code, password }  -> session
 *   POST /v1/auth/google           { idToken }                -> session
 *   GET  /v1/auth/providers        -> { apple, google }
 *   POST /v1/auth/signout          ends this session
 */
class AccountApi(
    private val baseUrl: String,
    private val timeoutMs: Int = 15_000,
) {
    data class Providers(val apple: Boolean, val google: Boolean)

    suspend fun providers(): Providers {
        val o = call("GET", "v1/auth/providers")
        return Providers(o.optBoolean("apple"), o.optBoolean("google"))
    }

    /** Creates the account (or refreshes an unconfirmed one) and emails a code. */
    suspend fun signUp(email: String, password: String) {
        call("POST", "v1/auth/signup", mapOf("email" to email, "password" to password))
    }

    suspend fun verify(email: String, code: String): AccountSessionInfo =
        session(call("POST", "v1/auth/verify", mapOf("email" to email, "code" to code)))

    suspend fun resendCode(email: String) {
        call("POST", "v1/auth/resend", mapOf("email" to email))
    }

    /**
     * Throws `AccountException(code = "verification_required")` for an unconfirmed email (the
     * server has sent a new code).
     */
    suspend fun signIn(email: String, password: String): AccountSessionInfo =
        session(call("POST", "v1/auth/signin", mapOf("email" to email, "password" to password)))

    suspend fun forgotPassword(email: String) {
        call("POST", "v1/auth/password/forgot", mapOf("email" to email))
    }

    suspend fun resetPassword(email: String, code: String, password: String): AccountSessionInfo =
        session(call("POST", "v1/auth/password/reset", mapOf("email" to email, "code" to code, "password" to password)))

    /** Exchanges a provider's ID token ("google" or "apple") for a session. */
    suspend fun signInWithProvider(provider: String, idToken: String): AccountSessionInfo =
        session(call("POST", "v1/auth/$provider", mapOf("idToken" to idToken)))

    suspend fun signOut(token: String) {
        try {
            call("POST", "v1/auth/signout", emptyMap(), token = token)
        } catch (_: AccountException) {
            // Best effort: the local session is gone either way.
        }
    }

    private fun session(o: JSONObject): AccountSessionInfo {
        val token = o.optString("token")
        if (token.isEmpty()) throw AccountException("bad_response", "The sync server didn't return a session.")
        val email = o.optJSONObject("account")?.optString("email").orEmpty()
        return AccountSessionInfo(token, email)
    }

    private suspend fun call(
        method: String,
        path: String,
        body: Map<String, String>? = null,
        token: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val conn = URL(baseUrl.trimEnd('/') + "/" + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(JSONObject(body).toString().toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (status !in 200..299) {
                throw AccountException(
                    json?.optString("code")?.ifEmpty { null } ?: "http_$status",
                    json?.optString("error")?.ifEmpty { null } ?: "The sync server answered with an error ($status).",
                )
            }
            json ?: JSONObject()
        } catch (e: IOException) {
            throw AccountException("offline", "Couldn't reach the sync server. Check your connection and try again.")
        } finally {
            conn.disconnect()
        }
    }
}
