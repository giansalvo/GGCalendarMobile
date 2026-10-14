package io.github.giansalvo.ggcalendar.core.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthorizationOutcome {
    val email: String

    data class Authorized(override val email: String, val accessToken: String) : AuthorizationOutcome

    /** The user must grant access once: launch [intentSender] and pass the result to [GoogleAuthorizer.tokenFromConsent]. */
    data class NeedsConsent(override val email: String, val intentSender: IntentSender) : AuthorizationOutcome
}

class GoogleAuthException(message: String) : Exception(message)

/**
 * Access to the Google Calendar scope through Play Services' AuthorizationClient. No token is stored by the app:
 * once the user has granted access, [authorize] returns a fresh access token silently for that account.
 */
@Singleton
class GoogleAuthorizer @Inject constructor(@ApplicationContext context: Context) {
    private val client = Identity.getAuthorizationClient(context)

    /** System account picker for Google accounts; the result carries [AccountManager.KEY_ACCOUNT_NAME]. */
    fun chooseAccountIntent(): Intent =
        AccountManager.newChooseAccountIntent(null, null, arrayOf(GOOGLE_ACCOUNT_TYPE), null, null, null, null)

    fun accountFromPicker(data: Intent?): String =
        data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: throw GoogleAuthException("No account selected")

    suspend fun authorize(email: String): AuthorizationOutcome {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(CALENDAR_SCOPE)))
            .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
            .build()
        val result = client.authorize(request).await()
        val pending = result.pendingIntent
        return if (result.hasResolution() && pending != null) {
            AuthorizationOutcome.NeedsConsent(email, pending.intentSender)
        } else {
            AuthorizationOutcome.Authorized(email, result.accessToken ?: throw GoogleAuthException("Google returned no access token"))
        }
    }

    /** Takes the Calendar access of [email] away from this app on Google's side (best effort: a failure is not an error). */
    suspend fun revoke(email: String) {
        runCatching {
            client.revokeAccess(
                RevokeAccessRequest.builder().setAccount(Account(email, GOOGLE_ACCOUNT_TYPE)).setScopes(listOf(Scope(CALENDAR_SCOPE))).build(),
            ).await()
        }
    }

    fun tokenFromConsent(data: Intent?): String =
        client.getAuthorizationResultFromIntent(data).accessToken ?: throw GoogleAuthException("Access not granted")

    companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        const val CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar"
    }
}
