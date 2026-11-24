package com.auraai.overlay.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.auraai.overlay.R
import com.auraai.overlay.databinding.ActivityMainBinding
import com.auraai.overlay.service.AuraAccessibilityService
import com.auraai.overlay.service.BubbleService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val REQUEST_OVERLAY = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge dark
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = 0xFF0A0D1A.toInt()
            navigationBarColor = 0xFF0A0D1A.toInt()
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayStatus()
        updateAccessibilityStatus()
        updateProviderLabel()
    }

    private fun setupUI() {
        binding.btnStart.setOnClickListener {
            if (canDrawOverlays()) startOverlay() else requestOverlayPermission()
        }
        binding.btnStop.setOnClickListener { stopOverlay() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.cardProvider.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnActivateAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    private fun updateOverlayStatus() {
        if (!canDrawOverlays()) {
            binding.tvStatus.text = "Izin diperlukan"
            binding.tvStatusDesc.text = "Tap izinkan untuk melanjutkan"
            binding.btnStart.text = "Izinkan"
            binding.btnStop.visibility = View.GONE
            binding.btnStart.visibility = View.VISIBLE
            return
        }
        if (BubbleService.isRunning) {
            binding.tvStatus.text = "Aktif"
            binding.tvStatusDesc.text = "Robot bubble berjalan di atas semua app"
            binding.btnStart.visibility = View.GONE
            binding.btnStop.visibility = View.VISIBLE
        } else {
            binding.tvStatus.text = "Siap digunakan"
            binding.tvStatusDesc.text = "Aktifkan bubble untuk mulai"
            binding.btnStart.text = "Aktifkan"
            binding.btnStart.visibility = View.VISIBLE
            binding.btnStop.visibility = View.GONE
        }
    }

    private fun updateAccessibilityStatus() {
        val active = AuraAccessibilityService.instance != null
        binding.tvAccessibilityDot.setBackgroundResource(
            if (active) R.drawable.bg_dot_green else R.drawable.bg_dot_red
        )
        binding.tvAccessibilityStatus.text = if (active)
            "Aktif — auto-jawab tersedia"
        else
            "Diperlukan untuk auto-jawab"
        binding.btnActivateAccessibility.text = if (active) "✓ Aktif" else "Aktifkan"
        binding.btnActivateAccessibility.isEnabled = !active
    }

    private fun updateProviderLabel() {
        val prefs = getSharedPreferences("auraai_prefs", MODE_PRIVATE)
        val id = prefs.getString("selected_provider", "auto") ?: "auto"
        binding.tvProviderName.text = when (id) {
            "auto"         -> "Auto — Pollinations gratis"
            "gemini"       -> "Gemini 1.5 Flash"
            "groq"         -> "Groq — Llama3"
            "mistral"      -> "Mistral Small"
            "cohere"       -> "Cohere Command-R"
            "together"     -> "Together AI — Llama3.2"
            "openai"       -> "OpenAI GPT-4o Mini"
            "pollinations" -> "Pollinations — Gratis"
            "deepseek"     -> "DeepSeek Chat"
            "claude"       -> "Claude Haiku"
            else           -> id
        }
    }

    private fun canDrawOverlays() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun requestOverlayPermission() {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("Izin diperlukan")
            .setMessage("AuraAI perlu izin 'Tampil di atas app lain' agar bubble robot bisa muncul saat kamu mengerjakan soal.")
            .setPositiveButton("Buka Pengaturan") { _, _ ->
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    REQUEST_OVERLAY
                )
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun startOverlay() {
        try {
            val intent = Intent(this, BubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            updateOverlayStatus()
            moveTaskToBack(true)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopOverlay() {
        stopService(Intent(this, BubbleService::class.java))
        updateOverlayStatus()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) updateOverlayStatus()
    }
}
