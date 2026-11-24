package com.auraai.overlay.engine

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.auraai.overlay.detector.ExamDetector
import com.auraai.overlay.detector.ExamResult
import com.auraai.overlay.service.AuraAccessibilityService

object AnswerEngine {

    private val handler = Handler(Looper.getMainLooper())

    fun clickAnswer(service: AuraAccessibilityService, exam: ExamResult, aiAnswer: String) {
        if (exam.isEssay) {
            handleEssay(service, exam, aiAnswer)
        } else if (exam.optionNodes.isNotEmpty()) {
            handleMultipleChoice(service, exam, aiAnswer)
            // After clicking answer, try to go to next question
            handler.postDelayed({ tryNextQuestion(service, exam) }, 1400)
        }
    }

    // ── ESSAY ─────────────────────────────────────────────────────
    private fun handleEssay(service: AuraAccessibilityService, exam: ExamResult, answer: String) {
        val node = exam.essayNode ?: return
        handler.postDelayed({
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }, 200)
        handler.postDelayed({
            service.typeText(node, answer)
        }, 500)
        handler.postDelayed({ trySubmitOrNext(service, exam) }, 1800)
    }

    // ── MULTIPLE CHOICE ───────────────────────────────────────────
    private fun handleMultipleChoice(service: AuraAccessibilityService, exam: ExamResult, aiAnswer: String) {
        val best = findBestMatch(exam, aiAnswer) ?: return
        handler.postDelayed({
            service.clickNode(best)
        }, 350)
    }

    // ── NAVIGATION ───────────────────────────────────────────────
    fun tryNextQuestion(service: AuraAccessibilityService, exam: ExamResult? = null) {
        // Use pre-detected buttons if available
        val root = service.rootInActiveWindow ?: return

        // Re-detect nav buttons fresh (might have changed after answer click)
        val next = ExamDetector.findNextButton(root)
            ?: exam?.nextButton

        if (next != null) {
            service.clickNode(next)
            return
        }

        // Fallback: tap gesture at common "next" position (bottom-right)
        val dm = service.resources.displayMetrics
        service.tapAt(dm.widthPixels * 0.85f, dm.heightPixels * 0.90f)
    }

    private fun trySubmitOrNext(service: AuraAccessibilityService, exam: ExamResult? = null) {
        val root = service.rootInActiveWindow ?: return
        val submit = ExamDetector.findSubmitButton(root) ?: exam?.submitButton
        if (submit != null) {
            service.clickNode(submit)
            return
        }
        tryNextQuestion(service, exam)
    }

    // ── BEST MATCH ────────────────────────────────────────────────
    fun findBestMatch(exam: ExamResult, aiAnswer: String): AccessibilityNodeInfo? {
        val answer = aiAnswer.trim().lowercase()

        data class ScoredNode(val node: AccessibilityNodeInfo, val score: Int)

        val scored = exam.optionNodes.mapIndexed { idx, node ->
            val text = (node.text?.toString() ?: "").trim().lowercase()
            val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
            val nodeText = if (text.isNotBlank()) text else desc
            var score = 0

            // === Letter match (highest confidence) ===
            val letter = ('a' + idx).toString()
            val num = (idx + 1).toString()

            // Exact letter match: "A", "a"
            if (answer == letter) score += 25
            // Format "A." or "A)"
            if (answer.startsWith("$letter.") || answer.startsWith("$letter)") ||
                answer.startsWith("$letter ")) score += 22
            // "jawaban a", "pilih a", "opsi a"
            listOf("jawaban", "pilih", "opsi", "pilihan", "option", "answer").forEach { kw ->
                if (answer.contains("$kw $letter") || answer.contains("$kw: $letter")) score += 18
            }

            // Numeric match
            if (answer == num) score += 10
            if (answer.startsWith("$num.") || answer.startsWith("$num)")) score += 8

            // === Content match ===
            if (nodeText.isNotBlank()) {
                // Full content in answer
                if (answer.contains(nodeText) && nodeText.length > 5) score += 15
                // Answer text matches option content
                if (nodeText.contains(answer) && answer.length > 5) score += 12

                // Word overlap (fuzzy)
                val aWords = answer.split(Regex("\\s+")).filter { it.length > 3 }.toSet()
                val oWords = nodeText.split(Regex("\\s+")).filter { it.length > 3 }.toSet()
                val overlap = aWords intersect oWords
                score += overlap.size * 5
            }

            ScoredNode(node, score)
        }

        val best = scored.maxByOrNull { it.score }
        return when {
            best == null -> exam.optionNodes.firstOrNull()
            best.score > 0 -> best.node
            // No match found — default to first option
            else -> exam.optionNodes.firstOrNull()
        }
    }
}
