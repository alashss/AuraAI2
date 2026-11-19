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

        // dipanggil dari BubbleService saat user tap "Scan"
        fun triggerScan() {
            instance?.doScan()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastScannedText = ""

    override fun onServiceConnected() {
        instance = this
        broadcastStatus("connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // auto-scan saat konten window berubah (soal baru muncul)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (autoClickEnabled && !isScanning) {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ doScan() }, 800) // debounce 800ms
            }
        }
    }

    override fun onInterrupt() {}

    // ── SCAN UTAMA ───────────────────────────────────────────────
    fun doScan() {
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

                // Detect platform + ambil soal dari node tree
                val examResult = ExamDetector.detect(root)

                if (examResult == null || examResult.questionText.isBlank()) {
                    isScanning = false
                    broadcastStatus("error:no_question")
                    return@Thread
                }

                // Kalau soal sama dengan scan terakhir dan auto-click off, skip
                if (examResult.questionText == lastScannedText && !autoClickEnabled) {
                    isScanning = false
                    broadcastStatus("idle")
                    return@Thread
                }

                lastScannedText = examResult.questionText
                broadcastQuestion(examResult.questionText, examResult.platform)
                broadcastStatus("answering")

                // Tanya AI
                val prefs = getSharedPreferences("auraai_prefs", MODE_PRIVATE)
                val answer = ProviderManager.askExam(prefs, examResult)

                broadcastAnswer(answer)

                // Auto-click kalau enabled
                if (autoClickEnabled && examResult.optionNodes.isNotEmpty()) {
                    handler.post {
                        AnswerEngine.clickAnswer(this, examResult, answer)
                    }
                }

                broadcastStatus("done")
            } catch (e: Exception) {
                broadcastStatus("error:${e.message}")
            } finally {
                isScanning = false
            }
        }.start()
    }

    // ── CLICK NODE ───────────────────────────────────────────────
    fun clickNode(node: AccessibilityNodeInfo) {
        // Coba performAction dulu
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        // Kalau node bukan clickable, cari parent yang clickable
        var parent = node.parent
        repeat(5) {
            if (parent?.isClickable == true) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            parent = parent?.parent
        }
        // Fallback: gesture tap ke tengah bounds node
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
    }

    fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun typeText(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ── BROADCAST ke BubbleService ────────────────────────────────
    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent("com.auraai.AURA_STATUS").apply {
            putExtra("status", status)
        })
    }

    private fun broadcastQuestion(question: String, platform: String) {
        sendBroadcast(Intent("com.auraai.AURA_QUESTION").apply {
            putExtra("question", question)
            putExtra("platform", platform)
        })
    }

    private fun broadcastAnswer(answer: String) {
        sendBroadcast(Intent("com.auraai.AURA_ANSWER").apply {
            putExtra("answer", answer)
        })
    }
}
