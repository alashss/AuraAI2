package com.auraai.overlay.ai

import android.content.SharedPreferences
import com.auraai.overlay.detector.ExamResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ProviderManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun getPrefs(context: android.content.Context): SharedPreferences =
        context.getSharedPreferences("auraai_prefs", android.content.Context.MODE_PRIVATE)

    // ── EXAM-SPECIFIC ENTRY POINT ─────────────────────────────────
    fun askExam(prefs: SharedPreferences, exam: ExamResult): String {
        val providerId = prefs.getString("selected_provider", "auto") ?: "auto"
        val provider = AiProvider.entries.firstOrNull { it.id == providerId } ?: AiProvider.AUTO
        val prompt = buildExamPrompt(exam)
        return ask(prefs, provider, prompt)
    }

    fun buildExamPrompt(exam: ExamResult): String {
        val sb = StringBuilder()
        sb.append("Platform: ${exam.platform}\n")
        sb.append("Soal: ${exam.questionText}\n")

        if (exam.isEssay || exam.options.isEmpty()) {
            sb.append("\nIni soal essay. Berikan jawaban lengkap dan benar dalam Bahasa Indonesia.")
        } else {
            sb.append("\nPilihan jawaban:\n")
            exam.options.forEachIndexed { i, opt ->
                sb.append("${('A' + i)}. $opt\n")
            }
            sb.append("\nJawab HANYA dengan huruf pilihan yang benar (A/B/C/D) diikuti teks jawaban. Contoh: 'A. teks opsi'")
        }
        return sb.toString()
    }

    // ── GENERAL ENTRY POINT ───────────────────────────────────────
    fun ask(prefs: SharedPreferences, provider: AiProvider, prompt: String): String {
        return when (provider) {
            AiProvider.AUTO -> askCascade(prefs, prompt)
            AiProvider.POLLINATIONS -> askPollinations(prompt)
            AiProvider.GEMINI -> askGemini(prefs, prompt)
            AiProvider.GROQ -> askGroq(prefs, prompt)
            AiProvider.MISTRAL -> askMistral(prefs, prompt)
            AiProvider.COHERE -> askCohere(prefs, prompt)
            AiProvider.TOGETHER -> askTogether(prefs, prompt)
            AiProvider.OPENAI -> askOpenAI(prefs, prompt)
        }
    }

    private fun askCascade(prefs: SharedPreferences, prompt: String): String {
        val fns: List<() -> String> = listOf(
            { askPollinations(prompt) },
            { askGemini(prefs, prompt) },
            { askGroq(prefs, prompt) },
            { askMistral(prefs, prompt) },
            { askCohere(prefs, prompt) },
            { askTogether(prefs, prompt) },
        )
        for (fn in fns) {
            try {
                val r = fn()
                if (r.isNotBlank() && !r.startsWith("ERROR")) return r
            } catch (_: Exception) {}
        }
        return "Tidak dapat terhubung ke AI."
    }

    // ── POLLINATIONS (no key) ─────────────────────────────────────
    private fun askPollinations(prompt: String): String {
        val body = JSONObject().apply {
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "Kamu asisten ujian. Jawab soal dengan akurat dan ringkas.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("model", "openai")
            put("jsonMode", false)
        }.toString()

        val req = Request.Builder()
            .url("https://text.pollinations.ai/openai")
            .post(body.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()

        val resp = client.newCall(req).execute()
        val json = JSONObject(resp.body?.string() ?: return "ERROR")
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    // ── GEMINI ───────────────────────────────────────────────────
    private fun askGemini(prefs: SharedPreferences, prompt: String): String {
        val key = prefs.getString("key_gemini", "") ?: return "ERROR: no key"
        if (key.isBlank()) return "ERROR: no key"

        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 512)
                put("temperature", 0.1)
            })
        }.toString()

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=$key")
            .post(body.toRequestBody(JSON))
            .build()

        val resp = client.newCall(req).execute()
        val json = JSONObject(resp.body?.string() ?: return "ERROR")
        return json.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
            .trim()
    }

    // ── OPENAI-COMPAT (Groq, Mistral, Together, OpenAI) ──────────
    private fun askOpenAICompat(url: String, key: String, model: String, prompt: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "Kamu asisten ujian cerdas. Jawab soal akurat dan ringkas dalam Bahasa Indonesia.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("max_tokens", 512)
            put("temperature", 0.1)
        }.toString()

        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .header("Authorization", "Bearer $key")
            .build()

        val resp = client.newCall(req).execute()
        val json = JSONObject(resp.body?.string() ?: return "ERROR")
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    private fun askGroq(prefs: SharedPreferences, prompt: String): String {
        val key = prefs.getString("key_groq", "") ?: return "ERROR"
        if (key.isBlank()) return "ERROR"
        return askOpenAICompat("https://api.groq.com/openai/v1/chat/completions", key, "llama3-8b-8192", prompt)
    }

    private fun askMistral(prefs: SharedPreferences, prompt: String): String {
        val key = prefs.getString("key_mistral", "") ?: return "ERROR"
        if (key.isBlank()) return "ERROR"
        return askOpenAICompat("https://api.mistral.ai/v1/chat/completions", key, "mistral-small-latest", prompt)
    }

    private fun askTogether(prefs: SharedPreferences, prompt: String): String {
        val key = prefs.getString("key_together", "") ?: return "ERROR"
        if (key.isBlank()) return "ERROR"
        return askOpenAICompat("https://api.together.xyz/v1/chat/completions", key, "meta-llama/Llama-3.2-11B-Vision-Instruct-Turbo", prompt)
    }

    private fun askOpenAI(prefs: SharedPreferences, prompt: String): String {
        val key = prefs.getString("key_openai", "") ?: return "ERROR"
        if (key.isBlank()) return "ERROR"
        return askOpenAICompat("https://api.openai.com/v1/chat/completions", key, "gpt-4o-mini", prompt)
    }

    private fun askCohere(prefs: SharedPreferences, prompt: String): String {
        val key = prefs.getString("key_cohere", "") ?: return "ERROR"
        if (key.isBlank()) return "ERROR"

        val body = JSONObject().apply {
            put("model", "command-r")
            put("message", prompt)
            put("max_tokens", 512)
            put("temperature", 0.1)
        }.toString()

        val req = Request.Builder()
            .url("https://api.cohere.ai/v1/chat")
            .post(body.toRequestBody(JSON))
            .header("Authorization", "Bearer $key")
            .build()

        val resp = client.newCall(req).execute()
        return JSONObject(resp.body?.string() ?: return "ERROR")
            .getString("text").trim()
    }
}
