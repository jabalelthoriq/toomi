package com.toomi.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.toomi.app.R
import com.toomi.app.core.supabase.SupabaseManager
import com.toomi.app.core.supabase.models.InteractionBroadcastPayload
import com.toomi.app.databinding.ActivityMainBinding
import com.toomi.app.service.FloatingOverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isOverlayRunning = false

    private val availablePets = listOf(
        "Kucing (Cat)" to "animal-cat.glb",
        "Anjing (Dog)" to "animal-dog.glb",
        "Panda" to "animal-panda.glb",
        "Kelinci (Bunny)" to "animal-bunny.glb",
        "Rubah (Fox)" to "animal-fox.glb",
        "Penguin" to "animal-penguin.glb",
        "Koala" to "animal-koala.glb",
        "Singa (Lion)" to "animal-lion.glb",
        "Harimau (Tiger)" to "animal-tiger.glb",
        "Beruang Kutub (Polar)" to "animal-polar.glb",
        "Gajah (Elephant)" to "animal-elephant.glb",
        "Jerapah (Giraffe)" to "animal-giraffe.glb",
        "Rusa (Deer)" to "animal-deer.glb",
        "Lebah (Bee)" to "animal-bee.glb",
        "Anak Ayam (Chick)" to "animal-chick.glb",
        "Sapi (Cow)" to "animal-cow.glb",
        "Babi (Pig)" to "animal-pig.glb",
        "Kepiting (Crab)" to "animal-crab.glb"
    )

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (checkOverlayPermission()) {
            startOverlayService()
        } else {
            Toast.makeText(this, "Izin Overlay dibutuhkan agar karakter dapat melayang!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPetSelector()
        setupListeners()
    }

    private fun setupPetSelector() {
        val petLabels = availablePets.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, petLabels)
        binding.spinnerPetSelector.adapter = adapter

        val prefs = getSharedPreferences("toomi_prefs", Context.MODE_PRIVATE)
        val currentPet = prefs.getString("selected_pet", "animal-cat.glb")
        val currentIndex = availablePets.indexOfFirst { it.second == currentPet }.coerceAtLeast(0)
        binding.spinnerPetSelector.setSelection(currentIndex)

        binding.spinnerPetSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedFile = availablePets[position].second
                prefs.edit().putString("selected_pet", selectedFile).apply()
                if (isOverlayRunning) {
                    // Restart overlay agar model baru termuat
                    stopOverlayService()
                    startOverlayService()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupListeners() {
        // Toggle Overlay Button
        binding.btnToggleOverlay.setOnClickListener {
            if (isOverlayRunning) {
                stopOverlayService()
            } else {
                if (checkOverlayPermission()) {
                    startOverlayService()
                } else {
                    requestOverlayPermission()
                }
            }
        }

        // Connect Couple Pair Code
        binding.btnConnectPair.setOnClickListener {
            val code = binding.etPairCode.text.toString().trim()
            if (code.length >= 6) {
                // Subscribe channel realtime pasangan
                SupabaseManager.subscribeToCoupleChannel(code)
                Toast.makeText(this, "Tersambung ke Room: $code", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Masukkan kode 6 karakter yang valid", Toast.LENGTH_SHORT).show()
            }
        }

        // Quick Test Buttons (Broadcast to partner)
        binding.btnSendPoke.setOnClickListener {
            val senderId = SupabaseManager.getCurrentUserId() ?: "my_device"
            SupabaseManager.broadcastInteraction(
                InteractionBroadcastPayload(
                    eventType = "POKE",
                    senderId = senderId,
                    animationCode = "POKE",
                    message = "Pasanganmu mencubitmu! 💖"
                )
            )
            Toast.makeText(this, "Poke terkirim ke pasangan!", Toast.LENGTH_SHORT).show()
        }

        binding.btnSendWave.setOnClickListener {
            val senderId = SupabaseManager.getCurrentUserId() ?: "my_device"
            SupabaseManager.broadcastInteraction(
                InteractionBroadcastPayload(
                    eventType = "WAVE",
                    senderId = senderId,
                    animationCode = "WAVE",
                    message = "Pasanganmu melambaikan tangan! 👋"
                )
            )
            Toast.makeText(this, "Lambaian terkirim ke pasangan!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, FloatingOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isOverlayRunning = true
        binding.btnToggleOverlay.text = getString(R.string.btn_stop_overlay)
        Toast.makeText(this, "Karakter melayang aktif!", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayService() {
        val intent = Intent(this, FloatingOverlayService::class.java)
        stopService(intent)
        isOverlayRunning = false
        binding.btnToggleOverlay.text = getString(R.string.btn_start_overlay)
        Toast.makeText(this, "Karakter melayang dimatikan", Toast.LENGTH_SHORT).show()
    }
}
