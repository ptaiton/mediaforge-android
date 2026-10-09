package com.mediaforge.android

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MobileSessionResult(val status: Int, val cookies: List<String>)

/** Password sessions remain supported; paired devices use a revocable credential. */
object MobileSession {
    fun authenticate(credentials: ServerCredentials): MobileSessionResult {
        val paired = credentials.deviceToken != null
        val path = if (paired) "/api/mobile/session" else "/api/auth/login"
        val connection = connection(credentials.baseUrl, path)
        try {
            if (paired) {
                connection.setRequestProperty("Authorization", "Bearer ${credentials.deviceToken}")
            } else {
                write(connection, JSONObject().put("username", credentials.username).put("password", credentials.password))
            }
            val status = connection.responseCode
            val cookies = connection.headerFields.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
                .values.flatMap { it.orEmpty() }
            runCatching { (if (status >= 400) connection.errorStream else connection.inputStream)?.close() }
            return MobileSessionResult(status, cookies)
        } finally { connection.disconnect() }
    }

    fun redeem(link: ConnectionLink, label: String): ServerCredentials {
        val code = requireNotNull(link.pairingCode)
        val connection = connection(link.serverUrl, "/api/mobile/pairings/redeem")
        try {
            write(connection, JSONObject().put("code", code).put("label", label.take(128)))
            check(connection.responseCode == 200) { "Invalid or expired pairing code" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 65536) { "Invalid pairing response" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return pairedCredentials(link, JSONObject(bytes.toString(Charsets.UTF_8)))
        } finally { connection.disconnect() }
    }

    internal fun pairedCredentials(link: ConnectionLink, response: JSONObject): ServerCredentials {
        val username = response.getJSONObject("user").getString("username")
        val token = response.getString("device_token")
        require(username == link.username && username.isNotBlank())
        require(token.matches(Regex("[0-9a-f]{64}")))
        return ServerCredentials(link.serverUrl, username, deviceToken = token)
    }

    private fun connection(baseUrl: String, path: String): HttpURLConnection =
        (URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }

    private fun write(connection: HttpURLConnection, body: JSONObject) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    }
}
