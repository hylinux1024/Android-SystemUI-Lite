package com.android.systemui.di

import android.content.ContentResolver
import android.content.Context
import android.os.Handler
import android.os.Looper
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
 * CameraManager, PowerManager, Vibrator, WindowManager, etc. as the UI modules
 * come online.
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
}
