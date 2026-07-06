package com.android.systemui

import android.content.res.Configuration

/**
 * Base interface for core SystemUI components.
 * Mirrors com.android.systemui.CoreStartable in AOSP.
 *
 * CoreStartable provides lifecycle management for components that need to
 * start/stop with the system. The CoreStartableComponent manages all instances.
 */
interface CoreStartable {

    /**
     * Called when the component should start.
     * Initialize resources, register listeners, etc.
     */
    fun start()

    /**
     * Called when the component should stop.
     * Release resources, unregister listeners, etc.
     */
    fun stop() {}

    /**
     * Called when the system has completed boot.
     * Perform deferred initialization here.
     */
    fun onBootCompleted() {}

    /**
     * Called when the user is switched.
     */
    fun onUserSwitch(newUserId: Int) {}

    /**
     * Called when user switch is complete.
     */
    fun onUserSwitchComplete(userId: Int) {}

    /**
     * Called when the configuration changes.
     */
    fun onConfigChanged(newConfig: Configuration) {}
}
