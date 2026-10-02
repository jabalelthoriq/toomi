package com.toomi.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.toomi.app.core.supabase.SupabaseManager
import com.toomi.app.core.supabase.models.InteractionBroadcastPayload

class BatteryStateReceiver : BroadcastReceiver() {

    private var lastReportedLevel = -1
    private var lastChargingState = false

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val batteryPct = if (level != -1 && scale != -1) {
                (level * 100 / scale.toFloat()).toInt()
            } else {
                level
            }

            // Broadcast only when change is significant (e.g. state changed or level dropped by >= 5%)
            if (batteryPct != lastReportedLevel || isCharging != lastChargingState) {
                lastReportedLevel = batteryPct
                lastChargingState = isCharging

                val senderId = SupabaseManager.getCurrentUserId() ?: "local_user"
                SupabaseManager.broadcastInteraction(
                    InteractionBroadcastPayload(
                        eventType = "BATTERY_STATUS",
                        senderId = senderId,
                        batteryLevel = batteryPct,
                        isCharging = isCharging
                    )
                )
            }
        }
    }
}
