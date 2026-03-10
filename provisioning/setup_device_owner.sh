#!/bin/bash
# =============================================================
# Aican Kiosk Launcher — Device Owner Provisioning Script
# =============================================================
#
# This script sets up the Aican Kiosk Launcher as the Device Owner
# on a connected Android device via ADB.
#
# PREREQUISITES:
#   1. ADB installed and in PATH
#   2. Device connected via USB with USB debugging enabled
#   3. Device must be freshly factory-reset (no Google account added)
#   4. APK must be built first: ./gradlew assembleDebug
#
# USAGE:
#   chmod +x setup_device_owner.sh
#   ./setup_device_owner.sh
#
# =============================================================

set -e

PACKAGE_NAME="com.aican.aicankiosklauncher"
ADMIN_RECEIVER="${PACKAGE_NAME}/.MyDeviceAdminReceiver"
APK_PATH="../app/build/outputs/apk/debug/app-debug.apk"

echo "=========================================="
echo "  Aican Kiosk Launcher — Device Setup"
echo "=========================================="
echo ""

# Step 1: Check ADB
echo "[1/4] Checking ADB connection..."
DEVICE_COUNT=$(adb devices | grep -c 'device$' || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "ERROR: No device found. Connect a device and enable USB debugging."
    exit 1
fi
echo "  ✅ Device connected"
echo ""

# Step 2: Install APK
echo "[2/4] Installing APK..."
if [ -f "$APK_PATH" ]; then
    adb install -r "$APK_PATH"
    echo "  ✅ APK installed"
else
    echo "  ⚠️  APK not found at: $APK_PATH"
    echo "  Checking if app is already installed..."
    if adb shell pm list packages | grep -q "$PACKAGE_NAME"; then
        echo "  ✅ App already installed on device"
    else
        echo "  ERROR: Build the APK first: ./gradlew assembleDebug"
        exit 1
    fi
fi
echo ""

# Step 3: Set Device Owner
echo "[3/4] Setting Device Owner..."
echo "  Running: adb shell dpm set-device-owner $ADMIN_RECEIVER"
adb shell dpm set-device-owner "$ADMIN_RECEIVER"
echo "  ✅ Device Owner set"
echo ""

# Step 4: Verify
echo "[4/4] Verifying..."
OWNER=$(adb shell dpm list-owners 2>/dev/null || echo "")
if echo "$OWNER" | grep -q "$PACKAGE_NAME"; then
    echo "  ✅ VERIFIED: $PACKAGE_NAME is Device Owner"
else
    echo "  ⚠️  Could not verify. Check manually with:"
    echo "      adb shell dpm list-owners"
fi

echo ""
echo "=========================================="
echo "  ✅ SETUP COMPLETE"
echo ""
echo "  The kiosk will activate on next app launch."
echo "  Secret exit: Tap logo 7 times → password: aican2024"
echo "=========================================="
