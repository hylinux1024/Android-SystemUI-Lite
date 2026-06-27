package com.android.systemui.lite

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.systemui.lite.systemui.SystemUIComponent
import com.android.systemui.lite.systemui.components.KeyguardViewController
import com.android.systemui.lite.systemui.components.NavigationBarController
import com.android.systemui.lite.systemui.components.NotificationPresenter
import com.android.systemui.lite.systemui.components.StatusBarController
import com.android.systemui.lite.systemui.core.QSCoreStartable
import com.android.systemui.lite.systemui.core.StatusBarCoreStartable
import com.android.systemui.lite.systemui.plugins.PluginManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SystemUIApplication - The main Application class for SystemUI.
 *
 * Mirrors AOSP SystemUIApplication:
 * - Initializes the DI/component graph (simplified here without Dagger)
 * - Provides startServicesIfNeeded() which is called by SystemUIService
 * - Manages CoreStartable lifecycle
 */
class SystemUIApplication : Application() {

    companion object {
        private const val TAG = "SystemUIApplication"

        @Volatile
        lateinit var instance: SystemUIApplication
            private set
    }

    val pluginManager = PluginManager()

    // Core loaded components (analogous to AOSP's CoreStartable map)
    private val _components = mutableListOf<SystemUIComponent>()
    val components: List<SystemUIComponent> get() = _components

    // Real status bar core startable
    private var statusBarCoreStartable: StatusBarCoreStartable? = null

    // QS tiles core startable
    private var qsCoreStartable: QSCoreStartable? = null

    // Whether services have been started
    @Volatile
    private var servicesStarted = false

    // Main thread handler for posting callbacks
    val mainHandler = Handler(Looper.getMainLooper())

    // Live terminal logs of the SystemUI process
    private val _systemLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val systemLogs: StateFlow<List<LogEntry>> = _systemLogs.asStateFlow()

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val tag: String,
        val message: String
    )

    override fun onCreate() {
        super.onCreate()
        instance = this

        logSystemEvent(TAG, "========================================")
        logSystemEvent(TAG, "SystemUIApplication onCreate initialized.")
        logSystemEvent(TAG, "Process: com.android.systemui")
        logSystemEvent(TAG, "UID: android.uid.systemui")
        logSystemEvent(TAG, "========================================")

        // Bind plugin logging to our global system logger
        pluginManager.registerLogListener { message ->
            logSystemEvent("PluginManager", message)
        }
    }

    /**
     * Called by SystemUIService when it starts.
     * Mirrors AOSP's startServicesIfNeeded() pattern.
     *
     * This method is idempotent - calling it multiple times has no effect.
     */
    fun startServicesIfNeeded() {
        if (servicesStarted) {
            Log.d(TAG, "startServicesIfNeeded already called, skipping")
            return
        }
        servicesStarted = true

        logSystemEvent(TAG, "startServicesIfNeeded called from SystemUIService")
        bootstrapComponents()
    }

    /**
     * Bootstrap all CoreStartable components.
     * In AOSP, these are provided by Dagger via SysUIComponent.getStartables().
     * Here we manually create and start each component.
     *
     * Components are started in deterministic order (sorted by class name),
     * matching AOSP's behavior.
     */
    private fun bootstrapComponents() {
        logSystemEvent(TAG, "SystemUI bootstrap: Initializing core services...")

        val logger: (String) -> Unit = { msg ->
            val tag = when {
                msg.contains("StatusBar") -> "StatusBarController"
                msg.contains("Navigation") -> "NavigationBarController"
                msg.contains("Keyguard") -> "KeyguardViewController"
                msg.contains("Notification") -> "NotificationPresenter"
                else -> "SystemUI"
            }
            logSystemEvent(tag, msg)
        }

        // Phase 2: Start real status bar core startable
        logSystemEvent(TAG, "Starting StatusBarCoreStartable (real system integration)...")
        try {
            statusBarCoreStartable = StatusBarCoreStartable(this)
            statusBarCoreStartable?.start()
            logSystemEvent(TAG, "StatusBarCoreStartable started successfully")
        } catch (e: Exception) {
            logSystemEvent(TAG, "ERROR starting StatusBarCoreStartable: ${e.message}")
            Log.e(TAG, "Error starting StatusBarCoreStartable", e)
        }

        // Start the overlay service to create the status bar window
        logSystemEvent(TAG, "Starting SystemUIOverlayService...")
        try {
            com.android.systemui.lite.systemui.service.SystemUIOverlayService.startService(this)
            logSystemEvent(TAG, "SystemUIOverlayService started successfully")
        } catch (e: Exception) {
            logSystemEvent(TAG, "ERROR starting SystemUIOverlayService: ${e.message}")
            Log.e(TAG, "Error starting SystemUIOverlayService", e)
        }

        // Phase 4: Start QS tiles core startable
        logSystemEvent(TAG, "Starting QSCoreStartable (real QS tile system)...")
        try {
            qsCoreStartable = QSCoreStartable(this)
            qsCoreStartable?.start()
            logSystemEvent(TAG, "QSCoreStartable started successfully")
        } catch (e: Exception) {
            logSystemEvent(TAG, "ERROR starting QSCoreStartable: ${e.message}")
            Log.e(TAG, "Error starting QSCoreStartable", e)
        }

        // Legacy simulated components (will be gradually replaced)
        val statusBar = StatusBarController(logger)
        val navBar = NavigationBarController(logger)
        val keyguard = KeyguardViewController(logger)
        val notification = NotificationPresenter(logger)

        // Sort by class name (matching AOSP's deterministic startup order)
        val startables = listOf(statusBar, navBar, keyguard, notification).sortedBy { it.name }

        startables.forEach { component ->
            logSystemEvent(TAG, "Starting component: ${component.name}")
            try {
                _components.add(component)
                component.start()
                logSystemEvent(TAG, "Component ${component.name} started successfully")
            } catch (e: Exception) {
                logSystemEvent(TAG, "ERROR starting component ${component.name}: ${e.message}")
                Log.e(TAG, "Error starting component ${component.name}", e)
            }
        }

        // Post-init tasks (mirrors AOSP InitController.executePostInitTasks())
        mainHandler.post {
            logSystemEvent(TAG, "Running post-init tasks...")
            onBootCompleted()
        }

        logSystemEvent(TAG, "Bootstrap completed. All core services started.")
    }

    /**
     * Called after all components have been started.
     * Mirrors AOSP CoreStartable.onBootCompleted().
     */
    private fun onBootCompleted() {
        statusBarCoreStartable?.onBootCompleted()
        qsCoreStartable?.onBootCompleted()

        _components.forEach { component ->
            logSystemEvent(TAG, "onBootCompleted for ${component.name}")
        }
        logSystemEvent(TAG, "SystemUI boot completed.")
    }

    /**
     * Stop all components. Called when the SystemUI process is shutting down.
     */
    fun stopServicesIfNeeded() {
        if (!servicesStarted) return

        logSystemEvent(TAG, "Stopping all SystemUI services...")

        // Stop real status bar
        statusBarCoreStartable?.stop()
        statusBarCoreStartable = null

        // Stop QS tiles
        qsCoreStartable?.stop()
        qsCoreStartable = null

        // Stop legacy components in reverse order
        _components.asReversed().forEach { component ->
            try {
                component.stop()
                logSystemEvent(TAG, "Component ${component.name} stopped")
            } catch (e: Exception) {
                logSystemEvent(TAG, "ERROR stopping component ${component.name}: ${e.message}")
            }
        }

        _components.clear()
        servicesStarted = false
        logSystemEvent(TAG, "All SystemUI services stopped.")
    }

    /**
     * Get the StatusBarCoreStartable instance.
     */
    fun getStatusBarCoreStartable(): StatusBarCoreStartable? = statusBarCoreStartable

    /**
     * Get the QSCoreStartable instance.
     */
    fun getQSCoreStartable(): QSCoreStartable? = qsCoreStartable

    fun logSystemEvent(tag: String, message: String) {
        Log.d(tag, message)
        val currentLogs = _systemLogs.value.toMutableList()
        currentLogs.add(LogEntry(tag = tag, message = message))
        // Maintain a maximum of 500 log entries
        if (currentLogs.size > 500) {
            currentLogs.removeAt(0)
        }
        _systemLogs.value = currentLogs
    }

    fun clearLogs() {
        _systemLogs.value = emptyList()
        logSystemEvent(TAG, "Developer logs cleared by user.")
    }
}
