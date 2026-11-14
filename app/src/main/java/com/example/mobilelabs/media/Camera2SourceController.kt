package com.example.mobilelabs.media

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * Lightweight Camera2 controller for facecam overlay (§6.4).
 * Targets ≤ 720p30 (never sensor maximum while gaming) and renders directly to the
 * GLES compositor's SurfaceTexture input Surface.
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

    @SuppressLint("MissingPermission")
    fun start(useFrontCamera: Boolean, targetSurface: Surface) {
        stop()
        val camId = findCameraId(useFrontCamera) ?: run {
            onCameraError(if (useFrontCamera) "Front camera not found" else "Rear camera not found")
            return
        }

        val t = HandlerThread("MobileLabs-Camera2").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        try {
            cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startRepeatingSession(camera, targetSurface)
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

    private fun startRepeatingSession(camera: CameraDevice, targetSurface: Surface) {
        try {
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(targetSurface)
            }
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(targetSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        runCatching {
                            session.setRepeatingRequest(req.build(), null, handler)
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

    fun stop() {
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
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
