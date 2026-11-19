package com.auraai.overlay.service

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.view.*
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
    }

    private lateinit var wm: WindowManager
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var isPanelOpen = false
    private val handler = Handler(Looper.getMainLooper())

    // Receiver untuk status dari AccessibilityService
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                "com.auraai.AURA_STATUS" -> {
                    val status = intent.getStringExtra("status") ?: return
                    updateStatus(status)
                }
                "com.auraai.AURA_QUESTION" -> {
                    val q = intent.getStringExtra("question") ?: return
                    val platform = intent.getStringExtra("platform") ?: ""
                    showQuestion(q, platform)
                }
                "com.auraai.AURA_ANSWER" -> {
                    val answer = intent.getStringExtra("answer") ?: return
                    showAnswer(answer)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotifChannel()
        startForeground(NOTIF_ID, buildNotif())

        val filter = IntentFilter().apply {
            addAction("com.auraai.AURA_STATUS")
            addAction("com.auraai.AURA_QUESTION")
            addAction("com.auraai.AURA_ANSWER")
        }
        registerReceiver(receiver, filter)
        showBubble()
    }

    override fun onDestroy() {
        isRunning = false
        unregisterReceiver(receiver)
        removeBubble()
        removePanel()
        super.onDestroy()
    }

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_STICKY
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
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, BubbleService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AuraAI Aktif")
            .setContentText("Tap bubble 🤖 untuk scan soal")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true).setSilent(true).build()
    }

    // ── BUBBLE ────────────────────────────────────────────────────
    private fun showBubble() {
        val inf = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        bubbleView = inf.inflate(R.layout.view_bubble, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16; y = 220
        }

        setupDrag(bubbleView!!, p)
        bubbleView!!.setOnClickListener { if (isPanelOpen) closePanel() else openPanel() }
        wm.addView(bubbleView, p)
        bubbleView!!.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.bubble_pulse))
    }

    private fun setupDrag(v: View, p: WindowManager.LayoutParams) {
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var dragging = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix=p.x; iy=p.y; tx=e.rawX; ty=e.rawY; dragging=false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(tx-e.rawX)>5||Math.abs(ty-e.rawY)>5) dragging=true
                    p.x=ix+(tx-e.rawX).toInt(); p.y=iy+(e.rawY-ty).toInt()
                    wm.updateViewLayout(v, p); true
                }
                MotionEvent.ACTION_UP -> { if (!dragging) v.performClick(); true }
                else -> false
            }
        }
    }

    private fun removeBubble() { bubbleView?.let { wm.removeView(it); bubbleView=null } }

    // ── PANEL ─────────────────────────────────────────────────────
    private fun openPanel() {
        if (isPanelOpen) return
        isPanelOpen = true

        val inf = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        panelView = inf.inflate(R.layout.view_panel, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val p = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 60 }

        setupPanelUI()
        wm.addView(panelView, p)
        panelView!!.startAnimation(
            TranslateAnimation(0f,0f,400f,0f).apply {
                duration=220; interpolator=DecelerateInterpolator() })
    }

    private fun setupPanelUI() {
        val panel = panelView ?: return
        val tvPlatform  = panel.findViewById<TextView>(R.id.tvPlatform)
        val tvQuestion  = panel.findViewById<TextView>(R.id.tvQuestion)
        val tvAnswer    = panel.findViewById<TextView>(R.id.tvAnswer)
        val tvStatus    = panel.findViewById<TextView>(R.id.tvStatus)
        val switchAuto  = panel.findViewById<Switch>(R.id.switchAutoClick)
        val progressBar = panel.findViewById<ProgressBar>(R.id.progressBar)

        // State awal
        switchAuto.isChecked = AuraAccessibilityService.autoClickEnabled
        switchAuto.setOnCheckedChangeListener { _, checked ->
            AuraAccessibilityService.autoClickEnabled = checked
            tvStatus.text = if (checked) "Auto-click ON — scan otomatis saat soal berubah"
            else "Auto-click OFF — tap Scan untuk jawab manual"
        }

        panel.findViewById<Button>(R.id.btnScan).setOnClickListener {
            if (AuraAccessibilityService.instance == null) {
                tvStatus.text = "⚠ Accessibility Service belum aktif!\nBuka Pengaturan → Aksesibilitas → AuraAI"
                return@setOnClickListener
            }
            progressBar.visibility = View.VISIBLE
            tvStatus.text = "🔍 Scanning layar..."
            tvQuestion.text = ""
            tvAnswer.text = ""
            AuraAccessibilityService.triggerScan()
        }

        panel.findViewById<Button>(R.id.btnClose).setOnClickListener { closePanel() }
    }

    private fun updateStatus(status: String) {
        val panel = panelView ?: return
        val tvStatus = panel.findViewById<TextView>(R.id.tvStatus) ?: return
        val pb = panel.findViewById<ProgressBar>(R.id.progressBar)

        handler.post {
            when {
                status == "scanning"  -> { pb?.visibility=View.VISIBLE; tvStatus.text="🔍 Scanning soal..." }
                status == "answering" -> { pb?.visibility=View.VISIBLE; tvStatus.text="🤖 AI sedang menjawab..." }
                status == "done"      -> { pb?.visibility=View.GONE;    tvStatus.text="✅ Selesai" }
                status == "connected" -> tvStatus.text = "✅ Accessibility aktif — siap scan"
                status.startsWith("error:no_question") -> {
                    pb?.visibility=View.GONE
                    tvStatus.text="⚠ Soal tidak terdeteksi. Pastikan soal terlihat di layar."
                }
                status.startsWith("error:no_window") -> {
                    pb?.visibility=View.GONE
                    tvStatus.text="⚠ Tidak bisa baca layar. Buka app ujian dulu."
                }
                status.startsWith("error:") -> {
                    pb?.visibility=View.GONE
                    tvStatus.text="❌ ${status.removePrefix("error:")}"
                }
            }
        }
    }

    private fun showQuestion(question: String, platform: String) {
        val panel = panelView ?: return
        handler.post {
            panel.findViewById<TextView>(R.id.tvPlatform)?.text = "📱 $platform"
            panel.findViewById<TextView>(R.id.tvQuestion)?.text = question
        }
    }

    private fun showAnswer(answer: String) {
        val panel = panelView ?: return
        handler.post {
            panel.findViewById<TextView>(R.id.tvAnswer)?.text = answer
            panel.findViewById<ProgressBar>(R.id.progressBar)?.visibility = View.GONE
        }
    }

    private fun closePanel() {
        isPanelOpen = false
        val anim = TranslateAnimation(0f,0f,0f,400f).apply {
            duration=200; interpolator=AccelerateInterpolator()
            setAnimationListener(object: Animation.AnimationListener {
                override fun onAnimationStart(a: Animation?) {}
                override fun onAnimationRepeat(a: Animation?) {}
                override fun onAnimationEnd(a: Animation?) { removePanel() }
            })
        }
        panelView?.startAnimation(anim)
    }

    private fun removePanel() {
        panelView?.let { try { wm.removeView(it) } catch (_:Exception) {}; panelView=null }
    }
}
