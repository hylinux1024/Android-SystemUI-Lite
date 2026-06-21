package com.android.systemui.di

import android.content.ContentResolver
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.Vibrator
import android.view.WindowManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApplicationModule {

    @Provides
    @Singleton
    fun provideAudioManager(@ApplicationContext context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Provides
    @Singleton
    fun provideWifiManager(@ApplicationContext context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Provides
    @Singleton
    fun provideCameraManager(@ApplicationContext context: Context): CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    @Provides
    @Singleton
    fun providePowerManager(@ApplicationContext context: Context): PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Provides
    @Singleton
    fun provideVibrator(@ApplicationContext context: Context): Vibrator =
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    @Provides
    @Singleton
    fun provideWindowManager(@ApplicationContext context: Context): WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Provides
    @Singleton
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver
}
