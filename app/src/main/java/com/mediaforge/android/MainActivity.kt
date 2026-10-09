package com.mediaforge.android

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var credentialStore: CredentialStore
    private val loginExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var credentials: ServerCredentials? = null
    private var pendingMediaId: Int? = null
    private var loginGeneration = 0
    private var authenticationPending = false
    private lateinit var updates: AppUpdates

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

        val canRestore = savedInstanceState != null && savedInstanceState.getString("saved_server_identity") == MobileLinks.identity(requireNotNull(credentials))
        pendingMediaId = if (savedInstanceState == null) notificationTarget(intent)
            else if (canRestore) savedInstanceState.getInt("pending_media_id", 0).takeIf { it > 0 } else null

        configureSystemBars()
        configureNotifications()

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

        swipeRefreshLayout = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.WHITE)
            setProgressBackgroundColorSchemeColor(Color.rgb(35, 35, 49))
            setOnRefreshListener { webView.reload() }
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        webView.setOnScrollChangeListener { view, _, _, _, _ ->
            swipeRefreshLayout.isEnabled = !view.canScrollVertically(-1)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(17, 17, 27))
            addView(swipeRefreshLayout)
        }
        applySafeArea(root)
        setContentView(root)
        updates = AppUpdates(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (!canRestore || pendingMediaId != null || savedInstanceState?.getBoolean("authentication_pending") == true || webView.restoreState(savedInstanceState!!) == null) {
            authenticateAndLoadServer()
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
        val generation = ++loginGeneration
        authenticationPending = true
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
                val result = MobileSession.authenticate(currentCredentials)
                responseCode = result.status
                cookies = result.cookies
            } catch (exception: Exception) {
                failure = exception.message ?: getString(R.string.server_unreachable)
            }

            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loginGeneration) return@runOnUiThread

                authenticationPending = false
                val target = pendingMediaId?.let { "${currentCredentials.baseUrl.trimEnd('/')}/library/$it" }
                    ?: currentCredentials.baseUrl
                pendingMediaId = null
                val cookieManager = CookieManager.getInstance()
                if (responseCode in 200..299 && cookies.isNotEmpty()) {
                    cookies.forEach { cookieManager.setCookie(currentCredentials.baseUrl, it) }
                    cookieManager.flush()
                    PushConfiguration.refresh(applicationContext, currentCredentials, cookies.joinToString("; ") { it.substringBefore(';') })
                    webView.loadUrl(target)
                } else {
                    if (responseCode == 401 && currentCredentials.deviceToken != null) {
                        CredentialStore(this).clear()
                        PushConfiguration.clear(applicationContext)
                        cookieManager.removeAllCookies { cookieManager.flush() }
                        startActivity(Intent(this, ConfigActivity::class.java).putExtra("pairing_required", true))
                        finish()
                        return@runOnUiThread
                    }
                    webView.loadUrl(target)
                    val message = when {
                        failure != null -> getString(R.string.connection_failed, failure)
                        responseCode == 401 -> getString(R.string.invalid_credentials)
                        else -> getString(R.string.automatic_login_failed, responseCode)
                    }
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun configureNotifications() {
        val channelId = "mediaforge-events"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                channelId,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
        }
    }

    private fun openConfiguration() {
        startActivity(Intent(this, ConfigActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        credentials = credentialStore.read()
        if (credentials == null) {
            openConfiguration()
            finish()
            return
        }
        pendingMediaId = notificationTarget(intent)
        authenticateAndLoadServer()
    }

    private fun notificationTarget(intent: Intent): Int? {
        val current = credentials ?: return null
        if (intent.getStringExtra(MobileLinks.SERVER_IDENTITY) != MobileLinks.identity(current)) return null
        return intent.getIntExtra(MobileLinks.MEDIA_ID, 0).takeIf { it > 0 }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("pending_media_id", pendingMediaId ?: 0)
        outState.putString("saved_server_identity", credentials?.let { MobileLinks.identity(it) })
        outState.putBoolean("authentication_pending", authenticationPending)
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::updates.isInitialized) updates.check()
    }

    override fun onDestroy() {
        if (::updates.isInitialized) updates.close()
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
        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (::swipeRefreshLayout.isInitialized) {
                swipeRefreshLayout.isRefreshing = false
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame && ::swipeRefreshLayout.isInitialized) {
                swipeRefreshLayout.isRefreshing = false
            }
        }

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

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 4001
    }
}
