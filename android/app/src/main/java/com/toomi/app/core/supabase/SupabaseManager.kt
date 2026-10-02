package com.toomi.app.core.supabase

import android.content.Context
import android.util.Log
import com.toomi.app.core.config.SupabaseConfig
import com.toomi.app.core.supabase.models.InteractionBroadcastPayload
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

object SupabaseManager {

    private const val TAG = "SupabaseManager"
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    lateinit var client: SupabaseClient
        private set

    private var activeCoupleChannel: RealtimeChannel? = null

    // Event bus for incoming broadcast messages to be consumed by UI / Overlay Service
    private val _incomingEvents = MutableSharedFlow<InteractionBroadcastPayload>()
    val incomingEvents: SharedFlow<InteractionBroadcastPayload> = _incomingEvents.asSharedFlow()

    fun init(context: Context) {
        client = createSupabaseClient(
            supabaseUrl = SupabaseConfig.SUPABASE_URL,
            supabaseKey = SupabaseConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
        Log.d(TAG, "Supabase initialized successfully")
    }

    /**
     * Berlangganan ke channel broadcast pasangan secara real-time
     */
    fun subscribeToCoupleChannel(coupleId: String) {
        scope.launch {
            try {
                // Lepas channel lama jika ada
                activeCoupleChannel?.unsubscribe()

                val channelName = "couple:$coupleId"
                val channel = client.realtime.channel(channelName)
                activeCoupleChannel = channel

                val broadcastFlow = channel.broadcastFlow<InteractionBroadcastPayload>(event = "interaction")
                
                channel.subscribe()
                Log.d(TAG, "Subscribed to Realtime channel: $channelName")

                broadcastFlow.collect { payload ->
                    Log.d(TAG, "Received broadcast: ${payload.eventType} from ${payload.senderId}")
                    _incomingEvents.emit(payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error subscribing to couple channel", e)
            }
        }
    }

    /**
     * Kirim aksi instan (Poke, animasi, chat, baterai) ke pasangan
     */
    fun broadcastInteraction(payload: InteractionBroadcastPayload) {
        scope.launch {
            try {
                activeCoupleChannel?.broadcast(
                    event = "interaction",
                    payload = payload
                )
                Log.d(TAG, "Broadcasted event: ${payload.eventType}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast interaction", e)
            }
        }
    }

    fun getCurrentUserId(): String? {
        return client.auth.currentUserOrNull()?.id
    }
}
