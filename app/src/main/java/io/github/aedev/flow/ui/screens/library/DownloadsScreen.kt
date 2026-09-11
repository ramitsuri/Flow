package io.github.aedev.flow.ui.screens.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.DownloadedTrack
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.library.MusicDownloadsList
import io.github.aedev.flow.ui.components.library.VideosDownloadsList
import io.github.aedev.flow.ui.components.shared.MediaKind
import io.github.aedev.flow.ui.components.shared.MediaKindSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBackClick: () -> Unit,
    onVideoClick: (videos: List<DownloadedVideo>, startIndex: Int) -> Unit,
    onMusicClick: (List<DownloadedTrack>, Int) -> Unit,
    onHomeClick: () -> Unit,
    modifier: Modifier = Modifier,
    isRoot: Boolean = false,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedKind by remember { mutableStateOf(MediaKind.Videos) }
    var showRemoveIncompleteDialog by remember { mutableStateOf(false) }
    var showSyncSummaryDialog by remember { mutableStateOf(false) }
    var pendingDeletion by remember { mutableStateOf<PendingDeletion?>(null) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val resources = LocalResources.current

    val externalAppSyncLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            viewModel.syncExternalDownloads()
        }

    fun launchExternalAppSync() {
        val intent = context.packageManager.getLaunchIntentForPackage(EXTERNAL_APP_SYNC_PACKAGE)
        if (intent != null) {
            intent.putExtra(EXTERNAL_APP_SYNC_INTENT_EXTRA_KEY, true)
            externalAppSyncLauncher.launch(intent)
        } else {
            Toast.makeText(context, resources.getString(R.string.sync_external_app_not_installed), Toast.LENGTH_SHORT).show()
        }
    }

    val externalPermission = "com.ramitsuri.videomonitor.READ_MONITORED_DATA"
    val externalPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                launchExternalAppSync()
            } else {
                Toast.makeText(context, resources.getString(R.string.sync_permission_denied), Toast.LENGTH_SHORT).show()
            }
        }

    val permissionsToRequest =
        remember {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            if (results.values.any { it }) viewModel.rescan()
        }

    LaunchedEffect(Unit) {
        val anyMissing =
            permissionsToRequest.any { perm ->
                ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
            }
        if (anyMissing) {
            permissionLauncher.launch(permissionsToRequest)
        } else {
            viewModel.rescan()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            FlowTopBar(
                title = stringResource(R.string.downloads_title),
                onBack = if (isRoot) null else onBackClick,
                actions = {
                    if (uiState.externalSyncStatus.isSyncing) {
                        CircularProgressIndicator(
                            modifier =
                                Modifier
                                    .padding(end = 12.dp)
                                    .size(24.dp),
                            strokeWidth = 2.dp,
                            strokeCap = StrokeCap.Round,
                        )
                    } else {
                        IconButton(onClick = {
                            if (ContextCompat.checkSelfPermission(context, externalPermission) == PackageManager.PERMISSION_GRANTED) {
                                launchExternalAppSync()
                            } else {
                                externalPermissionLauncher.launch(externalPermission)
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Sync,
                                contentDescription = stringResource(R.string.sync_external_downloads_action),
                            )
                        }
                    }

                    if (uiState.incompleteDownloadCount > 0) {
                        IconButton(onClick = { showRemoveIncompleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.remove_incomplete_downloads),
                            )
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            MediaKindSelector(
                options = MediaKind.entries,
                selected = selectedKind,
                onSelected = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedKind = it
                },
                label = { stringResource(it.labelRes) },
                icon = { it.icon },
            )

            Crossfade(
                targetState = selectedKind,
                animationSpec = tween(250, easing = EaseOutCubic),
                label = "downloads_kind_crossfade",
                modifier =
                    Modifier
                        .fillMaxSize()
                        .weight(1f),
            ) { kind ->
                when (kind) {
                    MediaKind.Videos -> {
                        VideosDownloadsList(
                            videos = uiState.downloadedVideos,
                            incompleteDownloads = uiState.incompleteVideoDownloads,
                            progressMap = uiState.downloadProgressMap,
                            mergingVideoIds = uiState.mergingVideoIds,
                            isRefreshing = uiState.isScanning,
                            onRefresh = { viewModel.rescan() },
                            onVideoClick = onVideoClick,
                            onDeleteClick = { id, title ->
                                pendingDeletion = PendingDeletion(id, title, MediaKind.Videos)
                            },
                            onPauseClick = { viewModel.pauseVideoDownload(it) },
                            onResumeClick = { viewModel.resumeVideoDownload(it) },
                            onHomeClick = onHomeClick,
                        )
                    }

                    MediaKind.Music -> {
                        MusicDownloadsList(
                            tracks = uiState.downloadedMusic,
                            isRefreshing = uiState.isScanning,
                            onRefresh = { viewModel.rescan() },
                            onMusicClick = onMusicClick,
                            onDeleteClick = { id, title ->
                                pendingDeletion = PendingDeletion(id, title, MediaKind.Music)
                            },
                            onHomeClick = onHomeClick,
                        )
                    }
                }
            }
        }
    }

    pendingDeletion?.let { deletion ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text(stringResource(R.string.delete_download_dialog_title)) },
            text = { Text(stringResource(R.string.delete_download_dialog_text, deletion.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        when (deletion.kind) {
                            MediaKind.Videos -> viewModel.deleteVideoDownload(deletion.id)
                            MediaKind.Music -> viewModel.deleteMusicDownload(deletion.id)
                        }
                        pendingDeletion = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    LaunchedEffect(uiState.externalSyncStatus.lastSyncSummary) {
        if (uiState.externalSyncStatus.lastSyncSummary != null) {
            showSyncSummaryDialog = true
            viewModel.syncExternalDownloadSummaryViewed()
        }
    }

    if (showSyncSummaryDialog) {
        uiState.externalSyncStatus.lastSyncSummary?.let { summary ->
            AlertDialog(
                onDismissRequest = { showSyncSummaryDialog = false },
                title = { Text(stringResource(R.string.sync_summary_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(
                                R.string.sync_summary_body,
                                summary.totalFound,
                                summary.triggeredCount,
                                summary.skippedAlreadyDownloaded,
                                summary.failedCount,
                                summary.removedCount,
                            ),
                        )

                        if (uiState.externalSyncStatus.failedVideos.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.sync_failed_items_label),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                            ) {
                                items(uiState.externalSyncStatus.failedVideos) { failed ->
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text(
                                            text = failed.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        failed.error?.let {
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSyncSummaryDialog = false }) {
                        Text(stringResource(R.string.btn_ok))
                    }
                },
                dismissButton = {
                    if (uiState.externalSyncStatus.failedVideos.isNotEmpty()) {
                        TextButton(onClick = {
                            showSyncSummaryDialog = false
                            viewModel.syncExternalDownloads(retryFailedOnly = true)
                        }) {
                            Text(stringResource(R.string.sync_retry_failed))
                        }
                    }
                },
            )
        }
    }

    if (showRemoveIncompleteDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveIncompleteDialog = false },
            title = { Text(stringResource(R.string.remove_incomplete_downloads)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.remove_incomplete_downloads_message,
                        uiState.incompleteDownloadCount,
                        uiState.incompleteDownloadCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveIncompleteDialog = false
                        viewModel.removeIncompleteDownloads()
                    },
                ) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveIncompleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private data class PendingDeletion(
    val id: String,
    val title: String,
    val kind: MediaKind,
)

private val EXTERNAL_APP_SYNC_PACKAGE =
    if (BuildConfig.DEBUG) {
        "com.ramitsuri.videomonitor.debug"
    } else {
        "com.ramitsuri.videomonitor"
    }
private const val EXTERNAL_APP_SYNC_INTENT_EXTRA_KEY = "refresh_and_exit"
