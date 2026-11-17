package com.example.mobilelabs.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.mobilelabs.MobileLabsApp
import com.example.mobilelabs.model.RecordingPhase
import com.example.mobilelabs.model.SessionPhase
import com.example.mobilelabs.overlay.FloatingApertureOverlay
import com.example.mobilelabs.probes.DisplayGeometryCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service hosting MediaProjection token, VirtualDisplay, dynamic Android 14+/16
 * foreground service types, ongoing notification controls, and optional FloatingApertureOverlay.
 * Never owns session state (Iron Rule R10): delegates all state transitions to SessionController.
 */
class CaptureForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var overlay: FloatingApertureOverlay? = null
    private var lastDensityDpi: Int = DisplayMetrics.DENSITY_DEFAULT
    private var activeUseMic: Boolean = false
    private var activeUseCam: Boolean = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                val app = application as MobileLabsApp
                app.sessionController.onScreenLocked()
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            val app = application as MobileLabsApp
            releaseVirtualDisplayAndProjection()
            app.sessionController.onMediaProjectionStoppedBySystem()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width > 0 && height > 0) {
                runCatching {
                    virtualDisplay?.resize(width, height, lastDensityDpi.coerceAtLeast(DisplayMetrics.DENSITY_MEDIUM))
                }
            }
            val app = application as MobileLabsApp
            app.sessionController.onCapturedContentResized(width, height)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        val app = application as MobileLabsApp
        overlay = FloatingApertureOverlay(
            context = this,
            onToggleStream = { app.sessionController.toggleStreamFromClient() },
            onToggleRecord = { app.sessionController.toggleRecordFromClient() },
            onTogglePauseRecord = { app.sessionController.togglePauseRecord() },
            onSaveReplay = { app.sessionController.saveReplayClip() },
            onCaptureScreenshot = { app.sessionController.captureCanvasScreenshot() },
            onToggleMicMute = { app.sessionController.toggleAudioMute("audio_mic") },
            onToggleGameMute = { app.sessionController.toggleAudioMute("audio_game") },
            onNextScene = { app.sessionController.cycleNextSceneForOverlay() },
            onToggleQuickPreset = { app.sessionController.toggleQuickPresetForOverlay() },
            onOpenFullUi = {
                val openIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(openIntent)
            },
            onPositionChanged = { isLand, x, y ->
                app.sessionController.saveOverlayPosition(isLand, x, y)
            }
        )

        scope.launch {
            app.sessionController.uiState.collectLatest { state ->
                updateNotification(
                    state.sessionPhase,
                    state.recordingPhase,
                    state.replayBuffer.enabled,
                    state.statusMessage
                )
                val micMuted = state.audioChannels.firstOrNull { it.id == "audio_mic" }?.muted == true
                val gameMuted = state.audioChannels.firstOrNull { it.id == "audio_game" }?.muted == true
                val activeSceneName = state.sceneCollection.scenes
                    .firstOrNull { it.id == state.programSceneId }?.name ?: "Scene"
                overlay?.updateConfirmedState(
                    sessionPhase = state.sessionPhase,
                    recordingPhase = state.recordingPhase,
                    thermal = state.telemetry.thermal,
                    micMuted = micMuted,
                    gameMuted = gameMuted,
                    replayEnabled = state.replayBuffer.enabled,
                    sceneName = activeSceneName,
                    presetId = state.videoConfig.presetId
                )
                if (state.floatingOverlayEnabled && state.hasOverlayPermission) {
                    if (overlay?.isVisible() == false) {
                        val (x, y) = app.sessionController.getOverlayInitialPosition(
                            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        )
                        overlay?.show(x, y)
                        app.sessionController.setOverlayCurrentlyShown(overlay?.isVisible() == true)
                    }
                } else if (overlay?.isVisible() == true) {
                    overlay?.hide()
                    app.sessionController.setOverlayCurrentlyShown(false)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MobileLabsApp
        when (intent?.action) {
            ACTION_ARM_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val dataIntent = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val useMic = intent.getBooleanExtra(EXTRA_USE_MIC, false)
                val useCam = intent.getBooleanExtra(EXTRA_USE_CAM, false)

                if (resultCode == Activity.RESULT_OK && dataIntent != null) {
                    activeUseMic = useMic
                    activeUseCam = useCam
                    startForegroundWithActiveTypes(useMic, useCam)
                    attachMediaProjection(resultCode, dataIntent)
                } else {
                    app.sessionController.onArmFailed("MediaProjection permission canceled")
                    stopSelf()
                }
            }
            ACTION_STOP_STREAM -> app.sessionController.stopStreaming()
            ACTION_STOP_RECORD -> app.sessionController.stopRecording()
            ACTION_PAUSE_RECORD -> app.sessionController.pauseRecording()
            ACTION_RESUME_RECORD -> app.sessionController.resumeRecording()
            ACTION_SAVE_REPLAY -> app.sessionController.saveReplayClip()
            ACTION_SCREENSHOT -> app.sessionController.captureCanvasScreenshot()
            ACTION_DISARM -> {
                activeUseMic = false
                activeUseCam = false
                releaseVirtualDisplayAndProjection()
                app.sessionController.disarmSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SYNC_OVERLAY -> {
                if (mediaProjection != null) {
                    startForegroundWithActiveTypes(useMic = activeUseMic, useCam = activeUseCam)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithActiveTypes(useMic: Boolean, useCam: Boolean) {
        val notif = buildNotification(SessionPhase.ARMING, RecordingPhase.IDLE, false, "Arming capture...")
        var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val hasMic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val hasCam = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (useMic && hasMic) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (useCam && hasCam) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }

        startForeground(NOTIF_ID, notif, fgsType)
    }

    private fun attachMediaProjection(resultCode: Int, dataIntent: Intent) {
        val app = application as MobileLabsApp
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, dataIntent)
        if (proj == null) {
            app.sessionController.onArmFailed("Failed to obtain MediaProjection token")
            stopSelf()
            return
        }

        mediaProjection = proj
        // Must register callback BEFORE createVirtualDisplay on Android 14+
        proj.registerCallback(projectionCallback, mainHandler)

        val displayInfo = DisplayGeometryCompat.readDefaultDisplay(this)
        val densityDpi = resources.displayMetrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_MEDIUM)
        lastDensityDpi = densityDpi

        app.sessionController.attachArmedProjection(
            mediaProjection = proj,
            displayWidthPx = displayInfo.widthPx,
            displayHeightPx = displayInfo.heightPx
        ) { targetSurface, canvasW, canvasH ->
            createSingleVirtualDisplay(proj, targetSurface, canvasW, canvasH, densityDpi)
        }
    }

    private fun createSingleVirtualDisplay(
        proj: MediaProjection,
        surface: Surface,
        canvasWidth: Int,
        canvasHeight: Int,
        densityDpi: Int
    ) {
        lastDensityDpi = densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_MEDIUM)
        runCatching { virtualDisplay?.release() }
        virtualDisplay = proj.createVirtualDisplay(
            "OBSmobile-Canvas",
            canvasWidth,
            canvasHeight,
            lastDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            mainHandler
        )
    }

    private fun releaseVirtualDisplayAndProjection() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        }
        mediaProjection = null
    }

    override fun onDestroy() {
        overlay?.hide()
        releaseVirtualDisplayAndProjection()
        runCatching { unregisterReceiver(screenOffReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                }
            )
        }
    }

    private fun updateNotification(
        sessionPhase: SessionPhase,
        recordingPhase: RecordingPhase,
        replayEnabled: Boolean,
        statusMessage: String
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(sessionPhase, recordingPhase, replayEnabled, statusMessage))
    }

    private fun buildNotification(
        sessionPhase: SessionPhase,
        recordingPhase: RecordingPhase,
        replayEnabled: Boolean,
        statusMessage: String
    ): Notification {
        val openPending = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val phaseLabel = when (sessionPhase) {
            SessionPhase.LIVE -> "LIVE"
            SessionPhase.CONNECTING -> "Connecting"
            SessionPhase.RECONNECTING -> "Reconnecting"
            SessionPhase.ARMED -> "Armed"
            SessionPhase.ARMING -> "Arming"
            else -> sessionPhase.displayLabel
        }
        val recLabel = when (recordingPhase) {
            RecordingPhase.RECORDING -> "REC"
            RecordingPhase.PAUSED -> "PAUSED"
            else -> recordingPhase.displayLabel
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.mobile_labs_logo)
            .setContentTitle("OBSmobile · $phaseLabel | $recLabel")
            .setContentText(statusMessage.ifBlank { "Ready" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)

        if (sessionPhase == SessionPhase.LIVE || sessionPhase == SessionPhase.CONNECTING || sessionPhase == SessionPhase.RECONNECTING) {
            val stopStreamPi = PendingIntent.getService(
                this,
                101,
                Intent(this, CaptureForegroundService::class.java).setAction(ACTION_STOP_STREAM),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, getString(R.string.action_stop_stream), stopStreamPi)
        }
        if (recordingPhase == RecordingPhase.RECORDING || recordingPhase == RecordingPhase.PAUSED) {
            val pauseOrResumeAction = if (recordingPhase == RecordingPhase.PAUSED) {
                ACTION_RESUME_RECORD
            } else {
                ACTION_PAUSE_RECORD
            }
            val pauseLabel = if (recordingPhase == RecordingPhase.PAUSED) {
                getString(R.string.action_resume_record)
            } else {
                getString(R.string.action_pause_record)
            }
            val pauseRecPi = PendingIntent.getService(
                this,
                104,
                Intent(this, CaptureForegroundService::class.java).setAction(pauseOrResumeAction),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, pauseLabel, pauseRecPi)

            val stopRecPi = PendingIntent.getService(
                this,
                102,
                Intent(this, CaptureForegroundService::class.java).setAction(ACTION_STOP_RECORD),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, getString(R.string.action_stop_record), stopRecPi)
        } else if (replayEnabled) {
            val replayPi = PendingIntent.getService(
                this,
                105,
                Intent(this, CaptureForegroundService::class.java).setAction(ACTION_SAVE_REPLAY),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, getString(R.string.action_save_replay), replayPi)
        }
        val disarmPi = PendingIntent.getService(
            this,
            103,
            Intent(this, CaptureForegroundService::class.java).setAction(ACTION_DISARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.addAction(0, getString(R.string.action_stop_all), disarmPi)
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "obsmobile_capture_channel"
        const val NOTIF_ID = 7200
        const val ACTION_ARM_PROJECTION = "com.example.mobilelabs.ARM_PROJECTION"
        const val ACTION_STOP_STREAM = "com.example.mobilelabs.STOP_STREAM"
        const val ACTION_STOP_RECORD = "com.example.mobilelabs.STOP_RECORD"
        const val ACTION_PAUSE_RECORD = "com.example.mobilelabs.PAUSE_RECORD"
        const val ACTION_RESUME_RECORD = "com.example.mobilelabs.RESUME_RECORD"
        const val ACTION_SAVE_REPLAY = "com.example.mobilelabs.SAVE_REPLAY"
        const val ACTION_SCREENSHOT = "com.example.mobilelabs.SCREENSHOT"
        const val ACTION_DISARM = "com.example.mobilelabs.DISARM"
        const val ACTION_SYNC_OVERLAY = "com.example.mobilelabs.SYNC_OVERLAY"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_USE_MIC = "use_mic"
        const val EXTRA_USE_CAM = "use_cam"
    }
}
