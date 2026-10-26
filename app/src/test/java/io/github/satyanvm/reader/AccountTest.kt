package io.github.satyanvm.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.annotation.Config
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * Sign-in against a local HTTP server that speaks the sync server's account API
 * (server/src/worker.js): email + password with an emailed code, password reset, Google tokens,
 * sign-out, and a revoked token signing the device out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
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
        assertEquals(AccountApi.Providers(apple = false, google = true, emailCodes = true), account.providers.value)
        server.emailCodes = false
        account.loadProviders()
        assertFalse(account.providers.value.emailCodes)
    }

    @Test
    fun `with email codes off, creating an account signs in at once`() = runBlocking<Unit> {
        server.emailCodes = false
        account.createAccount("  Reader@Example.com ", "correct horse")
        assertEquals(SyncAccount.Stage.SignedIn("reader@example.com"), account.stage.value)
        assertEquals(1, signIns)
        assertNull(server.lastCode)

        val other = newAccount()
        other.signOut()
        other.signIn("reader@example.com", "correct horse")
        assertEquals(SyncAccount.Stage.SignedIn("reader@example.com"), other.stage.value)
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

    @Test
    fun `billing shows the plan, gives a checkout page, and a revoked token signs out`() = runBlocking<Unit> {
        assertNull(account.billing())

        server.addAccount("p@example.com", "password1")
        account.signIn("p@example.com", "password1")
        val free = account.billing()!!
        assertFalse(free.pro)
        assertEquals(listOf("monthly", "yearly"), free.plans)
        assertEquals("https://reader.lemonsqueezy.com/buy/monthly?account=p@example.com", account.checkoutUrl("monthly"))

        server.pro = true
        val pro = account.billing()!!
        assertTrue(pro.pro)
        assertEquals("Monthly", pro.plan)
        assertEquals(1_800_000_000_000, pro.renewsAt)
        assertNull(pro.endsAt)
        assertEquals("https://reader.lemonsqueezy.com/billing", pro.manageUrl)

        server.sessions.clear()
        try {
            account.billing()
            fail("expected unauthorized")
        } catch (e: AccountException) {
            assertEquals("unauthorized", e.code)
        }
        assertEquals(SyncAccount.Stage.SignedOut, account.stage.value)
    }
}

/** Just enough of server/src/worker.js for the client: accounts, codes, sessions, positions. */
private class FakeAccountServer {
    val passwords = mutableMapOf<String, String>()
    private val verified = mutableSetOf<String>()
    private val codes = mutableMapOf<String, String>()
    val sessions = mutableMapOf<String, String>()
    var lastCode: String? = null
    var emailCodes = true
    var positionPuts = 0
    var pro = false
    private val positions = mutableMapOf<String, JSONObject>()
    private var nextCode = 123456
    private var nextToken = 1

    private val http = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val json = if (body.isBlank()) JSONObject() else JSONObject(body)
                val bearer = request.getHeader("Authorization")?.removePrefix("Bearer ")
                val (status, out) = synchronized(this@FakeAccountServer) {
                    route(request.method ?: "GET", request.requestUrl?.encodedPath ?: "/", json, bearer)
                }
                return MockResponse()
                    .setResponseCode(status)
                    .setHeader("Content-Type", "application/json")
                    .setBody(out.toString())
            }
        }
        start()
    }

    val url = http.url("/").toString().trimEnd('/')

    fun stop() = http.shutdown()

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
            "GET /v1/auth/providers" -> 200 to JSONObject().put("apple", false).put("google", true).put("emailCodes", emailCodes)
            "POST /v1/auth/signup" -> if (!emailCodes) {
                if (email in passwords) return error(409, "account_exists", "An account with this email already exists.")
                passwords[email] = b.getString("password")
                verified += email
                session(email)
            } else {
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
            "GET /v1/billing" -> if (bearer !in sessions) error(401, "unauthorized", "missing or invalid token") else
                200 to JSONObject()
                    .put("pro", pro)
                    .put("plan", if (pro) "Monthly" else JSONObject.NULL)
                    .put("renewsAt", if (pro) 1_800_000_000_000 else JSONObject.NULL)
                    .put("endsAt", JSONObject.NULL)
                    .put("manageUrl", if (pro) "https://reader.lemonsqueezy.com/billing" else JSONObject.NULL)
                    .put("plans", org.json.JSONArray(listOf("monthly", "yearly")))
            "POST /v1/billing/checkout" -> if (bearer !in sessions) error(401, "unauthorized", "missing or invalid token") else
                200 to JSONObject().put("url", "https://reader.lemonsqueezy.com/buy/${b.getString("plan")}?account=${sessions[bearer]}")
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
