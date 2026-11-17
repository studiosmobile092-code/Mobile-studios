package com.example.mobilelabs.persistence

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.example.mobilelabs.model.AppProfile
import com.example.mobilelabs.model.AudioFilterConfig
import com.example.mobilelabs.model.AudioMonitoringMode
import com.example.mobilelabs.model.CameraWhiteBalanceMode
import com.example.mobilelabs.model.DownscaleFilter
import com.example.mobilelabs.model.FacecamShape
import com.example.mobilelabs.model.IncompleteRecording
import com.example.mobilelabs.model.LutPreset
import com.example.mobilelabs.model.PresetId
import com.example.mobilelabs.model.RecordingFormat
import com.example.mobilelabs.model.RtmpServicePreset
import com.example.mobilelabs.model.ScaleFilterType
import com.example.mobilelabs.model.SceneCollection
import com.example.mobilelabs.model.SceneModel
import com.example.mobilelabs.model.ScreenFitMode
import com.example.mobilelabs.model.SoundboardPad
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.SourceTransform
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.model.TransitionType
import com.example.mobilelabs.model.VideoConfig
import com.example.mobilelabs.model.VideoFilterConfig
import com.example.mobilelabs.model.VoiceEffectPreset
import com.example.mobilelabs.model.defaultSceneCollection
import com.example.mobilelabs.model.defaultSoundboardPads
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Atomic JSON persistence for scene collections, profiles, audio/video filters,
 * imported media copies, and incomplete-recording markers across force-stops (§5.5, M1.7, M2.5).
 */
class SceneCollectionRepository(private val context: Context) {

    private val stateDir: File get() = File(context.filesDir, "mobilelabs_state").apply { mkdirs() }
    private val collectionsDir: File get() = File(stateDir, "collections").apply { mkdirs() }
    private val sceneFile: File get() = File(stateDir, "scene_collection.json")
    private val sceneBackupFile: File get() = File(stateDir, "scene_collection.json.bak")
    private val settingsFile: File get() = File(stateDir, "app_settings.json")
    private val settingsBackupFile: File get() = File(stateDir, "app_settings.json.bak")
    private val activeRecMarkerFile: File get() = File(stateDir, "active_recording_marker.json")
    val recordingsDir: File get() = File(context.filesDir, "recordings").apply { mkdirs() }
    val mediaAssetsDir: File get() = File(context.filesDir, "sources_media").apply { mkdirs() }

    data class PersistedSettings(
        val videoConfig: VideoConfig = VideoConfig(),
        val floatingOverlayEnabled: Boolean = false,
        val overlayPortraitX: Int = 24,
        val overlayPortraitY: Int = 240,
        val overlayLandscapeX: Int = 24,
        val overlayLandscapeY: Int = 180,
        val customRecordingFolderUri: String? = null,
        val studioModeEnabled: Boolean = false,
        val swapPreviewProgramOnTake: Boolean = true,
        val lockPreviewTouchWhileLive: Boolean = true,
        val audioFilters: Map<String, AudioFilterConfig> = emptyMap(),
        val audioSyncOffsetsMs: Map<String, Int> = emptyMap(),
        val profiles: List<AppProfile> = listOf(AppProfile()),
        val activeProfileId: String = "prof_default",
        val savedCollections: List<String> = listOf("Tab A9+ Gaming"),
        val adaptiveQualityLocked: Boolean = false,
        val onboardingCompleted: Boolean = false,
        val dualDestinationEnabled: Boolean = false,
        val replayBufferEnabled: Boolean = false,
        val replayDurationSec: Int = 30,
        val soundboardPads: List<SoundboardPad> = defaultSoundboardPads(),
        val benchmarkRecords: List<com.example.mobilelabs.model.BenchmarkRecord> = emptyList()
    )

    fun saveSceneCollectionAtomic(collection: SceneCollection) {
        val json = serializeSceneCollection(collection)
        writeAtomic(sceneFile, sceneBackupFile, json)
        val safeFileName = sanitizeFileName(collection.name) + ".json"
        val namedFile = File(collectionsDir, safeFileName)
        val namedBak = File(collectionsDir, "$safeFileName.bak")
        writeAtomic(namedFile, namedBak, json)
    }

    fun listSavedCollectionNames(): List<String> {
        val files = collectionsDir.listFiles { f -> f.extension == "json" }?.toList().orEmpty()
        val names = files.mapNotNull { file ->
            runCatching { JSONObject(file.readText()).optString("name", "") }.getOrNull()?.takeIf { it.isNotBlank() }
        }.distinct().sorted()
        return names.ifEmpty { listOf("Tab A9+ Gaming") }
    }

    fun loadNamedCollection(name: String): SceneCollection? {
        val safe = File(collectionsDir, sanitizeFileName(name) + ".json")
        val bak = File(collectionsDir, sanitizeFileName(name) + ".json.bak")
        val raw = readWithBackup(safe, bak) ?: return null
        return parseSceneCollection(raw)
    }

    fun deleteNamedCollection(name: String) {
        val safe = File(collectionsDir, sanitizeFileName(name) + ".json")
        val bak = File(collectionsDir, sanitizeFileName(name) + ".json.bak")
        runCatching { safe.delete() }
        runCatching { bak.delete() }
    }

    fun loadSceneCollection(): SceneCollection {
        val raw = readWithBackup(sceneFile, sceneBackupFile) ?: return defaultSceneCollection()
        return parseSceneCollection(raw) ?: defaultSceneCollection()
    }

    /**
     * Copies a SAF-picked image or video file into bounded app-private storage so playback
     * survives URI permission expiry without requesting broad storage permissions (Play Policy).
     */
    fun copySafMediaAsset(uri: Uri, prefix: String): Pair<String?, String?> {
        return runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri).orEmpty()
            val ext = when {
                mime.contains("gif") -> "gif"
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                mime.contains("mp4") -> "mp4"
                mime.contains("webm") -> "webm"
                mime.contains("matroska") || mime.contains("mkv") -> "mkv"
                mime.contains("wav") -> "wav"
                mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
                mime.contains("ogg") -> "ogg"
                mime.contains("aac") || mime.contains("m4a") -> "m4a"
                else -> "dat"
            }
            val dst = File(mediaAssetsDir, "${prefix}_${System.currentTimeMillis()}.$ext")
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dst).use { out ->
                    input.copyTo(out, bufferSize = 65536)
                    out.flush()
                }
            } ?: return@runCatching Pair(null, "Unable to read selected file")
            if (dst.length() <= 0L) {
                dst.delete()
                Pair(null, "Selected file is empty")
            } else {
                Pair(dst.absolutePath, null)
            }
        }.getOrElse { err ->
            Pair(null, err.message ?: "Failed to import media")
        }
    }

    private fun serializeSceneCollection(collection: SceneCollection): String {
        val root = JSONObject().apply {
            put("version", 2)
            put("id", collection.id)
            put("name", collection.name)
            put("activeSceneId", collection.activeSceneId)
            put("transitionType", collection.transitionType.name)
            put("transitionDurationMs", collection.transitionDurationMs)
            put("transitionColorArgb", collection.transitionColorArgb)
            put("scenes", JSONArray().apply {
                collection.scenes.forEach { scene ->
                    put(JSONObject().apply {
                        put("id", scene.id)
                        put("name", scene.name)
                        put("sources", JSONArray().apply {
                            scene.sources.forEach { src ->
                                put(JSONObject().apply {
                                    put("id", src.id)
                                    put("name", src.name)
                                    put("type", src.type.name)
                                    put("visible", src.visible)
                                    put("locked", src.locked)
                                    put("useFrontCamera", src.useFrontCamera)
                                    put("facecamShape", src.facecamShape.name)
                                    put("borderWidthPx", src.borderWidthPx.toDouble())
                                    put("borderColorArgb", src.borderColorArgb)
                                    put("mirrorHorizontal", src.mirrorHorizontal)
                                    put("camExposureEv", src.camExposureEv)
                                    put("camIso", src.camIso)
                                    put("camFocusDistance", src.camFocusDistance.toDouble())
                                    put("camWhiteBalance", src.camWhiteBalance.name)
                                    put("camZoomRatio", src.camZoomRatio.toDouble())
                                    put("camTorchEnabled", src.camTorchEnabled)
                                    put("mediaPathOrUri", src.mediaPathOrUri)
                                    put("mediaLoop", src.mediaLoop)
                                    put("mediaMuted", src.mediaMuted)
                                    put("mediaPlaying", src.mediaPlaying)
                                    put("textContent", src.textContent)
                                    put("textColorArgb", src.textColorArgb)
                                    put("textBgColorArgb", src.textBgColorArgb)
                                    put("textSizeSp", src.textSizeSp.toDouble())
                                    put("colorArgb", src.colorArgb)
                                    put("browserUrl", src.browserUrl)
                                    put("browserFpsCap", src.browserFpsCap)
                                    put("browserTransparentBg", src.browserTransparentBg)
                                    put("browserWidthPx", src.browserWidthPx)
                                    put("browserHeightPx", src.browserHeightPx)
                                    put("timerTargetSec", src.timerTargetSec)
                                    put("timerCountDown", src.timerCountDown)
                                    put("scoreHomeName", src.scoreHomeName)
                                    put("scoreAwayName", src.scoreAwayName)
                                    put("scoreHome", src.scoreHome)
                                    put("scoreAway", src.scoreAway)
                                    put("goalTitle", src.goalTitle)
                                    put("goalCurrent", src.goalCurrent)
                                    put("goalTarget", src.goalTarget)
                                    put("goalAccentColorArgb", src.goalAccentColorArgb)
                                    put("alertShowChime", src.alertShowChime)
                                    put("drawingBrushColorArgb", src.drawingBrushColorArgb)
                                    put("drawingBrushSizePx", src.drawingBrushSizePx.toDouble())
                                    put("chatOverlayOpacity", src.chatOverlayOpacity.toDouble())
                                    put("chatOverlayMaxMessages", src.chatOverlayMaxMessages)
                                    put("transform", JSONObject().apply {
                                        put("x", src.transform.xFraction.toDouble())
                                        put("y", src.transform.yFraction.toDouble())
                                        put("w", src.transform.widthFraction.toDouble())
                                        put("h", src.transform.heightFraction.toDouble())
                                        put("rot", src.transform.rotationDegrees.toDouble())
                                        put("cL", src.transform.cropLeft.toDouble())
                                        put("cT", src.transform.cropTop.toDouble())
                                        put("cR", src.transform.cropRight.toDouble())
                                        put("cB", src.transform.cropBottom.toDouble())
                                    })
                                    put("videoFilters", encodeVideoFilters(src.videoFilters))
                                })
                            }
                        })
                    })
                }
            })
        }
        return root.toString(2)
    }

    private fun parseSceneCollection(raw: String): SceneCollection? {
        return runCatching {
            val root = JSONObject(raw)
            val scenesArr = root.getJSONArray("scenes")
            val scenes = buildList {
                for (i in 0 until scenesArr.length()) {
                    val sObj = scenesArr.getJSONObject(i)
                    val srcArr = sObj.getJSONArray("sources")
                    val sources = buildList {
                        for (j in 0 until srcArr.length()) {
                            val srcObj = srcArr.getJSONObject(j)
                            val tObj = srcObj.optJSONObject("transform")
                            val srcType = runCatching {
                                SourceType.valueOf(srcObj.getString("type"))
                            }.getOrDefault(SourceType.COLOR_SOURCE)
                            val mediaPath = srcObj.optString("mediaPathOrUri", "").ifBlank { null }
                            val missingErr = if (
                                (srcType == SourceType.IMAGE_SOURCE || srcType == SourceType.MEDIA_SOURCE || srcType == SourceType.GIF_STICKER) &&
                                mediaPath != null &&
                                !mediaPath.startsWith("content://") &&
                                !File(mediaPath).exists()
                            ) {
                                "Missing file: ${mediaPath.substringAfterLast('/')}"
                            } else null

                            add(
                                SourceModel(
                                    id = srcObj.getString("id"),
                                    name = srcObj.getString("name"),
                                    type = srcType,
                                    visible = srcObj.optBoolean("visible", true),
                                    locked = srcObj.optBoolean("locked", false),
                                    erroredReason = missingErr,
                                    useFrontCamera = srcObj.optBoolean("useFrontCamera", true),
                                    facecamShape = runCatching {
                                        FacecamShape.valueOf(srcObj.optString("facecamShape", FacecamShape.RECTANGLE.name))
                                    }.getOrDefault(FacecamShape.RECTANGLE),
                                    borderWidthPx = srcObj.optDouble("borderWidthPx", 0.0).toFloat(),
                                    borderColorArgb = srcObj.optLong("borderColorArgb", 0xFFCC1B1BL),
                                    mirrorHorizontal = srcObj.optBoolean("mirrorHorizontal", false),
                                    camExposureEv = srcObj.optInt("camExposureEv", 0),
                                    camIso = srcObj.optInt("camIso", 0),
                                    camFocusDistance = srcObj.optDouble("camFocusDistance", -1.0).toFloat(),
                                    camWhiteBalance = runCatching {
                                        CameraWhiteBalanceMode.valueOf(
                                            srcObj.optString("camWhiteBalance", CameraWhiteBalanceMode.AUTO.name)
                                        )
                                    }.getOrDefault(CameraWhiteBalanceMode.AUTO),
                                    camZoomRatio = srcObj.optDouble("camZoomRatio", 1.0).toFloat().coerceIn(1f, 4f),
                                    camTorchEnabled = srcObj.optBoolean("camTorchEnabled", false),
                                    mediaPathOrUri = mediaPath,
                                    mediaLoop = srcObj.optBoolean("mediaLoop", true),
                                    mediaMuted = srcObj.optBoolean("mediaMuted", false),
                                    mediaPlaying = srcObj.optBoolean("mediaPlaying", true),
                                    textContent = srcObj.optString("textContent", "OBSmobile Stream"),
                                    textColorArgb = srcObj.optLong("textColorArgb", 0xFFFFFFFFL),
                                    textBgColorArgb = srcObj.optLong("textBgColorArgb", 0x9916161AL),
                                    textSizeSp = srcObj.optDouble("textSizeSp", 44.0).toFloat(),
                                    colorArgb = srcObj.optLong("colorArgb", 0xFF720000L),
                                    browserUrl = srcObj.optString("browserUrl", "https://streamlabs.com/widgets/alert-box/v3/demo"),
                                    browserFpsCap = srcObj.optInt("browserFpsCap", 20).coerceIn(5, 30),
                                    browserTransparentBg = srcObj.optBoolean("browserTransparentBg", true),
                                    browserWidthPx = srcObj.optInt("browserWidthPx", 800).coerceIn(160, 1920),
                                    browserHeightPx = srcObj.optInt("browserHeightPx", 600).coerceIn(120, 1080),
                                    timerTargetSec = srcObj.optInt("timerTargetSec", 300).coerceIn(5, 86400),
                                    timerCountDown = srcObj.optBoolean("timerCountDown", true),
                                    scoreHomeName = srcObj.optString("scoreHomeName", "P1"),
                                    scoreAwayName = srcObj.optString("scoreAwayName", "P2"),
                                    scoreHome = srcObj.optInt("scoreHome", 0),
                                    scoreAway = srcObj.optInt("scoreAway", 0),
                                    goalTitle = srcObj.optString("goalTitle", "FOLLOWER GOAL"),
                                    goalCurrent = srcObj.optInt("goalCurrent", 42),
                                    goalTarget = srcObj.optInt("goalTarget", 100).coerceAtLeast(1),
                                    goalAccentColorArgb = srcObj.optLong("goalAccentColorArgb", 0xFF9146FFL),
                                    alertShowChime = srcObj.optBoolean("alertShowChime", true),
                                    drawingBrushColorArgb = srcObj.optLong("drawingBrushColorArgb", 0xFFFF3B30L),
                                    drawingBrushSizePx = srcObj.optDouble("drawingBrushSizePx", 10.0).toFloat(),
                                    chatOverlayOpacity = srcObj.optDouble("chatOverlayOpacity", 0.72).toFloat(),
                                    chatOverlayMaxMessages = srcObj.optInt("chatOverlayMaxMessages", 7).coerceIn(2, 15),
                                    transform = if (tObj != null) {
                                        SourceTransform(
                                            xFraction = tObj.optDouble("x", 0.0).toFloat(),
                                            yFraction = tObj.optDouble("y", 0.0).toFloat(),
                                            widthFraction = tObj.optDouble("w", 1.0).toFloat(),
                                            heightFraction = tObj.optDouble("h", 1.0).toFloat(),
                                            rotationDegrees = tObj.optDouble("rot", 0.0).toFloat(),
                                            cropLeft = tObj.optDouble("cL", 0.0).toFloat(),
                                            cropTop = tObj.optDouble("cT", 0.0).toFloat(),
                                            cropRight = tObj.optDouble("cR", 0.0).toFloat(),
                                            cropBottom = tObj.optDouble("cB", 0.0).toFloat()
                                        )
                                    } else {
                                        SourceTransform()
                                    },
                                    videoFilters = decodeVideoFilters(srcObj.optJSONObject("videoFilters"))
                                )
                            )
                        }
                    }
                    add(
                        SceneModel(
                            id = sObj.getString("id"),
                            name = sObj.getString("name"),
                            sources = sources
                        )
                    )
                }
            }
            if (scenes.isEmpty()) return null
            SceneCollection(
                id = root.optString("id", "col_default"),
                name = root.optString("name", "Tab A9+ Gaming"),
                activeSceneId = root.optString("activeSceneId", scenes.first().id),
                scenes = scenes,
                transitionType = runCatching {
                    TransitionType.valueOf(root.optString("transitionType", "FADE"))
                }.getOrDefault(TransitionType.FADE),
                transitionDurationMs = root.optInt("transitionDurationMs", 300),
                transitionColorArgb = root.optLong("transitionColorArgb", 0xFF000000L)
            )
        }.getOrNull()
    }

    fun saveSettingsAtomic(settings: PersistedSettings) {
        val obj = JSONObject().apply {
            put("videoConfig", encodeVideoConfig(settings.videoConfig))
            put("floatingOverlayEnabled", settings.floatingOverlayEnabled)
            put("overlayPortraitX", settings.overlayPortraitX)
            put("overlayPortraitY", settings.overlayPortraitY)
            put("overlayLandscapeX", settings.overlayLandscapeX)
            put("overlayLandscapeY", settings.overlayLandscapeY)
            put("customRecordingFolderUri", settings.customRecordingFolderUri)
            put("studioModeEnabled", settings.studioModeEnabled)
            put("swapPreviewProgramOnTake", settings.swapPreviewProgramOnTake)
            put("lockPreviewTouchWhileLive", settings.lockPreviewTouchWhileLive)
            put("activeProfileId", settings.activeProfileId)
            put("adaptiveQualityLocked", settings.adaptiveQualityLocked)
            put("onboardingCompleted", settings.onboardingCompleted)
            put("dualDestinationEnabled", settings.dualDestinationEnabled)
            put("replayBufferEnabled", settings.replayBufferEnabled)
            put("replayDurationSec", settings.replayDurationSec)
            put("soundboardPads", JSONArray().apply {
                settings.soundboardPads.forEach { pad ->
                    put(JSONObject().apply {
                        put("id", pad.id)
                        put("label", pad.label)
                        put("filePath", pad.filePath)
                        put("builtInTone", pad.builtInTone)
                        put("volume", pad.volume.toDouble())
                    })
                }
            })
            put("benchmarkRecords", JSONArray().apply {
                settings.benchmarkRecords.forEach { b ->
                    put(JSONObject().apply {
                        put("id", b.id)
                        put("appName", b.appName)
                        put("presetLabel", b.presetLabel)
                        put("gameName", b.gameName)
                        put("gameAvgFps", b.gameAvgFps.toDouble())
                        put("gameOnePercentLowFps", b.gameOnePercentLowFps.toDouble())
                        put("streamDroppedPct", b.streamDroppedPct.toDouble())
                        put("peakThermalLabel", b.peakThermalLabel)
                        put("processPssMb", b.processPssMb)
                        put("notes", b.notes)
                        put("recordedAtIso", b.recordedAtIso)
                    })
                }
            })
            put("audioFilters", JSONObject().apply {
                settings.audioFilters.forEach { (chId, f) ->
                    put(chId, encodeAudioFilter(f))
                }
            })
            put("audioSyncOffsetsMs", JSONObject().apply {
                settings.audioSyncOffsetsMs.forEach { (chId, ms) ->
                    put(chId, ms)
                }
            })
            put("profiles", JSONArray().apply {
                settings.profiles.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("videoConfig", encodeVideoConfig(p.videoConfig))
                        put("rtmpServicePreset", p.rtmpServicePreset.name)
                        put("confirmStopActions", p.confirmStopActions)
                        put("lockPreviewTouchWhileLive", p.lockPreviewTouchWhileLive)
                        put("swapPreviewProgramOnTake", p.swapPreviewProgramOnTake)
                    })
                }
            })
        }
        writeAtomic(settingsFile, settingsBackupFile, obj.toString(2))
    }

    fun loadSettings(): PersistedSettings {
        val raw = readWithBackup(settingsFile, settingsBackupFile) ?: return PersistedSettings(
            savedCollections = listSavedCollectionNames()
        )
        return runCatching {
            val obj = JSONObject(raw)
            val vObj = obj.optJSONObject("videoConfig") ?: obj
            val videoConfig = decodeVideoConfig(vObj)
            val audioFiltersMap = mutableMapOf<String, AudioFilterConfig>()
            obj.optJSONObject("audioFilters")?.let { afObj ->
                afObj.keys().forEach { k ->
                    afObj.optJSONObject(k)?.let { fJson ->
                        audioFiltersMap[k] = decodeAudioFilter(fJson)
                    }
                }
            }
            val profilesList = buildList {
                val pArr = obj.optJSONArray("profiles")
                if (pArr != null) {
                    for (i in 0 until pArr.length()) {
                        val p = pArr.optJSONObject(i) ?: continue
                        add(
                            AppProfile(
                                id = p.optString("id", "prof_$i"),
                                name = p.optString("name", "Profile ${i + 1}"),
                                videoConfig = decodeVideoConfig(p.optJSONObject("videoConfig") ?: vObj),
                                rtmpServicePreset = runCatching {
                                    RtmpServicePreset.valueOf(p.optString("rtmpServicePreset", "CUSTOM"))
                                }.getOrDefault(RtmpServicePreset.CUSTOM),
                                confirmStopActions = p.optBoolean("confirmStopActions", true),
                                lockPreviewTouchWhileLive = p.optBoolean("lockPreviewTouchWhileLive", true),
                                swapPreviewProgramOnTake = p.optBoolean("swapPreviewProgramOnTake", true)
                            )
                        )
                    }
                }
            }.ifEmpty { listOf(AppProfile(videoConfig = videoConfig)) }

            val benchmarks = buildList {
                val bArr = obj.optJSONArray("benchmarkRecords")
                if (bArr != null) {
                    for (i in 0 until bArr.length()) {
                        val b = bArr.optJSONObject(i) ?: continue
                        add(
                            com.example.mobilelabs.model.BenchmarkRecord(
                                id = b.optString("id", "bench_$i"),
                                appName = b.optString("appName", "OBSmobile"),
                                presetLabel = b.optString("presetLabel", "Balanced 720p60"),
                                gameName = b.optString("gameName", "Geometry Dash"),
                                gameAvgFps = b.optDouble("gameAvgFps", 0.0).toFloat(),
                                gameOnePercentLowFps = b.optDouble("gameOnePercentLowFps", 0.0).toFloat(),
                                streamDroppedPct = b.optDouble("streamDroppedPct", 0.0).toFloat(),
                                peakThermalLabel = b.optString("peakThermalLabel", "TEMP OK"),
                                processPssMb = b.optInt("processPssMb", 0),
                                notes = b.optString("notes", ""),
                                recordedAtIso = b.optString("recordedAtIso", "")
                            )
                        )
                    }
                }
            }

            val audioSyncMap = mutableMapOf<String, Int>()
            obj.optJSONObject("audioSyncOffsetsMs")?.let { asObj ->
                asObj.keys().forEach { k ->
                    audioSyncMap[k] = asObj.optInt(k, 0).coerceIn(-500, 500)
                }
            }

            val pads = buildList {
                val padsArr = obj.optJSONArray("soundboardPads")
                if (padsArr != null) {
                    for (i in 0 until padsArr.length()) {
                        val p = padsArr.optJSONObject(i) ?: continue
                        add(
                            SoundboardPad(
                                id = p.optString("id", "sfx_$i"),
                                label = p.optString("label", "SFX ${i + 1}"),
                                filePath = p.optString("filePath", "").ifBlank { null },
                                builtInTone = p.optString("builtInTone", "").ifBlank { null },
                                volume = p.optDouble("volume", 0.85).toFloat().coerceIn(0f, 1f)
                            )
                        )
                    }
                }
            }.ifEmpty { defaultSoundboardPads() }

            PersistedSettings(
                videoConfig = videoConfig,
                floatingOverlayEnabled = obj.optBoolean("floatingOverlayEnabled", false),
                overlayPortraitX = obj.optInt("overlayPortraitX", 24),
                overlayPortraitY = obj.optInt("overlayPortraitY", 240),
                overlayLandscapeX = obj.optInt("overlayLandscapeX", 24),
                overlayLandscapeY = obj.optInt("overlayLandscapeY", 180),
                customRecordingFolderUri = obj.optString("customRecordingFolderUri", "").ifBlank { null },
                studioModeEnabled = obj.optBoolean("studioModeEnabled", false),
                swapPreviewProgramOnTake = obj.optBoolean("swapPreviewProgramOnTake", true),
                lockPreviewTouchWhileLive = obj.optBoolean("lockPreviewTouchWhileLive", true),
                audioFilters = audioFiltersMap,
                audioSyncOffsetsMs = audioSyncMap,
                profiles = profilesList,
                activeProfileId = obj.optString("activeProfileId", profilesList.first().id),
                savedCollections = listSavedCollectionNames(),
                adaptiveQualityLocked = obj.optBoolean("adaptiveQualityLocked", false),
                onboardingCompleted = obj.optBoolean("onboardingCompleted", false),
                dualDestinationEnabled = obj.optBoolean("dualDestinationEnabled", false),
                replayBufferEnabled = obj.optBoolean("replayBufferEnabled", false),
                replayDurationSec = obj.optInt("replayDurationSec", 30).coerceIn(10, 120),
                soundboardPads = pads,
                benchmarkRecords = benchmarks
            )
        }.getOrElse { PersistedSettings(savedCollections = listSavedCollectionNames()) }
    }

    private fun encodeVideoConfig(vc: VideoConfig): JSONObject = JSONObject().apply {
        put("presetId", vc.presetId.name)
        put("canvasWidth", vc.canvasWidth)
        put("canvasHeight", vc.canvasHeight)
        put("outputWidth", vc.outputWidth)
        put("outputHeight", vc.outputHeight)
        put("fps", vc.fps)
        put("videoBitrateKbps", vc.videoBitrateKbps)
        put("audioBitrateKbps", vc.audioBitrateKbps)
        put("keyframeIntervalSec", vc.keyframeIntervalSec)
        put("downscaleFilter", vc.downscaleFilter.name)
        put("screenFitMode", vc.screenFitMode.name)
        put("screenCropOffsetY", vc.screenCropOffsetYFraction.toDouble())
        put("recordingFormat", vc.recordingFormat.name)
        put("dynamicBitrateEnabled", vc.dynamicBitrateEnabled)
        put("lowLatencyNetworkMode", vc.lowLatencyNetworkMode)
        put("multiTrackAudioEnabled", vc.multiTrackAudioEnabled)
        put("skipGoLivePreFlight", vc.skipGoLivePreFlight)
    }

    private fun decodeVideoConfig(obj: JSONObject): VideoConfig {
        val preset = runCatching {
            PresetId.valueOf(obj.optString("presetId", PresetId.BALANCED.name))
        }.getOrDefault(PresetId.BALANCED)
        val fit = runCatching {
            ScreenFitMode.valueOf(obj.optString("screenFitMode", ScreenFitMode.CROP_TO_FILL.name))
        }.getOrDefault(ScreenFitMode.CROP_TO_FILL)
        val ds = runCatching {
            DownscaleFilter.valueOf(obj.optString("downscaleFilter", DownscaleFilter.BILINEAR.name))
        }.getOrDefault(DownscaleFilter.BILINEAR)
        val recFmt = runCatching {
            RecordingFormat.valueOf(obj.optString("recordingFormat", RecordingFormat.FMP4.name))
        }.getOrDefault(RecordingFormat.FMP4)
        return VideoConfig(
            presetId = preset,
            canvasWidth = obj.optInt("canvasWidth", preset.canvasWidth),
            canvasHeight = obj.optInt("canvasHeight", preset.canvasHeight),
            outputWidth = obj.optInt("outputWidth", preset.outputWidth),
            outputHeight = obj.optInt("outputHeight", preset.outputHeight),
            fps = obj.optInt("fps", preset.fps),
            videoBitrateKbps = obj.optInt("videoBitrateKbps", preset.videoBitrateKbps),
            audioBitrateKbps = obj.optInt("audioBitrateKbps", preset.audioBitrateKbps),
            keyframeIntervalSec = obj.optInt("keyframeIntervalSec", 2),
            downscaleFilter = ds,
            screenFitMode = fit,
            screenCropOffsetYFraction = obj.optDouble("screenCropOffsetY", 0.5).toFloat(),
            recordingFormat = recFmt,
            dynamicBitrateEnabled = obj.optBoolean("dynamicBitrateEnabled", true),
            lowLatencyNetworkMode = obj.optBoolean("lowLatencyNetworkMode", true),
            multiTrackAudioEnabled = obj.optBoolean("multiTrackAudioEnabled", false),
            skipGoLivePreFlight = obj.optBoolean("skipGoLivePreFlight", false)
        )
    }

    private fun encodeAudioFilter(f: AudioFilterConfig): JSONObject = JSONObject().apply {
        put("gainEnabled", f.gainEnabled)
        put("gainDb", f.gainDb.toDouble())
        put("noiseSuppressionEnabled", f.noiseSuppressionEnabled)
        put("noiseGateEnabled", f.noiseGateEnabled)
        put("gateCloseDb", f.gateCloseDb.toDouble())
        put("gateOpenDb", f.gateOpenDb.toDouble())
        put("compressorEnabled", f.compressorEnabled)
        put("compressorThresholdDb", f.compressorThresholdDb.toDouble())
        put("compressorRatio", f.compressorRatio.toDouble())
        put("compressorMakeupDb", f.compressorMakeupDb.toDouble())
        put("limiterEnabled", f.limiterEnabled)
        put("limiterCeilingDb", f.limiterCeilingDb.toDouble())
        put("autoDuckingEnabled", f.autoDuckingEnabled)
        put("duckAmountDb", f.duckAmountDb.toDouble())
        put("pushToTalkEnabled", f.pushToTalkEnabled)
        put("eqEnabled", f.eqEnabled)
        put("eqLowDb", f.eqLowDb.toDouble())
        put("eqMidDb", f.eqMidDb.toDouble())
        put("eqHighDb", f.eqHighDb.toDouble())
        put("voiceEffect", f.voiceEffect.name)
        put("reverbMix", f.reverbMix.toDouble())
        put("monitoringMode", f.monitoringMode.name)
    }

    private fun decodeAudioFilter(obj: JSONObject): AudioFilterConfig = AudioFilterConfig(
        gainEnabled = obj.optBoolean("gainEnabled", false),
        gainDb = obj.optDouble("gainDb", 0.0).toFloat(),
        noiseSuppressionEnabled = obj.optBoolean("noiseSuppressionEnabled", false),
        noiseGateEnabled = obj.optBoolean("noiseGateEnabled", false),
        gateCloseDb = obj.optDouble("gateCloseDb", -45.0).toFloat(),
        gateOpenDb = obj.optDouble("gateOpenDb", -38.0).toFloat(),
        compressorEnabled = obj.optBoolean("compressorEnabled", false),
        compressorThresholdDb = obj.optDouble("compressorThresholdDb", -18.0).toFloat(),
        compressorRatio = obj.optDouble("compressorRatio", 4.0).toFloat(),
        compressorMakeupDb = obj.optDouble("compressorMakeupDb", 3.0).toFloat(),
        limiterEnabled = obj.optBoolean("limiterEnabled", false),
        limiterCeilingDb = obj.optDouble("limiterCeilingDb", -1.5).toFloat(),
        autoDuckingEnabled = obj.optBoolean("autoDuckingEnabled", false),
        duckAmountDb = obj.optDouble("duckAmountDb", -10.0).toFloat(),
        pushToTalkEnabled = obj.optBoolean("pushToTalkEnabled", false),
        eqEnabled = obj.optBoolean("eqEnabled", false),
        eqLowDb = obj.optDouble("eqLowDb", 0.0).toFloat().coerceIn(-12f, 12f),
        eqMidDb = obj.optDouble("eqMidDb", 0.0).toFloat().coerceIn(-12f, 12f),
        eqHighDb = obj.optDouble("eqHighDb", 0.0).toFloat().coerceIn(-12f, 12f),
        voiceEffect = runCatching {
            VoiceEffectPreset.valueOf(obj.optString("voiceEffect", VoiceEffectPreset.NONE.name))
        }.getOrDefault(VoiceEffectPreset.NONE),
        reverbMix = obj.optDouble("reverbMix", 0.22).toFloat().coerceIn(0f, 0.7f),
        monitoringMode = runCatching {
            AudioMonitoringMode.valueOf(obj.optString("monitoringMode", AudioMonitoringMode.OFF.name))
        }.getOrDefault(AudioMonitoringMode.OFF)
    )

    private fun encodeVideoFilters(f: VideoFilterConfig): JSONObject = JSONObject().apply {
        put("colorCorrectionEnabled", f.colorCorrectionEnabled)
        put("brightness", f.brightness.toDouble())
        put("contrast", f.contrast.toDouble())
        put("saturation", f.saturation.toDouble())
        put("gamma", f.gamma.toDouble())
        put("opacity", f.opacity.toDouble())
        put("cropPadEnabled", f.cropPadEnabled)
        put("scaleAspectEnabled", f.scaleAspectEnabled)
        put("scaleFilter", f.scaleFilter.name)
        put("chromaKeyEnabled", f.chromaKeyEnabled)
        put("chromaKeyColorArgb", f.chromaKeyColorArgb)
        put("chromaSimilarity", f.chromaSimilarity.toDouble())
        put("chromaSmoothness", f.chromaSmoothness.toDouble())
        put("chromaSpill", f.chromaSpill.toDouble())
        put("sharpenEnabled", f.sharpenEnabled)
        put("sharpenAmount", f.sharpenAmount.toDouble())
        put("beautyEnabled", f.beautyEnabled)
        put("beautyStrength", f.beautyStrength.toDouble())
        put("lutEnabled", f.lutEnabled)
        put("lutPreset", f.lutPreset.name)
        put("lutIntensity", f.lutIntensity.toDouble())
    }

    private fun decodeVideoFilters(obj: JSONObject?): VideoFilterConfig {
        if (obj == null) return VideoFilterConfig()
        return VideoFilterConfig(
            colorCorrectionEnabled = obj.optBoolean("colorCorrectionEnabled", false),
            brightness = obj.optDouble("brightness", 0.0).toFloat(),
            contrast = obj.optDouble("contrast", 1.0).toFloat(),
            saturation = obj.optDouble("saturation", 1.0).toFloat(),
            gamma = obj.optDouble("gamma", 1.0).toFloat(),
            opacity = obj.optDouble("opacity", 1.0).toFloat(),
            cropPadEnabled = obj.optBoolean("cropPadEnabled", false),
            scaleAspectEnabled = obj.optBoolean("scaleAspectEnabled", false),
            scaleFilter = runCatching {
                ScaleFilterType.valueOf(obj.optString("scaleFilter", "BILINEAR"))
            }.getOrDefault(ScaleFilterType.BILINEAR),
            chromaKeyEnabled = obj.optBoolean("chromaKeyEnabled", false),
            chromaKeyColorArgb = obj.optLong("chromaKeyColorArgb", 0xFF00B140L),
            chromaSimilarity = obj.optDouble("chromaSimilarity", 0.28).toFloat(),
            chromaSmoothness = obj.optDouble("chromaSmoothness", 0.08).toFloat(),
            chromaSpill = obj.optDouble("chromaSpill", 0.15).toFloat(),
            sharpenEnabled = obj.optBoolean("sharpenEnabled", false),
            sharpenAmount = obj.optDouble("sharpenAmount", 0.35).toFloat(),
            beautyEnabled = obj.optBoolean("beautyEnabled", false),
            beautyStrength = obj.optDouble("beautyStrength", 0.45).toFloat().coerceIn(0f, 1f),
            lutEnabled = obj.optBoolean("lutEnabled", false),
            lutPreset = runCatching {
                LutPreset.valueOf(obj.optString("lutPreset", "NONE"))
            }.getOrDefault(LutPreset.NONE),
            lutIntensity = obj.optDouble("lutIntensity", 0.7).toFloat()
        )
    }

    fun markActiveRecording(file: File, startedAtMs: Long) {
        val obj = JSONObject().apply {
            put("fileName", file.name)
            put("filePath", file.absolutePath)
            put("startedAtMs", startedAtMs)
        }
        writeAtomic(activeRecMarkerFile, null, obj.toString())
    }

    fun clearActiveRecordingMarker() {
        if (activeRecMarkerFile.exists()) {
            activeRecMarkerFile.delete()
        }
    }

    fun detectIncompleteRecordings(): List<IncompleteRecording> {
        val list = mutableListOf<IncompleteRecording>()
        if (activeRecMarkerFile.exists()) {
            runCatching {
                val obj = JSONObject(activeRecMarkerFile.readText())
                val path = obj.getString("filePath")
                val f = File(path)
                if (f.exists() && f.length() > 0L) {
                    val playable = verifyMediaPlayable(f)
                    list += IncompleteRecording(
                        fileName = f.name,
                        filePath = f.absolutePath,
                        startedAtMs = obj.optLong("startedAtMs", f.lastModified()),
                        bytesOnDisk = f.length(),
                        verifiedPlayable = playable,
                        statusNote = if (playable) {
                            "INCOMPLETE (playable tracks verified)"
                        } else {
                            "INCOMPLETE (needs recovery/remux)"
                        }
                    )
                }
            }
        }
        recordingsDir.listFiles { file -> file.extension.equals("mp4", true) && file.length() > 0L }
            ?.forEach { file ->
                if (list.none { it.filePath == file.absolutePath }) {
                    val playable = verifyMediaPlayable(file)
                    list += IncompleteRecording(
                        fileName = file.name,
                        filePath = file.absolutePath,
                        startedAtMs = file.lastModified(),
                        bytesOnDisk = file.length(),
                        verifiedPlayable = playable,
                        statusNote = if (playable) {
                            "INCOMPLETE (unpublished MP4, playable)"
                        } else {
                            "INCOMPLETE (unfinalized)"
                        }
                    )
                }
            }
        return list.sortedByDescending { it.startedAtMs }
    }

    fun verifyMediaPlayable(file: File): Boolean {
        if (!file.exists() || file.length() < 1024L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var hasVideo = false
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) hasVideo = true
            }
            hasVideo
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64).ifBlank { "default" }

    private fun writeAtomic(target: File, backup: File?, content: String) {
        if (backup != null && target.exists()) {
            runCatching { target.copyTo(backup, overwrite = true) }
        }
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            runCatching { fos.fd.sync() }
        }
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun readWithBackup(primary: File, backup: File): String? {
        if (primary.exists()) {
            val txt = runCatching { primary.readText(Charsets.UTF_8) }.getOrNull()
            if (!txt.isNullOrBlank()) return txt
        }
        if (backup.exists()) {
            return runCatching { backup.readText(Charsets.UTF_8) }.getOrNull()
        }
        return null
    }
}
