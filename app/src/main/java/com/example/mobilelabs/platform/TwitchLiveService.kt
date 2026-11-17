package com.example.mobilelabs.platform

import com.example.mobilelabs.model.PlatformChatMessage
import com.example.mobilelabs.model.StreamingPlatform
import com.example.mobilelabs.model.TwitchCategoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Twitch Helix API & Twitch IRC WebSocket Chat client for 1-click Twitch connect:
 * - Fetches broadcaster ID, login, display name, stream key, and channel title/game category
 * - Searches Twitch categories and updates live stream title/category
 * - Connects to wss://irc-ws.chat.twitch.tv:443 for real-time incoming/outgoing chat
 */
class TwitchLiveService(
    private val httpClient: OkHttpClient = OAuthPkceManager.defaultHttpClient()
) {

    data class AccountSetup(
        val broadcasterId: String,
        val login: String,
        val displayName: String,
        val ingestUrl: String,
        val streamKey: String,
        val streamTitle: String,
        val categoryId: String,
        val categoryName: String
    )

    private val helixBase = "https://api.twitch.tv/helix"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchAccountSetup(accessToken: String, clientId: String): Result<AccountSetup> =
        withContext(Dispatchers.IO) {
            runCatching {
                val userReq = Request.Builder()
                    .url("$helixBase/users")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Client-Id", clientId)
                    .get()
                    .build()
                val userData = executeJson(userReq).getJSONArray("data").getJSONObject(0)
                val userId = userData.getString("id")
                val login = userData.getString("login")
                val displayName = userData.optString("display_name", login)

                val keyReq = Request.Builder()
                    .url("$helixBase/streams/key?broadcaster_id=$userId")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Client-Id", clientId)
                    .get()
                    .build()
                val streamKey = executeJson(keyReq)
                    .getJSONArray("data")
                    .getJSONObject(0)
                    .getString("stream_key")

                val chanReq = Request.Builder()
                    .url("$helixBase/channels?broadcaster_id=$userId")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Client-Id", clientId)
                    .get()
                    .build()
                val chanObj = runCatching {
                    executeJson(chanReq).optJSONArray("data")?.optJSONObject(0)
                }.getOrNull()

                val ingestUrl = fetchBestRtmpsIngest()

                AccountSetup(
                    broadcasterId = userId,
                    login = login,
                    displayName = displayName,
                    ingestUrl = ingestUrl,
                    streamKey = streamKey,
                    streamTitle = chanObj?.optString("title", "OBSmobile Live Stream").orEmpty(),
                    categoryId = chanObj?.optString("game_id", "").orEmpty(),
                    categoryName = chanObj?.optString("game_name", "Games + Demos").orEmpty()
                )
            }
        }

    suspend fun searchCategories(
        accessToken: String,
        clientId: String,
        query: String
    ): Result<List<TwitchCategoryItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder()
                .url("$helixBase/search/categories?query=$encoded&first=15")
                .header("Authorization", "Bearer $accessToken")
                .header("Client-Id", clientId)
                .get()
                .build()
            val data = executeJson(req).optJSONArray("data") ?: return@runCatching emptyList()
            List(data.length()) { idx ->
                val obj = data.getJSONObject(idx)
                TwitchCategoryItem(
                    id = obj.getString("id"),
                    name = obj.getString("name")
                )
            }
        }
    }

    suspend fun updateChannelInfo(
        accessToken: String,
        clientId: String,
        broadcasterId: String,
        title: String,
        gameId: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                if (title.isNotBlank()) put("title", title.take(140))
                if (!gameId.isNullOrBlank()) put("game_id", gameId)
            }
            val req = Request.Builder()
                .url("$helixBase/channels?broadcaster_id=$broadcasterId")
                .header("Authorization", "Bearer $accessToken")
                .header("Client-Id", clientId)
                .patch(payload.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    error("Twitch update failed (${resp.code}): $body")
                }
            }
        }
    }

    private fun fetchBestRtmpsIngest(): String {
        val defaultIngest = "rtmps://ingest.global-contribute.live-video.net:443/app"
        return runCatching {
            val req = Request.Builder()
                .url("https://ingest.twitch.tv/ingests")
                .get()
                .build()
            val arr = executeJson(req).optJSONArray("ingests") ?: return defaultIngest
            for (i in 0 until arr.length()) {
                val tmpl = arr.optJSONObject(i)?.optString("url_template", "").orEmpty()
                if (tmpl.startsWith("rtmp")) {
                    return tmpl.replace("/{stream_key}", "").replace("{stream_key}", "").trimEnd('/')
                }
            }
            defaultIngest
        }.getOrDefault(defaultIngest)
    }

    private fun executeJson(req: Request): JSONObject {
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(raw).optString("message", raw) }.getOrDefault(raw)
                error("Twitch API ${resp.code}: $msg")
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }
}

/**
 * Lightweight Twitch IRC WebSocket chat client supporting incoming chat tags/colors and sending messages.
 */
class TwitchIrcChatClient(
    private val httpClient: OkHttpClient = OAuthPkceManager.defaultHttpClient()
) {
    private var webSocket: WebSocket? = null
    private var joinedChannel: String = ""

    fun connect(
        oauthToken: String,
        nickLogin: String,
        onMessage: (PlatformChatMessage) -> Unit,
        onStatus: (String) -> Unit
    ) {
        disconnect()
        val channel = nickLogin.lowercase().trimStart('#')
        joinedChannel = channel

        val req = Request.Builder()
            .url("wss://irc-ws.chat.twitch.tv:443")
            .build()

        webSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                webSocket.send("PASS oauth:$oauthToken")
                webSocket.send("NICK $channel")
                webSocket.send("JOIN #$channel")
                onStatus("Twitch IRC connected (#$channel)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { line ->
                        if (line.startsWith("PING")) {
                            val payload = line.removePrefix("PING").trim().ifEmpty { ":tmi.twitch.tv" }
                            webSocket.send("PONG $payload")
                        } else if (line.contains(" PRIVMSG ")) {
                            parseIrcPrivMsg(line)?.let(onMessage)
                        }
                    }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("Twitch chat disconnected: ${t.message ?: "socket error"}")
            }
        })
    }

    fun sendChatMessage(text: String): Boolean {
        val ws = webSocket ?: return false
        val clean = text.replace('\r', ' ').replace('\n', ' ').trim().take(480)
        if (clean.isBlank() || joinedChannel.isBlank()) return false
        return ws.send("PRIVMSG #$joinedChannel :$clean")
    }

    fun disconnect() {
        runCatching { webSocket?.close(1000, "closed") }
        webSocket = null
        joinedChannel = ""
    }

    companion object {
        fun parseIrcPrivMsg(rawLine: String): PlatformChatMessage? {
            var remaining = rawLine
            val tags = mutableMapOf<String, String>()
            if (remaining.startsWith("@")) {
                val spaceIdx = remaining.indexOf(' ')
                if (spaceIdx <= 1) return null
                remaining.substring(1, spaceIdx).split(';').forEach { kv ->
                    val eq = kv.indexOf('=')
                    if (eq > 0) tags[kv.substring(0, eq)] = kv.substring(eq + 1)
                }
                remaining = remaining.substring(spaceIdx + 1)
            }
            val privIdx = remaining.indexOf(" PRIVMSG ")
            if (privIdx < 0) return null
            val prefix = remaining.substring(0, privIdx).trimStart(':')
            val authorFallback = prefix.substringBefore('!').ifBlank { "TwitchUser" }
            val displayName = tags["display-name"]?.takeIf { it.isNotBlank() } ?: authorFallback
            val msgStart = remaining.indexOf(" :", startIndex = privIdx)
            if (msgStart < 0) return null
            val msgBody = remaining.substring(msgStart + 2)

            val badges = tags["badges"].orEmpty()
            val badgeLabel = when {
                badges.contains("broadcaster") -> "HOST"
                badges.contains("moderator") -> "MOD"
                badges.contains("vip") -> "VIP"
                badges.contains("subscriber") -> "SUB"
                else -> ""
            }
            val colorHex = tags["color"].orEmpty()
            val parsedColor = if (colorHex.startsWith("#") && colorHex.length == 7) {
                runCatching { (0xFF000000L or colorHex.substring(1).toLong(16)) }.getOrDefault(0xFFA970FFL)
            } else 0xFFA970FFL

            return PlatformChatMessage(
                id = "tw_${tags["id"] ?: System.nanoTime()}",
                platform = StreamingPlatform.TWITCH,
                author = displayName,
                badgeLabel = badgeLabel,
                text = msgBody,
                timestampMs = System.currentTimeMillis(),
                colorArgb = parsedColor
            )
        }
    }
}
