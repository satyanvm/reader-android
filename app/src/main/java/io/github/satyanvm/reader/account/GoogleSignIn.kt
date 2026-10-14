package io.github.satyanvm.reader.account

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * Sign in with Google through Credential Manager. Google issues the ID token for [webClientId],
 * an OAuth client of type "Web application" (`google.webClientId` in local.properties), which the
 * server must list in GOOGLE_CLIENT_IDS. The same Google Cloud project also needs an "Android"
 * client for this package and the signing certificate's SHA-1, or Google refuses the request.
 */
class GoogleSignIn(private val webClientId: String) {
    val isConfigured: Boolean get() = webClientId.isNotBlank()

    /** Returns Google's ID token (a JWT) for the server to verify. [context] must be an Activity. */
    suspend fun idToken(context: Context): String {
        if (!isConfigured) {
            throw AccountException("google_unavailable", "Sign in with Google isn't set up in this build of Reader (no google.webClientId).")
        }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
            .build()
        val result = try {
            CredentialManager.create(context).getCredential(context, request)
        } catch (e: GetCredentialCancellationException) {
            throw AccountException(CANCELLED, "Sign in with Google was cancelled.")
        } catch (e: NoCredentialException) {
            throw AccountException("google", "There's no Google account on this device to sign in with.")
        } catch (e: GetCredentialException) {
            throw AccountException("google", "Sign in with Google failed: ${e.message ?: e.type}")
        }
        val credential = result.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                return GoogleIdTokenCredential.createFrom(credential.data).idToken
            } catch (_: GoogleIdTokenParsingException) {
                // Falls through to the error below.
            }
        }
        throw AccountException("google", "Google didn't return a sign-in token.")
    }

    companion object {
        /** The person closed the Google sheet; not an error worth showing. */
        const val CANCELLED = "cancelled"
    }
}
