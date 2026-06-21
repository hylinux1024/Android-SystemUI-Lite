package com.android.systemui.utils

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log

object SettingsHelper {

    private const val TAG = "SettingsHelper"

    fun getSecureInt(resolver: ContentResolver, name: String, defaultValue: Int = 0): Int {
        return try {
            Settings.Secure.getInt(resolver, name)
        } catch (e: Settings.SettingNotFoundException) {
            Log.w(TAG, "Setting not found: $name, using default: $defaultValue")
            defaultValue
        }
    }

    fun putSecureInt(resolver: ContentResolver, name: String, value: Int): Boolean {
        return try {
            Settings.Secure.putInt(resolver, name, value)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write setting: $name", e)
            false
        }
    }

    fun getSecureString(resolver: ContentResolver, name: String): String? {
        return try {
            Settings.Secure.getString(resolver, name)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read setting: $name", e)
            null
        }
    }

    fun putSecureString(resolver: ContentResolver, name: String, value: String): Boolean {
        return try {
            Settings.Secure.putString(resolver, name, value) != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write setting: $name", e)
            false
        }
    }

    fun getGlobalInt(resolver: ContentResolver, name: String, defaultValue: Int = 0): Int {
        return try {
            Settings.Global.getInt(resolver, name)
        } catch (e: Settings.SettingNotFoundException) {
            Log.w(TAG, "Global setting not found: $name, using default: $defaultValue")
            defaultValue
        }
    }

    fun putGlobalInt(resolver: ContentResolver, name: String, value: Int): Boolean {
        return try {
            Settings.Global.putInt(resolver, name, value)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write global setting: $name", e)
            false
        }
    }

    fun getSystemInt(resolver: ContentResolver, name: String, defaultValue: Int = 0): Int {
        return try {
            Settings.System.getInt(resolver, name)
        } catch (e: Settings.SettingNotFoundException) {
            Log.w(TAG, "System setting not found: $name, using default: $defaultValue")
            defaultValue
        }
    }

    fun putSystemInt(resolver: ContentResolver, name: String, value: Int): Boolean {
        return try {
            Settings.System.putInt(resolver, name, value)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write system setting: $name", e)
            false
        }
    }
}
