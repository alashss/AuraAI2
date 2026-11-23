package com.auraai.overlay.ai

import android.content.Context
import android.content.SharedPreferences
import com.auraai.overlay.detector.ExamResult
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ProviderManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun getPrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("auraai_prefs", Context.MODE_PRIVATE)

    fun askExam(prefs: SharedPreferences, exam: ExamResult): String {
        val providerId = prefs.getString("selected_provider", "auto") ?: "auto"
        val provider = AiProvider.entries.firstOrNull { it.id == providerId } ?: AiProvider.AUTO
        return ask(prefs, provider, buildExamPrompt(exam, prefs))
    }

    fun buildExamPrompt(exam: ExamResult, prefs: SharedPreferences): String {
        val sb = StringBuilder()
        if (exam.isEssay || exam.options.isEmpty()) {
            sb.append("Soal essay ujian. Jawab SINGKAT LANGSUNG maksimal 2 kalimat. TANPA kata pengantar.\n\nSoal: ${exam.questionText}")
        } else {
            sb.append("Soal pilihan ganda. Jawab HANYA dengan format 'A. teks jawaban'. TANPA penjelasan.\n\nSoal: ${exam.questionText}\n\nPilihan:\n")
            exam.options.forEachIndexed { i, opt -> sb.append("${('A' + i)}. $opt\n") }
        }
        return sb.toString()
    }

    fun ask(prefs: SharedPreferences, provider: AiProvider, prompt: String): String {
        return try {
            when (provider) {
                AiProvider.AUTO         -> cascade(prefs, prompt)
                AiProvider.POLLINATIONS -> pollinations(prompt)
                AiProvider.GEMINI       -> gemini(prefs, prompt)
                AiProvider.GROQ         -> openaiCompat("https://api.groq.com/openai/v1/chat/completions",
                    prefs.getString("key_groq","") ?: "", "llama3-8b-8192", prompt)
                AiProvider.DEEPSEEK     -> openaiCompat("https://api.deepseek.com/v1/chat/completions",
                    prefs.getString("key_deepseek","") ?: "", "deepseek-chat", prompt)
                AiProvider.CLAUDE       -> claude(prefs, prompt)
                AiProvider.MISTRAL      -> openaiCompat("https://api.mistral.ai/v1/chat/completions",
                    prefs.getString("key_mistral","") ?: "", "mistral-small-latest", prompt)
                AiProvider.OPENAI       -> openaiCompat("https://api.openai.com/v1/chat/completions",
                    prefs.getString("key_openai","") ?: "", "gpt-4o-mini", prompt)
                AiProvider.TOGETHER     -> openaiCompat("https://api.together.xyz/v1/chat/completions",
                    prefs.getString("key_together","") ?: "", "meta-llama/Llama-3.2-11B-Vision-Instruct-Turbo", prompt)
                AiProvider.COHERE       -> cohere(prefs, prompt)
            }
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    private fun cascade(prefs: SharedPreferences, prompt: String): String {
        val fns: List<() -> String> = listOf(
            { pollinations(prompt) },
            { openaiCompat("https://api.groq.com/openai/v1/chat/completions",
                prefs.getString("key_groq","") ?: "", "llama3-8b-8192", prompt) },
            { openaiCompat("https://api.deepseek.com/v1/chat/completions",
                prefs.getString("key_deepseek","") ?: "", "deepseek-chat", prompt) },
            { gemini(prefs, prompt) },
        )
        for (fn in fns) {
            runCatching {
                val r = fn()
                if (r.isNotBlank() && !r.startsWith("ERROR")) return r
            }
        }
        return "Gagal terhubung ke AI. Set API key di Pengaturan."
    }

    private fun pollinations(prompt: String): String {
        val body = JSONObject().apply {
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role","system"); put("content","Jawab soal ujian singkat dan benar dalam Bahasa Indonesia.") })
                put(JSONObject().apply { put("role","user"); put("content",prompt) })
            })
            put("model","openai"); put("jsonMode", false)
        }.toString()
        val resp = client.newCall(Request.Builder()
            .url("https://text.pollinations.ai/openai")
            .post(body.toRequestBody(JSON)).build()).execute()
        val rb = resp.body?.string() ?: return "ERROR: empty"
        if (!resp.isSuccessful) return "ERROR: HTTP ${resp.code}"
        return JSONObject(rb).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content").trim()
    }

    private fun gemini(prefs: SharedPreferences, prompt: String): String {
        val key = prefs.getString("key_gemini","") ?: ""
        if (key.isBlank()) return "ERROR: no gemini key"
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text",prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply { put("maxOutputTokens",256); put("temperature",0.1) })
        }.toString()
        val resp = client.newCall(Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=$key")
            .post(body.toRequestBody(JSON)).build()).execute()
        val rb = resp.body?.string() ?: return "ERROR: empty"
        if (!resp.isSuccessful) return "ERROR: HTTP ${resp.code}"
        return JSONObject(rb).getJSONArray("candidates")
            .getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text").trim()
    }

    private fun claude(prefs: SharedPreferences, prompt: String): String {
        val key = prefs.getString("key_claude","") ?: ""
        if (key.isBlank()) return "ERROR: no claude key"
        val body = JSONObject().apply {
            put("model","claude-haiku-20240307")
            put("max_tokens", 256)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role","user"); put("content",prompt) })
            })
        }.toString()
        val resp = client.newCall(Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(body.toRequestBody(JSON))
            .header("x-api-key", key)
            .header("anthropic-version","2023-06-01").build()).execute()
        val rb = resp.body?.string() ?: return "ERROR: empty"
        if (!resp.isSuccessful) return "ERROR: HTTP ${resp.code}"
        return JSONObject(rb).getJSONArray("content")
            .getJSONObject(0).getString("text").trim()
    }

    private fun openaiCompat(url: String, key: String, model: String, prompt: String): String {
        if (key.isBlank()) return "ERROR: no key"
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role","system"); put("content","Jawab soal ujian singkat dan benar dalam Bahasa Indonesia.") })
                put(JSONObject().apply { put("role","user"); put("content",prompt) })
            })
            put("max_tokens",256); put("temperature",0.1)
        }.toString()
        val resp = client.newCall(Request.Builder().url(url)
            .post(body.toRequestBody(JSON))
            .header("Authorization","Bearer $key").build()).execute()
        val rb = resp.body?.string() ?: return "ERROR: empty"
        if (!resp.isSuccessful) return "ERROR: HTTP ${resp.code}"
        return JSONObject(rb).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content").trim()
    }

    private fun cohere(prefs: SharedPreferences, prompt: String): String {
        val key = prefs.getString("key_cohere","") ?: ""
        if (key.isBlank()) return "ERROR: no cohere key"
        val body = JSONObject().apply {
            put("model","command-r"); put("message",prompt)
            put("max_tokens",256); put("temperature",0.1)
        }.toString()
        val resp = client.newCall(Request.Builder()
            .url("https://api.cohere.ai/v1/chat")
            .post(body.toRequestBody(JSON))
            .header("Authorization","Bearer $key").build()).execute()
        val rb = resp.body?.string() ?: return "ERROR: empty"
        if (!resp.isSuccessful) return "ERROR: HTTP ${resp.code}"
        return JSONObject(rb).getString("text").trim()
    }
}
