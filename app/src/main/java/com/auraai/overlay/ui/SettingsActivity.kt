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

        supportActionBar?.apply {
            title = "Pengaturan AuraAI"
            setDisplayHomeAsUpEnabled(true)
        }

        // Setup spinner adapter
        val providerNames = AiProvider.entries.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProvider.adapter = adapter

        loadSettings()
        setupUI()
    }

    private fun loadSettings() {
        val prefs = ProviderManager.getPrefs(this)
        binding.etGeminiKey.setText(prefs.getString("key_gemini", ""))
        binding.etGroqKey.setText(prefs.getString("key_groq", ""))
        binding.etMistralKey.setText(prefs.getString("key_mistral", ""))
        binding.etCohereKey.setText(prefs.getString("key_cohere", ""))
        binding.etTogetherKey.setText(prefs.getString("key_together", ""))
        binding.etOpenaiKey.setText(prefs.getString("key_openai", ""))

        val provider = prefs.getString("selected_provider", "auto") ?: "auto"
        val idx = AiProvider.entries.indexOfFirst { it.id == provider }.coerceAtLeast(0)
        binding.spinnerProvider.setSelection(idx)

        binding.etKkm.setText(prefs.getInt("kkm", 75).toString())
        binding.switchStealth.isChecked = prefs.getBoolean("stealth_mode", false)
        binding.switchBahasaIndo.isChecked = prefs.getBoolean("lang_id", true)
    }

    private fun setupUI() {
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnTestProvider.setOnClickListener { testProvider() }
    }

    private fun saveSettings() {
        val prefs = ProviderManager.getPrefs(this)
        prefs.edit().apply {
            putString("key_gemini", binding.etGeminiKey.text.toString().trim())
            putString("key_groq", binding.etGroqKey.text.toString().trim())
            putString("key_mistral", binding.etMistralKey.text.toString().trim())
            putString("key_cohere", binding.etCohereKey.text.toString().trim())
            putString("key_together", binding.etTogetherKey.text.toString().trim())
            putString("key_openai", binding.etOpenaiKey.text.toString().trim())

            val selectedProvider = AiProvider.entries[binding.spinnerProvider.selectedItemPosition]
            putString("selected_provider", selectedProvider.id)

            val kkm = binding.etKkm.text.toString().toIntOrNull() ?: 75
            putInt("kkm", kkm.coerceIn(0, 100))
            putBoolean("stealth_mode", binding.switchStealth.isChecked)
            putBoolean("lang_id", binding.switchBahasaIndo.isChecked)
            apply()
        }
        Toast.makeText(this, "Pengaturan disimpan ✓", Toast.LENGTH_SHORT).show()
    }

    private fun testProvider() {
        Toast.makeText(this, "Testing provider...", Toast.LENGTH_SHORT).show()
        val prefs = ProviderManager.getPrefs(this)
        val provider = AiProvider.entries[binding.spinnerProvider.selectedItemPosition]
        Thread {
            try {
                val result = ProviderManager.ask(prefs, provider, "Jawab: 1 + 1 = ?")
                runOnUiThread {
                    Toast.makeText(this, "✓ Hasil: $result", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "✗ Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
