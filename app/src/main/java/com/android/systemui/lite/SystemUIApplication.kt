package com.android.systemui.lite

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.systemui.lite.systemui.CoreStartable
import com.android.systemui.lite.systemui.core.NavigationBarCoreStartable
import com.android.systemui.lite.systemui.core.QSCoreStartable
import com.android.systemui.lite.systemui.core.ShadeCoreStartable
import com.android.systemui.lite.systemui.core.StatusBarCoreStartable
import com.android.systemui.lite.systemui.plugins.PluginManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SystemUIApplication : Application() {

    companion object {
        private const val TAG = "SystemUIApplication"

        @Volatile
        lateinit var instance: SystemUIApplication
            private set
    }

    val pluginManager = PluginManager()

    private val startables = linkedMapOf<Class<*>, CoreStartable>()

    @Volatile
    private var servicesStarted = false

    val mainHandler = Handler(Looper.getMainLooper())

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

        pluginManager.registerLogListener { message ->
            logSystemEvent("PluginManager", message)
        }
    }

    fun startServicesIfNeeded() {
        if (servicesStarted) {
            Log.d(TAG, "startServicesIfNeeded already called, skipping")
            return
        }
        servicesStarted = true

        logSystemEvent(TAG, "startServicesIfNeeded called from SystemUIService")
        bootstrapComponents()
    }

    private fun bootstrapComponents() {
        logSystemEvent(TAG, "SystemUI bootstrap: Initializing core services...")

        // Phase 1: Create ShadeCoreStartable (needed by StatusBarCoreStartable for ShadeController)
        val shade = ShadeCoreStartable(this)
        registerStartable(ShadeCoreStartable::class.java, shade)

        // Phase 2: StatusBarCoreStartable (status bar window + system state)
        val statusBar = StatusBarCoreStartable(this, shade)
        registerStartable(StatusBarCoreStartable::class.java, statusBar)

        // Phase 3: NavigationBarCoreStartable
        registerStartable(NavigationBarCoreStartable::class.java, NavigationBarCoreStartable(this))

        // Phase 4: QSCoreStartable
        registerStartable(QSCoreStartable::class.java, QSCoreStartable(this))

        // Start all in sorted order (matching AOSP's deterministic startup)
        val sorted = startables.toSortedMap(compareBy { it.name })
        sorted.forEach { (cls, startable) ->
            logSystemEvent(TAG, "Starting: ${cls.simpleName}")
            try {
                startable.start()
                logSystemEvent(TAG, "${cls.simpleName} started successfully")
            } catch (e: Exception) {
                logSystemEvent(TAG, "ERROR starting ${cls.simpleName}: ${e.message}")
                Log.e(TAG, "Error starting ${cls.simpleName}", e)
            }
        }

        // Post-init tasks (mirrors AOSP InitController)
        mainHandler.post {
            logSystemEvent(TAG, "Running post-init tasks...")
            sorted.forEach { (cls, startable) ->
                startable.onBootCompleted()
            }
        }

        logSystemEvent(TAG, "Bootstrap completed. All core services started.")
    }

    private fun registerStartable(cls: Class<*>, startable: CoreStartable) {
        startables[cls] = startable
    }

    fun stopServicesIfNeeded() {
        if (!servicesStarted) return

        logSystemEvent(TAG, "Stopping all SystemUI services...")

        // Stop in reverse order
        startables.entries.reversed().forEach { (cls, startable) ->
            try {
                startable.stop()
                logSystemEvent(TAG, "${cls.simpleName} stopped")
            } catch (e: Exception) {
                logSystemEvent(TAG, "ERROR stopping ${cls.simpleName}: ${e.message}")
            }
        }

        startables.clear()
        servicesStarted = false
        logSystemEvent(TAG, "All SystemUI services stopped.")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : CoreStartable> getStartable(cls: Class<T>): T? {
        return startables[cls] as? T
    }

    fun getStartableCount(): Int = startables.size

    fun logSystemEvent(tag: String, message: String) {
        Log.d(tag, message)
        val currentLogs = _systemLogs.value.toMutableList()
        currentLogs.add(LogEntry(tag = tag, message = message))
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
