package com.mediaforge.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class PairingTest {
    private val code = "a".repeat(64)
    private val token = "b".repeat(64)
    private val prefix = "mediaforge://connect?server=https%3A%2F%2Fmedia.example&username=alice"

    @Test fun pairingQrRequiresAnAccountAndOneValidCode() {
        val link = MobileLinks.connection("$prefix&code=$code")!!
        assertEquals(code, link.pairingCode)
        assertNull(MobileLinks.connection("$prefix&code=short"))
        assertNull(MobileLinks.connection("$prefix&code=${code.uppercase()}"))
        assertNull(MobileLinks.connection("$prefix&code=$code&code=$code"))
        assertNull(MobileLinks.connection("mediaforge://connect?server=https%3A%2F%2Fmedia.example&code=$code"))
        assertNull(MobileLinks.connection("$prefix&code="))
        assertNull(MobileLinks.connection(prefix)!!.pairingCode)
    }

    @Test fun pairingResponseMustMatchTheScannedAccountAndContainAValidDeviceToken() {
        val link = ConnectionLink("https://media.example/", "alice", code)
        fun response(user: String = "alice", credential: String = token) =
            JSONObject().put("user", JSONObject().put("username", user)).put("device_token", credential)
        val credentials = MobileSession.pairedCredentials(link, response())
        assertEquals("", credentials.password)
        assertEquals(token, credentials.deviceToken)
        assertEquals(MobileLinks.identity(credentials), MobileLinks.identity(credentials.copy(deviceToken = "c".repeat(64))))
        assertTrue(runCatching { MobileSession.pairedCredentials(link, response("bob")) }.isFailure)
        assertTrue(runCatching { MobileSession.pairedCredentials(link, response(credential = "invalid")) }.isFailure)
    }

    @Test fun mobileAuthenticationUsesTheDeviceTokenAndKeepsManualLoginCompatible() {
        val requests = CopyOnWriteArrayList<TestRequest>()
        val server = HttpFixture { request ->
            requests.add(request)
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\nSet-Cookie: session=test; HttpOnly; Path=/\r\n\r\n{}"
        }
        try {
            val credentials = ServerCredentials("http://127.0.0.1:${server.port}/", "alice", "password", token)
            val session = MobileSession.authenticate(credentials)
            assertEquals(200, session.status); assertFalse(session.cookies.isEmpty())
            assertEquals("/api/mobile/session", requests[0].path)
            assertEquals("Bearer $token", requests[0].headers["authorization"])
            assertEquals("", requests[0].body)
            MobileSession.authenticate(credentials.copy(deviceToken = null))
            assertEquals("/api/auth/login", requests[1].path); assertNull(requests[1].headers["authorization"])
            assertEquals("alice", JSONObject(requests[1].body).getString("username"))
            assertEquals("password", JSONObject(requests[1].body).getString("password"))
        } finally { server.close() }
    }

    @Test fun redeemExchangesOnlyTheTemporaryCodeAndPersistsNoPassword() {
        val requests = CopyOnWriteArrayList<TestRequest>()
        val body = JSONObject().put("user", JSONObject().put("username", "alice")).put("device_token", token).toString()
        val server = HttpFixture { request ->
            requests.add(request)
            "HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
        }
        try {
            val credentials = MobileSession.redeem(ConnectionLink("http://127.0.0.1:${server.port}/", "alice", code), "Test phone")
            assertEquals(token, credentials.deviceToken); assertEquals("", credentials.password)
            assertEquals("/api/mobile/pairings/redeem", requests.single().path)
            val payload = JSONObject(requests.single().body)
            assertEquals(code, payload.getString("code")); assertEquals("Test phone", payload.getString("label"))
            assertFalse(payload.has("password"))
        } finally { server.close() }
    }

    @Test fun credentialsAreNeverForwardedToARedirect() {
        val followed = AtomicBoolean(false)
        val server = HttpFixture { request ->
            if (request.path == "/api/mobile/session") {
                "HTTP/1.1 307 Temporary Redirect\r\nLocation: /unexpected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            } else {
                followed.set(true)
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            }
        }
        try {
            val result = MobileSession.authenticate(ServerCredentials("http://127.0.0.1:${server.port}/", "alice", deviceToken = token))
            assertEquals(307, result.status); assertFalse(followed.get())
        } finally { server.close() }
    }
}

private data class TestRequest(val path: String, val headers: Map<String, String>, val body: String)

private class HttpFixture(handler: (TestRequest) -> String) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    private val thread = Thread {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            client.use {
                it.soTimeout = 5000
                val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
                val path = reader.readLine().split(' ')[1]
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                val body = CharArray(headers["content-length"]?.toInt() ?: 0)
                var offset = 0
                while (offset < body.size) {
                    val count = reader.read(body, offset, body.size - offset)
                    check(count > 0)
                    offset += count
                }
                val response = handler(TestRequest(path, headers, String(body)))
                it.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
            }
        }
    }.apply { isDaemon = true; start() }
    override fun close() { socket.close(); thread.join(5000) }
}
