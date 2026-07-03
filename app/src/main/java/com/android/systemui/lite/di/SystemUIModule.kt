package com.android.systemui.lite.di

import android.content.Context
import com.android.systemui.lite.data.NotificationProvider
import com.android.systemui.lite.data.ScreenRecorderController
import com.android.systemui.lite.data.SystemStateProvider
import com.android.systemui.lite.data.WallpaperProvider
import com.android.systemui.lite.plugins.PluginManager
import com.android.systemui.lite.qs.QSTileManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

val appModule = module {
    single { PluginManager() }
    single { NotificationProvider() }
    single<SystemStateProvider> { SystemStateProvider(androidContext()) }
    single { WallpaperProvider(androidContext()) }
    // Screen recording session owner — must be a singleton so the MediaProjection token
    // and the flag survive shade close/reopen (US-009 AC5 / AC6).
    single { ScreenRecorderController(androidContext()) }
    single { QSTileManager(androidContext()) }
}

fun initKoin(context: Context) {
    startKoin {
        androidContext(context)
        modules(appModule)
    }
}

fun destroyKoin() {
    stopKoin()
}
