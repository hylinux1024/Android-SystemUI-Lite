package com.android.systemui.lite.systemui.components

import com.android.systemui.lite.systemui.SystemUIComponent

class StatusBarController(private val onLog: (String) -> Unit) : SystemUIComponent {
    override val name = "StatusBarController"
    
    override fun start() {
        onLog("[StatusBarController] Initializing Status Bar icon services...")
        onLog("[StatusBarController] BatteryMonitor & NetworkController registered.")
        onLog("[StatusBarController] PhoneStatusBarView inflated and attached to WindowManager.")
    }

    override fun stop() {
        onLog("[StatusBarController] Status Bar services stopped.")
    }
}

class NavigationBarController(private val onLog: (String) -> Unit) : SystemUIComponent {
    override val name = "NavigationBarController"

    override fun start() {
        onLog("[NavigationBarController] Starting Navigation Bar services...")
        onLog("[NavigationBarController] GestureNavigationController listening for screen edge swipes.")
        onLog("[NavigationBarController] NavigationBarView attached successfully.")
    }

    override fun stop() {
        onLog("[NavigationBarController] Navigation Bar services stopped.")
    }
}

class KeyguardViewController(private val onLog: (String) -> Unit) : SystemUIComponent {
    override val name = "KeyguardViewController"

    override fun start() {
        onLog("[KeyguardViewController] Starting Keyguard (Lockscreen) security services...")
        onLog("[KeyguardViewController] BiometricPromptService and PIN input controllers bound.")
        onLog("[KeyguardViewController] KeyguardViewManager listening for power button events.")
    }

    override fun stop() {
        onLog("[KeyguardViewController] Keyguard services stopped.")
    }
}

class NotificationPresenter(private val onLog: (String) -> Unit) : SystemUIComponent {
    override val name = "NotificationPresenter"

    override fun start() {
        onLog("[NotificationPresenter] Binding NotificationListenerService...")
        onLog("[NotificationPresenter] NotificationMediaTemplateHelper initialized for music control binding.")
        onLog("[NotificationPresenter] Grouping and bubble expansion pipelines active.")
    }

    override fun stop() {
        onLog("[NotificationPresenter] Notification services stopped.")
    }
}
