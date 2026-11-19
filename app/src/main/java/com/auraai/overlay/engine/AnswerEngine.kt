package com.auraai.overlay.engine

import android.os.Handler
import android.os.Looper
import com.auraai.overlay.detector.ExamResult
import com.auraai.overlay.service.AuraAccessibilityService

object AnswerEngine {

    private val handler = Handler(Looper.getMainLooper())

    fun clickAnswer(
        service: AuraAccessibilityService,
        exam: ExamResult,
        aiAnswer: String
    ) {
        if (exam.isEssay) {
            // Essay: type ke EditText
            exam.essayNode?.let { node ->
                handler.postDelayed({
                    service.typeText(node, aiAnswer)
                }, 300)
            }
            return
        }

        if (exam.optionNodes.isEmpty()) return

        // Cari opsi yang paling cocok dengan jawaban AI
        val bestNode = findBestMatch(exam, aiAnswer)

        if (bestNode != null) {
            handler.postDelayed({
                service.clickNode(bestNode)
            }, 500) // delay 500ms biar natural
        }
    }

    private fun findBestMatch(exam: ExamResult, aiAnswer: String): android.view.accessibility.AccessibilityNodeInfo? {
        val answer = aiAnswer.trim().lowercase()

        // Skor tiap opsi
        data class ScoredNode(
            val node: android.view.accessibility.AccessibilityNodeInfo,
            val score: Int
        )

        val scored = exam.optionNodes.mapIndexed { idx, node ->
            val text = (node.text?.toString() ?: "").trim().lowercase()
            val label = text.take(2) // "a." "b." dll
            var score = 0

            // Match langsung: jawaban AI mengandung teks opsi
            if (text.isNotBlank() && answer.contains(text)) score += 10
            if (text.isNotBlank() && text.contains(answer)) score += 8

            // Match huruf opsi: AI jawab "A" atau "a)" atau "pilihan A"
            val optionLetter = ('a' + idx).toString()
            if (answer == optionLetter) score += 15
            if (answer.startsWith("$optionLetter.") || answer.startsWith("$optionLetter)")) score += 12
            if (answer.contains("pilihan $optionLetter")) score += 10
            if (answer.contains("jawaban $optionLetter")) score += 10
            if (answer.contains("opsi $optionLetter")) score += 10

            // Match nomor: "1" = opsi pertama
            val optionNum = (idx + 1).toString()
            if (answer == optionNum) score += 8
            if (answer.startsWith("$optionNum.") || answer.startsWith("$optionNum)")) score += 6

            // Fuzzy: hitung kata yang sama
            val answerWords = answer.split(" ").filter { it.length > 2 }.toSet()
            val optionWords = text.split(" ").filter { it.length > 2 }.toSet()
            score += (answerWords intersect optionWords).size * 3

            ScoredNode(node, score)
        }

        return scored.maxByOrNull { it.score }
            ?.takeIf { it.score > 0 }
            ?.node
            ?: exam.optionNodes.firstOrNull() // fallback ke opsi pertama
    }
}
