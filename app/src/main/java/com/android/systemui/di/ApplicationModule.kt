package com.android.systemui.di

import android.content.ContentResolver
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the small set of framework / infrastructure services that the
 * minimal app's Hilt graph needs. Trimmed from the proven SystemUI-Lite
 * ApplicationModule: a later phase re-adds AudioManager, WifiManager,
 * CameraManager, PowerManager, Vibrator, etc. as the UI modules come online.
 *
 * STATUS-BAR milestone (Phase 3): added provideWindowManager — required by
 * StatusBarManager to add the TYPE_STATUS_BAR window overlay.
 */
@Module
@InstallIn(SingletonComponent::class)
object ApplicationModule {

    @Provides
    @Singleton
    fun provideMainHandler(): Handler = Handler(Looper.getMainLooper())

    @Provides
    @Singleton
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

    @Provides
    @Singleton
    fun provideWindowManager(@ApplicationContext context: Context): WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
}
