package com.mediaforge.android

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var credentialStore: CredentialStore
    private var credentials: ServerCredentials? = null
    private var loginAttemptedFor: String? = null

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

        window.statusBarColor = Color.rgb(17, 17, 27)
        window.navigationBarColor = Color.rgb(17, 17, 27)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            setBackgroundColor(Color.rgb(17, 17, 27))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.setSupportZoom(false)
            webViewClient = MediaForgeWebViewClient()
            webChromeClient = WebChromeClient()
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        setContentView(createContent())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        if (savedInstanceState == null) {
            loadConfiguredServer()
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun createContent(): LinearLayout {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
            setBackgroundColor(Color.rgb(17, 17, 27))
        }

        val title = TextView(this).apply {
            text = "MediaForge"
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        toolbar.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
        )

        val configButton = Button(this).apply {
            text = "Configuration"
            isAllCaps = false
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.mediaforge_primary))
            setOnClickListener { openConfiguration() }
        }
        toolbar.addView(
            configButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)),
        )

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 17, 27))
            addView(toolbar, LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            addView(webView)
        }
    }

    private fun loadConfiguredServer() {
        val currentCredentials = credentials ?: return
        loginAttemptedFor = null
        webView.loadUrl(currentCredentials.baseUrl)
    }

    private fun openConfiguration() {
        startActivity(Intent(this, ConfigActivity::class.java))
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        credentials = credentialStore.read()
        loadConfiguredServer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
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
            val currentCredentials = credentials ?: return
            if (loginAttemptedFor == currentCredentials.baseUrl) return

            loginAttemptedFor = currentCredentials.baseUrl
            view.evaluateJavascript(buildAutoLoginScript(currentCredentials), null)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = handleUrl(view, request.url)

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleUrl(view, Uri.parse(url))

        private fun handleUrl(view: WebView, uri: Uri): Boolean {
            return if (uri.scheme == "http" || uri.scheme == "https") {
                false
            } else {
                runCatching {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                true
            }
        }
    }

    private fun buildAutoLoginScript(credentials: ServerCredentials): String {
        val username = JSONObject.quote(credentials.username)
        val password = JSONObject.quote(credentials.password)

        return """
            (function() {
              const username = $username;
              const password = $password;
              const inputs = Array.from(document.querySelectorAll('input'));
              const userInput = inputs.find((input) => input.type !== 'password' && input.type !== 'hidden');
              const passwordInput = inputs.find((input) => input.type === 'password');
              if (!userInput || !passwordInput) return;

              const setValue = (input, value) => {
                const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
                if (setter) setter.call(input, value); else input.value = value;
                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
              };

              setValue(userInput, username);
              setValue(passwordInput, password);
              const form = passwordInput.closest('form') || userInput.closest('form');
              const submit = form?.querySelector('button[type="submit"], button:not([type]), input[type="submit"]');
              if (submit) submit.click(); else if (form?.requestSubmit) form.requestSubmit();
            })();
        """.trimIndent()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
