package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mobilelabs.MobileLabsApp
import com.example.mobilelabs.model.SessionPhase
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.service.CaptureForegroundService
import com.example.mobilelabs.ui.WorkspaceCallbacks
import com.example.mobilelabs.ui.WorkspaceScreen
import com.example.ui.theme.ApertureRed
import com.example.ui.theme.MobileLabsTheme
import com.example.ui.theme.StudioText
import com.example.ui.theme.StudioTextDim
import com.example.ui.theme.SurfaceLevel1

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as MobileLabsApp
        val controller = app.sessionController

        setContent {
            MobileLabsTheme {
                val state by controller.uiState.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current

                var pendingMediaKind by remember { mutableStateOf<SourceType?>(null) }
                var pendingRelinkSourceId by remember { mutableStateOf<String?>(null) }
                var importMissingAssetsAlert by remember { mutableStateOf<List<String>?>(null) }

                // Iron Rule R9: stop preview rendering & UI timers immediately on ON_STOP
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> {
                                controller.refreshPermissionStates()
                                controller.setUiVisible(true)
                            }
                            Lifecycle.Event.ON_STOP -> {
                                controller.setUiVisible(false)
                            }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val projectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                        val activeScene = state.sceneCollection.scenes.firstOrNull {
                            it.id == state.programSceneId
                        } ?: state.sceneCollection.scenes.firstOrNull {
                            it.id == state.sceneCollection.activeSceneId
                        }
                        val usesCam = activeScene?.sources?.any {
                            it.type == SourceType.CAMERA_2 && it.visible
                        } == true
                        val svcIntent = Intent(this, CaptureForegroundService::class.java).apply {
                            action = CaptureForegroundService.ACTION_ARM_PROJECTION
                            putExtra(CaptureForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                            putExtra(CaptureForegroundService.EXTRA_RESULT_DATA, result.data)
                            putExtra(CaptureForegroundService.EXTRA_USE_MIC, state.hasMicPermission)
                            putExtra(CaptureForegroundService.EXTRA_USE_CAM, usesCam && state.hasCameraPermission)
                        }
                        ContextCompat.startForegroundService(this, svcIntent)
                    } else {
                        controller.onArmFailed("Screen capture consent was canceled")
                    }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    controller.refreshPermissionStates()
                    launchFullScreenProjectionConsent(projectionLauncher)
                }

                val overlaySettingsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    controller.refreshPermissionStates()
                    if (Settings.canDrawOverlays(this)) {
                        controller.setFloatingOverlayEnabled(true)
                    }
                }

                // Zero-permission Photo Picker for Images & Video Media Sources (Google Play Policy)
                val photoPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    val kind = pendingMediaKind ?: SourceType.IMAGE_SOURCE
                    val relinkId = pendingRelinkSourceId
                    pendingMediaKind = null
                    pendingRelinkSourceId = null
                    if (uri != null) {
                        if (relinkId != null) {
                            controller.relinkSourceFromUri(relinkId, kind, uri)
                        } else {
                            controller.importMediaSourceFromUri(kind, uri)
                        }
                    }
                }

                val exportObsJsonLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/json")
                ) { uri ->
                    if (uri != null) {
                        controller.exportObsJsonToUri(uri) {}
                    }
                }

                val importObsJsonLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        controller.importObsJsonFromUri(uri) { ok, issues ->
                            if (!ok || issues.isNotEmpty()) {
                                importMissingAssetsAlert = issues
                            }
                        }
                    }
                }

                val recordingFolderLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    if (uri != null) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        }
                        controller.setCustomRecordingFolderUri(uri.toString())
                    }
                }

                fun requestPermissionsAndArm() {
                    controller.beginArming()
                    val needed = mutableListOf<String>()
                    if (!state.hasMicPermission) needed += Manifest.permission.RECORD_AUDIO
                    if (!state.hasCameraPermission) {
                        val activeScene = state.sceneCollection.scenes.firstOrNull {
                            it.id == state.sceneCollection.activeSceneId
                        }
                        if (activeScene?.sources?.any { it.type == SourceType.CAMERA_2 } == true) {
                            needed += Manifest.permission.CAMERA
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !state.hasNotificationPermission) {
                        needed += Manifest.permission.POST_NOTIFICATIONS
                    }
                    if (needed.isNotEmpty()) {
                        permissionLauncher.launch(needed.toTypedArray())
                    } else {
                        launchFullScreenProjectionConsent(projectionLauncher)
                    }
                }

                WorkspaceScreen(
                    state = state,
                    callbacks = WorkspaceCallbacks(
                        onAttachPreviewSurface = controller::attachPreviewSurface,
                        onArmOrDisarm = {
                            if (state.sessionPhase == SessionPhase.IDLE || state.sessionPhase == SessionPhase.ERROR) {
                                requestPermissionsAndArm()
                            } else {
                                startService(
                                    Intent(this, CaptureForegroundService::class.java)
                                        .setAction(CaptureForegroundService.ACTION_DISARM)
                                )
                            }
                        },
                        onToggleStream = controller::toggleStreamFromClient,
                        onToggleRecord = controller::toggleRecordFromClient,
                        onSelectScene = controller::selectScene,
                        onCreateScene = controller::createScene,
                        onRenameScene = controller::renameScene,
                        onDuplicateScene = controller::duplicateScene,
                        onDeleteScene = controller::deleteScene,
                        onMoveScene = controller::moveScene,
                        onSelectSource = controller::selectSource,
                        onAddSource = { controller.addSourceToActiveScene(it) },
                        onPickImageForNewSource = {
                            pendingMediaKind = SourceType.IMAGE_SOURCE
                            pendingRelinkSourceId = null
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onPickMediaForNewSource = {
                            pendingMediaKind = SourceType.MEDIA_SOURCE
                            pendingRelinkSourceId = null
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        onRelinkImageSource = { srcId ->
                            pendingMediaKind = SourceType.IMAGE_SOURCE
                            pendingRelinkSourceId = srcId
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRelinkMediaSource = { srcId ->
                            pendingMediaKind = SourceType.MEDIA_SOURCE
                            pendingRelinkSourceId = srcId
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        onUpdateSourceProperties = controller::updateSourceProperties,
                        onUpdateSourceVideoFilters = controller::updateSourceVideoFilters,
                        onRemoveSource = controller::removeSource,
                        onDuplicateSource = controller::duplicateSource,
                        onMoveSourceOrder = controller::moveSourceOrder,
                        onToggleSourceVisible = controller::toggleSourceVisibility,
                        onToggleSourceLock = controller::toggleSourceLock,
                        onSwitchCamera = controller::switchCameraLens,
                        onDragSourceTransform = controller::dragSourceTransform,
                        onUpdateSourceCrop = controller::updateSourceCrop,
                        onNudgeSource = controller::nudgeSelectedSourceTransform,
                        onFitSelectedToScreen = controller::fitSelectedSourceToScreen,
                        onAlignSelectedCenter = controller::alignMultiSelectedCenter,
                        onUnlockLiveTouch = { controller.setUnlockLiveTouchOverride(true) },
                        onSetActiveTool = controller::setActiveTool,
                        onUndo = controller::undoSceneEdit,
                        onRedo = controller::redoSceneEdit,
                        onAudioVolume = controller::setAudioVolume,
                        onAudioMute = controller::toggleAudioMute,
                        onAudioLock = controller::toggleAudioLock,
                        onUpdateAudioFilters = controller::updateAudioFilters,
                        onAudioSyncOffset = controller::setAudioSyncOffset,
                        onMasterMute = controller::toggleMasterMute,
                        onTransitionChange = controller::setTransition,
                        onToggleStudioMode = controller::toggleStudioMode,
                        onStudioTake = controller::triggerStudioTake,
                        onToggleExpandDock = controller::setExpandedDock,
                        onApplyPreset = controller::applyPreset,
                        onUpdateVideoConfig = controller::updateVideoConfig,
                        onUpdateScreenFit = controller::updateScreenFitMode,
                        onSaveStreamCredentials = controller::saveStreamCredentials,
                        onSaveSecondaryCredentials = controller::saveSecondaryStreamCredentials,
                        onApplyGamingScenePreset = controller::applyGamingScenePreset,
                        onToggleAdaptiveLock = controller::setAdaptiveQualityLocked,
                        onResetAdaptiveQuality = controller::resetAdaptiveQuality,
                        onRefreshSamsungAdvisor = controller::refreshSamsungAdvisor,
                        onRunAutoTune = { controller.runAutoTuneProbe(applyRecommended = true) },
                        onTestRtmpReachability = controller::testRtmpReachability,
                        onCompleteOnboarding = controller::completeOnboarding,
                        onDismissCrashBanner = controller::dismissCrashBanner,
                        onAddBenchmarkRecord = controller::addBenchmarkRecord,
                        onDeleteBenchmarkRecord = controller::deleteBenchmarkRecord,
                        onPickRecordingFolder = { recordingFolderLauncher.launch(null) },
                        onResetRecordingFolder = { controller.setCustomRecordingFolderUri(null) },
                        onRecoverIncompleteRecording = controller::recoverIncompleteRecording,
                        onDeleteIncompleteRecording = controller::deleteIncompleteRecording,
                        onCreateProfile = controller::createProfile,
                        onSelectProfile = controller::selectProfile,
                        onDeleteProfile = controller::deleteProfile,
                        onCreateOrDupCollection = controller::createOrDuplicateCollection,
                        onSwitchCollection = controller::switchNamedCollection,
                        onDeleteCollection = controller::deleteNamedCollection,
                        onExportObsJson = { exportObsJsonLauncher.launch("mobile_labs_collection.obs.json") },
                        onImportObsJson = { importObsJsonLauncher.launch(arrayOf("application/json", "*/*")) },
                        onRequestEnableOverlay = { enable ->
                            if (enable && !Settings.canDrawOverlays(this)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    "package:$packageName".toUri()
                                )
                                overlaySettingsLauncher.launch(intent)
                            } else {
                                controller.setFloatingOverlayEnabled(enable)
                            }
                        },
                        onToggleHud = controller::toggleHudExpanded,
                        onRunProbe = controller::runFullDeviceAndCodecProbe,
                        onExportDiagnostics = controller::exportDiagnosticsZip,
                        onExitApp = {
                            startService(
                                Intent(this, CaptureForegroundService::class.java)
                                    .setAction(CaptureForegroundService.ACTION_DISARM)
                            )
                            finish()
                        }
                    )
                )

                importMissingAssetsAlert?.let { issues ->
                    AlertDialog(
                        onDismissRequest = { importMissingAssetsAlert = null },
                        containerColor = SurfaceLevel1,
                        title = { Text("OBS JSON Import Notice", color = StudioText) },
                        text = {
                            Text(
                                text = issues.joinToString("\n• ", prefix = "• "),
                                color = StudioTextDim
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = { importMissingAssetsAlert = null },
                                colors = ButtonDefaults.buttonColors(containerColor = ApertureRed)
                            ) { Text("OK", color = StudioText) }
                        }
                    )
                }
            }
        }
    }

    private fun launchFullScreenProjectionConsent(
        launcher: androidx.activity.result.ActivityResultLauncher<Intent>
    ) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        launcher.launch(intent)
    }
}
