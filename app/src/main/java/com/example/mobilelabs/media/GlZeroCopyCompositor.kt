package com.example.mobilelabs.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.example.mobilelabs.model.LutPreset
import com.example.mobilelabs.model.SceneModel
import com.example.mobilelabs.model.ScreenFitMode
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.model.TransitionType
import com.example.mobilelabs.model.VideoConfig
import com.example.mobilelabs.model.VideoFilterConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Multi-layer GLES3/EGL zero-copy compositor (Phase 2: M2.1, M2.3, M2.4).
 *
 * Supports:
 * - Zero-copy VirtualDisplay OES, Camera2 OES, and MediaSourceDecoder OES surfaces
 * - Size-aware cached GL_TEXTURE_2D for Image & Text sources (bounded LRU, evicted on hide)
 * - Solid Color quads
 * - GPU-only VideoFilterConfig fragment shaders (Color Correction, Chroma Key, Sharpen, 4 LUTs)
 * - Studio Mode split preview (left = Preview, right = Program) while encoder ALWAYS gets Program
 * - 5 Transitions (Cut, Fade, Slide, Swipe, Fade-to-Color)
 * - Preview paused immediately on ON_STOP (Iron Rule R9)
 */
class GlZeroCopyCompositor {

    data class CompositorStats(
        val captureCadenceFps: Float,
        val compositorFps: Float,
        val renderSkippedFrames: Long,
        val captureGapCount: Long,
        val compositorPassMicros: Long
    )

    private data class CachedTexture(
        val texId: Int,
        val key: String,
        val width: Int,
        val height: Int,
        var lastUsedFrame: Long
    )

    private data class ActiveTransition(
        val fromScene: SceneModel,
        val type: TransitionType,
        val durationMs: Int,
        val colorArgb: Long,
        val startedAtMs: Long
    )

    private class ShaderLocations(val programId: Int) {
        val uTexMatrix = GLES30.glGetUniformLocation(programId, "uTexMatrix")
        val uSampler = GLES30.glGetUniformLocation(programId, "uSampler")
        val uIsSolid = GLES30.glGetUniformLocation(programId, "uIsSolid")
        val uSolidColor = GLES30.glGetUniformLocation(programId, "uSolidColor")
        val uOpacity = GLES30.glGetUniformLocation(programId, "uOpacity")
        val uColorEnabled = GLES30.glGetUniformLocation(programId, "uColorEnabled")
        val uBrightness = GLES30.glGetUniformLocation(programId, "uBrightness")
        val uContrast = GLES30.glGetUniformLocation(programId, "uContrast")
        val uSaturation = GLES30.glGetUniformLocation(programId, "uSaturation")
        val uGamma = GLES30.glGetUniformLocation(programId, "uGamma")
        val uChromaEnabled = GLES30.glGetUniformLocation(programId, "uChromaEnabled")
        val uChromaColor = GLES30.glGetUniformLocation(programId, "uChromaColor")
        val uChromaParams = GLES30.glGetUniformLocation(programId, "uChromaParams")
        val uSharpenEnabled = GLES30.glGetUniformLocation(programId, "uSharpenEnabled")
        val uSharpenAmount = GLES30.glGetUniformLocation(programId, "uSharpenAmount")
        val uTexelSize = GLES30.glGetUniformLocation(programId, "uTexelSize")
        val uLutMode = GLES30.glGetUniformLocation(programId, "uLutMode")
        val uLutIntensity = GLES30.glGetUniformLocation(programId, "uLutIntensity")
    }

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglConfig: EGLConfig? = null
    private var dummyPbuffer: EGLSurface? = null
    private var encoderEglSurface: EGLSurface? = null
    private var previewEglSurface: EGLSurface? = null

    private var oesLocs: ShaderLocations? = null
    private var tex2dLocs: ShaderLocations? = null
    private var quadBuffer: FloatBuffer? = null

    private var screenTexId = 0
    private var screenSurfaceTexture: SurfaceTexture? = null
    var screenInputSurface: Surface? = null
        private set

    private var cameraTexId = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null
    var cameraInputSurface: Surface? = null
        private set

    private var mediaTexId = 0
    private var mediaSurfaceTexture: SurfaceTexture? = null
    private var mediaInputSurface: Surface? = null
    private var activeMediaDecoder: MediaSourceDecoder? = null
    private var activeMediaSignature: String? = null
    private var onMediaErrorCallback: ((String, String) -> Unit)? = null

    private val screenFrameAvailable = AtomicBoolean(false)
    private val cameraFrameAvailable = AtomicBoolean(false)
    private val mediaFrameAvailable = AtomicBoolean(false)
    private val previewEnabled = AtomicBoolean(true)
    private val running = AtomicBoolean(false)

    private val configRef = AtomicReference(VideoConfig())
    private val programSceneRef = AtomicReference<SceneModel?>(null)
    private val previewSceneRef = AtomicReference<SceneModel?>(null)
    private val studioModeRef = AtomicBoolean(false)
    private val activeTransitionRef = AtomicReference<ActiveTransition?>(null)
    private val cameraFrontRef = AtomicBoolean(true)

    // Bounded GPU texture cache (max 16 images/text overlays)
    private val textureCache = LinkedHashMap<String, CachedTexture>(16, 0.75f, true)
    private var renderFrameCounter = 0L

    private var previewWidth = 0
    private var previewHeight = 0
    private var sourceDisplayWidth = 1920
    private var sourceDisplayHeight = 1200

    private val captureArrivalCount = AtomicInteger(0)
    private val compositorTickCount = AtomicInteger(0)
    private val renderSkippedCount = AtomicLong(0)
    private val captureGapCount = AtomicLong(0)
    private val lastPassMicros = AtomicLong(0)
    private var lastCaptureArrivalNs = 0L
    private var lastStatsWindowNs = 0L
    @Volatile private var cachedCaptureFps = 0f
    @Volatile private var cachedCompositorFps = 0f

    private val screenMatrix = FloatArray(16)
    private val cameraMatrix = FloatArray(16)
    private val mediaMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16).apply {
        android.opengl.Matrix.setIdentityM(this, 0)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val tickStartNs = SystemClock.elapsedRealtimeNanos()
            renderTick(tickStartNs)
            val cfg = configRef.get()
            val framePeriodNs = 1_000_000_000L / max(1, cfg.fps)
            val elapsedNs = SystemClock.elapsedRealtimeNanos() - tickStartNs
            lastPassMicros.set(elapsedNs / 1000L)
            if (elapsedNs > framePeriodNs * 2) {
                renderSkippedCount.incrementAndGet()
            }
            val delayMs = max(1L, (framePeriodNs - elapsedNs) / 1_000_000L)
            glHandler?.postDelayed(this, delayMs)
        }
    }

    fun start(
        videoConfig: VideoConfig,
        displayWidthPx: Int,
        displayHeightPx: Int,
        onMediaError: (sourceId: String, reason: String) -> Unit = { _, _ -> },
        onSurfacesReady: (screenSurface: Surface, cameraSurface: Surface) -> Unit
    ) {
        if (running.getAndSet(true)) return
        configRef.set(videoConfig)
        sourceDisplayWidth = max(1, displayWidthPx)
        sourceDisplayHeight = max(1, displayHeightPx)
        onMediaErrorCallback = onMediaError
        lastStatsWindowNs = SystemClock.elapsedRealtimeNanos()

        val thread = HandlerThread("MobileLabs-GLES", Process.THREAD_PRIORITY_DISPLAY)
        thread.start()
        glThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler

        handler.post {
            initEglAndPrograms()
            createInputTextures()
            val screenSurf = screenInputSurface
            val camSurf = cameraInputSurface
            if (screenSurf != null && camSurf != null) {
                onSurfacesReady(screenSurf, camSurf)
            }
            handler.post(tickRunnable)
        }
    }

    fun updateVideoConfig(videoConfig: VideoConfig) {
        configRef.set(videoConfig)
    }

    fun updateScenesForRender(
        programScene: SceneModel?,
        previewScene: SceneModel?,
        studioModeEnabled: Boolean
    ) {
        programSceneRef.set(programScene)
        previewSceneRef.set(previewScene)
        studioModeRef.set(studioModeEnabled)
        val camSrc = programScene?.sources?.firstOrNull { it.type == SourceType.CAMERA_2 && it.visible }
            ?: previewScene?.sources?.firstOrNull { it.type == SourceType.CAMERA_2 && it.visible }
        if (camSrc != null) {
            cameraFrontRef.set(camSrc.useFrontCamera)
        }
        syncMediaSourceDecoder(programScene, previewScene, studioModeEnabled)
    }

    fun triggerTransition(
        fromScene: SceneModel?,
        type: TransitionType,
        durationMs: Int,
        colorArgb: Long
    ) {
        if (fromScene == null || type == TransitionType.CUT) {
            activeTransitionRef.set(null)
            return
        }
        activeTransitionRef.set(
            ActiveTransition(
                fromScene = fromScene,
                type = type,
                durationMs = durationMs.coerceIn(50, 2500),
                colorArgb = colorArgb,
                startedAtMs = SystemClock.elapsedRealtime()
            )
        )
    }

    fun attachEncoderSurface(surface: Surface?) {
        glHandler?.post {
            destroySurface(encoderEglSurface)
            encoderEglSurface = null
            val disp = eglDisplay
            if (surface != null && surface.isValid && disp != null && disp != EGL14.EGL_NO_DISPLAY) {
                encoderEglSurface = EGL14.eglCreateWindowSurface(
                    disp, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
                )
            }
        }
    }

    fun attachPreviewSurface(surface: Surface?, width: Int, height: Int) {
        glHandler?.post {
            destroySurface(previewEglSurface)
            previewEglSurface = null
            previewWidth = width
            previewHeight = height
            val disp = eglDisplay
            if (surface != null && surface.isValid && disp != null && disp != EGL14.EGL_NO_DISPLAY) {
                previewEglSurface = EGL14.eglCreateWindowSurface(
                    disp, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
                )
            }
        }
    }

    fun setPreviewEnabled(enabled: Boolean) {
        previewEnabled.set(enabled)
    }

    fun snapshotStats(): CompositorStats {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val dtSec = (nowNs - lastStatsWindowNs) / 1_000_000_000f
        if (dtSec >= 0.4f) {
            cachedCaptureFps = captureArrivalCount.getAndSet(0) / dtSec
            cachedCompositorFps = compositorTickCount.getAndSet(0) / dtSec
            lastStatsWindowNs = nowNs
        }
        return CompositorStats(
            captureCadenceFps = cachedCaptureFps,
            compositorFps = cachedCompositorFps,
            renderSkippedFrames = renderSkippedCount.get(),
            captureGapCount = captureGapCount.get(),
            compositorPassMicros = lastPassMicros.get()
        )
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        activeMediaDecoder?.stop()
        activeMediaDecoder = null
        activeMediaSignature = null

        val handler = glHandler
        val thread = glThread
        handler?.removeCallbacks(tickRunnable)
        handler?.post {
            releaseGlResources()
            thread?.quitSafely()
        }
        glHandler = null
        glThread = null
    }

    private fun syncMediaSourceDecoder(
        programScene: SceneModel?,
        previewScene: SceneModel?,
        studioModeEnabled: Boolean
    ) {
        val mediaSrc = programScene?.sources?.firstOrNull {
            it.type == SourceType.MEDIA_SOURCE && it.visible && !it.mediaPathOrUri.isNullOrBlank()
        } ?: if (studioModeEnabled) {
            previewScene?.sources?.firstOrNull {
                it.type == SourceType.MEDIA_SOURCE && it.visible && !it.mediaPathOrUri.isNullOrBlank()
            }
        } else null

        if (mediaSrc == null) {
            activeMediaDecoder?.stop()
            activeMediaDecoder = null
            activeMediaSignature = null
            return
        }

        val sig = "${mediaSrc.id}|${mediaSrc.mediaPathOrUri}"
        if (sig == activeMediaSignature && activeMediaDecoder != null) {
            activeMediaDecoder?.loopPlayback = mediaSrc.mediaLoop
            activeMediaDecoder?.isPlaying = mediaSrc.mediaPlaying
            return
        }

        activeMediaDecoder?.stop()
        activeMediaDecoder = null
        val surf = mediaInputSurface ?: return
        activeMediaSignature = sig
        activeMediaDecoder = MediaSourceDecoder(
            filePath = mediaSrc.mediaPathOrUri.orEmpty(),
            outputSurface = surf,
            onError = { reason ->
                onMediaErrorCallback?.invoke(mediaSrc.id, reason)
            }
        ).apply {
            loopPlayback = mediaSrc.mediaLoop
            isPlaying = mediaSrc.mediaPlaying
            start()
        }
    }

    private fun initEglAndPrograms() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        dummyPbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0
        )
        EGL14.eglMakeCurrent(eglDisplay, dummyPbuffer, dummyPbuffer, eglContext)

        val vertexSrc = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aTex;
            uniform mat4 uTexMatrix;
            out vec2 vTex;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        oesLocs = ShaderLocations(buildProgram(vertexSrc, buildFragmentShader(isOes = true)))
        tex2dLocs = ShaderLocations(buildProgram(vertexSrc, buildFragmentShader(isOes = false)))

        quadBuffer = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun buildFragmentShader(isOes: Boolean): String {
        val ext = if (isOes) "#extension GL_OES_EGL_image_external_essl3 : require\n" else ""
        val samplerType = if (isOes) "samplerExternalOES" else "sampler2D"
        return """
            #version 300 es
            $ext
            precision mediump float;
            in vec2 vTex;
            uniform $samplerType uSampler;
            uniform int uIsSolid;
            uniform vec4 uSolidColor;
            uniform float uOpacity;
            uniform int uColorEnabled;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uGamma;
            uniform int uChromaEnabled;
            uniform vec3 uChromaColor;
            uniform vec3 uChromaParams;
            uniform int uSharpenEnabled;
            uniform float uSharpenAmount;
            uniform vec2 uTexelSize;
            uniform int uLutMode;
            uniform float uLutIntensity;
            out vec4 outColor;

            vec3 applyLut(vec3 c, int mode, float intensity) {
                vec3 graded = c;
                if (mode == 1) {
                    // Vivid Gaming: punchy contrast & saturation boost
                    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
                    graded = clamp(mix(vec3(l), c, 1.32) * 1.06 - 0.03, 0.0, 1.0);
                } else if (mode == 2) {
                    // Teal & Orange
                    vec3 shadows = vec3(c.r * 0.88, c.g * 1.02, min(1.0, c.b * 1.16 + 0.04));
                    vec3 highs = vec3(min(1.0, c.r * 1.14 + 0.04), c.g * 1.01, c.b * 0.85);
                    float l = dot(c, vec3(0.299, 0.587, 0.114));
                    graded = mix(shadows, highs, smoothstep(0.18, 0.82, l));
                } else if (mode == 3) {
                    // Crisp Mono
                    float l = dot(c, vec3(0.299, 0.587, 0.114));
                    graded = vec3(clamp((l - 0.5) * 1.2 + 0.5, 0.0, 1.0));
                } else if (mode == 4) {
                    // Warm Cinema
                    graded = clamp(vec3(c.r * 1.08 + 0.02, c.g * 1.01, c.b * 0.90), 0.0, 1.0);
                }
                return mix(c, graded, intensity);
            }

            void main() {
                vec4 base = (uIsSolid == 1) ? uSolidColor : texture(uSampler, vTex);
                vec3 rgb = base.rgb;
                float alpha = base.a * uOpacity;

                if (uIsSolid == 0 && uSharpenEnabled == 1) {
                    vec3 n = texture(uSampler, vTex + vec2(0.0, uTexelSize.y)).rgb;
                    vec3 s = texture(uSampler, vTex - vec2(0.0, uTexelSize.y)).rgb;
                    vec3 e = texture(uSampler, vTex + vec2(uTexelSize.x, 0.0)).rgb;
                    vec3 w = texture(uSampler, vTex - vec2(uTexelSize.x, 0.0)).rgb;
                    vec3 blur = (n + s + e + w) * 0.25;
                    rgb = clamp(rgb + (rgb - blur) * uSharpenAmount * 1.6, 0.0, 1.0);
                }

                if (uChromaEnabled == 1) {
                    float dist = distance(rgb, uChromaColor);
                    float sim = uChromaParams.x;
                    float smoothRange = max(0.005, uChromaParams.y);
                    float spill = uChromaParams.z;
                    float keyAlpha = smoothstep(sim, sim + smoothRange, dist);
                    alpha *= keyAlpha;
                    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                    rgb = mix(vec3(luma), rgb, clamp(keyAlpha + (1.0 - spill), 0.0, 1.0));
                }

                if (uColorEnabled == 1) {
                    rgb = pow(max(rgb, vec3(0.0001)), vec3(1.0 / max(0.1, uGamma)));
                    rgb = (rgb - 0.5) * uContrast + 0.5 + vec3(uBrightness);
                    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                    rgb = mix(vec3(luma), rgb, uSaturation);
                    rgb = clamp(rgb, 0.0, 1.0);
                }

                if (uLutMode > 0) {
                    rgb = applyLut(rgb, uLutMode, uLutIntensity);
                }

                outColor = vec4(rgb, clamp(alpha, 0.0, 1.0));
            }
        """.trimIndent()
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return prog
    }

    private fun createInputTextures() {
        val textures = IntArray(3)
        GLES30.glGenTextures(3, textures, 0)
        screenTexId = textures[0]
        cameraTexId = textures[1]
        mediaTexId = textures[2]

        for (tex in textures) {
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        val cfg = configRef.get()
        screenSurfaceTexture = SurfaceTexture(screenTexId).apply {
            setDefaultBufferSize(cfg.canvasWidth, cfg.canvasHeight)
            setOnFrameAvailableListener({
                screenFrameAvailable.set(true)
                val now = SystemClock.elapsedRealtimeNanos()
                if (lastCaptureArrivalNs != 0L && now - lastCaptureArrivalNs > 100_000_000L) {
                    captureGapCount.incrementAndGet()
                }
                lastCaptureArrivalNs = now
                captureArrivalCount.incrementAndGet()
            }, glHandler)
        }
        screenInputSurface = Surface(screenSurfaceTexture)

        cameraSurfaceTexture = SurfaceTexture(cameraTexId).apply {
            setDefaultBufferSize(640, 360)
            setOnFrameAvailableListener({ cameraFrameAvailable.set(true) }, glHandler)
        }
        cameraInputSurface = Surface(cameraSurfaceTexture)

        mediaSurfaceTexture = SurfaceTexture(mediaTexId).apply {
            setDefaultBufferSize(1280, 720)
            setOnFrameAvailableListener({ mediaFrameAvailable.set(true) }, glHandler)
        }
        mediaInputSurface = Surface(mediaSurfaceTexture)
    }

    private fun renderTick(ptsNs: Long) {
        val disp = eglDisplay ?: return
        val ctx = eglContext ?: return
        val pbuf = dummyPbuffer ?: return
        if (disp == EGL14.EGL_NO_DISPLAY || ctx == EGL14.EGL_NO_CONTEXT) return
        EGL14.eglMakeCurrent(disp, pbuf, pbuf, ctx)

        renderFrameCounter++
        if (screenFrameAvailable.getAndSet(false)) {
            runCatching {
                screenSurfaceTexture?.updateTexImage()
                screenSurfaceTexture?.getTransformMatrix(screenMatrix)
            }
        }
        if (cameraFrameAvailable.getAndSet(false)) {
            runCatching {
                cameraSurfaceTexture?.updateTexImage()
                cameraSurfaceTexture?.getTransformMatrix(cameraMatrix)
            }
        }
        if (mediaFrameAvailable.getAndSet(false)) {
            runCatching {
                mediaSurfaceTexture?.updateTexImage()
                mediaSurfaceTexture?.getTransformMatrix(mediaMatrix)
            }
        }

        val cfg = configRef.get()
        val programScene = programSceneRef.get()
        val previewScene = previewSceneRef.get()
        val studioMode = studioModeRef.get()
        compositorTickCount.incrementAndGet()

        val activeTrans = activeTransitionRef.get()
        val transitionFraction = if (activeTrans != null) {
            val elapsed = (SystemClock.elapsedRealtime() - activeTrans.startedAtMs).toFloat()
            val f = (elapsed / activeTrans.durationMs.toFloat()).coerceIn(0f, 1f)
            if (f >= 1f) {
                activeTransitionRef.set(null)
            }
            f
        } else 1f

        // 1) Render PROGRAM (+ active transition) to Encoder Surface (exact output WxH, zero-copy)
        val encSurf = encoderEglSurface
        if (encSurf != null && encSurf != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(disp, encSurf, encSurf, ctx)
            renderProgramViewport(
                vpX = 0,
                vpY = 0,
                vpW = cfg.outputWidth,
                vpH = cfg.outputHeight,
                cfg = cfg,
                programScene = programScene,
                transition = activeTrans,
                transitionT = transitionFraction
            )
            EGLExt.eglPresentationTimeANDROID(disp, encSurf, ptsNs)
            EGL14.eglSwapBuffers(disp, encSurf)
        }

        // 2) Render to Preview Surface ONLY when UI is visible (Iron Rule R9)
        val prevSurf = previewEglSurface
        if (previewEnabled.get() && prevSurf != null && prevSurf != EGL14.EGL_NO_SURFACE && previewWidth > 0 && previewHeight > 0) {
            EGL14.eglMakeCurrent(disp, prevSurf, prevSurf, ctx)
            GLES30.glViewport(0, 0, previewWidth, previewHeight)
            GLES30.glClearColor(0.055f, 0.055f, 0.063f, 1.0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            if (studioMode) {
                val halfW = (previewWidth / 2) - 2
                // Left viewport: Preview scene (staged edits, NOT live)
                GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                GLES30.glScissor(0, 0, max(1, halfW), previewHeight)
                renderScenePass(0, 0, max(1, halfW), previewHeight, cfg, previewScene, globalAlpha = 1f, xOffsetNdc = 0f)
                // Right viewport: Program scene (actually live / recorded)
                val rightX = halfW + 4
                val rightW = max(1, previewWidth - rightX)
                GLES30.glScissor(rightX, 0, rightW, previewHeight)
                renderProgramViewport(rightX, 0, rightW, previewHeight, cfg, programScene, activeTrans, transitionFraction, clearBg = false)
                GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            } else {
                renderProgramViewport(0, 0, previewWidth, previewHeight, cfg, programScene, activeTrans, transitionFraction)
            }
            EGL14.eglSwapBuffers(disp, prevSurf)
        }

        if (renderFrameCounter % 120L == 0L) {
            pruneUnusedCachedTextures()
        }
    }

    private fun renderProgramViewport(
        vpX: Int,
        vpY: Int,
        vpW: Int,
        vpH: Int,
        cfg: VideoConfig,
        programScene: SceneModel?,
        transition: ActiveTransition?,
        transitionT: Float,
        clearBg: Boolean = true
    ) {
        if (clearBg) {
            GLES30.glViewport(vpX, vpY, vpW, vpH)
            GLES30.glClearColor(0.055f, 0.055f, 0.063f, 1.0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }

        if (transition == null || transitionT >= 1f) {
            renderScenePass(vpX, vpY, vpW, vpH, cfg, programScene, globalAlpha = 1f, xOffsetNdc = 0f)
            return
        }

        when (transition.type) {
            TransitionType.CUT -> {
                renderScenePass(vpX, vpY, vpW, vpH, cfg, programScene, 1f, 0f)
            }
            TransitionType.FADE -> {
                renderScenePass(vpX, vpY, vpW, vpH, cfg, transition.fromScene, 1f - transitionT, 0f)
                renderScenePass(vpX, vpY, vpW, vpH, cfg, programScene, transitionT, 0f)
            }
            TransitionType.SLIDE -> {
                renderScenePass(vpX, vpY, vpW, vpH, cfg, transition.fromScene, 1f, -2f * transitionT)
                renderScenePass(vpX, vpY, vpW, vpH, cfg, programScene, 1f, 2f * (1f - transitionT))
            }
            TransitionType.SWIPE -> {
                renderScenePass(vpX, vpY, vpW, vpH, cfg, transition.fromScene, 1f, 0f)
                val wipeW = max(1, (vpW * transitionT).toInt())
                GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                GLES30.glScissor(vpX, vpY, wipeW, vpH)
                renderScenePass(vpX, vpY, vpW, vpH, cfg, programScene, 1f, 0f)
                GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            }
            TransitionType.FADE_TO_COLOR -> {
                if (transitionT < 0.5f) {
                    val alphaOverlay = transitionT * 2f
                    renderScenePass(vpX, vpY, vpW, vpH, cfg, transition.fromScene, 1f, 0f)
                    drawSolidColorFull(transition.colorArgb, alphaOverlay)
                } else {
                    val alphaOverlay = (1f - transitionT) * 2f
                    renderScenePass(vpX, vpY, vpW, vpH, cfg, programScene, 1f, 0f)
                    drawSolidColorFull(transition.colorArgb, alphaOverlay)
                }
            }
        }
    }

    private fun renderScenePass(
        vpX: Int,
        vpY: Int,
        vpW: Int,
        vpH: Int,
        cfg: VideoConfig,
        scene: SceneModel?,
        globalAlpha: Float,
        xOffsetNdc: Float
    ) {
        GLES30.glViewport(vpX, vpY, vpW, vpH)
        if (scene == null) return

        for (src in scene.sources) {
            if (!src.visible) continue
            when (src.type) {
                SourceType.SCREEN_CAPTURE -> drawScreenSource(src, cfg, globalAlpha, xOffsetNdc)
                SourceType.CAMERA_2 -> drawOesSource(src, cameraTexId, cameraMatrix, mirrorU = src.useFrontCamera, globalAlpha = globalAlpha, xOffsetNdc = xOffsetNdc)
                SourceType.MEDIA_SOURCE -> drawOesSource(src, mediaTexId, mediaMatrix, mirrorU = false, globalAlpha = globalAlpha, xOffsetNdc = xOffsetNdc)
                SourceType.COLOR_SOURCE -> drawColorSource(src, globalAlpha, xOffsetNdc)
                SourceType.IMAGE_SOURCE -> drawImageSource(src, cfg, globalAlpha, xOffsetNdc)
                SourceType.TEXT_SOURCE -> drawTextSource(src, globalAlpha, xOffsetNdc)
                SourceType.MIC_AUDIO, SourceType.SYSTEM_AUDIO -> Unit
            }
        }
    }

    private fun drawScreenSource(src: SourceModel, cfg: VideoConfig, globalAlpha: Float, xOffsetNdc: Float) {
        val locs = oesLocs ?: return
        GLES30.glUseProgram(locs.programId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, screenTexId)
        GLES30.glUniform1i(locs.uSampler, 0)
        GLES30.glUniform1i(locs.uIsSolid, 0)
        GLES30.glUniformMatrix4fv(locs.uTexMatrix, 1, false, screenMatrix, 0)
        applyFilterUniforms(locs, src.videoFilters, globalAlpha, cfg.canvasWidth, cfg.canvasHeight)

        val t = src.transform
        val isCustomTransform = t.xFraction != 0f || t.yFraction != 0f || t.widthFraction != 1f || t.heightFraction != 1f || t.rotationDegrees != 0f
        if (isCustomTransform) {
            drawTransformedQuad(src, u0 = t.cropLeft, v0 = t.cropBottom, u1 = 1f - t.cropRight, v1 = 1f - t.cropTop, xOffsetNdc = xOffsetNdc)
            return
        }

        val srcAspect = sourceDisplayWidth.toFloat() / max(1, sourceDisplayHeight).toFloat()
        val dstAspect = cfg.canvasWidth.toFloat() / max(1, cfg.canvasHeight).toFloat()
        when (cfg.screenFitMode) {
            ScreenFitMode.STRETCH -> drawQuad(-1f + xOffsetNdc, -1f, 1f + xOffsetNdc, 1f, 0f, 0f, 1f, 1f)
            ScreenFitMode.FIT_WITH_BARS -> {
                if (srcAspect < dstAspect) {
                    val scaleX = srcAspect / dstAspect
                    drawQuad(-scaleX + xOffsetNdc, -1f, scaleX + xOffsetNdc, 1f, 0f, 0f, 1f, 1f)
                } else {
                    val scaleY = dstAspect / srcAspect
                    drawQuad(-1f + xOffsetNdc, -scaleY, 1f + xOffsetNdc, scaleY, 0f, 0f, 1f, 1f)
                }
            }
            ScreenFitMode.CROP_TO_FILL -> {
                if (srcAspect < dstAspect) {
                    val visibleHeightRatio = srcAspect / dstAspect
                    val slack = 1f - visibleHeightRatio
                    val vBottom = slack * cfg.screenCropOffsetYFraction
                    drawQuad(-1f + xOffsetNdc, -1f, 1f + xOffsetNdc, 1f, 0f, vBottom, 1f, vBottom + visibleHeightRatio)
                } else {
                    val visibleWidthRatio = dstAspect / srcAspect
                    val slack = 1f - visibleWidthRatio
                    val uLeft = slack * 0.5f
                    drawQuad(-1f + xOffsetNdc, -1f, 1f + xOffsetNdc, 1f, uLeft, 0f, uLeft + visibleWidthRatio, 1f)
                }
            }
        }
    }

    private fun drawOesSource(
        src: SourceModel,
        texId: Int,
        texMatrix: FloatArray,
        mirrorU: Boolean,
        globalAlpha: Float,
        xOffsetNdc: Float
    ) {
        val locs = oesLocs ?: return
        GLES30.glUseProgram(locs.programId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES30.glUniform1i(locs.uSampler, 0)
        GLES30.glUniform1i(locs.uIsSolid, 0)
        GLES30.glUniformMatrix4fv(locs.uTexMatrix, 1, false, texMatrix, 0)
        applyFilterUniforms(locs, src.videoFilters, globalAlpha, 640, 360)

        val t = src.transform
        val u0 = if (mirrorU) 1f - t.cropLeft else t.cropLeft
        val u1 = if (mirrorU) t.cropRight else 1f - t.cropRight
        drawTransformedQuad(src, u0 = u0, v0 = t.cropBottom, u1 = u1, v1 = 1f - t.cropTop, xOffsetNdc = xOffsetNdc)
    }

    private fun drawColorSource(src: SourceModel, globalAlpha: Float, xOffsetNdc: Float) {
        val locs = tex2dLocs ?: return
        GLES30.glUseProgram(locs.programId)
        GLES30.glUniform1i(locs.uIsSolid, 1)
        val a = ((src.colorArgb ushr 24) and 0xFF) / 255f
        val r = ((src.colorArgb ushr 16) and 0xFF) / 255f
        val g = ((src.colorArgb ushr 8) and 0xFF) / 255f
        val b = (src.colorArgb and 0xFF) / 255f
        GLES30.glUniform4f(locs.uSolidColor, r, g, b, a)
        GLES30.glUniformMatrix4fv(locs.uTexMatrix, 1, false, identityMatrix, 0)
        applyFilterUniforms(locs, src.videoFilters, globalAlpha, 1280, 720)
        drawTransformedQuad(src, 0f, 0f, 1f, 1f, xOffsetNdc)
    }

    private fun drawImageSource(src: SourceModel, cfg: VideoConfig, globalAlpha: Float, xOffsetNdc: Float) {
        val path = src.mediaPathOrUri ?: return
        val cached = getOrLoadImageTexture(path, cfg.canvasWidth, cfg.canvasHeight) ?: return
        draw2dTexture(src, cached, globalAlpha, xOffsetNdc)
    }

    private fun drawTextSource(src: SourceModel, globalAlpha: Float, xOffsetNdc: Float) {
        val key = "txt|${src.id}|${src.textContent}|${src.textColorArgb}|${src.textBgColorArgb}|${src.textSizeSp}"
        val cached = getOrRenderTextTexture(key, src) ?: return
        draw2dTexture(src, cached, globalAlpha, xOffsetNdc)
    }

    private fun draw2dTexture(src: SourceModel, tex: CachedTexture, globalAlpha: Float, xOffsetNdc: Float) {
        val locs = tex2dLocs ?: return
        GLES30.glUseProgram(locs.programId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex.texId)
        GLES30.glUniform1i(locs.uSampler, 0)
        GLES30.glUniform1i(locs.uIsSolid, 0)
        GLES30.glUniformMatrix4fv(locs.uTexMatrix, 1, false, identityMatrix, 0)
        applyFilterUniforms(locs, src.videoFilters, globalAlpha, tex.width, tex.height)

        val t = src.transform
        drawTransformedQuad(
            src = src,
            u0 = t.cropLeft,
            v0 = 1f - t.cropBottom,
            u1 = 1f - t.cropRight,
            v1 = t.cropTop,
            xOffsetNdc = xOffsetNdc
        )
    }

    private fun drawSolidColorFull(argb: Long, alpha: Float) {
        val locs = tex2dLocs ?: return
        GLES30.glUseProgram(locs.programId)
        GLES30.glUniform1i(locs.uIsSolid, 1)
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        GLES30.glUniform4f(locs.uSolidColor, r, g, b, alpha.coerceIn(0f, 1f))
        GLES30.glUniform1f(locs.uOpacity, 1f)
        GLES30.glUniform1i(locs.uColorEnabled, 0)
        GLES30.glUniform1i(locs.uChromaEnabled, 0)
        GLES30.glUniform1i(locs.uSharpenEnabled, 0)
        GLES30.glUniform1i(locs.uLutMode, 0)
        GLES30.glUniformMatrix4fv(locs.uTexMatrix, 1, false, identityMatrix, 0)
        drawQuad(-1f, -1f, 1f, 1f, 0f, 0f, 1f, 1f)
    }

    private fun applyFilterUniforms(
        locs: ShaderLocations,
        f: VideoFilterConfig,
        globalAlpha: Float,
        texW: Int,
        texH: Int
    ) {
        val baseOpacity = if (f.colorCorrectionEnabled) f.opacity else 1f
        GLES30.glUniform1f(locs.uOpacity, (baseOpacity * globalAlpha).coerceIn(0f, 1f))
        GLES30.glUniform1i(locs.uColorEnabled, if (f.colorCorrectionEnabled) 1 else 0)
        GLES30.glUniform1f(locs.uBrightness, f.brightness)
        GLES30.glUniform1f(locs.uContrast, f.contrast)
        GLES30.glUniform1f(locs.uSaturation, f.saturation)
        GLES30.glUniform1f(locs.uGamma, f.gamma)

        GLES30.glUniform1i(locs.uChromaEnabled, if (f.chromaKeyEnabled) 1 else 0)
        val ckR = ((f.chromaKeyColorArgb ushr 16) and 0xFF) / 255f
        val ckG = ((f.chromaKeyColorArgb ushr 8) and 0xFF) / 255f
        val ckB = (f.chromaKeyColorArgb and 0xFF) / 255f
        GLES30.glUniform3f(locs.uChromaColor, ckR, ckG, ckB)
        GLES30.glUniform3f(locs.uChromaParams, f.chromaSimilarity, f.chromaSmoothness, f.chromaSpill)

        GLES30.glUniform1i(locs.uSharpenEnabled, if (f.sharpenEnabled) 1 else 0)
        GLES30.glUniform1f(locs.uSharpenAmount, f.sharpenAmount)
        GLES30.glUniform2f(locs.uTexelSize, 1f / max(1, texW), 1f / max(1, texH))

        val lutIdx = if (!f.lutEnabled) 0 else when (f.lutPreset) {
            LutPreset.NONE -> 0
            LutPreset.VIVID_GAMING -> 1
            LutPreset.TEAL_ORANGE -> 2
            LutPreset.CRISP_MONO -> 3
            LutPreset.WARM_CINEMA -> 4
        }
        GLES30.glUniform1i(locs.uLutMode, lutIdx)
        GLES30.glUniform1f(locs.uLutIntensity, f.lutIntensity)
    }

    private fun drawTransformedQuad(
        src: SourceModel,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        xOffsetNdc: Float
    ) {
        val t = src.transform
        val leftNdc = -1f + 2f * t.xFraction + xOffsetNdc
        val rightNdc = -1f + 2f * min(1f, t.xFraction + t.widthFraction) + xOffsetNdc
        val topNdc = 1f - 2f * t.yFraction
        val bottomNdc = 1f - 2f * min(1f, t.yFraction + t.heightFraction)

        if (t.rotationDegrees == 0f) {
            drawQuad(leftNdc, bottomNdc, rightNdc, topNdc, u0, v0, u1, v1)
            return
        }

        val cx = (leftNdc + rightNdc) * 0.5f
        val cy = (bottomNdc + topNdc) * 0.5f
        val rad = Math.toRadians((-t.rotationDegrees).toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()

        fun rotX(x: Float, y: Float): Float = cx + (x - cx) * c - (y - cy) * s
        fun rotY(x: Float, y: Float): Float = cy + (x - cx) * s + (y - cy) * c

        val buf = quadBuffer ?: return
        buf.clear()
        buf.put(rotX(leftNdc, bottomNdc)).put(rotY(leftNdc, bottomNdc)).put(u0).put(v0)
        buf.put(rotX(rightNdc, bottomNdc)).put(rotY(rightNdc, bottomNdc)).put(u1).put(v0)
        buf.put(rotX(leftNdc, topNdc)).put(rotY(leftNdc, topNdc)).put(u0).put(v1)
        buf.put(rotX(rightNdc, topNdc)).put(rotY(rightNdc, topNdc)).put(u1).put(v1)
        buf.flip()
        submitQuadBuffer(buf)
    }

    private fun drawQuad(
        left: Float,
        bottom: Float,
        right: Float,
        top: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float
    ) {
        val buf = quadBuffer ?: return
        buf.clear()
        buf.put(left).put(bottom).put(u0).put(v0)
        buf.put(right).put(bottom).put(u1).put(v0)
        buf.put(left).put(top).put(u0).put(v1)
        buf.put(right).put(top).put(u1).put(v1)
        buf.flip()
        submitQuadBuffer(buf)
    }

    private fun submitQuadBuffer(buf: FloatBuffer) {
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        buf.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, buf)
        buf.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, buf)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun getOrLoadImageTexture(path: String, maxW: Int, maxH: Int): CachedTexture? {
        val key = "img|$path"
        textureCache[key]?.let {
            it.lastUsedFrame = renderFrameCounter
            return it
        }
        val f = File(path)
        if (!f.exists() || !f.canRead()) {
            onMediaErrorCallback?.invoke(path, "Missing image file: ${f.name}")
            return null
        }

        // Size-aware decode: never load oversized bitmaps full resolution (M2.1 Gate)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while ((bounds.outWidth / sample) > maxW * 2 || (bounds.outHeight / sample) > maxH * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        return uploadBitmapToCache(key, bmp)
    }

    private fun getOrRenderTextTexture(key: String, src: SourceModel): CachedTexture? {
        textureCache[key]?.let {
            it.lastUsedFrame = renderFrameCounter
            return it
        }
        val text = src.textContent.ifBlank { "Mobile Labs" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = src.textColorArgb.toInt()
            textSize = src.textSizeSp.coerceIn(14f, 120f) * 1.4f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val lines = text.split('\n')
        val pad = 24
        val textWidth = (lines.maxOfOrNull { paint.measureText(it) } ?: 120f).toInt().coerceIn(64, 1280)
        val lineHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent).toInt().coerceAtLeast(24)
        val totalHeight = (lineHeight * lines.size + pad * 2).coerceIn(48, 720)
        val totalWidth = (textWidth + pad * 2).coerceIn(96, 1280)

        val bmp = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(src.textBgColorArgb.toInt())
        var y = pad - paint.fontMetrics.ascent
        for (line in lines) {
            canvas.drawText(line, pad.toFloat(), y, paint)
            y += lineHeight
        }
        return uploadBitmapToCache(key, bmp)
    }

    private fun uploadBitmapToCache(key: String, bmp: Bitmap): CachedTexture {
        evictIfOverCapacity(maxEntries = 15)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val texId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()

        val entry = CachedTexture(texId = texId, key = key, width = w, height = h, lastUsedFrame = renderFrameCounter)
        textureCache[key] = entry
        return entry
    }

    private fun evictIfOverCapacity(maxEntries: Int) {
        while (textureCache.size >= maxEntries) {
            val oldestKey = textureCache.keys.firstOrNull() ?: break
            val removed = textureCache.remove(oldestKey)
            if (removed != null) {
                GLES30.glDeleteTextures(1, intArrayOf(removed.texId), 0)
            }
        }
    }

    private fun pruneUnusedCachedTextures() {
        val it = textureCache.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next().value
            if (renderFrameCounter - entry.lastUsedFrame > 180L) {
                GLES30.glDeleteTextures(1, intArrayOf(entry.texId), 0)
                it.remove()
            }
        }
    }

    private fun compileShader(type: Int, src: String): Int {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, src)
        GLES30.glCompileShader(id)
        return id
    }

    private fun destroySurface(surface: EGLSurface?) {
        val disp = eglDisplay ?: return
        if (surface != null && surface != EGL14.EGL_NO_SURFACE && disp != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(disp, dummyPbuffer, dummyPbuffer, eglContext)
            EGL14.eglDestroySurface(disp, surface)
        }
    }

    private fun releaseGlResources() {
        runCatching { screenInputSurface?.release() }
        runCatching { screenSurfaceTexture?.release() }
        runCatching { cameraInputSurface?.release() }
        runCatching { cameraSurfaceTexture?.release() }
        runCatching { mediaInputSurface?.release() }
        runCatching { mediaSurfaceTexture?.release() }
        screenInputSurface = null
        screenSurfaceTexture = null
        cameraInputSurface = null
        cameraSurfaceTexture = null
        mediaInputSurface = null
        mediaSurfaceTexture = null

        textureCache.values.forEach { entry ->
            GLES30.glDeleteTextures(1, intArrayOf(entry.texId), 0)
        }
        textureCache.clear()

        destroySurface(encoderEglSurface)
        destroySurface(previewEglSurface)
        destroySurface(dummyPbuffer)
        encoderEglSurface = null
        previewEglSurface = null
        dummyPbuffer = null

        oesLocs?.let { GLES30.glDeleteProgram(it.programId) }
        tex2dLocs?.let { GLES30.glDeleteProgram(it.programId) }
        oesLocs = null
        tex2dLocs = null

        val disp = eglDisplay
        if (disp != null && disp != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(disp, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            val ctx = eglContext
            if (ctx != null && ctx != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(disp, ctx)
            }
            EGL14.eglTerminate(disp)
        }
        eglContext = null
        eglDisplay = null
    }
}
