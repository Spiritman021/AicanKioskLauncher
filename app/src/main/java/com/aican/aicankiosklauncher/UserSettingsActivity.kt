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
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class UserSettingsActivity : AppCompatActivity() {
    companion object {
        private const val PREFS_NAME = "kiosk_prefs"
        private const val KEY_LAST_BRIGHTNESS = "last_brightness"
        private const val DEFAULT_BRIGHTNESS = 128
    }

    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var audioManager: AudioManager
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private lateinit var btnTabWifi: Button
    private lateinit var btnTabDisplaySound: Button
    private lateinit var layoutWifiTab: LinearLayout
    private lateinit var layoutDisplaySoundTab: NestedScrollView
    private lateinit var tvConnectedWifi: TextView
    private lateinit var tvWifiStatus: TextView
    private lateinit var switchWifiEnabled: SwitchCompat
    private lateinit var tvBrightnessValue: TextView
    private lateinit var tvVolumeValue: TextView
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekVolume: SeekBar
    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var isWifiReceiverRegistered = false
    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateConnectedWifiStatus()
            syncWifiSwitch()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshWifiList() }
    private val systemPanelLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshWifiList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_settings)
        clearWindowBrightnessOverride()

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        seedSavedBrightnessIfMissing()

        btnTabWifi = findViewById(R.id.btnTabWifi)
        btnTabDisplaySound = findViewById(R.id.btnTabDisplaySound)
        layoutWifiTab = findViewById(R.id.layoutWifiTab)
        layoutDisplaySoundTab = findViewById(R.id.layoutDisplaySoundTab)
        tvConnectedWifi = findViewById(R.id.tvConnectedWifi)
        tvWifiStatus = findViewById(R.id.tvWifiStatus)
        switchWifiEnabled = findViewById(R.id.switchWifiEnabled)
        tvBrightnessValue = findViewById(R.id.tvBrightnessValue)
        tvVolumeValue = findViewById(R.id.tvVolumeValue)
        seekBrightness = findViewById(R.id.seekBrightness)
        seekVolume = findViewById(R.id.seekVolume)

        val btnOpenWifiSystem = findViewById<Button>(R.id.btnOpenWifiSystem)
        val btnClose = findViewById<Button>(R.id.btnCloseUserSettings)
        val bottomActionBar = findViewById<View>(R.id.bottomActionBarUserSettings)
        applyBottomSystemInset(bottomActionBar)
        btnOpenWifiSystem.setOnClickListener { openSystemWifiPanel() }
        btnClose.setOnClickListener { finish() }
        btnTabWifi.setOnClickListener { showWifiTab() }
        btnTabDisplaySound.setOnClickListener { showDisplaySoundTab() }
        switchWifiEnabled.setOnCheckedChangeListener { _, isChecked ->
            handleWifiToggle(isChecked)
        }

        setupBrightnessControl()
        setupVolumeControl()
        showWifiTab()
    }

    override fun onResume() {
        super.onResume()
        clearWindowBrightnessOverride()
        refreshBrightnessValue()
        refreshVolumeValue()
        updateConnectedWifiStatus()
        refreshWifiList()
    }

    override fun onStart() {
        super.onStart()
        registerWifiStateReceiverIfNeeded()
    }

    override fun onPause() {
        clearWindowBrightnessOverride()
        super.onPause()
    }

    override fun onStop() {
        unregisterWifiStateReceiverIfNeeded()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeNetworkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshWifiList() {
        if (!hasWifiPermissions()) {
            tvConnectedWifi.text = getString(R.string.user_settings_connected_wifi_permission_short)
            requestWifiPermissions()
            return
        }

        updateConnectedWifiStatus()
        syncWifiSwitch()
    }

    private fun registerWifiStateReceiverIfNeeded() {
        if (isWifiReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        registerReceiver(wifiStateReceiver, filter)
        isWifiReceiverRegistered = true
    }

    private fun unregisterWifiStateReceiverIfNeeded() {
        if (!isWifiReceiverRegistered) return
        try {
            unregisterReceiver(wifiStateReceiver)
        } catch (_: Exception) {
        }
        isWifiReceiverRegistered = false
    }

    private fun onWifiConnectRequested(network: WifiNetworkItem) {
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, getString(R.string.user_settings_enable_wifi_first), Toast.LENGTH_SHORT).show()
            return
        }

        if (requiresPassword(network.capabilities)) {
            promptForWifiPassword(network)
        } else {
            connectToWifi(network.ssid, null)
        }
    }

    private fun promptForWifiPassword(network: WifiNetworkItem) {
        val input = EditText(this).apply {
            hint = getString(R.string.user_settings_wifi_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 28, 48, 28)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.user_settings_enter_password, network.ssid))
            .setView(input)
            .setPositiveButton(getString(R.string.user_settings_connect)) { _, _ ->
                val password = input.text.toString().trim()
                if (password.isBlank()) {
                    Toast.makeText(this, getString(R.string.user_settings_password_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                connectToWifi(network.ssid, password)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun connectToWifi(ssid: String, password: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || dpm.isDeviceOwnerApp(packageName)) {
            connectWithLegacyApi(ssid, password)
        } else {
            connectWithSpecifier(ssid, password)
        }
    }

    @Suppress("DEPRECATION")
    private fun connectWithLegacyApi(ssid: String, password: String?) {
        try {
            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (password.isNullOrBlank()) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"$password\""
                }
            }

            val networkId = wifiManager.addNetwork(config)
            if (networkId == -1) {
                val existing = wifiManager.configuredNetworks?.firstOrNull { it.SSID == "\"$ssid\"" }
                if (existing != null) {
                    wifiManager.disconnect()
                    wifiManager.enableNetwork(existing.networkId, true)
                    wifiManager.reconnect()
                    Toast.makeText(this, getString(R.string.user_settings_connecting, ssid), Toast.LENGTH_SHORT).show()
                    return
                }
                Toast.makeText(this, getString(R.string.user_settings_connect_failed), Toast.LENGTH_SHORT).show()
                return
            }

            wifiManager.disconnect()
            wifiManager.enableNetwork(networkId, true)
            wifiManager.reconnect()
            Toast.makeText(this, getString(R.string.user_settings_connecting, ssid), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.user_settings_connect_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectWithSpecifier(ssid: String, password: String?) {
        try {
            activeNetworkCallback?.let {
                try {
                    connectivityManager.unregisterNetworkCallback(it)
                } catch (_: Exception) {
                }
            }

            val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (!password.isNullOrBlank()) {
                specifierBuilder.setWpa2Passphrase(password)
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread {
                        updateConnectedWifiStatus()
                        Toast.makeText(
                            this@UserSettingsActivity,
                            getString(R.string.user_settings_connected, ssid),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onUnavailable() {
                    runOnUiThread {
                        Toast.makeText(
                            this@UserSettingsActivity,
                            getString(R.string.user_settings_connect_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            activeNetworkCallback = callback
            connectivityManager.requestNetwork(request, callback)
            Toast.makeText(this, getString(R.string.user_settings_connecting, ssid), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.user_settings_connect_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBrightnessControl() {
        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBrightnessValue.text = getString(R.string.user_settings_brightness_value, progress)
                if (fromUser) {
                    saveLastBrightness(progress)
                    applyWindowBrightness(progress)
                    applySystemBrightness(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun applyWindowBrightness(value: Int) {
        val lp = window.attributes
        lp.screenBrightness = (value / 255f).coerceIn(0f, 1f)
        window.attributes = lp
    }

    private fun applySystemBrightness(value: Int) {
        val safeValue = value.coerceIn(1, 255)

        if (dpm.isDeviceOwnerApp(packageName)) {
            try {
                dpm.setSystemSetting(
                    adminComponent,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString()
                )
                dpm.setSystemSetting(
                    adminComponent,
                    Settings.System.SCREEN_BRIGHTNESS,
                    safeValue.toString()
                )
                return
            } catch (_: Exception) {
                // Fall through to legacy Settings.System path.
            }
        }

        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, getString(R.string.user_settings_need_write_settings), Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
            return
        }

        try {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, safeValue)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.user_settings_brightness_apply_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshBrightnessValue() {
        val current = resolvePreferredBrightness().coerceIn(0, 255)
        seekBrightness.progress = current
        tvBrightnessValue.text = getString(R.string.user_settings_brightness_value, seekBrightness.progress)
        applyWindowBrightness(current)
    }

    private fun clearWindowBrightnessOverride() {
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun resolvePreferredBrightness(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_LAST_BRIGHTNESS)) {
            return getSavedBrightness()
        }
        val fromSystem = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) {
            DEFAULT_BRIGHTNESS
        }.coerceIn(1, 255)
        saveLastBrightness(fromSystem)
        return fromSystem
    }

    private fun saveLastBrightness(value: Int) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_BRIGHTNESS, value.coerceIn(1, 255))
            .apply()
    }

    private fun getSavedBrightness(): Int {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_BRIGHTNESS, DEFAULT_BRIGHTNESS)
            .coerceIn(1, 255)
    }

    private fun seedSavedBrightnessIfMissing() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_LAST_BRIGHTNESS)) return
        val current = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) {
            DEFAULT_BRIGHTNESS
        }.coerceIn(1, 255)
        prefs.edit().putInt(KEY_LAST_BRIGHTNESS, current).apply()
    }

    private fun setupVolumeControl() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        seekVolume.max = max
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVolumeValue.text = getString(R.string.user_settings_volume_value, progress, max)
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun refreshVolumeValue() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        seekVolume.max = max
        seekVolume.progress = current
        tvVolumeValue.text = getString(R.string.user_settings_volume_value, current, max)
    }

    private fun requiresPassword(capabilities: String): Boolean {
        val upper = capabilities.uppercase()
        return upper.contains("WEP") || upper.contains("WPA")
    }

    private fun readableSecurityLabel(scanResult: ScanResult): String {
        return if (requiresPassword(scanResult.capabilities)) {
            getString(R.string.user_settings_secured_network)
        } else {
            getString(R.string.user_settings_open_network)
        }
    }

    private fun hasWifiPermissions(): Boolean {
        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNearby = ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            return hasFineLocation && hasNearby
        }
        return hasFineLocation
    }

    private fun requestWifiPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun showWifiTab() {
        layoutWifiTab.visibility = android.view.View.VISIBLE
        layoutDisplaySoundTab.visibility = android.view.View.GONE
        btnTabWifi.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.kiosk_accent))
        btnTabWifi.setTextColor(getColor(R.color.white))
        btnTabDisplaySound.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.admin_button_bg))
        btnTabDisplaySound.setTextColor(getColor(R.color.admin_button_text))
    }

    private fun showDisplaySoundTab() {
        layoutWifiTab.visibility = android.view.View.GONE
        layoutDisplaySoundTab.visibility = android.view.View.VISIBLE
        btnTabWifi.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.admin_button_bg))
        btnTabWifi.setTextColor(getColor(R.color.admin_button_text))
        btnTabDisplaySound.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.kiosk_accent))
        btnTabDisplaySound.setTextColor(getColor(R.color.white))
    }

    @Suppress("DEPRECATION")
    private fun updateConnectedWifiStatus() {
        if (!wifiManager.isWifiEnabled) {
            tvConnectedWifi.text = getString(R.string.user_settings_wifi_disabled)
            tvWifiStatus.text = getString(R.string.user_settings_wifi_disabled)
            return
        }

        if (!hasWifiPermissions()) {
            tvConnectedWifi.text = getString(R.string.user_settings_connected_wifi_permission_short)
            tvWifiStatus.text = getString(R.string.user_settings_connected_wifi_permission_short)
            return
        }

        val ssid = currentConnectedSsid()
        if (ssid.isBlank()) {
            tvConnectedWifi.text = getString(R.string.user_settings_connected_wifi_none_short)
            tvWifiStatus.text = getString(R.string.user_settings_connected_wifi_none_short)
        } else {
            tvConnectedWifi.text = ssid
            tvWifiStatus.text = getString(R.string.user_settings_connected_label)
        }
    }

    @Suppress("DEPRECATION")
    private fun getSavedSsids(): Set<String> {
        return try {
            wifiManager.configuredNetworks
                ?.mapNotNull { it.SSID?.removePrefix("\"")?.removeSuffix("\"") }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    @Suppress("DEPRECATION")
    private fun currentConnectedSsid(): String {
        val ssidRaw = wifiManager.connectionInfo?.ssid ?: WifiManager.UNKNOWN_SSID
        val ssid = ssidRaw.removePrefix("\"").removeSuffix("\"")
        return if (ssid == WifiManager.UNKNOWN_SSID) "" else ssid
    }

    private fun syncWifiSwitch() {
        switchWifiEnabled.setOnCheckedChangeListener(null)
        switchWifiEnabled.isChecked = wifiManager.isWifiEnabled
        switchWifiEnabled.setOnCheckedChangeListener { _, isChecked ->
            handleWifiToggle(isChecked)
        }
    }

    @Suppress("DEPRECATION")
    private fun handleWifiToggle(enable: Boolean) {
        if (wifiManager.isWifiEnabled == enable) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Toast.makeText(this, getString(R.string.user_settings_wifi_toggle_system_only), Toast.LENGTH_SHORT).show()
            switchWifiEnabled.isChecked = wifiManager.isWifiEnabled
            openSystemWifiPanel()
            return
        }
        try {
            wifiManager.isWifiEnabled = enable
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.user_settings_wifi_toggle_failed), Toast.LENGTH_SHORT).show()
        } finally {
            refreshWifiList()
        }
    }

    private fun openSystemWifiPanel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, getString(R.string.user_settings_open_system_wifi_failed), Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        try {
            systemPanelLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.user_settings_open_system_wifi_failed), Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, getString(R.string.user_settings_open_system_wifi_failed), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.user_settings_open_system_wifi_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun applyBottomSystemInset(view: View) {
        val basePaddingBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePaddingBottom + bottomInset)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
