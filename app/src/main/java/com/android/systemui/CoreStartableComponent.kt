package com.android.systemui

import android.content.res.Configuration
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages all CoreStartable components.
 * Mirrors CoreStartableRepository and ComponentHelper in AOSP.
 *
 * Discovers and manages lifecycle of all CoreStartable instances.
 */
@Singleton
class CoreStartableComponent @Inject constructor() {

    companion object {
        private const val TAG = "CoreStartableComponent"
    }

    private val components = mutableListOf<CoreStartable>()
    private var isStarted = false

    /**
     * Register a CoreStartable component.
     * Called during initialization to collect all components.
     */
    fun register(component: CoreStartable) {
        components.add(component)
        Log.d(TAG, "Registered: ${component.javaClass.simpleName}")
    }

    /**
     * Start all registered components.
     * Mirrors SystemUIApplication.startServicesIfNeeded()
     */
    fun start() {
        if (isStarted) {
            Log.w(TAG, "Already started, skipping")
            return
        }

        Log.i(TAG, "Starting ${components.size} CoreStartable components")
        components.forEach { component ->
            try {
                Log.d(TAG, "Starting: ${component.javaClass.simpleName}")
                component.start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start: ${component.javaClass.simpleName}", e)
            }
        }
        isStarted = true
    }

    /**
     * Stop all registered components.
     */
    fun stop() {
        Log.i(TAG, "Stopping ${components.size} CoreStartable components")
        components.forEach { component ->
            try {
                component.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop: ${component.javaClass.simpleName}", e)
            }
        }
        isStarted = false
    }

    /**
     * Notify all components that boot is complete.
     */
    fun onBootCompleted() {
        components.forEach { component ->
            try {
                component.onBootCompleted()
            } catch (e: Exception) {
                Log.e(TAG, "onBootCompleted failed: ${component.javaClass.simpleName}", e)
            }
        }
    }

    /**
     * Notify all components of user switch.
     */
    fun onUserSwitch(newUserId: Int) {
        components.forEach { component ->
            try {
                component.onUserSwitch(newUserId)
            } catch (e: Exception) {
                Log.e(TAG, "onUserSwitch failed: ${component.javaClass.simpleName}", e)
            }
        }
    }

    /**
     * Notify all components of configuration change.
     */
    fun onConfigChanged(newConfig: Configuration) {
        components.forEach { component ->
            try {
                component.onConfigChanged(newConfig)
            } catch (e: Exception) {
                Log.e(TAG, "onConfigChanged failed: ${component.javaClass.simpleName}", e)
            }
        }
    }

    /**
     * Get all registered components.
     */
    fun getComponents(): List<CoreStartable> = components.toList()
}
