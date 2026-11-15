package com.example.mobilelabs.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.example.mobilelabs.model.StreamingPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Full OAuth 2.0 + PKCE (S256) manager for YouTube Live and Twitch 1-click sign-in.
 * Uses Android Chrome Custom Tabs (never WebView, complying with Google OAuth policies).
 */
class OAuthPkceManager(
    private val context: Context,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    data class PendingAuthSession(
        val platform: StreamingPlatform,
        val clientId: String,
        val codeVerifier: String,
        val state: String,
        val redirectUri: String
    )

    data class TokenResult(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMs: Long
    )

    @Volatile
    private var pendingSession: PendingAuthSession? = null

    fun hasPendingAuth(): Boolean = pendingSession != null

    fun launchOAuthLogin(platform: StreamingPlatform, clientId: String): Result<Unit> {
        val trimmedId = clientId.trim()
        if (trimmedId.isBlank()) {
            return Result.failure(
                IllegalStateException(
                    "Missing ${platform.label} OAuth Client ID. Add it in AI Studio Secrets or Connection Settings."
                )
            )
        }
        val verifier = generateCodeVerifier()
        val challenge = computeS256Challenge(verifier)
        val state = "${platform.name.lowercase()}_${generateRandomHex(12)}"
        val redirectUri = redirectUriFor(platform)

        pendingSession = PendingAuthSession(
            platform = platform,
            clientId = trimmedId,
            codeVerifier = verifier,
            state = state,
            redirectUri = redirectUri
        )

        val authUri = buildAuthorizationUri(
            platform = platform,
            clientId = trimmedId,
            redirectUri = redirectUri,
            state = state,
            codeChallenge = challenge
        )

        return runCatching {
            val customTabs = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabs.launchUrl(context, authUri)
        }.recoverCatching {
            val fallback = Intent(Intent.ACTION_VIEW, authUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    suspend fun handleRedirectUri(uri: Uri): Pair<StreamingPlatform?, Result<TokenResult>>? =
        withContext(Dispatchers.IO) {
            val session = pendingSession ?: return@withContext null
            val incomingScheme = uri.scheme.orEmpty()
            val expectedUri = Uri.parse(session.redirectUri)
            if (!incomingScheme.equals(expectedUri.scheme, ignoreCase = true)) {
                return@withContext null
            }

            // Clear pending session once matched so duplicate intents don't replay it
            pendingSession = null

            val error = uri.getQueryParameter("error") ?: fragmentParam(uri.fragment, "error")
            if (!error.isNullOrBlank()) {
                val desc = uri.getQueryParameter("error_description") ?: error
                return@withContext session.platform to Result.failure(IllegalStateException("OAuth canceled: $desc"))
            }

            val returnedState = uri.getQueryParameter("state") ?: fragmentParam(uri.fragment, "state")
            if (returnedState != session.state) {
                return@withContext session.platform to Result.failure(
                    IllegalStateException("OAuth state mismatch — possible stale redirect")
                )
            }

            // Handle fragment implicit token fallback if returned directly
            val fragmentToken = fragmentParam(uri.fragment, "access_token")
            if (!fragmentToken.isNullOrBlank()) {
                val expiresSec = fragmentParam(uri.fragment, "expires_in")?.toLongOrNull() ?: 3600L
                return@withContext session.platform to Result.success(
                    TokenResult(
                        accessToken = fragmentToken,
                        refreshToken = "",
                        expiresAtMs = System.currentTimeMillis() + expiresSec * 1000L
                    )
                )
            }

            val code = uri.getQueryParameter("code")
            if (code.isNullOrBlank()) {
                return@withContext session.platform to Result.failure(
                    IllegalStateException("OAuth callback missing authorization code")
                )
            }

            val tokenRes = exchangeAuthorizationCode(session, code)
            session.platform to tokenRes
        }

    private fun exchangeAuthorizationCode(session: PendingAuthSession, code: String): Result<TokenResult> {
        val tokenEndpoint = when (session.platform) {
            StreamingPlatform.YOUTUBE -> "https://oauth2.googleapis.com/token"
            StreamingPlatform.TWITCH -> "https://id.twitch.tv/oauth2/token"
        }
        val form = FormBody.Builder()
            .add("client_id", session.clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", session.redirectUri)
            .add("code_verifier", session.codeVerifier)
            .build()

        val req = Request.Builder()
            .url(tokenEndpoint)
            .post(form)
            .header("Accept", "application/json")
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = runCatching {
                        val j = JSONObject(body)
                        j.optString("error_description").ifBlank { j.optString("message", body) }
                    }.getOrDefault(body)
                    error("Token exchange failed (${resp.code}): $errMsg")
                }
                val json = JSONObject(body)
                val access = json.getString("access_token")
                val refresh = json.optString("refresh_token", "")
                val expiresInSec = json.optLong("expires_in", 3600L)
                TokenResult(
                    accessToken = access,
                    refreshToken = refresh,
                    expiresAtMs = System.currentTimeMillis() + expiresInSec * 1000L
                )
            }
        }
    }

    suspend fun refreshAccessToken(
        platform: StreamingPlatform,
        clientId: String,
        refreshToken: String
    ): Result<TokenResult> = withContext(Dispatchers.IO) {
        if (refreshToken.isBlank() || clientId.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Missing refresh token or client ID"))
        }
        val tokenEndpoint = when (platform) {
            StreamingPlatform.YOUTUBE -> "https://oauth2.googleapis.com/token"
            StreamingPlatform.TWITCH -> "https://id.twitch.tv/oauth2/token"
        }
        val form = FormBody.Builder()
            .add("client_id", clientId.trim())
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val req = Request.Builder()
            .url(tokenEndpoint)
            .post(form)
            .header("Accept", "application/json")
            .build()
        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("Refresh failed (${resp.code}): $body")
                }
                val json = JSONObject(body)
                TokenResult(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token", refreshToken),
                    expiresAtMs = System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L
                )
            }
        }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        fun redirectUriFor(platform: StreamingPlatform): String = when (platform) {
            StreamingPlatform.YOUTUBE -> "com.mobilelabs.studio:/oauth2redirect"
            StreamingPlatform.TWITCH -> "mobilelabs://oauth2redirect"
        }

        fun buildAuthorizationUri(
            platform: StreamingPlatform,
            clientId: String,
            redirectUri: String,
            state: String,
            codeChallenge: String
        ): Uri = when (platform) {
            StreamingPlatform.YOUTUBE -> Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter(
                    "scope",
                    "https://www.googleapis.com/auth/youtube https://www.googleapis.com/auth/youtube.force-ssl"
                )
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", codeChallenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("access_type", "offline")
                .appendQueryParameter("prompt", "consent")
                .build()

            StreamingPlatform.TWITCH -> Uri.parse("https://id.twitch.tv/oauth2/authorize").buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter(
                    "scope",
                    "channel:read:stream_key channel:manage:broadcast chat:read chat:edit"
                )
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", codeChallenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()
        }

        fun generateCodeVerifier(): String {
            val bytes = ByteArray(48)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        fun computeS256Challenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        private fun generateRandomHex(byteCount: Int): String {
            val bytes = ByteArray(byteCount)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun fragmentParam(fragment: String?, key: String): String? {
            if (fragment.isNullOrBlank()) return null
            return fragment.split('&')
                .mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx <= 0) null else Uri.decode(pair.substring(0, idx)) to Uri.decode(pair.substring(idx + 1))
                }
                .firstOrNull { it.first == key }
                ?.second
        }
    }
}
