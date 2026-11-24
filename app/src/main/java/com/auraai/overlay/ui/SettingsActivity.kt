package com.auraai.overlay.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.auraai.overlay.R
import com.auraai.overlay.ai.AiProvider
import com.auraai.overlay.ai.ProviderManager
import com.auraai.overlay.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

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
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        // Dark spinner adapter
        val names = AiProvider.entries.map { it.displayName }
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            names
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return super.getView(position, convertView, parent).also { v ->
                    (v as? android.widget.TextView)?.apply {
                        setTextColor(Color.parseColor("#F1F5FF"))
                        setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
                    }
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return super.getDropDownView(position, convertView, parent).also { v ->
                    (v as? android.widget.TextView)?.apply {
                        setTextColor(Color.parseColor("#F1F5FF"))
                        setBackgroundColor(Color.parseColor("#161C35"))
                        setPadding(48, 32, 48, 32)
                    }
                }
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProvider.adapter = adapter
        // popup bg set via adapter

        loadSettings()
        setupUI()
    }

    private fun loadSettings() {
        val p = ProviderManager.getPrefs(this)
        binding.etGeminiKey.setText(p.getString("key_gemini", ""))
        binding.etGroqKey.setText(p.getString("key_groq", ""))
        binding.etMistralKey.setText(p.getString("key_mistral", ""))
        binding.etCohereKey.setText(p.getString("key_cohere", ""))
        binding.etTogetherKey.setText(p.getString("key_together", ""))
        binding.etOpenaiKey.setText(p.getString("key_openai", ""))
        binding.etDeepseekKey.setText(p.getString("key_deepseek", ""))
        binding.etClaudeKey.setText(p.getString("key_claude", ""))
        val idx = AiProvider.entries.indexOfFirst {
            it.id == (p.getString("selected_provider", "auto") ?: "auto")
        }.coerceAtLeast(0)
        binding.spinnerProvider.setSelection(idx)
        binding.etKkm.setText(p.getInt("kkm", 75).toString())
        binding.switchStealth.isChecked = p.getBoolean("stealth_mode", false)
        binding.switchBahasaIndo.isChecked = p.getBoolean("lang_id", true)
    }

    private fun setupUI() {
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnTestProvider.setOnClickListener { testProvider() }
    }

    private fun saveSettings() {
        val p = ProviderManager.getPrefs(this)
        p.edit().apply {
            putString("key_gemini",   binding.etGeminiKey.text.toString().trim())
            putString("key_groq",     binding.etGroqKey.text.toString().trim())
            putString("key_mistral",  binding.etMistralKey.text.toString().trim())
            putString("key_cohere",   binding.etCohereKey.text.toString().trim())
            putString("key_together", binding.etTogetherKey.text.toString().trim())
            putString("key_openai",   binding.etOpenaiKey.text.toString().trim())
            putString("key_deepseek", binding.etDeepseekKey.text.toString().trim())
            putString("key_claude",   binding.etClaudeKey.text.toString().trim())
            putString("selected_provider",
                AiProvider.entries[binding.spinnerProvider.selectedItemPosition].id)
            putInt("kkm",
                binding.etKkm.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 75)
            putBoolean("stealth_mode", binding.switchStealth.isChecked)
            putBoolean("lang_id",      binding.switchBahasaIndo.isChecked)
            apply()
        }
        Toast.makeText(this, "✓ Disimpan", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testProvider() {
        val p = ProviderManager.getPrefs(this)
        val provider = AiProvider.entries[binding.spinnerProvider.selectedItemPosition]
        binding.btnTestProvider.isEnabled = false
        binding.btnTestProvider.text = "Testing..."
        Thread {
            val result = try {
                ProviderManager.ask(p, provider, "1 + 1 = ?")
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
            runOnUiThread {
                binding.btnTestProvider.isEnabled = true
                binding.btnTestProvider.text = "Test koneksi"
                val ok = !result.startsWith("ERROR")
                val msg = if (ok) "✓ ${provider.displayName}: $result" else "✗ $result"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
