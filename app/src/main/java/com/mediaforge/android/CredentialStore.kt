package com.mediaforge.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ServerCredentials(
    val baseUrl: String,
    val username: String,
    val password: String = "",
    val deviceToken: String? = null,
)

class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): ServerCredentials? {
        val baseUrl = preferences.getString(KEY_BASE_URL, null) ?: return null
        val username = preferences.getString(KEY_USERNAME, null) ?: return null
        return runCatching {
            val token = preferences.getString(KEY_DEVICE_TOKEN, null)
            if (token != null) ServerCredentials(baseUrl, username, deviceToken = decrypt(token))
            else ServerCredentials(baseUrl, username, decrypt(preferences.getString(KEY_PASSWORD, null) ?: return null))
        }.getOrNull()
    }

    fun save(credentials: ServerCredentials) {
        val edit = preferences.edit().putString(KEY_BASE_URL, credentials.baseUrl).putString(KEY_USERNAME, credentials.username)
        if (credentials.deviceToken != null) {
            edit.putString(KEY_DEVICE_TOKEN, encrypt(credentials.deviceToken)).remove(KEY_PASSWORD)
        } else {
            edit.putString(KEY_PASSWORD, encrypt(credentials.password)).remove(KEY_DEVICE_TOKEN)
        }
        edit.apply()
    }

    fun clear() { preferences.edit().clear().apply() }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val decoded = Base64.decode(value, Base64.NO_WRAP)
        val iv = decoded.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = decoded.copyOfRange(GCM_IV_LENGTH, decoded.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "mediaforge_credentials"
        const val KEY_BASE_URL = "base_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_ALIAS = "mediaforge_credentials_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH = 128
    }
}
