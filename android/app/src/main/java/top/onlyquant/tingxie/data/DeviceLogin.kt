package top.onlyquant.tingxie.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import java.security.MessageDigest
import java.security.SecureRandom

/** The redirect the server allows for Android (internal/auth/device.go). */
const val AndroidRedirect = "tingxie://auth/callback"

/** How a browser round trip ended. */
sealed interface LoginResult {
    data class Code(val code: String, val verifier: String, val server: String) : LoginResult
    data object Cancelled : LoginResult
    data object Invalid : LoginResult
}

/**
 * Device sign-in (RFC 8252): a Custom Tab opens /auth/device/login with a
 * PKCE challenge, the user allows the device on the server's confirm page,
 * and the page sends the browser back to tingxie://auth/callback.
 */
object DeviceLogin {
    private val random = SecureRandom()

    private fun token(bytes: Int): String {
        val b = ByteArray(bytes).also(random::nextBytes)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun challenge(verifier: String): String {
        val sum = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(sum, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** The name the confirm page and the device list show, e.g. "Pixel 9". */
    fun deviceName(context: Context): String {
        val named = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        return (named?.trim()?.ifEmpty { null } ?: Build.MODEL).take(64)
    }

    suspend fun start(context: Context, prefs: Prefs, server: String) {
        val pending = PendingLogin(server, token(24), token(32))
        prefs.setPendingLogin(pending)
        val url = Uri.parse("$server/auth/device/login").buildUpon()
            .appendQueryParameter("platform", "android")
            .appendQueryParameter("name", deviceName(context))
            .appendQueryParameter("redirect_uri", AndroidRedirect)
            .appendQueryParameter("state", pending.state)
            .appendQueryParameter("code_challenge", challenge(pending.verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url)
    }

    /** Matches a callback against the pending sign-in, which it consumes. */
    suspend fun finish(prefs: Prefs, uri: Uri): LoginResult {
        val pending = prefs.pendingLogin() ?: return LoginResult.Invalid
        if (uri.getQueryParameter("state") != pending.state) return LoginResult.Invalid
        prefs.setPendingLogin(null)
        if (uri.getQueryParameter("error") != null) return LoginResult.Cancelled
        val code = uri.getQueryParameter("code") ?: return LoginResult.Invalid
        return LoginResult.Code(code, pending.verifier, pending.server)
    }
}
