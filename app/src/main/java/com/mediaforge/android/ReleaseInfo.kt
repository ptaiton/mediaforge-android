package com.mediaforge.android

import org.json.JSONObject

data class ReleaseInfo(
    val tag: String,
    val versionCode: Int,
    val url: String,
    val size: Long,
    val sha256: String,
    val notes: String,
) {
    companion object {
        const val REPOSITORY = "ptaiton/mediaforge-android"
        const val MAX_APK_SIZE = 64L * 1024 * 1024

        fun versionCode(tag: String): Int? = runCatching {
            val match = Regex("v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(tag)
                ?: return null
            val (major, minor, patch) = match.destructured
            require(minor.toLong() < 1000 && patch.toLong() < 1000)
            val code = Math.addExact(Math.multiplyExact(major.toLong(), 1_000_000), minor.toLong() * 1000 + patch.toLong())
            require(code in 1..Int.MAX_VALUE.toLong())
            code.toInt()
        }.getOrNull()

        fun parse(json: JSONObject): ReleaseInfo {
            require(!json.optBoolean("draft") && !json.optBoolean("prerelease"))
            val tag = json.getString("tag_name")
            val code = versionCode(tag) ?: error("Unsupported version")
            val assets = json.getJSONArray("assets")
            val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name") == "mediaforge-$tag.apk" }
            require(matches.size == 1)
            val asset = matches.single()
            val url = asset.getString("browser_download_url")
            require(url == "https://github.com/$REPOSITORY/releases/download/$tag/mediaforge-$tag.apk")
            val size = asset.getLong("size")
            require(size in 1..MAX_APK_SIZE)
            val digest = asset.getString("digest")
            require(Regex("sha256:[a-f0-9]{64}").matches(digest))
            return ReleaseInfo(tag, code, url, size, digest.removePrefix("sha256:"), json.optString("body").take(6000))
        }

        fun acceptsArchive(packageName: String, version: Long, signers: Set<String>, installedPackage: String, installedVersion: Long, installedSigners: Set<String>): Boolean =
            packageName == installedPackage && version > installedVersion && signers.isNotEmpty() && signers == installedSigners
    }
}
