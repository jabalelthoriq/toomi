package com.toomi.app.core.filament

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import com.google.android.filament.*
import com.google.android.filament.utils.ModelViewer
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.sin

class FilamentManager(
    private val context: Context,
    private val surfaceView: SurfaceView
) : Choreographer.FrameCallback {

    companion object {
        private const val TAG = "FilamentManager"
        private const val IDLE_FRAME_DELAY_MS = 50L // ~20 FPS saat idle untuk hemat daya baterai
    }

    private lateinit var choreographer: Choreographer
    private lateinit var modelViewer: ModelViewer
    private var isRenderingActive = false
    private var isHighFpsMode = false
    private val handler = Handler(Looper.getMainLooper())
    
    private var rotationAngle = 0f
    private var isRotating = true

    init {
        initFilament()
    }

    private fun initFilament() {
        choreographer = Choreographer.getInstance()
        modelViewer = ModelViewer(surfaceView)

        // Set transparent background for overlay window
        modelViewer.scene.skybox = null
        modelViewer.view.blendMode = View.BlendMode.TRANSLUCENT
        
        val renderer = modelViewer.renderer
        renderer.clearOptions = renderer.clearOptions.apply {
            clear = true
            clearColor = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f) // 100% Transparan
        }

        // Setup camera
        val camera = modelViewer.camera
        camera.setExposure(16.0f, 1.0f / 125.0f, 100.0f)

        // Setup default direct lighting so models are bright and clear
        setupLighting()

        Log.d(TAG, "Filament 3D Engine initialized with transparent view")
    }

    private fun setupLighting() {
        try {
            val engine = modelViewer.engine
            val scene = modelViewer.scene

            val sunlight = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 0.95f, 0.9f)
                .intensity(70000.0f)
                .direction(-0.5f, -1.0f, -0.5f)
                .castShadows(false)
                .build(engine, sunlight)

            scene.addEntity(sunlight)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup lighting", e)
        }
    }

    fun loadModelFromAsset(assetPath: String) {
        try {
            val inputStream: InputStream = context.assets.open(assetPath)
            val bytes = inputStream.readBytes()
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                rewind()
            }
            loadModelFromBuffer(buffer)
            Log.d(TAG, "Loaded model successfully from: $assetPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model asset: $assetPath", e)
        }
    }

    fun loadModelFromBuffer(buffer: ByteBuffer) {
        try {
            modelViewer.destroyModel()
            modelViewer.loadModelGlb(buffer)
            modelViewer.transformToUnitCube()
            Log.d(TAG, "GLB Model loaded and centered in unit cube")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load 3D model buffer", e)
        }
    }

    fun triggerHappyReaction() {
        // Switch to 60 FPS for smooth reaction bounce/spin
        setHighFpsMode(true)
        handler.postDelayed({
            setHighFpsMode(false)
        }, 2500L)
    }

    fun playAnimation(animationIndex: Int, durationMs: Long = 3000L) {
        try {
            val animator = modelViewer.animator
            if (animator != null && animator.animationCount > animationIndex) {
                animator.applyAnimation(animationIndex, 0f)
                animator.updateBoneMatrices()
            }
            setHighFpsMode(true)
            handler.postDelayed({
                setHighFpsMode(false)
            }, durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing animation index: $animationIndex", e)
        }
    }

    fun startRendering() {
        if (!isRenderingActive) {
            isRenderingActive = true
            choreographer.postFrameCallback(this)
            Log.d(TAG, "Filament rendering started")
        }
    }

    fun pauseRendering() {
        if (isRenderingActive) {
            isRenderingActive = false
            choreographer.removeFrameCallback(this)
            Log.d(TAG, "Filament rendering paused (0% GPU/CPU)")
        }
    }

    private fun setHighFpsMode(enabled: Boolean) {
        isHighFpsMode = enabled
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRenderingActive) return

        // Optional gentle slow idle rotation
        if (isRotating) {
            rotationAngle += if (isHighFpsMode) 1.5f else 0.4f
            if (rotationAngle >= 360f) rotationAngle -= 360f
        }

        modelViewer.render(frameTimeNanos)

        if (isHighFpsMode) {
            choreographer.postFrameCallback(this)
        } else {
            handler.postDelayed({
                if (isRenderingActive) {
                    choreographer.postFrameCallback(this)
                }
            }, IDLE_FRAME_DELAY_MS)
        }
    }

    fun destroy() {
        pauseRendering()
        modelViewer.destroyModel()
    }
}
