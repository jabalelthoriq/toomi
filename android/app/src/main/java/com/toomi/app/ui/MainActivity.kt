package com.toomi.app.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.toomi.app.R
import com.toomi.app.core.supabase.SupabaseManager
import com.toomi.app.core.supabase.models.FriendItem
import com.toomi.app.core.supabase.models.InteractionBroadcastPayload
import com.toomi.app.core.supabase.models.Profile
import com.toomi.app.databinding.ActivityMainBinding
import com.toomi.app.service.FloatingOverlayService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isOverlayRunning = false
    private var myProfile: Profile? = null
    private var activePairedFriend: FriendItem? = null
    private var incomingLoginDialog: AlertDialog? = null

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

        // Pastikan user terautentikasi
        if (!SupabaseManager.isUserLoggedIn()) {
            redirectToLogin()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPetSelector()
        setupListeners()
        loadUserProfile()
        loadFriendsList()
        listenToDeviceControlEvents()
    }

    /**
     * Memuat profil akun dan Toomi ID unik
     */
    private fun loadUserProfile() {
        lifecycleScope.launch {
            val profile = SupabaseManager.getMyProfile()
            if (profile != null) {
                myProfile = profile
                binding.tvUserDisplayName.text = profile.displayName
                binding.tvUserEmail.text = profile.email ?: "Akun Terverifikasi"
                binding.tvMyToomiId.text = profile.toomiId.ifEmpty { "TM-NEW" }
                binding.tvDeviceInfo.text = "📱 ${SupabaseManager.getDeviceName()}"
            }
        }
    }

    /**
     * Memuat daftar teman dan permintaan pertemanan via Toomi ID
     */
    private fun loadFriendsList() {
        lifecycleScope.launch {
            val friends = SupabaseManager.getFriendsList()
            renderFriendsList(friends)
        }
    }

    private fun renderFriendsList(friends: List<FriendItem>) {
        val container = binding.layoutFriendsContainer
        container.removeAllViews()

        binding.tvFriendsTitle.text = "Daftar Teman (${friends.size})"

        if (friends.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "Belum ada teman. Bagikan ID Toomi Anda atau tambahkan teman baru!"
                setTextColor(getColor(R.color.text_muted))
                textSize = 12f
                setPadding(0, 30, 0, 30)
                gravity = android.view.Gravity.CENTER
            }
            container.addView(emptyView)
            return
        }

        val inflater = LayoutInflater.from(this)
        for (item in friends) {
            val itemView = inflater.inflate(R.layout.item_friend, container, false)
            val tvName = itemView.findViewById<TextView>(R.id.tv_friend_name)
            val tvToomiId = itemView.findViewById<TextView>(R.id.tv_friend_toomi_id)
            val tvStatus = itemView.findViewById<TextView>(R.id.tv_friend_status)
            val btnPair = itemView.findViewById<Button>(R.id.btn_pair_companion)
            val layoutActions = itemView.findViewById<LinearLayout>(R.id.layout_request_actions)
            val btnAccept = itemView.findViewById<Button>(R.id.btn_accept_friend)
            val btnReject = itemView.findViewById<Button>(R.id.btn_reject_friend)

            tvName.text = item.friendProfile.displayName
            tvToomiId.text = "ID: ${item.friendProfile.toomiId}"

            if (item.isIncomingRequest) {
                tvStatus.text = "📩 Menunggu persetujuan Anda"
                btnPair.visibility = View.GONE
                layoutActions.visibility = View.VISIBLE

                btnAccept.setOnClickListener {
                    lifecycleScope.launch {
                        SupabaseManager.acceptFriendRequest(item.friendshipId)
                        Toast.makeText(this@MainActivity, "Pertemanan diterima!", Toast.LENGTH_SHORT).show()
                        loadFriendsList()
                    }
                }

                btnReject.setOnClickListener {
                    lifecycleScope.launch {
                        SupabaseManager.deleteFriendship(item.friendshipId)
                        loadFriendsList()
                    }
                }
            } else if (item.isPending) {
                tvStatus.text = "⏳ Menunggu konfirmasi teman"
                btnPair.visibility = View.GONE
                layoutActions.visibility = View.GONE
            } else {
                tvStatus.text = "🟢 Terhubung (${item.friendProfile.batteryLevel}% Baterai)"
                layoutActions.visibility = View.GONE
                btnPair.visibility = View.VISIBLE

                val isCurrentPair = activePairedFriend?.friendProfile?.id == item.friendProfile.id
                if (isCurrentPair) {
                    btnPair.text = "Aktif ✅"
                    btnPair.setBackgroundColor(getColor(R.color.accent))
                } else {
                    btnPair.text = "Pasang 💖"
                    btnPair.setBackgroundColor(getColor(R.color.primary))
                }

                btnPair.setOnClickListener {
                    setActivePairedFriend(item)
                }
            }

            container.addView(itemView)
        }
    }

    private fun setActivePairedFriend(friendItem: FriendItem) {
        activePairedFriend = friendItem
        binding.tvActivePairedFriend.text = "${friendItem.friendProfile.displayName} (${friendItem.friendProfile.toomiId})"
        SupabaseManager.subscribeToFriendInteractions(friendItem.friendProfile.id)
        Toast.makeText(this, "Teman aktif diubah ke ${friendItem.friendProfile.displayName}", Toast.LENGTH_SHORT).show()
        loadFriendsList()
    }

    /**
     * Mendengarkan event kontrol sesi perangkat akun (1 User 1 HP)
     */
    private fun listenToDeviceControlEvents() {
        SupabaseManager.listenToDeviceControl()
        lifecycleScope.launch {
            SupabaseManager.deviceControlEvents.collectLatest { event ->
                when (event.eventType) {
                    "LOGIN_REQUEST" -> {
                        // Perangkat lain ingin masuk -> Tampilkan dialog ACC / Tolak
                        val reqDevice = event.requesterDeviceName ?: "Perangkat Lain"
                        val reqId = event.requestId ?: ""
                        showIncomingLoginApprovalDialog(reqDevice, reqId)
                    }
                    "KICK_OUT" -> {
                        // Sesi telah diambil alih oleh perangkat baru -> Terlempar keluar
                        handleKickOut()
                    }
                }
            }
        }
    }

    /**
     * Dialog konfirmasi ACC jika ada HP lain yang mencoba masuk ke akun ini
     */
    private fun showIncomingLoginApprovalDialog(requesterDevice: String, requestId: String) {
        incomingLoginDialog?.dismiss()
        val builder = AlertDialog.Builder(this)
            .setTitle("⚠️ Permintaan Masuk Perangkat Baru")
            .setMessage("Perangkat lain ($requesterDevice) meminta izin untuk masuk ke akun Toomi Anda.\n\nJika Anda mengizinkan (ACC), akun di HP ini akan langsung keluar secara otomatis.")
            .setCancelable(false)
            .setPositiveButton("ACC (Izinkan Masuk)") { dialog, _ ->
                dialog.dismiss()
                lifecycleScope.launch {
                    val res = SupabaseManager.approveLoginRequest(requestId)
                    res.onSuccess {
                        Toast.makeText(this@MainActivity, "Persetujuan diberikan. Mengeluarkan akun dari HP ini...", Toast.LENGTH_SHORT).show()
                        handleKickOut()
                    }.onFailure {
                        Toast.makeText(this@MainActivity, "Gagal memproses ACC: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Tolak") { dialog, _ ->
                dialog.dismiss()
                lifecycleScope.launch {
                    SupabaseManager.rejectLoginRequest(requestId)
                    Toast.makeText(this@MainActivity, "Permintaan masuk berhasil ditolak.", Toast.LENGTH_SHORT).show()
                }
            }

        incomingLoginDialog = builder.create()
        incomingLoginDialog?.show()
    }

    private fun handleKickOut() {
        stopOverlayService()
        Toast.makeText(this, "Sesi Anda telah dialihkan ke perangkat baru.", Toast.LENGTH_LONG).show()
        redirectToLogin()
    }

    private fun redirectToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
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
                    stopOverlayService()
                    startOverlayService()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupListeners() {
        // Salin ID Toomi
        binding.btnCopyToomiId.setOnClickListener {
            val toomiId = binding.tvMyToomiId.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Toomi ID", toomiId)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "ID Toomi disalin: $toomiId", Toast.LENGTH_SHORT).show()
        }

        // Bagikan ID Toomi
        binding.btnShareToomiId.setOnClickListener {
            val toomiId = binding.tvMyToomiId.text.toString()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Hubungkan Toomi ID Saya!")
                putExtra(Intent.EXTRA_TEXT, "Yuk berteman di aplikasi Toomi 3D Companion! Tambahkan ID Toomi saya: $toomiId 💕")
            }
            startActivity(Intent.createChooser(shareIntent, "Bagikan ID Toomi"))
        }

        // Tambah Teman via Toomi ID
        binding.btnAddFriend.setOnClickListener {
            val inputId = binding.etSearchToomiId.text.toString().trim().uppercase()
            if (inputId.isEmpty()) {
                Toast.makeText(this, "Masukkan ID Toomi teman!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val res = SupabaseManager.addFriendByToomiId(inputId)
                Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_LONG).show()
                if (res.success) {
                    binding.etSearchToomiId.text?.clear()
                    loadFriendsList()
                }
            }
        }

        // Segarkan daftar teman
        binding.btnRefreshFriends.setOnClickListener {
            loadFriendsList()
            Toast.makeText(this, "Memperbarui daftar teman...", Toast.LENGTH_SHORT).show()
        }

        // Quick Interactions (Poke / Wave) ke teman yang sedang dipasangkan
        binding.btnSendPoke.setOnClickListener {
            val friend = activePairedFriend
            if (friend == null) {
                Toast.makeText(this, "Pilih teman di daftar terlebih dahulu!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val senderId = SupabaseManager.getCurrentUserId() ?: "my_device"
            SupabaseManager.broadcastInteraction(
                InteractionBroadcastPayload(
                    eventType = "POKE",
                    senderId = senderId,
                    receiverId = friend.friendProfile.id,
                    animationCode = "POKE",
                    message = "${myProfile?.displayName ?: "Temanmu"} mencubitmu! 💖"
                )
            )
            Toast.makeText(this, "Poke terkirim ke ${friend.friendProfile.displayName}!", Toast.LENGTH_SHORT).show()
        }

        binding.btnSendWave.setOnClickListener {
            val friend = activePairedFriend
            if (friend == null) {
                Toast.makeText(this, "Pilih teman di daftar terlebih dahulu!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val senderId = SupabaseManager.getCurrentUserId() ?: "my_device"
            SupabaseManager.broadcastInteraction(
                InteractionBroadcastPayload(
                    eventType = "WAVE",
                    senderId = senderId,
                    receiverId = friend.friendProfile.id,
                    animationCode = "WAVE",
                    message = "${myProfile?.displayName ?: "Temanmu"} melambaikan tangan! 👋"
                )
            )
            Toast.makeText(this, "Lambaian terkirim ke ${friend.friendProfile.displayName}!", Toast.LENGTH_SHORT).show()
        }

        // Toggle Floating Overlay Service
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

        // Tombol Logout
        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Keluar Akun")
                .setMessage("Apakah Anda yakin ingin keluar dari perangkat ini? Anda dapat masuk kembali di HP mana pun nanti.")
                .setPositiveButton("Keluar") { _, _ ->
                    lifecycleScope.launch {
                        stopOverlayService()
                        SupabaseManager.signOut()
                        redirectToLogin()
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
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
    }

    override fun onDestroy() {
        super.onDestroy()
        incomingLoginDialog?.dismiss()
    }
}
