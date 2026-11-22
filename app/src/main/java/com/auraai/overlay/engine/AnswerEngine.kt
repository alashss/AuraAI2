package com.auraai.overlay.engine

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.auraai.overlay.detector.ExamResult
import com.auraai.overlay.service.AuraAccessibilityService

object AnswerEngine {

    private val handler = Handler(Looper.getMainLooper())

    fun clickAnswer(service: AuraAccessibilityService, exam: ExamResult, aiAnswer: String) {
        if (exam.isEssay) {
            handleEssay(service, exam, aiAnswer)
            return
        }
        if (exam.optionNodes.isNotEmpty()) {
            handleMultipleChoice(service, exam, aiAnswer)
        }
        // Setelah jawab, coba next soal (delay 1.2s biar animasi selesai)
        handler.postDelayed({ tryNextQuestion(service) }, 1200)
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
        // Submit/Next setelah ngetik
        handler.postDelayed({ trySubmitOrNext(service) }, 1500)
    }

    // ── MULTIPLE CHOICE ───────────────────────────────────────────
    private fun handleMultipleChoice(service: AuraAccessibilityService, exam: ExamResult, aiAnswer: String) {
        val best = findBestMatch(exam, aiAnswer) ?: return
        handler.postDelayed({
            service.clickNode(best)
        }, 400)
    }

    // ── NEXT QUESTION ─────────────────────────────────────────────
    fun tryNextQuestion(service: AuraAccessibilityService) {
        val root = service.rootInActiveWindow ?: return
        val next = findNextButton(root)
        if (next != null) {
            service.clickNode(next)
        }
    }

    private fun trySubmitOrNext(service: AuraAccessibilityService) {
        val root = service.rootInActiveWindow ?: return
        // Coba submit dulu, kalau gak ada cari next
        val submit = findSubmitButton(root)
        if (submit != null) {
            service.clickNode(submit)
            return
        }
        val next = findNextButton(root)
        if (next != null) service.clickNode(next)
    }

    // ── FIND NEXT BUTTON ─────────────────────────────────────────
    private fun findNextButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nextKeywords = listOf(
            "next", "lanjut", "selanjutnya", "berikutnya",
            "→", "›", ">>", "next question", "soal berikutnya",
            "lanjutkan", "continue", "next soal"
        )
        return findButtonByKeywords(root, nextKeywords)
    }

    private fun findSubmitButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val submitKeywords = listOf(
            "submit", "selesai", "kirim", "finish",
            "done", "simpan jawaban", "akhiri ujian"
        )
        return findButtonByKeywords(root, submitKeywords)
    }

    private fun findButtonByKeywords(
        root: AccessibilityNodeInfo,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        // BFS biar nemuin yang paling atas dulu
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = (node.text?.toString() ?: "").lowercase().trim()
            val desc = (node.contentDescription?.toString() ?: "").lowercase().trim()
            val combined = "$text $desc"
            if (keywords.any { combined.contains(it) }) {
                // Cari node yang clickable di sini atau parent-nya
                if (node.isClickable) return node
                var parent = node.parent
                repeat(4) {
                    if (parent?.isClickable == true) return parent
                    parent = parent?.parent
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // ── BEST MATCH ────────────────────────────────────────────────
    private fun findBestMatch(
        exam: ExamResult,
        aiAnswer: String
    ): AccessibilityNodeInfo? {
        val answer = aiAnswer.trim().lowercase()

        data class ScoredNode(val node: AccessibilityNodeInfo, val score: Int)

        val scored = exam.optionNodes.mapIndexed { idx, node ->
            val text = (node.text?.toString() ?: "").trim().lowercase()
            var score = 0

            // Match teks langsung
            if (text.isNotBlank() && answer.contains(text)) score += 10
            if (text.isNotBlank() && text.contains(answer.take(20))) score += 8

            // Match huruf opsi: A/B/C/D
            val letter = ('a' + idx).toString()
            if (answer == letter) score += 20
            if (answer.startsWith("$letter.") || answer.startsWith("$letter)")) score += 15
            if (answer.startsWith("$letter ")) score += 12
            listOf("pilihan", "jawaban", "opsi", "option", "answer").forEach { kw ->
                if (answer.contains("$kw $letter")) score += 10
            }

            // Match angka opsi: 1/2/3/4
            val num = (idx + 1).toString()
            if (answer == num) score += 8
            if (answer.startsWith("$num.") || answer.startsWith("$num)")) score += 6

            // Fuzzy word match
            val aWords = answer.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
            val oWords = text.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
            score += (aWords intersect oWords).size * 4

            ScoredNode(node, score)
        }

        return scored.maxByOrNull { it.score }
            ?.takeIf { it.score > 0 }
            ?.node
            ?: exam.optionNodes.firstOrNull()
    }
}
