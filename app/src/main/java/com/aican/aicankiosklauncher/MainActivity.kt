package com.aican.aicankiosklauncher

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.KeyEvent

/**
 * MainActivity — The Kiosk Launcher.
 *
 * This activity acts as the device home screen (launcher). When the app is set
 * as Device Owner, it locks down the device into full kiosk mode:
 * - Immersive full-screen (no status bar, no navigation)
 * - Lock task mode (user cannot leave the app)
 * - User restrictions (no factory reset, no unknown apps, no new accounts)
 * - System packages suspended (settings, etc.)
 * - Screen always on
 * - Displays whitelisted apps in a grid for launching within kiosk mode
 *
 * Secret Exit: Tap the logo 7 times → enter password → exits kiosk → opens AdminActivity.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var wifiManager: WifiManager

    private lateinit var tvClock: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvWifiSSID: TextView
    private lateinit var btnOpenWifiSystem: Button
    private lateinit var btnOpenUserSettings: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvNoApps: TextView
    private lateinit var rvWhitelistedApps: RecyclerView
    private lateinit var whitelistedAppAdapter: WhitelistedAppAdapter

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 1000)
        }
    }

    private var tapCount = 0
    private val tapResetHandler = Handler(Looper.getMainLooper())
    private var isBatteryReceiverRegistered = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryStatus(intent)
        }
    }
    private var isPermissionFlowActive = false
    private var permissionWizardDialog: AlertDialog? = null
    private var restrictedSettingsConfirmed = false
    private var suppressPermissionWizardUntil = 0L
    private var lastSettingsClickAt = 0L
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        isPermissionFlowActive = false
        continuePermissionFlow()
    }
    private val systemWifiPanelLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hideSystemUI()
        updateConnectedWifiStatus()
    }

    companion object {
        private const val ADMIN_PASSWORD = "aican2024"
        private const val SECRET_TAP_COUNT = 7
        private const val TAP_RESET_DELAY_MS = 3000L
        private const val PREFS_NAME = "kiosk_prefs"
        private const val KEY_WHITELISTED_APPS = "whitelisted_apps"
        private const val KEY_FIRST_LAUNCH_PERMISSIONS_DONE = "first_launch_permissions_done"
        private const val KEY_RESTRICTED_SETTINGS_CONFIRMED = "restricted_settings_confirmed"
        private const val GRID_COLUMNS = 4
    }

    private enum class PermissionStep {
        RESTRICTED_SETTINGS,
        ACCESS_FINE_LOCATION,
        NEARBY_WIFI_DEVICES,
        MODIFY_SYSTEM_SETTINGS,
        INSTALL_UNKNOWN_APPS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        restrictedSettingsConfirmed = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESTRICTED_SETTINGS_CONFIRMED, false)

        // Hide system UI before setting content
        hideSystemUI()

        setContentView(R.layout.activity_main)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        KioskWatchdogStarter.start(this)

        // Bind views
        tvClock = findViewById(R.id.tvClock)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        tvWifiSSID = findViewById(R.id.tvWifiSSID)
        btnOpenWifiSystem = findViewById(R.id.btnOpenWifiSystem)
        btnOpenUserSettings = findViewById(R.id.btnOpenUserSettings)
        tvStatus = findViewById(R.id.tvStatus)
        tvNoApps = findViewById(R.id.tvNoApps)
        rvWhitelistedApps = findViewById(R.id.rvWhitelistedApps)

        // Set up whitelisted apps grid
        whitelistedAppAdapter = WhitelistedAppAdapter(emptyList()) { targetPackage ->
            launchWhitelistedApp(targetPackage)
        }
        rvWhitelistedApps.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        rvWhitelistedApps.adapter = whitelistedAppAdapter

        refreshKioskStatus()

        expandTouchTarget(btnOpenUserSettings, 28)

        btnOpenUserSettings.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastSettingsClickAt < 500) return@setOnClickListener
            lastSettingsClickAt = now
            suppressPermissionWizardUntil = now + 1500L
            startActivity(Intent(this, UserSettingsActivity::class.java))
        }
        btnOpenWifiSystem.setOnClickListener {
            openSystemWifiPanel()
        }

        // Secret admin exit — 7 taps on logo
        setupSecretExit()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        clockHandler.post(clockRunnable)
        startBatteryMonitoring()

        // Refresh whitelisted apps every time we return to the home screen
        loadAndDisplayWhitelistedApps()

        refreshKioskStatus()
        updateConnectedWifiStatus()

        // Re-lock if device owner and not already in lock task
        if (dpm.isDeviceOwnerApp(packageName)) {
            try {
                dpm.setLockTaskPackages(adminComponent, KioskInstallHelper.buildAllowedPackages(this))
                startLockTask()
            } catch (e: Exception) {
                // Already in lock task mode, ignore
            }
        }

        continuePermissionFlow()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
                -> true // consume & block
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        stopBatteryMonitoring()
    }

    private fun refreshKioskStatus() {
        if (dpm.isDeviceOwnerApp(packageName)) {
            lockDownDevice()
            tvStatus.text = getString(R.string.kiosk_state_active_short)
            tvStatus.setTextColor(getColor(R.color.kiosk_status_active))
        } else {
            try {
                stopLockTask()
            } catch (_: Exception) {
            }

            tvStatus.text = getString(R.string.kiosk_state_inactive_short)
            tvStatus.setTextColor(getColor(R.color.kiosk_status_inactive))
        }
    }

    private fun continuePermissionFlow() {
        if (System.currentTimeMillis() < suppressPermissionWizardUntil) return
        if (isFirstLaunchPermissionFlowCompleted()) {
            permissionWizardDialog?.dismiss()
            permissionWizardDialog = null
            return
        }
        showOrUpdatePermissionWizard()
    }

    private fun nextMissingPermissionStep(): PermissionStep? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !restrictedSettingsConfirmed) {
            return PermissionStep.RESTRICTED_SETTINGS
        }
        if (!hasRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return PermissionStep.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasRuntimePermission(Manifest.permission.NEARBY_WIFI_DEVICES)
        ) {
            return PermissionStep.NEARBY_WIFI_DEVICES
        }
        if (!Settings.System.canWrite(this)) {
            return PermissionStep.MODIFY_SYSTEM_SETTINGS
        }
        if (!packageManager.canRequestPackageInstalls()) {
            return PermissionStep.INSTALL_UNKNOWN_APPS
        }
        return null
    }

    private fun hasRuntimePermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isFirstLaunchPermissionFlowCompleted(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FIRST_LAUNCH_PERMISSIONS_DONE, false)
    }

    private fun markFirstLaunchPermissionFlowCompleted() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FIRST_LAUNCH_PERMISSIONS_DONE, true)
            .putBoolean(KEY_RESTRICTED_SETTINGS_CONFIRMED, restrictedSettingsConfirmed)
            .apply()
        isPermissionFlowActive = false
        permissionWizardDialog?.dismiss()
        permissionWizardDialog = null
    }

    private fun showOrUpdatePermissionWizard() {
        val dialog = permissionWizardDialog ?: createPermissionWizardDialog().also {
            permissionWizardDialog = it
            it.show()
        }

        val nextStep = nextMissingPermissionStep()
        if (nextStep == null) {
            markFirstLaunchPermissionFlowCompleted()
            return
        }

        val tvChecklist = dialog.findViewById<TextView>(R.id.tvPermissionWizardChecklist) ?: return
        val tvStepHint = dialog.findViewById<TextView>(R.id.tvPermissionWizardStepHint) ?: return
        val btnPrimary = dialog.findViewById<Button>(R.id.btnPermissionWizardPrimary) ?: return
        val btnSecondary = dialog.findViewById<Button>(R.id.btnPermissionWizardSecondary) ?: return

        tvChecklist.text = buildPermissionChecklistText()
        tvStepHint.text = stepHintText(nextStep)
        btnPrimary.text = getString(R.string.permission_wizard_action_grant_now)
        btnPrimary.setOnClickListener { performPermissionStep(nextStep) }

        if (nextStep == PermissionStep.RESTRICTED_SETTINGS) {
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.text = getString(R.string.permission_wizard_action_recheck)
            btnSecondary.setOnClickListener {
                restrictedSettingsConfirmed = true
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_RESTRICTED_SETTINGS_CONFIRMED, true)
                    .apply()
                continuePermissionFlow()
            }
        } else {
            btnSecondary.visibility = View.GONE
            btnSecondary.setOnClickListener(null)
        }
    }

    private fun createPermissionWizardDialog(): AlertDialog {
        val view = layoutInflater.inflate(R.layout.dialog_permission_wizard, null)
        return AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create().apply {
                setCanceledOnTouchOutside(false)
            }
    }

    private fun expandTouchTarget(view: View, extraDp: Int) {
        val parent = view.parent as? View ?: return
        val extraPx = (extraDp * resources.displayMetrics.density).toInt()
        parent.post {
            val rect = Rect()
            view.getHitRect(rect)
            rect.left -= extraPx
            rect.top -= extraPx
            rect.right += extraPx
            rect.bottom += extraPx
            parent.touchDelegate = android.view.TouchDelegate(rect, view)
        }
    }

    private fun performPermissionStep(step: PermissionStep) {
        if (isPermissionFlowActive) return
        isPermissionFlowActive = true
        when (step) {
            PermissionStep.RESTRICTED_SETTINGS -> {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
                isPermissionFlowActive = false
            }
            PermissionStep.ACCESS_FINE_LOCATION -> {
                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            PermissionStep.NEARBY_WIFI_DEVICES -> {
                permissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            PermissionStep.MODIFY_SYSTEM_SETTINGS -> {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
                isPermissionFlowActive = false
            }
            PermissionStep.INSTALL_UNKNOWN_APPS -> {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                })
                isPermissionFlowActive = false
            }
        }
    }

    private fun buildPermissionChecklistText(): CharSequence {
        val statusDone = "✓"
        val statusPending = "•"
        val builder = SpannableStringBuilder()
        val steps = listOf(
            PermissionStep.RESTRICTED_SETTINGS,
            PermissionStep.ACCESS_FINE_LOCATION,
            PermissionStep.NEARBY_WIFI_DEVICES,
            PermissionStep.MODIFY_SYSTEM_SETTINGS,
            PermissionStep.INSTALL_UNKNOWN_APPS
        ).filterNot { it == PermissionStep.NEARBY_WIFI_DEVICES && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU }

        steps.forEachIndexed { index, step ->
            val granted = isStepGranted(step)
            val status = if (granted) statusDone else statusPending
            builder.append("$status ${stepLabel(step)}")
            if (index != steps.lastIndex) builder.append("\n")
        }
        return builder
    }

    private fun isStepGranted(step: PermissionStep): Boolean {
        return when (step) {
            PermissionStep.RESTRICTED_SETTINGS -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true else restrictedSettingsConfirmed
            }
            PermissionStep.ACCESS_FINE_LOCATION -> hasRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION)
            PermissionStep.NEARBY_WIFI_DEVICES -> {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    hasRuntimePermission(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            PermissionStep.MODIFY_SYSTEM_SETTINGS -> Settings.System.canWrite(this)
            PermissionStep.INSTALL_UNKNOWN_APPS -> packageManager.canRequestPackageInstalls()
        }
    }

    private fun stepLabel(step: PermissionStep): String {
        return when (step) {
            PermissionStep.RESTRICTED_SETTINGS -> "Allow restricted settings"
            PermissionStep.ACCESS_FINE_LOCATION -> "Location (Wi-Fi scan)"
            PermissionStep.NEARBY_WIFI_DEVICES -> "Nearby Wi-Fi devices"
            PermissionStep.MODIFY_SYSTEM_SETTINGS -> "Modify system settings"
            PermissionStep.INSTALL_UNKNOWN_APPS -> "Install unknown apps"
        }
    }

    private fun stepHintText(step: PermissionStep): String {
        return when (step) {
            PermissionStep.RESTRICTED_SETTINGS -> getString(R.string.permission_wizard_step_restricted)
            PermissionStep.ACCESS_FINE_LOCATION -> getString(R.string.permission_wizard_step_location)
            PermissionStep.NEARBY_WIFI_DEVICES -> getString(R.string.permission_wizard_step_nearby)
            PermissionStep.MODIFY_SYSTEM_SETTINGS -> getString(R.string.permission_wizard_step_write_settings)
            PermissionStep.INSTALL_UNKNOWN_APPS -> getString(R.string.permission_wizard_step_install_unknown)
        }
    }

    private fun startBatteryMonitoring() {
        if (!isBatteryReceiverRegistered) {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            isBatteryReceiverRegistered = true
        }
        val stickyIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        updateBatteryStatus(stickyIntent)
    }

    private fun stopBatteryMonitoring() {
        if (!isBatteryReceiverRegistered) return
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
        isBatteryReceiverRegistered = false
    }

    private fun updateBatteryStatus(intent: Intent?) {
        if (intent == null) {
            tvBatteryStatus.text = getString(R.string.battery_status_unavailable)
            tvBatteryStatus.setTextColor(getColor(R.color.kiosk_text_secondary))
            return
        }

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val percent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else -1

        val statusText = when {
            status == BatteryManager.BATTERY_STATUS_FULL -> getString(R.string.battery_state_full)
            status == BatteryManager.BATTERY_STATUS_CHARGING && plugged == BatteryManager.BATTERY_PLUGGED_USB ->
                getString(R.string.battery_state_charging_usb)
            status == BatteryManager.BATTERY_STATUS_CHARGING && plugged == BatteryManager.BATTERY_PLUGGED_AC ->
                getString(R.string.battery_state_charging_ac)
            status == BatteryManager.BATTERY_STATUS_CHARGING && plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ->
                getString(R.string.battery_state_charging_wireless)
            status == BatteryManager.BATTERY_STATUS_CHARGING -> getString(R.string.battery_state_charging)
            percent in 0..15 -> getString(R.string.battery_state_low)
            else -> getString(R.string.battery_state_on_battery)
        }

        tvBatteryStatus.text = if (percent >= 0) {
            getString(R.string.battery_status_format, percent, statusText)
        } else {
            getString(R.string.battery_status_unavailable)
        }

        val colorRes = when {
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL ->
                R.color.kiosk_status_active
            percent in 0..15 -> R.color.kiosk_status_inactive
            else -> R.color.kiosk_text_primary
        }
        tvBatteryStatus.setTextColor(getColor(colorRes))
    }

    @Suppress("DEPRECATION")
    private fun updateConnectedWifiStatus() {
        if (!wifiManager.isWifiEnabled) {
            tvWifiSSID.text = getString(R.string.user_settings_connected_wifi_off)
            return
        }

        if (!hasRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            tvWifiSSID.text = getString(R.string.user_settings_connected_wifi_permission_short)
            return
        }

        val ssidRaw = wifiManager.connectionInfo?.ssid ?: WifiManager.UNKNOWN_SSID
        val ssid = ssidRaw.removePrefix("\"").removeSuffix("\"")
        tvWifiSSID.text = if (ssid.isBlank() || ssid == WifiManager.UNKNOWN_SSID) {
            getString(R.string.user_settings_connected_wifi_none_short)
        } else {
            ssid
        }
    }

    private fun openSystemWifiPanel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, getString(R.string.user_settings_open_system_wifi_failed), Toast.LENGTH_LONG).show()
            return
        }

        try {
            systemWifiPanelLauncher.launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.user_settings_open_system_wifi_failed), Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, getString(R.string.user_settings_open_system_wifi_failed), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.user_settings_open_system_wifi_failed), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Load whitelisted app package names from SharedPreferences,
     * resolve their info from PackageManager, and display in the grid.
     */
    private fun loadAndDisplayWhitelistedApps() {
        val whitelistedPkgs = loadWhitelistedApps()
        val launchableAppsByPackage = getLaunchableAppsByPackage()

        val apps = whitelistedPkgs.mapNotNull { pkgName ->
            launchableAppsByPackage[pkgName]?.copy(isWhitelisted = true)
        }.sortedBy { it.appName.lowercase() }

        if (apps.isEmpty()) {
            tvNoApps.visibility = View.VISIBLE
            rvWhitelistedApps.visibility = View.GONE
            return
        }

        tvNoApps.visibility = View.GONE
        rvWhitelistedApps.visibility = View.VISIBLE

        whitelistedAppAdapter.updateData(apps)
    }

    /**
     * Build a package -> app info map using launcher activities so the home screen
     * only shows apps that are actually launchable from the device.
     */
    private fun getLaunchableAppsByPackage(): Map<String, InstalledAppInfo> {
        val pm = packageManager
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != packageName }
            .mapNotNull { appInfo ->
                val pkgName = appInfo.packageName
                val launchIntent = pm.getLaunchIntentForPackage(pkgName) ?: return@mapNotNull null
                launchIntent.resolveActivityInfo(pm, 0) ?: return@mapNotNull null

                pkgName to InstalledAppInfo(
                    packageName = pkgName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo),
                    isWhitelisted = false
                )
            }
            .toMap()
    }

    /**
     * Load whitelisted app package names from SharedPreferences.
     */
    private fun loadWhitelistedApps(): Set<String> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_WHITELISTED_APPS, emptySet())?.toSet() ?: emptySet()
    }

    /**
     * Launch a whitelisted app within lock-task mode.
     */
    private fun launchWhitelistedApp(targetPackage: String) {
        try {
            // Ensure the target package is in the allowed lock-task list
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.setLockTaskPackages(adminComponent, KioskInstallHelper.buildAllowedPackages(this))
            }

            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                // Launch inside the current task so Back can return to our launcher.
                launchIntent.flags = launchIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                launchIntent.flags = launchIntent.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED.inv()
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "Cannot launch app", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error launching app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Lock down the device using Device Policy Manager.
     * Only works when the app is set as Device Owner.
     */
    private fun lockDownDevice()
    {
        try {
            registerAsLauncher()

            // 1. Set allowed packages (our package + whitelisted) and start lock task
            dpm.setLockTaskPackages(adminComponent, KioskInstallHelper.buildAllowedPackages(this))
            // Keep lock-task strict but allow hardware power-menu actions (long-press power).
            // This restores Restart / Power off options without opening navigation or notifications.
            dpm.setLockTaskFeatures(
                adminComponent,
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
            )
            startLockTask()

            // 2. Prevent factory reset
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)

            // 3. Prevent adding accounts
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)

            // 4. Prevent sideloading
//            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)

            // 5. Disable safe boot
//            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)

            // ── THESE WERE MISSING ──

            // 6. Prevent WiFi changes (this blocks Settings → WiFi)
//        dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_WIFI)

            // 7. Prevent Bluetooth changes
//            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_BLUETOOTH)

            // 8. Prevent USB file transfer
//        dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)

            // ── NETWORK & CONNECTIVITY (uncomment as needed) ──
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_TETHERING)
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_AIRPLANE_MODE)        // API 28+
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_VPN)
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_NETWORK_RESET)

            // ── APPS & CONTENT (uncomment as needed) ──
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)

            // ── HARDWARE & MEDIA (uncomment as needed) ──
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADJUST_VOLUME)
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNMUTE_MICROPHONE)
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT) // API 28+
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_BRIGHTNESS)     // API 28+

            // ── SECURITY (uncomment as needed) ──
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS)
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_CREDENTIALS)
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_LOCATION)       // API 28+
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_DATE_TIME)      // API 28+
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)    // ⚠️ LOCKS OUT ADB!

            // ── UI & MISC (uncomment as needed) ──
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CREATE_WINDOWS)
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SET_WALLPAPER)
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_OUTGOING_CALLS)
            // dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SMS)

            // 11. Suspend settings + other system apps
            try {
                dpm.setPackagesSuspended(
                    adminComponent,
                    arrayOf(
                        "com.android.settings",
                        "com.miui.home",
                        "com.miui.securitycenter",
                        "com.google.android.gms",
                        "com.miui.gallery",
                        "com.mi.android.globalFileexplorer"
                    ),
                    true
                )
            } catch (e: Exception) {
                // Some packages may not be suspendable
            }

            KioskWatchdogStarter.start(this)

        } catch (e: Exception) {
            Toast.makeText(this, "Lock failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Register this app as the persistent HOME launcher while we are device owner.
     */
    private fun registerAsLauncher() {
        if (!dpm.isDeviceOwnerApp(packageName)) return

        try {
            val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }

            dpm.clearPackagePersistentPreferredActivities(adminComponent, packageName)
            val launcherComponent = ComponentName(this, SplashActivity::class.java)
            dpm.addPersistentPreferredActivity(adminComponent, homeFilter, launcherComponent)
        } catch (_: Exception) {
            // Some devices/ROMs may reject this; keep kiosk startup alive.
        }
    }

    /**
     * Immersive sticky mode — hides status bar and navigation bar.
     */
    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
    }

    /**
     * Update the clock display with current time.
     */
    private fun updateClock() {
        val sdf = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
        tvClock.text = sdf.format(Date())
    }

    /**
     * Set up secret 7-tap exit on the logo.
     */
    private fun setupSecretExit() {
        tvClock.setOnClickListener {
            tapCount++
            tapResetHandler.removeCallbacksAndMessages(null)

            if (tapCount >= SECRET_TAP_COUNT) {
                tapCount = 0
                showAdminPasswordDialog()
            } else {
                // Reset tap count after 3 seconds of no tapping
                tapResetHandler.postDelayed({ tapCount = 0 }, TAP_RESET_DELAY_MS)
            }
        }
    }

    /**
     * Show password dialog for admin access.
     */
    private fun showAdminPasswordDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.admin_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.admin_password_title))
            .setView(editText)
            .setPositiveButton("Unlock") { _, _ ->
                val password = editText.text.toString()
                if (password == ADMIN_PASSWORD) {
                    exitKioskMode()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.admin_password_incorrect),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }

    /**
     * Exit kiosk mode and launch the admin panel.
     */
    private fun exitKioskMode() {
        try {
            stopLockTask()
        } catch (e: Exception) {
            // May already be unlocked
        }

        val intent = Intent(this, AdminActivity::class.java)
        startActivity(intent)
    }

    /**
     * Override back press — blocked in kiosk mode.
     */
    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // No-op: back button is disabled in kiosk mode
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }
}
