package com.example.mobilelabs.platform

import com.example.mobilelabs.model.PlatformChatMessage
import com.example.mobilelabs.model.StreamingPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * YouTube Data API v3 integration for 1-click Live setup:
 * - Fetches authenticated channel title & ID
 * - Creates or reuses an upcoming LiveBroadcast + LiveStream, binds them, and extracts RTMPS URL + stream key
 * - Updates title/privacy and transitions broadcast lifecycle (testing -> live -> complete)
 * - Polls and sends messages to YouTube Live Chat
 */
class YouTubeLiveService(
    private val httpClient: OkHttpClient = OAuthPkceManager.defaultHttpClient()
) {

    data class ChannelInfo(
        val channelId: String,
        val title: String
    )

    data class BroadcastSetup(
        val channelId: String,
        val channelTitle: String,
        val broadcastId: String,
        val liveChatId: String,
        val ingestUrl: String,
        val streamKey: String,
        val title: String,
        val privacyStatus: String
    )

    data class ChatPollResult(
        val messages: List<PlatformChatMessage>,
        val nextPageToken: String?,
        val pollingIntervalMs: Long
    )

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://www.googleapis.com/youtube/v3"

    suspend fun fetchChannelInfo(accessToken: String): Result<ChannelInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/channels?part=snippet&mine=true")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val body = executeJson(req)
            val items = body.optJSONArray("items")
            require(items != null && items.length() > 0) {
                "No YouTube channel found on this Google account"
            }
            val first = items.getJSONObject(0)
            ChannelInfo(
                channelId = first.getString("id"),
                title = first.getJSONObject("snippet").optString("title", "YouTube Channel")
            )
        }
    }

    suspend fun prepareOneClickBroadcast(
        accessToken: String,
        title: String,
        privacyStatus: String
    ): Result<BroadcastSetup> = withContext(Dispatchers.IO) {
        runCatching {
            val channel = fetchChannelInfo(accessToken).getOrThrow()

            // 1. Check if an active or upcoming broadcast with a bound stream already exists
            val listReq = Request.Builder()
                .url("$baseUrl/liveBroadcasts?part=id,snippet,status,contentDetails&broadcastStatus=upcoming&broadcastType=all")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val existingItems = runCatching { executeJson(listReq).optJSONArray("items") }.getOrNull()
            var broadcastObj: JSONObject? = existingItems?.let { arr ->
                if (arr.length() > 0) arr.optJSONObject(0) else null
            }

            if (broadcastObj == null) {
                val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val startIso = isoFormat.format(Date(System.currentTimeMillis() + 5_000L))
                val insertPayload = JSONObject().apply {
                    put("snippet", JSONObject().apply {
                        put("title", title.ifBlank { "Mobile Labs Live Stream" }.take(100))
                        put("scheduledStartTime", startIso)
                    })
                    put("status", JSONObject().apply {
                        put("privacyStatus", privacyStatus.ifBlank { "public" })
                        put("selfDeclaredMadeForKids", false)
                    })
                    put("contentDetails", JSONObject().apply {
                        put("enableAutoStart", true)
                        put("enableAutoStop", false)
                        put("latencyPreference", "ultraLow")
                    })
                }
                val createReq = Request.Builder()
                    .url("$baseUrl/liveBroadcasts?part=id,snippet,status,contentDetails")
                    .header("Authorization", "Bearer $accessToken")
                    .post(insertPayload.toString().toRequestBody(jsonMediaType))
                    .build()
                broadcastObj = executeJson(createReq)
            }

            val broadcastId = broadcastObj.getString("id")
            val liveChatId = broadcastObj.optJSONObject("snippet")?.optString("liveChatId", "").orEmpty()
            var boundStreamId = broadcastObj.optJSONObject("contentDetails")?.optString("boundStreamId", "").orEmpty()

            // 2. Fetch bound stream or create/reuse a persistent Mobile Labs stream key
            var streamObj = if (boundStreamId.isNotBlank()) {
                findStreamById(accessToken, boundStreamId)
            } else {
                findExistingStreams(accessToken).firstOrNull()
            }

            if (streamObj == null) {
                val streamPayload = JSONObject().apply {
                    put("snippet", JSONObject().apply {
                        put("title", "Mobile Labs RTMP Ingest")
                    })
                    put("cdn", JSONObject().apply {
                        put("frameRate", "variable")
                        put("ingestionType", "rtmp")
                        put("resolution", "variable")
                    })
                }
                val createStreamReq = Request.Builder()
                    .url("$baseUrl/liveStreams?part=id,snippet,cdn,status")
                    .header("Authorization", "Bearer $accessToken")
                    .post(streamPayload.toString().toRequestBody(jsonMediaType))
                    .build()
                streamObj = executeJson(createStreamReq)
            }

            val streamId = streamObj.getString("id")
            if (boundStreamId != streamId) {
                val bindReq = Request.Builder()
                    .url("$baseUrl/liveBroadcasts/bind?id=$broadcastId&part=id,snippet,contentDetails,status&streamId=$streamId")
                    .header("Authorization", "Bearer $accessToken")
                    .post("".toRequestBody(jsonMediaType))
                    .build()
                val boundResult = executeJson(bindReq)
                boundStreamId = boundResult.optJSONObject("contentDetails")?.optString("boundStreamId", streamId).orEmpty()
            }

            val ingestionInfo = streamObj.optJSONObject("cdn")?.optJSONObject("ingestionInfo")
            val rtmpsUrl = ingestionInfo?.optString("rtmpsIngestionAddress").orEmpty()
                .ifBlank { ingestionInfo?.optString("ingestionAddress").orEmpty() }
                .ifBlank { "rtmps://a.rtmps.youtube.com:443/live2" }
            val streamName = ingestionInfo?.optString("streamName").orEmpty()
            require(streamName.isNotBlank()) {
                "YouTube returned no stream key (check that Live Streaming is enabled on your channel)"
            }

            BroadcastSetup(
                channelId = channel.channelId,
                channelTitle = channel.title,
                broadcastId = broadcastId,
                liveChatId = liveChatId,
                ingestUrl = rtmpsUrl,
                streamKey = streamName,
                title = broadcastObj.optJSONObject("snippet")?.optString("title", title).orEmpty(),
                privacyStatus = broadcastObj.optJSONObject("status")?.optString("privacyStatus", privacyStatus).orEmpty()
            )
        }
    }

    suspend fun updateBroadcastMetadata(
        accessToken: String,
        broadcastId: String,
        title: String,
        privacyStatus: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("id", broadcastId)
                put("snippet", JSONObject().apply {
                    put("title", title.ifBlank { "Mobile Labs Live Stream" }.take(100))
                })
                put("status", JSONObject().apply {
                    put("privacyStatus", privacyStatus.ifBlank { "public" })
                })
            }
            val req = Request.Builder()
                .url("$baseUrl/liveBroadcasts?part=snippet,status")
                .header("Authorization", "Bearer $accessToken")
                .put(payload.toString().toRequestBody(jsonMediaType))
                .build()
            executeJson(req)
            Unit
        }
    }

    suspend fun waitForActiveStreamStatus(
        accessToken: String,
        broadcastId: String,
        maxWaitMs: Long = 15_000L
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val active = runCatching {
                val bReq = Request.Builder()
                    .url("$baseUrl/liveBroadcasts?part=contentDetails,status&id=$broadcastId")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                val bArr = executeJson(bReq).optJSONArray("items")
                val item = if (bArr != null && bArr.length() > 0) bArr.optJSONObject(0) else null
                val lifeCycle = item?.optJSONObject("status")?.optString("lifeCycleStatus").orEmpty()
                if (lifeCycle == "live") return@withContext true
                val streamId = item?.optJSONObject("contentDetails")?.optString("boundStreamId").orEmpty()
                if (streamId.isNotBlank()) {
                    val stream = findStreamById(accessToken, streamId)
                    stream?.optJSONObject("status")?.optString("streamStatus") == "active"
                } else {
                    false
                }
            }.getOrDefault(false)
            if (active) return@withContext true
            delay(1500L)
        }
        false
    }

    suspend fun transitionBroadcast(
        accessToken: String,
        broadcastId: String,
        targetStatus: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/liveBroadcasts/transition?id=$broadcastId&broadcastStatus=$targetStatus&part=status")
                .header("Authorization", "Bearer $accessToken")
                .post("".toRequestBody(jsonMediaType))
                .build()
            executeJson(req)
            Unit
        }
    }

    suspend fun pollLiveChat(
        accessToken: String,
        liveChatId: String,
        pageToken: String? = null
    ): Result<ChatPollResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildString {
                append("$baseUrl/liveChat/messages?liveChatId=${URLEncoder.encode(liveChatId, "UTF-8")}")
                append("&part=id,snippet,authorDetails")
                if (!pageToken.isNullOrBlank()) {
                    append("&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}")
                }
            }
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val json = executeJson(req)
            val items = json.optJSONArray("items")
            val messages = buildList {
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val snippet = item.optJSONObject("snippet") ?: continue
                        val author = item.optJSONObject("authorDetails")
                        val displayMsg = snippet.optString("displayMessage", "").trim()
                        if (displayMsg.isBlank()) continue
                        val isOwner = author?.optBoolean("isChatOwner", false) == true
                        val isMod = author?.optBoolean("isChatModerator", false) == true
                        val badge = when {
                            isOwner -> "HOST"
                            isMod -> "MOD"
                            author?.optBoolean("isChatSponsor", false) == true -> "MBR"
                            else -> ""
                        }
                        add(
                            PlatformChatMessage(
                                id = "yt_${item.optString("id", "$i")}",
                                platform = StreamingPlatform.YOUTUBE,
                                author = author?.optString("displayName", "Viewer").orEmpty(),
                                badgeLabel = badge,
                                text = displayMsg,
                                timestampMs = System.currentTimeMillis(),
                                colorArgb = if (isOwner) 0xFFFF4E45L else 0xFF4CA6FFL
                            )
                        )
                    }
                }
            }
            ChatPollResult(
                messages = messages,
                nextPageToken = json.optString("nextPageToken", "").ifBlank { null },
                pollingIntervalMs = json.optLong("pollingIntervalMillis", 4000L).coerceIn(2000L, 15000L)
            )
        }
    }

    suspend fun sendLiveChatMessage(
        accessToken: String,
        liveChatId: String,
        messageText: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("snippet", JSONObject().apply {
                    put("liveChatId", liveChatId)
                    put("type", "textMessageEvent")
                    put("textMessageDetails", JSONObject().apply {
                        put("messageText", messageText.take(200))
                    })
                })
            }
            val req = Request.Builder()
                .url("$baseUrl/liveChat/messages?part=snippet")
                .header("Authorization", "Bearer $accessToken")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            executeJson(req)
            Unit
        }
    }

    private fun findStreamById(accessToken: String, streamId: String): JSONObject? {
        val req = Request.Builder()
            .url("$baseUrl/liveStreams?part=id,snippet,cdn,status&id=$streamId")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val arr = runCatching { executeJson(req).optJSONArray("items") }.getOrNull() ?: return null
        return if (arr.length() > 0) arr.optJSONObject(0) else null
    }

    private fun findExistingStreams(accessToken: String): List<JSONObject> {
        val req = Request.Builder()
            .url("$baseUrl/liveStreams?part=id,snippet,cdn,status&mine=true")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val arr = runCatching { executeJson(req).optJSONArray("items") }.getOrNull() ?: return emptyList()
        return List(arr.length()) { idx -> arr.getJSONObject(idx) }
    }

    private fun executeJson(req: Request): JSONObject {
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
                }.getOrDefault("").ifBlank { raw }
                error("YouTube API ${resp.code}: $msg")
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }
}
