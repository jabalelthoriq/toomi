package com.toomi.app.core.supabase.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val username: String? = null,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    @SerialName("battery_level")
    val batteryLevel: Int = 100,
    @SerialName("is_charging")
    val isCharging: Boolean = false,
    @SerialName("current_status")
    val currentStatus: String = "ACTIVE",
    @SerialName("character_model_id")
    val characterModelId: String = "default_mascot"
)

@Serializable
data class Couple(
    val id: String,
    @SerialName("user1_id")
    val user1Id: String,
    @SerialName("user2_id")
    val user2Id: String? = null,
    @SerialName("pair_code")
    val pairCode: String,
    val status: String = "PENDING"
)

/**
 * Payload WebSocket Broadcast untuk interaksi instan (<100ms)
 */
@Serializable
data class InteractionBroadcastPayload(
    @SerialName("event_type")
    val eventType: String, // POKE, HUG, BUBBLE_CHAT, BATTERY_STATUS, MOOD_CHANGE
    @SerialName("sender_id")
    val senderId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val message: String? = null,
    @SerialName("animation_code")
    val animationCode: String? = null, // IDLE, POKE, HAPPY, SLEEP, WAVE
    @SerialName("battery_level")
    val batteryLevel: Int? = null,
    @SerialName("is_charging")
    val isCharging: Boolean? = null
)
