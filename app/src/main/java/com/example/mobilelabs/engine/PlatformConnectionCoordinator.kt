package com.example.mobilelabs.engine

import android.content.Context
import android.net.Uri
import com.example.mobilelabs.model.MobileLabsUiState
import com.example.mobilelabs.model.PlatformChatMessage
import com.example.mobilelabs.model.PlatformConnectionState
import com.example.mobilelabs.model.SessionPhase
import com.example.mobilelabs.model.StreamingPlatform
import com.example.mobilelabs.platform.OAuthPkceManager
import com.example.mobilelabs.platform.TwitchIrcChatClient
import com.example.mobilelabs.platform.TwitchLiveService
import com.example.mobilelabs.platform.YouTubeLiveService
import com.example.mobilelabs.security.KeystoreSecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordinates 1-click OAuth PKCE sign-in, encrypted credential hydration/refresh,
 * YouTube Live & Twitch API metadata/ingest resolution, and unified Live Chat (YouTube + Twitch IRC).
 */
class PlatformConnectionCoordinator(
    context: Context,
    private val secretStore: KeystoreSecretStore,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MobileLabsUiState>,
    private val onChatUpdated: () -> Unit,
    private val logEvent: (String) -> Unit
) {
    private val oauthManager = OAuthPkceManager(context)
    private val youtubeService = YouTubeLiveService()
    private val twitchService = TwitchLiveService()
    private val twitchIrc = TwitchIrcChatClient()

    private var youtubeChatJob: Job? = null
    private val seenChatIds = LinkedHashSet<String>(256)

    fun hydrateSavedConnections() {
        scope.launch(Dispatchers.IO) {
            val hasCustomYtId = secretStore.loadCustomClientId(StreamingPlatform.YOUTUBE).isNotBlank()
            val hasCustomTwId = secretStore.loadCustomClientId(StreamingPlatform.TWITCH).isNotBlank()
            var ytBundle = secretStore.loadPlatformBundle(StreamingPlatform.YOUTUBE)
            var twBundle = secretStore.loadPlatformBundle(StreamingPlatform.TWITCH)

            if (ytBundle != null && ytBundle.accessToken.isNotBlank()) {
                ytBundle = ensureFreshBundle(StreamingPlatform.YOUTUBE, ytBundle)
            }
            if (twBundle != null && twBundle.accessToken.isNotBlank()) {
                twBundle = ensureFreshBundle(StreamingPlatform.TWITCH, twBundle)
            }

            uiState.update { cur ->
                cur.copy(
                    customYoutubeClientIdSaved = hasCustomYtId,
                    customTwitchClientIdSaved = hasCustomTwId,
                    youtubeConnection = ytBundle?.toConnectionState(StreamingPlatform.YOUTUBE)
                        ?: PlatformConnectionState(StreamingPlatform.YOUTUBE),
                    twitchConnection = twBundle?.toConnectionState(StreamingPlatform.TWITCH)
                        ?: PlatformConnectionState(StreamingPlatform.TWITCH)
                )
            }

            if (twBundle != null && twBundle.accessToken.isNotBlank() && twBundle.channelName.isNotBlank()) {
                startTwitchChat(twBundle.accessToken, twBundle.channelName)
            }
            if (ytBundle != null && ytBundle.accessToken.isNotBlank() && ytBundle.liveChatId.isNotBlank()) {
                startYouTubeChatPolling(ytBundle.liveChatId)
            }
        }
    }

    fun startPlatformOAuth(platform: StreamingPlatform) {
        scope.launch {
            val clientId = secretStore.resolveClientId(platform)
            updatePlatformState(platform) { it.copy(authInProgress = true, errorReason = null) }
            val launched = oauthManager.launchOAuthLogin(platform, clientId)
            launched.onSuccess {
                uiState.update { it.copy(statusMessage = "Sign in to ${platform.label} in browser...") }
                logEvent("Launched OAuth PKCE for ${platform.label}")
            }.onFailure { err ->
                val msg = err.message ?: "Failed to launch ${platform.label} OAuth"
                updatePlatformState(platform) { it.copy(authInProgress = false, errorReason = msg) }
                uiState.update { it.copy(statusMessage = msg) }
                logEvent("OAuth launch failed (${platform.label}): $msg")
            }
        }
    }

    fun handleOAuthRedirect(uri: Uri) {
        if (!oauthManager.hasPendingAuth()) return
        scope.launch {
            val handled = oauthManager.handleRedirectUri(uri) ?: return@launch
            val (platform, tokenRes) = handled
            if (platform == null) return@launch

            tokenRes.onFailure { err ->
                val msg = err.message ?: "OAuth failed"
                updatePlatformState(platform) { it.copy(authInProgress = false, errorReason = msg) }
                uiState.update { it.copy(statusMessage = msg) }
                logEvent("OAuth callback error (${platform.label}): $msg")
            }.onSuccess { tokens ->
                val clientId = secretStore.resolveClientId(platform)
                when (platform) {
                    StreamingPlatform.YOUTUBE -> completeYouTubeConnection(tokens)
                    StreamingPlatform.TWITCH -> completeTwitchConnection(tokens, clientId)
                }
            }
        }
    }

    private suspend fun completeYouTubeConnection(tokens: OAuthPkceManager.TokenResult) {
        val currentState = uiState.value.youtubeConnection
        val prep = youtubeService.prepareOneClickBroadcast(
            accessToken = tokens.accessToken,
            title = currentState.streamTitle,
            privacyStatus = currentState.privacyStatus
        )
        prep.onSuccess { setup ->
            val bundle = KeystoreSecretStore.PlatformCredentialBundle(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAtMs = tokens.expiresAtMs,
                channelName = setup.channelTitle,
                broadcasterId = setup.channelId,
                ingestUrl = setup.ingestUrl,
                streamKey = setup.streamKey,
                broadcastId = setup.broadcastId,
                liveChatId = setup.liveChatId,
                streamTitle = setup.title,
                privacyStatus = setup.privacyStatus,
                enabledForMultiStream = true
            )
            secretStore.savePlatformBundle(StreamingPlatform.YOUTUBE, bundle)
            updatePlatformState(StreamingPlatform.YOUTUBE) { bundle.toConnectionState(StreamingPlatform.YOUTUBE) }
            uiState.update { it.copy(statusMessage = "YouTube Connected: ${setup.channelTitle} (1-Click Ready)") }
            logEvent("YouTube Live connected: ${setup.channelTitle}, stream key auto-fetched")
            if (setup.liveChatId.isNotBlank()) {
                startYouTubeChatPolling(setup.liveChatId)
            }
        }.onFailure { err ->
            // Fallback: fetch channel info even if user hasn't enabled Live Streaming on YouTube yet
            val ch = youtubeService.fetchChannelInfo(tokens.accessToken).getOrNull()
            val bundle = KeystoreSecretStore.PlatformCredentialBundle(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAtMs = tokens.expiresAtMs,
                channelName = ch?.title ?: "YouTube Account",
                broadcasterId = ch?.channelId.orEmpty(),
                ingestUrl = "rtmps://a.rtmps.youtube.com:443/live2",
                enabledForMultiStream = false
            )
            secretStore.savePlatformBundle(StreamingPlatform.YOUTUBE, bundle)
            val errMsg = err.message ?: "Live setup incomplete"
            updatePlatformState(StreamingPlatform.YOUTUBE) {
                bundle.toConnectionState(StreamingPlatform.YOUTUBE).copy(errorReason = errMsg)
            }
            uiState.update { it.copy(statusMessage = "YouTube signed in, note: $errMsg") }
            logEvent("YouTube partial connect: $errMsg")
        }
    }

    private suspend fun completeTwitchConnection(tokens: OAuthPkceManager.TokenResult, clientId: String) {
        val setupRes = twitchService.fetchAccountSetup(tokens.accessToken, clientId)
        setupRes.onSuccess { setup ->
            val bundle = KeystoreSecretStore.PlatformCredentialBundle(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAtMs = tokens.expiresAtMs,
                channelName = setup.login,
                broadcasterId = setup.broadcasterId,
                ingestUrl = setup.ingestUrl,
                streamKey = setup.streamKey,
                streamTitle = setup.streamTitle.ifBlank { "Mobile Labs Live Stream" },
                categoryId = setup.categoryId,
                categoryName = setup.categoryName.ifBlank { "Games + Demos" },
                enabledForMultiStream = true
            )
            secretStore.savePlatformBundle(StreamingPlatform.TWITCH, bundle)
            updatePlatformState(StreamingPlatform.TWITCH) { bundle.toConnectionState(StreamingPlatform.TWITCH) }
            uiState.update { it.copy(statusMessage = "Twitch Connected: ${setup.displayName} (1-Click Ready)") }
            logEvent("Twitch connected: ${setup.displayName} (#${setup.login}), stream key auto-fetched")
            startTwitchChat(tokens.accessToken, setup.login)
        }.onFailure { err ->
            val msg = err.message ?: "Twitch setup failed"
            updatePlatformState(StreamingPlatform.TWITCH) { it.copy(authInProgress = false, errorReason = msg) }
            uiState.update { it.copy(statusMessage = msg) }
            logEvent("Twitch connect failed: $msg")
        }
    }

    fun disconnectPlatform(platform: StreamingPlatform) {
        scope.launch(Dispatchers.IO) {
            secretStore.clearPlatformBundle(platform)
            if (platform == StreamingPlatform.TWITCH) {
                twitchIrc.disconnect()
            } else {
                youtubeChatJob?.cancel()
                youtubeChatJob = null
            }
            updatePlatformState(platform) { PlatformConnectionState(platform) }
            uiState.update { it.copy(statusMessage = "${platform.label} disconnected") }
            logEvent("Disconnected ${platform.label}")
        }
    }

    fun saveCustomClientId(platform: StreamingPlatform, clientId: String) {
        scope.launch(Dispatchers.IO) {
            secretStore.saveCustomClientId(platform, clientId)
            val saved = secretStore.loadCustomClientId(platform).isNotBlank()
            uiState.update { cur ->
                when (platform) {
                    StreamingPlatform.YOUTUBE -> cur.copy(customYoutubeClientIdSaved = saved)
                    StreamingPlatform.TWITCH -> cur.copy(customTwitchClientIdSaved = saved)
                }.copy(
                    statusMessage = if (saved) {
                        "Saved custom ${platform.label} OAuth Client ID (Keystore encrypted)"
                    } else {
                        "Cleared custom ${platform.label} Client ID (using AI Studio Secret default)"
                    }
                )
            }
        }
    }

    fun setPlatformMultiStreamEnabled(platform: StreamingPlatform, enabled: Boolean) {
        updatePlatformState(platform) { it.copy(enabledForMultiStream = enabled) }
        scope.launch(Dispatchers.IO) {
            val bundle = secretStore.loadPlatformBundle(platform) ?: return@launch
            secretStore.savePlatformBundle(platform, bundle.copy(enabledForMultiStream = enabled))
        }
    }

    fun searchTwitchCategories(query: String) {
        if (query.isBlank()) {
            uiState.update { it.copy(twitchCategories = emptyList()) }
            return
        }
        scope.launch(Dispatchers.IO) {
            val bundle = secretStore.loadPlatformBundle(StreamingPlatform.TWITCH) ?: return@launch
            val fresh = ensureFreshBundle(StreamingPlatform.TWITCH, bundle)
            val clientId = secretStore.resolveClientId(StreamingPlatform.TWITCH)
            twitchService.searchCategories(fresh.accessToken, clientId, query).onSuccess { items ->
                uiState.update { it.copy(twitchCategories = items) }
            }
        }
    }

    fun updatePlatformBroadcastMeta(
        platform: StreamingPlatform,
        title: String,
        categoryId: String,
        categoryName: String,
        privacyStatus: String
    ) {
        scope.launch(Dispatchers.IO) {
            val bundle = secretStore.loadPlatformBundle(platform) ?: return@launch
            val fresh = ensureFreshBundle(platform, bundle)
            val updatedBundle = fresh.copy(
                streamTitle = title.ifBlank { fresh.streamTitle },
                categoryId = categoryId.ifBlank { fresh.categoryId },
                categoryName = categoryName.ifBlank { fresh.categoryName },
                privacyStatus = privacyStatus.ifBlank { fresh.privacyStatus }
            )
            secretStore.savePlatformBundle(platform, updatedBundle)
            updatePlatformState(platform) { updatedBundle.toConnectionState(platform) }

            when (platform) {
                StreamingPlatform.YOUTUBE -> {
                    if (updatedBundle.broadcastId.isNotBlank()) {
                        youtubeService.updateBroadcastMetadata(
                            updatedBundle.accessToken,
                            updatedBundle.broadcastId,
                            updatedBundle.streamTitle,
                            updatedBundle.privacyStatus
                        ).onFailure { err ->
                            logEvent("YouTube title update note: ${err.message}")
                        }
                    }
                }
                StreamingPlatform.TWITCH -> {
                    val clientId = secretStore.resolveClientId(StreamingPlatform.TWITCH)
                    if (updatedBundle.broadcasterId.isNotBlank()) {
                        twitchService.updateChannelInfo(
                            updatedBundle.accessToken,
                            clientId,
                            updatedBundle.broadcasterId,
                            updatedBundle.streamTitle,
                            updatedBundle.categoryId.ifBlank { null }
                        ).onFailure { err ->
                            logEvent("Twitch info update note: ${err.message}")
                        }
                    }
                }
            }
            uiState.update { it.copy(statusMessage = "Updated ${platform.label} stream metadata") }
        }
    }

    /**
     * Resolves fresh ingest URL + stream key for enabled connected platforms (YouTube + Twitch)
     * and launches independent RTMP/RTMPS output slots sharing the single HW encode.
     * Returns number of platform streams started.
     */
    suspend fun startEnabledPlatformStreams(
        launchSlot: (
            slotId: String,
            ingestUrl: String,
            streamKey: String,
            onConnected: () -> Unit,
            onError: (String) -> Unit
        ) -> Unit
    ): Int {
        var startedCount = 0
        val ytState = uiState.value.youtubeConnection
        if (ytState.connected && ytState.enabledForMultiStream) {
            var bundle = secretStore.loadPlatformBundle(StreamingPlatform.YOUTUBE)
            if (bundle != null) {
                bundle = ensureFreshBundle(StreamingPlatform.YOUTUBE, bundle)
                if (bundle.streamKey.isBlank() || bundle.ingestUrl.isBlank()) {
                    youtubeService.prepareOneClickBroadcast(
                        bundle.accessToken,
                        bundle.streamTitle,
                        bundle.privacyStatus
                    ).onSuccess { setup ->
                        bundle = bundle!!.copy(
                            ingestUrl = setup.ingestUrl,
                            streamKey = setup.streamKey,
                            broadcastId = setup.broadcastId,
                            liveChatId = setup.liveChatId
                        )
                        secretStore.savePlatformBundle(StreamingPlatform.YOUTUBE, bundle!!)
                        updatePlatformState(StreamingPlatform.YOUTUBE) {
                            bundle!!.toConnectionState(StreamingPlatform.YOUTUBE)
                        }
                        if (setup.liveChatId.isNotBlank()) startYouTubeChatPolling(setup.liveChatId)
                    }
                }
                val finalYt = bundle
                if (finalYt != null && finalYt.ingestUrl.isNotBlank() && finalYt.streamKey.isNotBlank()) {
                    startedCount++
                    updatePlatformState(StreamingPlatform.YOUTUBE) {
                        it.copy(outputPhase = SessionPhase.CONNECTING, errorReason = null)
                    }
                    launchSlot(
                        "youtube",
                        finalYt.ingestUrl,
                        finalYt.streamKey,
                        {
                            updatePlatformState(StreamingPlatform.YOUTUBE) {
                                it.copy(outputPhase = SessionPhase.LIVE, errorReason = null)
                            }
                            logEvent("YouTube Live RTMP ingest connected")
                            if (finalYt.broadcastId.isNotBlank()) {
                                scope.launch(Dispatchers.IO) {
                                    delay(3500L)
                                    youtubeService.transitionBroadcast(
                                        finalYt.accessToken,
                                        finalYt.broadcastId,
                                        "live"
                                    )
                                }
                            }
                        },
                        { err ->
                            val safe = KeystoreSecretStore.redactUrlAndKeys(err, finalYt.streamKey)
                            updatePlatformState(StreamingPlatform.YOUTUBE) {
                                it.copy(outputPhase = SessionPhase.ERROR, errorReason = safe)
                            }
                            logEvent("YouTube Live stream error: $safe")
                        }
                    )
                }
            }
        }

        val twState = uiState.value.twitchConnection
        if (twState.connected && twState.enabledForMultiStream) {
            var bundle = secretStore.loadPlatformBundle(StreamingPlatform.TWITCH)
            if (bundle != null) {
                bundle = ensureFreshBundle(StreamingPlatform.TWITCH, bundle)
                val finalTw = bundle
                if (finalTw.ingestUrl.isNotBlank() && finalTw.streamKey.isNotBlank()) {
                    startedCount++
                    updatePlatformState(StreamingPlatform.TWITCH) {
                        it.copy(outputPhase = SessionPhase.CONNECTING, errorReason = null)
                    }
                    launchSlot(
                        "twitch",
                        finalTw.ingestUrl,
                        finalTw.streamKey,
                        {
                            updatePlatformState(StreamingPlatform.TWITCH) {
                                it.copy(outputPhase = SessionPhase.LIVE, errorReason = null)
                            }
                            logEvent("Twitch RTMP ingest connected (#${finalTw.channelName})")
                        },
                        { err ->
                            val safe = KeystoreSecretStore.redactUrlAndKeys(err, finalTw.streamKey)
                            updatePlatformState(StreamingPlatform.TWITCH) {
                                it.copy(outputPhase = SessionPhase.ERROR, errorReason = safe)
                            }
                            logEvent("Twitch stream error: $safe")
                        }
                    )
                }
            }
        }
        return startedCount
    }

    fun onStreamingStopped() {
        updatePlatformState(StreamingPlatform.YOUTUBE) {
            it.copy(outputPhase = SessionPhase.IDLE, bufferedBytes = 0L)
        }
        updatePlatformState(StreamingPlatform.TWITCH) {
            it.copy(outputPhase = SessionPhase.IDLE, bufferedBytes = 0L)
        }
    }

    fun updatePlatformTelemetry(
        ytBuffered: Long,
        ytDropped: Long,
        twBuffered: Long,
        twDropped: Long
    ) {
        val cur = uiState.value
        if (cur.youtubeConnection.connected || cur.twitchConnection.connected) {
            uiState.update { state ->
                state.copy(
                    youtubeConnection = state.youtubeConnection.copy(
                        bufferedBytes = ytBuffered,
                        droppedFrames = ytDropped
                    ),
                    twitchConnection = state.twitchConnection.copy(
                        bufferedBytes = twBuffered,
                        droppedFrames = twDropped
                    )
                )
            }
        }
    }

    fun sendLiveChatMessage(text: String, targetPlatform: StreamingPlatform? = null) {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val st = uiState.value
            val sendTw = (targetPlatform == null || targetPlatform == StreamingPlatform.TWITCH) &&
                st.twitchConnection.connected
            val sendYt = (targetPlatform == null || targetPlatform == StreamingPlatform.YOUTUBE) &&
                st.youtubeConnection.connected && st.youtubeConnection.liveChatId.isNotBlank()

            if (sendTw) {
                val ok = twitchIrc.sendChatMessage(cleaned)
                if (ok) {
                    appendChatMessage(
                        PlatformChatMessage(
                            id = "self_tw_${System.nanoTime()}",
                            platform = StreamingPlatform.TWITCH,
                            author = st.twitchConnection.channelName.ifBlank { "You" },
                            badgeLabel = "HOST",
                            text = cleaned,
                            colorArgb = 0xFFA970FFL
                        )
                    )
                }
            }
            if (sendYt) {
                val bundle = secretStore.loadPlatformBundle(StreamingPlatform.YOUTUBE)
                if (bundle != null) {
                    val fresh = ensureFreshBundle(StreamingPlatform.YOUTUBE, bundle)
                    youtubeService.sendLiveChatMessage(
                        fresh.accessToken,
                        st.youtubeConnection.liveChatId,
                        cleaned
                    )
                }
            }
        }
    }

    fun stopChatClients() {
        youtubeChatJob?.cancel()
        youtubeChatJob = null
        twitchIrc.disconnect()
    }

    private fun startTwitchChat(oauthToken: String, login: String) {
        twitchIrc.connect(
            oauthToken = oauthToken,
            nickLogin = login,
            onMessage = { msg -> appendChatMessage(msg) },
            onStatus = { status -> logEvent(status) }
        )
    }

    private fun startYouTubeChatPolling(liveChatId: String) {
        youtubeChatJob?.cancel()
        youtubeChatJob = scope.launch(Dispatchers.IO) {
            var pageToken: String? = null
            while (isActive) {
                val bundle = secretStore.loadPlatformBundle(StreamingPlatform.YOUTUBE) ?: break
                val fresh = ensureFreshBundle(StreamingPlatform.YOUTUBE, bundle)
                val pollRes = youtubeService.pollLiveChat(fresh.accessToken, liveChatId, pageToken)
                var sleepMs = 4500L
                pollRes.onSuccess { res ->
                    pageToken = res.nextPageToken ?: pageToken
                    sleepMs = res.pollingIntervalMs
                    res.messages.forEach { appendChatMessage(it) }
                }.onFailure {
                    sleepMs = 8000L
                }
                delay(sleepMs)
            }
        }
    }

    private fun appendChatMessage(msg: PlatformChatMessage) {
        synchronized(seenChatIds) {
            if (!seenChatIds.add(msg.id)) return
            if (seenChatIds.size > 250) {
                val it = seenChatIds.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }
        uiState.update { cur ->
            val updated = (cur.chatMessages + msg).takeLast(150)
            cur.copy(chatMessages = updated)
        }
        onChatUpdated()
    }

    private suspend fun ensureFreshBundle(
        platform: StreamingPlatform,
        bundle: KeystoreSecretStore.PlatformCredentialBundle
    ): KeystoreSecretStore.PlatformCredentialBundle {
        val now = System.currentTimeMillis()
        if (bundle.expiresAtMs > 0L && bundle.expiresAtMs - now > 120_000L) {
            return bundle
        }
        if (bundle.refreshToken.isBlank()) return bundle
        val clientId = secretStore.resolveClientId(platform)
        val refreshed = oauthManager.refreshAccessToken(platform, clientId, bundle.refreshToken).getOrNull()
            ?: return bundle
        val updated = bundle.copy(
            accessToken = refreshed.accessToken,
            refreshToken = refreshed.refreshToken,
            expiresAtMs = refreshed.expiresAtMs
        )
        secretStore.savePlatformBundle(platform, updated)
        return updated
    }

    private fun updatePlatformState(
        platform: StreamingPlatform,
        fn: (PlatformConnectionState) -> PlatformConnectionState
    ) {
        uiState.update { cur ->
            when (platform) {
                StreamingPlatform.YOUTUBE -> cur.copy(youtubeConnection = fn(cur.youtubeConnection))
                StreamingPlatform.TWITCH -> cur.copy(twitchConnection = fn(cur.twitchConnection))
            }
        }
    }

    private fun KeystoreSecretStore.PlatformCredentialBundle.toConnectionState(
        platform: StreamingPlatform
    ): PlatformConnectionState = PlatformConnectionState(
        platform = platform,
        connected = accessToken.isNotBlank(),
        authInProgress = false,
        channelName = channelName,
        broadcasterId = broadcasterId,
        ingestUrl = ingestUrl,
        maskedStreamKey = if (streamKey.isBlank()) "Not fetched" else secretStore.maskSecret(streamKey),
        hasStreamKey = streamKey.isNotBlank(),
        broadcastId = broadcastId,
        liveChatId = liveChatId,
        streamTitle = streamTitle,
        categoryName = categoryName,
        categoryId = categoryId,
        privacyStatus = privacyStatus,
        enabledForMultiStream = enabledForMultiStream
    )
}
