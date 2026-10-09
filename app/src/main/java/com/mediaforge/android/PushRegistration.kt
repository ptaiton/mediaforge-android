package com.mediaforge.android

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object PushRegistration {
    private val executor = Executors.newSingleThreadExecutor()

    fun register(context: Context, token: String) {
        executor.execute {
            val credentials = CredentialStore(context.applicationContext).read() ?: return@execute
            val projectId = PushConfiguration.projectId(context.applicationContext) ?: return@execute
            runCatching {
                val session = MobileSession.authenticate(credentials)
                check(session.status in 200..299 && session.cookies.isNotEmpty()) { "Mobile authentication failed" }
                if (CredentialStore(context.applicationContext).read() != credentials) return@execute
                val cookie = session.cookies.joinToString("; ") { it.substringBefore(';') }
                val connection = URL("${credentials.baseUrl.trimEnd('/')}/api/mobile/push-tokens")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Cookie", cookie)
                val body = JSONObject()
                    .put("token", token)
                    .put("platform", "android")
                    .put("project_id", projectId)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                connection.outputStream.use { it.write(body) }
                connection.responseCode
                connection.disconnect()
            }
        }
    }

}
