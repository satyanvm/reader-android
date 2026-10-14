package io.github.satyanvm.reader.account

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Reader account on this device, the same account as on the Mac. Signing in (email and
 * password confirmed with an emailed code, or Google) gets a session from the sync server; every
 * device signed in to the same account keeps the same reading positions.
 *
 * This is the [AccountSession] sync uses, so signing in or out turns sync on or off at once.
 * [fallbackToken] (`sync.token` in local.properties) stands in while no account is signed in, for
 * testing against a server with a token from `server/bin/admin.js`.
 *
 * The session is kept in [prefs], private to this app.
 */
class SyncAccount(
    private val api: AccountApi,
    private val prefs: SharedPreferences,
    private val fallbackToken: String = "",
    /** Called after every sign-in, so reading done before it goes up straight away. */
    private val onSignedIn: () -> Unit = {},
) : AccountSession {

    sealed interface Stage {
        data object SignedOut : Stage

        /** A code was emailed: to confirm a new account, or to reset the password. */
        data class AwaitingCode(val email: String, val purpose: CodePurpose) : Stage
        data class SignedIn(val email: String) : Stage
    }

    enum class CodePurpose { ConfirmEmail, ResetPassword }

    @Volatile private var session: AccountSessionInfo? = load()

    private val _stage = MutableStateFlow<Stage>(session?.let { Stage.SignedIn(it.email) } ?: Stage.SignedOut)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    private val _providers = MutableStateFlow(AccountApi.Providers(apple = false, google = false))
    val providers: StateFlow<AccountApi.Providers> = _providers.asStateFlow()

    /** Set when the server ended this session (password changed, signed out everywhere). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    override val isSignedIn: Boolean get() = token() != null
    override fun token(): String? = session?.token ?: fallbackToken.ifBlank { null }

    suspend fun loadProviders() {
        runCatching { api.providers() }.onSuccess { _providers.value = it }
    }

    // Email and password

    suspend fun createAccount(email: String, password: String) {
        val email = clean(email)
        val session = api.signUp(email, password)
        if (session != null) signedIn(session) else _stage.value = Stage.AwaitingCode(email, CodePurpose.ConfirmEmail)
    }

    suspend fun signIn(email: String, password: String) {
        val email = clean(email)
        try {
            signedIn(api.signIn(email, password))
        } catch (e: AccountException) {
            if (e.code != "verification_required") throw e
            // The server has emailed a fresh code; confirming it finishes signing in.
            _stage.value = Stage.AwaitingCode(email, CodePurpose.ConfirmEmail)
        }
    }

    suspend fun confirm(code: String) {
        val stage = _stage.value as? Stage.AwaitingCode ?: return
        if (stage.purpose != CodePurpose.ConfirmEmail) return
        signedIn(api.verify(stage.email, code.trim()))
    }

    suspend fun resendCode() {
        val stage = _stage.value as? Stage.AwaitingCode ?: return
        when (stage.purpose) {
            CodePurpose.ConfirmEmail -> api.resendCode(stage.email)
            CodePurpose.ResetPassword -> api.forgotPassword(stage.email)
        }
    }

    suspend fun forgotPassword(email: String) {
        val email = clean(email)
        api.forgotPassword(email)
        _stage.value = Stage.AwaitingCode(email, CodePurpose.ResetPassword)
    }

    suspend fun resetPassword(code: String, newPassword: String) {
        val stage = _stage.value as? Stage.AwaitingCode ?: return
        if (stage.purpose != CodePurpose.ResetPassword) return
        signedIn(api.resetPassword(stage.email, code.trim(), newPassword))
    }

    fun cancelCode() {
        if (_stage.value is Stage.AwaitingCode) _stage.value = Stage.SignedOut
    }

    // Google (and Apple, which the server also accepts)

    suspend fun signInWithProvider(provider: String, idToken: String) {
        signedIn(api.signInWithProvider(provider, idToken))
    }

    // Session

    suspend fun signOut() {
        val token = session?.token
        clear()
        if (token != null) api.signOut(token)
    }

    /** The server rejected our token: the session is over on its side, so end it here too. */
    fun onUnauthorized() {
        if (session == null) return
        clear()
        _notice.value = "You were signed out (your password changed, or you signed out everywhere). Sign in again to keep syncing."
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private fun signedIn(info: AccountSessionInfo) {
        session = info
        save(info)
        _notice.value = null
        _stage.value = Stage.SignedIn(info.email)
        onSignedIn()
    }

    private fun clear() {
        session = null
        prefs.edit().remove(KEY_TOKEN).remove(KEY_EMAIL).commit()
        _stage.value = Stage.SignedOut
    }

    private fun load(): AccountSessionInfo? {
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return AccountSessionInfo(token, prefs.getString(KEY_EMAIL, null).orEmpty())
    }

    private fun save(info: AccountSessionInfo) {
        prefs.edit().putString(KEY_TOKEN, info.token).putString(KEY_EMAIL, info.email).commit()
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_EMAIL = "email"

        fun clean(email: String) = email.trim().lowercase()
    }
}
