package com.example.mobilelabs.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.secretsDataStore by preferencesDataStore(name = "mobilelabs_keystore_secrets")

/**
 * Stores primary & secondary RTMP stream keys encrypted via Android Keystore (AES-256-GCM).
 * Stream keys are never written to logs, crash dumps, or diagnostics exports (Iron Rule R13).
 */
class KeystoreSecretStore(private val context: Context) {

    private val keyAlias = "mobilelabs_rtmp_stream_key_aes"
    private val encryptedKeyPref = stringPreferencesKey("encrypted_rtmp_stream_key")
    private val serverUrlPref = stringPreferencesKey("rtmp_server_url")
    private val presetPref = stringPreferencesKey("rtmp_service_preset")

    // Secondary RTMP destination (M3.4 Dual Destination)
    private val secondaryEncryptedKeyPref = stringPreferencesKey("encrypted_secondary_rtmp_stream_key")
    private val secondaryServerUrlPref = stringPreferencesKey("secondary_rtmp_server_url")
    private val secondaryPresetPref = stringPreferencesKey("secondary_rtmp_service_preset")

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
            // Strip RTMP stream key suffix and query tokens in logs/diagnostics
            out = out.replace(
                Regex("(rtmps?://[^/\\s]+/[^/\\s]+/)([^\\s]+)"),
                "$1[REDACTED]"
            )
            out = out.replace(
                Regex("(streamKey|key|token|auth)=[^&\\s]+", RegexOption.IGNORE_CASE),
                "$1=[REDACTED]"
            )
            return out
        }
    }
}
