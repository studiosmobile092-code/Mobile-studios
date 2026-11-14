package com.example.mobilelabs.persistence

import android.content.Context
import androidx.core.net.toUri
import com.example.mobilelabs.model.LutPreset
import com.example.mobilelabs.model.ScaleFilterType
import com.example.mobilelabs.model.SceneCollection
import com.example.mobilelabs.model.SceneModel
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.SourceTransform
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.model.TransitionType
import com.example.mobilelabs.model.VideoFilterConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * OBS-compatible Scene Collection JSON exporter & untrusted-input importer (§12.2 M2.5, §15.3).
 * Stream keys and credentials are strictly excluded from all exports.
 */
object ObsJsonImporterExporter {

    private const val MAX_SCENES = 32
    private const val MAX_SOURCES_PER_SCENE = 48
    private const val MAX_TEXT_CHARS = 512

    data class ImportResult(
        val collection: SceneCollection,
        val missingAssets: List<String>,
        val warnings: List<String>
    )

    fun exportToObsJson(
        collection: SceneCollection,
        canvasWidth: Int = 1280,
        canvasHeight: Int = 720
    ): String {
        val cw = canvasWidth.coerceAtLeast(320)
        val ch = canvasHeight.coerceAtLeast(240)

        val sceneOrder = JSONArray()
        val obsSources = JSONArray()
        val seenIds = mutableSetOf<String>()

        collection.scenes.forEach { scene ->
            sceneOrder.put(JSONObject().put("name", scene.name))

            val itemsArr = JSONArray()
            scene.sources.forEach { src ->
                if (seenIds.add(src.id)) {
                    obsSources.put(encodeLeafObsSource(src))
                }
                itemsArr.put(JSONObject().apply {
                    put("name", src.name)
                    put("source_uuid", src.id)
                    put("visible", src.visible)
                    put("locked", src.locked)
                    put("rot", src.transform.rotationDegrees.toDouble())
                    put("pos", JSONObject().apply {
                        put("x", (src.transform.xFraction * cw).toDouble())
                        put("y", (src.transform.yFraction * ch).toDouble())
                    })
                    put("scale", JSONObject().apply {
                        put("x", src.transform.widthFraction.toDouble())
                        put("y", src.transform.heightFraction.toDouble())
                    })
                    put("crop_left", (src.transform.cropLeft * cw).toInt())
                    put("crop_top", (src.transform.cropTop * ch).toInt())
                    put("crop_right", (src.transform.cropRight * cw).toInt())
                    put("crop_bottom", (src.transform.cropBottom * ch).toInt())
                })
            }

            obsSources.put(JSONObject().apply {
                put("name", scene.name)
                put("uuid", scene.id)
                put("id", "scene")
                put("settings", JSONObject().put("items", itemsArr))
            })
        }

        val activeSceneName = collection.scenes.firstOrNull {
            it.id == collection.activeSceneId
        }?.name ?: collection.scenes.firstOrNull()?.name.orEmpty()

        val transitions = JSONArray().apply {
            put(JSONObject().apply {
                put("name", collection.transitionType.label)
                put("id", toObsTransitionId(collection.transitionType))
                put("duration", collection.transitionDurationMs)
                put("settings", JSONObject().apply {
                    put("color", collection.transitionColorArgb)
                })
            })
        }

        return JSONObject().apply {
            put("name", collection.name)
            put("current_scene", activeSceneName)
            put("current_program_scene", activeSceneName)
            put("current_transition", collection.transitionType.label)
            put("transition_duration", collection.transitionDurationMs)
            put("scene_order", sceneOrder)
            put("sources", obsSources)
            put("transitions", transitions)
            put("mobilelabs_canvas", JSONObject().apply {
                put("width", cw)
                put("height", ch)
                put("collectionId", collection.id)
                put("transitionType", collection.transitionType.name)
                put("transitionColorArgb", collection.transitionColorArgb)
            })
        }.toString(2)
    }

    fun importFromObsJson(
        context: Context,
        rawJson: String,
        fallbackCanvasW: Int = 1280,
        fallbackCanvasH: Int = 720
    ): ImportResult {
        require(rawJson.length <= 2_000_000) { "Scene JSON exceeds 2 MB safety limit" }
        val root = JSONObject(rawJson)
        val colName = root.optString("name", "Imported Collection").take(80).ifBlank { "Imported Collection" }

        val canvasMeta = root.optJSONObject("mobilelabs_canvas")
        val cw = (canvasMeta?.optInt("width", fallbackCanvasW) ?: fallbackCanvasW).coerceIn(320, 3840)
        val ch = (canvasMeta?.optInt("height", fallbackCanvasH) ?: fallbackCanvasH).coerceIn(240, 2160)

        val missingAssets = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val sourcesArr = root.optJSONArray("sources") ?: JSONArray()
        val leafByName = mutableMapOf<String, JSONObject>()
        val leafByUuid = mutableMapOf<String, JSONObject>()
        val sceneObjs = mutableMapOf<String, JSONObject>()

        for (i in 0 until sourcesArr.length()) {
            val obj = sourcesArr.optJSONObject(i) ?: continue
            val obsId = obj.optString("id", "")
            val name = obj.optString("name", "")
            val uuid = obj.optString("uuid", "")
            if (obsId == "scene") {
                sceneObjs[name] = obj
            } else {
                if (name.isNotBlank()) leafByName[name] = obj
                if (uuid.isNotBlank()) leafByUuid[uuid] = obj
            }
        }

        val orderedNames = buildList {
            val orderArr = root.optJSONArray("scene_order")
            if (orderArr != null) {
                for (i in 0 until minOf(orderArr.length(), MAX_SCENES)) {
                    val nm = orderArr.optJSONObject(i)?.optString("name").orEmpty()
                    if (nm.isNotBlank() && sceneObjs.containsKey(nm)) add(nm)
                }
            }
            sceneObjs.keys.forEach { if (!contains(it) && size < MAX_SCENES) add(it) }
        }

        if (sceneObjs.size > MAX_SCENES) {
            warnings += "Scene count clamped to $MAX_SCENES"
        }

        val scenes = orderedNames.mapIndexed { idx, sceneName ->
            val sceneObj = sceneObjs.getValue(sceneName)
            val sceneId = sceneObj.optString("uuid", "").ifBlank { "scene_imp_$idx" }
            val items = sceneObj.optJSONObject("settings")?.optJSONArray("items") ?: JSONArray()

            val parsedSources = buildList {
                for (j in 0 until minOf(items.length(), MAX_SOURCES_PER_SCENE)) {
                    val item = items.optJSONObject(j) ?: continue
                    val srcUuid = item.optString("source_uuid", "")
                    val srcName = item.optString("name", "Source ${j + 1}").take(80)
                    val defObj = leafByUuid[srcUuid] ?: leafByName[srcName]
                    val model = decodeSourceItem(
                        context = context,
                        item = item,
                        defObj = defObj,
                        fallbackId = srcUuid.ifBlank { "src_imp_${idx}_$j" },
                        fallbackName = srcName,
                        cw = cw,
                        ch = ch,
                        missingAssets = missingAssets,
                        warnings = warnings
                    )
                    add(model)
                }
            }

            SceneModel(
                id = sceneId,
                name = sceneName.take(80),
                sources = parsedSources
            )
        }

        require(scenes.isNotEmpty()) { "No scenes found in imported OBS JSON" }

        val currentSceneName = root.optString("current_scene", scenes.first().name)
        val activeSceneId = scenes.firstOrNull { it.name == currentSceneName }?.id ?: scenes.first().id

        val transType = canvasMeta?.optString("transitionType")?.let {
            runCatching { TransitionType.valueOf(it) }.getOrNull()
        } ?: fromObsTransitionName(root.optString("current_transition", "Fade"))
        val transDur = root.optInt("transition_duration", 300).coerceIn(50, 2000)
        val transColor = canvasMeta?.optLong("transitionColorArgb", 0xFF000000L) ?: 0xFF000000L

        return ImportResult(
            collection = SceneCollection(
                id = "col_${System.currentTimeMillis()}",
                name = colName,
                activeSceneId = activeSceneId,
                scenes = scenes,
                transitionType = transType,
                transitionDurationMs = transDur,
                transitionColorArgb = transColor
            ),
            missingAssets = missingAssets.distinct(),
            warnings = warnings.distinct()
        )
    }

    private fun encodeLeafObsSource(src: SourceModel): JSONObject {
        val settings = JSONObject().apply {
            when (src.type) {
                SourceType.IMAGE_SOURCE -> put("file", src.mediaPathOrUri.orEmpty())
                SourceType.MEDIA_SOURCE -> {
                    put("local_file", src.mediaPathOrUri.orEmpty())
                    put("looping", src.mediaLoop)
                    put("muted", src.mediaMuted)
                }
                SourceType.TEXT_SOURCE -> {
                    put("text", src.textContent)
                    put("color1", src.textColorArgb)
                    put("bk_color", src.textBgColorArgb)
                    put("font", JSONObject().put("size", src.textSizeSp.toInt()))
                }
                SourceType.COLOR_SOURCE -> put("color", src.colorArgb)
                SourceType.CAMERA_2 -> put("front_camera", src.useFrontCamera)
                else -> Unit
            }
        }
        return JSONObject().apply {
            put("name", src.name)
            put("uuid", src.id)
            put("id", toObsSourceId(src.type))
            put("settings", settings)
            put("filters", encodeVideoFilters(src.videoFilters))
        }
    }

    private fun encodeVideoFilters(f: VideoFilterConfig): JSONArray {
        val arr = JSONArray()
        if (f.colorCorrectionEnabled) {
            arr.put(JSONObject().apply {
                put("id", "color_filter_v2")
                put("name", "Color Correction")
                put("enabled", true)
                put("settings", JSONObject().apply {
                    put("brightness", f.brightness.toDouble())
                    put("contrast", f.contrast.toDouble())
                    put("saturation", f.saturation.toDouble())
                    put("gamma", f.gamma.toDouble())
                    put("opacity", f.opacity.toDouble())
                })
            })
        }
        if (f.cropPadEnabled) {
            arr.put(JSONObject().put("id", "crop_filter").put("name", "Crop/Pad").put("enabled", true))
        }
        if (f.scaleAspectEnabled) {
            arr.put(JSONObject().apply {
                put("id", "scale_filter")
                put("name", "Scale/Aspect")
                put("enabled", true)
                put("settings", JSONObject().put("sampling", f.scaleFilter.name))
            })
        }
        if (f.chromaKeyEnabled) {
            arr.put(JSONObject().apply {
                put("id", "chroma_key_filter_v2")
                put("name", "Chroma Key")
                put("enabled", true)
                put("settings", JSONObject().apply {
                    put("key_color", f.chromaKeyColorArgb)
                    put("similarity", f.chromaSimilarity.toDouble())
                    put("smoothness", f.chromaSmoothness.toDouble())
                    put("spill", f.chromaSpill.toDouble())
                })
            })
        }
        if (f.sharpenEnabled) {
            arr.put(JSONObject().apply {
                put("id", "sharpness_filter_v2")
                put("name", "Sharpen")
                put("enabled", true)
                put("settings", JSONObject().put("sharpness", f.sharpenAmount.toDouble()))
            })
        }
        if (f.lutEnabled && f.lutPreset != LutPreset.NONE) {
            arr.put(JSONObject().apply {
                put("id", "clut_filter")
                put("name", "Apply LUT")
                put("enabled", true)
                put("settings", JSONObject().apply {
                    put("preset", f.lutPreset.name)
                    put("clut_amount", f.lutIntensity.toDouble())
                })
            })
        }
        return arr
    }

    private fun decodeSourceItem(
        context: Context,
        item: JSONObject,
        defObj: JSONObject?,
        fallbackId: String,
        fallbackName: String,
        cw: Int,
        ch: Int,
        missingAssets: MutableList<String>,
        warnings: MutableList<String>
    ): SourceModel {
        val obsType = defObj?.optString("id", "").orEmpty()
        val settings = defObj?.optJSONObject("settings") ?: JSONObject()
        val mappedType = fromObsSourceId(obsType, warnings)

        val pos = item.optJSONObject("pos")
        val scale = item.optJSONObject("scale")
        val transform = SourceTransform(
            xFraction = ((pos?.optDouble("x", 0.0) ?: 0.0) / cw).toFloat().coerceIn(-0.5f, 0.95f),
            yFraction = ((pos?.optDouble("y", 0.0) ?: 0.0) / ch).toFloat().coerceIn(-0.5f, 0.95f),
            widthFraction = (scale?.optDouble("x", 1.0) ?: 1.0).toFloat().coerceIn(0.08f, 1.5f),
            heightFraction = (scale?.optDouble("y", 1.0) ?: 1.0).toFloat().coerceIn(0.08f, 1.5f),
            rotationDegrees = item.optDouble("rot", 0.0).toFloat(),
            cropLeft = (item.optInt("crop_left", 0).toFloat() / cw).coerceIn(0f, 0.45f),
            cropTop = (item.optInt("crop_top", 0).toFloat() / ch).coerceIn(0f, 0.45f),
            cropRight = (item.optInt("crop_right", 0).toFloat() / cw).coerceIn(0f, 0.45f),
            cropBottom = (item.optInt("crop_bottom", 0).toFloat() / ch).coerceIn(0f, 0.45f)
        )

        val rawPath = when (mappedType) {
            SourceType.IMAGE_SOURCE -> settings.optString("file", "").ifBlank { null }
            SourceType.MEDIA_SOURCE -> settings.optString("local_file", "").ifBlank { null }
            else -> null
        }

        val assetErr = if ((mappedType == SourceType.IMAGE_SOURCE || mappedType == SourceType.MEDIA_SOURCE) && rawPath != null) {
            if (isAssetAccessible(context, rawPath)) null
            else {
                val shortName = rawPath.substringAfterLast('/')
                missingAssets += shortName
                "Missing asset: $shortName"
            }
        } else if (mappedType == SourceType.IMAGE_SOURCE || mappedType == SourceType.MEDIA_SOURCE) {
            missingAssets += fallbackName
            "Missing media path"
        } else null

        val filters = decodeVideoFilters(defObj?.optJSONArray("filters"))

        return SourceModel(
            id = fallbackId,
            name = fallbackName,
            type = mappedType,
            visible = item.optBoolean("visible", true),
            locked = item.optBoolean("locked", false),
            erroredReason = assetErr,
            useFrontCamera = settings.optBoolean("front_camera", true),
            mediaPathOrUri = rawPath,
            mediaLoop = settings.optBoolean("looping", true),
            mediaMuted = settings.optBoolean("muted", false),
            textContent = settings.optString("text", "Mobile Labs Stream").take(MAX_TEXT_CHARS),
            textColorArgb = settings.optLong("color1", 0xFFFFFFFFL),
            textBgColorArgb = settings.optLong("bk_color", 0x9916161AL),
            textSizeSp = (settings.optJSONObject("font")?.optDouble("size", 44.0) ?: 44.0).toFloat().coerceIn(14f, 140f),
            colorArgb = settings.optLong("color", 0xFF720000L),
            transform = transform,
            videoFilters = filters
        )
    }

    private fun decodeVideoFilters(arr: JSONArray?): VideoFilterConfig {
        if (arr == null || arr.length() == 0) return VideoFilterConfig()
        var cfg = VideoFilterConfig()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            if (!f.optBoolean("enabled", true)) continue
            val s = f.optJSONObject("settings") ?: JSONObject()
            when (f.optString("id")) {
                "color_filter_v2", "color_filter" -> cfg = cfg.copy(
                    colorCorrectionEnabled = true,
                    brightness = s.optDouble("brightness", 0.0).toFloat().coerceIn(-0.5f, 0.5f),
                    contrast = s.optDouble("contrast", 1.0).toFloat().coerceIn(0.2f, 2.0f),
                    saturation = s.optDouble("saturation", 1.0).toFloat().coerceIn(0f, 2.0f),
                    gamma = s.optDouble("gamma", 1.0).toFloat().coerceIn(0.5f, 2.0f),
                    opacity = s.optDouble("opacity", 1.0).toFloat().coerceIn(0.1f, 1.0f)
                )
                "crop_filter" -> cfg = cfg.copy(cropPadEnabled = true)
                "scale_filter" -> cfg = cfg.copy(
                    scaleAspectEnabled = true,
                    scaleFilter = runCatching {
                        ScaleFilterType.valueOf(s.optString("sampling", "BILINEAR"))
                    }.getOrDefault(ScaleFilterType.BILINEAR)
                )
                "chroma_key_filter_v2", "chroma_key_filter" -> cfg = cfg.copy(
                    chromaKeyEnabled = true,
                    chromaKeyColorArgb = s.optLong("key_color", 0xFF00B140L),
                    chromaSimilarity = s.optDouble("similarity", 0.28).toFloat().coerceIn(0.01f, 0.8f),
                    chromaSmoothness = s.optDouble("smoothness", 0.08).toFloat().coerceIn(0.01f, 0.5f),
                    chromaSpill = s.optDouble("spill", 0.15).toFloat().coerceIn(0f, 1.0f)
                )
                "sharpness_filter_v2", "sharpness_filter" -> cfg = cfg.copy(
                    sharpenEnabled = true,
                    sharpenAmount = s.optDouble("sharpness", 0.35).toFloat().coerceIn(0f, 1.0f)
                )
                "clut_filter" -> cfg = cfg.copy(
                    lutEnabled = true,
                    lutPreset = runCatching {
                        LutPreset.valueOf(s.optString("preset", "VIVID_GAMING"))
                    }.getOrDefault(LutPreset.VIVID_GAMING),
                    lutIntensity = s.optDouble("clut_amount", 0.7).toFloat().coerceIn(0f, 1.0f)
                )
            }
        }
        return cfg
    }

    private fun isAssetAccessible(context: Context, pathOrUri: String): Boolean {
        if (pathOrUri.startsWith("content://")) {
            return runCatching {
                context.contentResolver.openInputStream(pathOrUri.toUri())?.close()
                true
            }.getOrDefault(false)
        }
        val f = File(pathOrUri)
        return f.exists() && f.canRead()
    }

    private fun toObsSourceId(type: SourceType): String = when (type) {
        SourceType.SCREEN_CAPTURE -> "screen_capture"
        SourceType.CAMERA_2 -> "android_camera2"
        SourceType.IMAGE_SOURCE -> "image_source"
        SourceType.MEDIA_SOURCE -> "ffmpeg_source"
        SourceType.TEXT_SOURCE -> "text_ft2_source_v2"
        SourceType.COLOR_SOURCE -> "color_source_v2"
        SourceType.MIC_AUDIO -> "wasapi_input_capture"
        SourceType.SYSTEM_AUDIO -> "wasapi_output_capture"
    }

    private fun fromObsSourceId(obsId: String, warnings: MutableList<String>): SourceType = when {
        obsId in setOf("screen_capture", "monitor_capture", "display_capture", "xshm_input", "game_capture") ->
            SourceType.SCREEN_CAPTURE
        obsId in setOf("android_camera2", "dshow_input", "v4l2_input", "av_capture_input") ->
            SourceType.CAMERA_2
        obsId == "image_source" -> SourceType.IMAGE_SOURCE
        obsId in setOf("ffmpeg_source", "vlc_source") -> SourceType.MEDIA_SOURCE
        obsId.startsWith("text_") -> SourceType.TEXT_SOURCE
        obsId.startsWith("color_source") -> SourceType.COLOR_SOURCE
        obsId.contains("input_capture") -> SourceType.MIC_AUDIO
        obsId.contains("output_capture") -> SourceType.SYSTEM_AUDIO
        obsId == "browser_source" -> {
            warnings += "OBS browser_source mapped to Text placeholder (Browser source deferred in Phase 2)"
            SourceType.TEXT_SOURCE
        }
        else -> {
            warnings += "Unknown OBS source '$obsId' mapped to Color placeholder"
            SourceType.COLOR_SOURCE
        }
    }

    private fun toObsTransitionId(type: TransitionType): String = when (type) {
        TransitionType.CUT -> "cut_transition"
        TransitionType.FADE -> "fade_transition"
        TransitionType.SLIDE -> "slide_transition"
        TransitionType.SWIPE -> "swipe_transition"
        TransitionType.FADE_TO_COLOR -> "fade_to_color_transition"
    }

    private fun fromObsTransitionName(name: String): TransitionType = when {
        name.contains("Cut", ignoreCase = true) -> TransitionType.CUT
        name.contains("Slide", ignoreCase = true) -> TransitionType.SLIDE
        name.contains("Swipe", ignoreCase = true) -> TransitionType.SWIPE
        name.contains("Color", ignoreCase = true) -> TransitionType.FADE_TO_COLOR
        else -> TransitionType.FADE
    }
}
