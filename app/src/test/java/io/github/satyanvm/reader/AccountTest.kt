package io.github.satyanvm.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import io.github.satyanvm.reader.account.AccountApi
import io.github.satyanvm.reader.account.AccountException
import io.github.satyanvm.reader.account.SyncAccount
import io.github.satyanvm.reader.sync.HttpSyncApi
import io.github.satyanvm.reader.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress

/**
 * Sign-in against a local HTTP server that speaks the sync server's account API
 * (server/src/worker.js): email + password with an emailed code, password reset, Google tokens,
 * sign-out, and a revoked token signing the device out.
 */
@RunWith(RobolectricTestRunner::class)
class AccountTest {
    private lateinit var server: FakeAccountServer
    private lateinit var account: SyncAccount
    private var signIns = 0

    @Before
    fun setUp() {
        server = FakeAccountServer()
        account = newAccount()
    }

    @After
    fun tearDown() = server.stop()

    private fun prefs() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("account-test", Context.MODE_PRIVATE)

    private fun newAccount() = SyncAccount(AccountApi(server.url), prefs(), onSignedIn = { signIns++ })

    @Test
    fun `new account is confirmed with the emailed code, then signed in`() = runBlocking<Unit> {
        account.createAccount("  Reader@Example.com ", "correct horse")
        assertEquals(
            SyncAccount.Stage.AwaitingCode("reader@example.com", SyncAccount.CodePurpose.ConfirmEmail),
            account.stage.value,
        )
        assertFalse(account.isSignedIn)

        account.confirm(server.lastCode!!)
        assertEquals(SyncAccount.Stage.SignedIn("reader@example.com"), account.stage.value)
        assertTrue(account.isSignedIn)
        assertEquals(1, signIns)
    }

    @Test
    fun `wrong code keeps waiting for the right one`() = runBlocking<Unit> {
        account.createAccount("a@example.com", "password1")
        try {
            account.confirm("000000")
            fail("expected invalid_code")
        } catch (e: AccountException) {
            assertEquals("invalid_code", e.code)
        }
        assertTrue(account.stage.value is SyncAccount.Stage.AwaitingCode)
        account.confirm(server.lastCode!!)
        assertTrue(account.isSignedIn)
    }

    @Test
    fun `signing in to an unconfirmed account asks for the new code`() = runBlocking<Unit> {
        server.addAccount("b@example.com", "password1", verified = false)
        account.signIn("b@example.com", "password1")
        assertTrue(account.stage.value is SyncAccount.Stage.AwaitingCode)
        account.confirm(server.lastCode!!)
        assertEquals(SyncAccount.Stage.SignedIn("b@example.com"), account.stage.value)
    }

    @Test
    fun `wrong password is reported and stays signed out`() = runBlocking<Unit> {
        server.addAccount("c@example.com", "password1")
        try {
            account.signIn("c@example.com", "nope-nope")
            fail("expected invalid_credentials")
        } catch (e: AccountException) {
            assertEquals("invalid_credentials", e.code)
            assertEquals("Wrong email or password.", e.message)
        }
        assertEquals(SyncAccount.Stage.SignedOut, account.stage.value)
    }

    @Test
    fun `forgot password sets a new one and signs in`() = runBlocking<Unit> {
        server.addAccount("d@example.com", "old-password")
        account.forgotPassword("d@example.com")
        assertEquals(
            SyncAccount.Stage.AwaitingCode("d@example.com", SyncAccount.CodePurpose.ResetPassword),
            account.stage.value,
        )
        account.resetPassword(server.lastCode!!, "new-password")
        assertTrue(account.isSignedIn)
        assertEquals("new-password", server.passwords["d@example.com"])
    }

    @Test
    fun `google token exchanges for a session`() = runBlocking<Unit> {
        account.signInWithProvider("google", "google-id-token-for:g@example.com")
        assertEquals(SyncAccount.Stage.SignedIn("g@example.com"), account.stage.value)
        assertEquals(1, signIns)
    }

    @Test
    fun `providers say which buttons to show`() = runBlocking<Unit> {
        account.loadProviders()
        assertEquals(AccountApi.Providers(apple = false, google = true), account.providers.value)
    }

    @Test
    fun `session survives an app restart and sign-out ends it on the server`() = runBlocking<Unit> {
        server.addAccount("e@example.com", "password1")
        account.signIn("e@example.com", "password1")
        val token = account.token()!!

        val restarted = newAccount()
        assertEquals(SyncAccount.Stage.SignedIn("e@example.com"), restarted.stage.value)
        assertEquals(token, restarted.token())

        restarted.signOut()
        assertEquals(SyncAccount.Stage.SignedOut, restarted.stage.value)
        assertNull(restarted.token())
        assertFalse(token in server.sessions)
        assertEquals(SyncAccount.Stage.SignedOut, newAccount().stage.value)
    }

    @Test
    fun `server unreachable is reported as offline`() = runBlocking<Unit> {
        val offline = SyncAccount(AccountApi("http://127.0.0.1:1"), prefs())
        try {
            offline.signIn("f@example.com", "password1")
            fail("expected offline")
        } catch (e: AccountException) {
            assertEquals("offline", e.code)
        }
    }

    @Test
    fun `sync uses the signed-in token, and a revoked token signs the device out`() = runBlocking<Unit> {
        val device = Device("phone", server = null)
        val engine = SyncEngine(
            device.repo,
            HttpSyncApi(server.url, account::token),
            account,
            onUnauthorized = account::onUnauthorized,
        )
        device.repo.saveLocal(pos(42))
        // Signed out: nothing is sent, the position waits.
        assertTrue(engine.pushPending())
        assertEquals(0, server.positionPuts)
        assertEquals(1, device.repo.pending().size)

        server.addAccount("h@example.com", "password1")
        account.signIn("h@example.com", "password1")
        assertTrue(engine.pushPending())
        assertEquals(1, server.positionPuts)
        assertTrue(device.repo.pending().isEmpty())

        server.sessions.clear() // e.g. the password was reset on another device
        device.repo.saveLocal(pos(43))
        assertTrue(engine.pushPending())
        assertEquals(SyncAccount.Stage.SignedOut, account.stage.value)
        assertTrue(account.notice.value != null)
        assertEquals(1, device.repo.pending().size)
    }
}

/** Just enough of server/src/worker.js for the client: accounts, codes, sessions, positions. */
private class FakeAccountServer {
    val passwords = mutableMapOf<String, String>()
    private val verified = mutableSetOf<String>()
    private val codes = mutableMapOf<String, String>()
    val sessions = mutableMapOf<String, String>()
    var lastCode: String? = null
    var positionPuts = 0
    private val positions = mutableMapOf<String, JSONObject>()
    private var nextCode = 123456
    private var nextToken = 1

    private val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.bufferedReader().readText()
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)
            val bearer = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
            val (status, out) = synchronized(this@FakeAccountServer) {
                route(ex.requestMethod, ex.requestURI.path, json, bearer)
            }
            val bytes = out.toString().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    val url = "http://127.0.0.1:${http.address.port}"

    fun stop() = http.stop(0)

    fun addAccount(email: String, password: String, verified: Boolean = true) {
        passwords[email] = password
        if (verified) this.verified += email
    }

    private fun sendCode(email: String) {
        val code = (nextCode++).toString()
        codes[email] = code
        lastCode = code
    }

    private fun session(email: String): Pair<Int, JSONObject> {
        val token = "token-${nextToken++}"
        sessions[token] = email
        return 200 to JSONObject().put("token", token).put("account", JSONObject().put("email", email))
    }

    private fun error(status: Int, code: String, message: String) =
        status to JSONObject().put("error", message).put("code", code)

    private fun route(method: String, path: String, b: JSONObject, bearer: String?): Pair<Int, JSONObject> {
        val email = b.optString("email")
        return when ("$method $path") {
            "GET /v1/auth/providers" -> 200 to JSONObject().put("apple", false).put("google", true)
            "POST /v1/auth/signup" -> {
                passwords[email] = b.getString("password")
                sendCode(email)
                202 to JSONObject().put("status", "verification_sent")
            }
            "POST /v1/auth/verify" ->
                if (codes[email] != b.optString("code")) error(400, "invalid_code", "That code isn't right.")
                else {
                    codes.remove(email)
                    verified += email
                    session(email)
                }
            "POST /v1/auth/resend" -> {
                sendCode(email)
                200 to JSONObject()
            }
            "POST /v1/auth/signin" -> when {
                passwords[email] != b.optString("password") -> error(401, "invalid_credentials", "Wrong email or password.")
                email !in verified -> {
                    sendCode(email)
                    error(403, "verification_required", "Confirm your email first.")
                }
                else -> session(email)
            }
            "POST /v1/auth/password/forgot" -> {
                sendCode(email)
                200 to JSONObject()
            }
            "POST /v1/auth/password/reset" ->
                if (codes[email] != b.optString("code")) error(400, "invalid_code", "That code isn't right.")
                else {
                    codes.remove(email)
                    passwords[email] = b.getString("password")
                    sessions.values.removeAll { it == email }
                    session(email)
                }
            "POST /v1/auth/google" -> session(b.getString("idToken").substringAfter("google-id-token-for:"))
            "POST /v1/auth/signout" -> {
                sessions.remove(bearer)
                200 to JSONObject().put("status", "signed_out")
            }
            else -> {
                if (!path.startsWith("/v1/reading-positions/")) return error(404, "not_found", "not found")
                if (bearer == null || bearer !in sessions) return error(401, "unauthorized", "missing or invalid token")
                val id = path.removePrefix("/v1/reading-positions/")
                if (method == "PUT") {
                    positionPuts++
                    positions[id] = b
                }
                positions[id]?.let { 200 to it } ?: error(404, "not_found", "no position")
            }
        }
    }
}
