package com.auraai.overlay.service

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.view.animation.*
import android.webkit.*
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
    private var chatView: View? = null
    private var isChatOpen = false
    private var isMenuOpen = false
    private val handler = Handler(Looper.getMainLooper())

    private var stealthMode = false
    private var autoAnswerEnabled = false
    private var currentQuestion = ""
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

    // ── NOTIFICATION ──────────────────────────────────────────────
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

    // ── ROBOT MASCOT ──────────────────────────────────────────────
    private fun showRobot() {
        val size = dp(72)
        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(16); y = dp(220) }

        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_robot_mascot)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        setupDrag(iv, params)
        iv.setOnClickListener {
            if (isChatOpen) closeChat()
            else if (isMenuOpen) closeMenu()
            else openMenu()
        }

        iv.startAnimation(ScaleAnimation(1f, 1.08f, 1f, 1.08f,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 900; repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        })

        try { wm.addView(iv, params); robotView = iv } catch (e: Exception) { e.printStackTrace() }
    }

    private fun setupDrag(v: View, p: WindowManager.LayoutParams) {
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var dragging = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix=p.x; iy=p.y; tx=e.rawX; ty=e.rawY; dragging=false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx=tx-e.rawX; val dy=e.rawY-ty
                    if (Math.abs(dx)>8||Math.abs(dy)>8) dragging=true
                    p.x=(ix+dx).toInt().coerceAtLeast(0)
                    p.y=(iy+dy).toInt().coerceAtLeast(0)
                    try { wm.updateViewLayout(v,p) } catch(_:Exception){}
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragging) v.performClick(); true }
                else -> false
            }
        }
    }

    // ── MENU ──────────────────────────────────────────────────────
    private fun openMenu() {
        if (isMenuOpen) return
        isMenuOpen = true

        val params = WindowManager.LayoutParams(
            dp(265), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(120) }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(14))
            elevation = dp(8).toFloat()
            background = roundedBg(Color.WHITE, dp(16))
        }

        card.addView(TextView(this).apply {
            text = "AuraAI"
            textSize = 10f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(dp(20), dp(4), dp(20), dp(8))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        })
        card.addView(divider())

        listOf(
            Triple("✨", "Jawab soal otomatis", 0),
            Triple("✏️", "Jawaban diisi AI", 1),
            Triple("🎯", "Auto nilai di atas KKM", 2),
            Triple("📖", "Penjelasan lengkap", 3),
            Triple("💬", "Buka ChatGPT", 4),
        ).forEach { (icon, label, idx) -> card.addView(menuRow(icon, label, idx)) }

        card.addView(divider())

        // Stealth toggle
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        row.addView(TextView(this).apply { text="👁️"; textSize=18f; setPadding(0,0,dp(12),0) })
        row.addView(TextView(this).apply {
            text="Mode samaran"; textSize=14f
            setTextColor(Color.parseColor("#212121"))
            layoutParams=LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val toggle = Switch(this).apply {
            isChecked = stealthMode
            setOnCheckedChangeListener { _, c ->
                stealthMode = c
                robotView?.alpha = if (c) 0.15f else 1f
            }
        }
        row.addView(toggle)
        card.addView(row)

        card.addView(TextView(this).apply {
            text = "DEVELOPER  @tugasvip3"
            textSize = 9f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#BDBDBD"))
            setPadding(0, dp(4), 0, 0)
        })

        card.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.slide_in_left).apply {
            duration = 180
        })
        card.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) closeMenu(); false }

        try { wm.addView(card, params); menuView = card } catch (e: Exception) { e.printStackTrace() }
    }

    private fun menuRow(icon: String, label: String, idx: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true; isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            addView(TextView(this@BubbleService).apply {
                text=icon; textSize=18f; setPadding(0,0,dp(14),0)
            })
            addView(TextView(this@BubbleService).apply {
                text=label; textSize=14f; setTextColor(Color.parseColor("#212121"))
            })
            setOnClickListener {
                closeMenu()
                handler.postDelayed({ handleMenuAction(idx) }, 160)
            }
        }
    }

    private fun handleMenuAction(idx: Int) {
        when (idx) {
            0 -> {
                autoAnswerEnabled = true
                AuraAccessibilityService.autoClickEnabled = true
                showStatus("🔍 Scanning soal...", true)
                AuraAccessibilityService.triggerScan()
            }
            1 -> {
                showStatus("✏️ Mode isi AI aktif", false)
                AuraAccessibilityService.triggerScan()
            }
            2 -> {
                autoAnswerEnabled = true
                AuraAccessibilityService.autoClickEnabled = true
                showStatus("🎯 Auto KKM aktif...", true)
                AuraAccessibilityService.triggerScan()
            }
            3 -> {
                showStatus("📖 Meminta penjelasan...", true)
                AuraAccessibilityService.instance?.doScanWithMode("explain")
            }
            4 -> openChat()
        }
    }

    private fun closeMenu() {
        if (!isMenuOpen) return
        isMenuOpen = false
        val view = menuView ?: return
        menuView = null
        handler.post {
            try { view.clearAnimation(); wm.removeView(view) } catch (_: Exception) {}
        }
    }

    // ── FLOATING CHATGPT (WebView overlay, persistent session) ────
    private fun openChat() {
        if (isChatOpen) { closeChat(); return }
        isChatOpen = true

        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.92).toInt(),
            (dm.heightPixels * 0.78).toInt(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER; y = -dp(30) }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.WHITE, dp(18))
            elevation = dp(12).toFloat()
        }

        // Header bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#6C63FF"))
            // rounded top only via manual padding
        }
        header.addView(TextView(this).apply {
            text = "💬 ChatGPT"
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // Close button
        header.addView(TextView(this).apply {
            text = "✕"
            textSize = 18f; setTextColor(Color.WHITE)
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { closeChat() }
        })
        container.addView(header)

        // WebView dengan persistent cookies
        val webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = false
                displayZoomControls = false
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36"
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    // Tetap di dalam WebView, jangan buka browser luar
                    return false
                }
            }
            webChromeClient = WebChromeClient()
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(this@apply, true)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            loadUrl("https://chat.openai.com")
        }
        container.addView(webView)

        // Slide in dari bawah
        container.startAnimation(TranslateAnimation(0f, 0f, dp(100).toFloat(), 0f).apply {
            duration = 240; interpolator = DecelerateInterpolator()
        })

        try { wm.addView(container, params); chatView = container }
        catch (e: Exception) { isChatOpen = false; e.printStackTrace() }
    }

    private fun closeChat() {
        if (!isChatOpen) return
        isChatOpen = false
        val view = chatView ?: return
        chatView = null
        handler.post {
            try { view.clearAnimation(); wm.removeView(view) } catch (_: Exception) {}
        }
    }

    // ── STATUS / ANSWER CARD ──────────────────────────────────────
    private fun showStatus(text: String, progress: Boolean) {
        removeStatus()
        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.72).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(100) }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            elevation = dp(6).toFloat()
            background = roundedBg(Color.WHITE, dp(20))
        }
        card.addView(TextView(this).apply {
            this.text = text; textSize = 13f
            setTextColor(Color.parseColor("#212121"))
            typeface = Typeface.DEFAULT_BOLD
        })
        if (progress) {
            card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6B9D"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
                    .apply { topMargin = dp(6) }
            })
        }
        card.startAnimation(TranslateAnimation(0f, 0f, dp(60).toFloat(), 0f).apply {
            duration = 220; interpolator = DecelerateInterpolator()
        })
        try { wm.addView(card, params); statusView = card } catch (e: Exception) { e.printStackTrace() }
    }

    private fun showAnswer(question: String, answer: String, platform: String) {
        removeStatus()
        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.88).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(80) }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(8).toFloat()
            background = roundedBg(Color.WHITE, dp(20))
        }

        if (platform.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = "📱 $platform"; textSize = 10f
                setTextColor(Color.parseColor("#6C63FF"))
                background = roundedBg(Color.parseColor("#EDE7FF"), dp(20))
                setPadding(dp(8), dp(2), dp(8), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            })
        }

        if (question.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = question.take(100) + if (question.length > 100) "..." else ""
                textSize = 11f; maxLines = 2
                setTextColor(Color.parseColor("#757575"))
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            })
        }

        card.addView(TextView(this).apply {
            text = answer; textSize = 15f
            setTextColor(Color.parseColor("#212121"))
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(Color.parseColor("#F0F4FF"), dp(10))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        })

        card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false; progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6B9D"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
                .apply { bottomMargin = dp(8) }
        })

        card.addView(TextView(this).apply {
            text = "✕ Tutup"; textSize = 11f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { removeStatus() }
        })

        card.startAnimation(TranslateAnimation(0f, 0f, dp(80).toFloat(), 0f).apply {
            duration = 250; interpolator = DecelerateInterpolator()
        })
        try { wm.addView(card, params); statusView = card } catch (e: Exception) { e.printStackTrace() }
        handler.postDelayed({ removeStatus() }, 14000)
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
                status == "scanning"  -> showStatus("🔍 Scanning soal...", true)
                status == "answering" -> showStatus("🤖 AI menjawab...", true)
                status == "done"      -> { /* answer card sudah tampil */ }
                status == "idle"      -> removeStatus()
                status.startsWith("error:no_question") -> {
                    showStatus("⚠️ Soal tidak terdeteksi", false)
                    handler.postDelayed({ removeStatus() }, 3000)
                }
                status.startsWith("error:no_window") -> {
                    showStatus("⚠️ Buka app ujian dulu", false)
                    handler.postDelayed({ removeStatus() }, 3000)
                }
                status.startsWith("error:") -> {
                    showStatus("❌ ${status.removePrefix("error:")}", false)
                    handler.postDelayed({ removeStatus() }, 4000)
                }
            }
        }
    }

    private fun handleQuestion(question: String, platform: String) {
        currentQuestion = question
        currentPlatform = platform
        handler.post { showStatus("✏️ Mengerjakan soal", true) }
    }

    private fun handleAnswer(answer: String) {
        handler.post { showAnswer(currentQuestion, answer, currentPlatform) }
    }

    // ── HELPERS ───────────────────────────────────────────────────
    private fun removeAllViews() {
        listOf(robotView, menuView, statusView, chatView).forEach {
            it?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        }
        robotView = null; menuView = null; statusView = null; chatView = null
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun roundedBg(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat()
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#F0F0F0"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { setMargins(dp(16), dp(2), dp(16), dp(2)) }
    }
}
