package com.auraai.overlay.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.auraai.overlay.ai.AiProvider
import com.auraai.overlay.ai.ProviderManager
import com.auraai.overlay.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply { title = "Pengaturan AuraAI"; setDisplayHomeAsUpEnabled(true) }

        val names = AiProvider.entries.map { it.displayName }
        binding.spinnerProvider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

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
        val idx = AiProvider.entries.indexOfFirst { it.id == (p.getString("selected_provider","auto") ?: "auto") }.coerceAtLeast(0)
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
            putString("key_gemini", binding.etGeminiKey.text.toString().trim())
            putString("key_groq", binding.etGroqKey.text.toString().trim())
            putString("key_mistral", binding.etMistralKey.text.toString().trim())
            putString("key_cohere", binding.etCohereKey.text.toString().trim())
            putString("key_together", binding.etTogetherKey.text.toString().trim())
            putString("key_openai", binding.etOpenaiKey.text.toString().trim())
            putString("key_deepseek", binding.etDeepseekKey.text.toString().trim())
            putString("key_claude", binding.etClaudeKey.text.toString().trim())
            putString("selected_provider", AiProvider.entries[binding.spinnerProvider.selectedItemPosition].id)
            putInt("kkm", binding.etKkm.text.toString().toIntOrNull()?.coerceIn(0,100) ?: 75)
            putBoolean("stealth_mode", binding.switchStealth.isChecked)
            putBoolean("lang_id", binding.switchBahasaIndo.isChecked)
            apply()
        }
        Toast.makeText(this, "Disimpan ✓", Toast.LENGTH_SHORT).show()
    }

    private fun testProvider() {
        val p = ProviderManager.getPrefs(this)
        val provider = AiProvider.entries[binding.spinnerProvider.selectedItemPosition]
        Toast.makeText(this, "Testing ${provider.displayName}...", Toast.LENGTH_SHORT).show()
        Thread {
            val result = try { ProviderManager.ask(p, provider, "1 + 1 = ?") } catch (e: Exception) { "ERROR: ${e.message}" }
            runOnUiThread { Toast.makeText(this, "✓ $result", Toast.LENGTH_LONG).show() }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
}
