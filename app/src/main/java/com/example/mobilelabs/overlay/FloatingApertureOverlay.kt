package com.example.mobilelabs.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.example.R
import com.example.mobilelabs.model.PresetId
import com.example.mobilelabs.model.RecordingPhase
import com.example.mobilelabs.model.SessionPhase
import com.example.mobilelabs.model.ThermalSummary
import kotlin.math.abs

/**
 * Optional Floating Aperture Control v2 (§10, M2.6).
 * Uses plain Android Views inside TYPE_APPLICATION_OVERLAY (no Compose, no Qt, no animation loop).
 * Features:
 * - Zero CPU when idle; dims after 3 s; menu auto-collapses after 4 s
 * - Deliberate double-tap confirmation on Stop Live / Stop Rec
 * - Drag + edge snap + long-press 10s temporary hide
 * - Compact 2-row v2 quick bar: Stream, Record, Mute Mic, Mute Game, Next Scene, Preset, Open UI
 */
class FloatingApertureOverlay(
    private val context: Context,
    private val onToggleStream: () -> Unit,
    private val onToggleRecord: () -> Unit,
    private val onToggleMicMute: () -> Unit,
    private val onToggleGameMute: () -> Unit,
    private val onNextScene: () -> Unit,
    private val onToggleQuickPreset: () -> Unit,
    private val onOpenFullUi: () -> Unit,
    private val onPositionChanged: (isLandscape: Boolean, x: Int, y: Int) -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var rootContainer: LinearLayout? = null
    private var badgeView: ApertureBadgeView? = null
    private var menuContainer: LinearLayout? = null
    private var streamBtn: TextView? = null
    private var recordBtn: TextView? = null
    private var micMuteBtn: TextView? = null
    private var gameMuteBtn: TextView? = null
    private var sceneBtn: TextView? = null
    private var presetBtn: TextView? = null
    private var openUiBtn: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isShowing = false
    private var isMenuExpanded = false
    private var pendingStopStreamConfirm = false
    private var pendingStopRecordConfirm = false

    private var currentSessionPhase = SessionPhase.IDLE
    private var currentRecPhase = RecordingPhase.IDLE
    private var currentThermal = ThermalSummary()
    private var currentMicMuted = false
    private var currentGameMuted = false
    private var currentSceneName = "Scene"
    private var currentPresetId = PresetId.BALANCED

    private val dimRunnable = Runnable {
        if (!isMenuExpanded) {
            badgeView?.alpha = 0.50f
        }
    }

    private val collapseRunnable = Runnable {
        collapseMenu()
    }

    private val unhideRunnable = Runnable {
        rootContainer?.visibility = View.VISIBLE
        scheduleDim()
    }

    fun show(initialX: Int, initialY: Int) {
        if (isShowing || !Settings.canDrawOverlays(context)) return

        val density = context.resources.displayMetrics.density
        val badgeSizePx = (52 * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = ApertureBadgeView(context).apply {
            layoutParams = LinearLayout.LayoutParams(badgeSizePx, badgeSizePx)
            contentDescription = "Mobile Labs floating control"
        }

        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                setColor("#EB16161A".toColorInt())
                cornerRadius = 10 * density
                setStroke((1 * density).toInt(), "#34343D".toColorInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (8 * density).toInt()
            }
        }

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val sBtn = buildMenuButton("Stream", density) { handleStreamTap() }
        val rBtn = buildMenuButton("Record", density) { handleRecordTap() }
        val mBtn = buildMenuButton("Mic", density) {
            rescheduleAutoCollapse()
            onToggleMicMute()
        }
        val gBtn = buildMenuButton("Game", density) {
            rescheduleAutoCollapse()
            onToggleGameMute()
        }
        val scBtn = buildMenuButton("Scene", density) {
            rescheduleAutoCollapse()
            onNextScene()
        }
        val pBtn = buildMenuButton("Preset", density) {
            rescheduleAutoCollapse()
            onToggleQuickPreset()
        }
        val uBtn = buildMenuButton("Open UI", density) {
            collapseMenu()
            onOpenFullUi()
        }

        row1.addView(sBtn)
        row1.addView(rBtn)
        row1.addView(mBtn)
        row1.addView(gBtn)
        row2.addView(scBtn)
        row2.addView(pBtn)
        row2.addView(uBtn)
        menu.addView(row1)
        menu.addView(row2)

        root.addView(badge)
        root.addView(menu)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        attachTouchHandler(badge, params)
        root.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && isMenuExpanded) {
                collapseMenu()
                view.performClick()
            }
            false
        }

        runCatching {
            windowManager.addView(root, params)
            rootContainer = root
            badgeView = badge
            menuContainer = menu
            streamBtn = sBtn
            recordBtn = rBtn
            micMuteBtn = mBtn
            gameMuteBtn = gBtn
            sceneBtn = scBtn
            presetBtn = pBtn
            openUiBtn = uBtn
            layoutParams = params
            isShowing = true
            renderConfirmedState()
            scheduleDim()
        }
    }

    fun updateConfirmedState(
        sessionPhase: SessionPhase,
        recordingPhase: RecordingPhase,
        thermal: ThermalSummary,
        micMuted: Boolean,
        gameMuted: Boolean,
        sceneName: String,
        presetId: PresetId
    ) {
        currentSessionPhase = sessionPhase
        currentRecPhase = recordingPhase
        currentThermal = thermal
        currentMicMuted = micMuted
        currentGameMuted = gameMuted
        currentSceneName = sceneName
        currentPresetId = presetId
        if (!isShowing) return
        handler.post {
            if (!Settings.canDrawOverlays(context)) {
                hide()
                return@post
            }
            renderConfirmedState()
        }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        if (!isShowing) return
        runCatching {
            rootContainer?.let { windowManager.removeView(it) }
        }
        rootContainer = null
        badgeView = null
        menuContainer = null
        streamBtn = null
        recordBtn = null
        micMuteBtn = null
        gameMuteBtn = null
        sceneBtn = null
        presetBtn = null
        openUiBtn = null
        layoutParams = null
        isShowing = false
        isMenuExpanded = false
    }

    fun isVisible(): Boolean = isShowing

    private fun renderConfirmedState() {
        val badge = badgeView ?: return
        badge.updateState(currentSessionPhase, currentRecPhase, currentThermal.shouldWarnBanner)

        val isLive = currentSessionPhase == SessionPhase.LIVE ||
            currentSessionPhase == SessionPhase.CONNECTING ||
            currentSessionPhase == SessionPhase.RECONNECTING
        val isRec = currentRecPhase == RecordingPhase.RECORDING

        streamBtn?.text = when {
            pendingStopStreamConfirm -> "Confirm"
            isLive -> "Stop Live"
            currentSessionPhase == SessionPhase.ARMED -> "Go Live"
            else -> "Arm UI"
        }
        recordBtn?.text = when {
            pendingStopRecordConfirm -> "Confirm"
            isRec -> "Stop Rec"
            currentSessionPhase == SessionPhase.ARMED || isLive -> "Rec"
            else -> "Arm UI"
        }
        micMuteBtn?.text = if (currentMicMuted) "Mic: OFF" else "Mic: ON"
        gameMuteBtn?.text = if (currentGameMuted) "Game: OFF" else "Game: ON"
        sceneBtn?.text = context.getString(R.string.overlay_next_scene, currentSceneName.take(8))
        presetBtn?.text = if (currentPresetId == PresetId.LOW_OVERHEAD) "Low 30fps" else "Bal 60fps"
    }

    private fun handleStreamTap() {
        val isLive = currentSessionPhase == SessionPhase.LIVE ||
            currentSessionPhase == SessionPhase.CONNECTING ||
            currentSessionPhase == SessionPhase.RECONNECTING
        if (isLive) {
            if (!pendingStopStreamConfirm) {
                pendingStopStreamConfirm = true
                renderConfirmedState()
                rescheduleAutoCollapse()
                return
            }
            pendingStopStreamConfirm = false
            collapseMenu()
            onToggleStream()
        } else if (currentSessionPhase == SessionPhase.ARMED) {
            collapseMenu()
            onToggleStream()
        } else {
            collapseMenu()
            onOpenFullUi()
        }
    }

    private fun handleRecordTap() {
        val isRec = currentRecPhase == RecordingPhase.RECORDING
        if (isRec) {
            if (!pendingStopRecordConfirm) {
                pendingStopRecordConfirm = true
                renderConfirmedState()
                rescheduleAutoCollapse()
                return
            }
            pendingStopRecordConfirm = false
            collapseMenu()
            onToggleRecord()
        } else if (currentSessionPhase == SessionPhase.ARMED || currentSessionPhase == SessionPhase.LIVE) {
            collapseMenu()
            onToggleRecord()
        } else {
            collapseMenu()
            onOpenFullUi()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchHandler(badge: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var moved = false
        var downAtMs = 0L

        badge.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(dimRunnable)
                    badge.alpha = 1.0f
                    startX = params.x
                    startY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    moved = false
                    downAtMs = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        moved = true
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(rootContainer, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val heldMs = System.currentTimeMillis() - downAtMs
                    if (moved) {
                        snapToNearestEdge(params)
                    } else if (heldMs >= 650L) {
                        collapseMenu()
                        rootContainer?.visibility = View.GONE
                        handler.postDelayed(unhideRunnable, 10_000L)
                    } else {
                        v.performClick()
                        toggleMenu()
                    }
                    scheduleDim()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToNearestEdge(params: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val badgeW = badgeView?.width ?: 120
        val center = params.x + badgeW / 2
        params.x = if (center < metrics.widthPixels / 2) 16 else (metrics.widthPixels - badgeW - 16).coerceAtLeast(16)
        params.y = params.y.coerceIn(24, (metrics.heightPixels - badgeW - 24).coerceAtLeast(24))
        runCatching { windowManager.updateViewLayout(rootContainer, params) }
        val isLand = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        onPositionChanged(isLand, params.x, params.y)
    }

    private fun toggleMenu() {
        if (isMenuExpanded) {
            collapseMenu()
        } else {
            isMenuExpanded = true
            pendingStopStreamConfirm = false
            pendingStopRecordConfirm = false
            menuContainer?.visibility = View.VISIBLE
            renderConfirmedState()
            rescheduleAutoCollapse()
        }
    }

    private fun collapseMenu() {
        isMenuExpanded = false
        pendingStopStreamConfirm = false
        pendingStopRecordConfirm = false
        menuContainer?.visibility = View.GONE
        handler.removeCallbacks(collapseRunnable)
        scheduleDim()
    }

    private fun rescheduleAutoCollapse() {
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, 4000L)
    }

    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
        handler.postDelayed(dimRunnable, 3000L)
    }

    private fun buildMenuButton(label: String, density: Float, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor("#F2F2F2".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            minHeight = (44 * density).toInt()
            minWidth = (58 * density).toInt()
            gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            background = GradientDrawable().apply {
                setColor("#720000".toColorInt())
                cornerRadius = 6 * density
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (5 * density).toInt()
            }
            setOnClickListener { onClick() }
        }
    }

    private class ApertureBadgeView(context: Context) : LinearLayout(context) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = "#CC16161A".toColorInt()
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = "#FF4D4F".toColorInt()
        }
        private var showLiveDot = false
        private var ringColor = Color.GRAY

        init {
            setWillNotDraw(false)
            gravity = Gravity.CENTER
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.mobile_labs_logo)
                val pad = (10 * context.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        fun updateState(session: SessionPhase, rec: RecordingPhase, thermalWarn: Boolean) {
            ringColor = when {
                session == SessionPhase.ERROR || rec == RecordingPhase.ERROR -> "#FF4D4F".toColorInt()
                session == SessionPhase.LIVE -> "#A30000".toColorInt()
                rec == RecordingPhase.RECORDING -> "#720000".toColorInt()
                thermalWarn -> "#F5A524".toColorInt()
                session == SessionPhase.ARMED -> Color.WHITE
                else -> "#777782".toColorInt()
            }
            showLiveDot = (session == SessionPhase.LIVE || rec == RecordingPhase.RECORDING)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = (minOf(width, height) / 2f) - 4f
            canvas.drawCircle(cx, cy, r, fillPaint)
            ringPaint.color = ringColor
            canvas.drawCircle(cx, cy, r, ringPaint)
            super.onDraw(canvas)
            if (showLiveDot) {
                canvas.drawCircle(width - 12f, 12f, 7f, dotPaint)
            }
        }
    }
}
