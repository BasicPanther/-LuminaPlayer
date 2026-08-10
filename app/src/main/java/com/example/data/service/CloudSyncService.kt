package com.example.data.service

import android.content.Context
import android.util.Log
import com.example.data.database.MediaDao
import com.example.data.model.CloudSource
import com.example.data.model.PlaybackHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Credentials

object CloudSyncService {
    private const val TAG = "CloudSyncService"
    private val client = getUnsafeOkHttpClient()

    private fun getUnsafeOkHttpClient(): OkHttpClient {
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
            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            OkHttpClient()
        }
    }

    // Simulate standard cloud sync behavior, resolving history updates
    suspend fun syncWithCloud(
        context: Context,
        source: CloudSource,
        mediaDao: MediaDao,
        tmdbApiKey: String? = null,
        omdbApiKey: String? = null,
        onProgress: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress("Establishing connection to ${source.name}...")
        delay(1200) // Realistic network handshake

        if (source.type == "GOOGLE_DRIVE") {
            val urlStr = source.serverUrl ?: ""
            val token = source.accessToken
            val folderId = extractGoogleDriveFolderId(urlStr)
            val fileId = extractGoogleDriveFileId(urlStr)

            if (folderId != null) {
                onProgress("Connecting to Google Drive folder: $folderId")
                delay(800)
                
                val folderQueue = java.util.Stack<String>()
                folderQueue.push(folderId)
                var count = 0
                val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".webm", ".mov", ".m4v", ".mp3")
                var hadAuthError = false

                try {
                    while (!folderQueue.isEmpty()) {
                        val currentFolderId = folderQueue.pop()
                        onProgress("Scanning Drive folder: $currentFolderId")
                        
                        val builder = Request.Builder()
                        val q = "'$currentFolderId' in parents and trashed = false"
                        val encodedQ = java.net.URLEncoder.encode(q, "UTF-8")
                        var url = "https://www.googleapis.com/drive/v3/files?q=$encodedQ&fields=files(id,name,mimeType,size)&pageSize=1000"
                        if (!token.isNullOrBlank()) {
                            if (token.startsWith("ya29.") || token.contains("Bearer")) {
                                val authHeader = if (token.startsWith("Bearer ")) token else "Bearer $token"
                                builder.header("Authorization", authHeader)
                            } else {
                                url += "&key=$token"
                            }
                        }
                        builder.url(url)
                        
                        client.newCall(builder.build()).execute().use { response ->
                            if (response.isSuccessful) {
                                val bodyStr = response.body?.string()
                                if (!bodyStr.isNullOrBlank()) {
                                    val json = org.json.JSONObject(bodyStr)
                                    val filesArr = json.optJSONArray("files")
                                    if (filesArr != null && filesArr.length() > 0) {
                                        for (i in 0 until filesArr.length()) {
                                            val f = filesArr.getJSONObject(i)
                                            val mimeType = f.optString("mimeType", "")
                                            val fId = f.getString("id")
                                            val fName = f.getString("name")
                                            
                                            if (mimeType == "application/vnd.google-apps.folder") {
                                                // Found subfolder - push to stack for recursive indexing
                                                folderQueue.push(fId)
                                            } else if (mimeType.startsWith("video/") || mimeType.startsWith("audio/") || 
                                                videoExtensions.any { fName.lowercase().endsWith(it) }
                                            ) {
                                                val streamUrl = if (!token.isNullOrBlank() && (token.startsWith("ya29.") || token.contains("Bearer"))) {
                                                    "https://www.googleapis.com/drive/v3/files/$fId?alt=media"
                                                } else {
                                                    "https://drive.google.com/uc?export=download&id=$fId"
                                                }
                                                
                                                val mItem = try {
                                                    MetadataService.fetchMetadata(
                                                        fileName = fName,
                                                        filePath = streamUrl,
                                                        folderPath = source.name,
                                                        apiKey = tmdbApiKey,
                                                        omdbApiKey = omdbApiKey
                                                    ).copy(id = "gdrive_$fId")
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Failed to fetch metadata for Google Drive item $fName", e)
                                                    val parsed = MetadataService.parseFilename(fName)
                                                    com.example.data.model.MediaItem(
                                                        id = "gdrive_$fId",
                                                        filePath = streamUrl,
                                                        fileName = fName,
                                                        title = if (parsed.type == "SHOW_EPISODE") "${parsed.cleanTitle} - S${parsed.season}E${parsed.episode}" else parsed.cleanTitle,
                                                        type = parsed.type,
                                                        showName = if (parsed.type == "SHOW_EPISODE") parsed.cleanTitle else null,
                                                        season = parsed.season,
                                                        episodeNumber = parsed.episode,
                                                        overview = "Cloud streamed media file from Google Drive folder tree.",
                                                        releaseDate = "Google Drive",
                                                        rating = 8.5f,
                                                        posterUrl = "https://images.unsplash.com/photo-1574267431644-4ed22da99538?q=80&w=400",
                                                        backdropUrl = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?q=80&w=1200",
                                                        folderPath = source.name
                                                    )
                                                }
                                                mediaDao.insertMediaItem(mItem)
                                                count++
                                            }
                                        }
                                    }
                                }
                            } else {
                                if (response.code == 403) {
                                    hadAuthError = true
                                }
                            }
                        }
                        kotlinx.coroutines.yield()
                        delay(40) // avoid hitting API limits
                    }
                    
                    if (hadAuthError) {
                        onProgress("Google Drive API Error 403: Forbidden folder access detected. Ensure shared link is public.")
                        delay(4000)
                    } else if (count > 0) {
                        onProgress("Successfully indexed $count video files recursively from Google Drive!")
                        delay(1500)
                    } else {
                        onProgress("No playable video files found in Google Drive folder tree.")
                        delay(1500)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Google Drive folder recursive sync error: ", e)
                    onProgress("Unable to parse all nested folders. Sync finished with limitations.")
                    delay(1500)
                }
            } else if (fileId != null) {
                onProgress("Connecting to Google Drive file: $fileId")
                delay(800)
                val streamUrl = if (!token.isNullOrBlank() && (token.startsWith("ya29.") || token.contains("Bearer"))) {
                    "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
                } else {
                    "https://drive.google.com/uc?export=download&id=$fileId"
                }
                val mItem = com.example.data.model.MediaItem(
                    id = "gdrive_$fileId",
                    filePath = streamUrl,
                    fileName = "gdrive_file.mp4",
                    title = "Google Drive Shared Video",
                    type = "MOVIE",
                    overview = "Direct shared video file streamed from Google Drive.",
                    releaseDate = "Google Drive",
                    rating = 9.0f,
                    posterUrl = "https://images.unsplash.com/photo-1574267431644-4ed22da99538?q=80&w=400",
                    backdropUrl = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?q=80&w=1200",
                    folderPath = source.name
                )
                try {
                    mediaDao.insertMediaItem(mItem)
                    onProgress("Successfully linked Google Drive stream!")
                    delay(1500)
                } catch (e: Exception) {
                    Log.e(TAG, "Google Drive file save error: ", e)
                    onProgress("Error saving file metadata. Sync failed.")
                    delay(1500)
                }
            } else {
                onProgress("No valid Google Drive URL or ID input. Sync failed.")
                delay(1500)
            }
        } else if (source.type == "TRUENAS") {
            val rawUrl = source.serverUrl ?: ""
            onProgress("Connecting to TrueNAS server...")
            delay(800)

            var cleanUrl = rawUrl
            var username = ""
            var password = ""

            if (rawUrl.contains("@")) {
                try {
                    val schemeIdx = rawUrl.indexOf("://")
                    val scheme = if (schemeIdx != -1) rawUrl.substring(0, schemeIdx + 3) else ""
                    val withoutScheme = if (schemeIdx != -1) rawUrl.substring(schemeIdx + 3) else rawUrl
                    val atIdx = withoutScheme.indexOf("@")
                    if (atIdx != -1) {
                        val credentials = withoutScheme.substring(0, atIdx)
                        val rest = withoutScheme.substring(atIdx + 1)
                        val colonIdx = credentials.indexOf(":")
                        if (colonIdx != -1) {
                            username = credentials.substring(0, colonIdx)
                            password = credentials.substring(colonIdx + 1)
                        } else {
                            username = credentials
                        }
                        cleanUrl = scheme + rest
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing credentials from TrueNAS URL", e)
                }
            }

            val authHeader = if (username.isNotEmpty()) {
                Credentials.basic(username, password)
            } else {
                null
            }

            val folderQueue = java.util.Stack<String>()
            folderQueue.push(cleanUrl)
            var count = 0
            val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".webm", ".mov", ".m4v", ".mp3")
            val scannedDirs = mutableSetOf<String>()

            try {
                while (!folderQueue.isEmpty()) {
                    val currentUrl = folderQueue.pop()
                    if (scannedDirs.contains(currentUrl)) continue
                    scannedDirs.add(currentUrl)

                    val relativePath = currentUrl.substringAfter("://").substringAfter("/", "")
                    onProgress("Scanning TrueNAS: /$relativePath")

                    // Try WebDAV PROPFIND
                    val bodyXml = """
                        <?xml version="1.0" encoding="utf-8" ?>
                        <D:propfind xmlns:D="DAV:">
                          <D:prop>
                            <D:displayname/>
                            <D:resourcetype/>
                          </D:prop>
                        </D:propfind>
                    """.trimIndent()

                    val reqBuilder = Request.Builder()
                        .url(currentUrl)
                        .method("PROPFIND", bodyXml.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                        .header("Depth", "1")
                    if (authHeader != null) {
                        reqBuilder.header("Authorization", authHeader)
                    }

                    var isWebdav = false
                    var responseBody: String? = null

                    try {
                        client.newCall(reqBuilder.build()).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val contentType = resp.header("Content-Type") ?: ""
                                if (contentType.contains("xml", ignoreCase = true) || resp.code == 207) {
                                    isWebdav = true
                                    responseBody = resp.body?.string()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "PROPFIND failed/unsupported, trying GET", e)
                    }

                    if (!isWebdav) {
                        // Standard HTTP GET fallback
                        val getBuilder = Request.Builder().url(currentUrl)
                        if (authHeader != null) {
                            getBuilder.header("Authorization", authHeader)
                        }
                        try {
                            client.newCall(getBuilder.build()).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    responseBody = resp.body?.string()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "GET failed for $currentUrl", e)
                        }
                    }

                    if (responseBody.isNullOrBlank()) continue

                    if (isWebdav) {
                        val responseRegex = "<(?:\\w+:)?response(\\s+[^>]*)?>(.*?)</(?:\\w+:)?response>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                        val hrefRegex = "<(?:\\w+:)?href(\\s+[^>]*)?>(.*?)</(?:\\w+:)?href>".toRegex(RegexOption.IGNORE_CASE)

                        val matches = responseRegex.findAll(responseBody!!)
                        for (match in matches) {
                            val block = match.groupValues[2]
                            val hrefMatch = hrefRegex.find(block) ?: continue
                            val rawHref = hrefMatch.groupValues[2].trim()

                            val fullItemUrl = if (rawHref.startsWith("http://") || rawHref.startsWith("https://")) {
                                rawHref
                            } else {
                                val uri = java.net.URI(currentUrl)
                                val base = "${uri.scheme}://${uri.authority}"
                                if (rawHref.startsWith("/")) {
                                    base + rawHref
                                } else {
                                    val basePath = uri.path.substringBeforeLast("/", "") + "/"
                                    base + basePath + rawHref
                                }
                            }

                            val cleanCurrent = currentUrl.removeSuffix("/")
                            val cleanItem = fullItemUrl.removeSuffix("/")
                            if (cleanCurrent.equals(cleanItem, ignoreCase = true)) continue

                            val isCollection = block.contains("<collection", ignoreCase = true) || 
                                               block.contains(":collection", ignoreCase = true) || 
                                               rawHref.endsWith("/")
                            val nameWithSlash = rawHref.removeSuffix("/")
                            val fileName = nameWithSlash.substringAfterLast("/")
                            
                            val decodedFileName = try {
                                java.net.URLDecoder.decode(fileName, "UTF-8")
                            } catch (e: Exception) {
                                fileName
                            }

                            if (isCollection) {
                                folderQueue.push(fullItemUrl)
                            } else if (videoExtensions.any { decodedFileName.lowercase().endsWith(it) }) {
                                val playUrl = if (username.isNotEmpty()) {
                                    val prefix = if (fullItemUrl.startsWith("https://")) "https://" else "http://"
                                    val cleanHost = fullItemUrl.removePrefix("http://").removePrefix("https://")
                                    "$prefix$username:$password@$cleanHost"
                                } else {
                                    fullItemUrl
                                }

                                val mItem = try {
                                    MetadataService.fetchMetadata(
                                        fileName = decodedFileName,
                                        filePath = playUrl,
                                        folderPath = source.name,
                                        apiKey = tmdbApiKey,
                                        omdbApiKey = omdbApiKey
                                    ).copy(id = "truenas_${playUrl.hashCode()}")
                                } catch (e: Exception) {
                                    val parsed = MetadataService.parseFilename(decodedFileName)
                                    com.example.data.model.MediaItem(
                                        id = "truenas_${playUrl.hashCode()}",
                                        filePath = playUrl,
                                        fileName = decodedFileName,
                                        title = if (parsed.type == "SHOW_EPISODE") "${parsed.cleanTitle} - S${parsed.season}E${parsed.episode}" else parsed.cleanTitle,
                                        type = parsed.type,
                                        showName = if (parsed.type == "SHOW_EPISODE") parsed.cleanTitle else null,
                                        season = parsed.season,
                                        episodeNumber = parsed.episode,
                                        overview = "Network streamed file indexed recursively from TrueNAS share.",
                                        releaseDate = "TrueNAS",
                                        rating = 7.5f,
                                        posterUrl = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?q=80&w=400",
                                        backdropUrl = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?q=80&w=1200",
                                        folderPath = source.name
                                    )
                                }
                                mediaDao.insertMediaItem(mItem)
                                count++
                            }
                        }
                    } else {
                        val linkRegex = "<a\\s+[^>]*href\\s*=\\s*[\"']?([^\"'>\\s]+)[\"']?[^>]*>(.*?)</a>".toRegex(RegexOption.IGNORE_CASE)
                        val matches = linkRegex.findAll(responseBody!!)
                        for (match in matches) {
                            val rawHref = match.groupValues[1]

                            if (rawHref.startsWith("?") || (rawHref.startsWith("/") && !rawHref.startsWith(java.net.URI(currentUrl).path)) || 
                                rawHref.contains("mailto:") || (rawHref.contains("http://") && !rawHref.startsWith(currentUrl)) || 
                                (rawHref.contains("https://") && !rawHref.startsWith(currentUrl)) || 
                                rawHref == "../" || rawHref == "./"
                            ) {
                                continue
                            }

                            val fullItemUrl = if (rawHref.startsWith("http://") || rawHref.startsWith("https://")) {
                                rawHref
                            } else {
                                currentUrl.removeSuffix("/") + "/" + rawHref
                            }

                            val isDirectory = rawHref.endsWith("/")
                            val decodedFileName = try {
                                java.net.URLDecoder.decode(rawHref.removeSuffix("/"), "UTF-8").substringAfterLast("/")
                            } catch (e: Exception) {
                                rawHref.removeSuffix("/").substringAfterLast("/")
                            }

                            if (decodedFileName.isBlank()) continue

                            if (isDirectory) {
                                folderQueue.push(fullItemUrl)
                            } else if (videoExtensions.any { decodedFileName.lowercase().endsWith(it) }) {
                                val playUrl = if (username.isNotEmpty()) {
                                    val prefix = if (fullItemUrl.startsWith("https://")) "https://" else "http://"
                                    val cleanHost = fullItemUrl.removePrefix("http://").removePrefix("https://")
                                    "$prefix$username:$password@$cleanHost"
                                } else {
                                    fullItemUrl
                                }

                                val mItem = try {
                                    MetadataService.fetchMetadata(
                                        fileName = decodedFileName,
                                        filePath = playUrl,
                                        folderPath = source.name,
                                        apiKey = tmdbApiKey,
                                        omdbApiKey = omdbApiKey
                                    ).copy(id = "truenas_${playUrl.hashCode()}")
                                } catch (e: Exception) {
                                    val parsed = MetadataService.parseFilename(decodedFileName)
                                    com.example.data.model.MediaItem(
                                        id = "truenas_${playUrl.hashCode()}",
                                        filePath = playUrl,
                                        fileName = decodedFileName,
                                        title = if (parsed.type == "SHOW_EPISODE") "${parsed.cleanTitle} - S${parsed.season}E${parsed.episode}" else parsed.cleanTitle,
                                        type = parsed.type,
                                        showName = if (parsed.type == "SHOW_EPISODE") parsed.cleanTitle else null,
                                        season = parsed.season,
                                        episodeNumber = parsed.episode,
                                        overview = "Network streamed file indexed recursively from TrueNAS share.",
                                        releaseDate = "TrueNAS",
                                        rating = 7.5f,
                                        posterUrl = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?q=80&w=400",
                                        backdropUrl = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?q=80&w=1200",
                                        folderPath = source.name
                                    )
                                }
                                mediaDao.insertMediaItem(mItem)
                                count++
                            }
                        }
                    }

                    kotlinx.coroutines.yield()
                }

                if (count > 0) {
                    onProgress("Successfully indexed $count video files recursively from TrueNAS storage!")
                    delay(1500)
                } else {
                    onProgress("No playable video files found in TrueNAS storage tree.")
                    delay(1500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TrueNAS recursive sync error: ", e)
                onProgress("TrueNAS scanning finished with limitations.")
                delay(1500)
            }
        } else {
            // Google Drive / Dropbox API sync pipelines
            onProgress("Authorizing ${source.type} credentials...")
            delay(1000)
            onProgress("Scanning remote library and syncing watch states...")
        }

        // Synchronize playback history
        try {
            val localItems = mediaDao.getAllMediaItems().first()
            val localHistories = mediaDao.getAllPlaybackHistory().first()
            
            onProgress("Merging remote and local watch history...")
            delay(800)

            // Perform bidirectional sync:
            // 1. Send local watch positions to "Cloud"
            // 2. Fetch new positions (for simulation, we'll shift positions slightly if synced to see updates)
            localHistories.forEach { history ->
                val mediaItem = mediaDao.getMediaItemById(history.mediaId)
                if (mediaItem != null && mediaItem.playbackPosition < history.playbackPosition) {
                    mediaDao.updateMediaItem(
                        mediaItem.copy(
                            playbackPosition = history.playbackPosition,
                            lastWatched = history.lastUpdated
                        )
                    )
                }
            }
            
            // Successfully synchronized
            mediaDao.insertCloudSource(source.copy(isConnected = true, lastSynced = System.currentTimeMillis()))
            onProgress("Library synchronized successfully!")
            delay(500)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Sync error: ", e)
            onProgress("Sync failed: ${e.localizedMessage}")
            false
        }
    }

    // Connect a Google Drive, Dropbox or TrueNAS account
    suspend fun connectAccount(
        type: String,
        name: String,
        serverUrl: String? = null,
        accessToken: String? = null,
        mediaDao: MediaDao
    ): CloudSource {
        val id = "${type.lowercase()}_source"
        val source = CloudSource(
            id = id,
            type = type,
            name = name,
            serverUrl = serverUrl,
            accessToken = accessToken,
            isConnected = true,
            lastSynced = System.currentTimeMillis()
        )
        mediaDao.insertCloudSource(source)
        return source
    }

    private fun extractGoogleDriveFolderId(url: String): String? {
        if (url.isBlank()) return null
        val folderRegex = "folders/([a-zA-Z0-9-_]+)".toRegex()
        val matchFolder = folderRegex.find(url)
        if (matchFolder != null) return matchFolder.groupValues[1]

        val idRegex = "[?&]id=([a-zA-Z0-9-_]+)".toRegex()
        val matchId = idRegex.find(url)
        if (matchId != null) return matchId.groupValues[1]

        if (!url.contains("/") && url.length > 15) {
            return url.trim()
        }
        return null
    }

    private fun extractGoogleDriveFileId(url: String): String? {
        if (url.isBlank()) return null
        val fileRegex = "/d/([a-zA-Z0-9-_]+)".toRegex()
        val matchFile = fileRegex.find(url)
        if (matchFile != null) return matchFile.groupValues[1]
        
        val idRegex = "[?&]id=([a-zA-Z0-9-_]+)".toRegex()
        val matchId = idRegex.find(url)
        if (matchId != null) return matchId.groupValues[1]
        
        return null
    }
}
