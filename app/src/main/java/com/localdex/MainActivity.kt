package com.localdex

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.localdex.scrcpy.ScrcpySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var refreshButton: Button
    private lateinit var configGroup: View
    private lateinit var displaySpecPresetGroup: MaterialButtonToggleGroup
    private lateinit var displaySpecField: EditText
    private lateinit var panelPositionGroup: MaterialButtonToggleGroup
    private lateinit var startButton: Button
    private lateinit var viewerButton: Button
    private lateinit var stopButton: Button
    private lateinit var diagnosticsButton: Button

    // Denial just means the setup checklist's notification step stays unchecked
    // (areNotificationsEnabled() already reflects it) and pairing discovery fails
    // gracefully (AdbMdns catches the resulting SecurityException) — nothing here
    // needs the actual grant results, just a re-check once the dialog is gone.
    private val requestRuntimePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { checkStatus(forceCheck = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        actionButton = findViewById(R.id.actionButton)
        refreshButton = findViewById(R.id.refreshButton)
        configGroup = findViewById(R.id.configGroup)
        displaySpecPresetGroup = findViewById(R.id.displaySpecPresetGroup)
        displaySpecField = findViewById(R.id.displaySpecField)
        panelPositionGroup = findViewById(R.id.panelPositionGroup)
        startButton = findViewById(R.id.startButton)
        viewerButton = findViewById(R.id.viewerButton)
        stopButton = findViewById(R.id.stopButton)
        diagnosticsButton = findViewById(R.id.diagnosticsButton)

        val initialSpec = Prefs.getDisplaySpec(this)
        displaySpecField.setText(initialSpec)
        // Reflect whatever's actually in the field: one of the named presets if it
        // matches exactly, Custom otherwise (a previously typed value, most likely).
        displaySpecPresetGroup.check(
            DISPLAY_SPEC_PRESETS.entries.find { it.value == initialSpec }?.key ?: R.id.presetCustom
        )
        displaySpecPresetGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            // presetCustom has no mapped spec; selecting it just leaves the field as
            // it is, ready for manual editing.
            DISPLAY_SPEC_PRESETS[checkedId]?.let { spec -> displaySpecField.setText(spec) }
        }

        val initialPanelFraction = Prefs.getPanelPositionFraction(this)
        panelPositionGroup.check(
            PANEL_POSITION_PRESETS.entries.find { it.value == initialPanelFraction }?.key
                ?: R.id.panelPositionOneThird
        )
        panelPositionGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            PANEL_POSITION_PRESETS[checkedId]?.let { fraction -> Prefs.setPanelPositionFraction(this, fraction) }
        }

        refreshButton.setOnClickListener { checkStatus(forceCheck = true) }
        startButton.setOnClickListener { startDex() }
        viewerButton.setOnClickListener {
            startActivity(Intent(this, ViewerActivity::class.java))
        }
        stopButton.setOnClickListener {
            DexService.stop(this)
            statusText.postDelayed({ checkStatus() }, 500)
        }
        diagnosticsButton.setOnClickListener { copyDiagnostics() }

        ensureRuntimePermissions()
    }

    /**
     * Requests whichever of the two runtime permissions minSdk 33 requires aren't
     * granted yet: POST_NOTIFICATIONS (the pairing-code entry notification) and
     * NEARBY_WIFI_DEVICES (AdbMdns's pairing-service discovery). Asked once per
     * launch here rather than from onResume, so a denial doesn't re-prompt on
     * every return to the app.
     */
    private fun ensureRuntimePermissions() {
        val missing = RUNTIME_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestRuntimePermissions.launch(missing.toTypedArray())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val fromPairing = intent?.getBooleanExtra(EXTRA_FROM_PAIRING, false) == true
        if (fromPairing) {
            intent.removeExtra(EXTRA_FROM_PAIRING)
        }
        checkStatus(forceCheck = fromPairing)
    }

    private fun checkStatus(forceCheck: Boolean = false) {
        if (ScrcpySession.current != null) {
            showRunningState()
            return
        }

        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                Adb.getConnectionStatus(this@MainActivity, forceCheck)
            }

            when (status) {
                Adb.ConnectionStatus.CONNECTED -> {
                    showConnectedState()
                    stopService(Intent(this@MainActivity, PairingInputService::class.java))
                    Prefs.setHasPairedBefore(this@MainActivity)
                    acquireWirelessDebuggingPermission()
                }
                else -> {
                    // Holding WRITE_SECURE_SETTINGS lets us switch wireless debugging
                    // back on ourselves (it turns off on reboot / network change).
                    if (Prefs.hasPairedBefore(this@MainActivity) &&
                        !WirelessDebugging.isEnabled(this@MainActivity) &&
                        WirelessDebugging.canToggle(this@MainActivity)
                    ) {
                        statusText.text = "Turning wireless debugging on…"
                        val enabled = WirelessDebugging.enable(this@MainActivity)
                        if (enabled) {
                            checkStatus(forceCheck = true)
                            return@launch
                        }
                    }
                    showSetupChecklist()
                }
            }
        }
    }

    private fun acquireWirelessDebuggingPermission() {
        if (permissionGrantAttempted || WirelessDebugging.canToggle(this)) return
        permissionGrantAttempted = true
        lifecycleScope.launch {
            WirelessDebugging.tryAcquireTogglePermission(this@MainActivity)
        }
    }

    private fun showRunningState() {
        val session = ScrcpySession.current
        val displayId = session?.displayId ?: -1
        statusText.text = if (displayId >= 0) {
            buildString {
                append("🖥️ DeX is running on display $displayId.\n\n")
                append("From a computer on the same adb connection you can open the same ")
                append("desktop with:\n\nscrcpy --display-id=$displayId")
                when {
                    session?.freeformForceFailed == true -> append(
                        "\n\n⚠️ Could not switch this display to freeform mode — apps may " +
                            "open fullscreen with no window controls."
                    )
                    session?.freeformForceInProgress == true ->
                        // The freeform result can land a moment after the display id does.
                        statusText.postDelayed({ if (ScrcpySession.current === session) checkStatus() }, 500)
                }
            }
        } else {
            // The display id arrives from the server log moments after start.
            statusText.postDelayed({ if (ScrcpySession.current != null) checkStatus() }, 1000)
            "🖥️ DeX session is starting…"
        }
        actionButton.visibility = View.GONE
        refreshButton.visibility = View.GONE
        configGroup.visibility = View.GONE
        startButton.visibility = View.GONE
        viewerButton.visibility = View.VISIBLE
        stopButton.visibility = View.VISIBLE
    }

    private fun showConnectedState() {
        statusText.text = "✅ ADB connected.\n\n" +
            "Display spec is WIDTHxHEIGHT/DPI — lower DPI means more room, higher " +
            "DPI means bigger UI."
        actionButton.visibility = View.GONE
        refreshButton.visibility = View.VISIBLE
        configGroup.visibility = View.VISIBLE
        startButton.visibility = View.VISIBLE
        viewerButton.visibility = View.GONE
        stopButton.visibility = View.GONE
    }

    private fun showSetupChecklist() {
        val devModeEnabled = isDeveloperOptionsEnabled()
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()

        val step1 = if (devModeEnabled) "✅" else "⬜"
        val step2 = if (notificationsEnabled) "✅" else "⬜"
        val step3 = if (devModeEnabled && notificationsEnabled) "⬜" else "⚪"

        statusText.text = buildString {
            append("Setup Progress:\n\n")
            append("$step1 Step 1: Enable Developer Options\n")
            if (!devModeEnabled) {
                append("   • Settings → About phone → Software information\n")
                append("   • Tap \"Build number\" 7 times\n\n")
            } else {
                append("   Complete!\n\n")
            }
            append("$step2 Step 2: Enable Notifications\n")
            if (!notificationsEnabled) {
                append("   • Required to enter pairing codes\n\n")
            } else {
                append("   Complete!\n\n")
            }
            append("$step3 Step 3: Pair with Wireless ADB\n")
            if (devModeEnabled && notificationsEnabled) {
                append("   • Tap \"Start Pairing\" below\n")
                append("   • In Wireless debugging, tap \"Pair device with pairing code\"\n")
                append("   • Enter the code in the LocalDex notification\n")
            } else {
                append("   Complete previous steps first\n")
            }
        }

        when {
            !devModeEnabled -> {
                actionButton.text = "Open Settings"
                actionButton.setOnClickListener {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Please open Settings manually", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            !notificationsEnabled -> {
                actionButton.text = "Enable Notifications"
                actionButton.setOnClickListener {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                }
            }
            else -> {
                actionButton.text = "Start Pairing"
                actionButton.setOnClickListener { startPairing() }
            }
        }

        actionButton.visibility = View.VISIBLE
        refreshButton.visibility = View.VISIBLE
        configGroup.visibility = View.GONE
        startButton.visibility = View.GONE
        viewerButton.visibility = View.GONE
        stopButton.visibility = View.GONE
    }

    private fun startPairing() {
        startService(Intent(this, PairingInputService::class.java))
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (ex: Exception) {
                Toast.makeText(
                    this,
                    "Open Settings → Developer options → Wireless debugging manually",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startDex() {
        val spec = displaySpecField.text.toString().trim()
        if (!Regex("\\d{3,4}x\\d{3,4}/\\d{2,3}").matches(spec)) {
            Toast.makeText(this, "Display spec must look like 1920x1440/240", Toast.LENGTH_LONG).show()
            return
        }
        Prefs.setDisplaySpec(this, spec)

        DexService.start(this)
        startActivity(Intent(this, ViewerActivity::class.java))
    }

    /**
     * Gathers a text diagnostics report and puts it on the clipboard. Runs on
     * Dispatchers.IO: it does an ADB round-trip and shells out to `logcat`, neither
     * of which belongs on the main thread.
     */
    private fun copyDiagnostics() {
        diagnosticsButton.isEnabled = false
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { Diagnostics.collect(this@MainActivity) }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LocalDex diagnostics", report))
            Toast.makeText(this@MainActivity, "Diagnostics copied", Toast.LENGTH_SHORT).show()
            diagnosticsButton.isEnabled = true
        }
    }

    private fun isDeveloperOptionsEnabled(): Boolean {
        return try {
            Settings.Global.getInt(
                contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            true
        }
    }

    companion object {
        /** Set by [PairingInputService] when it brings the app forward after pairing. */
        const val EXTRA_FROM_PAIRING = "com.localdex.FROM_PAIRING"

        // Only try the self-grant once per session.
        private var permissionGrantAttempted = false

        /**
         * Named display-spec shortcuts for the preset row. Unverified on real
         * hardware which of these actually looks best on a given screen — they're
         * starting points, not measured values. presetCustom is deliberately
         * unmapped: it just leaves the field open for manual entry.
         */
        private val DISPLAY_SPEC_PRESETS = mapOf(
            R.id.presetCompact to "1600x1200/280",
            R.id.presetBalanced to Prefs.DEFAULT_DISPLAY_SPEC,
            R.id.presetSpacious to "2560x1600/220",
        )

        /** Where the viewer's exit tab sits, as a fraction of screen height up from the bottom. */
        private val PANEL_POSITION_PRESETS = mapOf(
            R.id.panelPositionOneThird to 1f / 3f,
            R.id.panelPositionHalf to 1f / 2f,
            R.id.panelPositionTwoThirds to 2f / 3f,
        )

        /** Both are runtime (dangerous) permissions on minSdk 33+ — every device
         *  this app supports needs them requested, not just declared. */
        private val RUNTIME_PERMISSIONS = arrayOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
    }
}
