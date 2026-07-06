#!/bin/bash
# SystemUI-Lite2 Installation Script
# Pushes the built APK to the device's SystemUI location
#
# Requirements:
#   - Rooted device with adb root access
#   - Device must support adb remount (system partition writable)
#   - Platform key must match the device's signing key
#
# Usage:
#   ./install.sh          # Install APK to device
#   ./install.sh clean    # Clean build before install
#   ./install.sh restart  # Restart SystemUI on device
#   ./install.sh logs     # Show SystemUI logs
#   ./install.sh status   # Check SystemUI status

set -e

APK_PATH="app/build/outputs/apk/debug/SystemUI.apk"
DEVICE_PATH="/system_ext/priv-app/SystemUI/SystemUI.apk"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_header() {
    echo ""
    echo -e "${BLUE}=========================================${NC}"
    echo -e "${BLUE} SystemUI-Lite2 Installer${NC}"
    echo -e "${BLUE}=========================================${NC}"
    echo ""
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

check_adb() {
    if ! command -v adb &> /dev/null; then
        print_error "adb is not installed or not in PATH"
        exit 1
    fi
}

check_device() {
    local device_count=$(adb devices | grep -c "device$")
    if [ "$device_count" -eq 0 ]; then
        print_error "No device connected"
        echo "Connect a device via USB or adb connect <ip>"
        exit 1
    elif [ "$device_count" -gt 1 ]; then
        print_warning "Multiple devices connected. Using first device."
    fi
}

build_apk() {
    print_info "Building APK..."
    if [ "$1" = "clean" ]; then
        ./gradlew clean assembleDebug
    else
        ./gradlew assembleDebug
    fi

    if [ ! -f "$APK_PATH" ]; then
        print_error "APK not found at $APK_PATH"
        echo "Build may have failed. Check the output above."
        exit 1
    fi

    print_success "APK built successfully"
}

install_apk() {
    print_info "Rooting and remounting device..."
    adb root
    sleep 2

    print_info "Remounting system partition..."
    adb remount

    print_info "Pushing APK to device..."
    adb push "$APK_PATH" "$DEVICE_PATH"

    print_success "APK installed to $DEVICE_PATH"
}

restart_systemui() {
    print_info "Restarting SystemUI..."

    # Force stop the existing SystemUI process
    adb shell "am force-stop com.android.systemui"
    adb shell stop;
    adb shell start
    sleep 1

    # Alternative: restart the framework (more thorough)
    # adb shell stop && adb shell start

    print_success "SystemUI restarted"
}

show_logs() {
    print_info "Showing SystemUI logs (Ctrl+C to stop)..."
    adb logcat -s SystemUIService:* SystemUIApplication:* StatusBarManager:* QSTileManager:* SystemNotificationListener:*
}

show_status() {
    print_info "Checking SystemUI status..."

    echo ""
    echo "Running processes:"
    adb shell "ps -A | grep systemui" || echo "  No SystemUI process found"

    echo ""
    echo "Window status:"
    adb shell "dumpsys window | grep -A 2 StatusBar" 2>/dev/null | head -10 || echo "  Could not get window status"

    echo ""
    echo "Notification listener status:"
    adb shell "settings get secure enabled_notification_listeners" 2>/dev/null | grep -o "com.android.systemui" || echo "  NotificationListenerService not enabled"

    echo ""
    echo "Installed APK:"
    adb shell "ls -la $DEVICE_PATH" 2>/dev/null || echo "  APK not found at $DEVICE_PATH"
}

print_usage() {
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  (none)    Build and install APK to device"
    echo "  clean     Clean build and install APK"
    echo "  restart   Restart SystemUI on device"
    echo "  logs      Show SystemUI logs in real-time"
    echo "  status    Check SystemUI status on device"
    echo "  help      Show this help message"
}

# Main script
print_header

case "${1:-}" in
    clean)
        check_adb
        check_device
        build_apk "clean"
        install_apk
        restart_systemui
        ;;
    restart)
        check_adb
        check_device
        restart_systemui
        ;;
    logs)
        check_adb
        check_device
        show_logs
        ;;
    status)
        check_adb
        check_device
        show_status
        ;;
    help|-h|--help)
        print_usage
        ;;
    "")
        check_adb
        check_device
        build_apk
        install_apk
        restart_systemui
        ;;
    *)
        print_error "Unknown command: $1"
        print_usage
        exit 1
        ;;
esac

echo ""
print_success "Done!"
