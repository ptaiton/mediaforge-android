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
                val cookie = login(credentials)
                val connection = URL("${credentials.baseUrl.trimEnd('/')}/api/mobile/push-tokens")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
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

    private fun login(credentials: ServerCredentials): String {
        val connection = URL("${credentials.baseUrl.trimEnd('/')}/api/auth/login")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        val body = JSONObject()
            .put("username", credentials.username)
            .put("password", credentials.password)
            .toString()
            .toByteArray(Charsets.UTF_8)
        connection.outputStream.use { it.write(body) }
        val responseCode = connection.responseCode
        val cookie = connection.headerFields
            .filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
            .values
            .flatMap { it.orEmpty() }
            .joinToString("; ") { it.substringBefore(';') }
        runCatching {
            (if (responseCode >= 400) connection.errorStream else connection.inputStream)?.close()
        }
        connection.disconnect()
        check(responseCode in 200..299 && cookie.isNotEmpty()) { "Authentification impossible" }
        return cookie
    }
}
