package com.mediaforge.android

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MobileFeaturesTest {
    @Test fun connectionSupportsEncodedAccountsAndServerPaths() {
        val link = MobileLinks.connection("mediaforge://connect?server=https%3A%2F%2Fmedia.example%2Fapp&username=Alice+%26+Bob")!!
        assertEquals("https://media.example/app/", link.serverUrl)
        assertEquals("Alice & Bob", link.username)
        assertEquals("", MobileLinks.connection("mediaforge://connect?server=http%3A%2F%2F192.168.1.10%3A8080")!!.username)
    }

    @Test fun untrustedQrCodesCannotSupplyPasswordsOrOverrideParameters() {
        for (value in listOf(
            "https://media.example/", "mediaforge://other?server=https%3A%2F%2Fmedia.example",
            "mediaforge://connect?server=javascript%3Aalert(1)",
            "mediaforge://connect?server=https%3A%2F%2Fuser%3Apassword%40media.example",
            "mediaforge://connect?server=https%3A%2F%2Fmedia.example%3Ftoken%3Dsecret",
            "mediaforge://connect?server=https%3A%2F%2Fmedia.example%23fragment",
            "mediaforge://connect?server=https%3A%2F%2Fmedia.example&server=https%3A%2F%2Fevil.example",
            "mediaforge://connect?server=https%3A%2F%2Fmedia.example&password=secret",
            "mediaforge://connect?server=%ZZ",
            "mediaforge://connect?server=https%3A%2F%2Fmedia.example&username=%0Aadmin",
        )) assertNull(value, MobileLinks.connection(value))
    }

    @Test fun serverAddressesRejectCredentialsAndInvalidPorts() {
        assertEquals("https://media.example/", MobileLinks.normalizeServer(" https://media.example "))
        assertEquals("http://[::1]:8080/", MobileLinks.normalizeServer("http://[::1]:8080"))
        for (url in listOf("file:///media", "https://user:pass@media.example", "https://media.example:99999", "https://media.example?", "https://media.example#", "https://media.example\n/path")) {
            assertNull(url, MobileLinks.normalizeServer(url))
        }
    }

    @Test fun notificationsAcceptOnlyPositiveLocalMediaIds() {
        assertEquals(23, MobileLinks.mediaId("23"))
        for (id in listOf(null, "", "0", "-1", "2147483648", "/library/3", "https://evil.example")) assertNull(MobileLinks.mediaId(id))
        val account = ServerCredentials("https://media.example/", "alice", "password")
        assertNotEquals(MobileLinks.notificationTag(account), MobileLinks.notificationTag(account.copy(username = "bob")))
        assertNotEquals(MobileLinks.notificationTag(account), MobileLinks.notificationTag(account.copy(baseUrl = "https://other.example/")))
        assertEquals(MobileLinks.notificationTag(account), MobileLinks.notificationTag(account.copy(password = "new-password")))
        assertNotEquals(MobileLinks.identity(account), MobileLinks.identity(account.copy(username = "bob")))
        assertNotEquals(MobileLinks.identity(account), MobileLinks.identity(account.copy(baseUrl = "https://other.example/")))
    }

    private fun release(): JSONObject = JSONObject("""{
        "tag_name":"v0.1.9", "draft":false, "prerelease":false, "body":"Release notes",
        "assets":[{"name":"mediaforge-v0.1.9.apk","size":4000000,
        "browser_download_url":"https://github.com/ptaiton/mediaforge-android/releases/download/v0.1.9/mediaforge-v0.1.9.apk",
        "digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]
    }""")

    @Test fun releaseMetadataUsesTheSameVersionSchemeAsTheSignedBuild() {
        val parsed = ReleaseInfo.parse(release())
        assertEquals(1009, parsed.versionCode)
        assertEquals(2_003_004, ReleaseInfo.versionCode("v2.3.4"))
        assertEquals(64, parsed.sha256.length)
        for (tag in listOf("v0.1.9-beta", "latest", "v00.1.9", "v0.1000.0", "v0.1.1000", "v999999999999999999999999.1.0")) assertNull(ReleaseInfo.versionCode(tag))
    }

    @Test fun unsafeReleaseSourcesAndMissingChecksumsAreRejected() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("draft", true) }, { it.put("prerelease", true) },
            { it.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.example/update.apk") },
            { it.getJSONArray("assets").getJSONObject(0).put("size", ReleaseInfo.MAX_APK_SIZE + 1) },
            { it.getJSONArray("assets").getJSONObject(0).put("size", 0) },
            { it.getJSONArray("assets").getJSONObject(0).remove("digest") },
            { it.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:invalid") },
            { it.getJSONArray("assets").put(it.getJSONArray("assets").getJSONObject(0)) },
        )
        for (mutation in mutations) {
            val json = release(); mutation(json)
            assertTrue(runCatching { ReleaseInfo.parse(json) }.isFailure)
        }
    }

    @Test fun installingUpdatesRequiresTheSameAppAStrictlyNewerVersionAndTheSameSigner() {
        fun accepts(name: String = "com.mediaforge.android", version: Long = 1009, signers: Set<String> = setOf("trusted")) =
            ReleaseInfo.acceptsArchive(name, version, signers, "com.mediaforge.android", 1008, setOf("trusted"))
        assertTrue(accepts())
        assertFalse(accepts(name = "com.other.app"))
        assertFalse(accepts(version = 1008))
        assertFalse(accepts(version = 1007))
        assertFalse(accepts(signers = emptySet()))
        assertFalse(accepts(signers = setOf("untrusted")))
        assertFalse(accepts(signers = setOf("trusted", "extra")))
    }
}
