package com.mediaforge.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import android.webkit.CookieManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class ConfigActivity : ComponentActivity() {
    private val pairingExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var pairingInProgress = false
    private lateinit var updates: AppUpdates
    private var scannerDialog: ConnectionScannerDialog? = null
    private var foreground = false
    private var pairingGeneration = 0
    private data class PendingPairing(val generation: Int, val source: ConnectionScannerDialog, val result: Result<ServerCredentials>)
    private var pendingPairing: PendingPairing? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { if (foreground) scannerDialog?.resume() }
        else scannerDialog?.permissionDenied()
    }
    private lateinit var credentialStore: CredentialStore
    private lateinit var urlInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialStore = CredentialStore(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val content = createContent()
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(dp(28), dp(44) + safeInsets.top, dp(28), dp(28) + safeInsets.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
        setContentView(content)
        updates = AppUpdates(this)
        if (intent.getBooleanExtra("pairing_required", false)) {
            errorText.text = getString(R.string.pairing_revoked)
            errorText.visibility = TextView.VISIBLE
        }
    }

    private fun createContent(): ScrollView {
        val existing = credentialStore.read()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(44), dp(28), dp(28))
            setBackgroundColor(ContextCompat.getColor(this@ConfigActivity, R.color.mediaforge_background))
        }

        val logo = TextView(this).apply {
            text = "◆"
            textSize = 34f
            setTextColor(ContextCompat.getColor(this@ConfigActivity, R.color.mediaforge_primary))
            gravity = android.view.Gravity.CENTER
        }
        content.addView(logo, matchParentWrap())

        val title = TextView(this).apply {
            text = getString(R.string.configure_mediaforge)
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(10), 0, dp(8))
        }
        content.addView(title, matchParentWrap())

        val description = TextView(this).apply {
            text = getString(R.string.configure_description)
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        }
        content.addView(description, matchParentWrap())

        val scanButton = Button(this).apply {
            text = getString(R.string.qr_scan)
            isAllCaps = false
            setOnClickListener {
                if (pairingInProgress) return@setOnClickListener
                showScanner()
            }
        }
        content.addView(scanButton, inputLayoutParams())

        urlInput = createInput(
            hint = getString(R.string.server_url),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            value = existing?.baseUrl ?: "http://10.0.2.2:8080/",
        )
        content.addView(urlInput, inputLayoutParams())

        usernameInput = createInput(
            hint = getString(R.string.username),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
            value = existing?.username.orEmpty(),
        )
        content.addView(usernameInput, inputLayoutParams())

        passwordInput = createInput(
            hint = getString(R.string.password),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            value = existing?.password.orEmpty(),
        )
        content.addView(passwordInput, inputLayoutParams())

        errorText = TextView(this).apply {
            setTextColor(Color.rgb(255, 125, 125))
            textSize = 13f
            visibility = TextView.GONE
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        content.addView(errorText, matchParentWrap())

        val saveButton = Button(this).apply {
            text = getString(R.string.save_and_open)
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@ConfigActivity, R.color.mediaforge_primary),
            )
            setOnClickListener { saveAndOpen() }
        }
        content.addView(saveButton, inputLayoutParams(top = 8))

        val securityNote = TextView(this).apply {
            text = getString(if (existing?.deviceToken != null) R.string.paired_security_note else R.string.password_security_note)
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
        content.addView(securityNote, matchParentWrap())

        val updateButton = Button(this).apply {
            text = getString(R.string.update_check_button)
            isAllCaps = false
            setOnClickListener { updates.check(manual = true) }
        }
        content.addView(updateButton, inputLayoutParams(top = 24))
        content.addView(TextView(this).apply {
            text = getString(R.string.app_version, BuildConfig.VERSION_NAME)
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = android.view.Gravity.CENTER
        }, matchParentWrap())

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    private fun showScanner() {
        if (scannerDialog?.isShowing == true || pairingInProgress) return
        val source = ConnectionScannerDialog(
            this,
            requestCamera = {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    if (foreground) scannerDialog?.resume()
                } else cameraPermission.launch(Manifest.permission.CAMERA)
            },
            connect = { link ->
                val active = scannerDialog
                if (link.pairingCode != null && active != null) pairAndOpen(link, active)
                else {
                    urlInput.setText(link.serverUrl)
                    usernameInput.setText(link.username)
                    passwordInput.setText("")
                    errorText.visibility = TextView.GONE
                    active?.dismiss()
                    passwordInput.requestFocus()
                }
            },
            dismissed = {
                scannerDialog = null
                pairingGeneration++
                pendingPairing = null
                pairingInProgress = false
            },
        )
        scannerDialog = source
        source.show()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        scannerDialog?.resume()
        applyPendingPairing()
    }

    override fun onPause() {
        foreground = false
        scannerDialog?.pause()
        super.onPause()
    }

    private fun pairAndOpen(link: ConnectionLink, source: ConnectionScannerDialog) {
        if (pairingInProgress) return
        pairingInProgress = true
        val generation = ++pairingGeneration
        source.connecting()
        pairingExecutor.execute {
            val result = runCatching { MobileSession.redeem(link, "Android · ${android.os.Build.MODEL}") }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != pairingGeneration || !source.isShowing) return@runOnUiThread
                pendingPairing = PendingPairing(generation, source, result)
                applyPendingPairing()
            }
        }
    }

    private fun applyPendingPairing() {
        if (!foreground) return
        val pending = pendingPairing ?: return
        pendingPairing = null
        if (pending.generation != pairingGeneration || !pending.source.isShowing) return
        pairingInProgress = false
        pending.result.onSuccess { credentials ->
            runCatching { storeAndOpen(credentials) }
                .onSuccess { pending.source.dismiss() }
                .onFailure { pending.source.pairingFailed() }
        }.onFailure { pending.source.pairingFailed() }
    }

    private fun saveAndOpen() {
        if (pairingInProgress) return
        val rawUrl = urlInput.text.toString().trim()
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val normalizedUrl = MobileLinks.normalizeServer(rawUrl)

        val existing = credentialStore.read()
        val retainedToken = existing?.deviceToken?.takeIf { existing.baseUrl == normalizedUrl && existing.username == username && password.isBlank() }
        val error = when {
            normalizedUrl == null ->
                getString(R.string.invalid_server_url)
            username.isBlank() -> getString(R.string.username_required)
            password.isBlank() && retainedToken == null -> getString(R.string.password_required)
            else -> null
        }

        if (error != null) {
            errorText.text = error
            errorText.visibility = TextView.VISIBLE
            return
        }

        storeAndOpen(ServerCredentials(requireNotNull(normalizedUrl), username, password, retainedToken))
    }

    private fun storeAndOpen(credentials: ServerCredentials) {
        val previous = credentialStore.read()
        val accountChanged = previous != null && (previous.baseUrl != credentials.baseUrl || previous.username != credentials.username)
        if (accountChanged) PushConfiguration.clear(applicationContext)
        credentialStore.save(credentials)
        fun openServer() {
            if (isFinishing || isDestroyed) return
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
        }
        if (accountChanged) {
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                openServer()
            }
        } else openServer()
    }

    override fun onDestroy() {
        scannerDialog?.dismiss()
        pairingExecutor.shutdownNow()
        if (::updates.isInitialized) updates.close()
        super.onDestroy()
    }

    private fun createInput(hint: String, inputType: Int, value: String): EditText =
        EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            this.setText(value)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 16f
            setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            backgroundTintList = ColorStateList.valueOf(Color.rgb(100, 100, 120))
        }

    private fun inputLayoutParams(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(0, dp(top), 0, dp(14))
        }

    private fun matchParentWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
