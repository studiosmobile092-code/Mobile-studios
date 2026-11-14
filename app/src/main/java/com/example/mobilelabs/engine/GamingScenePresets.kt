package com.example.mobilelabs.engine

import com.example.mobilelabs.model.GamingScenePreset
import com.example.mobilelabs.model.SceneModel
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.SourceTransform
import com.example.mobilelabs.model.SourceType

/**
 * M3.2 One-tap Gaming Scene Presets generating normal editable OBS scenes (§12.3).
 */
object GamingScenePresets {

    fun buildScenesForPreset(preset: GamingScenePreset): List<SceneModel> {
        val ts = System.currentTimeMillis()
        return when (preset) {
            GamingScenePreset.GAME_ONLY -> listOf(
                SceneModel(
                    id = "scene_preset_game_$ts",
                    name = "Game Only",
                    sources = listOf(
                        SourceModel(
                            id = "src_screen_$ts",
                            name = "Tablet Screen (16:9)",
                            type = SourceType.SCREEN_CAPTURE,
                            visible = true
                        )
                    )
                )
            )
            GamingScenePreset.GAME_FACECAM -> listOf(
                SceneModel(
                    id = "scene_preset_gf_$ts",
                    name = "Game + Facecam",
                    sources = listOf(
                        SourceModel(
                            id = "src_screen_gf_$ts",
                            name = "Tablet Screen (16:9)",
                            type = SourceType.SCREEN_CAPTURE,
                            visible = true
                        ),
                        SourceModel(
                            id = "src_cam_gf_$ts",
                            name = "Facecam PiP",
                            type = SourceType.CAMERA_2,
                            visible = true,
                            useFrontCamera = true,
                            transform = SourceTransform(0.73f, 0.68f, 0.24f, 0.27f)
                        )
                    )
                )
            )
            GamingScenePreset.GAME_FACECAM_CHAT -> listOf(
                SceneModel(
                    id = "scene_preset_gfc_$ts",
                    name = "Game + Cam + Chat",
                    sources = listOf(
                        SourceModel(
                            id = "src_screen_gfc_$ts",
                            name = "Tablet Screen (16:9)",
                            type = SourceType.SCREEN_CAPTURE,
                            visible = true
                        ),
                        SourceModel(
                            id = "src_chat_bg_$ts",
                            name = "Chat Panel Backdrop",
                            type = SourceType.COLOR_SOURCE,
                            visible = true,
                            colorArgb = 0xAA16161AL,
                            transform = SourceTransform(0.02f, 0.56f, 0.25f, 0.40f)
                        ),
                        SourceModel(
                            id = "src_chat_label_$ts",
                            name = "Stream Chat Header",
                            type = SourceType.TEXT_SOURCE,
                            visible = true,
                            textContent = "LIVE CHAT",
                            textSizeSp = 28f,
                            textColorArgb = 0xFFF2F2F2L,
                            textBgColorArgb = 0xCC720000L,
                            transform = SourceTransform(0.02f, 0.51f, 0.25f, 0.06f)
                        ),
                        SourceModel(
                            id = "src_cam_gfc_$ts",
                            name = "Facecam PiP",
                            type = SourceType.CAMERA_2,
                            visible = true,
                            useFrontCamera = true,
                            transform = SourceTransform(0.73f, 0.68f, 0.24f, 0.27f)
                        )
                    )
                )
            )
            GamingScenePreset.INTERMISSION_PACK -> listOf(
                buildIntermissionScene("scene_start_$ts", "Starting Soon", "STARTING SOON", 0xFF720000L, ts),
                buildIntermissionScene("scene_brb_$ts", "Be Right Back", "BE RIGHT BACK", 0xFF1F242DL, ts + 1),
                buildIntermissionScene("scene_end_$ts", "Stream Ending", "THANKS FOR WATCHING", 0xFF16161AL, ts + 2)
            )
        }
    }

    private fun buildIntermissionScene(
        sceneId: String,
        sceneName: String,
        titleText: String,
        bgColor: Long,
        salt: Long
    ): SceneModel {
        return SceneModel(
            id = sceneId,
            name = sceneName,
            sources = listOf(
                SourceModel(
                    id = "src_bg_$salt",
                    name = "$sceneName Backdrop",
                    type = SourceType.COLOR_SOURCE,
                    visible = true,
                    colorArgb = bgColor
                ),
                SourceModel(
                    id = "src_txt_$salt",
                    name = "$sceneName Title",
                    type = SourceType.TEXT_SOURCE,
                    visible = true,
                    textContent = "$titleText · MOBILE LABS",
                    textSizeSp = 46f,
                    textColorArgb = 0xFFFFFFFFL,
                    textBgColorArgb = 0x88000000L,
                    transform = SourceTransform(0.16f, 0.40f, 0.68f, 0.20f)
                )
            )
        )
    }
}
