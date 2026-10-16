package io.github.satyanvm.reader.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Sign in, create an account, confirm the emailed code, reset a password, or (signed in) see the
 * account and sign out. The same flows as the Mac app's Settings > Sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    account: SyncAccount,
    google: GoogleSignIn,
    onBack: () -> Unit,
) {
    val stage by account.stage.collectAsStateWithLifecycle()
    val providers by account.providers.collectAsStateWithLifecycle()
    val notice by account.notice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { account.loadProviders() }

    fun submit(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        info = null
        scope.launch {
            try {
                action()
            } catch (e: AccountException) {
                if (e.code != GoogleSignIn.CANCELLED) error = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            notice?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            when (val s = stage) {
                is SyncAccount.Stage.SignedIn -> SignedInContent(
                    email = s.email,
                    busy = busy,
                    onSignOut = { submit { account.signOut() } },
                )
                is SyncAccount.Stage.AwaitingCode -> CodeContent(
                    stage = s,
                    busy = busy,
                    onConfirm = { code -> submit { account.confirm(code) } },
                    onReset = { code, password -> submit { account.resetPassword(code, password) } },
                    onResend = {
                        submit {
                            account.resendCode()
                            info = "A new code is on its way to ${s.email}."
                        }
                    },
                    onCancel = {
                        error = null
                        account.cancelCode()
                    },
                )
                SyncAccount.Stage.SignedOut -> SignedOutContent(
                    busy = busy,
                    showGoogle = providers.google && google.isConfigured,
                    emailCodes = providers.emailCodes,
                    onSignIn = { email, password ->
                        account.dismissNotice()
                        submit { account.signIn(email, password) }
                    },
                    onCreate = { email, password ->
                        account.dismissNotice()
                        submit { account.createAccount(email, password) }
                    },
                    onForgot = { email -> submit { account.forgotPassword(email) } },
                    onGoogle = {
                        account.dismissNotice()
                        submit { account.signInWithProvider("google", google.idToken(context)) }
                    },
                    onInvalid = { error = it },
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            info?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun SignedInContent(email: String, busy: Boolean, onSignOut: () -> Unit) {
    Text("Signed in as", style = MaterialTheme.typography.labelLarge)
    Text(email.ifEmpty { "your account" }, style = MaterialTheme.typography.titleMedium)
    Text(
        "Your books and your place in each one sync automatically with your other devices signed in to this account. Books you add here are uploaded, and books added elsewhere download to this device.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(onClick = onSignOut, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
}

private enum class SignedOutMode { SignIn, Create, Forgot }

@Composable
private fun SignedOutContent(
    busy: Boolean,
    showGoogle: Boolean,
    emailCodes: Boolean,
    onSignIn: (String, String) -> Unit,
    onCreate: (String, String) -> Unit,
    onForgot: (String) -> Unit,
    onGoogle: () -> Unit,
    onInvalid: (String) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(SignedOutMode.SignIn) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Text(
        when (mode) {
            SignedOutMode.SignIn -> "Sign in to sync your books and reading positions with your other devices."
            SignedOutMode.Create ->
                if (emailCodes) "Create an account. We'll email you a code to confirm the address."
                else "Create an account to sync your books and reading positions with your other devices."
            SignedOutMode.Forgot -> "Enter your email and we'll send a code to set a new password."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    if (mode != SignedOutMode.Forgot) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            supportingText = if (mode == SignedOutMode.Create) {
                { Text("At least $MIN_PASSWORD characters") }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(
        onClick = {
            val e = email.trim()
            when {
                !e.contains('@') -> onInvalid("Enter your email address.")
                mode == SignedOutMode.Forgot -> onForgot(e)
                password.isEmpty() -> onInvalid("Enter your password.")
                mode == SignedOutMode.Create && password.length < MIN_PASSWORD ->
                    onInvalid("Use at least $MIN_PASSWORD characters for the password.")
                mode == SignedOutMode.Create -> onCreate(e, password)
                else -> onSignIn(e, password)
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when (mode) {
                SignedOutMode.SignIn -> if (busy) "Signing in…" else "Sign in"
                SignedOutMode.Create -> if (busy) "Creating account…" else "Create account"
                SignedOutMode.Forgot -> if (busy) "Sending code…" else "Send code"
            },
        )
    }
    when (mode) {
        SignedOutMode.SignIn -> {
            // Resetting a password needs an emailed code.
            if (emailCodes) TextButton(onClick = { mode = SignedOutMode.Forgot }) { Text("Forgot password?") }
            TextButton(onClick = { mode = SignedOutMode.Create }) { Text("New here? Create an account") }
        }
        else -> TextButton(onClick = { mode = SignedOutMode.SignIn }) { Text("Back to sign in") }
    }
    if (showGoogle && mode != SignedOutMode.Forgot) {
        HorizontalDivider()
        OutlinedButton(onClick = onGoogle, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Continue with Google")
        }
    }
}

@Composable
private fun CodeContent(
    stage: SyncAccount.Stage.AwaitingCode,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onReset: (String, String) -> Unit,
    onResend: () -> Unit,
    onCancel: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    val reset = stage.purpose == SyncAccount.CodePurpose.ResetPassword

    Text(
        "We emailed a 6-digit code to ${stage.email}. " +
            if (reset) "Enter it with a new password." else "Enter it to confirm your account.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = code,
        onValueChange = { v -> code = v.filter(Char::isDigit).take(6) },
        label = { Text("Code") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    if (reset) {
        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = { Text("New password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            supportingText = { Text("At least $MIN_PASSWORD characters") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(
        onClick = { if (reset) onReset(code, newPassword) else onConfirm(code) },
        enabled = !busy && code.length == 6 && (!reset || newPassword.length >= MIN_PASSWORD),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (reset) "Set password and sign in" else "Confirm")
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onResend, enabled = !busy) { Text("Send a new code") }
    TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
}

/** Matches the server's minimum (server/src/auth.js). */
private const val MIN_PASSWORD = 8
