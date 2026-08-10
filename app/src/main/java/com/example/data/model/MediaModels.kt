package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_items")
data class MediaItem(
    @PrimaryKey val id: String, // filePath.hashCode().toString() or UUID
    val filePath: String,
    val fileName: String,
    val title: String,
    val type: String, // "MOVIE" or "SHOW_EPISODE"
    val showName: String? = null,
    val season: Int? = null,
    val episodeNumber: Int? = null,
    val duration: Long = 0L,
    val playbackPosition: Long = 0L,
    val lastWatched: Long = 0L,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val releaseDate: String? = null,
    val rating: Float? = null,
    val folderPath: String, // Directory path for folder navigation
    val isFavorite: Boolean = false,
    val subtitlePath: String? = null, // Offline subtitle path (.srt/.vtt)
    val cast: String? = null,
    val director: String? = null
)

@Entity(tableName = "cloud_sources")
data class CloudSource(
    @PrimaryKey val id: String,
    val type: String, // "GOOGLE_DRIVE", "DROPBOX", "TRUENAS"
    val name: String,
    val serverUrl: String? = null, // for TrueNAS or custom WebDAV
    val accessToken: String? = null,
    val isConnected: Boolean = false,
    val lastSynced: Long = 0L
)

@Entity(tableName = "playback_histories")
data class PlaybackHistory(
    @PrimaryKey val mediaId: String,
    val title: String,
    val type: String,
    val playbackPosition: Long,
    val duration: Long,
    val lastUpdated: Long
)
