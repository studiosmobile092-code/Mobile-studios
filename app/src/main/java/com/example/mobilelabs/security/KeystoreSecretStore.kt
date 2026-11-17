package com.example.mobilelabs.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import com.example.mobilelabs.model.StreamingPlatform
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.secretsDataStore by preferencesDataStore(name = "mobilelabs_keystore_secrets")

/**
 * Stores primary & secondary RTMP stream keys plus YouTube & Twitch OAuth tokens / Client IDs
 * encrypted via Android Keystore (AES-256-GCM).
 * Secrets are never written to logs, crash dumps, or diagnostics exports.
 */
class KeystoreSecretStore(private val context: Context) {

    data class PlatformCredentialBundle(
        val accessToken: String = "",
        val refreshToken: String = "",
        val expiresAtMs: Long = 0L,
        val channelName: String = "",
        val broadcasterId: String = "",
        val ingestUrl: String = "",
        val streamKey: String = "",
        val broadcastId: String = "",
        val liveChatId: String = "",
        val streamTitle: String = "Mobile Labs Live Stream",
        val categoryName: String = "Games + Demos",
        val categoryId: String = "",
        val privacyStatus: String = "public",
        val enabledForMultiStream: Boolean = true
    )

    private val keyAlias = "mobilelabs_rtmp_stream_key_aes"
    private val encryptedKeyPref = stringPreferencesKey("encrypted_rtmp_stream_key")
    private val serverUrlPref = stringPreferencesKey("rtmp_server_url")
    private val presetPref = stringPreferencesKey("rtmp_service_preset")

    // Secondary RTMP destination (M3.4 Dual Destination)
    private val secondaryEncryptedKeyPref = stringPreferencesKey("encrypted_secondary_rtmp_stream_key")
    private val secondaryServerUrlPref = stringPreferencesKey("secondary_rtmp_server_url")
    private val secondaryPresetPref = stringPreferencesKey("secondary_rtmp_service_preset")

    private fun platformPrefKey(platform: StreamingPlatform) =
        stringPreferencesKey("encrypted_oauth_${platform.name.lowercase()}")

    private fun clientIdPrefKey(platform: StreamingPlatform) =
        stringPreferencesKey("encrypted_client_id_${platform.name.lowercase()}")

    suspend fun saveCustomClientId(platform: StreamingPlatform, clientId: String) {
        val cleaned = clientId.trim()
        context.secretsDataStore.edit { prefs ->
            val key = clientIdPrefKey(platform)
            if (cleaned.isBlank()) prefs.remove(key) else prefs[key] = encryptString(cleaned)
        }
    }

    suspend fun loadCustomClientId(platform: StreamingPlatform): String = runCatching {
        val encoded = context.secretsDataStore.data.first()[clientIdPrefKey(platform)] ?: return@runCatching ""
        decryptString(encoded)
    }.getOrDefault("")

    suspend fun resolveClientId(platform: StreamingPlatform): String {
        val custom = loadCustomClientId(platform).trim()
        if (custom.isNotBlank()) return custom
        return when (platform) {
            StreamingPlatform.YOUTUBE -> sanitizeBuildConfigSecret(BuildConfig.YOUTUBE_OAUTH_CLIENT_ID)
            StreamingPlatform.TWITCH -> sanitizeBuildConfigSecret(BuildConfig.TWITCH_OAUTH_CLIENT_ID)
        }
    }

    suspend fun savePlatformBundle(platform: StreamingPlatform, bundle: PlatformCredentialBundle) {
        val json = JSONObject().apply {
            put("accessToken", bundle.accessToken)
            put("refreshToken", bundle.refreshToken)
            put("expiresAtMs", bundle.expiresAtMs)
            put("channelName", bundle.channelName)
            put("broadcasterId", bundle.broadcasterId)
            put("ingestUrl", bundle.ingestUrl)
            put("streamKey", bundle.streamKey)
            put("broadcastId", bundle.broadcastId)
            put("liveChatId", bundle.liveChatId)
            put("streamTitle", bundle.streamTitle)
            put("categoryName", bundle.categoryName)
            put("categoryId", bundle.categoryId)
            put("privacyStatus", bundle.privacyStatus)
            put("enabledForMultiStream", bundle.enabledForMultiStream)
        }.toString()
        context.secretsDataStore.edit { prefs ->
            prefs[platformPrefKey(platform)] = encryptString(json)
        }
    }

    suspend fun loadPlatformBundle(platform: StreamingPlatform): PlatformCredentialBundle? = runCatching {
        val encoded = context.secretsDataStore.data.first()[platformPrefKey(platform)] ?: return@runCatching null
        val json = decryptString(encoded)
        if (json.isBlank()) return@runCatching null
        val obj = JSONObject(json)
        PlatformCredentialBundle(
            accessToken = obj.optString("accessToken", ""),
            refreshToken = obj.optString("refreshToken", ""),
            expiresAtMs = obj.optLong("expiresAtMs", 0L),
            channelName = obj.optString("channelName", ""),
            broadcasterId = obj.optString("broadcasterId", ""),
            ingestUrl = obj.optString("ingestUrl", ""),
            streamKey = obj.optString("streamKey", ""),
            broadcastId = obj.optString("broadcastId", ""),
            liveChatId = obj.optString("liveChatId", ""),
            streamTitle = obj.optString("streamTitle", "Mobile Labs Live Stream"),
            categoryName = obj.optString("categoryName", "Games + Demos"),
            categoryId = obj.optString("categoryId", ""),
            privacyStatus = obj.optString("privacyStatus", "public"),
            enabledForMultiStream = obj.optBoolean("enabledForMultiStream", true)
        )
    }.getOrNull()

    suspend fun clearPlatformBundle(platform: StreamingPlatform) {
        context.secretsDataStore.edit { prefs ->
            prefs.remove(platformPrefKey(platform))
        }
    }

    suspend fun saveStreamConfig(presetName: String, serverUrl: String, streamKeyPlain: String?) {
        context.secretsDataStore.edit { prefs ->
            prefs[presetPref] = presetName
            prefs[serverUrlPref] = serverUrl.trim()
            if (streamKeyPlain != null) {
                if (streamKeyPlain.isBlank()) {
                    prefs.remove(encryptedKeyPref)
                } else {
                    prefs[encryptedKeyPref] = encryptString(streamKeyPlain.trim())
                }
            }
        }
    }

    suspend fun saveSecondaryStreamConfig(presetName: String, serverUrl: String, streamKeyPlain: String?) {
        context.secretsDataStore.edit { prefs ->
            prefs[secondaryPresetPref] = presetName
            prefs[secondaryServerUrlPref] = serverUrl.trim()
            if (streamKeyPlain != null) {
                if (streamKeyPlain.isBlank()) {
                    prefs.remove(secondaryEncryptedKeyPref)
                } else {
                    prefs[secondaryEncryptedKeyPref] = encryptString(streamKeyPlain.trim())
                }
            }
        }
    }

    suspend fun loadServerUrl(): String = runCatching {
        context.secretsDataStore.data.first()[serverUrlPref].orEmpty()
    }.getOrDefault("")

    suspend fun loadPresetName(): String? = runCatching {
        context.secretsDataStore.data.first()[presetPref]
    }.getOrNull()

    suspend fun loadStreamKey(): String = runCatching {
        val encoded = context.secretsDataStore.data.first()[encryptedKeyPref] ?: return@runCatching ""
        decryptString(encoded)
    }.getOrDefault("")

    suspend fun hasStreamKey(): Boolean = loadStreamKey().isNotBlank()

    suspend fun getMaskedPreview(): String = maskKey(loadStreamKey())

    suspend fun loadSecondaryServerUrl(): String = runCatching {
        context.secretsDataStore.data.first()[secondaryServerUrlPref].orEmpty()
    }.getOrDefault("")

    suspend fun loadSecondaryPresetName(): String? = runCatching {
        context.secretsDataStore.data.first()[secondaryPresetPref]
    }.getOrNull()

    suspend fun loadSecondaryStreamKey(): String = runCatching {
        val encoded = context.secretsDataStore.data.first()[secondaryEncryptedKeyPref] ?: return@runCatching ""
        decryptString(encoded)
    }.getOrDefault("")

    suspend fun hasSecondaryStreamKey(): Boolean = loadSecondaryStreamKey().isNotBlank()

    suspend fun getSecondaryMaskedPreview(): String = maskKey(loadSecondaryStreamKey())

    fun maskSecret(key: String): String = maskKey(key)

    private fun maskKey(key: String): String {
        if (key.isBlank()) return "Not set"
        return if (key.length <= 4) "••••" else "••••••${key.takeLast(4)}"
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encryptString(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(4 + iv.size + ciphertext.size)
            .putInt(iv.size)
            .put(iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decryptString(encoded: String): String {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(raw)
        val ivLen = buffer.int
        if (ivLen <= 0 || ivLen > 32) return ""
        val iv = ByteArray(ivLen)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    companion object {
        fun sanitizeBuildConfigSecret(raw: String): String {
            val cleaned = raw.trim().trim('"').trim()
            if (cleaned.isBlank() ||
                cleaned == "YOUR_YOUTUBE_OAUTH_CLIENT_ID" ||
                cleaned == "YOUR_TWITCH_OAUTH_CLIENT_ID" ||
                cleaned.startsWith("YOUR_")
            ) {
                return ""
            }
            return cleaned
        }

        fun redactUrlAndKeys(
            input: String,
            streamKey: String? = null,
            extraKeys: List<String> = emptyList()
        ): String {
            var out = input
            if (!streamKey.isNullOrBlank()) {
                out = out.replace(streamKey, "[REDACTED_KEY]")
            }
            extraKeys.filter { it.isNotBlank() }.forEach { extra ->
                out = out.replace(extra, "[REDACTED_KEY]")
            }
            out = out.replace(
                Regex("(rtmps?://[^/\\s]+/(?:[^/\\s]+/)+)([^\\s]+)"),
                "$1[REDACTED]"
            )
            out = out.replace(
                Regex("(streamKey|key|token|auth|access_token|refresh_token)=[^&\\s]+", RegexOption.IGNORE_CASE),
                "$1=[REDACTED]"
            )
            out = out.replace(Regex("oauth:[A-Za-z0-9_\\-]+"), "oauth:[REDACTED]")
            return out
        }
    }
}

