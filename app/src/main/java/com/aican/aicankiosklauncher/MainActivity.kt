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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 *
 * Secret Exit: Tap the logo 7 times → enter password → exits kiosk → opens AdminActivity.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private lateinit var tvClock: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var ivLogo: ImageView

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

        // Bind views
        tvClock = findViewById(R.id.tvClock)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        ivLogo = findViewById(R.id.ivLogo)

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
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
    }

    /**
     * Lock down the device using Device Policy Manager.
     * Only works when the app is set as Device Owner.
     */
    private fun lockDownDevice() {
        try {
            // 1. Set this app as the only allowed lock task package
            dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
            startLockTask()

            // 2. Prevent factory reset
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)

            // 3. Prevent adding new user accounts
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)

            // 4. Prevent installing apps from unknown sources
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)

            // 5. Disable safe boot
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)

            // 6. Try to suspend system packages (may fail on some devices)
            try {
                dpm.setPackagesSuspended(
                    adminComponent,
                    arrayOf(
                        "com.android.settings",
                        "com.google.android.gms"
                    ),
                    true
                )
            } catch (e: Exception) {
                // Some packages may not be suspendable on certain devices
            }

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
        ivLogo.setOnClickListener {
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