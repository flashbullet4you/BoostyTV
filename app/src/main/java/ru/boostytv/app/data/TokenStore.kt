package ru.boostytv.app.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ru.boostytv.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Хранение токенов Boosty и обновление по refresh_token.
 * Токены лежат в EncryptedSharedPreferences; refresh блокируется мьютексом,
 * чтобы параллельные запросы не сделали два refresh и не убили сессию.
 */
class TokenStore(context: Context, private val client: OkHttpClient = defaultClient()) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val refreshLock = Mutex()

    val accessToken: String get() = prefs.getString(KEY_ACCESS, "").orEmpty()
    val refreshToken: String get() = prefs.getString(KEY_REFRESH, "").orEmpty()
    val deviceId: String get() = prefs.getString(KEY_DEVICE, "").orEmpty()
    val userAgent: String get() = prefs.getString(KEY_UA, BuildConfig.DESKTOP_UA).orEmpty()

    val isLoggedIn: Boolean get() = accessToken.isNotBlank() && refreshToken.isNotBlank()

    fun saveTokens(
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
        deviceId: String? = null,
    ) {
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_EXPIRES, expiresAt)
            .putString(KEY_UA, BuildConfig.DESKTOP_UA)
            .apply { if (deviceId != null) putString(KEY_DEVICE, deviceId) }
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun expiresAt(): Long = prefs.getLong(KEY_EXPIRES, 0L)

    /** Токен, пригодный для запроса: при необходимости обновляется. */
    suspend fun validAccessToken(): String {
        if (!isLoggedIn) throw IllegalStateException("Нет токенов — нужен вход")
        if (expiresAt() > System.currentTimeMillis() + 60_000) return accessToken
        return refresh()
    }

    /** Обновление по refresh_token. Возвращает новый access token. */
    suspend fun refresh(): String = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("device_id", deviceId)
                .add("device_os", "web")
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()
            val request = Request.Builder()
                .url("${BuildConfig.BOOSTY_API_URL}/oauth/token/")
                .post(body)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .apply { if (accessToken.isNotBlank()) header("Authorization", "Bearer $accessToken") }
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "refresh failed: ${response.code}")
                    throw IllegalStateException("Не удалось обновить токен (${response.code})")
                }
                val payload = Json.parseToJsonElement(response.body!!.string()) as JsonObject
                val newAccess = payload["access_token"]!!.jsonPrimitive.content
                val newRefresh = payload["refresh_token"]?.jsonPrimitive?.content ?: refreshToken
                val expiresIn = payload["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
                saveTokens(
                    accessToken = newAccess,
                    refreshToken = newRefresh,
                    expiresAt = System.currentTimeMillis() + expiresIn * 1000,
                )
                newAccess
            }
        }
    }

    companion object {
        private const val TAG = "TokenStore"
        private const val FILE_NAME = "boosty_auth"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_UA = "user_agent"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
