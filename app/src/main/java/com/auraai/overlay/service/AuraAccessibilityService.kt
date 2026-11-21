package com.auraai.overlay.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.auraai.overlay.ai.ProviderManager
import com.auraai.overlay.detector.ExamDetector
import com.auraai.overlay.detector.ExamResult
import com.auraai.overlay.engine.AnswerEngine

class AuraAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AuraAccessibilityService? = null
        var autoClickEnabled = false
        var isScanning = false

        fun triggerScan() { instance?.doScan(mode = "auto") }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastScannedText = ""
    private var debounceJob: Runnable? = null

    override fun onServiceConnected() {
        instance = this
        broadcastStatus("connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!autoClickEnabled || isScanning) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // Debounce 1.2s — tunggu layar stabil
        debounceJob?.let { handler.removeCallbacks(it) }
        debounceJob = Runnable { doScan(mode = "auto") }
        handler.postDelayed(debounceJob!!, 1200)
    }

    override fun onInterrupt() {}

    // ── SCAN ─────────────────────────────────────────────────────
    fun doScan(mode: String = "auto") {
        if (isScanning) return
        isScanning = true
        broadcastStatus("scanning")

        Thread {
            try {
                val root = rootInActiveWindow ?: run {
                    isScanning = false
                    broadcastStatus("error:no_window")
                    return@Thread
                }

                val exam = ExamDetector.detect(root)

                if (exam == null || exam.questionText.isBlank()) {
                    isScanning = false
                    broadcastStatus("error:no_question")
                    return@Thread
                }

                // Jangan rescan soal yang sama kalau auto mode
                if (mode == "auto" && exam.questionText == lastScannedText) {
                    isScanning = false
                    broadcastStatus("idle")
                    return@Thread
                }

                lastScannedText = exam.questionText
                broadcastQuestion(exam.questionText, exam.platform)
                broadcastStatus("answering")

                val prefs = getSharedPreferences("auraai_prefs", MODE_PRIVATE)

                // Build prompt sesuai mode
                val answer = when (mode) {
                    "explain" -> {
                        val prompt = buildExplainPrompt(exam)
                        ProviderManager.ask(prefs,
                            com.auraai.overlay.ai.AiProvider.entries.firstOrNull {
                                it.id == (prefs.getString("selected_provider", "auto") ?: "auto")
                            } ?: com.auraai.overlay.ai.AiProvider.AUTO,
                            prompt)
                    }
                    else -> ProviderManager.askExam(prefs, exam)
                }

                broadcastAnswer(answer)

                // Auto-click / auto-type
                if (autoClickEnabled) {
                    handler.post {
                        AnswerEngine.clickAnswer(this, exam, answer)
                    }
                }

                broadcastStatus("done")
            } catch (e: Exception) {
                broadcastStatus("error:${e.message?.take(60)}")
            } finally {
                isScanning = false
            }
        }.start()
    }

    fun doScanWithMode(mode: String) { doScan(mode) }

    // ── ACTIONS ───────────────────────────────────────────────────
    fun clickNode(node: AccessibilityNodeInfo) {
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        var parent = node.parent
        repeat(6) {
            if (parent?.isClickable == true) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            parent = parent?.parent
        }
        // Fallback gesture
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun typeText(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ── PROMPT BUILDERS ───────────────────────────────────────────
    private fun buildExplainPrompt(exam: ExamResult): String {
        val sb = StringBuilder()
        sb.append("Soal: ${exam.questionText}\n")
        if (exam.options.isNotEmpty()) {
            sb.append("Pilihan:\n")
            exam.options.forEachIndexed { i, opt -> sb.append("${('A' + i)}. $opt\n") }
        }
        sb.append("\nBerikan penjelasan lengkap step-by-step mengapa jawaban itu benar dalam Bahasa Indonesia.")
        return sb.toString()
    }

    // ── BROADCAST ─────────────────────────────────────────────────
    private fun broadcastStatus(status: String) =
        sendBroadcast(Intent("com.auraai.AURA_STATUS").putExtra("status", status))

    private fun broadcastQuestion(q: String, platform: String) =
        sendBroadcast(Intent("com.auraai.AURA_QUESTION")
            .putExtra("question", q).putExtra("platform", platform))

    private fun broadcastAnswer(a: String) =
        sendBroadcast(Intent("com.auraai.AURA_ANSWER").putExtra("answer", a))
}
