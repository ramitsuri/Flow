package io.github.aedev.flow.ui.screens.library

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.dao.ExternalAppVideoDao
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.local.entity.DownloadWithItems
import io.github.aedev.flow.data.local.entity.ExternalAppVideoEntity
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.DownloadedTrack
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.downloader.FlowDownloadService
import io.github.aedev.flow.player.stream.InnerTubeStreamBridge
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import io.github.aedev.flow.player.stream.StreamMergeUtils
import io.github.aedev.flow.player.stream.StreamSizeEstimator
import io.github.aedev.flow.player.stream.VideoCodecUtils
import io.github.aedev.flow.ui.screens.player.components.DownloadStreamHelpers
import io.github.aedev.flow.ui.screens.player.util.VideoPlayerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.VideoStream
import javax.inject.Inject
import io.github.aedev.flow.data.music.DownloadManager as MusicDownloadManager

@HiltViewModel
class DownloadsViewModel
    @Inject
    constructor(
        private val videoDownloadManager: VideoDownloadManager,
        private val musicDownloadManager: MusicDownloadManager,
        private val youtubeRepository: YouTubeRepository,
        private val externalAppVideoDao: ExternalAppVideoDao,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(DownloadsUiState())
        val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

        /**
         * IDs of items currently being deleted (optimistically hidden from the list).
         */
        private val pendingDeleteIds = MutableStateFlow<Set<String>>(emptySet())

        init {
            observeDownloads()
        }

        private fun observeDownloads() {
            viewModelScope.launch {
                combine(
                    musicDownloadManager.downloadedTracks,
                    pendingDeleteIds,
                ) { tracks, pending ->
                    tracks.filter { it.track.videoId !in pending }
                }.collect { tracks ->
                    _uiState.update { it.copy(downloadedMusic = tracks) }
                }
            }

            viewModelScope.launch {
                combine(
                    videoDownloadManager.downloadedVideos,
                    pendingDeleteIds,
                ) { videos, pending ->
                    videos.filter { it.video.id !in pending }
                }.collect { videos ->
                    _uiState.update { it.copy(downloadedVideos = videos) }
                }
            }

            viewModelScope.launch {
                combine(
                    videoDownloadManager.allDownloads,
                    pendingDeleteIds,
                ) { downloads, pending ->
                    val incomplete =
                        downloads.filter { download ->
                            download.download.videoId !in pending &&
                                download.overallStatus != io.github.aedev.flow.data.local.entity.DownloadItemStatus.COMPLETED
                        }
                    incomplete.filter { !it.isAudioOnly } to incomplete.size
                }.collect { (incomplete, incompleteCount) ->
                    _uiState.update { state ->
                        // Auto-clear merging flags for downloads that are no longer active
                        val activeIds = incomplete.map { it.download.videoId }.toSet()
                        state.copy(
                            incompleteVideoDownloads = incomplete,
                            incompleteDownloadCount = incompleteCount,
                            mergingVideoIds = state.mergingVideoIds.intersect(activeIds),
                        )
                    }
                }
            }

            viewModelScope.launch {
                videoDownloadManager.progressUpdates.collect { update ->
                    _uiState.update { state ->
                        val newMerging =
                            if (update.isMerging) {
                                state.mergingVideoIds + update.videoId
                            } else {
                                state.mergingVideoIds - update.videoId
                            }
                        state.copy(
                            downloadProgressMap = state.downloadProgressMap + (update.videoId to update.progress),
                            mergingVideoIds = newMerging,
                        )
                    }
                }
            }
        }

        fun deleteVideoDownload(videoId: String) {
            pendingDeleteIds.update { it + videoId }
            viewModelScope.launch(Dispatchers.IO) {
                deleteVideoDownloadSuspend(videoId)
                pendingDeleteIds.update { it - videoId }
            }
        }

        private suspend fun deleteVideoDownloadSuspend(videoId: String) {
            val download = videoDownloadManager.getDownloadWithItems(videoId)
            if (download?.overallStatus != DownloadItemStatus.COMPLETED) {
                FlowDownloadService.cancelDownload(appContext, videoId)
                delay(500L)
            }
            videoDownloadManager.deleteDownload(videoId)
        }

        fun deleteMusicDownload(videoId: String) {
            pendingDeleteIds.update { it + videoId }
            viewModelScope.launch(Dispatchers.IO) {
                musicDownloadManager.deleteDownload(videoId)
                pendingDeleteIds.update { it - videoId }
            }
        }

        fun pauseVideoDownload(videoId: String) {
            FlowDownloadService.pauseDownload(appContext, videoId)
        }

        fun resumeVideoDownload(videoId: String) {
            FlowDownloadService.resumeDownload(appContext, videoId)
        }

        fun removeIncompleteDownloads() {
            viewModelScope.launch(Dispatchers.IO) {
                val ids =
                    videoDownloadManager.allDownloads
                        .first()
                        .filter { it.overallStatus != io.github.aedev.flow.data.local.entity.DownloadItemStatus.COMPLETED }
                        .map { it.download.videoId }
                if (ids.isEmpty()) return@launch

                pendingDeleteIds.update { it + ids }
                ids.forEach { videoId -> FlowDownloadService.cancelDownload(appContext, videoId) }
                delay(500L)
                videoDownloadManager.deleteIncompleteDownloads()
                pendingDeleteIds.update { it - ids.toSet() }
            }
        }

        fun rescan() {
            viewModelScope.launch {
                _uiState.update { it.copy(isScanning = true) }
                videoDownloadManager.scanAndRecoverDownloads()
                _uiState.update { it.copy(isScanning = false) }
            }
        }

        fun syncExternalDownloads(retryFailedOnly: Boolean = false) {
            val permission = "com.ramitsuri.videomonitor.READ_MONITORED_DATA"
            if (appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                _uiState.update { it.copy(externalSyncStatus = it.externalSyncStatus.copy(isSyncing = false)) }
                return
            }

            viewModelScope.launch {
                _uiState.update { it.copy(externalSyncStatus = it.externalSyncStatus.copy(isSyncing = true)) }

                val newExternalVideos =
                    withContext(Dispatchers.IO) {
                        fetchExternalVideos()
                    }
                val newExternalVideoIds = newExternalVideos.map { it.videoId }.toSet()
                val knownExternalVideos = externalAppVideoDao.get()
                val currentDownloads =
                    videoDownloadManager.allDownloads
                        .first()
                        .map { it.download.videoId }
                        .toSet()

                val toRemove = knownExternalVideos.filter { it.id !in newExternalVideoIds }
                toRemove.forEach { externalAppVideoToRemove ->
                    pendingDeleteIds.update { it + externalAppVideoToRemove.id }
                    deleteVideoDownloadSuspend(externalAppVideoToRemove.id)
                    pendingDeleteIds.update { it - externalAppVideoToRemove.id }
                }

                if (toRemove.isNotEmpty()) {
                    externalAppVideoDao.delete(toRemove.map { it.id })
                }

                val toDownload =
                    if (retryFailedOnly) {
                        val failedIds =
                            _uiState.value.externalSyncStatus.failedVideos
                                .map { it.videoId }
                                .toSet()
                        newExternalVideos.filter { it.videoId in failedIds }
                    } else {
                        newExternalVideos.filter { it.videoId !in currentDownloads }
                    }

                val skippedCount = if (retryFailedOnly) 0 else newExternalVideos.size - toDownload.size
                var triggeredCount = 0
                val failedVideos = mutableListOf<FailedVideo>()

                toDownload.forEach { extVideo ->
                    try {
                        val streamInfo = youtubeRepository.getVideoStreamInfo(extVideo.videoId)
                        if (streamInfo == null) {
                            failedVideos.add(FailedVideo(extVideo.videoId, extVideo.videoTitle, "Failed to get stream info"))
                            return@forEach
                        }

                        val innerTubeResult = InnerTubeVideoStreamExtractor.extract(extVideo.videoId, false)
                        val innerTubeVideoStreams =
                            innerTubeResult?.let {
                                InnerTubeStreamBridge.convertVideoFormats(it.videoFormats)
                            } ?: emptyList()
                        val innerTubeAudioStreams =
                            innerTubeResult?.let {
                                InnerTubeStreamBridge.convertAudioFormats(it.audioFormats)
                            } ?: emptyList()

                        val extractorVideoStreams =
                            (streamInfo.videoStreams + streamInfo.videoOnlyStreams)
                                .filterIsInstance<VideoStream>()

                        val effectiveVideoStreams =
                            StreamMergeUtils.mergeVideoStreams(
                                extractorVideoStreams,
                                innerTubeVideoStreams,
                            )
                        val effectiveAudioStreams =
                            StreamMergeUtils.mergeAudioStreams(
                                streamInfo.audioStreams,
                                innerTubeAudioStreams,
                            )

                        val streamSizes =
                            StreamSizeEstimator.merge(
                                StreamSizeEstimator.fromExtractorStreams(
                                    effectiveVideoStreams,
                                    effectiveAudioStreams,
                                    streamInfo.duration,
                                ),
                                StreamSizeEstimator.fromInnerTubeFormats(
                                    innerTubeResult?.videoFormats.orEmpty(),
                                    innerTubeResult?.audioFormats.orEmpty(),
                                    streamInfo.duration * 1000L,
                                ),
                            )

                        // Selection logic: Clamp to 1080p, pick highest <= 1080p, then smallest size
                        val targetHeight = 1080
                        val availableHeights = effectiveVideoStreams.map { VideoCodecUtils.qualityHeightFromStream(it) }.distinct()
                        val selectedHeight = availableHeights.filter { it <= targetHeight }.maxOrNull()

                        if (selectedHeight == null) {
                            failedVideos.add(FailedVideo(extVideo.videoId, extVideo.videoTitle, "No suitable resolution found"))
                            return@forEach
                        }

                        val candidateStreams =
                            effectiveVideoStreams.filter {
                                VideoCodecUtils.qualityHeightFromStream(it) == selectedHeight
                            }
                        val bestStream =
                            candidateStreams.minByOrNull { stream ->
                                val codecKey = VideoCodecUtils.codecKeyFromStream(stream)
                                streamSizes[VideoCodecUtils.streamSizeKey(selectedHeight, codecKey)] ?: Long.MAX_VALUE
                            }

                        if (bestStream == null) {
                            failedVideos.add(FailedVideo(extVideo.videoId, extVideo.videoTitle, "No suitable stream found"))
                            return@forEach
                        }

                        val selectedCodec = VideoCodecUtils.codecKeyFromStream(bestStream)
                        val downloadUrl = bestStream.getContent()
                        val codecLabel = VideoCodecUtils.codecLabelFromKey(selectedCodec)
                        val qualityLabel = "$codecLabel ${selectedHeight}p"

                        var audioUrl: String? = null
                        if (bestStream.isVideoOnly) {
                            val compatible =
                                DownloadStreamHelpers.pickCompatibleAudioForVideo(
                                    selectedCodec,
                                    effectiveAudioStreams,
                                    null,
                                )
                            audioUrl = compatible?.getContent()?.takeIf { it.isNotBlank() }
                        }

                        val videoModel =
                            Video(
                                id = extVideo.videoId,
                                title = extVideo.videoTitle,
                                channelName = streamInfo.uploaderName ?: "",
                                channelId = "", // Not strictly needed for download start
                                thumbnailUrl = "",
                                duration = streamInfo.duration.toInt(),
                                viewCount = streamInfo.viewCount,
                                uploadDate = "",
                            )

                        VideoPlayerUtils.startDownload(
                            context = appContext,
                            video = videoModel,
                            url = downloadUrl,
                            qualityLabel = qualityLabel,
                            audioUrl = audioUrl,
                            videoCodec =
                                when (selectedCodec) {
                                    "vp9", "vp8", "av1" -> selectedCodec
                                    else -> null
                                },
                        )
                        triggeredCount++
                    } catch (e: Exception) {
                        Log.e("DownloadsViewModel", "Sync failed for ${extVideo.videoId}", e)
                        failedVideos.add(FailedVideo(extVideo.videoId, extVideo.videoTitle, e.message))
                    }
                }

                externalAppVideoDao.upsert(
                    newExternalVideos.map {
                        ExternalAppVideoEntity(it.videoId, it.videoTitle)
                    },
                )

                _uiState.update {
                    it.copy(
                        externalSyncStatus =
                            ExternalSyncStatus(
                                isSyncing = false,
                                failedVideos = failedVideos,
                                lastSyncSummary =
                                    SyncSummary(
                                        totalFound = newExternalVideos.size,
                                        skippedAlreadyDownloaded = skippedCount,
                                        triggeredCount = triggeredCount,
                                        failedCount = failedVideos.size,
                                        removedCount = toRemove.size,
                                    ),
                            ),
                    )
                }
            }
        }

        fun syncExternalDownloadSummaryViewed() {
            _uiState.update { uiState ->
                uiState.copy(externalSyncStatus = uiState.externalSyncStatus.copy(lastSyncSummary = null))
            }
        }

        private fun fetchExternalVideos(): List<ExternalVideo> {
            val contentUri = "content://com.ramitsuri.videomonitor.provider/videos".toUri()
            val videos = mutableListOf<ExternalVideo>()
            try {
                appContext.contentResolver.query(contentUri, null, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow("videoId")
                    val titleIdx = cursor.getColumnIndexOrThrow("videoTitle")
                    val positionIdx = cursor.getColumnIndexOrThrow("position")
                    while (cursor.moveToNext()) {
                        videos.add(
                            ExternalVideo(
                                videoId = cursor.getString(idIdx),
                                videoTitle = cursor.getString(titleIdx),
                                position = cursor.getInt(positionIdx),
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("DownloadsViewModel", "Failed to query content provider", e)
            }
            return videos.sortedBy { it.position }
        }

        private data class ExternalVideo(
            val videoId: String,
            val videoTitle: String,
            val position: Int,
        )
    }

data class DownloadsUiState(
    val downloadedVideos: List<DownloadedVideo> = emptyList(),
    val incompleteVideoDownloads: List<DownloadWithItems> = emptyList(),
    val downloadedMusic: List<DownloadedTrack> = emptyList(),
    val downloadProgressMap: Map<String, Float> = emptyMap(),
    val mergingVideoIds: Set<String> = emptySet(),
    val incompleteDownloadCount: Int = 0,
    val isLoading: Boolean = false,
    val isScanning: Boolean = false,
    val externalSyncStatus: ExternalSyncStatus = ExternalSyncStatus(),
)

data class ExternalSyncStatus(
    val isSyncing: Boolean = false,
    val failedVideos: List<FailedVideo> = emptyList(),
    val lastSyncSummary: SyncSummary? = null,
)

data class FailedVideo(
    val videoId: String,
    val title: String,
    val error: String? = null,
)

data class SyncSummary(
    val totalFound: Int,
    val skippedAlreadyDownloaded: Int,
    val triggeredCount: Int,
    val failedCount: Int,
    val removedCount: Int,
)
