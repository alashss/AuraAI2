package com.auraai.overlay.detector

import android.view.accessibility.AccessibilityNodeInfo

data class ExamResult(
    val platform: String,
    val questionText: String,
    val options: List<String>,          // teks opsi A/B/C/D
    val optionNodes: List<AccessibilityNodeInfo>, // node buat di-klik
    val essayNode: AccessibilityNodeInfo? = null, // kalau essay
    val isEssay: Boolean = false
)

object ExamDetector {

    fun detect(root: AccessibilityNodeInfo): ExamResult? {
        val pkg = root.packageName?.toString() ?: ""
        val allText = extractAllText(root)

        return when {
            isWayground(pkg, allText)     -> detectWayground(root, allText)
            isKahoot(pkg, allText)        -> detectKahoot(root, allText)
            isGoogleForm(pkg, allText)    -> detectGoogleForm(root, allText)
            isExamBrowser(pkg, allText)   -> detectExamBrowser(root, allText)
            isChromeBased(pkg)            -> detectGenericWeb(root, allText)
            else                          -> detectGeneric(root, allText)
        }
    }

    // ── PLATFORM DETECTORS ───────────────────────────────────────

    private fun isWayground(pkg: String, text: String) =
        pkg.contains("wayground", true) ||
        text.contains("wayground", true)

    private fun isKahoot(pkg: String, text: String) =
        pkg.contains("kahoot", true) ||
        text.contains("kahoot", true)

    private fun isGoogleForm(pkg: String, text: String) =
        pkg.contains("google", true) && (
            text.contains("google form", true) ||
            text.contains("formulir", true)
        ) || pkg.contains("docs.google", true)

    private fun isExamBrowser(pkg: String, text: String) =
        pkg.contains("exambrowser", true) ||
        pkg.contains("seb", true) || // Safe Exam Browser
        text.contains("exambrowser", true)

    private fun isChromeBased(pkg: String) =
        pkg.contains("chrome", true) ||
        pkg.contains("browser", true) ||
        pkg.contains("firefox", true) ||
        pkg.contains("webview", true)

    // ── WAYGROUND ─────────────────────────────────────────────────
    private fun detectWayground(root: AccessibilityNodeInfo, allText: String): ExamResult? {
        // Wayground punya struktur: soal di TextView besar, opsi di bawahnya
        val questionNode = findNodeByClass(root, "android.widget.TextView") { node ->
            val t = node.text?.toString() ?: ""
            t.length > 15 && !t.startsWith("A.") && !t.startsWith("B.")
                && !t.startsWith("C.") && !t.startsWith("D.")
                && !t.contains("Soal ke") && !t.contains("Waktu")
        }
        val question = questionNode?.text?.toString() ?: extractQuestion(allText) ?: return null

        // Opsi: node yang textnya diawali A. B. C. D. atau pakai RadioButton
        val optionNodes = findOptionNodes(root)
        val options = optionNodes.map { it.text?.toString() ?: "" }.filter { it.isNotBlank() }

        // Essay check
        val essayNode = findNodeByClass(root, "android.widget.EditText") { true }
        val isEssay = essayNode != null && optionNodes.isEmpty()

        return ExamResult(
            platform = "Wayground",
            questionText = question,
            options = options,
            optionNodes = optionNodes,
            essayNode = essayNode,
            isEssay = isEssay
        )
    }

    // ── KAHOOT ────────────────────────────────────────────────────
    private fun detectKahoot(root: AccessibilityNodeInfo, allText: String): ExamResult? {
        // Kahoot: soal di heading, 4 opsi warna-warni (merah/biru/kuning/hijau)
        val questionNode = findNodeByContentDesc(root) { desc ->
            desc.length > 10 && !desc.contains("button", true)
        } ?: findNodeByClass(root, "android.widget.TextView") { node ->
            val t = node.text?.toString() ?: ""
            t.length > 10 && !t.all { it.isDigit() }
        }

        val question = questionNode?.text?.toString()
            ?: questionNode?.contentDescription?.toString()
            ?: extractQuestion(allText)
            ?: return null

        // Opsi Kahoot: 4 button dengan teks jawaban
        val optionNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllClickable(root, optionNodes, limit = 4)

        val options = optionNodes.map {
            it.text?.toString() ?: it.contentDescription?.toString() ?: ""
        }.filter { it.isNotBlank() }

        return ExamResult(
            platform = "Kahoot",
            questionText = question,
            options = options,
            optionNodes = optionNodes
        )
    }

    // ── GOOGLE FORM ───────────────────────────────────────────────
    private fun detectGoogleForm(root: AccessibilityNodeInfo, allText: String): ExamResult? {
        val question = extractQuestion(allText) ?: return null

        // Google Form: opsi di RadioButton atau CheckBox
        val radioNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByClass(root, "android.widget.RadioButton", radioNodes)

        val checkNodes = mutableListOf<AccessibilityNodeInfo>()
        if (radioNodes.isEmpty()) {
            findNodesByClass(root, "android.widget.CheckBox", checkNodes)
        }

        val optionNodes = if (radioNodes.isNotEmpty()) radioNodes else checkNodes
        val options = optionNodes.map { it.text?.toString() ?: "" }.filter { it.isNotBlank() }

        // Essay
        val essayNode = findNodeByClass(root, "android.widget.EditText") { true }
        val isEssay = essayNode != null && optionNodes.isEmpty()

        return ExamResult(
            platform = "Google Form",
            questionText = question,
            options = options,
            optionNodes = optionNodes,
            essayNode = essayNode,
            isEssay = isEssay
        )
    }

    // ── EXAMBROWSER ───────────────────────────────────────────────
    private fun detectExamBrowser(root: AccessibilityNodeInfo, allText: String): ExamResult? {
        // ExamBrowser biasanya WebView — parse dari teks
        val question = extractQuestion(allText) ?: return null
        val optionNodes = findOptionNodes(root)
        val options = optionNodes.map { it.text?.toString() ?: "" }.filter { it.isNotBlank() }

        return ExamResult(
            platform = "ExamBrowser",
            questionText = question,
            options = options,
            optionNodes = optionNodes
        )
    }

    // ── GENERIC WEB / BROWSER ─────────────────────────────────────
    private fun detectGenericWeb(root: AccessibilityNodeInfo, allText: String): ExamResult? {
        val question = extractQuestion(allText) ?: return null
        val optionNodes = findOptionNodes(root)
        val options = optionNodes.map { it.text?.toString() ?: "" }.filter { it.isNotBlank() }
        val essayNode = findNodeByClass(root, "android.widget.EditText") { true }

        return ExamResult(
            platform = "Browser",
            questionText = question,
            options = options,
            optionNodes = optionNodes,
            essayNode = essayNode,
            isEssay = essayNode != null && optionNodes.isEmpty()
        )
    }

    // ── GENERIC FALLBACK ──────────────────────────────────────────
    private fun detectGeneric(root: AccessibilityNodeInfo, allText: String): ExamResult? {
        val question = extractQuestion(allText) ?: return null
        val optionNodes = findOptionNodes(root)
        val options = optionNodes.map { it.text?.toString() ?: "" }.filter { it.isNotBlank() }
        val essayNode = findNodeByClass(root, "android.widget.EditText") { true }

        return ExamResult(
            platform = "Unknown",
            questionText = question,
            options = options,
            optionNodes = optionNodes,
            essayNode = essayNode,
            isEssay = essayNode != null && optionNodes.isEmpty()
        )
    }

    // ── HELPERS ───────────────────────────────────────────────────

    private fun extractAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            n.text?.let { sb.append(it).append("\n") }
            n.contentDescription?.let { sb.append(it).append("\n") }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(node)
        return sb.toString()
    }

    private fun extractQuestion(allText: String): String? {
        val lines = allText.lines()
            .map { it.trim() }
            .filter { it.length > 10 }

        // Cari baris yang paling mungkin jadi soal:
        // - Bukan opsi (tidak diawali A. B. 1. 2. dll)
        // - Punya tanda tanya atau cukup panjang
        val optionPrefixes = listOf("a.", "b.", "c.", "d.", "1.", "2.", "3.", "4.",
            "a)", "b)", "c)", "d)", "1)", "2)", "3)", "4)")

        return lines.firstOrNull { line ->
            val lower = line.lowercase()
            optionPrefixes.none { lower.startsWith(it) } &&
            line.length > 15 &&
            !line.all { it.isDigit() } &&
            !lower.contains("waktu") &&
            !lower.contains("soal ke") &&
            !lower.contains("timer") &&
            !lower.contains("score") &&
            !lower.contains("nilai")
        }
    }

    private fun findOptionNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()

        // 1. RadioButton
        findNodesByClass(root, "android.widget.RadioButton", results)
        if (results.isNotEmpty()) return results

        // 2. CheckBox
        findNodesByClass(root, "android.widget.CheckBox", results)
        if (results.isNotEmpty()) return results

        // 3. TextView yang diawali A. B. C. D.
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            val text = node.text?.toString()?.trim() ?: ""
            val lower = text.lowercase()
            if (lower.matches(Regex("^[a-d][.)].+")) ||
                text.matches(Regex("^[1-4][.)].+"))) {
                results.add(node)
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        if (results.isNotEmpty()) return results

        // 4. Clickable nodes dalam jumlah 2-5 (kemungkinan opsi)
        findAllClickable(root, results, limit = 5)
        return results
    }

    private fun findNodeByClass(
        root: AccessibilityNodeInfo,
        className: String,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (root.className?.toString() == className && predicate(root)) return root
        for (i in 0 until root.childCount) {
            val result = findNodeByClass(root.getChild(i) ?: continue, className, predicate)
            if (result != null) return result
        }
        return null
    }

    private fun findNodeByContentDesc(
        root: AccessibilityNodeInfo,
        predicate: (String) -> Boolean
    ): AccessibilityNodeInfo? {
        val desc = root.contentDescription?.toString() ?: ""
        if (desc.isNotBlank() && predicate(desc)) return root
        for (i in 0 until root.childCount) {
            val result = findNodeByContentDesc(root.getChild(i) ?: continue, predicate)
            if (result != null) return result
        }
        return null
    }

    private fun findNodesByClass(
        root: AccessibilityNodeInfo,
        className: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (root.className?.toString() == className) results.add(root)
        for (i in 0 until root.childCount) {
            findNodesByClass(root.getChild(i) ?: continue, className, results)
        }
    }

    private fun findAllClickable(
        root: AccessibilityNodeInfo,
        results: MutableList<AccessibilityNodeInfo>,
        limit: Int
    ) {
        if (results.size >= limit) return
        if (root.isClickable && !root.text.isNullOrBlank()) results.add(root)
        for (i in 0 until root.childCount) {
            if (results.size >= limit) return
            findAllClickable(root.getChild(i) ?: continue, results, limit)
        }
    }
}
