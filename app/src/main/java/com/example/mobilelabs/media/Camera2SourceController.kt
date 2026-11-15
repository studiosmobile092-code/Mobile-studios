package com.example.mobilelabs.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import com.example.mobilelabs.model.CameraWhiteBalanceMode
import kotlin.math.roundToInt

data class CameraProSettings(
    val exposureEv: Int = 0,
    val iso: Int = 0,
    val focusDistance: Float = -1f,
    val whiteBalance: CameraWhiteBalanceMode = CameraWhiteBalanceMode.AUTO,
    val zoomRatio: Float = 1.0f,
    val torchEnabled: Boolean = false
)

/**
 * Lightweight Camera2 controller for facecam & IRL camera overlay (§6.4).
 * Targets <= 30 fps (never sensor maximum while gaming), renders zero-copy to the
 * GLES compositor's SurfaceTexture input Surface, and supports live Pro Manual controls.
 */
class Camera2SourceController(
    private val context: Context,
    private val onCameraError: (String) -> Unit
) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var activeCamId: String? = null
    private var activeTargetSurface: Surface? = null
    private var activeFpsRange: Range<Int>? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    @Volatile private var currentProSettings = CameraProSettings()

    @SuppressLint("MissingPermission")
    fun start(
        useFrontCamera: Boolean,
        targetSurface: Surface,
        proSettings: CameraProSettings = currentProSettings
    ) {
        stop()
        currentProSettings = proSettings
        val camId = findCameraId(useFrontCamera) ?: run {
            onCameraError(if (useFrontCamera) "Front camera not found" else "Rear camera not found")
            return
        }

        activeCamId = camId
        activeTargetSurface = targetSurface
        val chars = runCatching { cameraManager.getCameraCharacteristics(camId) }.getOrNull()
        activeCharacteristics = chars
        val cappedRange = selectMax30FpsRange(camId)
        activeFpsRange = cappedRange

        val t = HandlerThread("MobileLabs-Camera2").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        try {
            cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startRepeatingSession(camera, targetSurface, cappedRange)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    onCameraError("Camera disconnected or claimed by another app")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    onCameraError("Camera2 error ($error)")
                }
            }, h)
        } catch (tErr: Throwable) {
            onCameraError("Camera2 open failed: ${tErr.message}")
        }
    }

    fun updateProSettings(proSettings: CameraProSettings) {
        if (currentProSettings == proSettings) return
        currentProSettings = proSettings
        val h = handler ?: return
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val surf = activeTargetSurface ?: return
        h.post {
            runCatching {
                val builder = buildConfiguredRequest(camera, surf, activeFpsRange, proSettings)
                session.setRepeatingRequest(builder.build(), null, h)
            }
        }
    }

    private fun startRepeatingSession(
        camera: CameraDevice,
        targetSurface: Surface,
        fpsRange: Range<Int>?
    ) {
        try {
            val req = buildConfiguredRequest(camera, targetSurface, fpsRange, currentProSettings)
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(targetSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        runCatching {
                            val latestReq = buildConfiguredRequest(camera, targetSurface, fpsRange, currentProSettings)
                            session.setRepeatingRequest(latestReq.build(), null, handler)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onCameraError("Camera2 session configure failed")
                    }
                },
                handler
            )
        } catch (e: Throwable) {
            onCameraError("Camera2 preview start failed: ${e.message}")
        }
    }

    private fun buildConfiguredRequest(
        camera: CameraDevice,
        targetSurface: Surface,
        fpsRange: Range<Int>?,
        pro: CameraProSettings
    ): CaptureRequest.Builder {
        return camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(targetSurface)
            if (fpsRange != null) {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            }
            val chars = activeCharacteristics

            // AE / ISO / Exposure Compensation
            val isoRange = chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            if (pro.iso > 0 && isoRange != null) {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, pro.iso.coerceIn(isoRange.lower, isoRange.upper))
                val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                if (expRange != null) {
                    set(
                        CaptureRequest.SENSOR_EXPOSURE_TIME,
                        16_666_666L.coerceIn(expRange.lower, expRange.upper)
                    )
                }
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                val evRange = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                if (evRange != null && evRange.lower < evRange.upper) {
                    set(
                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        pro.exposureEv.coerceIn(evRange.lower, evRange.upper)
                    )
                }
            }

            // Focus mode (Continuous video AF vs manual diopter distance)
            val minFocusDist = chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (pro.focusDistance >= 0f && minFocusDist > 0f) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    (pro.focusDistance.coerceIn(0f, 1f) * minFocusDist)
                )
            } else {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }

            // White balance
            val awbMode = when (pro.whiteBalance) {
                CameraWhiteBalanceMode.AUTO -> CaptureRequest.CONTROL_AWB_MODE_AUTO
                CameraWhiteBalanceMode.DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                CameraWhiteBalanceMode.TUNGSTEN -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                CameraWhiteBalanceMode.CLOUDY -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                CameraWhiteBalanceMode.FLUORESCENT -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            }
            val availAwb = chars?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            if (availAwb == null || availAwb.contains(awbMode)) {
                set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
            }

            // Optical / Digital Zoom
            val maxZoom = chars?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            val clampedZoom = pro.zoomRatio.coerceIn(1f, maxZoom.coerceAtLeast(1f))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val zoomRange = chars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRange != null) {
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, clampedZoom.coerceIn(zoomRange.lower, zoomRange.upper))
                }
            } else if (clampedZoom > 1.01f) {
                val activeRect = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (activeRect != null) {
                    val cropW = (activeRect.width() / clampedZoom).roundToInt().coerceAtLeast(2)
                    val cropH = (activeRect.height() / clampedZoom).roundToInt().coerceAtLeast(2)
                    val left = activeRect.left + (activeRect.width() - cropW) / 2
                    val top = activeRect.top + (activeRect.height() - cropH) / 2
                    set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
                }
            }

            // Flashlight / Torch
            val hasFlash = chars?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (hasFlash) {
                set(
                    CaptureRequest.FLASH_MODE,
                    if (pro.torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                )
            }
        }
    }

    private fun selectMax30FpsRange(camId: String): Range<Int>? {
        return runCatching {
            val ranges = cameraManager.getCameraCharacteristics(camId)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.upper <= 30 }
                .orEmpty()
            ranges.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
        }.getOrNull()
    }

    fun stop() {
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        activeCamId = null
        activeTargetSurface = null
        activeCharacteristics = null
        runCatching { thread?.quitSafely() }
        thread = null
        handler = null
    }

    private fun findCameraId(front: Boolean): String? {
        val desiredFacing = if (front) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == desiredFacing
            } ?: cameraManager.cameraIdList.firstOrNull()
        }.getOrNull()
    }
}

