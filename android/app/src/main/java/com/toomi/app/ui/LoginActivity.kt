package com.toomi.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.toomi.app.core.config.SupabaseConfig
import com.toomi.app.core.supabase.SupabaseManager
import com.toomi.app.databinding.ActivityLoginBinding
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.Google
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"
    }

    private lateinit var binding: ActivityLoginBinding
    private var pendingEmail: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Jika user sudah login sebelumnya, langsung lanjut ke MainActivity
        if (SupabaseManager.isUserLoggedIn()) {
            proceedToMain()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        if (SupabaseManager.isUserLoggedIn()) {
            proceedToMain()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val data = intent.data
        if (data != null && data.scheme == "toomi") {
            lifecycleScope.launch {
                try {
                    SupabaseManager.client.handleDeeplinks(intent)
                    if (SupabaseManager.isUserLoggedIn()) {
                        proceedToMain()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling oauth deeplink", e)
                }
            }
        }
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
            if (otp.length !in 6..8) {
                Toast.makeText(this, "Masukkan kode OTP yang valid (6-8 digit)!", Toast.LENGTH_SHORT).show()
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
                Log.e(TAG, "Gagal kirim OTP", err)
                val msg = when {
                    err.message?.contains("Database error", ignoreCase = true) == true ->
                        "Error database Supabase. Silakan coba kembali."
                    else -> "Gagal mengirim OTP: ${err.message}"
                }
                Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
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
                proceedToMain()
            }.onFailure { err ->
                Toast.makeText(this@LoginActivity, "Kode OTP salah atau kedaluwarsa: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun signInWithGoogle() {
        setLoading(true)
        lifecycleScope.launch {
            // 1. Gunakan Google Credential Manager jika Client ID terpasang
            if (SupabaseConfig.GOOGLE_WEB_CLIENT_ID.isNotEmpty()) {
                try {
                    val credentialManager = CredentialManager.create(this@LoginActivity)
                    val googleIdOption = GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(SupabaseConfig.GOOGLE_WEB_CLIENT_ID)
                        .setAutoSelectEnabled(false)
                        .build()

                    val request = GetCredentialRequest.Builder()
                        .addCredentialOption(googleIdOption)
                        .build()

                    val response = credentialManager.getCredential(this@LoginActivity, request)
                    val credential = response.credential

                    if (credential is CustomCredential &&
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        val idToken = googleIdTokenCredential.idToken
                        
                        Log.d(TAG, "Google ID Token received successfully, verifying with Supabase...")
                        val res = SupabaseManager.signInWithGoogle(idToken)
                        setLoading(false)
                        
                        res.onSuccess {
                            Toast.makeText(this@LoginActivity, "Login Google Berhasil!", Toast.LENGTH_SHORT).show()
                            proceedToMain()
                        }.onFailure { err ->
                            Log.e(TAG, "Supabase ID Token validation failed", err)
                            val alertMsg = "Supabase menolak ID Token: ${err.message}\n\nPastikan Client ID di Supabase Dashboard (Auth > Providers > Google) persis sama dengan Web Client ID ini:\n${SupabaseConfig.GOOGLE_WEB_CLIENT_ID}"
                            AlertDialog.Builder(this@LoginActivity)
                                .setTitle("Gagal Login Google")
                                .setMessage(alertMsg)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                        return@launch
                    }
                } catch (e: GetCredentialCancellationException) {
                    setLoading(false)
                    Log.d(TAG, "User cancelled Google sign in")
                    return@launch
                } catch (e: Exception) {
                    setLoading(false)
                    Log.e(TAG, "Credential Manager Exception", e)
                    val alertMsg = "Gagal mengambil kredensial Google: ${e.message}\n\nPastikan SHA-1 fingerprint dari aplikasi Anda sudah didaftarkan di Google Cloud Console pada OAuth Client ID (Android)."
                    AlertDialog.Builder(this@LoginActivity)
                        .setTitle("Error Google Credential")
                        .setMessage(alertMsg)
                        .setPositiveButton("OK", null)
                        .show()
                    return@launch
                }
            }

            // 2. Fallback: Supabase Web OAuth jika Web Client ID kosong
            try {
                SupabaseManager.client.auth.signInWith(Google)
                setLoading(false)
            } catch (e: Exception) {
                setLoading(false)
                Log.e(TAG, "Error signing in with Google Web OAuth", e)
                Toast.makeText(this@LoginActivity, "Login Google OAuth gagal: ${e.message}", Toast.LENGTH_LONG).show()
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
}
