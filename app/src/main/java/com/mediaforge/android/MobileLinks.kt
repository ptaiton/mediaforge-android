package com.mediaforge.android

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

data class ConnectionLink(val serverUrl: String, val username: String, val pairingCode: String? = null)

object MobileLinks {
    const val MEDIA_ID = "media_id"
    const val SERVER_IDENTITY = "server_identity"

    fun mediaId(value: String?): Int? = value?.toIntOrNull()?.takeIf { it > 0 }

    fun identity(credentials: ServerCredentials): String = "${credentials.baseUrl}\n${credentials.username}"

    fun notificationTag(credentials: ServerCredentials?): String = credentials?.let {
        MessageDigest.getInstance("SHA-256").digest(identity(it).toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    } ?: "unconfigured"

    fun normalizeServer(value: String): String? = runCatching {
        require(value.length in 1..2048 && value.none { it.isISOControl() })
        val uri = URI(value.trim())
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.port in -1..65535)
        uri.toASCIIString().let { if (it.endsWith('/')) it else "$it/" }
    }.getOrNull()

    fun connection(value: String): ConnectionLink? = runCatching {
        require(value.length <= 4096)
        val uri = URI(value)
        require(uri.scheme == "mediaforge" && uri.host == "connect")
        require(uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null)
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        val params = uri.rawQuery.orEmpty().split('&').map { part ->
            val pieces = part.split('=', limit = 2)
            require(pieces.size == 2)
            URLDecoder.decode(pieces[0], "UTF-8") to URLDecoder.decode(pieces[1], "UTF-8")
        }
        require(params.map { it.first }.distinct().size == params.size)
        require(params.all { it.first in setOf("server", "username", "code") })
        val fields = params.toMap()
        val server = normalizeServer(fields["server"].orEmpty()) ?: error("Invalid server")
        val username = fields["username"].orEmpty()
        require(username.length <= 128 && username.none { it.isISOControl() })
        val code = fields["code"]
        require(code == null || (username.isNotBlank() && code.matches(Regex("[0-9a-f]{64}"))))
        ConnectionLink(server, username, code)
    }.getOrNull()
}
