package com.toomi.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.toomi.app.R
import com.toomi.app.ToomiApp
import com.toomi.app.core.filament.FilamentManager
import com.toomi.app.core.supabase.SupabaseManager
import com.toomi.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.InputStream
import java.nio.ByteBuffer

class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "FloatingOverlayService"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.toomi.app.ACTION_STOP"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var filamentManager: FilamentManager? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var screenStateReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BatteryStateReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, createNotification())
        setupOverlayView()
        setupScreenStateListener()
        setupBatteryListener()
        observePartnerInteractions()
    }

    private fun setupOverlayView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.layout_floating_overlay, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        val surfaceView = overlayView!!.findViewById<SurfaceView>(R.id.surface_filament)
        filamentManager = FilamentManager(this, surfaceView)

        // Load sample model from assets or create basic scene
        loadDefaultModel()

        // Setup Drag & Drop gestures
        setupTouchInteractions(overlayView!!, params)

        windowManager?.addView(overlayView, params)
        filamentManager?.startRendering()
    }

    private fun loadDefaultModel() {
        val prefs = getSharedPreferences("toomi_prefs", Context.MODE_PRIVATE)
        val selectedPet = prefs.getString("selected_pet", "animal-cat.glb") ?: "animal-cat.glb"
        
        // Coba muat dari models/ atau avatar.glb
        try {
            filamentManager?.loadModelFromAsset("models/$selectedPet")
        } catch (e: Exception) {
            filamentManager?.loadModelFromAsset("avatar.glb")
        }
    }

    private fun setupTouchInteractions(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isClick = false
                    }

                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        // User tap avatar: Kirim reaksi poke langsung ke pasangan
                        triggerLocalTap()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun triggerLocalTap() {
        triggerHaptic()
        showBubbleMessage("Kirim cinta ke pasangan... 💖", 2000L)
        filamentManager?.playAnimation(0) // Play happy/poke animation
    }

    /**
     * Mendengarkan event real-time dari pasangan melalui Supabase Realtime
     */
    private fun observePartnerInteractions() {
        serviceScope.launch {
            SupabaseManager.incomingEvents.collect { payload ->
                val currentUserId = SupabaseManager.getCurrentUserId()
                if (payload.senderId != currentUserId) {
                    when (payload.eventType) {
                        "POKE" -> {
                            triggerHaptic()
                            filamentManager?.playAnimation(1)
                            showBubbleMessage(payload.message ?: "Pasangan mencubitmu! 🥰", 3500L)
                        }
                        "WAVE" -> {
                            filamentManager?.playAnimation(2)
                            showBubbleMessage(payload.message ?: "Pasangan melambaikan tangan! 👋", 3000L)
                        }
                        "BUBBLE_CHAT" -> {
                            showBubbleMessage(payload.message ?: "", 4000L)
                        }
                        "BATTERY_STATUS" -> {
                            val level = payload.batteryLevel ?: 100
                            updateBatteryBadge(level, payload.isCharging ?: false)
                        }
                    }
                }
            }
        }
    }

    private fun showBubbleMessage(text: String, durationMs: Long) {
        val bubbleLayout = overlayView?.findViewById<LinearLayout>(R.id.layout_bubble_chat)
        val tvBubble = overlayView?.findViewById<TextView>(R.id.tv_bubble_text)

        tvBubble?.text = text
        bubbleLayout?.visibility = View.VISIBLE

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            bubbleLayout?.visibility = View.GONE
        }, durationMs)
    }

    private fun updateBatteryBadge(level: Int, isCharging: Boolean) {
        val tvBattery = overlayView?.findViewById<TextView>(R.id.tv_battery_badge)
        if (level < 15 && !isCharging) {
            tvBattery?.text = "🪫 Pasangan $level%"
            tvBattery?.visibility = View.VISIBLE
        } else {
            tvBattery?.visibility = View.GONE
        }
    }

    private fun triggerHaptic() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(120)
        }
    }

    /**
     * Smart Screen State Management: Matikan rendering saat layar mati (0% CPU/GPU)
     */
    private fun setupScreenStateListener() {
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> filamentManager?.pauseRendering()
                    Intent.ACTION_SCREEN_ON -> filamentManager?.startRendering()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    private fun setupBatteryListener() {
        batteryReceiver = BatteryStateReceiver()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ToomiApp.CHANNEL_ID)
            .setContentTitle("Toomi")
            .setContentText("Karakter 3D & sinkronisasi pasangan sedang aktif")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        screenStateReceiver?.let { unregisterReceiver(it) }
        batteryReceiver?.let { unregisterReceiver(it) }

        filamentManager?.destroy()
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }
    }
}
