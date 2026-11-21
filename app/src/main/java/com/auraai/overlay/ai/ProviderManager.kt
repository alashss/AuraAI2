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

    // ── EXAM ENTRY POINT ─────────────────────────────────────────
    fun askExam(prefs: SharedPreferences, exam: ExamResult): String {
        val providerId = prefs.getString("selected_provider", "auto") ?: "auto"
        val provider = AiProvider.entries.firstOrNull { it.id == providerId } ?: AiProvider.AUTO
        val prompt = buildExamPrompt(exam, prefs)
        return ask(prefs, provider, prompt)
    }

    private fun buildExamPrompt(exam: ExamResult, prefs: SharedPreferences): String {
        val useBahasa = prefs.getBoolean("lang_id", true)
        val sb = StringBuilder()

        if (exam.isEssay || exam.options.isEmpty()) {
            if (useBahasa) {
                sb.append("Ini soal essay ujian. Jawab SINGKAT dan LANGSUNG ke intinya. Maksimal 3 kalimat. Jangan pakai kata pengantar.\n\n")
                sb.append("Soal: ${exam.questionText}")
            } else {
                sb.append("Essay exam question. Answer BRIEFLY and DIRECTLY. Max 3 sentences. No preamble.\n\nQuestion: ${exam.questionText}")
            }
        } else {
            if (useBahasa) {
                sb.append("Soal pilihan ganda. Jawab HANYA dengan huruf dan teks jawaban yang benar. Contoh: 'B. 40'. Tanpa penjelasan.\n\n")
                sb.append("Soal: ${exam.questionText}\n\nPilihan:\n")
            } else {
                sb.append("Multiple choice. Answer ONLY with the letter and text. Example: 'B. 40'. No explanation.\n\nQuestion: ${exam.questionText}\n\nOptions:\n")
            }
            exam.options.forEachIndexed { i, opt -> sb.append("${('A' + i)}. $opt\n") }
        }
        return sb.toString()
    }

    // ── GENERAL ───────────────────────────────────────────────────
    fun ask(prefs: SharedPreferences, provider: AiProvider, prompt: String): String {
        return try {
            when (provider) {
                AiProvider.AUTO         -> cascade(prefs, prompt)
                AiProvider.POLLINATIONS -> pollinations(prompt)
                AiProvider.GEMINI       -> gemini(prefs, prompt)
                AiProvider.GROQ         -> openaiCompat("https://api.groq.com/openai/v1/chat/completions",
                    prefs.getString("key_groq","") ?: "", "llama3-8b-8192", prompt)
                AiProvider.MISTRAL      -> openaiCompat("https://api.mistral.ai/v1/chat/completions",
                    prefs.getString("key_mistral","") ?: "", "mistral-small-latest", prompt)
                AiProvider.COHERE       -> cohere(prefs, prompt)
                AiProvider.TOGETHER     -> openaiCompat("https://api.together.xyz/v1/chat/completions",
                    prefs.getString("key_together","") ?: "", "meta-llama/Llama-3.2-11B-Vision-Instruct-Turbo", prompt)
                AiProvider.OPENAI       -> openaiCompat("https://api.openai.com/v1/chat/completions",
                    prefs.getString("key_openai","") ?: "", "gpt-4o-mini", prompt)
            }
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    private fun cascade(prefs: SharedPreferences, prompt: String): String {
        val fns: List<() -> String> = listOf(
            { pollinations(prompt) },
            { gemini(prefs, prompt) },
            { openaiCompat("https://api.groq.com/openai/v1/chat/completions",
                prefs.getString("key_groq","") ?: "", "llama3-8b-8192", prompt) },
            { openaiCompat("https://api.mistral.ai/v1/chat/completions",
                prefs.getString("key_mistral","") ?: "", "mistral-small-latest", prompt) },
        )
        for (fn in fns) {
            runCatching {
                val r = fn()
                if (r.isNotBlank() && !r.startsWith("ERROR")) return r
            }
        }
        return "Tidak dapat terhubung ke AI. Set API key di Pengaturan."
    }

    // ── POLLINATIONS (free, no key) ───────────────────────────────
    private fun pollinations(prompt: String): String {
        val body = JSONObject().apply {
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role","system"); put("content","Jawab soal ujian singkat dan benar.") })
                put(JSONObject().apply { put("role","user"); put("content",prompt) })
            })
            put("model","openai"); put("jsonMode", false)
        }.toString()
        val req = Request.Builder()
            .url("https://text.pollinations.ai/openai")
            .post(body.toRequestBody(JSON))
            .build()
        val resp = client.newCall(req).execute()
        val rb = resp.body?.string() ?: return "ERROR: empty"
        if (!resp.isSuccessful) return "ERROR: HTTP ${resp.code}"
        return JSONObject(rb).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content").trim()
    }

    // ── GEMINI ───────────────────────────────────────────────────
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
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens",256); put("temperature",0.1)
            })
        }.toString()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=$key")
            .post(body.toRequestBody(JSON)).build()
        val resp = client.newCall(req).execute()
        val rb = resp.body?.string() ?: return "ERROR: empty"
        if (!resp.isSuccessful) return "ERROR: HTTP ${resp.code}"
        return JSONObject(rb).getJSONArray("candidates")
            .getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text").trim()
    }

    // ── OPENAI-COMPAT ─────────────────────────────────────────────
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
        val req = Request.Builder().url(url)
            .post(body.toRequestBody(JSON))
            .header("Authorization","Bearer $key").build()
        val resp = client.newCall(req).execute()
        val rb = resp.body?.string() ?: return "ERROR: empty"
        if (!resp.isSuccessful) return "ERROR: HTTP ${resp.code}"
        return JSONObject(rb).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content").trim()
    }

    // ── COHERE ───────────────────────────────────────────────────
    private fun cohere(prefs: SharedPreferences, prompt: String): String {
        val key = prefs.getString("key_cohere","") ?: ""
        if (key.isBlank()) return "ERROR: no cohere key"
        val body = JSONObject().apply {
            put("model","command-r"); put("message",prompt)
            put("max_tokens",256); put("temperature",0.1)
        }.toString()
        val req = Request.Builder()
            .url("https://api.cohere.ai/v1/chat")
            .post(body.toRequestBody(JSON))
            .header("Authorization","Bearer $key").build()
        val resp = client.newCall(req).execute()
        val rb = resp.body?.string() ?: return "ERROR: empty"
        if (!resp.isSuccessful) return "ERROR: HTTP ${resp.code}"
        return JSONObject(rb).getString("text").trim()
    }
}
