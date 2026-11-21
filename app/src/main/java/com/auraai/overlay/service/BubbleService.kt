package com.auraai.overlay.service

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.auraai.overlay.R
import com.auraai.overlay.ui.MainActivity

class BubbleService : Service() {

    companion object {
        var isRunning = false
        const val CHANNEL_ID = "auraai_bubble"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "STOP"
    }

    private lateinit var wm: WindowManager
    private var robotView: View? = null
    private var menuView: View? = null
    private var statusView: View? = null
    private var isMenuOpen = false
    private val handler = Handler(Looper.getMainLooper())

    // State
    private var stealthMode = false
    private var autoAnswerEnabled = false
    private var currentQuestion = ""
    private var currentAnswer = ""
    private var currentPlatform = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                "com.auraai.AURA_STATUS"   -> handleStatus(intent.getStringExtra("status") ?: return)
                "com.auraai.AURA_QUESTION" -> handleQuestion(
                    intent.getStringExtra("question") ?: return,
                    intent.getStringExtra("platform") ?: ""
                )
                "com.auraai.AURA_ANSWER"   -> handleAnswer(intent.getStringExtra("answer") ?: return)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotifChannel()
        startForeground(NOTIF_ID, buildNotif())
        registerBroadcast()
        showRobot()
    }

    override fun onDestroy() {
        isRunning = false
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        removeAllViews()
        super.onDestroy()
    }

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    private fun registerBroadcast() {
        val filter = IntentFilter().apply {
            addAction("com.auraai.AURA_STATUS")
            addAction("com.auraai.AURA_QUESTION")
            addAction("com.auraai.AURA_ANSWER")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    // ── NOTIFICATION ─────────────────────────────────────────────
    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "AuraAI", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, BubbleService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AuraAI Aktif")
            .setContentText("Tap robot 🤖 untuk menu")
            .setSmallIcon(R.drawable.ic_notif)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true).setSilent(true).build()
    }

    // ── ROBOT MASCOT ─────────────────────────────────────────────
    private fun showRobot() {
        val ctx = this
        val size = dp(72)
        val type = overlayType()

        val params = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(16); y = dp(220)
        }

        // Build robot view programmatically
        val iv = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_robot_mascot)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        setupDrag(iv, params)
        iv.setOnClickListener {
            if (isMenuOpen) closeMenu() else openMenu()
        }

        // Pulse animasi
        val pulse = ScaleAnimation(1f, 1.08f, 1f, 1.08f,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 900; repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        iv.startAnimation(pulse)

        try {
            wm.addView(iv, params)
            robotView = iv
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun setupDrag(v: View, p: WindowManager.LayoutParams) {
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var dragging = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = p.x; iy = p.y; tx = e.rawX; ty = e.rawY; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = tx - e.rawX; val dy = e.rawY - ty
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) dragging = true
                    p.x = (ix + dx).toInt().coerceAtLeast(0)
                    p.y = (iy + dy).toInt().coerceAtLeast(0)
                    try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) v.performClick(); true
                }
                else -> false
            }
        }
    }

    // ── MENU (persis video: popup putih, item list) ───────────────
    private fun openMenu() {
        if (isMenuOpen) return
        isMenuOpen = true

        val ctx = this
        val type = overlayType()
        val menuW = dp(260)

        val params = WindowManager.LayoutParams(
            menuW, WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12); y = dp(120)
        }

        // Container utama
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(16))
            elevation = dp(8).toFloat()
            background = roundedWhite(dp(16))
        }

        // Header kecil
        val header = TextView(ctx).apply {
            text = "AuraAI"
            textSize = 11f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(dp(20), dp(4), dp(20), dp(8))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
        }
        card.addView(header)

        // Divider
        card.addView(dividerLine())

        // Menu items
        val items = listOf(
            Triple("✨", "Jawab soal otomatis", 0),
            Triple("✏️", "Jawaban diisi AI", 1),
            Triple("🎯", "Auto nilai di atas KKM", 2),
            Triple("📖", "Penjelasan lengkap", 3),
            Triple("💬", "Buka ChatGPT", 4),
        )

        for ((icon, label, idx) in items) {
            card.addView(menuItem(ctx, icon, label, idx))
        }

        card.addView(dividerLine())

        // Mode samaran (toggle)
        val stealthRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val stealthIcon = TextView(ctx).apply {
            text = "👁️"
            textSize = 18f
            setPadding(0, 0, dp(12), 0)
        }
        val stealthLabel = TextView(ctx).apply {
            text = "Mode samaran"
            textSize = 14f
            setTextColor(Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val stealthToggle = Switch(ctx).apply {
            isChecked = stealthMode
            thumbTintList = android.content.res.ColorStateList.valueOf(
                if (stealthMode) Color.parseColor("#6C63FF") else Color.parseColor("#BDBDBD")
            )
            setOnCheckedChangeListener { _, checked ->
                stealthMode = checked
                thumbTintList = android.content.res.ColorStateList.valueOf(
                    if (checked) Color.parseColor("#6C63FF") else Color.parseColor("#BDBDBD")
                )
                // Kalau stealth: sembunyiin robot
                if (checked) {
                    robotView?.alpha = 0.15f
                } else {
                    robotView?.alpha = 1f
                }
            }
        }
        stealthRow.addView(stealthIcon)
        stealthRow.addView(stealthLabel)
        stealthRow.addView(stealthToggle)
        card.addView(stealthRow)

        // Developer label
        val devLabel = TextView(ctx).apply {
            text = "DEVELOPER  @tugasvip3"
            textSize = 9f
            setTextColor(Color.parseColor("#BDBDBD"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(devLabel)

        // Animasi slide-in dari kiri
        card.startAnimation(AnimationUtils.loadAnimation(ctx, android.R.anim.slide_in_left).apply {
            duration = 180
        })

        // Tutup kalau tap di luar
        card.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) closeMenu()
            false
        }

        try {
            wm.addView(card, params)
            menuView = card
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun menuItem(ctx: Context, icon: String, label: String, idx: Int): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        val iconTv = TextView(ctx).apply {
            text = icon; textSize = 18f
            setPadding(0, 0, dp(14), 0)
        }
        val labelTv = TextView(ctx).apply {
            text = label; textSize = 14f
            setTextColor(Color.parseColor("#212121"))
        }
        row.addView(iconTv)
        row.addView(labelTv)

        row.setOnClickListener {
            closeMenu()
            handler.postDelayed({ handleMenuAction(idx) }, 150)
        }
        return row
    }

    private fun handleMenuAction(idx: Int) {
        when (idx) {
            0 -> { // Jawab soal otomatis
                autoAnswerEnabled = true
                AuraAccessibilityService.autoClickEnabled = true
                showStatus("🔍 Scanning soal otomatis...", isProgress = true)
                AuraAccessibilityService.triggerScan()
            }
            1 -> { // Jawaban diisi AI (essay mode)
                showStatus("✏️ Mode isi jawaban AI aktif", isProgress = false)
                AuraAccessibilityService.triggerScan()
            }
            2 -> { // Auto nilai KKM
                autoAnswerEnabled = true
                AuraAccessibilityService.autoClickEnabled = true
                showStatus("🎯 Auto KKM aktif — scanning...", isProgress = true)
                AuraAccessibilityService.triggerScan()
            }
            3 -> { // Penjelasan lengkap
                showStatus("📖 Meminta penjelasan lengkap...", isProgress = true)
                AuraAccessibilityService.instance?.doScanWithMode("explain")
            }
            4 -> { // Buka ChatGPT
                try {
                    val intent = Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://chat.openai.com")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun closeMenu() {
        isMenuOpen = false
        menuView?.let {
            val anim = AlphaAnimation(1f, 0f).apply {
                duration = 150
                setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationStart(a: Animation?) {}
                    override fun onAnimationRepeat(a: Animation?) {}
                    override fun onAnimationEnd(a: Animation?) {
                        try { wm.removeView(it) } catch (_: Exception) {}
                        menuView = null
                    }
                })
            }
            it.startAnimation(anim)
        }
    }

    // ── STATUS BAR (bawah, persis video) ─────────────────────────
    private fun showStatus(text: String, isProgress: Boolean) {
        removeStatus()
        val ctx = this
        val type = overlayType()
        val dm = resources.displayMetrics

        val params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.72).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(100)
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            elevation = dp(6).toFloat()
            background = roundedWhite(dp(20))
        }

        val statusRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusTv = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#212121"))
            typeface = Typeface.DEFAULT_BOLD
        }
        statusRow.addView(statusTv)
        card.addView(statusRow)

        if (isProgress) {
            val pb = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#FF6B9D")
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(3)
                ).apply { topMargin = dp(6) }
            }
            card.addView(pb)
        }

        // Animasi slide up
        card.startAnimation(TranslateAnimation(0f, 0f, dp(60).toFloat(), 0f).apply {
            duration = 220; interpolator = DecelerateInterpolator()
        })

        try {
            wm.addView(card, params)
            statusView = card
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun showAnswer(question: String, answer: String, platform: String) {
        removeStatus()
        val ctx = this
        val type = overlayType()
        val dm = resources.displayMetrics

        val params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.88).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(80)
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(8).toFloat()
            background = roundedWhite(dp(20))
        }

        // Platform chip
        if (platform.isNotBlank()) {
            val chip = TextView(ctx).apply {
                text = "📱 $platform"
                textSize = 10f
                setTextColor(Color.parseColor("#6C63FF"))
                background = chipBg()
                setPadding(dp(8), dp(2), dp(8), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            card.addView(chip)
        }

        // Soal (singkat, max 2 baris)
        if (question.isNotBlank()) {
            val qTv = TextView(ctx).apply {
                text = question.take(120) + if (question.length > 120) "..." else ""
                textSize = 11f
                setTextColor(Color.parseColor("#757575"))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            card.addView(qTv)
        }

        // Jawaban (highlight)
        val aTv = TextView(ctx).apply {
            text = answer
            textSize = 15f
            setTextColor(Color.parseColor("#212121"))
            typeface = Typeface.DEFAULT_BOLD
            background = answerBg()
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        card.addView(aTv)

        // Progress bar pink (seperti video)
        val pb = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#FF6B9D")
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)
            ).apply { bottomMargin = dp(8) }
        }
        card.addView(pb)

        // Tombol tutup kecil
        val closeBtn = TextView(ctx).apply {
            text = "✕ Tutup"
            textSize = 11f
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        closeBtn.setOnClickListener { removeStatus() }
        card.addView(closeBtn)

        // Slide up
        card.startAnimation(TranslateAnimation(0f, 0f, dp(80).toFloat(), 0f).apply {
            duration = 250; interpolator = DecelerateInterpolator()
        })

        try {
            wm.addView(card, params)
            statusView = card
        } catch (e: Exception) { e.printStackTrace() }

        // Auto hide setelah 12 detik
        handler.postDelayed({ removeStatus() }, 12000)
    }

    private fun removeStatus() {
        statusView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            statusView = null
        }
    }

    // ── BROADCAST HANDLERS ────────────────────────────────────────
    private fun handleStatus(status: String) {
        handler.post {
            when {
                status == "scanning"  -> showStatus("🔍 Scanning soal...", isProgress = true)
                status == "answering" -> showStatus("🤖 AI menjawab...", isProgress = true)
                status == "done"      -> { /* answer card sudah tampil */ }
                status == "idle"      -> removeStatus()
                status.startsWith("error:no_question") -> {
                    showStatus("⚠️ Soal tidak terdeteksi", isProgress = false)
                    handler.postDelayed({ removeStatus() }, 3000)
                }
                status.startsWith("error:no_window") -> {
                    showStatus("⚠️ Buka app ujian dulu", isProgress = false)
                    handler.postDelayed({ removeStatus() }, 3000)
                }
                status.startsWith("error:") -> {
                    showStatus("❌ ${status.removePrefix("error:")}", isProgress = false)
                    handler.postDelayed({ removeStatus() }, 4000)
                }
            }
        }
    }

    private fun handleQuestion(question: String, platform: String) {
        currentQuestion = question
        currentPlatform = platform
        handler.post {
            showStatus("✏️ Mengerjakan soal", isProgress = true)
        }
    }

    private fun handleAnswer(answer: String) {
        currentAnswer = answer
        handler.post {
            showAnswer(currentQuestion, answer, currentPlatform)
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────
    private fun removeAllViews() {
        listOf(robotView, menuView, statusView).forEach {
            it?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        }
        robotView = null; menuView = null; statusView = null
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun roundedWhite(radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radius.toFloat()
            setStroke(1, Color.parseColor("#F0F0F0"))
        }
    }

    private fun chipBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#EDE7FF"))
            cornerRadius = dp(20).toFloat()
        }
    }

    private fun answerBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor("#F0F4FF"))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.parseColor("#C5CAE9"))
        }
    }

    private fun dividerLine(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { setMargins(dp(16), dp(2), dp(16), dp(2)) }
        }
    }
}
