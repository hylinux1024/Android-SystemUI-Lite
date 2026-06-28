package com.android.systemui.lite.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PluginManager {
    private val _plugins = MutableStateFlow<List<SystemUIPlugin>>(
        listOf(
            DynamicIslandPlugin(),
            TrafficIndicatorPlugin(),
            CyberClockPlugin(),
            ResourceMonitorPlugin()
        )
    )
    val plugins: StateFlow<List<SystemUIPlugin>> = _plugins.asStateFlow()

    private val logCallback = mutableListOf<(String) -> Unit>()

    fun registerLogListener(listener: (String) -> Unit) {
        logCallback.add(listener)
    }

    private fun logEvent(message: String) {
        logCallback.forEach { it(message) }
    }

    fun togglePlugin(id: String): Boolean {
        val currentList = _plugins.value
        val target = currentList.find { it.id == id } ?: return false
        val newState = !target.isEnabled
        
        target.isEnabled = newState
        if (newState) {
            target.onPluginEnabled()
            logEvent("[PluginManager] Plugin Loaded: ${target.name} (${target.id})")
        } else {
            target.onPluginDisabled()
            logEvent("[PluginManager] Plugin Unloaded: ${target.name} (${target.id})")
        }
        
        // Trigger flow update
        _plugins.value = currentList.toList()
        return newState
    }
}
