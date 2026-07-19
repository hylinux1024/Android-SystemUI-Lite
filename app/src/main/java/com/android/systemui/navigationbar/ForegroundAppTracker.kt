package com.android.systemui.navigationbar

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers the question "is a user app (not the launcher / system UI) currently
 * in the foreground?" — the gate for the edge-swipe-back gesture.
 *
 * Requirement: side-swipe-to-go-back must only fire when there is an opened
 * app. On the launcher / lock screen / home the gesture is a no-op so it does
 * not interfere with launcher paging or the bouncer.
 *
 * Implementation note: this process runs as the system uid (sharedUserId =
 * android.uid.system, platform-signed), so
 * [ActivityManager.getRunningAppProcesses] returns the full process list rather
 * than the caller-only slice that a normal third-party app would see. We filter
 * for [ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND] processes
 * and exclude the launcher(s) and SystemUI itself.
 */
@Singleton
class ForegroundAppTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ForegroundAppTracker"
        /** Re-resolve the launcher package set at most this often. */
        private const val LAUNCHER_REFRESH_INTERVAL_MS = 30_000L
    }

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val packageManager = context.packageManager

    /** Cached set of packages that resolve ACTION_MAIN + CATEGORY_HOME. */
    private var launcherPackages: Set<String> = emptySet()
    private var lastRefreshMs = 0L

    /**
     * True when a non-launcher, non-system user app holds the foreground.
     * Cheap enough to call on every ACTION_DOWN.
     */
    fun isAppInForeground(): Boolean {
        val foreground = foregroundProcesses()
        if (foreground.isEmpty()) return false

        val launchers = launcherPackageSet()
        val myPackage = context.packageName

        return foreground.any { pkg ->
            pkg !in launchers && pkg != myPackage && !pkg.startsWith("android")
        }
    }

    /** Package names of all processes currently at FOREGROUND importance. */
    private fun foregroundProcesses(): List<String> {
        val processes = try {
            activityManager.runningAppProcesses
        } catch (e: SecurityException) {
            Log.w(TAG, "getRunningAppProcesses denied", e)
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "getRunningAppProcesses failed", e)
            return emptyList()
        }
        if (processes == null) return emptyList()

        return processes
            .filter { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
            .mapNotNull { it.processName?.takeIf { name -> name.isNotEmpty() } }
            .distinct()
    }

    /** Resolve the set of launcher (home) packages, cached for a few seconds. */
    private fun launcherPackageSet(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - lastRefreshMs > LAUNCHER_REFRESH_INTERVAL_MS || launcherPackages.isEmpty()) {
            launcherPackages = resolveLaunchers()
            lastRefreshMs = now
        }
        return launcherPackages
    }

    private fun resolveLaunchers(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolvers = try {
            packageManager.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            emptyList()
        }
        return resolvers.mapNotNull { it.activityInfo?.packageName }.toSet()
            .ifEmpty { setOf("com.android.launcher3", "com.google.android.apps.nexuslauncher") }
    }
}
