package com.aican.aicankiosklauncher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.KeyEvent
import androidx.core.content.ContextCompat

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

    private lateinit var tvClock: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDetail: TextView
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

    companion object {
        private const val ADMIN_PASSWORD = "aican2024"
        private const val SECRET_TAP_COUNT = 7
        private const val TAP_RESET_DELAY_MS = 3000L
        private const val PREFS_NAME = "kiosk_prefs"
        private const val KEY_WHITELISTED_APPS = "whitelisted_apps"
        private const val GRID_COLUMNS = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        // Hide system UI before setting content
        hideSystemUI()

        setContentView(R.layout.activity_main)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ContextCompat.startForegroundService(this, Intent(this, KioskWatchdogService::class.java))

        // Bind views
        tvClock = findViewById(R.id.tvClock)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        tvNoApps = findViewById(R.id.tvNoApps)
        rvWhitelistedApps = findViewById(R.id.rvWhitelistedApps)

        // Set up whitelisted apps grid
        whitelistedAppAdapter = WhitelistedAppAdapter(emptyList()) { targetPackage ->
            launchWhitelistedApp(targetPackage)
        }
        rvWhitelistedApps.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        rvWhitelistedApps.adapter = whitelistedAppAdapter

        // Check if we are device owner and lock down
        if (dpm.isDeviceOwnerApp(packageName)) {
            lockDownDevice()
            tvStatus.text = getString(R.string.kiosk_mode_active)
            tvStatusDetail.text = getString(R.string.kiosk_status_locked)
            tvStatus.setTextColor(getColor(R.color.kiosk_status_active))
        } else {
            tvStatus.text = "NOT DEVICE OWNER"
            tvStatusDetail.text = getString(R.string.kiosk_status_unlocked)
            tvStatus.setTextColor(getColor(R.color.kiosk_status_inactive))
        }

        // Secret admin exit — 7 taps on logo
        setupSecretExit()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        clockHandler.post(clockRunnable)

        // Refresh whitelisted apps every time we return to the home screen
        loadAndDisplayWhitelistedApps()

        // Re-lock if device owner and not already in lock task
        if (dpm.isDeviceOwnerApp(packageName)) {
            try {
                val whitelisted = loadWhitelistedApps()
                val allAllowed = mutableListOf(packageName)
                allAllowed.addAll(whitelisted)
                dpm.setLockTaskPackages(adminComponent, allAllowed.toTypedArray())
                startLockTask()
            } catch (e: Exception) {
                // Already in lock task mode, ignore
            }
        }
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
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .associate { resolveInfo ->
                val pkgName = resolveInfo.activityInfo.packageName
                pkgName to InstalledAppInfo(
                    packageName = pkgName,
                    appName = resolveInfo.loadLabel(pm).toString(),
                    icon = resolveInfo.loadIcon(pm),
                    isWhitelisted = false
                )
            }
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
                val whitelisted = loadWhitelistedApps()
                val allAllowed = mutableListOf(packageName)
                allAllowed.addAll(whitelisted)
                dpm.setLockTaskPackages(adminComponent, allAllowed.toTypedArray())
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
            // 1. Set allowed packages (our package + whitelisted) and start lock task
            val whitelisted = loadWhitelistedApps()
            val allAllowed = mutableListOf(packageName)
            allAllowed.addAll(whitelisted)
            dpm.setLockTaskPackages(adminComponent, allAllowed.toTypedArray())
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
                        "com.android.vending",
                        "com.miui.gallery",
                        "com.mi.android.globalFileexplorer"
                    ),
                    true
                )
            } catch (e: Exception) {
                // Some packages may not be suspendable
            }

            ContextCompat.startForegroundService(this, Intent(this, KioskWatchdogService::class.java))

        } catch (e: Exception) {
            Toast.makeText(this, "Lock failed: ${e.message}", Toast.LENGTH_LONG).show()
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
