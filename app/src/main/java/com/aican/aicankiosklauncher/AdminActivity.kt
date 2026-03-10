package com.aican.aicankiosklauncher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * AdminActivity — Secret settings panel for servicing the device.
 *
 * Accessible only via the 7-tap password on the kiosk screen.
 * Provides controls to:
 * - View device owner status
 * - Re-lock into kiosk mode
 * - Temporarily clear user restrictions
 * - Open Android Settings
 * - Return to kiosk launcher
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        val tvDeviceOwnerStatus = findViewById<TextView>(R.id.tvDeviceOwnerStatus)
        val btnRelockKiosk = findViewById<Button>(R.id.btnRelockKiosk)
        val btnOpenSettings = findViewById<Button>(R.id.btnOpenSettings)
        val btnClearRestrictions = findViewById<Button>(R.id.btnClearRestrictions)
        val btnReturnToKiosk = findViewById<Button>(R.id.btnReturnToKiosk)

        // Show device owner status
        val isDeviceOwner = dpm.isDeviceOwnerApp(packageName)
        tvDeviceOwnerStatus.text = getString(
            R.string.device_owner_status,
            if (isDeviceOwner) "YES ✅" else "NO ❌"
        )

        // Re-lock into kiosk mode
        btnRelockKiosk.setOnClickListener {
            if (isDeviceOwner) {
                try {
                    dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                    Toast.makeText(this, getString(R.string.kiosk_relocked), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            returnToKiosk()
        }

        // Open Android Settings
        btnOpenSettings.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open settings: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // Temporarily clear user restrictions
        btnClearRestrictions.setOnClickListener {
            if (isDeviceOwner) {
                try {
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)

                    // Unsuspend packages
                    try {
                        dpm.setPackagesSuspended(
                            adminComponent,
                            arrayOf(
                                "com.android.settings",
                                "com.google.android.gms"
                            ),
                            false
                        )
                    } catch (e: Exception) {
                        // Some packages may not be unsuspendable
                    }

                    Toast.makeText(this, getString(R.string.restrictions_cleared), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Return to kiosk
        btnReturnToKiosk.setOnClickListener {
            returnToKiosk()
        }
    }

    private fun returnToKiosk() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        returnToKiosk()
    }
}
