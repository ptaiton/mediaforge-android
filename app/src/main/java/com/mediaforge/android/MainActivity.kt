package com.mediaforge.android

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var credentialStore: CredentialStore
    private val loginExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var credentials: ServerCredentials? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialStore = CredentialStore(this)
        credentials = credentialStore.read()

        if (credentials == null) {
            openConfiguration()
            finish()
            return
        }

        configureSystemBars()

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(17, 17, 27))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.setSupportZoom(false)
            settings.userAgentString = "${settings.userAgentString} MediaForgeAndroid/${BuildConfig.VERSION_NAME}"
            webViewClient = MediaForgeWebViewClient()
            webChromeClient = WebChromeClient()
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(17, 17, 27))
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        applySafeArea(root)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null) {
            authenticateAndLoadServer()
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun applySafeArea(root: FrameLayout) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(top = safeInsets.top, bottom = safeInsets.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun authenticateAndLoadServer() {
        val currentCredentials = credentials ?: return
        webView.loadData(
            "<html><body style=\"background:#11111b\"></body></html>",
            "text/html",
            "UTF-8",
        )

        loginExecutor.execute {
            var responseCode = -1
            var cookies = emptyList<String>()
            var failure: String? = null

            try {
                val connection = URL(loginUrl(currentCredentials.baseUrl)).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")

                val body = JSONObject()
                    .put("username", currentCredentials.username)
                    .put("password", currentCredentials.password)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                connection.outputStream.use { it.write(body) }
                responseCode = connection.responseCode
                cookies = connection.headerFields
                    .filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
                    .values
                    .flatMap { it.orEmpty() }
                runCatching {
                    (if (responseCode >= 400) connection.errorStream else connection.inputStream)?.close()
                }
                connection.disconnect()
            } catch (exception: Exception) {
                failure = exception.message ?: "Impossible de joindre le serveur."
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                val cookieManager = CookieManager.getInstance()
                if (responseCode in 200..299 && cookies.isNotEmpty()) {
                    cookies.forEach { cookieManager.setCookie(currentCredentials.baseUrl, it) }
                    cookieManager.flush()
                    webView.loadUrl(currentCredentials.baseUrl)
                } else {
                    webView.loadUrl(currentCredentials.baseUrl)
                    val message = when {
                        failure != null -> "Connexion au serveur impossible : $failure"
                        responseCode == 401 -> "Identifiants invalides."
                        else -> "Connexion automatique impossible (HTTP $responseCode)."
                    }
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loginUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/api/auth/login"

    private fun openConfiguration() {
        startActivity(Intent(this, ConfigActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        credentials = credentialStore.read()
        authenticateAndLoadServer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loginExecutor.shutdownNow()
        if (::webView.isInitialized) {
            webView.apply {
                stopLoading()
                webChromeClient = null
                webViewClient = WebViewClient()
                destroy()
            }
        }
        super.onDestroy()
    }

    private inner class MediaForgeWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = handleUrl(view, request.url)

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleUrl(view, Uri.parse(url))

        private fun handleUrl(view: WebView, uri: Uri): Boolean {
            if (uri.scheme == "mediaforge" && uri.host == "settings") {
                openConfiguration()
                return true
            }

            return if (uri.scheme == "http" || uri.scheme == "https") {
                false
            } else {
                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                true
            }
        }
    }
}
