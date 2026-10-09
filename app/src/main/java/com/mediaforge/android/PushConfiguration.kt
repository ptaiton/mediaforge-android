package com.mediaforge.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Only public Firebase options are stored here; service account keys stay on the server. */
object PushConfiguration {
    private const val PREFERENCES = "mediaforge_push_configuration"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun restore(context: Context) {
        val credentials = CredentialStore(context).read() ?: return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getString("server", null) != identity(credentials)) return
        val raw = preferences.getString("firebase", null) ?: return
        runCatching { initialize(context, JSONObject(raw)) }
            .onFailure { Log.w("MediaForgePush", "Unable to restore push configuration") }
    }

    fun refresh(context: Context, credentials: ServerCredentials, cookie: String) {
        val application = context.applicationContext
        executor.execute {
            try {
                val connection = URL("${credentials.baseUrl.trimEnd('/')}/api/mobile/config")
                    .openConnection() as HttpURLConnection
                val result = try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("Cookie", cookie)
                    connection.setRequestProperty("Accept", "application/json")
                    if (connection.responseCode != 200) return@execute
                    connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                } finally { connection.disconnect() }
                mainHandler.post {
                    if (CredentialStore(application).read() != credentials) return@post
                    val config = result.optJSONObject("firebase")
                    if (!result.optBoolean("enabled") || config == null) {
                        clear(application)
                        return@post
                    }
                    runCatching {
                        // Persist before initialization, including for process starts triggered by FCM.
                        application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                            .putString("server", identity(credentials))
                            .putString("firebase", config.toString()).commit()
                        initialize(application, config)
                        val app = FirebaseApp.getInstance()
                        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                            if (CredentialStore(application).read() == credentials &&
                                FirebaseApp.getApps(application).any { it === app }) {
                                PushRegistration.register(application, token)
                            }
                        }
                    }.onFailure { Log.w("MediaForgePush", "Unable to configure push notifications") }
                }
            } catch (_: Exception) {
                // An unavailable server must not erase a working cached configuration.
                Log.w("MediaForgePush", "Unable to refresh push configuration")
            }
        }
    }

    fun projectId(context: Context): String? {
        val credentials = CredentialStore(context).read() ?: return null
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getString("server", null) != identity(credentials)) return null
        return runCatching {
            JSONObject(preferences.getString("firebase", null) ?: return null).getString("project_id")
        }.getOrNull()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }?.let { app ->
            FirebaseMessaging.getInstance().isAutoInitEnabled = false
            FirebaseMessaging.getInstance().deleteToken()
            app.delete()
        }
    }

    private fun identity(credentials: ServerCredentials) = "${credentials.baseUrl}\n${credentials.username}"

    private fun initialize(context: Context, config: JSONObject) {
        val options = FirebaseOptions.Builder()
            .setProjectId(config.getString("project_id"))
            .setApplicationId(config.getString("application_id"))
            .setApiKey(config.getString("api_key"))
            .setGcmSenderId(config.getString("sender_id"))
            .build()
        val existing = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        if (existing != null && existing.options == options) return
        if (existing != null) {
            FirebaseMessaging.getInstance().isAutoInitEnabled = false
            FirebaseMessaging.getInstance().deleteToken()
            existing.delete()
        }
        FirebaseApp.initializeApp(context, options)
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
    }
}
