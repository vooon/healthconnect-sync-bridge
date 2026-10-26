package ru.vehq.hcsync.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The Wanderer API token, encrypted at rest with an Android Keystore key.
 *
 * androidx.security:security-crypto is deprecated as of 1.1.0 (no replacement library; Google
 * suggests using the Keystore directly). It still works on Android 14–16 and is the smallest
 * option, so it stays until it breaks. Never log the value read from here.
 */
@Suppress("DEPRECATION")
class Secrets(context: Context) {
    private val prefs: SharedPreferences by lazy {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiToken: String?
        get() = prefs.getString(KEY_API_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_API_TOKEN) else putString(KEY_API_TOKEN, value.trim())
            }.apply()
        }

    private companion object {
        const val KEY_API_TOKEN = "api_token"
    }
}
