package io.github.satyanvm.reader.account

/**
 * Who is signed in. Sync runs only while [isSignedIn].
 *
 * Real sign-in isn't built yet; [StaticTokenSession] treats a token configured at build time as
 * a signed-in account, which is enough to exercise the sync path against a backend.
 */
interface AccountSession {
    val isSignedIn: Boolean
    fun token(): String?
}

class StaticTokenSession(private val token: String) : AccountSession {
    override val isSignedIn: Boolean get() = token.isNotBlank()
    override fun token(): String? = token.ifBlank { null }
}
