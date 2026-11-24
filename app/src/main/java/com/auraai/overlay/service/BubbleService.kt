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
    private val handler = Handler(Looper.getMainLooper())

    // Views
    private var robotView: View? = null
    private var menuView: View? = null
    private var statusView: View? = null
    private var aiPanelView: View? = null

    // Params (kept for drag)
    private var robotParams: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var aiPanelParams: WindowManager.LayoutParams? = null

    private var isMenuOpen = false
    private var isAiPanelOpen = false
    private var stealthMode = false
    private var currentQuestion = ""
    private var currentPlatform = ""
    private var autoAnswerEnabled = false

    private val persistentWebViews = mutableMapOf<String, WebView>()

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
        handler.removeCallbacksAndMessages(null)
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
        val openPi = PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AuraAI Aktif")
            .setContentText("Tap robot untuk menu")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true).setSilent(true).build()
    }

    // ── ROBOT MASCOT ──────────────────────────────────────────────
    private fun showRobot() {
        val size = dp(64)
        val lp = baseLp(size, size).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(14); y = dp(200)
        }
        robotParams = lp

        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_robot_mascot)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = if (stealthMode) 0.12f else 1f
        }

        setupDragAndClick(iv, lp,
            onClick = {
                when {
                    isAiPanelOpen -> closeAiPanel()
                    isMenuOpen    -> closeMenu()
                    else          -> openMenu()
                }
            }
        )

        val pulse = ScaleAnimation(1f, 1.08f, 1f, 1.08f,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 900; repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        iv.startAnimation(pulse)

        try { wm.addView(iv, lp); robotView = iv } catch (e: Exception) { e.printStackTrace() }
    }

    // ── DRAG + CLICK helper ───────────────────────────────────────
    // Works for both robot and any overlay panel.
    // dragHandle: the view that starts the drag (can be same as root or a header)
    // root: the view passed to wm.updateViewLayout
    private fun setupDrag(
        dragHandle: View,
        root: View,
        lp: WindowManager.LayoutParams,
        onlyDrag: Boolean = false
    ) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f; var downRawY = 0f
        var startX = 0; var startY = 0
        var dragging = false

        dragHandle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = (startY + dy.toInt()).coerceAtLeast(0)
                        try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // If not dragged and not onlyDrag, let clicks fall through to onClick
                    if (!dragging && !onlyDrag) false else true
                }
                else -> false
            }
        }
    }

    private fun setupDragAndClick(view: View, lp: WindowManager.LayoutParams, onClick: () -> Unit) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f; var downRawY = 0f
        var startX = 0; var startY = 0
        var dragging = false

        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX; val dy = e.rawY - downRawY
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = (startY + dy.toInt()).coerceAtLeast(0)
                        try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragging) onClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    // ── MENU ──────────────────────────────────────────────────────
    private fun openMenu() {
        if (isMenuOpen) return
        isMenuOpen = true

        val lp = baseLp(dp(272), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            // FLAG_NOT_TOUCH_MODAL needed so clicks outside don't get swallowed
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            x = dp(14); y = dp(130)
        }
        menuParams = lp

        // Root card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
            elevation = dp(12).toFloat()
            background = roundedBg(Color.parseColor("#0F1324"), dp(20), Color.parseColor("#2A3156"), 1)
        }

        // ── HEADER (drag handle) ──────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(10), dp(12))
            // Visual drag hint
            setBackgroundColor(Color.parseColor("#111628"))
            // Rounded top only
            background = topRoundedBg(Color.parseColor("#111628"), dp(20))
        }

        val dragIndicator = TextView(this).apply {
            text = "⠿"  // braille dots = drag handle hint
            textSize = 15f
            setTextColor(Color.parseColor("#4A5578"))
            setPadding(0, 0, dp(8), 0)
        }
        val titleTv = TextView(this).apply {
            text = "AuraAI"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F1F5FF"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Close button — large touch target
        val closeBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            isClickable = true; isFocusable = true
        }
        val closeTv = TextView(this).apply {
            text = "✕"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#8892B0"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        closeBtn.addView(closeTv)
        closeBtn.setOnClickListener { closeMenu() }

        header.addView(dragIndicator)
        header.addView(titleTv)
        header.addView(closeBtn)
        card.addView(header)

        // Drag on the header
        setupDrag(header, card, lp, onlyDrag = true)

        // ── AUTO ANSWER TOGGLE ────────────────────────────────────
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(14), dp(6))
            addView(TextView(this@BubbleService).apply {
                text = "Auto jawab"
                textSize = 13f
                setTextColor(Color.parseColor("#F1F5FF"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Switch(this@BubbleService).apply {
                isChecked = autoAnswerEnabled
                setOnCheckedChangeListener { _, c ->
                    autoAnswerEnabled = c
                    AuraAccessibilityService.autoClickEnabled = c
                    if (c) showStatus("🤖 Auto jawab aktif")
                }
            })
        })

        card.addView(divider())

        // ── MENU ROWS ─────────────────────────────────────────────
        listOf(
            Triple("🔍", "Scan & jawab sekarang", 0),
            Triple("👁️", "Tampilkan jawaban saja", 1),
            Triple("📖", "Penjelasan lengkap", 2),
            Triple("🤖", "Buka AI panel", 3),
        ).forEach { (icon, label, idx) -> card.addView(menuRow(icon, label, idx)) }

        card.addView(divider())

        // ── STEALTH TOGGLE ────────────────────────────────────────
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(14), dp(10))
            addView(TextView(this@BubbleService).apply {
                text = "Mode samaran"
                textSize = 13f
                setTextColor(Color.parseColor("#8892B0"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Switch(this@BubbleService).apply {
                isChecked = stealthMode
                setOnCheckedChangeListener { _, c ->
                    stealthMode = c
                    robotView?.alpha = if (c) 0.12f else 1f
                }
            })
        })

        // Animate in from left
        card.startAnimation(TranslateAnimation(-dp(300).toFloat(), 0f, 0f, 0f).apply {
            duration = 200; interpolator = DecelerateInterpolator()
        })

        try { wm.addView(card, lp); menuView = card } catch (e: Exception) {
            isMenuOpen = false; e.printStackTrace()
        }
    }

    private fun menuRow(icon: String, label: String, idx: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            isClickable = true; isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            addView(TextView(this@BubbleService).apply {
                text = icon; textSize = 17f; setPadding(0, 0, dp(12), 0)
            })
            addView(TextView(this@BubbleService).apply {
                text = label; textSize = 13f
                setTextColor(Color.parseColor("#F1F5FF"))
            })
            setOnClickListener { closeMenu(); handler.postDelayed({ handleMenuAction(idx) }, 160) }
        }

    private fun handleMenuAction(idx: Int) {
        when (idx) {
            0 -> { AuraAccessibilityService.autoClickEnabled = true;  showStatus("🔍 Scanning...", loading = true); AuraAccessibilityService.triggerScan() }
            1 -> { AuraAccessibilityService.autoClickEnabled = false; showStatus("🔍 Scanning...", loading = true); AuraAccessibilityService.triggerScan() }
            2 -> { AuraAccessibilityService.autoClickEnabled = false; showStatus("📖 Meminta penjelasan...", loading = true); AuraAccessibilityService.instance?.doScanWithMode("explain") }
            3 -> openAiPanel()
        }
    }

    private fun closeMenu() {
        if (!isMenuOpen) return
        isMenuOpen = false
        val view = menuView ?: run { menuView = null; return }
        menuView = null
        animateOut(view, slideLeft = true) {
            try { wm.removeView(view) } catch (_: Exception) {}
        }
    }

    // ── AI PANEL ──────────────────────────────────────────────────
    private fun openAiPanel() {
        if (isAiPanelOpen) { closeAiPanel(); return }
        isAiPanelOpen = true

        val dm = resources.displayMetrics
        val lp = baseLp(
            (dm.widthPixels * 0.93).toInt(),
            (dm.heightPixels * 0.82).toInt()
        ).apply {
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.CENTER
            y = -dp(16)
        }
        aiPanelParams = lp

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(14).toFloat()
            background = roundedBg(Color.parseColor("#0A0D1A"), dp(20), Color.parseColor("#2A3156"), 1)
        }

        // Header (also drag handle)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(10), dp(14))
            background = topRoundedBg(Color.parseColor("#111628"), dp(20))
        }

        val dragIco = TextView(this).apply {
            text = "⠿"; textSize = 15f
            setTextColor(Color.parseColor("#4A5578"))
            setPadding(0, 0, dp(8), 0)
        }
        val titleTv = TextView(this).apply {
            text = "AI Panel"
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            isClickable = true; isFocusable = true
        }
        val closeTv = TextView(this).apply {
            text = "✕"; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#8892B0"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        closeBtn.addView(closeTv)
        closeBtn.setOnClickListener { closeAiPanel() }

        header.addView(dragIco); header.addView(titleTv); header.addView(closeBtn)
        container.addView(header)

        // Drag on header
        setupDrag(header, container, lp, onlyDrag = true)

        // Tab bar — only Gemini + ChatGPT
        data class AiTab(val label: String, val url: String, val color: String)
        val tabs = listOf(
            AiTab("Gemini",  "https://gemini.google.com",  "#4285F4"),
            AiTab("ChatGPT", "https://chat.openai.com",    "#10A37F"),
        )

        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#0F1324"))
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        tabScroll.addView(tabRow)
        container.addView(tabScroll)

        val webContainer = FrameLayout(this)
        container.addView(webContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Build WebViews
        tabs.forEachIndexed { i, tab ->
            val wv = persistentWebViews.getOrPut(tab.url) {
                WebView(this).apply {
                    settings.apply {
                        javaScriptEnabled = true; domStorageEnabled = true
                        databaseEnabled = true; loadWithOverviewMode = true
                        useWideViewPort = true; setSupportZoom(true)
                        builtInZoomControls = false; displayZoomControls = false
                        userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        mediaPlaybackRequiresUserGesture = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false
                    }
                    webChromeClient = WebChromeClient()
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(this@apply, true)
                    }
                    loadUrl(tab.url)
                }
            }
            webContainer.addView(wv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            wv.visibility = if (i == 0) View.VISIBLE else View.GONE
        }

        // Tab buttons
        var activeIdx = 0
        val tabBtns = mutableListOf<TextView>()
        tabs.forEachIndexed { i, tab ->
            val btn = TextView(this).apply {
                text = tab.label
                textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(16), dp(7), dp(16), dp(7))
                setTextColor(if (i == 0) Color.WHITE else Color.parseColor("#4A5578"))
                background = if (i == 0) roundedBg(Color.parseColor(tab.color), dp(20))
                             else roundedBg(Color.parseColor("#161C35"), dp(20), Color.parseColor("#2A3156"), 1)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(6) }
                setOnClickListener {
                    if (activeIdx == i) return@setOnClickListener
                    // Deactivate old
                    tabBtns[activeIdx].setTextColor(Color.parseColor("#4A5578"))
                    tabBtns[activeIdx].background = roundedBg(Color.parseColor("#161C35"), dp(20), Color.parseColor("#2A3156"), 1)
                    (webContainer.getChildAt(activeIdx) as? WebView)?.visibility = View.GONE
                    // Activate new
                    activeIdx = i
                    setTextColor(Color.WHITE)
                    background = roundedBg(Color.parseColor(tab.color), dp(20))
                    (webContainer.getChildAt(i) as? WebView)?.visibility = View.VISIBLE
                }
            }
            tabBtns.add(btn)
            tabRow.addView(btn)
        }

        // Animate in from bottom
        container.startAnimation(TranslateAnimation(0f, 0f, dp(160).toFloat(), 0f).apply {
            duration = 260; interpolator = DecelerateInterpolator()
        })

        try { wm.addView(container, lp); aiPanelView = container }
        catch (e: Exception) { isAiPanelOpen = false; e.printStackTrace() }
    }

    private fun closeAiPanel() {
        if (!isAiPanelOpen) return
        isAiPanelOpen = false
        val view = aiPanelView ?: run { aiPanelView = null; return }
        aiPanelView = null
        animateOut(view, slideBottom = true) {
            try { wm.removeView(view) } catch (_: Exception) {}
        }
    }

    // ── STATUS / ANSWER CARDS ─────────────────────────────────────
    private fun showStatus(text: String, loading: Boolean = false) {
        removeStatus()
        val dm = resources.displayMetrics
        val lp = baseLp(
            (dm.widthPixels * 0.76).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(100)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = dp(8).toFloat()
            background = roundedBg(Color.parseColor("#111628"), dp(18), Color.parseColor("#2A3156"), 1)
        }
        card.addView(TextView(this).apply {
            this.text = text; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F1F5FF"))
        })
        if (loading) {
            card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#7C6FFF"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
                    .apply { topMargin = dp(8) }
            })
        }
        card.startAnimation(TranslateAnimation(0f, 0f, dp(60).toFloat(), 0f).apply {
            duration = 220; interpolator = DecelerateInterpolator()
        })
        try { wm.addView(card, lp); statusView = card } catch (e: Exception) { e.printStackTrace() }
    }

    private fun showAnswer(question: String, answer: String, platform: String) {
        removeStatus()
        val dm = resources.displayMetrics
        val lp = baseLp(
            (dm.widthPixels * 0.90).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(10).toFloat()
            background = roundedBg(Color.parseColor("#111628"), dp(20), Color.parseColor("#2A3156"), 1)
        }

        // Platform + close row
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        if (platform.isNotBlank()) {
            topRow.addView(TextView(this).apply {
                text = platform; textSize = 10f
                setTextColor(Color.parseColor("#9B91FF"))
                background = roundedBg(Color.parseColor("#1E2545"), dp(20))
                setPadding(dp(10), dp(3), dp(10), dp(3))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        } else {
            topRow.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
        }

        // ✕ close — large touch target via FrameLayout
        val closeContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(32))
            isClickable = true; isFocusable = true
            setOnClickListener { removeStatus() }
        }
        closeContainer.addView(TextView(this).apply {
            text = "✕"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#4A5578"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        topRow.addView(closeContainer)
        card.addView(topRow)

        // Question preview
        if (question.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = question.take(90) + if (question.length > 90) "…" else ""
                textSize = 11f; maxLines = 2
                setTextColor(Color.parseColor("#4A5578"))
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            })
        }

        // Answer bubble
        card.addView(TextView(this).apply {
            text = answer; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg(Color.parseColor("#1E2545"), dp(12), Color.parseColor("#7C6FFF"), 1)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        card.startAnimation(TranslateAnimation(0f, 0f, dp(80).toFloat(), 0f).apply {
            duration = 260; interpolator = DecelerateInterpolator()
        })
        try { wm.addView(card, lp); statusView = card } catch (e: Exception) { e.printStackTrace() }

        handler.postDelayed({ removeStatus() }, 18000)
    }

    private fun removeStatus() {
        statusView?.let { v ->
            try { wm.removeView(v) } catch (_: Exception) {}
            statusView = null
        }
    }

    // ── BROADCAST HANDLERS ────────────────────────────────────────
    private fun handleStatus(status: String) {
        handler.post {
            when {
                status == "scanning"  -> showStatus("🔍 Scanning soal...", loading = true)
                status == "answering" -> showStatus("🤖 AI menjawab...", loading = true)
                status == "done"      -> {}
                status == "idle"      -> removeStatus()
                status == "connected" -> {}
                status.startsWith("error:no_question") -> {
                    showStatus("⚠️ Soal tidak terdeteksi")
                    handler.postDelayed({ removeStatus() }, 3500)
                }
                status.startsWith("error:no_window") -> {
                    showStatus("⚠️ Buka app ujian dulu")
                    handler.postDelayed({ removeStatus() }, 3500)
                }
                status.startsWith("error:") -> {
                    showStatus("❌ ${status.removePrefix("error:")}")
                    handler.postDelayed({ removeStatus() }, 5000)
                }
            }
        }
    }

    private fun handleQuestion(question: String, platform: String) {
        currentQuestion = question; currentPlatform = platform
        handler.post { showStatus("✏️ Mengerjakan soal...", loading = true) }
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

    private fun baseLp(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    private fun dp(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun roundedBg(color: Int, radius: Int, strokeColor: Int? = null, strokeDp: Int = 1) =
        GradientDrawable().apply {
            setColor(color); cornerRadius = radius.toFloat()
            strokeColor?.let { setStroke(dp(strokeDp), it) }
        }

    private fun topRoundedBg(color: Int, radius: Int): GradientDrawable {
        val r = radius.toFloat()
        return GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#1E2545"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(dp(16), dp(2), dp(16), dp(2)) }
    }

    private fun animateOut(view: View, slideLeft: Boolean = false, slideBottom: Boolean = false, onEnd: () -> Unit) {
        val anim = when {
            slideLeft -> TranslateAnimation(0f, -dp(300).toFloat(), 0f, 0f)
            slideBottom -> TranslateAnimation(0f, 0f, 0f, dp(160).toFloat())
            else -> AlphaAnimation(1f, 0f)
        }.apply {
            duration = 200; interpolator = AccelerateInterpolator()
        }
        anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(a: Animation?) {}
            override fun onAnimationRepeat(a: Animation?) {}
            override fun onAnimationEnd(a: Animation?) { handler.post(onEnd) }
        })
        try { view.startAnimation(anim) } catch (_: Exception) { handler.post(onEnd) }
    }
}
