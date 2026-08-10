package com.example.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.MediaItem
import com.example.data.model.CloudSource
import com.example.data.model.PlaybackHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    // Media Items
    @Query("SELECT * FROM media_items ORDER BY title ASC")
    fun getAllMediaItems(): Flow<List<MediaItem>>

    @Query("SELECT * FROM media_items WHERE folderPath = :folderPath ORDER BY fileName ASC")
    fun getMediaItemsInFolder(folderPath: String): Flow<List<MediaItem>>

    @Query("SELECT DISTINCT folderPath FROM media_items ORDER BY folderPath ASC")
    fun getAllFolders(): Flow<List<String>>

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    suspend fun getMediaItemById(id: String): MediaItem?

    @Query("SELECT * FROM media_items WHERE filePath = :filePath LIMIT 1")
    suspend fun getMediaItemByFilePath(filePath: String): MediaItem?

    @Query("SELECT * FROM media_items WHERE playbackPosition > 0 AND playbackPosition < (duration - 10000) ORDER BY lastWatched DESC")
    fun getContinueWatching(): Flow<List<MediaItem>>

    @Query("SELECT * FROM media_items WHERE type = :type ORDER BY lastWatched DESC")
    fun getMediaItemsByType(type: String): Flow<List<MediaItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaItem(item: MediaItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaItems(items: List<MediaItem>)

    @Update
    suspend fun updateMediaItem(item: MediaItem)

    @Delete
    suspend fun deleteMediaItem(item: MediaItem)

    @Query("DELETE FROM media_items")
    suspend fun clearAllMedia()

    // Cloud Sources
    @Query("SELECT * FROM cloud_sources")
    fun getAllCloudSources(): Flow<List<CloudSource>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCloudSource(source: CloudSource)

    @Query("DELETE FROM cloud_sources WHERE id = :id")
    suspend fun deleteCloudSource(id: String)

    // Playback History Sync
    @Query("SELECT * FROM playback_histories ORDER BY lastUpdated DESC")
    fun getAllPlaybackHistory(): Flow<List<PlaybackHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: PlaybackHistory)
}
