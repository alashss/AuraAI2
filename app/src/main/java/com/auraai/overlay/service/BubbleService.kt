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
import com.auraai.overlay.ai.AiProvider
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
    private var aiPanelView: View? = null
    private var isMenuOpen = false
    private var isAiPanelOpen = false
    private val handler = Handler(Looper.getMainLooper())

    private var stealthMode = false
    private var currentQuestion = ""
    private var currentPlatform = ""

    // WebView persisten — dibuat sekali, tidak dihancurkan saat panel ditutup
    private var persistentWebViews = mutableMapOf<String, WebView>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                "com.auraai.AURA_STATUS"   -> handleStatus(intent.getStringExtra("status") ?: return)
                "com.auraai.AURA_QUESTION" -> handleQuestion(
                    intent.getStringExtra("question") ?: return,
                    intent.getStringExtra("platform") ?: "")
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
        persistentWebViews.values.forEach { try { it.destroy() } catch (_: Exception) {} }
        persistentWebViews.clear()
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
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, BubbleService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AuraAI Aktif")
            .setContentText("Tap robot untuk menu")
            .setSmallIcon(R.drawable.ic_notif)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true).setSilent(true).build()
    }

    // ── ROBOT MASCOT ──────────────────────────────────────────────
    private fun showRobot() {
        val size = dp(68)
        val params = WindowManager.LayoutParams(size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(14); y = dp(200) }

        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_robot_mascot)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        setupDrag(iv, params)
        iv.setOnClickListener {
            when {
                isAiPanelOpen -> closeAiPanel()
                isMenuOpen    -> closeMenu()
                else          -> openMenu()
            }
        }

        // Pulse animasi
        val pulse = ScaleAnimation(1f, 1.07f, 1f, 1.07f,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 950; repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE; interpolator = AccelerateDecelerateInterpolator()
        }
        iv.startAnimation(pulse)

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

        val params = WindowManager.LayoutParams(dp(270), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(110) }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(14))
            elevation = dp(10).toFloat()
            background = roundedBg(Color.WHITE, dp(18))
        }

        // Header
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(2), dp(14), dp(10))
            addView(ImageView(this@BubbleService).apply {
                setImageResource(R.drawable.ic_robot_mascot)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(8) }
            })
            addView(TextView(this@BubbleService).apply {
                text = "AuraAI"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#1A1F3A"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@BubbleService).apply {
                text = "✕"; textSize = 16f; setTextColor(Color.parseColor("#BDBDBD"))
                setPadding(dp(8), 0, dp(4), 0)
                setOnClickListener { closeMenu() }
            })
        })
        card.addView(divider())

        // Menu items
        listOf(
            Triple("✨", "Jawab soal otomatis", 0),
            Triple("✏️", "Jawaban diisi AI", 1),
            Triple("🎯", "Auto nilai di atas KKM", 2),
            Triple("📖", "Penjelasan lengkap", 3),
            Triple("🤖", "Buka panel AI", 4),
        ).forEach { (icon, label, idx) -> card.addView(menuRow(icon, label, idx)) }

        card.addView(divider())

        // Stealth toggle row
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(TextView(this@BubbleService).apply { text="👁️"; textSize=17f; setPadding(0,0,dp(12),0) })
            addView(TextView(this@BubbleService).apply {
                text="Mode samaran"; textSize=13f
                setTextColor(Color.parseColor("#424242"))
                layoutParams=LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val toggle = Switch(this@BubbleService).apply {
                isChecked = stealthMode
                setOnCheckedChangeListener { _, c ->
                    stealthMode = c; robotView?.alpha = if (c) 0.12f else 1f
                }
            }
            addView(toggle)
        })

        card.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) closeMenu(); false }
        card.startAnimation(TranslateAnimation(-dp(280).toFloat(), 0f, 0f, 0f).apply {
            duration = 200; interpolator = DecelerateInterpolator()
        })
        try { wm.addView(card, params); menuView = card } catch (e: Exception) { e.printStackTrace() }
    }

    private fun menuRow(icon: String, label: String, idx: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            isClickable = true; isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            addView(TextView(this@BubbleService).apply { text=icon; textSize=17f; setPadding(0,0,dp(12),0) })
            addView(TextView(this@BubbleService).apply { text=label; textSize=13f; setTextColor(Color.parseColor("#212121")) })
            setOnClickListener { closeMenu(); handler.postDelayed({ handleMenuAction(idx) }, 160) }
        }

    private fun handleMenuAction(idx: Int) {
        when (idx) {
            0 -> {
                AuraAccessibilityService.autoClickEnabled = true
                showStatus("🔍 Scanning soal...", true)
                AuraAccessibilityService.triggerScan()
            }
            1 -> {
                AuraAccessibilityService.autoClickEnabled = false
                showStatus("✏️ Scanning — tampilkan jawaban...", true)
                AuraAccessibilityService.triggerScan()
            }
            2 -> {
                AuraAccessibilityService.autoClickEnabled = true
                showStatus("🎯 Auto KKM — scanning...", true)
                AuraAccessibilityService.triggerScan()
            }
            3 -> {
                AuraAccessibilityService.autoClickEnabled = false
                showStatus("📖 Meminta penjelasan...", true)
                AuraAccessibilityService.instance?.doScanWithMode("explain")
            }
            4 -> openAiPanel()
        }
    }

    private fun closeMenu() {
        if (!isMenuOpen) return
        isMenuOpen = false
        val view = menuView ?: return
        menuView = null
        handler.post { try { view.clearAnimation(); wm.removeView(view) } catch (_: Exception) {} }
    }

    // ── FLOATING AI PANEL ─────────────────────────────────────────
    // Panel ngambang dengan tab: Claude / ChatGPT / Gemini / DeepSeek
    // WebView masing-masing persisten — tidak di-reload saat ganti tab
    private fun openAiPanel() {
        if (isAiPanelOpen) { closeAiPanel(); return }
        isAiPanelOpen = true

        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.93).toInt(),
            (dm.heightPixels * 0.80).toInt(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER; y = -dp(20) }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(14).toFloat()
            background = roundedBg(Color.parseColor("#1A1F3A"), dp(20))
        }

        // ── Header ───────────────────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#252B50"))
        }
        header.addView(TextView(this).apply {
            text = "🤖 AI Panel"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "✕"; textSize = 20f; setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(dp(12), dp(2), dp(4), dp(2))
            setOnClickListener { closeAiPanel() }
        })
        container.addView(header)

        // ── Tab bar ───────────────────────────────────────────────
        data class AiTab(val label: String, val url: String, val color: String)
        val tabs = listOf(
            AiTab("Claude",    "https://claude.ai",           "#D97706"),
            AiTab("ChatGPT",   "https://chat.openai.com",     "#10A37F"),
            AiTab("Gemini",    "https://gemini.google.com",   "#4285F4"),
            AiTab("DeepSeek",  "https://chat.deepseek.com",   "#6C63FF"),
        )

        val tabBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#1E2440"))
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        tabBar.addView(tabRow)
        container.addView(tabBar)

        // ── WebView container ─────────────────────────────────────
        val webContainer = FrameLayout(this)
        container.addView(webContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Buat semua WebView di awal (persisten)
        tabs.forEachIndexed { i, tab ->
            val wv = persistentWebViews.getOrPut(tab.url) {
                WebView(this).apply {
                    settings.apply {
                        javaScriptEnabled = true; domStorageEnabled = true
                        databaseEnabled = true; loadWithOverviewMode = true
                        useWideViewPort = true; setSupportZoom(false)
                        builtInZoomControls = false; displayZoomControls = false
                        userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false
                    }
                    webChromeClient = WebChromeClient()
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl(tab.url)
                }
            }
            webContainer.addView(wv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
            wv.visibility = if (i == 0) View.VISIBLE else View.GONE
        }

        // Build tab buttons
        var activeIdx = 0
        val tabButtons = mutableListOf<TextView>()
        tabs.forEachIndexed { i, tab ->
            val btn = TextView(this).apply {
                text = tab.label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setTextColor(if (i == 0) Color.WHITE else Color.parseColor("#757575"))
                background = if (i == 0) roundedBg(Color.parseColor(tab.color), dp(20))
                             else roundedBg(Color.parseColor("#2D3458"), dp(20))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(6) }
                setOnClickListener {
                    if (activeIdx == i) return@setOnClickListener
                    // Deactivate old
                    tabButtons[activeIdx].apply {
                        setTextColor(Color.parseColor("#757575"))
                        background = roundedBg(Color.parseColor("#2D3458"), dp(20))
                    }
                    // Hide old webview
                    (webContainer.getChildAt(activeIdx) as? WebView)?.visibility = View.GONE
                    // Activate new
                    activeIdx = i
                    setTextColor(Color.WHITE)
                    background = roundedBg(Color.parseColor(tab.color), dp(20))
                    // Show new webview
                    (webContainer.getChildAt(i) as? WebView)?.visibility = View.VISIBLE
                }
            }
            tabButtons.add(btn)
            tabRow.addView(btn)
        }

        // Slide in dari bawah
        container.startAnimation(TranslateAnimation(0f, 0f, dp(120).toFloat(), 0f).apply {
            duration = 260; interpolator = DecelerateInterpolator()
        })

        try { wm.addView(container, params); aiPanelView = container }
        catch (e: Exception) { isAiPanelOpen = false; e.printStackTrace() }
    }

    private fun closeAiPanel() {
        if (!isAiPanelOpen) return
        isAiPanelOpen = false
        val view = aiPanelView ?: return
        aiPanelView = null
        // Slide out
        val anim = TranslateAnimation(0f, 0f, 0f, dp(120).toFloat()).apply {
            duration = 200; interpolator = AccelerateInterpolator()
        }
        anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(a: Animation?) {}
            override fun onAnimationRepeat(a: Animation?) {}
            override fun onAnimationEnd(a: Animation?) {
                handler.post { try { view.clearAnimation(); wm.removeView(view) } catch (_: Exception) {} }
            }
        })
        view.startAnimation(anim)
    }

    // ── STATUS / ANSWER CARD ──────────────────────────────────────
    private fun showStatus(text: String, progress: Boolean) {
        removeStatus()
        val dm = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.74).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(110) }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = dp(8).toFloat()
            background = roundedBg(Color.parseColor("#1A1F3A"), dp(20))
        }
        card.addView(TextView(this).apply {
            this.text = text; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        if (progress) {
            card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6B9D"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
                    .apply { topMargin = dp(8) }
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
            (dm.widthPixels * 0.90).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(80) }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(10).toFloat()
            background = roundedBg(Color.parseColor("#1A1F3A"), dp(20))
        }

        // Platform chip
        if (platform.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = "📱 $platform"; textSize = 10f
                setTextColor(Color.parseColor("#A78BFA"))
                background = roundedBg(Color.parseColor("#252B50"), dp(20))
                setPadding(dp(10), dp(3), dp(10), dp(3))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            })
        }

        // Soal singkat
        if (question.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = question.take(100) + if (question.length > 100) "..." else ""
                textSize = 11f; maxLines = 2
                setTextColor(Color.parseColor("#9CA3AF"))
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            })
        }

        // Jawaban — highlight
        card.addView(TextView(this).apply {
            text = answer; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#6C63FF"), dp(12))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        })

        // Progress bar pink
        card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false; progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6B9D"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
                .apply { bottomMargin = dp(10) }
        })

        // Tutup button
        card.addView(TextView(this).apply {
            text = "✕ Tutup"; textSize = 11f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { removeStatus() }
        })

        card.startAnimation(TranslateAnimation(0f, 0f, dp(80).toFloat(), 0f).apply {
            duration = 260; interpolator = DecelerateInterpolator()
        })
        try { wm.addView(card, params); statusView = card } catch (e: Exception) { e.printStackTrace() }
        handler.postDelayed({ removeStatus() }, 15000)
    }

    private fun removeStatus() {
        statusView?.let { try { wm.removeView(it) } catch (_: Exception) {}; statusView = null }
    }

    // ── BROADCAST HANDLERS ────────────────────────────────────────
    private fun handleStatus(status: String) {
        handler.post {
            when {
                status == "scanning"  -> showStatus("🔍 Scanning soal...", true)
                status == "answering" -> showStatus("🤖 AI menjawab...", true)
                status == "done"      -> {}
                status == "idle"      -> removeStatus()
                status == "connected" -> {} // accessibility connected
                status.startsWith("error:no_question") -> {
                    showStatus("⚠️ Soal tidak terdeteksi", false)
                    handler.postDelayed({ removeStatus() }, 3500)
                }
                status.startsWith("error:no_window") -> {
                    showStatus("⚠️ Buka app ujian dulu", false)
                    handler.postDelayed({ removeStatus() }, 3500)
                }
                status.startsWith("error:") -> {
                    showStatus("❌ ${status.removePrefix("error:")}", false)
                    handler.postDelayed({ removeStatus() }, 5000)
                }
            }
        }
    }

    private fun handleQuestion(question: String, platform: String) {
        currentQuestion = question; currentPlatform = platform
        handler.post { showStatus("✏️ Mengerjakan soal", true) }
    }

    private fun handleAnswer(answer: String) {
        handler.post { showAnswer(currentQuestion, answer, currentPlatform) }
    }

    // ── HELPERS ───────────────────────────────────────────────────
    private fun removeAllViews() {
        listOf(robotView, menuView, statusView, aiPanelView).forEach {
            it?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        }
        robotView = null; menuView = null; statusView = null; aiPanelView = null
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun roundedBg(color: Int, radius: Int) =
        GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#F0F0F0"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { setMargins(dp(16), dp(2), dp(16), dp(2)) }
    }
}
