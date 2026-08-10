package com.example.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.model.MediaItem
import com.example.data.model.CloudSource
import com.example.data.model.PlaybackHistory
import com.example.data.repository.MediaRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class MediaViewModel(private val repository: MediaRepository) : ViewModel() {

    private val TAG = "MediaViewModel"

    // Data streams
    val allMedia: StateFlow<List<MediaItem>> = repository.allMediaItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val continueWatching: StateFlow<List<MediaItem>> = repository.continueWatching
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val folders: StateFlow<List<String>> = repository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cloudSources: StateFlow<List<CloudSource>> = repository.cloudSources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playbackHistory: StateFlow<List<PlaybackHistory>> = repository.playbackHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI Operation States
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanStatus = MutableStateFlow("")
    val scanStatus: StateFlow<String> = _scanStatus.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatus = MutableStateFlow("")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _isDownloadingSubs = MutableStateFlow(false)
    val isDownloadingSubs: StateFlow<Boolean> = _isDownloadingSubs.asStateFlow()

    private val _subDownloadStatus = MutableStateFlow("")
    val subDownloadStatus: StateFlow<String> = _subDownloadStatus.asStateFlow()

    // Persistent User Preferences State
    private val _scannedFolders = MutableStateFlow<List<String>>(emptyList())
    val scannedFolders: StateFlow<List<String>> = _scannedFolders.asStateFlow()

    private val _tmdbApiKey = MutableStateFlow("")
    val tmdbApiKey: StateFlow<String> = _tmdbApiKey.asStateFlow()

    private val _omdbApiKey = MutableStateFlow("")
    val omdbApiKey: StateFlow<String> = _omdbApiKey.asStateFlow()

    private val _autoplayEnabled = MutableStateFlow(true)
    val autoplayEnabled: StateFlow<Boolean> = _autoplayEnabled.asStateFlow()

    // Subtitle Customization States
    private val _subStyleBgColor = MutableStateFlow("DEFAULT")
    val subStyleBgColor: StateFlow<String> = _subStyleBgColor.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val _subStyleBgOpacity = MutableStateFlow(0.5f)
    val subStyleBgOpacity: StateFlow<Float> = _subStyleBgOpacity.asStateFlow()

    private val _subStyleTextSize = MutableStateFlow(1.0f)
    val subStyleTextSize: StateFlow<Float> = _subStyleTextSize.asStateFlow()

    private val _subStyleFont = MutableStateFlow("DEFAULT")
    val subStyleFont: StateFlow<String> = _subStyleFont.asStateFlow()

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences("aura_player_prefs", Context.MODE_PRIVATE)
        
        // TMDB API key
        _tmdbApiKey.value = prefs.getString("tmdb_api_key", "") ?: ""

        // OMDB API key
        _omdbApiKey.value = prefs.getString("omdb_api_key", "") ?: ""

        // Autoplay enabled
        _autoplayEnabled.value = prefs.getBoolean("autoplay_enabled", true)

        // Subtitle customization
        _subStyleBgColor.value = prefs.getString("sub_style_bg_color", "DEFAULT") ?: "DEFAULT"
        _subStyleBgOpacity.value = prefs.getFloat("sub_style_bg_opacity", 0.5f)
        _subStyleTextSize.value = prefs.getFloat("sub_style_text_size", 1.0f)
        _subStyleFont.value = prefs.getString("sub_style_font", "DEFAULT") ?: "DEFAULT"

        // Directories to scan
        val foldersSet = prefs.getStringSet("scanned_directories", null)
        if (foldersSet == null) {
            val defaults = listOf(
                File(context.filesDir, "Movies").absolutePath,
                File(context.filesDir, "Shows").absolutePath,
                File(context.cacheDir, "Movies").absolutePath
            )
            // Create directories if they don't exist so user can select/see them
            defaults.forEach { path ->
                val f = File(path)
                if (!f.exists()) f.mkdirs()
            }
            _scannedFolders.value = defaults
            prefs.edit().putStringSet("scanned_directories", defaults.toSet()).apply()
        } else {
            _scannedFolders.value = foldersSet.toList().sorted()
        }
    }

    fun saveTmdbApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences("aura_player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("tmdb_api_key", key.trim()).apply()
        _tmdbApiKey.value = key.trim()
    }

    fun saveOmdbApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences("aura_player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("omdb_api_key", key.trim()).apply()
        _omdbApiKey.value = key.trim()
    }

    fun updateMovieMetadata(
        id: String,
        title: String,
        overview: String?,
        releaseDate: String?,
        rating: Float?,
        posterUrl: String?,
        backdropUrl: String?,
        type: String = "MOVIE",
        showName: String? = null,
        season: Int? = null,
        episodeNumber: Int? = null
    ) {
        viewModelScope.launch {
            val item = repository.getMediaItemById(id)
            if (item != null) {
                val updated = item.copy(
                    title = title,
                    overview = overview,
                    releaseDate = releaseDate,
                    rating = rating ?: 0f,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    type = type,
                    showName = showName,
                    season = season,
                    episodeNumber = episodeNumber
                )
                repository.updateMediaItem(updated)
            }
        }
    }

    fun markAsNotMovie(id: String) {
        viewModelScope.launch {
            val item = repository.getMediaItemById(id)
            if (item != null) {
                val updated = item.copy(
                    type = "OTHER",
                    showName = null,
                    season = null,
                    episodeNumber = null
                )
                repository.updateMediaItem(updated)
            }
        }
    }

    fun getDownloadedFile(context: Context, ep: MediaItem): File {
        val safeFileName = "${ep.title.replace("[^a-zA-Z0-9.-]".toRegex(), "_")}_${ep.id.hashCode()}.mp4"
        
        // 1. Try Public Downloads/LuminaPlayer first
        try {
            val publicDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val appDir = File(publicDir, "LuminaPlayer")
            val file = File(appDir, safeFileName)
            if (file.exists()) return file
        } catch (e: Exception) {}

        // 2. Try External Files Download/LuminaPlayer
        try {
            val extFilesDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (extFilesDir != null) {
                val appDir = File(extFilesDir, "LuminaPlayer")
                val file = File(appDir, safeFileName)
                if (file.exists()) return file
            }
        } catch (e: Exception) {}

        // 3. Fallback internal downloads folder
        val internalDir = File(context.filesDir, "downloads")
        val oldFile = File(internalDir, "${ep.id}.mp4")
        if (oldFile.exists()) return oldFile
        return File(internalDir, safeFileName)
    }

    private fun getDownloadTargetFile(context: Context, ep: MediaItem): File {
        val safeFileName = "${ep.title.replace("[^a-zA-Z0-9.-]".toRegex(), "_")}_${ep.id.hashCode()}.mp4"
        
        // Try Public Downloads/LuminaPlayer
        try {
            val publicDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val appDir = File(publicDir, "LuminaPlayer")
            if (!appDir.exists()) {
                appDir.mkdirs()
            }
            if (appDir.exists() && appDir.canWrite()) {
                return File(appDir, safeFileName)
            }
        } catch (e: Exception) {}

        // Fallback External Files Download/LuminaPlayer
        try {
            val extFilesDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (extFilesDir != null) {
                val appDir = File(extFilesDir, "LuminaPlayer")
                if (!appDir.exists()) {
                    appDir.mkdirs()
                }
                return File(appDir, safeFileName)
            }
        } catch (e: Exception) {}

        // Fallback Internal
        val internalDir = File(context.filesDir, "downloads")
        if (!internalDir.exists()) {
            internalDir.mkdirs()
        }
        return File(internalDir, safeFileName)
    }

    private fun getUnsafeOkHttpClient(): okhttp3.OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            )
            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocketFactory = sslContext.socketFactory
            okhttp3.OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            okhttp3.OkHttpClient()
        }
    }

    fun downloadEpisode(context: Context, ep: MediaItem) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val isNetworkFile = ep.filePath.startsWith("http://") || ep.filePath.startsWith("https://")
            if (!isNetworkFile) return@launch

            _downloadProgress.update { it + (ep.id to 0.01f) }

            try {
                var finalUrl = ep.filePath
                val fileId = if (ep.id.startsWith("gdrive_")) ep.id.removePrefix("gdrive_") else null
                val token = cloudSources.value.find { it.type == "GOOGLE_DRIVE" && !it.accessToken.isNullOrBlank() }?.accessToken

                if (fileId != null) {
                    val rawUrl = if (!token.isNullOrBlank()) {
                        "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
                    } else {
                        ep.filePath
                    }
                    finalUrl = com.example.data.service.GoogleDriveResolver.resolveGoogleDriveUrl(rawUrl, token)
                }

                // Setup HTTP client using trust-all certificates to handle local self-signed HTTPS TrueNAS/WebDAV servers
                val client = getUnsafeOkHttpClient()

                val requestBuilder = okhttp3.Request.Builder().url(finalUrl)
                
                // Add Google Drive Bearer token header if applicable
                if (fileId != null && !token.isNullOrBlank() && finalUrl.startsWith("https://www.googleapis.com/")) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }
                
                // Extract and inject Basic Auth headers if URL has credential authority (e.g. TrueNAS WebDAV user:pass@host)
                if (ep.filePath.startsWith("http") && ep.filePath.contains("@")) {
                    try {
                        val schemeIdx = ep.filePath.indexOf("://")
                        if (schemeIdx != -1) {
                            val scheme = ep.filePath.substring(0, schemeIdx + 3)
                            val withoutScheme = ep.filePath.substring(schemeIdx + 3)
                            val atIdx = withoutScheme.indexOf("@")
                            if (atIdx != -1) {
                                val credentials = withoutScheme.substring(0, atIdx)
                                val rest = withoutScheme.substring(atIdx + 1)
                                val colonIdx = credentials.indexOf(":")
                                val cleanPlayUrl = scheme + rest
                                requestBuilder.url(cleanPlayUrl)
                                if (colonIdx != -1) {
                                    val username = java.net.URLDecoder.decode(credentials.substring(0, colonIdx), "UTF-8")
                                    val password = java.net.URLDecoder.decode(credentials.substring(colonIdx + 1), "UTF-8")
                                    requestBuilder.header("Authorization", okhttp3.Credentials.basic(username, password))
                                } else {
                                    val username = java.net.URLDecoder.decode(credentials, "UTF-8")
                                    requestBuilder.header("Authorization", okhttp3.Credentials.basic(username, ""))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse basic auth credentials for download", e)
                    }
                }

                // Cookie support for GDrive
                val cookies = com.example.data.service.GoogleDriveResolver.gdriveCookies[finalUrl] ?: com.example.data.service.GoogleDriveResolver.gdriveCookies[ep.filePath]
                if (!cookies.isNullOrBlank()) {
                    requestBuilder.header("Cookie", cookies)
                }
                requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

                val downloadRequest = requestBuilder.build()

                client.newCall(downloadRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Download request failed for ${ep.title}: Code ${response.code}")
                        _downloadProgress.update { it - ep.id }
                        return@launch
                    }

                    val body = response.body
                    if (body == null) {
                        Log.e(TAG, "Download response body is null for ${ep.title}")
                        _downloadProgress.update { it - ep.id }
                        return@launch
                    }

                    val contentLength = body.contentLength()
                    val localFile = getDownloadTargetFile(context, ep)

                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    body.byteStream().use { inputStream ->
                        localFile.outputStream().use { outputStream ->
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                if (contentLength > 0) {
                                    val progress = totalBytesRead.toFloat() / contentLength
                                    val clampedProgress = progress.coerceIn(0.01f, 0.99f)
                                    _downloadProgress.update { it + (ep.id to clampedProgress) }
                                }
                            }
                        }
                    }

                    // For the transparent overlay checking, we don't strictly need to modify filePath to keep network streams streamable.
                    // But we will copy the filePath to localFile's absolute path to satisfy existing .startsWith("/") checks in standard parts of the app!
                    val updatedItem = ep.copy(filePath = localFile.absolutePath)
                    repository.updateMediaItem(updatedItem)
                    _downloadProgress.update { it - ep.id }
                    Log.d(TAG, "Downloaded episode ${ep.title} successfully to ${localFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download episode ${ep.title}", e)
                _downloadProgress.update { it - ep.id }
            }
        }
    }

    fun deleteDownloadedEpisode(context: Context, ep: MediaItem) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val localFile = getDownloadedFile(context, ep)
            if (localFile.exists()) {
                localFile.delete()
            }
            
            // Revert back to original network stream URL
            val fileId = if (ep.id.startsWith("gdrive_")) ep.id.removePrefix("gdrive_") else null
            if (fileId != null) {
                val token = cloudSources.value.find { it.type == "GOOGLE_DRIVE" && !it.accessToken.isNullOrBlank() }?.accessToken
                val cloudUrl = if (!token.isNullOrBlank()) {
                    "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
                } else {
                    "https://drive.google.com/uc?export=download&id=$fileId"
                }
                val updatedItem = ep.copy(filePath = cloudUrl)
                repository.updateMediaItem(updatedItem)
            } else if (ep.id.startsWith("truenas_")) {
                // TrueNAS files use our transparent downloaded file path checking, so we don't overwrite filePath in the DB.
                // We just trigger a dummy update in the Room Database to force the UI to recompose and show "Not Downloaded" instantly.
                val updatedItem = ep.copy(isFavorite = ep.isFavorite)
                repository.updateMediaItem(updatedItem)
            }
        }
    }

    fun updateShowMetadata(
        oldShowName: String,
        newShowName: String,
        overview: String?,
        releaseDate: String?,
        rating: Float?,
        posterUrl: String?,
        backdropUrl: String?,
        tmdbId: Int? = null
    ) {
        viewModelScope.launch {
            val apiKey = _tmdbApiKey.value.ifBlank { null }
            val episodes = repository.allMediaItems.first().filter { it.showName == oldShowName }
            
            episodes.forEach { ep ->
                var epTitle = ep.title
                var epOverview = ep.overview
                var epBackdrop = backdropUrl ?: ep.backdropUrl
                
                if (tmdbId != null) {
                    if (apiKey != null) {
                        try {
                            val epDetails = com.example.data.service.MetadataService.api.getEpisodeDetails(
                                tvId = tmdbId,
                                season = ep.season ?: 1,
                                episode = ep.episodeNumber ?: 1,
                                apiKey = apiKey
                            )
                            epTitle = epDetails.name?.takeIf { it.isNotBlank() } ?: ep.title
                            epOverview = epDetails.overview ?: ep.overview
                            if (!epDetails.still_path.isNullOrEmpty()) {
                                epBackdrop = "https://image.tmdb.org/t/p/w1280${epDetails.still_path}"
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to fetch episode details for ep ${ep.episodeNumber}", e)
                        }
                    } else {
                        // Use keyless TVmaze!
                        try {
                            val epDetails = com.example.data.service.MetadataService.fetchEpisodeFromTvmaze(
                                showId = tmdbId,
                                season = ep.season ?: 1,
                                episode = ep.episodeNumber ?: 1
                            )
                            if (epDetails != null) {
                                epTitle = epDetails.name
                                epOverview = epDetails.overview ?: ep.overview
                                if (!epDetails.stillUrl.isNullOrEmpty()) {
                                    epBackdrop = epDetails.stillUrl
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to fetch TVmaze episode details for ep ${ep.episodeNumber}", e)
                        }
                    }
                }
                
                val displayTitle = if (ep.type == "SHOW_EPISODE") {
                    "${newShowName} - S${ep.season ?: 1}E${ep.episodeNumber ?: 1}: ${epTitle.substringAfter(" - ").substringAfter("S01E01").trim()}"
                } else {
                    ep.title
                }

                val updated = ep.copy(
                    title = displayTitle,
                    showName = newShowName,
                    posterUrl = posterUrl,
                    backdropUrl = epBackdrop,
                    overview = epOverview ?: overview,
                    rating = rating ?: ep.rating,
                    releaseDate = releaseDate ?: ep.releaseDate,
                    type = "SHOW_EPISODE"
                )
                repository.updateMediaItem(updated)
            }
        }
    }

    fun setAutoplayEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("aura_player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("autoplay_enabled", enabled).apply()
        _autoplayEnabled.value = enabled
    }

    fun addScannedFolder(context: Context, path: String) {
        val cleanPath = path.trim()
        if (cleanPath.isEmpty()) return
        val prefs = context.getSharedPreferences("aura_player_prefs", Context.MODE_PRIVATE)
        val foldersSet = prefs.getStringSet("scanned_directories", emptySet())?.toMutableSet() ?: mutableSetOf()
        foldersSet.add(cleanPath)
        prefs.edit().putStringSet("scanned_directories", foldersSet).apply()
        _scannedFolders.value = foldersSet.toList().sorted()
    }

    fun removeScannedFolder(context: Context, path: String) {
        val prefs = context.getSharedPreferences("aura_player_prefs", Context.MODE_PRIVATE)
        val foldersSet = prefs.getStringSet("scanned_directories", emptySet())?.toMutableSet() ?: return
        foldersSet.remove(path)
        prefs.edit().putStringSet("scanned_directories", foldersSet).apply()
        _scannedFolders.value = foldersSet.toList().sorted()
    }

    fun scanLocalMedia(context: Context) {
        viewModelScope.launch {
            _isScanning.value = true
            _scanStatus.value = "Starting media library scan..."
            try {
                // Ensure preferences are loaded
                loadPreferences(context)
                val apiKey = _tmdbApiKey.value.ifBlank { null }
                val omdbKey = _omdbApiKey.value.ifBlank { null }
                val folders = _scannedFolders.value
                
                if (folders.isEmpty()) {
                    _scanStatus.value = "No directories selected to scan!"
                    return@launch
                }
                
                repository.scanDevice(context, folders, apiKey, omdbKey) { progress ->
                    _scanStatus.value = progress
                }
                _scanStatus.value = "Scan completed successfully."
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed: ", e)
                _scanStatus.value = "Scan failed: ${e.localizedMessage}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun loadDemoLibrary() {
        viewModelScope.launch {
            _isScanning.value = true
            _scanStatus.value = "Generating high-fidelity Lumina Player Sample Library..."
            try {
                repository.loadDemoLibrary()
                _scanStatus.value = "Demo library loaded. Beautiful movie covers linked!"
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading demo library: ", e)
                _scanStatus.value = "Failed loading demo: ${e.localizedMessage}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun clearLibrary() {
        viewModelScope.launch {
            repository.clearAll()
            _scanStatus.value = "Media catalog cleared."
        }
    }

    fun syncCloudSource(context: Context, source: CloudSource) {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncStatus.value = "Starting cloud sync with ${source.name}..."
            try {
                val apiKey = _tmdbApiKey.value.ifBlank { null }
                val omdbKey = _omdbApiKey.value.ifBlank { null }
                val success = repository.syncCloud(context, source, apiKey, omdbKey) { progress ->
                    _syncStatus.value = progress
                }
                if (success) {
                    _syncStatus.value = "${source.name} synced successfully!"
                } else {
                    _syncStatus.value = "Sync with ${source.name} completed with warnings."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed: ", e)
                _syncStatus.value = "Sync failed: ${e.localizedMessage}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun connectCloudAccount(type: String, name: String, url: String? = null, token: String? = null) {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncStatus.value = "Connecting to $name..."
            try {
                repository.connectCloud(type, name, url, token)
                _syncStatus.value = "$name connected successfully."
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ", e)
                _syncStatus.value = "Connection failed: ${e.localizedMessage}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun disconnectCloudAccount(id: String) {
        viewModelScope.launch {
            repository.deleteCloudSource(id)
            _syncStatus.value = "Account disconnected."
        }
    }

    fun downloadSubtitles(context: Context, item: MediaItem, language: String) {
        viewModelScope.launch {
            _isDownloadingSubs.value = true
            _subDownloadStatus.value = "Querying subtitle databases..."
            try {
                val success = repository.downloadSubtitlesForItem(context, item, language) { progress ->
                    _subDownloadStatus.value = progress
                }
                if (success) {
                    _subDownloadStatus.value = "Subtitles downloaded and synchronized!"
                } else {
                    _subDownloadStatus.value = "No matching subtitles found."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sub download error: ", e)
                _subDownloadStatus.value = "Download failed: ${e.localizedMessage}"
            } finally {
                _isDownloadingSubs.value = false
            }
        }
    }

    fun linkLocalSubtitleFile(context: Context, item: MediaItem, file: File) {
        viewModelScope.launch {
            _isDownloadingSubs.value = true
            _subDownloadStatus.value = "Linking local subtitle file..."
            val success = repository.linkLocalSubtitle(context, item, file)
            if (success) {
                _subDownloadStatus.value = "Local subtitle linked!"
            } else {
                _subDownloadStatus.value = "Failed to copy subtitle file."
            }
            _isDownloadingSubs.value = false
        }
    }

    fun removeLocalSubtitle(context: Context, item: MediaItem) {
        viewModelScope.launch {
            _isDownloadingSubs.value = true
            _subDownloadStatus.value = "Removing subtitle..."
            val success = repository.removeLocalSubtitle(context, item)
            if (success) {
                _subDownloadStatus.value = "Local subtitle removed."
            } else {
                _subDownloadStatus.value = "Failed to remove subtitle."
            }
            _isDownloadingSubs.value = false
        }
    }

    fun generateCcLocally(context: Context, item: MediaItem) {
        viewModelScope.launch {
            _isDownloadingSubs.value = true
            _subDownloadStatus.value = "Initializing Local AI transcription..."
            try {
                val success = repository.generateCcLocally(context, item) { progress ->
                    _subDownloadStatus.value = progress
                }
                if (success) {
                    _subDownloadStatus.value = "Offline CC generated & synchronized!"
                } else {
                    _subDownloadStatus.value = "Offline CC generation failed."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local CC error: ", e)
                _subDownloadStatus.value = "Generation failed: ${e.localizedMessage}"
            } finally {
                _isDownloadingSubs.value = false
            }
        }
    }

    fun updatePlaybackPosition(id: String, position: Long, duration: Long) {
        viewModelScope.launch {
            repository.updatePlaybackPosition(id, position, duration)
        }
    }

    fun updateSubtitleStyle(
        context: Context,
        bgColor: String,
        bgOpacity: Float,
        textSize: Float,
        font: String
    ) {
        val prefs = context.getSharedPreferences("aura_player_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("sub_style_bg_color", bgColor)
            putFloat("sub_style_bg_opacity", bgOpacity)
            putFloat("sub_style_text_size", textSize)
            putString("sub_style_font", font)
        }.apply()
        
        _subStyleBgColor.value = bgColor
        _subStyleBgOpacity.value = bgOpacity
        _subStyleTextSize.value = textSize
        _subStyleFont.value = font
    }

    // Factory Provider
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MediaViewModel::class.java)) {
                val db = AppDatabase.getDatabase(context)
                val repository = MediaRepository(db.mediaDao())
                val vm = MediaViewModel(repository)
                vm.loadPreferences(context)
                @Suppress("UNCHECKED_CAST")
                return vm as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
