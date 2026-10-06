package com.mediaforge.android

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
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
            text = "Configurer MediaForge"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(10), 0, dp(8))
        }
        content.addView(title, matchParentWrap())

        val description = TextView(this).apply {
            text = "Indiquez l’adresse de votre serveur et vos identifiants pour ouvrir automatiquement votre session."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        }
        content.addView(description, matchParentWrap())

        urlInput = createInput(
            hint = "URL du serveur",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            value = existing?.baseUrl ?: "http://10.0.2.2:8080/",
        )
        content.addView(urlInput, inputLayoutParams())

        usernameInput = createInput(
            hint = "Nom d’utilisateur",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
            value = existing?.username.orEmpty(),
        )
        content.addView(usernameInput, inputLayoutParams())

        passwordInput = createInput(
            hint = "Mot de passe",
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
            text = "Enregistrer et ouvrir MediaForge"
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@ConfigActivity, R.color.mediaforge_primary),
            )
            setOnClickListener { saveAndOpen() }
        }
        content.addView(saveButton, inputLayoutParams(top = 8))

        val securityNote = TextView(this).apply {
            text = "Le mot de passe est chiffré avec le stockage sécurisé Android."
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
        content.addView(securityNote, matchParentWrap())

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    private fun saveAndOpen() {
        val rawUrl = urlInput.text.toString().trim()
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val parsedUrl = Uri.parse(rawUrl)

        val error = when {
            parsedUrl.scheme !in setOf("http", "https") || parsedUrl.host.isNullOrBlank() ->
                "L’URL doit commencer par http:// ou https:// et contenir un serveur."
            username.isBlank() -> "Le nom d’utilisateur est obligatoire."
            password.isBlank() -> "Le mot de passe est obligatoire."
            else -> null
        }

        if (error != null) {
            errorText.text = error
            errorText.visibility = TextView.VISIBLE
            return
        }

        val normalizedUrl = if (rawUrl.endsWith('/')) rawUrl else "$rawUrl/"
        credentialStore.save(ServerCredentials(normalizedUrl, username, password))
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
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
