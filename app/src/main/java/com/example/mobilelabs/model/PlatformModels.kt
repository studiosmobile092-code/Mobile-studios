package com.example.mobilelabs.model

import androidx.compose.runtime.Immutable

enum class StreamingPlatform(val label: String) {
    YOUTUBE("YouTube Live"),
    TWITCH("Twitch")
}

enum class FacecamShape(val label: String, val shaderCode: Int) {
    RECTANGLE("Rectangle", 0),
    ROUNDED("Rounded Box", 1),
    CIRCLE("Circle", 2),
    SQUIRCLE("Squircle", 3),
    HEXAGON("Hexagon", 4)
}

@Immutable
data class PlatformConnectionState(
    val platform: StreamingPlatform,
    val connected: Boolean = false,
    val authInProgress: Boolean = false,
    val channelName: String = "",
    val broadcasterId: String = "",
    val ingestUrl: String = "",
    val maskedStreamKey: String = "Not fetched",
    val hasStreamKey: Boolean = false,
    val broadcastId: String = "",
    val liveChatId: String = "",
    val streamTitle: String = "Mobile Labs Live Stream",
    val categoryName: String = "Games + Demos",
    val categoryId: String = "",
    val privacyStatus: String = "public",
    val enabledForMultiStream: Boolean = true,
    val outputPhase: SessionPhase = SessionPhase.IDLE,
    val errorReason: String? = null,
    val bufferedBytes: Long = 0L,
    val droppedFrames: Long = 0L
)

@Immutable
data class TwitchCategoryItem(
    val id: String,
    val name: String
)

@Immutable
data class PlatformChatMessage(
    val id: String,
    val platform: StreamingPlatform,
    val author: String,
    val badgeLabel: String = "",
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val colorArgb: Long = 0xFF56B4E9L
)

@Immutable
data class SoundboardPad(
    val id: String,
    val label: String,
    val filePath: String? = null,
    val builtInTone: String? = null,
    val volume: Float = 0.85f
)

@Immutable
data class ReplayBufferState(
    val enabled: Boolean = false,
    val durationSec: Int = 30,
    val bufferedSeconds: Float = 0f,
    val bufferedKb: Long = 0L,
    val clipsSavedCount: Int = 0,
    val lastSavedClipPath: String? = null
)

@Immutable
data class DrawingStroke(
    val id: String,
    val colorArgb: Long,
    val strokeWidthFraction: Float,
    val points: List<Pair<Float, Float>>
)

@Immutable
data class TwitchDeviceAuthState(
    val active: Boolean = false,
    val userCode: String = "",
    val deviceCode: String = "",
    val verificationUri: String = "https://www.twitch.tv/activate",
    val expiresAtMs: Long = 0L,
    val polling: Boolean = false,
    val error: String? = null
)

enum class AlertEventType(val label: String, val accentArgb: Long) {
    FOLLOWER("NEW FOLLOWER", 0xFF9146FFL),
    SUBSCRIBER("NEW SUBSCRIBER", 0xFF00C853L),
    DONATION("SUPER CHAT / TIP", 0xFFFFB300L),
    RAID("INCOMING RAID", 0xFFE91E63L),
    MILESTONE("GOAL MILESTONE", 0xFF29B6F6L)
}

@Immutable
data class StreamAlertEvent(
    val id: String,
    val type: AlertEventType,
    val platform: StreamingPlatform = StreamingPlatform.TWITCH,
    val senderName: String,
    val detailText: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val durationMs: Long = 4500L
)

