package com.auraai.overlay.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayStatus()
        updateAccessibilityStatus()
        updateProviderLabel()
    }

    private fun setupUI() {
        // Status card gradient
        binding.statusCard.setCardBackgroundColor(resources.getColor(R.color.purple_primary, theme))

        binding.btnStart.setOnClickListener {
            if (canDrawOverlays()) startOverlay()
            else requestOverlayPermission()
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
            binding.tvStatus.text = "Izin Overlay Diperlukan"
            binding.tvStatusDesc.text = "Tap Mulai untuk izinkan overlay"
            binding.btnStart.text = "Izinkan"
            binding.btnStop.visibility = View.GONE
            binding.btnStart.visibility = View.VISIBLE
            return
        }
        if (BubbleService.isRunning) {
            binding.tvStatus.text = "✅ AuraAI Aktif"
            binding.tvStatusDesc.text = "Robot bubble berjalan di atas semua app"
            binding.btnStart.visibility = View.GONE
            binding.btnStop.visibility = View.VISIBLE
        } else {
            binding.tvStatus.text = "AuraAI Siap"
            binding.tvStatusDesc.text = "Tap Mulai untuk aktifkan bubble"
            binding.btnStart.text = "Mulai"
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
            "Belum aktif — diperlukan untuk auto-jawab"
        binding.btnActivateAccessibility.text = if (active) "✓ Aktif" else "Aktifkan"
        binding.btnActivateAccessibility.isEnabled = !active
    }

    private fun updateProviderLabel() {
        val prefs = getSharedPreferences("auraai_prefs", MODE_PRIVATE)
        val id = prefs.getString("selected_provider", "auto") ?: "auto"
        binding.tvProviderName.text = when (id) {
            "auto"         -> "Auto (Pollinations gratis)"
            "gemini"       -> "Gemini Flash"
            "groq"         -> "Groq — Llama3 (cepat)"
            "mistral"      -> "Mistral Small"
            "cohere"       -> "Cohere Command-R"
            "together"     -> "Together AI — Llama3.2"
            "openai"       -> "OpenAI GPT-4o Mini"
            "pollinations" -> "Pollinations (gratis)"
            else           -> id
        }
    }

    private fun canDrawOverlays() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun requestOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("Izin Overlay Dibutuhkan")
            .setMessage("AuraAI perlu izin 'Tampil di atas app lain' agar bubble robot bisa muncul saat kamu mengerjakan soal ujian.")
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
