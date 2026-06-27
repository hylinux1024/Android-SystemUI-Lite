package com.android.systemui.lite.systemui.plugins

interface SystemUIPlugin {
    val id: String
    val name: String
    val description: String
    val category: PluginCategory
    var isEnabled: Boolean
    
    fun onPluginEnabled()
    fun onPluginDisabled()
}

enum class PluginCategory {
    STATUS_BAR,
    LOCK_SCREEN,
    QUICK_SETTINGS
}

// Built-in plugin concrete implementations (simulated inside our sandbox)
class DynamicIslandPlugin : SystemUIPlugin {
    override val id = "dynamic_island"
    override val name = "Dynamic Island Cutout"
    override val description = "Adds an interactive notch pill at the top of the screen that expands for notification alerts and active media playback."
    override val category = PluginCategory.STATUS_BAR
    override var isEnabled = false

    override fun onPluginEnabled() {
        // Log event
    }

    override fun onPluginDisabled() {
        // Log event
    }
}

class TrafficIndicatorPlugin : SystemUIPlugin {
    override val id = "traffic_indicator"
    override val name = "Network Traffic Speed"
    override val description = "Displays real-time network download/upload speed indicator directly in the Status Bar status area."
    override val category = PluginCategory.STATUS_BAR
    override var isEnabled = false

    override fun onPluginEnabled() {}
    override fun onPluginDisabled() {}
}

class CyberClockPlugin : SystemUIPlugin {
    override val id = "cyber_clock"
    override val name = "Neon Cyberpunk Clock"
    override val description = "Replaces the Lockscreen clock with a futuristic glowing cyberpunk design featuring dual neon colors and a system health widget."
    override val category = PluginCategory.LOCK_SCREEN
    override var isEnabled = false

    override fun onPluginEnabled() {}
    override fun onPluginDisabled() {}
}

class ResourceMonitorPlugin : SystemUIPlugin {
    override val id = "resource_monitor"
    override val name = "RAM & CPU Live Monitor"
    override val description = "Injects a live system resource consumption card (CPU/RAM telemetry) into the Quick Settings shade panel."
    override val category = PluginCategory.QUICK_SETTINGS
    override var isEnabled = false

    override fun onPluginEnabled() {}
    override fun onPluginDisabled() {}
}
