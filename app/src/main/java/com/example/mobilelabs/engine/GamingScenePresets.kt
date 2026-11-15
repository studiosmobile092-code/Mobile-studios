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
                            id = "src_chat_overlay_$ts",
                            name = "Live Chat Box",
                            type = SourceType.CHAT_OVERLAY,
                            visible = true,
                            transform = SourceTransform(0.02f, 0.50f, 0.28f, 0.44f)
                        ),
                        SourceModel(
                            id = "src_alert_gfc_$ts",
                            name = "Stream Alert Box",
                            type = SourceType.ALERT_BOX,
                            visible = true,
                            transform = SourceTransform(0.28f, 0.04f, 0.44f, 0.14f)
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
            GamingScenePreset.PRO_ESPORTS_HUD -> listOf(
                SceneModel(
                    id = "scene_preset_esports_$ts",
                    name = "Pro Esports HUD",
                    sources = listOf(
                        SourceModel(
                            id = "src_screen_esp_$ts",
                            name = "Game Feed (16:9)",
                            type = SourceType.SCREEN_CAPTURE,
                            visible = true
                        ),
                        SourceModel(
                            id = "src_score_esp_$ts",
                            name = "Match Scoreboard",
                            type = SourceType.SCOREBOARD_SOURCE,
                            visible = true,
                            scoreHomeName = "TEAM A",
                            scoreAwayName = "TEAM B",
                            transform = SourceTransform(0.26f, 0.02f, 0.48f, 0.11f)
                        ),
                        SourceModel(
                            id = "src_timer_esp_$ts",
                            name = "Round Timer",
                            type = SourceType.TIMER_SOURCE,
                            visible = true,
                            timerTargetSec = 600,
                            timerCountDown = true,
                            transform = SourceTransform(0.02f, 0.02f, 0.20f, 0.10f)
                        ),
                        SourceModel(
                            id = "src_goal_esp_$ts",
                            name = "Subscriber Goal",
                            type = SourceType.GOAL_BAR,
                            visible = true,
                            goalTitle = "STREAM GOAL",
                            goalCurrent = 42,
                            goalTarget = 100,
                            transform = SourceTransform(0.02f, 0.85f, 0.36f, 0.11f)
                        ),
                        SourceModel(
                            id = "src_alert_esp_$ts",
                            name = "Stream Alert Box",
                            type = SourceType.ALERT_BOX,
                            visible = true,
                            transform = SourceTransform(0.30f, 0.15f, 0.40f, 0.14f)
                        ),
                        SourceModel(
                            id = "src_cam_esp_$ts",
                            name = "Player Cam",
                            type = SourceType.CAMERA_2,
                            visible = true,
                            useFrontCamera = true,
                            borderWidthPx = 4f,
                            transform = SourceTransform(0.75f, 0.67f, 0.23f, 0.29f)
                        )
                    )
                )
            )
            GamingScenePreset.COZY_IRL_GOAL -> listOf(
                SceneModel(
                    id = "scene_preset_irl_$ts",
                    name = "IRL Facecam + Goal",
                    sources = listOf(
                        SourceModel(
                            id = "src_cam_irl_$ts",
                            name = "IRL Pro Camera",
                            type = SourceType.CAMERA_2,
                            visible = true,
                            useFrontCamera = true,
                            transform = SourceTransform(0f, 0f, 1f, 1f)
                        ),
                        SourceModel(
                            id = "src_chat_irl_$ts",
                            name = "Live Chat Box",
                            type = SourceType.CHAT_OVERLAY,
                            visible = true,
                            transform = SourceTransform(0.02f, 0.50f, 0.30f, 0.45f)
                        ),
                        SourceModel(
                            id = "src_goal_irl_$ts",
                            name = "Follower Goal Bar",
                            type = SourceType.GOAL_BAR,
                            visible = true,
                            goalTitle = "FOLLOWER GOAL",
                            goalCurrent = 42,
                            goalTarget = 100,
                            transform = SourceTransform(0.58f, 0.85f, 0.40f, 0.11f)
                        ),
                        SourceModel(
                            id = "src_alert_irl_$ts",
                            name = "Alert Popup",
                            type = SourceType.ALERT_BOX,
                            visible = true,
                            transform = SourceTransform(0.28f, 0.04f, 0.44f, 0.14f)
                        )
                    )
                )
            )
            GamingScenePreset.INTERMISSION_PACK -> listOf(
                buildIntermissionScene("scene_start_$ts", "Starting Soon", "STARTING SOON", 0xFF720000L, includeTimer = true, ts),
                buildIntermissionScene("scene_brb_$ts", "Be Right Back", "BE RIGHT BACK", 0xFF1F242DL, includeTimer = true, ts + 1),
                buildIntermissionScene("scene_end_$ts", "Stream Ending", "THANKS FOR WATCHING", 0xFF16161AL, includeTimer = false, ts + 2)
            )
        }
    }

    private fun buildIntermissionScene(
        sceneId: String,
        sceneName: String,
        titleText: String,
        bgColor: Long,
        includeTimer: Boolean,
        salt: Long
    ): SceneModel {
        val list = mutableListOf(
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
                transform = SourceTransform(0.16f, 0.34f, 0.68f, 0.20f)
            )
        )
        if (includeTimer) {
            list += SourceModel(
                id = "src_timer_$salt",
                name = "Break Countdown",
                type = SourceType.TIMER_SOURCE,
                visible = true,
                timerTargetSec = 300,
                timerCountDown = true,
                transform = SourceTransform(0.35f, 0.58f, 0.30f, 0.14f)
            )
        }
        return SceneModel(
            id = sceneId,
            name = sceneName,
            sources = list
        )
    }
}
