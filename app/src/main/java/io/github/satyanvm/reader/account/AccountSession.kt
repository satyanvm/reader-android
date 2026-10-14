package io.github.satyanvm.reader.account

/**
 * Who is signed in. Sync runs only while [isSignedIn].
 *
 * [SyncAccount] is the real one (email/password or Google); [StaticTokenSession] treats a fixed
 * token as a signed-in account, for tests.
 */
interface AccountSession {
    val isSignedIn: Boolean
    fun token(): String?
}

class StaticTokenSession(private val token: String) : AccountSession {
    override val isSignedIn: Boolean get() = token.isNotBlank()
    override fun token(): String? = token.ifBlank { null }
}
