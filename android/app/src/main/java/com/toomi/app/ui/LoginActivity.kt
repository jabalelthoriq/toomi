package com.toomi.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.toomi.app.core.supabase.SupabaseManager
import com.toomi.app.databinding.ActivityLoginBinding
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var pendingEmail: String = ""
    private var approvalDialog: AlertDialog? = null
    private var deviceControlJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Periksa apakah ada sesi tersimpan
        if (SupabaseManager.isUserLoggedIn()) {
            checkActiveDeviceSessionAndProceed()
        }

        setupListeners()
    }

    private fun setupListeners() {
        // Step 1: Kirim OTP ke Email
        binding.btnSendOtp.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Masukkan alamat email yang valid!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendOtp(email)
        }

        // Step 2: Verifikasi Kode OTP
        binding.btnVerifyOtp.setOnClickListener {
            val otp = binding.etOtp.text.toString().trim()
            if (otp.length != 6) {
                Toast.makeText(this, "Masukkan 6 digit kode OTP!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            verifyOtp(pendingEmail, otp)
        }

        // Kembali ganti email
        binding.btnBackEmail.setOnClickListener {
            binding.layoutOtpStep.visibility = View.GONE
            binding.layoutEmailStep.visibility = View.VISIBLE
        }

        // Masuk dengan Google
        binding.btnGoogleSignin.setOnClickListener {
            signInWithGoogle()
        }
    }

    private fun sendOtp(email: String) {
        setLoading(true)
        lifecycleScope.launch {
            val result = SupabaseManager.sendEmailOtp(email)
            setLoading(false)
            result.onSuccess {
                pendingEmail = email
                binding.layoutEmailStep.visibility = View.GONE
                binding.layoutOtpStep.visibility = View.VISIBLE
                binding.tvOtpSentInfo.text = "Kode OTP 6-digit telah dikirim ke:\n$email"
                Toast.makeText(this@LoginActivity, "Kode OTP terkirim! Cek inbox/spam email Anda.", Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                Toast.makeText(this@LoginActivity, "Gagal mengirim OTP: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun verifyOtp(email: String, otp: String) {
        setLoading(true)
        lifecycleScope.launch {
            val result = SupabaseManager.verifyEmailOtp(email, otp)
            setLoading(false)
            result.onSuccess {
                Toast.makeText(this@LoginActivity, "Verifikasi Berhasil!", Toast.LENGTH_SHORT).show()
                checkActiveDeviceSessionAndProceed()
            }.onFailure { err ->
                Toast.makeText(this@LoginActivity, "Kode OTP salah atau kedaluwarsa: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun signInWithGoogle() {
        setLoading(true)
        lifecycleScope.launch {
            try {
                // Gunakan Supabase OAuth Flow
                SupabaseManager.client.auth.signInWith(Google)
                setLoading(false)
                checkActiveDeviceSessionAndProceed()
            } catch (e: Exception) {
                setLoading(false)
                Toast.makeText(this@LoginActivity, "Login Google gagal: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Memeriksa apakah akun sedang aktif di HP lain (1 User 1 HP Policy)
     */
    private fun checkActiveDeviceSessionAndProceed() {
        setLoading(true)
        lifecycleScope.launch {
            val sessionResponse = SupabaseManager.requestDeviceSession()
            setLoading(false)

            if (sessionResponse.status == "GRANTED") {
                // Perangkat ini langsung menjadi perangkat aktif
                proceedToMain()
            } else if (sessionResponse.status == "NEED_APPROVAL") {
                // Ada HP lain yang sedang aktif -> Tampilkan dialog menunggu persetujuan (ACC)
                val activeDevName = sessionResponse.activeDeviceName ?: "HP Anda yang lain"
                val reqId = sessionResponse.requestId
                showWaitingApprovalDialog(activeDevName, reqId)
            }
        }
    }

    /**
     * Dialog modal menunggu persetujuan (ACC) dari perangkat lama
     */
    private fun showWaitingApprovalDialog(activeDeviceName: String, requestId: String?) {
        SupabaseManager.listenToDeviceControl()

        val builder = AlertDialog.Builder(this)
            .setTitle("🔒 Konfirmasi Masuk Perangkat")
            .setMessage("Akun Toomi Anda saat ini sedang aktif di perangkat:\n\n📱 $activeDeviceName\n\nNotifikasi persetujuan (ACC) telah dikirimkan ke HP tersebut. Silakan buka HP tersebut dan tekan 'ACC (Izinkan)' untuk melanjutkan.")
            .setCancelable(false)
            .setNegativeButton("Batalkan") { dialog, _ ->
                dialog.dismiss()
                lifecycleScope.launch {
                    SupabaseManager.signOut()
                }
            }

        approvalDialog = builder.create()
        approvalDialog?.show()

        // Dengarkan realtime event jika HP lama meng-ACC atau menolak
        deviceControlJob?.cancel()
        deviceControlJob = lifecycleScope.launch {
            SupabaseManager.deviceControlEvents.collectLatest { event ->
                if (event.eventType == "LOGIN_APPROVED") {
                    approvalDialog?.dismiss()
                    Toast.makeText(this@LoginActivity, "Persetujuan diterima! Berhasil masuk.", Toast.LENGTH_LONG).show()
                    proceedToMain()
                } else if (event.eventType == "LOGIN_REJECTED") {
                    approvalDialog?.dismiss()
                    Toast.makeText(this@LoginActivity, "Permintaan masuk ditolak oleh HP aktif.", Toast.LENGTH_LONG).show()
                    SupabaseManager.signOut()
                }
            }
        }
    }

    private fun proceedToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnSendOtp.isEnabled = !isLoading
        binding.btnVerifyOtp.isEnabled = !isLoading
        binding.btnGoogleSignin.isEnabled = !isLoading
    }

    override fun onDestroy() {
        super.onDestroy()
        approvalDialog?.dismiss()
        deviceControlJob?.cancel()
    }
}
