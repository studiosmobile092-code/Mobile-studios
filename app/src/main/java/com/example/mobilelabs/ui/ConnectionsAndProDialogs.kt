package com.example.mobilelabs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig
import com.example.mobilelabs.model.MobileLabsUiState
import com.example.mobilelabs.model.PlatformConnectionState
import com.example.mobilelabs.model.SessionPhase
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.model.StreamingPlatform
import com.example.ui.theme.ApertureRed
import com.example.ui.theme.ApertureRedHi
import com.example.ui.theme.ObsBorder
import com.example.ui.theme.ObsHeaderBg
import com.example.ui.theme.ObsSelectionBlue
import com.example.ui.theme.ObsWellBg
import com.example.ui.theme.ObsWindowBg
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusWarn
import com.example.ui.theme.StudioDivider
import com.example.ui.theme.StudioText
import com.example.ui.theme.StudioTextDim
import com.example.ui.theme.SurfaceLevel1
import com.example.ui.theme.SurfaceLevel2
import java.util.Locale

private val YouTubeRed = Color(0xFFE62117)
private val TwitchPurple = Color(0xFF9146FF)

/**
 * 1-Click YouTube & Twitch OAuth PKCE Connect + Multi-Stream Manager + Client ID Config.
 */
@Composable
fun ConnectionsAndMultiStreamDialog(
    state: MobileLabsUiState,
    onDismiss: () -> Unit,
    onStartOAuth: (StreamingPlatform) -> Unit,
    onDisconnect: (StreamingPlatform) -> Unit,
    onToggleMultiStream: (StreamingPlatform, Boolean) -> Unit,
    onUpdateBroadcastMeta: (
        platform: StreamingPlatform,
        title: String,
        categoryId: String,
        categoryName: String,
        privacyStatus: String
    ) -> Unit,
    onSearchTwitchCategories: (String) -> Unit,
    onSaveCustomClientId: (StreamingPlatform, String) -> Unit,
    onOpenCustomRtmpSettings: () -> Unit
) {
    var showClientIdConfig by remember { mutableStateOf(false) }
    var ytClientIdInput by remember { mutableStateOf("") }
    var twClientIdInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = ObsWindowBg,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, ObsBorder),
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .fillMaxHeight(0.90f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "1-Click Stream Destinations & Multi-Stream",
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioText
                        )
                        Text(
                            "Sign in once via OAuth PKCE — RTMP ingest URLs & stream keys are fetched automatically.",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextDim
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onDismiss(); onOpenCustomRtmpSettings() },
                            modifier = Modifier.testTag("connections_custom_rtmp_btn")
                        ) {
                            Text("Custom / Dual RTMP", color = StudioText, fontSize = 12.sp)
                        }
                        TextButton(onClick = onDismiss) { Text("Close", color = StudioText) }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PlatformDestinationCard(
                            conn = state.youtubeConnection,
                            accent = YouTubeRed,
                            twitchCategories = emptyList(),
                            onConnect = { onStartOAuth(StreamingPlatform.YOUTUBE) },
                            onDisconnect = { onDisconnect(StreamingPlatform.YOUTUBE) },
                            onToggleMulti = { onToggleMultiStream(StreamingPlatform.YOUTUBE, it) },
                            onSearchCategories = {},
                            onSaveMeta = { title, catId, catName, privacy ->
                                onUpdateBroadcastMeta(StreamingPlatform.YOUTUBE, title, catId, catName, privacy)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        PlatformDestinationCard(
                            conn = state.twitchConnection,
                            accent = TwitchPurple,
                            twitchCategories = state.twitchCategories,
                            onConnect = { onStartOAuth(StreamingPlatform.TWITCH) },
                            onDisconnect = { onDisconnect(StreamingPlatform.TWITCH) },
                            onToggleMulti = { onToggleMultiStream(StreamingPlatform.TWITCH, it) },
                            onSearchCategories = onSearchTwitchCategories,
                            onSaveMeta = { title, catId, catName, privacy ->
                                onUpdateBroadcastMeta(StreamingPlatform.TWITCH, title, catId, catName, privacy)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Surface(
                        color = ObsWellBg,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, ObsBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showClientIdConfig = !showClientIdConfig },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.Key, contentDescription = null, tint = StudioTextDim, modifier = Modifier.size(16.dp))
                                    Text(
                                        "OAuth Client ID Configuration (AI Studio Secrets / In-App)",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = StudioText
                                    )
                                }
                                Text(
                                    if (showClientIdConfig) "Hide" else "Configure",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ObsSelectionBlue
                                )
                            }

                            val ytSecretActive = com.example.mobilelabs.security.KeystoreSecretStore
                                .sanitizeBuildConfigSecret(BuildConfig.YOUTUBE_OAUTH_CLIENT_ID).isNotBlank()
                            val twSecretActive = com.example.mobilelabs.security.KeystoreSecretStore
                                .sanitizeBuildConfigSecret(BuildConfig.TWITCH_OAUTH_CLIENT_ID).isNotBlank()
                            Text(
                                text = "YouTube Client ID: ${
                                    when {
                                        state.customYoutubeClientIdSaved -> "Custom Keystore Override Active"
                                        ytSecretActive -> "Configured via AI Studio Secrets"
                                        else -> "Not configured (add YOUTUBE_OAUTH_CLIENT_ID in AI Studio Secrets or below)"
                                    }
                                } · Twitch Client ID: ${
                                    when {
                                        state.customTwitchClientIdSaved -> "Custom Keystore Override Active"
                                        twSecretActive -> "Configured via AI Studio Secrets"
                                        else -> "Not configured (add TWITCH_OAUTH_CLIENT_ID in AI Studio Secrets or below)"
                                    }
                                }",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextDim
                            )

                            if (showClientIdConfig) {
                                HorizontalDivider(color = ObsBorder)
                                Text(
                                    "Redirect URIs for OAuth App Setup — YouTube: com.mobilelabs.studio:/oauth2redirect · Twitch: mobilelabs://oauth2redirect",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusWarn
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = ytClientIdInput,
                                        onValueChange = { ytClientIdInput = it },
                                        label = { Text("YouTube OAuth Client ID (Android / Installed)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("yt_client_id_input")
                                    )
                                    Button(
                                        onClick = { onSaveCustomClientId(StreamingPlatform.YOUTUBE, ytClientIdInput) },
                                        colors = ButtonDefaults.buttonColors(containerColor = YouTubeRed)
                                    ) { Text("Save", color = Color.White) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = twClientIdInput,
                                        onValueChange = { twClientIdInput = it },
                                        label = { Text("Twitch OAuth Client ID (Mobile / Public)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("tw_client_id_input")
                                    )
                                    Button(
                                        onClick = { onSaveCustomClientId(StreamingPlatform.TWITCH, twClientIdInput) },
                                        colors = ButtonDefaults.buttonColors(containerColor = TwitchPurple)
                                    ) { Text("Save", color = Color.White) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformDestinationCard(
    conn: PlatformConnectionState,
    accent: Color,
    twitchCategories: List<com.example.mobilelabs.model.TwitchCategoryItem>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleMulti: (Boolean) -> Unit,
    onSearchCategories: (String) -> Unit,
    onSaveMeta: (title: String, categoryId: String, categoryName: String, privacyStatus: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var titleInput by remember(conn.platform, conn.streamTitle) { mutableStateOf(conn.streamTitle) }
    var privacyInput by remember(conn.platform, conn.privacyStatus) { mutableStateOf(conn.privacyStatus) }
    var catQuery by remember(conn.platform, conn.categoryName) { mutableStateOf(conn.categoryName) }
    var selectedCatId by remember(conn.platform, conn.categoryId) { mutableStateOf(conn.categoryId) }
    var selectedCatName by remember(conn.platform, conn.categoryName) { mutableStateOf(conn.categoryName) }

    Surface(
        color = SurfaceLevel1,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (conn.connected) accent.copy(alpha = 0.8f) else ObsBorder),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(10.dp).background(accent, CircleShape))
                    Text(conn.platform.label, style = MaterialTheme.typography.titleSmall, color = StudioText, fontWeight = FontWeight.Bold)
                }
                val badgeColor = when (conn.outputPhase) {
                    SessionPhase.LIVE -> StatusOk
                    SessionPhase.CONNECTING -> StatusWarn
                    SessionPhase.ERROR -> ApertureRedHi
                    else -> if (conn.connected) StatusOk else StudioTextDim
                }
                Text(
                    text = when {
                        conn.outputPhase != SessionPhase.IDLE -> conn.outputPhase.displayLabel
                        conn.authInProgress -> "SIGNING IN..."
                        conn.connected -> "READY"
                        else -> "DISCONNECTED"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (!conn.connected) {
                Text(
                    text = if (conn.platform == StreamingPlatform.YOUTUBE) {
                        "Tap once to sign in with Google, create a Live Broadcast, and auto-fetch your RTMPS stream key."
                    } else {
                        "Tap once to sign in with Twitch, auto-fetch your ingest stream key, and join Twitch live chat."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextDim
                )
                Button(
                    onClick = onConnect,
                    enabled = !conn.authInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("connect_${conn.platform.name.lowercase()}_btn")
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (conn.authInProgress) "Waiting for browser..." else "1-Click Connect ${conn.platform.label}",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().background(ObsWellBg, RoundedCornerShape(4.dp)).padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusOk, modifier = Modifier.size(14.dp))
                            Text(
                                conn.channelName.ifBlank { conn.platform.label },
                                style = MaterialTheme.typography.labelLarge,
                                color = StudioText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            "Auto Key: ${conn.maskedStreamKey} · ${conn.ingestUrl.substringBeforeLast('/')}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = onDisconnect) {
                        Icon(Icons.Default.LinkOff, contentDescription = "Disconnect", tint = ApertureRedHi, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Disconnect", color = ApertureRedHi, fontSize = 11.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Include in Go Live (Multi-Stream)", style = MaterialTheme.typography.bodySmall, color = StudioText)
                    Switch(
                        checked = conn.enabledForMultiStream,
                        onCheckedChange = onToggleMulti,
                        modifier = Modifier.testTag("multistream_switch_${conn.platform.name.lowercase()}")
                    )
                }

                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    label = { Text("Stream Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (conn.platform == StreamingPlatform.YOUTUBE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Privacy:", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        listOf("public", "unlisted", "private").forEach { mode ->
                            val active = privacyInput == mode
                            OutlinedButton(
                                onClick = { privacyInput = mode },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                border = BorderStroke(1.dp, if (active) accent else ObsBorder),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(mode.replaceFirstChar { it.uppercase() }, fontSize = 11.sp, color = StudioText)
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = catQuery,
                        onValueChange = {
                            catQuery = it
                            onSearchCategories(it)
                        },
                        label = { Text("Search Twitch Game / Category") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (twitchCategories.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            twitchCategories.take(6).forEach { cat ->
                                val selected = cat.id == selectedCatId
                                OutlinedButton(
                                    onClick = {
                                        selectedCatId = cat.id
                                        selectedCatName = cat.name
                                        catQuery = cat.name
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    border = BorderStroke(1.dp, if (selected) accent else ObsBorder),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text(cat.name, fontSize = 11.sp, color = StudioText)
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = { onSaveMeta(titleInput, selectedCatId, selectedCatName, privacyInput) },
                    colors = ButtonDefaults.buttonColors(containerColor = ObsSelectionBlue),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Update Stream Info", color = StudioText, fontSize = 12.sp)
                }
            }

            if (!conn.errorReason.isNullOrBlank()) {
                Text(conn.errorReason, style = MaterialTheme.typography.labelSmall, color = ApertureRedHi)
            }
        }
    }
}

/**
 * Interactive Unified YouTube + Twitch Live Chat Drawer.
 */
@Composable
fun LiveChatOverlayDrawer(
    state: MobileLabsUiState,
    onClose: () -> Unit,
    onSendChat: (String, StreamingPlatform?) -> Unit,
    onClearChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    var filterPlatform by remember { mutableStateOf<StreamingPlatform?>(null) }
    var targetPlatform by remember { mutableStateOf<StreamingPlatform?>(null) }
    var draft by remember { mutableStateOf("") }

    val filtered = remember(state.chatMessages, filterPlatform) {
        if (filterPlatform == null) state.chatMessages else state.chatMessages.filter { it.platform == filterPlatform }
    }

    Surface(
        color = Color(0xEB18181E),
        border = BorderStroke(1.dp, ObsBorder),
        shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp),
        modifier = modifier
            .width(340.dp)
            .fillMaxHeight()
            .testTag("live_chat_drawer")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live Chat (${filtered.size})", style = MaterialTheme.typography.titleSmall, color = StudioText, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClearChat) { Text("Clear", fontSize = 11.sp, color = StudioTextDim) }
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close Live Chat", tint = StudioText)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null to "All", StreamingPlatform.YOUTUBE to "YouTube", StreamingPlatform.TWITCH to "Twitch").forEach { (plat, label) ->
                    val active = filterPlatform == plat
                    OutlinedButton(
                        onClick = { filterPlatform = plat },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        border = BorderStroke(1.dp, if (active) ObsSelectionBlue else ObsBorder),
                        modifier = Modifier.height(28.dp)
                    ) { Text(label, fontSize = 11.sp, color = StudioText) }
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = ObsBorder)

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                reverseLayout = true
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "No chat messages yet. Connect YouTube or Twitch to stream live chat directly into your HUD and Chat Overlay source.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextDim,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                } else {
                    items(filtered.asReversed(), key = { it.id }) { msg ->
                        Row(
                            modifier = Modifier.fillMaxWidth().background(ObsWellBg, RoundedCornerShape(4.dp)).padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            val platColor = if (msg.platform == StreamingPlatform.YOUTUBE) YouTubeRed else TwitchPurple
                            Text(
                                text = if (msg.platform == StreamingPlatform.YOUTUBE) "YT" else "TW",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier
                                    .background(platColor, RoundedCornerShape(3.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = msg.author,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(msg.colorArgb.toInt())
                                    )
                                    if (msg.badgeLabel.isNotBlank()) {
                                        Text(
                                            text = msg.badgeLabel,
                                            fontSize = 9.sp,
                                            color = StudioTextDim,
                                            modifier = Modifier.border(0.5.dp, ObsBorder, RoundedCornerShape(2.dp)).padding(horizontal = 3.dp)
                                        )
                                    }
                                }
                                Text(msg.text, fontSize = 12.sp, color = StudioText)
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(null to "Both", StreamingPlatform.YOUTUBE to "YT", StreamingPlatform.TWITCH to "TW").forEach { (plat, lbl) ->
                    val active = targetPlatform == plat
                    Text(
                        text = lbl,
                        fontSize = 10.sp,
                        color = StudioText,
                        modifier = Modifier
                            .background(if (active) ObsSelectionBlue else ObsHeaderBg, RoundedCornerShape(3.dp))
                            .clickable { targetPlatform = plat }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Send message to chat...", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("chat_reply_input")
                )
                IconButton(
                    onClick = {
                        if (draft.isNotBlank()) {
                            onSendChat(draft.trim(), targetPlatform)
                            draft = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(ApertureRed, RoundedCornerShape(6.dp))
                        .testTag("chat_send_btn")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send chat reply", tint = Color.White)
                }
            }
        }
    }
}

/**
 * Unified Pro Creator Tools Dialog:
 * Instant Replay Buffer, Canvas Screenshot, Soundboard SFX Pads, Push-to-Talk,
 * Screen Drawing / Ink Controls, and Quick Timer / Scoreboard triggers.
 */
@Composable
fun ProToolsDialog(
    state: MobileLabsUiState,
    onDismiss: () -> Unit,
    onToggleReplayBuffer: (Boolean, Int) -> Unit,
    onSaveReplayClip: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onTriggerSoundboardPad: (String) -> Unit,
    onSetSoundboardPadVolume: (String, Float) -> Unit,
    onPickSoundboardAudio: (String) -> Unit,
    onSetPushToTalkHeld: (Boolean) -> Unit,
    onSetDrawingMode: (Boolean) -> Unit,
    onSetDrawingBrush: (Long, Float) -> Unit,
    onUndoDrawingStroke: () -> Unit,
    onClearDrawingStrokes: () -> Unit,
    onControlTimer: (String, String) -> Unit,
    onAdjustScoreboard: (String, Boolean, Int) -> Unit,
    onResetScoreboard: (String) -> Unit
) {
    val editableSceneId = if (state.studioModeEnabled) state.previewSceneId else state.sceneCollection.activeSceneId
    val editableScene = state.sceneCollection.scenes.firstOrNull { it.id == editableSceneId }
    val timerSources = editableScene?.sources?.filter { it.type == SourceType.TIMER_SOURCE }.orEmpty()
    val scoreSources = editableScene?.sources?.filter { it.type == SourceType.SCOREBOARD_SOURCE }.orEmpty()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = ObsWindowBg,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, ObsBorder),
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mobile Labs Pro Creator Tools", style = MaterialTheme.typography.titleMedium, color = StudioText)
                    TextButton(onClick = onDismiss) { Text("Close", color = StudioText) }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Instant Replay Buffer + Screenshot
                    Surface(
                        color = SurfaceLevel1,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, ObsBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Replay, contentDescription = null, tint = ApertureRedHi)
                                    Column {
                                        Text("OBS Instant Replay Buffer", style = MaterialTheme.typography.titleSmall, color = StudioText)
                                        Text(
                                            text = "Ring: ${state.replayBuffer.durationSec}s · Buffered: ${
                                                String.format(Locale.US, "%.1fs", state.replayBuffer.bufferedSeconds)
                                            } (${state.replayBuffer.bufferedKb} KB) · Saved clips: ${state.replayBuffer.clipsSavedCount}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StudioTextDim
                                        )
                                    }
                                }
                                Switch(
                                    checked = state.replayBuffer.enabled,
                                    onCheckedChange = { onToggleReplayBuffer(it, state.replayBuffer.durationSec) },
                                    modifier = Modifier.testTag("replay_buffer_switch")
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Length:", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                                listOf(15, 30, 60, 90).forEach { sec ->
                                    val active = state.replayBuffer.durationSec == sec
                                    OutlinedButton(
                                        onClick = { onToggleReplayBuffer(state.replayBuffer.enabled, sec) },
                                        border = BorderStroke(1.dp, if (active) ApertureRedHi else ObsBorder),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) { Text("${sec}s", fontSize = 12.sp, color = StudioText) }
                                }
                                Spacer(Modifier.weight(1f))
                                Button(
                                    onClick = onSaveReplayClip,
                                    enabled = state.replayBuffer.enabled && state.sessionPhase != SessionPhase.IDLE,
                                    colors = ButtonDefaults.buttonColors(containerColor = ApertureRed),
                                    modifier = Modifier.height(40.dp).testTag("save_replay_clip_btn")
                                ) {
                                    Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Save Replay", color = Color.White)
                                }
                                OutlinedButton(
                                    onClick = onCaptureScreenshot,
                                    enabled = state.sessionPhase != SessionPhase.IDLE,
                                    modifier = Modifier.height(40.dp).testTag("capture_screenshot_btn")
                                ) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = StudioText, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Snap PNG", color = StudioText)
                                }
                            }
                        }
                    }

                    // 2. Soundboard / SFX Pads + Push-to-Talk
                    Surface(
                        color = SurfaceLevel1,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, ObsBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = StatusOk)
                                    Text("Soundboard / SFX Pads & Push-to-Talk", style = MaterialTheme.typography.titleSmall, color = StudioText)
                                }
                                Box(
                                    modifier = Modifier
                                        .height(48.dp)
                                        .background(if (state.pushToTalkHeld) StatusOk else ObsSelectionBlue, RoundedCornerShape(6.dp))
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onPress = {
                                                    onSetPushToTalkHeld(true)
                                                    tryAwaitRelease()
                                                    onSetPushToTalkHeld(false)
                                                }
                                            )
                                        }
                                        .padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Mic, contentDescription = "Hold for Push-to-Talk", tint = Color.White, modifier = Modifier.size(16.dp))
                                        Text(
                                            if (state.pushToTalkHeld) "PTT LIVE (HOLDING)" else "Hold Push-to-Talk",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.soundboardPads.forEach { pad ->
                                    Surface(
                                        color = SurfaceLevel2,
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, StudioDivider),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Button(
                                                onClick = { onTriggerSoundboardPad(pad.id) },
                                                colors = ButtonDefaults.buttonColors(containerColor = ApertureRed),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(48.dp)
                                                    .testTag("sfx_pad_${pad.id}")
                                            ) {
                                                Text(pad.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Text(
                                                text = pad.filePath?.substringAfterLast('/') ?: "Built-in: ${pad.builtInTone ?: "SFX"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = StudioTextDim,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Slider(
                                                value = pad.volume,
                                                onValueChange = { onSetSoundboardPadVolume(pad.id, it) },
                                                valueRange = 0f..1.5f
                                            )
                                            OutlinedButton(
                                                onClick = { onPickSoundboardAudio(pad.id) },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                modifier = Modifier.fillMaxWidth().height(32.dp)
                                            ) { Text("Assign Audio", fontSize = 10.sp, color = StudioText) }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Screen Drawing / Ink Annotations
                    Surface(
                        color = SurfaceLevel1,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, ObsBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Brush, contentDescription = null, tint = StatusWarn)
                                    Text("Screen Drawing / Ink Layer (${state.drawingStrokes.size} strokes)", style = MaterialTheme.typography.titleSmall, color = StudioText)
                                }
                                Switch(
                                    checked = state.drawingModeActive,
                                    onCheckedChange = {
                                        onSetDrawingMode(it)
                                        if (it) onDismiss()
                                    },
                                    modifier = Modifier.testTag("drawing_mode_switch")
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0xFFFF3B30L, 0xFFF5A524L, 0xFF43B581L, 0xFF56B4E9L, 0xFFFFFFFFL).forEach { col ->
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(Color(col.toInt()), CircleShape)
                                            .border(
                                                if (state.drawingColorArgb == col) 2.5.dp else 1.dp,
                                                if (state.drawingColorArgb == col) Color.White else StudioDivider,
                                                CircleShape
                                            )
                                            .clickable { onSetDrawingBrush(col, state.drawingBrushWidthFraction) }
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Slider(
                                    value = state.drawingBrushWidthFraction,
                                    onValueChange = { onSetDrawingBrush(state.drawingColorArgb, it) },
                                    valueRange = 0.003f..0.025f,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(onClick = onUndoDrawingStroke) { Text("Undo", color = StudioText) }
                                OutlinedButton(onClick = onClearDrawingStrokes) { Text("Clear", color = ApertureRedHi) }
                            }
                        }
                    }

                    // 4. Live Timer & Scoreboard Controls
                    if (timerSources.isNotEmpty() || scoreSources.isNotEmpty()) {
                        Surface(
                            color = SurfaceLevel1,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, ObsBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Live Scene Widgets", style = MaterialTheme.typography.titleSmall, color = StudioText)
                                timerSources.forEach { tSrc ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Timer, contentDescription = null, tint = StudioTextDim, modifier = Modifier.size(16.dp))
                                        Text(tSrc.name, color = StudioText, modifier = Modifier.weight(1f))
                                        OutlinedButton(onClick = { onControlTimer(tSrc.id, if (tSrc.timerRunning) "pause" else "start") }) {
                                            Text(if (tSrc.timerRunning) "Pause" else "Start", color = StudioText)
                                        }
                                        OutlinedButton(onClick = { onControlTimer(tSrc.id, "reset") }) {
                                            Text("Reset", color = StudioTextDim)
                                        }
                                    }
                                }
                                scoreSources.forEach { sSrc ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.SportsScore, contentDescription = null, tint = StudioTextDim, modifier = Modifier.size(16.dp))
                                        Text("${sSrc.scoreHomeName} ${sSrc.scoreHome} : ${sSrc.scoreAway} ${sSrc.scoreAwayName}", color = StudioText, modifier = Modifier.weight(1f))
                                        OutlinedButton(onClick = { onAdjustScoreboard(sSrc.id, true, 1) }) { Text("${sSrc.scoreHomeName} +1", color = StudioText) }
                                        OutlinedButton(onClick = { onAdjustScoreboard(sSrc.id, false, 1) }) { Text("${sSrc.scoreAwayName} +1", color = StudioText) }
                                        TextButton(onClick = { onResetScoreboard(sSrc.id) }) { Text("Reset", color = StudioTextDim) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
