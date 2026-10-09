package com.mediaforge.android

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AppUpdates(private val activity: ComponentActivity) {
    private val executor = Executors.newSingleThreadExecutor()
    private val preferences = activity.getSharedPreferences("mediaforge_updates", 0)
    private var busy = false
    private var dialog: AlertDialog? = null
    private var cancelled = AtomicBoolean(false)
    private val directory get() = File(activity.cacheDir, "updates")
    private val apk get() = File(directory, "mediaforge-update.apk")
    private val permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (activity.packageManager.canRequestPackageInstalls()) install(apk)
        else toast(R.string.update_install_permission_required)
    }

    fun check(manual: Boolean = false) {
        if (busy) return
        val now = System.currentTimeMillis()
        if (!manual && now - preferences.getLong("last_check", 0) < 6 * 60 * 60 * 1000) return
        preferences.edit().putLong("last_check", now).apply()
        busy = true
        if (manual) toast(R.string.update_checking)
        executor.execute {
            val result = runCatching {
                val connection = connect("https://api.github.com/repos/${ReleaseInfo.REPOSITORY}/releases/latest")
                try {
                    require(connection.responseCode == 200)
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            require(output.size() + count <= 256 * 1024)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    ReleaseInfo.parse(JSONObject(bytes.toString(Charsets.UTF_8)))
                } finally { connection.disconnect() }
            }
            ui {
                busy = false
                val release = result.getOrNull()
                if (release == null) {
                    if (manual) toast(R.string.update_check_failed)
                } else if (release.versionCode <= BuildConfig.VERSION_CODE) {
                    if (manual) toast(R.string.update_current)
                } else if (manual || preferences.getString("dismissed_tag", null) != release.tag ||
                    now - preferences.getLong("dismissed_at", 0) >= 24 * 60 * 60 * 1000) {
                    offer(release)
                }
            }
        }
    }

    private fun offer(release: ReleaseInfo) {
        dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available, release.tag))
            .setMessage(activity.getString(R.string.update_description, BuildConfig.VERSION_NAME, release.tag) +
                if (release.notes.isNotBlank()) "\n\n${release.notes}" else "")
            .setPositiveButton(R.string.update_download) { _, _ -> download(release) }
            .setNegativeButton(R.string.update_later) { _, _ -> dismiss(release) }
            .setOnCancelListener { dismiss(release) }
            .show()
    }

    private fun dismiss(release: ReleaseInfo) {
        preferences.edit().putString("dismissed_tag", release.tag).putLong("dismissed_at", System.currentTimeMillis()).apply()
    }

    private fun download(release: ReleaseInfo) {
        if (busy) return
        busy = true
        cancelled = AtomicBoolean(false)
        val token = cancelled
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(activity).apply { text = activity.getString(R.string.update_downloading) }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(label); addView(progress)
        }
        dialog = AlertDialog.Builder(activity).setTitle(R.string.update_downloading).setView(content)
            .setNegativeButton(android.R.string.cancel) { _, _ -> token.set(true) }
            .setOnCancelListener { token.set(true) }.show()
        executor.execute {
            val result = runCatching {
                directory.mkdirs()
                val partial = File(directory, "download.part")
                try {
                    val connection = connect(release.url)
                    try {
                        require(connection.responseCode == 200)
                        val digest = MessageDigest.getInstance("SHA-256")
                        var total = 0L
                        var lastPercent = -1
                        connection.inputStream.use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    check(!token.get() && !Thread.currentThread().isInterrupted)
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    total += count
                                    require(total <= release.size)
                                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                                    val percent = (total * 100 / release.size).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        ui { progress.progress = percent }
                                    }
                                }
                            }
                        }
                        require(total == release.size && hex(digest.digest()) == release.sha256)
                        validate(partial, release.versionCode.toLong())
                        check(!token.get())
                        apk.delete()
                        check(partial.renameTo(apk))
                    } finally { connection.disconnect() }
                } finally { partial.delete() }
            }
            ui {
                busy = false; dialog?.dismiss(); dialog = null
                if (!token.get()) {
                    if (result.isSuccess) requestInstallation() else toast(R.string.update_download_failed)
                }
            }
        }
    }

    private fun requestInstallation() {
        if (activity.packageManager.canRequestPackageInstalls()) install(apk)
        else {
            dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.update_install_permission_title)
                .setMessage(R.string.update_install_permission_description)
                .setPositiveButton(R.string.update_allow_installation) { _, _ ->
                    runCatching {
                        permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    }.onFailure { toast(R.string.update_install_failed) }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun install(file: File) {
        runCatching {
            validate(file)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { toast(R.string.update_install_failed) }
    }

    @Suppress("DEPRECATION")
    private fun validate(file: File, expectedVersion: Long? = null) {
        require(file.isFile && file.length() in 1..ReleaseInfo.MAX_APK_SIZE)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = activity.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK")
        val installed = activity.packageManager.getPackageInfo(activity.packageName, flags)
        fun signers(info: PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                .orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
        fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        require(ReleaseInfo.acceptsArchive(archive.packageName, version(archive), signers(archive), installed.packageName, version(installed), signers(installed)))
        require(expectedVersion == null || version(archive) == expectedVersion)
    }

    private fun connect(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000
        setRequestProperty("User-Agent", "MediaForgeAndroid/${BuildConfig.VERSION_NAME}")
        if (url.startsWith("https://api.github.com/")) {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun ui(action: () -> Unit) = activity.runOnUiThread {
        if (!activity.isFinishing && !activity.isDestroyed) action()
    }
    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    fun close() {
        cancelled.set(true)
        dialog?.dismiss(); dialog = null
        executor.shutdownNow()
    }
}
