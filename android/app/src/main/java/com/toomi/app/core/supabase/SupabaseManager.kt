package com.toomi.app.core.supabase

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.toomi.app.core.config.SupabaseConfig
import com.toomi.app.core.supabase.models.AddFriendResponse
import com.toomi.app.core.supabase.models.DeviceControlBroadcastPayload
import com.toomi.app.core.supabase.models.DeviceSessionResponse
import com.toomi.app.core.supabase.models.FriendItem
import com.toomi.app.core.supabase.models.Friendship
import com.toomi.app.core.supabase.models.InteractionBroadcastPayload
import com.toomi.app.core.supabase.models.LoginRequest
import com.toomi.app.core.supabase.models.Profile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

object SupabaseManager {

    private const val TAG = "SupabaseManager"
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val json = Json { ignoreUnknownKeys = true }

    lateinit var client: SupabaseClient
        private set

    private var deviceControlChannel: RealtimeChannel? = null
    private var activeFriendChannel: RealtimeChannel? = null

    // Event bus for incoming interactions (Poke, Wave, Chat, Battery)
    private val _incomingEvents = MutableSharedFlow<InteractionBroadcastPayload>()
    val incomingEvents: SharedFlow<InteractionBroadcastPayload> = _incomingEvents.asSharedFlow()

    // Event bus for device login control (Login request alert, kick-out signal, approval result)
    private val _deviceControlEvents = MutableSharedFlow<DeviceControlBroadcastPayload>()
    val deviceControlEvents: SharedFlow<DeviceControlBroadcastPayload> = _deviceControlEvents.asSharedFlow()

    private var cachedDeviceId: String? = null
    private var cachedDeviceName: String? = null

    fun init(context: Context) {
        client = createSupabaseClient(
            supabaseUrl = SupabaseConfig.SUPABASE_URL,
            supabaseKey = SupabaseConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth) {
                scheme = "toomi"
                host = "login-callback"
            }
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }

        // Generate or retrieve persistent Device ID
        val prefs = context.getSharedPreferences("toomi_device_prefs", Context.MODE_PRIVATE)
        var devId = prefs.getString("device_unique_id", null)
        if (devId.isNullOrEmpty()) {
            devId = UUID.randomUUID().toString()
            prefs.edit().putString("device_unique_id", devId).apply()
        }
        cachedDeviceId = devId
        cachedDeviceName = "${Build.MANUFACTURER.capitalize()} ${Build.MODEL}"

        Log.d(TAG, "Supabase initialized with Device: $cachedDeviceName ($cachedDeviceId)")
    }

    fun getDeviceId(): String = cachedDeviceId ?: "unknown_device"
    fun getDeviceName(): String = cachedDeviceName ?: "Android Device"

    fun getCurrentUserId(): String? {
        return try {
            client.auth.currentSessionOrNull()?.user?.id
        } catch (e: Exception) {
            null
        }
    }

    fun isUserLoggedIn(): Boolean {
        return getCurrentUserId() != null
    }

    // ========================================================
    // 1. AUTHENTICATION (Google & Email OTP)
    // ========================================================

    /**
     * Kirim kode OTP 6-digit ke email pengguna
     */
    suspend fun sendEmailOtp(email: String): Result<Unit> {
        return try {
            client.auth.signInWith(OTP) {
                this.email = email
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending Email OTP", e)
            Result.failure(e)
        }
    }

    /**
     * Verifikasi kode OTP email
     */
    suspend fun verifyEmailOtp(email: String, otpToken: String): Result<Unit> {
        return try {
            client.auth.verifyEmailOtp(
                type = OtpType.Email.EMAIL,
                email = email,
                token = otpToken
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying Email OTP", e)
            Result.failure(e)
        }
    }

    /**
     * Sign in dengan Google OAuth atau ID Token
     */
    suspend fun signInWithGoogle(idTokenString: String? = null): Result<Unit> {
        return try {
            if (!idTokenString.isNullOrEmpty()) {
                client.auth.signInWith(io.github.jan.supabase.auth.providers.builtin.IDToken) {
                    idToken = idTokenString
                    provider = Google
                }
            } else {
                client.auth.signInWith(Google)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error signing in with Google", e)
            Result.failure(e)
        }
    }

    /**
     * Sign out dari akun
     */
    suspend fun signOut(): Result<Unit> {
        return try {
            activeFriendChannel?.unsubscribe()
            client.auth.signOut()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error during signOut", e)
            Result.failure(e)
        }
    }

    // ========================================================
    // 3. TOOMI ID & MULTI-FRIEND PAIRING SYSTEM
    // ========================================================

    /**
     * Dapatkan profil user saat ini (termasuk Toomi ID unik)
     */
    suspend fun getMyProfile(): Profile? {
        val userId = getCurrentUserId() ?: return null
        return try {
            val existing = client.postgrest["profiles"]
                .select {
                    filter {
                        eq("id", userId)
                    }
                }
                .decodeSingleOrNull<Profile>()

            if (existing != null) {
                return existing
            }

            // Fallback: Jika profile belum terbuat di DB, generate toomi_id dan insert
            val genId = "TM-" + UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
            val userEmail = client.auth.currentSessionOrNull()?.user?.email
            val defaultName = userEmail?.substringBefore("@") ?: "Toomi User"

            val fallbackProfile = Profile(
                id = userId,
                toomiId = genId,
                email = userEmail,
                displayName = defaultName,
                characterModelId = "animal-cat.glb"
            )

            try {
                client.postgrest["profiles"].insert(fallbackProfile)
            } catch (insertErr: Exception) {
                Log.w(TAG, "Fallback profile insert ignored", insertErr)
            }

            fallbackProfile
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching profile", e)
            null
        }
    }

    /**
     * Tambah teman menggunakan Toomi ID unik
     */
    suspend fun addFriendByToomiId(toomiId: String): AddFriendResponse {
        return try {
            val response = client.postgrest.rpc(
                function = "add_friend_by_toomi_id",
                parameters = buildJsonObject {
                    put("p_toomi_id", toomiId.trim().uppercase())
                }
            )
            val jsonElement = response.decodeAs<kotlinx.serialization.json.JsonElement>()
            json.decodeFromJsonElement<AddFriendResponse>(jsonElement)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding friend by Toomi ID", e)
            AddFriendResponse(success = false, message = e.message ?: "Gagal menambahkan teman")
        }
    }

    /**
     * Dapatkan daftar teman (Accepted & Pending)
     */
    suspend fun getFriendsList(): List<FriendItem> {
        val userId = getCurrentUserId() ?: return emptyList()
        return try {
            // Ambil semua row pertemanan di mana user terlibat
            val friendships = client.postgrest["friendships"]
                .select {
                    filter {
                        or {
                            eq("user_id", userId)
                            eq("friend_id", userId)
                        }
                    }
                }
                .decodeList<Friendship>()

            val resultList = mutableListOf<FriendItem>()

            for (f in friendships) {
                val targetId = if (f.userId == userId) f.friendId else f.userId
                val isIncoming = (f.friendId == userId && f.status == "PENDING")
                val isPending = f.status == "PENDING"

                val friendProfile = client.postgrest["profiles"]
                    .select {
                        filter {
                            eq("id", targetId)
                        }
                    }
                    .decodeSingleOrNull<Profile>()

                if (friendProfile != null) {
                    resultList.add(
                        FriendItem(
                            friendshipId = f.id,
                            friendProfile = friendProfile,
                            isIncomingRequest = isIncoming,
                            isPending = isPending
                        )
                    )
                }
            }

            resultList
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching friends list", e)
            emptyList()
        }
    }

    /**
     * Terima permintaan pertemanan
     */
    suspend fun acceptFriendRequest(friendshipId: String): Result<Unit> {
        return try {
            client.postgrest["friendships"]
                .update(
                    {
                        set("status", "ACCEPTED")
                    }
                ) {
                    filter {
                        eq("id", friendshipId)
                    }
                }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error accepting friend request", e)
            Result.failure(e)
        }
    }

    /**
     * Tolak/Hapus pertemanan
     */
    suspend fun deleteFriendship(friendshipId: String): Result<Unit> {
        return try {
            client.postgrest["friendships"]
                .delete {
                    filter {
                        eq("id", friendshipId)
                    }
                }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting friendship", e)
            Result.failure(e)
        }
    }

    // ========================================================
    // 4. REALTIME INTERACTION BROADCAST (Multi-friend Support)
    // ========================================================

    /**
     * Berlangganan ke channel interaksi teman terpilih
     */
    fun subscribeToFriendInteractions(friendUserId: String) {
        val myId = getCurrentUserId() ?: return
        scope.launch {
            try {
                activeFriendChannel?.unsubscribe()

                // Buat room ID konsisten antara myId dan friendUserId
                val roomId = if (myId < friendUserId) "${myId}_${friendUserId}" else "${friendUserId}_${myId}"
                val channelName = "friend_room:$roomId"
                val channel = client.realtime.channel(channelName)
                activeFriendChannel = channel

                val broadcastFlow = channel.broadcastFlow<InteractionBroadcastPayload>(event = "interaction")
                channel.subscribe()
                Log.d(TAG, "Subscribed to friend room: $channelName")

                broadcastFlow.collect { payload ->
                    Log.d(TAG, "Received interaction: ${payload.eventType} from ${payload.senderId}")
                    _incomingEvents.emit(payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error subscribing to friend room", e)
            }
        }
    }

    /**
     * Kirim aksi instan (Poke, animasi, chat, baterai) ke teman yang sedang dipasangkan di layar
     */
    fun broadcastInteraction(payload: InteractionBroadcastPayload) {
        scope.launch {
            try {
                val jsonObject = buildJsonObject {
                    put("event_type", payload.eventType)
                    put("sender_id", payload.senderId)
                    payload.receiverId?.let { put("receiver_id", it) }
                    put("timestamp", payload.timestamp)
                    payload.message?.let { put("message", it) }
                    payload.animationCode?.let { put("animation_code", it) }
                    payload.batteryLevel?.let { put("battery_level", it) }
                    payload.isCharging?.let { put("is_charging", it) }
                }
                activeFriendChannel?.broadcast(
                    event = "interaction",
                    message = jsonObject
                )
                Log.d(TAG, "Broadcasted interaction: ${payload.eventType}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast interaction", e)
            }
        }
    }
}
