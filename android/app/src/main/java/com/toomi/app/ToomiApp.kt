package com.toomi.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.android.filament.utils.Utils
import com.toomi.app.core.supabase.SupabaseManager

class ToomiApp : Application() {

    companion object {
        const val CHANNEL_ID = "toomi_overlay_channel"
        lateinit var instance: ToomiApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Initialize Filament Native Engine
        Utils.init()

        // 2. Initialize Supabase Client
        SupabaseManager.init(this)

        // 3. Create Notification Channel for Foreground Service
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Toomi LDR Companion Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga karakter 3D dan koneksi real-time tetap aktif"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}
