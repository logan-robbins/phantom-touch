package ai.qmachina.phantomtouch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.qmachina.phantomtouch.service.CaptureService
import ai.qmachina.phantomtouch.service.GestureService
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Onboarding activity. Walks through every permission PhantomTouch needs,
 * checks each one in real time, and won't start the server until all
 * prerequisites are green.
 *
 * Permission checklist:
 *   1. AccessibilityService   — gesture dispatch to any foreground app
 *   2. Battery optimization   — prevent Android from killing the service
 *   3. MediaProjection        — screenshot capture
 *   4. Start server           — HTTP command server on :8080
 *
 * After setup, the user can leave the app (Home / Back). The foreground
 * service keeps the HTTP server and screenshot capability alive.
 */
class MainActivity : Activity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val REQUEST_BATTERY_EXEMPTION = 1002
    }

    // ── UI refs ───────────────────────────────────────────────────
    private lateinit var step1Status: TextView
    private lateinit var step1Btn: Button
    private lateinit var step2Status: TextView
    private lateinit var step2Btn: Button
    private lateinit var step3Status: TextView
    private lateinit var step3Btn: Button
    private lateinit var step4Status: TextView
    private lateinit var step4Btn: Button
    private lateinit var relayUrlInput: android.widget.EditText
    private lateinit var relayTokenInput: android.widget.EditText
    private lateinit var serverInfoText: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0D0D0D"))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 72, 56, 56)
        }

        // Header
        layout.addView(text("PhantomTouch", size = 28f, bold = true, color = "#FFFFFF"))
        layout.addView(text("LLM-driven device automation", size = 13f, color = "#888888", bottomPad = 40))

        // ── Step 1: Accessibility ─────────────────────────────────
        layout.addView(text("STEP 1", size = 11f, color = "#666666", bold = true, topPad = 16))
        layout.addView(text("Enable Accessibility Service", size = 16f, color = "#FFFFFF", bottomPad = 4))
        layout.addView(text(
            "Grants gesture dispatch (tap, swipe, type) and UI tree " +
            "access to any foreground app — LinkedIn, Chrome, Facebook, etc. " +
            "This is the core permission that makes everything work.",
            size = 12f, color = "#999999", bottomPad = 12
        ))
        step1Status = statusLabel()
        layout.addView(step1Status)
        step1Btn = actionButton("Open Accessibility Settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        layout.addView(step1Btn)
        layout.addView(divider())

        // ── Step 2: Battery optimization ──────────────────────────
        layout.addView(text("STEP 2", size = 11f, color = "#666666", bold = true, topPad = 16))
        layout.addView(text("Disable Battery Optimization", size = 16f, color = "#FFFFFF", bottomPad = 4))
        layout.addView(text(
            "Prevents Android from killing the background service. " +
            "Critical on Samsung (Game Optimizing Service), Xiaomi (MIUI Battery Saver), " +
            "OnePlus, and Pixel. Without this, the server dies within minutes of screen-off.",
            size = 12f, color = "#999999", bottomPad = 12
        ))
        step2Status = statusLabel()
        layout.addView(step2Status)
        step2Btn = actionButton("Request Battery Exemption") {
            requestBatteryExemption()
        }
        layout.addView(step2Btn)
        layout.addView(divider())

        // ── Step 3: Screen capture ────────────────────────────────
        layout.addView(text("STEP 3", size = 11f, color = "#666666", bold = true, topPad = 16))
        layout.addView(text("Grant Screen Capture", size = 16f, color = "#FFFFFF", bottomPad = 4))
        layout.addView(text(
            "MediaProjection — captures whatever is on screen. The screenshot " +
            "goes to the LLM so it can see Chrome pages, LinkedIn feeds, etc.",
            size = 12f, color = "#999999", bottomPad = 12
        ))
        step3Status = statusLabel()
        layout.addView(step3Status)
        step3Btn = actionButton("Grant Screen Capture") {
            requestScreenCapture()
        }
        layout.addView(step3Btn)
        layout.addView(divider())

        // ── Step 4: Relay config ───────────────────────────────────
        layout.addView(text("STEP 4", size = 11f, color = "#666666", bold = true, topPad = 16))
        layout.addView(text("Configure Relay (VM Connection)", size = 16f, color = "#FFFFFF", bottomPad = 4))
        layout.addView(text(
            "Enter your VM's relay WebSocket URL. The phone connects OUT " +
            "to the VM — no port forwarding, no VPN needed. " +
            "Leave blank for local-only (LAN/ADB) mode.",
            size = 12f, color = "#999999", bottomPad = 12
        ))

        relayUrlInput = android.widget.EditText(this).apply {
            hint = "ws://your-vm-ip:9091"
            textSize = 14f
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#555555"))
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(24, 16, 24, 16)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        layout.addView(relayUrlInput)

        layout.addView(text("Auth Token (optional)", size = 11f, color = "#666666", topPad = 8, bottomPad = 4))
        relayTokenInput = android.widget.EditText(this).apply {
            hint = "shared secret"
            textSize = 14f
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#555555"))
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(24, 16, 24, 16)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(relayTokenInput)
        layout.addView(divider())

        // ── Step 5: Start server ──────────────────────────────────
        layout.addView(text("STEP 5", size = 11f, color = "#666666", bold = true, topPad = 16))
        layout.addView(text("Start & Minimize", size = 16f, color = "#FFFFFF", bottomPad = 4))
        layout.addView(text(
            "Starts local HTTP server + relay connection (if configured), " +
            "then minimizes the app. Everything runs as a foreground service.",
            size = 12f, color = "#999999", bottomPad = 12
        ))
        step4Status = statusLabel()
        layout.addView(step4Status)
        step4Btn = actionButton("Start Server & Minimize") {
            startServerAndMinimize()
        }
        layout.addView(step4Btn)
        layout.addView(divider())

        // ── Server info ───────────────────────────────────────────
        serverInfoText = text("", size = 12f, color = "#00FF88", topPad = 16).apply {
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            visibility = View.GONE
        }
        layout.addView(serverInfoText)

        // ── OEM-specific warnings ─────────────────────────────────
        layout.addView(text("OEM NOTES", size = 11f, color = "#666666", bold = true, topPad = 24))
        layout.addView(text(
            "Samsung: Also disable 'Adaptive battery' and 'Put unused apps to sleep' " +
            "in Settings → Battery → Background usage limits.\n\n" +
            "Xiaomi: Lock the app in Recents (long press → lock icon) and disable " +
            "'Battery saver' for PhantomTouch in app settings.\n\n" +
            "OnePlus: Disable 'Optimized charging' and battery optimization per-app.",
            size = 11f, color = "#777777", bottomPad = 16
        ))

        // ── Log ───────────────────────────────────────────────────
        layout.addView(text("LOG", size = 11f, color = "#666666", bold = true, topPad = 8))
        logText = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#555555"))
            typeface = Typeface.MONOSPACE
        }
        layout.addView(logText)

        root.addView(layout)
        setContentView(root)

        // Restore saved relay URL
        val prefs = getPreferences(Context.MODE_PRIVATE)
        relayUrlInput.setText(prefs.getString("relay_url", ""))
        relayTokenInput.setText(prefs.getString("relay_token", ""))
    }

    override fun onResume() {
        super.onResume()
        refreshChecklist()
    }

    // ── Permission checks ─────────────────────────────────────────

    private fun isAccessibilityEnabled(): Boolean {
        return GestureService.instance != null
    }

    private fun isBatteryExempt(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isServerRunning(): Boolean {
        return CaptureService.instance != null
    }

    // ── Checklist UI ──────────────────────────────────────────────

    private fun refreshChecklist() {
        val a11y = isAccessibilityEnabled()
        val battery = isBatteryExempt()
        val projection = projectionResultCode != null || isServerRunning()
        val server = isServerRunning()
        val relay = CaptureService.instance?.isRelayConnected() == true

        updateStep(step1Status, step1Btn, a11y, "Accessibility Service")
        updateStep(step2Status, step2Btn, battery, "Battery Exemption")
        updateStep(step3Status, step3Btn, projection, "Screen Capture", enabled = true)
        updateStep(step4Status, step4Btn, server, "Server Running", enabled = a11y && projection)

        if (server) {
            val ip = getLocalIp() ?: "???"
            val relayUrl = relayUrlInput.text.toString().trim()
            serverInfoText.visibility = View.VISIBLE
            serverInfoText.text = buildString {
                appendLine("═══ SERVER LIVE ═══")
                appendLine()
                appendLine("Local: http://$ip:${CaptureService.SERVER_PORT}/health")
                if (relay && relayUrl.isNotEmpty()) {
                    appendLine()
                    appendLine("Relay: connected → $relayUrl")
                    appendLine("  VM gateway hits http://localhost:9090/execute")
                }
                appendLine()
                appendLine("App is minimized. Service is running.")
            }
        } else {
            serverInfoText.visibility = View.GONE
        }
    }

    private fun updateStep(status: TextView, btn: Button, done: Boolean, label: String, enabled: Boolean = true) {
        if (done) {
            status.text = "✓ $label"
            status.setTextColor(Color.parseColor("#00FF88"))
            btn.visibility = View.GONE
        } else {
            status.text = "✗ $label"
            status.setTextColor(Color.parseColor("#FF4444"))
            btn.visibility = View.VISIBLE
            btn.isEnabled = enabled
            btn.alpha = if (enabled) 1.0f else 0.4f
        }
    }

    // ── Actions ───────────────────────────────────────────────────

    private fun requestBatteryExemption() {
        try {
            startActivityForResult(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                },
                REQUEST_BATTERY_EXEMPTION
            )
        } catch (e: Exception) {
            // Some OEM ROMs block the direct intent
            log("Direct battery intent blocked — opening battery settings")
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                log("Battery settings also blocked — open Settings manually")
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    private var projectionResultCode: Int? = null
    private var projectionData: Intent? = null

    private fun startServerAndMinimize() {
        val rc = projectionResultCode
        val data = projectionData

        if (rc == null || data == null) {
            log("ERROR: grant screen capture first (step 3)")
            return
        }
        if (!isAccessibilityEnabled()) {
            log("ERROR: enable accessibility service first (step 1)")
            return
        }

        val relayUrl = relayUrlInput.text.toString().trim()
        val relayToken = relayTokenInput.text.toString().trim()

        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, rc)
            putExtra(CaptureService.EXTRA_DATA, data)
            if (relayUrl.isNotEmpty()) {
                putExtra(CaptureService.EXTRA_RELAY_URL, relayUrl)
                putExtra(CaptureService.EXTRA_RELAY_TOKEN, relayToken)
            }
        }
        startForegroundService(serviceIntent)

        if (relayUrl.isNotEmpty()) {
            log("Started: local :${CaptureService.SERVER_PORT} + relay → $relayUrl")
        } else {
            log("Started: local :${CaptureService.SERVER_PORT} (no relay)")
        }

        // Save relay URL for next launch
        getPreferences(Context.MODE_PRIVATE).edit().apply {
            putString("relay_url", relayUrl)
            putString("relay_token", relayToken)
            apply()
        }

        // Let service initialize, then refresh and go home
        window.decorView.postDelayed({
            refreshChecklist()
            moveTaskToBack(true)
        }, 600)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    projectionResultCode = resultCode
                    projectionData = data
                    log("Screen capture granted")
                } else {
                    log("Screen capture DENIED")
                }
            }
            REQUEST_BATTERY_EXEMPTION -> {
                log("Battery exemption: ${if (isBatteryExempt()) "granted" else "denied"}")
            }
        }
        refreshChecklist()
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun getLocalIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (e: Exception) { null }
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread { logText.append("[$ts] $msg\n") }
    }

    // ── View builders ─────────────────────────────────────────────

    private fun text(
        content: String, size: Float, color: String = "#FFFFFF",
        bold: Boolean = false, topPad: Int = 0, bottomPad: Int = 0
    ): TextView = TextView(this).apply {
        text = content
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, topPad, 0, bottomPad)
    }

    private fun statusLabel(): TextView = TextView(this).apply {
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, 8)
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        setBackgroundColor(Color.parseColor("#1A1A1A"))
        setTextColor(Color.parseColor("#FFFFFF"))
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#222222"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(0, 24, 0, 8) }
    }
}
