#!/bin/bash
# Deploy SystemUI-Lite to device
# Usage: ./deploy.sh [device_ip]

set -e

DEVICE_IP="${1:-}"
ADB="adb"
if [ -n "$DEVICE_IP" ]; then
    ADB="adb -s $DEVICE_IP:5555"
fi

echo "=== SystemUI-Lite Deploy Script ==="
echo ""

# Check device connection
echo "Checking device connection..."
$ADB devices | grep -q "device$" || { echo "ERROR: No device connected. Run: adb connect <ip>"; exit 1; }

# Root and remount
echo "Enabling root access..."
$ADB root
sleep 2

echo "Remounting /system partition as read-write..."
$ADB remount
sleep 2

# Check if remount succeeded, try disable-verity if not
if ! $ADB shell "mount -o rw,remount /system_ext" 2>/dev/null; then
    echo "Trying to disable verity..."
    $ADB disable-verity
    $ADB reboot
    echo "Waiting for device to reboot..."
    sleep 30
    $ADB wait-for-device
    $ADB root
    sleep 2
    $ADB remount
    sleep 2
fi

# Remove existing SystemUI if present
echo "Removing existing SystemUI..."
$ADB shell "rm -rf /system_ext/priv-app/SystemUI" 2>/dev/null || true
$ADB shell "rm -rf /system_ext/priv-app/SystemUI.apk" 2>/dev/null || true

# Create directory
echo "Creating target directory..."
$ADB shell "mkdir -p /system_ext/priv-app/SystemUI"

# Push APK
echo "Pushing SystemUI.apk..."
$ADB push app/build/outputs/apk/debug/SystemUI.apk /system_ext/priv-app/SystemUI/SystemUI.apk

# Push permissions allowlist
echo "Pushing privapp-permissions allowlist..."
$ADB push deploy/privapp-permissions-systemui-lite.xml /system_ext/etc/permissions/

# Fix permissions
echo "Fixing permissions..."
$ADB shell "chmod 644 /system_ext/priv-app/SystemUI/SystemUI.apk"
$ADB shell "chmod 644 /system_ext/etc/permissions/privapp-permissions-systemui-lite.xml"

# Sync and reboot
echo "Syncing..."
$ADB shell "sync"

echo ""
echo "=== Deploy complete! ==="
echo "Rebooting device..."
$ADB reboot
echo "Done. Wait for device to boot and check logs with:"
echo "  adb logcat -s SystemUI-Lite:*"
