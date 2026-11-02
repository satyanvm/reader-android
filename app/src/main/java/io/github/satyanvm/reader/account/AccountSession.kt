package io.github.satyanvm.reader.account

/**
 * Who is signed in. Sync runs only while [syncAllowed]: signed in, and (for a real account) with
 * Pro, which sync is part of.
 *
 * [SyncAccount] is the real one (email/password or Google); [StaticTokenSession] treats a fixed
 * token as a signed-in account, for tests.
 */
interface AccountSession {
    val isSignedIn: Boolean
    val syncAllowed: Boolean get() = isSignedIn
    fun token(): String?
}

class StaticTokenSession(private val token: String) : AccountSession {
    override val isSignedIn: Boolean get() = token.isNotBlank()
    override fun token(): String? = token.ifBlank { null }
}
