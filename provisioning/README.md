# Aican Kiosk Launcher — Provisioning Guide

## Quick Start (ADB Method)

```bash
# 1. Build the APK
cd /Users/vanand/Desktop/VishalAP/AicanKioskLauncher
./gradlew assembleDebug

# 2. Factory reset the tablet (Settings → System → Reset)

# 3. Skip Google account setup during initial setup wizard

# 4. Enable USB debugging (Settings → Developer Options → USB Debugging)

# 5. Connect tablet via USB and run:
cd provisioning
chmod +x setup_device_owner.sh
./setup_device_owner.sh
```

## Manual ADB Commands

If you prefer to run commands manually:

```bash
# Install APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Set as Device Owner (MUST be on fresh device, no Google account)
adb shell dpm set-device-owner com.aican.aicankiosklauncher/.MyDeviceAdminReceiver

# Verify
adb shell dpm list-owners
```

## QR Code Provisioning (Alternative)

For bulk deployment, generate a QR code with this JSON:

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
      "com.aican.aicankiosklauncher/.MyDeviceAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION":
      "https://your-server.com/aican-kiosk.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM":
      "<your_apk_signature_hash>",
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": false,
  "android.app.extra.PROVISIONING_WIFI_SSID": "YourWifi",
  "android.app.extra.PROVISIONING_WIFI_PASSWORD": "your_password"
}
```

Then on a freshly factory-reset tablet:
1. On the welcome screen, tap **6 times** on a blank area
2. Scan the QR code with the device camera
3. The app downloads, installs, and becomes Device Owner automatically

## Removing Device Owner

To remove kiosk mode and restore the device:

```bash
adb shell dpm remove-active-admin com.aican.aicankiosklauncher/.MyDeviceAdminReceiver
```

Or from within the app: Tap logo 7 times → enter password `aican2024` → Clear Restrictions.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `Not allowed to set device owner` | Remove all Google accounts first, or factory reset |
| `Already has a device owner` | Run `adb shell dpm remove-active-admin ...` first |
| Kiosk doesn't start after reboot | Ensure `RECEIVE_BOOT_COMPLETED` permission is in manifest |
| Can't exit kiosk | Tap app logo 7 times → enter password `aican2024` |
