package com.example.data.repository

import android.content.Context
import com.example.data.database.MediaDao
import com.example.data.model.MediaItem
import com.example.data.model.CloudSource
import com.example.data.model.PlaybackHistory
import com.example.data.service.CloudSyncService
import com.example.data.service.LocalMediaScanner
import com.example.data.service.SubtitleService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

class MediaRepository(private val mediaDao: MediaDao) {

    // Read queries
    val allMediaItems: Flow<List<MediaItem>> = mediaDao.getAllMediaItems()
    val continueWatching: Flow<List<MediaItem>> = mediaDao.getContinueWatching().map { items ->
        val seenShows = mutableSetOf<String>()
        val result = mutableListOf<MediaItem>()
        for (item in items) {
            if (item.type == "SHOW_EPISODE" && !item.showName.isNullOrBlank()) {
                if (seenShows.add(item.showName)) {
                    result.add(item)
                }
            } else {
                result.add(item)
            }
        }
        result
    }
    val cloudSources: Flow<List<CloudSource>> = mediaDao.getAllCloudSources()
    val playbackHistory: Flow<List<PlaybackHistory>> = mediaDao.getAllPlaybackHistory()
    val folders: Flow<List<String>> = mediaDao.getAllFolders()

    fun getMediaItemsInFolder(folderPath: String): Flow<List<MediaItem>> =
        mediaDao.getMediaItemsInFolder(folderPath)

    fun getMediaItemsByType(type: String): Flow<List<MediaItem>> =
        mediaDao.getMediaItemsByType(type)

    suspend fun getMediaItemById(id: String): MediaItem? =
        mediaDao.getMediaItemById(id)

    // Write queries
    suspend fun updateMediaItem(item: MediaItem) =
        mediaDao.updateMediaItem(item)

    suspend fun updatePlaybackPosition(id: String, position: Long, duration: Long) {
        val item = mediaDao.getMediaItemById(id)
        if (item != null) {
            val updated = item.copy(
                playbackPosition = position,
                duration = duration,
                lastWatched = System.currentTimeMillis()
            )
            mediaDao.updateMediaItem(updated)
            
            // Insert history for sync
            val history = PlaybackHistory(
                mediaId = id,
                title = item.title,
                type = item.type,
                playbackPosition = position,
                duration = duration,
                lastUpdated = System.currentTimeMillis()
            )
            mediaDao.insertHistory(history)
        }
    }

    suspend fun deleteMediaItem(item: MediaItem) =
        mediaDao.deleteMediaItem(item)

    suspend fun clearAll() =
        mediaDao.clearAllMedia()

    // Scanner actions
    suspend fun scanDevice(context: Context, foldersList: List<String>, apiKey: String?, omdbApiKey: String? = null, onProgress: (String) -> Unit) {
        LocalMediaScanner.scanDeviceStorage(context, mediaDao, foldersList, apiKey, omdbApiKey, onProgress)
    }

    suspend fun loadDemoLibrary() {
        LocalMediaScanner.populateSampleMediaHub(mediaDao)
    }

    // Subtitle actions
    suspend fun downloadSubtitlesForItem(
        context: Context,
        item: MediaItem,
        language: String,
        onProgress: (String) -> Unit
    ): Boolean = SubtitleService.downloadSubtitles(context, item, language, mediaDao, onProgress)

    suspend fun linkLocalSubtitle(context: Context, item: MediaItem, file: File): Boolean =
        SubtitleService.importLocalSubtitle(context, item, file, mediaDao)

    suspend fun removeLocalSubtitle(context: Context, item: MediaItem): Boolean =
        SubtitleService.removeLocalSubtitle(context, item, mediaDao)

    suspend fun generateCcLocally(
        context: Context,
        item: MediaItem,
        onProgress: (String) -> Unit
    ): Boolean = SubtitleService.generateCcLocally(context, item, mediaDao, onProgress)

    // Cloud sync actions
    suspend fun syncCloud(
        context: Context,
        source: CloudSource,
        tmdbApiKey: String? = null,
        omdbApiKey: String? = null,
        onProgress: (String) -> Unit
    ): Boolean = CloudSyncService.syncWithCloud(context, source, mediaDao, tmdbApiKey, omdbApiKey, onProgress)

    suspend fun connectCloud(
        type: String,
        name: String,
        serverUrl: String? = null,
        accessToken: String? = null
    ): CloudSource = CloudSyncService.connectAccount(type, name, serverUrl, accessToken, mediaDao)

    suspend fun deleteCloudSource(id: String) =
        mediaDao.deleteCloudSource(id)
}
