package com.mediaforge.android

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/** The camera, account confirmation and pairing feedback share the same connection dialog. */
class ConnectionScannerDialog(
    private val activity: ComponentActivity,
    private val requestCamera: () -> Unit,
    private val connect: (ConnectionLink) -> Unit,
    private val dismissed: () -> Unit,
) {
    private var link: ConnectionLink? = null
    private var scanning = true
    private var cameraRunning = false
    private val scanner = DecoratedBarcodeView(activity).apply {
        decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        barcodeView.isUseTextureView = true
        statusView.visibility = View.GONE
        background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(16).toFloat() }
        clipToOutline = true
        contentDescription = activity.getString(R.string.qr_camera_preview)
    }
    private val message = text(activity.getString(R.string.qr_prompt), 14f)
    private val account = text("", 16f).apply { visibility = View.GONE }
    private val status = text("", 13f).apply { visibility = View.GONE }
    private val dialog: AlertDialog
    val isShowing: Boolean get() = dialog.isShowing

    init {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(message, row())
            // A portrait camera viewport fits the connection dialog without changing Activity orientation.
            val availableHeight = activity.resources.displayMetrics.heightPixels
            val previewHeight = minOf(dp(320), availableHeight / 2)
            addView(scanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeight).apply { bottomMargin = dp(16) })
            addView(account, row())
            addView(status, row())
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.qr_scan)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(R.string.qr_use_server, null)
            .setNeutralButton(R.string.qr_scan_again, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            scanning = false
            pause()
            dismissed()
        }
        scanner.barcodeView.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() = Unit
            override fun previewStopped() = Unit
            override fun cameraClosed() = Unit
            override fun cameraError(error: Exception) {
                if (isShowing && scanning) {
                    cameraRunning = false
                    scanner.visibility = View.GONE
                    showError(R.string.qr_camera_unavailable)
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.VISIBLE
                }
            }
        })
        scanner.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                if (!isShowing || !scanning || !cameraRunning) return
                val found = MobileLinks.connection(result.text)
                if (found == null) {
                    showError(R.string.qr_invalid)
                    return
                }
                link = found
                scanning = false
                pause()
                scanner.visibility = View.GONE
                message.setText(R.string.qr_confirm_server)
                account.text = found.serverUrl + if (found.username.isNotEmpty()) "\n${found.username}" else ""
                account.visibility = View.VISIBLE
                status.visibility = View.GONE
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply { visibility = View.VISIBLE; isEnabled = true }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.VISIBLE
            }
        })
    }

    fun show() {
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(ContextCompat.getColor(activity, R.color.mediaforge_background))
                cornerRadius = dp(20).toFloat()
            })
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            visibility = View.GONE
            isAllCaps = false
            setOnClickListener { link?.let(connect) }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
            visibility = View.GONE
            isAllCaps = false
            setOnClickListener { scanAgain() }
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isAllCaps = false
        requestCamera()
    }

    fun resume() {
        if (!isShowing || !scanning || cameraRunning) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionDenied()
            return
        }
        status.visibility = View.GONE
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.GONE
        scanner.visibility = View.VISIBLE
        cameraRunning = true
        runCatching { scanner.resume() }.onFailure {
            cameraRunning = false
            scanner.visibility = View.GONE
            showError(R.string.qr_camera_unavailable)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.VISIBLE
        }
    }

    fun pause() {
        cameraRunning = false
        scanner.pause()
    }

    fun dismiss() = dialog.dismiss()

    fun permissionDenied() {
        if (!isShowing) return
        pause()
        scanner.visibility = View.GONE
        showError(R.string.qr_camera_permission)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.VISIBLE
    }

    fun connecting() {
        scanning = false
        pause()
        status.setText(R.string.qr_connecting)
        status.setTextColor(Color.LTGRAY)
        status.visibility = View.VISIBLE
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
    }

    fun pairingFailed() {
        if (!isShowing) return
        showError(R.string.qr_pairing_failed)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
    }

    private fun scanAgain() {
        link = null
        scanning = true
        account.visibility = View.GONE
        status.visibility = View.GONE
        scanner.visibility = View.VISIBLE
        message.setText(R.string.qr_prompt)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility = View.GONE
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.GONE
        requestCamera()
    }

    private fun showError(messageId: Int) {
        status.setText(messageId)
        status.setTextColor(Color.rgb(255, 125, 125))
        status.visibility = View.VISIBLE
    }

    private fun text(value: String, size: Float) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(Color.LTGRAY)
    }
    private fun row() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
