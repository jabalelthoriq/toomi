package com.toomi.app.core.supabase.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    @SerialName("toomi_id")
    val toomiId: String = "",
    val email: String? = null,
    @SerialName("display_name")
    val displayName: String = "Toomi User",
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    @SerialName("active_device_id")
    val activeDeviceId: String? = null,
    @SerialName("active_device_name")
    val activeDeviceName: String? = null,
    @SerialName("last_device_active_at")
    val lastDeviceActiveAt: String? = null,
    @SerialName("battery_level")
    val batteryLevel: Int = 100,
    @SerialName("is_charging")
    val isCharging: Boolean = false,
    @SerialName("current_status")
    val currentStatus: String = "ACTIVE",
    @SerialName("character_model_id")
    val characterModelId: String = "animal-cat.glb"
)

@Serializable
data class Friendship(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("friend_id")
    val friendId: String,
    val status: String = "PENDING", // PENDING, ACCEPTED, REJECTED, BLOCKED
    @SerialName("created_at")
    val createdAt: String? = null
)

@Serializable
data class FriendItem(
    val friendshipId: String,
    val friendProfile: Profile,
    val isIncomingRequest: Boolean = false,
    val isPending: Boolean = false
)

@Serializable
data class LoginRequest(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("requester_device_id")
    val requesterDeviceId: String,
    @SerialName("requester_device_name")
    val requesterDeviceName: String,
    val status: String = "PENDING", // PENDING, APPROVED, REJECTED, EXPIRED
    @SerialName("created_at")
    val createdAt: String? = null
)

@Serializable
data class DeviceSessionResponse(
    val status: String = "GRANTED", // GRANTED, NEED_APPROVAL
    @SerialName("request_id")
    val requestId: String? = null,
    @SerialName("active_device_name")
    val activeDeviceName: String? = null,
    @SerialName("previous_device_name")
    val previousDeviceName: String? = null,
    @SerialName("is_switched")
    val isSwitched: Boolean = false,
    val message: String? = null
)

@Serializable
data class AddFriendResponse(
    val success: Boolean,
    val message: String,
    @SerialName("friend_name")
    val friendName: String? = null,
    @SerialName("friend_toomi_id")
    val friendToomiId: String? = null
)

/**
 * Payload WebSocket Broadcast untuk interaksi instan (<100ms) antar teman
 */
@Serializable
data class InteractionBroadcastPayload(
    @SerialName("event_type")
    val eventType: String, // POKE, HUG, WAVE, BUBBLE_CHAT, BATTERY_STATUS, MOOD_CHANGE
    @SerialName("sender_id")
    val senderId: String,
    @SerialName("receiver_id")
    val receiverId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val message: String? = null,
    @SerialName("animation_code")
    val animationCode: String? = null, // IDLE, POKE, HAPPY, SLEEP, WAVE
    @SerialName("battery_level")
    val batteryLevel: Int? = null,
    @SerialName("is_charging")
    val isCharging: Boolean? = null
)

/**
 * Realtime broadcast payload untuk kontrol sesi login antar perangkat (1 User 1 HP)
 */
@Serializable
data class DeviceControlBroadcastPayload(
    @SerialName("event_type")
    val eventType: String, // LOGIN_REQUEST, LOGIN_APPROVED, LOGIN_REJECTED, KICK_OUT
    @SerialName("user_id")
    val userId: String,
    @SerialName("request_id")
    val requestId: String? = null,
    @SerialName("requester_device_name")
    val requesterDeviceName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
