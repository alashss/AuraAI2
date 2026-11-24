package com.auraai.overlay.detector

import android.view.accessibility.AccessibilityNodeInfo

data class ExamResult(
    val platform: String,
    val questionText: String,
    val options: List<String>,
    val optionNodes: List<AccessibilityNodeInfo>,
    val essayNode: AccessibilityNodeInfo? = null,
    val isEssay: Boolean = false,
    val nextButton: AccessibilityNodeInfo? = null,
    val submitButton: AccessibilityNodeInfo? = null
)

object ExamDetector {

    // ── KNOWN PACKAGES ───────────────────────────────────────────
    private val CHROME_PACKAGES = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
        "com.chrome.canary", "org.mozilla.firefox", "com.microsoft.emmx",
        "com.opera.browser", "com.brave.browser", "com.UCMobile.intl",
        "com.android.browser", "com.sec.android.app.sbrowser"
    )

    private val WEBVIEW_PACKAGES = setOf(
        "com.android.webview", "com.google.android.webview"
    )

    fun detect(root: AccessibilityNodeInfo): ExamResult? {
        val pkg = root.packageName?.toString() ?: ""
        val allText = extractAllText(root)

        val baseResult = when {
            isWayground(pkg, allText)    -> detectWayground(root, allText)
            isQuizziz(pkg, allText)      -> detectQuizziz(root, allText)
            isKahoot(pkg, allText)       -> detectKahoot(root, allText)
            isGoogleForm(pkg, allText)   -> detectGoogleForm(root, allText)
            isExamBrowser(pkg, allText)  -> detectExamBrowser(root, allText)
            isNearpod(pkg, allText)      -> detectGenericMcq(root, allText, "Nearpod")
            isMicrosoft(pkg, allText)    -> detectGenericMcq(root, allText, "MS Forms")
            isMoodle(pkg, allText)       -> detectMoodle(root, allText)
            isChromeOrBrowser(pkg)       -> detectGenericWeb(root, allText)
            isWebView(pkg)               -> detectGenericWeb(root, allText)
            else                         -> detectGeneric(root, allText)
        } ?: return null

        // Augment with navigation buttons
        val next = findNextButton(root)
        val submit = findSubmitButton(root)
        return baseResult.copy(nextButton = next, submitButton = submit)
    }

    // ── PLATFORM DETECTORS ───────────────────────────────────────

    private fun isWayground(pkg: String, text: String) =
        pkg.contains("wayground", true) || text.contains("wayground", true)

    private fun isQuizziz(pkg: String, text: String) =
        pkg.contains("quizizz", true) || text.contains("quizizz", true)

    private fun isKahoot(pkg: String, text: String) =
        pkg.contains("kahoot", true) || text.contains("kahoot", true)

    private fun isGoogleForm(pkg: String, text: String) =
        pkg.contains("docs.google", true) ||
        (pkg.contains("google", true) && text.contains("formulir", true)) ||
        text.contains("google form", true)

    private fun isExamBrowser(pkg: String, text: String) =
        pkg.contains("exambrowser", true) || pkg.contains(".seb.", true) ||
        pkg.contains("safebrowser", true) || text.contains("safe exam browser", true)

    private fun isNearpod(pkg: String, text: String) =
        pkg.contains("nearpod", true) || text.contains("nearpod", true)

    private fun isMicrosoft(pkg: String, text: String) =
        pkg.contains("microsoft", true) && (text.contains("forms", true) || text.contains("quiz", true))

    private fun isMoodle(pkg: String, text: String) =
        text.contains("moodle", true) || text.contains("quiz attempt", true) ||
        text.contains("finish attempt", true)

    private fun isChromeOrBrowser(pkg: String) = pkg in CHROME_PACKAGES

    private fun isWebView(pkg: String) = pkg in WEBVIEW_PACKAGES ||
        pkg.contains("webview", true)

    // ── WAYGROUND ─────────────────────────────────────────────────
    private fun detectWayground(root: AccessibilityNodeInfo, allText: String): ExamResult? {
        val question = findLargestQuestion(root)
            ?: extractQuestion(allText)
            ?: return null

        val optionNodes = findOptionNodes(root)
        val options = optionNodes.map { it.text?.toString() ?: "" }.filter { it.isNotBlank() }
        val essayNode = if (optionNodes.isEmpty()) findEditText(root) else null

        return ExamResult(
            platform = "Wayground",
            questionText = question,
            options = options,
            optionNodes = optionNodes,
            essayNode = essayNode,
            isEssay = essayNode != null && optionNodes.isEmpty()
        )
    }

    // ── QUIZIZZ ───────────────────────────────────────────────────
    private fun detectQuizziz(root: AccessibilityNodeInfo, allText: String): ExamResult? {
        val question = extractQuestion(allText) ?: return null
        // Quizizz punya 4 button opsi besar dengan teks jawaban
        val optionNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllClickableWithText(root, optionNodes, minLen = 2, limit = 4)
        val options = optionNodes.map { it.text?.toString() ?: "" }.filter { it.isNotBlank() }
        return ExamResult("Quizizz", question, options, optionNodes)
    }

    // ── KAHOOT ───────────────────────────────────────────────────
    private fun detectKahoot(root: AccessibilityNodeInfo, allText: String): ExamResult? {
        val question = findLargestQuestion(root)
            ?: extractQuestion(allText)
            ?: return null

        val optionNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllClickableWithText(root, optionNodes, minLen = 2, limit = 4)
        val options = optionNodes.map {
            it.text?.toString() ?: it.contentDescription?.toString() ?: ""
        }.filter { it.isNotBlank() }

        return ExamResult("Kahoot", question, options, optionNodes)
    }

    // ── GOOGLE FORM ───────────────────────────────────────────────
    private fun detectGoogleForm(root: AccessibilityNodeInfo, allText: String): ExamResult? {
        val question = extractQuestion(allText) ?: return null

        val radioNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByClass(root, "android.widget.RadioButton", radioNodes)

        val checkNodes = mutableListOf<AccessibilityNodeInfo>()
        if (radioNodes.isEmpty()) {
            findNodesByClass(root, "android.widget.CheckBox", checkNodes)
        }

        val optionNodes = if (radioNodes.isNotEmpty()) radioNodes else checkNodes
        val options = optionNodes.map { it.text?.toString() ?: "" }.filter { it.isNotBlank() }

        val essayNode = if (optionNodes.isEmpty()) findEditText(root) else null
        return ExamResult(
            platform = "Google Form",
            questionText = question,
            options = options,
            optionNodes = optionNodes,
            essayNode = essayNode,
            isEssay = essayNode != null && optionNodes.isEmpty()
        )
    }

    // ── EXAMBROWSER / SEB ─────────────────────────────────────────
    private fun detectExamBrowser(root: AccessibilityNodeInfo, allText: String): ExamResult? {
        val question = extractQuestion(allText) ?: return null
        val optionNodes = findOptionNodes(root)
        val options = optionNodes.map { it.text?.toString() ?: "" }.filter { it.isNotBlank() }
        val essayNode = if (optionNodes.isEmpty()) findEditText(root) else null
        return ExamResult(
            platform = "ExamBrowser",
            questionText = question,
            options = options,
            optionNodes = optionNodes,
            essayNode = essayNode,
            isEssay = essayNode != null && optionNodes.isEmpty()
        )
    }

    // ── MOODLE ───────────────────────────────────────────────────
    private fun detectMoodle(root: AccessibilityNodeInfo, allText: String): ExamResult? {
        val question = extractQuestion(allText) ?: return null
        val optionNodes = findOptionNodes(root)
        val options = optionNodes.map { it.text?.toString() ?: "" }.filter { it.isNotBlank() }
        val essayNode = if (optionNodes.isEmpty()) findEditText(root) else null
        return ExamResult(
            platform = "Moodle",
            questionText = question,
            options = options,
            optionNodes = optionNodes,
            essayNode = essayNode,
            isEssay = essayNode != null && optionNodes.isEmpty()
        )
    }

    // ── GENERIC MCQ (Nearpod, MS Forms, etc) ─────────────────────
    private fun detectGenericMcq(root: AccessibilityNodeInfo, allText: String, platform: String): ExamResult? {
        val question = extractQuestion(allText) ?: return null
        val optionNodes = findOptionNodes(root)
        val options = optionNodes.map { it.text?.toString() ?: "" }.filter { it.isNotBlank() }
        val essayNode = if (optionNodes.isEmpty()) findEditText(root) else null
        return ExamResult(
            platform = platform,
            questionText = question,
            options = options,
            optionNodes = optionNodes,
            essayNode = essayNode,
            isEssay = essayNode != null && optionNodes.isEmpty()
        )
    }

    // ── GENERIC WEB ───────────────────────────────────────────────
    private fun detectGenericWeb(root: AccessibilityNodeInfo, allText: String): ExamResult? {
        val question = extractQuestion(allText) ?: return null
        val optionNodes = findOptionNodes(root)
        val options = optionNodes.map { it.text?.toString() ?: "" }.filter { it.isNotBlank() }
        val essayNode = if (optionNodes.isEmpty()) findEditText(root) else null
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
        val essayNode = if (optionNodes.isEmpty()) findEditText(root) else null
        return ExamResult(
            platform = "Universal",
            questionText = question,
            options = options,
            optionNodes = optionNodes,
            essayNode = essayNode,
            isEssay = essayNode != null && optionNodes.isEmpty()
        )
    }

    // ── NAV BUTTON FINDERS ───────────────────────────────────────

    fun findNextButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val keywords = listOf(
            "next", "lanjut", "selanjutnya", "berikutnya",
            "→", "›", ">>", "next question", "soal berikutnya",
            "lanjutkan", "continue", "next soal", "forward", "maju",
            "soal ke", "soal selanjutnya"
        )
        return findButtonByKeywords(root, keywords)
    }

    fun findSubmitButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val keywords = listOf(
            "submit", "selesai", "kirim", "finish",
            "done", "simpan jawaban", "akhiri ujian",
            "akhiri", "submit quiz", "serahkan", "end quiz"
        )
        return findButtonByKeywords(root, keywords)
    }

    fun findButtonByKeywords(root: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = (node.text?.toString() ?: "").lowercase().trim()
            val desc = (node.contentDescription?.toString() ?: "").lowercase().trim()
            val combined = "$text $desc"
            if (keywords.any { combined.contains(it) }) {
                if (node.isClickable) return node
                var parent = node.parent
                repeat(5) {
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

    // ── HELPERS ───────────────────────────────────────────────────

    fun extractAllText(node: AccessibilityNodeInfo): String {
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

    private fun findLargestQuestion(root: AccessibilityNodeInfo): String? {
        var best: String? = null
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (n.className?.toString() == "android.widget.TextView") {
                val t = n.text?.toString() ?: ""
                val lower = t.lowercase()
                if (t.length > (best?.length ?: 15) &&
                    !lower.startsWith("a.") && !lower.startsWith("b.") &&
                    !lower.startsWith("c.") && !lower.startsWith("d.") &&
                    !lower.contains("waktu") && !lower.contains("timer") &&
                    !lower.contains("soal ke") && !lower.contains("score") &&
                    !lower.contains("nilai")) {
                    best = t
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return best
    }

    private fun extractQuestion(allText: String): String? {
        val NOISE_WORDS = setOf(
            "waktu", "timer", "soal ke", "score", "nilai", "menit",
            "detik", "point", "streak", "skor", "login", "signup",
            "next", "lanjut", "submit", "selesai", "finish"
        )
        val optionPrefixes = listOf(
            "a.", "b.", "c.", "d.", "e.",
            "1.", "2.", "3.", "4.", "5.",
            "a)", "b)", "c)", "d)", "e)",
            "1)", "2)", "3)", "4)", "5)"
        )
        return allText.lines()
            .map { it.trim() }
            .filter { line ->
                line.length > 15 &&
                !line.all { it.isDigit() } &&
                NOISE_WORDS.none { line.lowercase().contains(it) } &&
                optionPrefixes.none { line.lowercase().startsWith(it) }
            }
            .maxByOrNull { it.length }
    }

    private fun findOptionNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()

        // 1. RadioButton — paling reliable
        findNodesByClass(root, "android.widget.RadioButton", results)
        if (results.isNotEmpty()) return results

        // 2. CheckBox
        findNodesByClass(root, "android.widget.CheckBox", results)
        if (results.isNotEmpty()) return results

        // 3. TextView A./B./C./D. pattern
        fun walkForLetterOptions(node: AccessibilityNodeInfo?) {
            node ?: return
            val text = node.text?.toString()?.trim() ?: ""
            if (text.matches(Regex("(?i)^[a-e][.)].{1,200}"))) results.add(node)
            for (i in 0 until node.childCount) walkForLetterOptions(node.getChild(i))
        }
        walkForLetterOptions(root)
        if (results.size in 2..6) return results
        results.clear()

        // 4. Numbered 1./2./3./4. pattern
        fun walkForNumberOptions(node: AccessibilityNodeInfo?) {
            node ?: return
            val text = node.text?.toString()?.trim() ?: ""
            if (text.matches(Regex("^[1-5][.)].{1,200}"))) results.add(node)
            for (i in 0 until node.childCount) walkForNumberOptions(node.getChild(i))
        }
        walkForNumberOptions(root)
        if (results.size in 2..6) return results
        results.clear()

        // 5. Fallback: 2-5 clickable nodes with meaningful text
        findAllClickableWithText(root, results, minLen = 3, limit = 5)
        return results
    }

    private fun findEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.className?.toString() == "android.widget.EditText") return root
        for (i in 0 until root.childCount) {
            val r = findEditText(root.getChild(i) ?: continue)
            if (r != null) return r
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

    private fun findAllClickableWithText(
        root: AccessibilityNodeInfo,
        results: MutableList<AccessibilityNodeInfo>,
        minLen: Int,
        limit: Int
    ) {
        if (results.size >= limit) return
        val text = root.text?.toString() ?: ""
        val desc = root.contentDescription?.toString() ?: ""
        val hasText = text.length >= minLen || desc.length >= minLen
        // Skip nav/ui elements
        val combined = (text + desc).lowercase()
        val isNavElement = listOf("next", "submit", "lanjut", "selesai", "login", "signup",
            "back", "kembali", "home", "settings").any { combined == it }
        if (root.isClickable && hasText && !isNavElement) results.add(root)
        for (i in 0 until root.childCount) {
            if (results.size >= limit) return
            findAllClickableWithText(root.getChild(i) ?: continue, results, minLen, limit)
        }
    }
}
