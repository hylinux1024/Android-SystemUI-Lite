package com.android.systemui.lite.di

import android.content.Context
import com.android.systemui.lite.data.SystemStateProvider
import com.android.systemui.lite.data.WallpaperProvider
import com.android.systemui.lite.plugins.PluginManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

val appModule = module {
    single { PluginManager() }
    single { SystemStateProvider(androidContext()) }
    single { WallpaperProvider(androidContext()) }
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
