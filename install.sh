adb root && adb remount
adb push app/build/outputs/apk/debug/SystemUI.apk /system_ext/priv-app/SystemUI/SystemUI.apk
adb shell stop && adb shell start