package com.example.data.service

import android.content.Context
import android.util.Log
import com.example.data.database.MediaDao
import com.example.data.model.CloudSource
import com.example.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Stack
import java.util.concurrent.TimeUnit

object CloudSyncService {
    private const val TAG = "CloudSyncService"
    private val VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".avi", ".webm", ".mov", ".m4v", ".ts", ".flv", ".wmv")
    val client: OkHttpClient by lazy { getUnsafeOkHttpClient() }

    fun getUnsafeOkHttpClient(): OkHttpClient {
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
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            OkHttpClient()
        }
    }

    /**
     * Extracts URL-encoded Basic Auth credentials from a URL like http://user:pass@host/path
     * returning Pair(cleanUrlWithoutCreds, authHeaderOrNull).
     */
    fun extractUrlAndAuth(rawUrl: String): Pair<String, String?> {
        if (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true)) {
            return rawUrl to null
        }
        val schemeIdx = rawUrl.indexOf("://")
        if (schemeIdx == -1) return rawUrl to null
        val scheme = rawUrl.substring(0, schemeIdx + 3)
        val afterScheme = rawUrl.substring(schemeIdx + 3)
        val slashIdx = afterScheme.indexOf('/')
        val authority = if (slashIdx != -1) afterScheme.substring(0, slashIdx) else afterScheme
        val pathPart = if (slashIdx != -1) afterScheme.substring(slashIdx) else ""

        val atIdx = authority.lastIndexOf('@')
        if (atIdx == -1) return rawUrl to null

        val creds = authority.substring(0, atIdx)
        val hostPort = authority.substring(atIdx + 1)
        val colonIdx = creds.indexOf(':')
        val username = try {
            URLDecoder.decode(if (colonIdx != -1) creds.substring(0, colonIdx) else creds, "UTF-8")
        } catch (_: Exception) {
            if (colonIdx != -1) creds.substring(0, colonIdx) else creds
        }
        val password = try {
            URLDecoder.decode(if (colonIdx != -1) creds.substring(colonIdx + 1) else "", "UTF-8")
        } catch (_: Exception) {
            if (colonIdx != -1) creds.substring(colonIdx + 1) else ""
        }

        val cleanUrl = "$scheme$hostPort$pathPart"
        val authHeader = if (username.isNotBlank()) Credentials.basic(username, password) else null
        return cleanUrl to authHeader
    }

    fun buildAuthenticatedHttpUrl(baseUrl: String, username: String, password: String): String {
        val trimmed = baseUrl.trim()
        val withScheme = if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            "http://$trimmed"
        } else {
            trimmed
        }
        val (cleanUrl, _) = extractUrlAndAuth(withScheme)
        if (username.isBlank()) return cleanUrl
        val scheme = if (cleanUrl.startsWith("https://", ignoreCase = true)) "https://" else "http://"
        val hostAndPath = cleanUrl.removePrefix("https://").removePrefix("http://")
        val encUser = URLEncoder.encode(username.trim(), "UTF-8")
        val encPass = URLEncoder.encode(password, "UTF-8")
        val authPart = if (password.isNotEmpty()) "$encUser:$encPass@" else "$encUser@"
        return "$scheme$authPart$hostAndPath"
    }

    suspend fun testWebDavOrHttpConnection(assembledUrl: String): Result<String> = withContext(Dispatchers.IO) {
        val (cleanUrl, authHeader) = extractUrlAndAuth(assembledUrl)
        try {
            // Try WebDAV PROPFIND first
            val bodyXml = """
                <?xml version="1.0" encoding="utf-8" ?>
                <D:propfind xmlns:D="DAV:">
                  <D:prop>
                    <D:displayname/>
                    <D:resourcetype/>
                  </D:prop>
                </D:propfind>
            """.trimIndent()
            val propfindReq = Request.Builder()
                .url(cleanUrl)
                .method("PROPFIND", bodyXml.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .header("Depth", "1")
                .apply { if (authHeader != null) header("Authorization", authHeader) }
                .build()

            client.newCall(propfindReq).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 207) {
                    return@withContext Result.success("WebDAV connected (HTTP ${resp.code})")
                }
                if (resp.code == 401 || resp.code == 403) {
                    return@withContext Result.failure(Exception("Authentication failed (HTTP ${resp.code}). Check username & password."))
                }
            }

            // Fallback to GET / HEAD for plain HTTP file servers
            val getReq = Request.Builder()
                .url(cleanUrl)
                .apply { if (authHeader != null) header("Authorization", authHeader) }
                .build()
            client.newCall(getReq).execute().use { resp ->
                when {
                    resp.isSuccessful -> Result.success("HTTP server reachable (HTTP ${resp.code})")
                    resp.code == 401 || resp.code == 403 ->
                        Result.failure(Exception("Authentication failed (HTTP ${resp.code}). Check credentials."))
                    else ->
                        Result.failure(Exception("Server responded with HTTP ${resp.code} at $cleanUrl"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed for $cleanUrl", e)
            Result.failure(Exception("Cannot reach server: ${e.localizedMessage ?: e.javaClass.simpleName}"))
        }
    }

    suspend fun syncWithCloud(
        context: Context,
        source: CloudSource,
        mediaDao: MediaDao,
        tmdbApiKey: String? = null,
        omdbApiKey: String? = null,
        onProgress: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress("Connecting to ${source.name}...")

        when (source.type) {
            "SMB" -> {
                return@withContext try {
                    val total = SmbNetworkService.scanSmbShare(
                        source = source,
                        mediaDao = mediaDao,
                        tmdbApiKey = tmdbApiKey,
                        omdbApiKey = omdbApiKey,
                        onProgress = onProgress
                    )
                    syncPlaybackHistories(mediaDao)
                    mediaDao.insertCloudSource(source.copy(isConnected = true, lastSynced = System.currentTimeMillis()))
                    onProgress(
                        if (total > 0) "SMB sync complete: $total video(s) ready to stream!"
                        else "Connected to ${source.name}, no video files found in share."
                    )
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "SMB sync error", e)
                    onProgress("SMB error: ${e.localizedMessage ?: "Connection failed"}")
                    false
                }
            }

            "DIRECT_URL" -> {
                return@withContext try {
                    val streamUrl = source.serverUrl?.trim()
                        ?: throw IllegalArgumentException("Stream URL is empty")
                    val rawFileName = try {
                        URLDecoder.decode(URI(streamUrl).path.substringAfterLast('/'), "UTF-8")
                            .ifBlank { "${source.name}.mp4" }
                    } catch (_: Exception) {
                        "${source.name}.mp4"
                    }
                    val item = MetadataService.fetchMetadata(
                        fileName = rawFileName,
                        filePath = streamUrl,
                        folderPath = source.name,
                        apiKey = tmdbApiKey,
                        omdbApiKey = omdbApiKey
                    ).copy(id = "webdav_${streamUrl.hashCode()}")
                    mediaDao.insertMediaItem(item)
                    mediaDao.insertCloudSource(source.copy(isConnected = true, lastSynced = System.currentTimeMillis()))
                    onProgress("Added stream '${item.title}' ready to play!")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Direct URL sync error", e)
                    onProgress("Failed to add stream URL: ${e.localizedMessage}")
                    false
                }
            }

            "GOOGLE_DRIVE" -> {
                return@withContext syncGoogleDrive(source, mediaDao, tmdbApiKey, omdbApiKey, onProgress)
            }

            else -> {
                // TRUENAS / WEBDAV / HTTP_DIR
                return@withContext syncWebDavOrHttp(source, mediaDao, tmdbApiKey, omdbApiKey, onProgress)
            }
        }
    }

    private suspend fun syncWebDavOrHttp(
        source: CloudSource,
        mediaDao: MediaDao,
        tmdbApiKey: String?,
        omdbApiKey: String?,
        onProgress: (String) -> Unit
    ): Boolean {
        val rawUrl = source.serverUrl?.trim() ?: ""
        if (rawUrl.isBlank()) {
            onProgress("Server URL is empty.")
            return false
        }

        val (cleanRootUrl, authHeader) = extractUrlAndAuth(rawUrl)
        val credsPrefix = if (rawUrl.contains("@") && rawUrl.contains("://")) {
            val schemeEnd = rawUrl.indexOf("://") + 3
            val afterScheme = rawUrl.substring(schemeEnd)
            val atIdx = afterScheme.substringBefore("/").lastIndexOf('@')
            if (atIdx != -1) afterScheme.substring(0, atIdx + 1) else ""
        } else {
            ""
        }

        val existingPaths = mediaDao.getAllFilePaths().toHashSet()
        val folderQueue = Stack<String>()
        folderQueue.push(cleanRootUrl)
        val scannedNormalizedPaths = mutableSetOf<String>()
        val discovered = mutableListOf<Pair<String, String>>() // playUrl to decodedFileName
        var authError = false
        var lastErrorMsg: String? = null

        fun normalizeForCompare(urlStr: String): String {
            return try {
                val u = URI(urlStr)
                val decPath = URLDecoder.decode(u.path ?: "/", "UTF-8").trimEnd('/')
                "${u.scheme?.lowercase()}://${u.authority?.lowercase()}$decPath"
            } catch (_: Exception) {
                urlStr.trimEnd('/').lowercase()
            }
        }

        try {
            while (folderQueue.isNotEmpty()) {
                val currentUrl = folderQueue.pop()
                val normCurrent = normalizeForCompare(currentUrl)
                if (!scannedNormalizedPaths.add(normCurrent)) continue

                val relativePath = try {
                    URLDecoder.decode(URI(currentUrl).path ?: "/", "UTF-8")
                } catch (_: Exception) {
                    currentUrl.substringAfter("://").substringAfter("/", "/")
                }
                onProgress("Scanning ${source.name}: $relativePath")

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
                if (authHeader != null) reqBuilder.header("Authorization", authHeader)

                var isWebdav = false
                var responseBody: String? = null

                try {
                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        if (resp.code == 401 || resp.code == 403) {
                            authError = true
                            lastErrorMsg = "HTTP ${resp.code} Unauthorized"
                        } else if (resp.isSuccessful || resp.code == 207) {
                            val contentType = resp.header("Content-Type") ?: ""
                            val bodyText = resp.body?.string()
                            if (resp.code == 207 || contentType.contains("xml", ignoreCase = true) ||
                                (bodyText?.contains("<multistatus", ignoreCase = true) == true) ||
                                (bodyText?.contains(":multistatus", ignoreCase = true) == true)
                            ) {
                                isWebdav = true
                                responseBody = bodyText
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "PROPFIND attempt failed on $currentUrl: ${e.message}")
                    lastErrorMsg = e.localizedMessage
                }

                if (authError) break

                if (!isWebdav) {
                    val getBuilder = Request.Builder().url(currentUrl)
                    if (authHeader != null) getBuilder.header("Authorization", authHeader)
                    try {
                        client.newCall(getBuilder.build()).execute().use { resp ->
                            if (resp.code == 401 || resp.code == 403) {
                                authError = true
                                lastErrorMsg = "HTTP ${resp.code} Unauthorized"
                            } else if (resp.isSuccessful) {
                                responseBody = resp.body?.string()
                            } else {
                                lastErrorMsg = "HTTP ${resp.code}"
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "GET failed for $currentUrl", e)
                        lastErrorMsg = e.localizedMessage
                    }
                }

                if (authError || responseBody.isNullOrBlank()) continue

                if (isWebdav) {
                    val responseRegex = "<(?:\\w+:)?response(\\s+[^>]*)?>(.*?)</(?:\\w+:)?response>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    val hrefRegex = "<(?:\\w+:)?href(\\s+[^>]*)?>(.*?)</(?:\\w+:)?href>".toRegex(RegexOption.IGNORE_CASE)

                    for (match in responseRegex.findAll(responseBody!!)) {
                        val block = match.groupValues[2]
                        val rawHref = hrefRegex.find(block)?.groupValues?.get(2)?.trim() ?: continue

                        val fullItemUrl = resolveHref(currentUrl, rawHref) ?: continue
                        if (normalizeForCompare(fullItemUrl) == normCurrent) continue

                        val isCollection = block.contains("<collection", ignoreCase = true) ||
                            block.contains(":collection", ignoreCase = true) ||
                            rawHref.endsWith("/")
                        val fileNameRaw = rawHref.removeSuffix("/").substringAfterLast("/")
                        val decodedFileName = try {
                            URLDecoder.decode(fileNameRaw, "UTF-8")
                        } catch (_: Exception) {
                            fileNameRaw
                        }
                        if (decodedFileName.isBlank()) continue

                        if (isCollection) {
                            folderQueue.push(fullItemUrl)
                        } else if (VIDEO_EXTENSIONS.any { decodedFileName.lowercase().endsWith(it) }) {
                            val playUrl = injectCredentials(fullItemUrl, credsPrefix)
                            discovered.add(playUrl to decodedFileName)
                        }
                    }
                } else {
                    val linkRegex = "<a\\s+[^>]*href\\s*=\\s*[\"']?([^\"'>\\s]+)[\"']?[^>]*>(.*?)</a>".toRegex(RegexOption.IGNORE_CASE)
                    for (match in linkRegex.findAll(responseBody!!)) {
                        val rawHref = match.groupValues[1].trim()
                        if (rawHref.startsWith("?") || rawHref.startsWith("#") ||
                            rawHref.startsWith("mailto:", ignoreCase = true) ||
                            rawHref == "../" || rawHref == "./" || rawHref == ".." || rawHref == "."
                        ) continue

                        val fullItemUrl = resolveHref(currentUrl, rawHref) ?: continue
                        val normItem = normalizeForCompare(fullItemUrl)
                        if (normItem == normCurrent || !normItem.startsWith(normalizeForCompare(cleanRootUrl))) continue

                        val isDirectory = rawHref.endsWith("/")
                        val fileNameRaw = rawHref.removeSuffix("/").substringAfterLast("/")
                        val decodedFileName = try {
                            URLDecoder.decode(fileNameRaw, "UTF-8")
                        } catch (_: Exception) {
                            fileNameRaw
                        }
                        if (decodedFileName.isBlank()) continue

                        if (isDirectory) {
                            folderQueue.push(fullItemUrl)
                        } else if (VIDEO_EXTENSIONS.any { decodedFileName.lowercase().endsWith(it) }) {
                            val playUrl = injectCredentials(fullItemUrl, credsPrefix)
                            discovered.add(playUrl to decodedFileName)
                        }
                    }
                }
                kotlinx.coroutines.yield()
            }

            if (authError) {
                onProgress("Authentication failed ($lastErrorMsg). Verify username and password.")
                return false
            }

            val newFiles = discovered.filter { (url, _) -> !existingPaths.contains(url) }
            if (newFiles.isNotEmpty()) {
                val semaphore = Semaphore(4)
                coroutineScope {
                    newFiles.forEachIndexed { idx, (playUrl, decodedFileName) ->
                        launch {
                            semaphore.withPermit {
                                onProgress("Indexing [${idx + 1}/${newFiles.size}]: $decodedFileName")
                                val itemId = "webdav_${playUrl.hashCode()}"
                                val mItem = try {
                                    MetadataService.fetchMetadata(
                                        fileName = decodedFileName,
                                        filePath = playUrl,
                                        folderPath = source.name,
                                        apiKey = tmdbApiKey,
                                        omdbApiKey = omdbApiKey
                                    ).copy(id = itemId)
                                } catch (e: Exception) {
                                    val parsed = MetadataService.parseFilename(decodedFileName)
                                    MediaItem(
                                        id = itemId,
                                        filePath = playUrl,
                                        fileName = decodedFileName,
                                        title = if (parsed.type == "SHOW_EPISODE") "${parsed.cleanTitle} - S${parsed.season}E${parsed.episode}" else parsed.cleanTitle,
                                        type = parsed.type,
                                        showName = if (parsed.type == "SHOW_EPISODE") parsed.cleanTitle else null,
                                        season = parsed.season,
                                        episodeNumber = parsed.episode,
                                        overview = "Network video streamed from ${source.name}.",
                                        releaseDate = source.type,
                                        rating = 7.5f,
                                        posterUrl = MetadataService.CINEMATIC_STILLS[kotlin.math.abs(decodedFileName.hashCode()) % MetadataService.CINEMATIC_STILLS.size],
                                        backdropUrl = MetadataService.CINEMATIC_STILLS[kotlin.math.abs(playUrl.hashCode()) % MetadataService.CINEMATIC_STILLS.size],
                                        folderPath = source.name
                                    )
                                }
                                mediaDao.insertMediaItem(mItem)
                            }
                        }
                    }
                }
            }

            syncPlaybackHistories(mediaDao)
            mediaDao.insertCloudSource(source.copy(isConnected = true, lastSynced = System.currentTimeMillis()))

            if (discovered.isEmpty()) {
                val reason = if (lastErrorMsg != null) " ($lastErrorMsg)" else ""
                onProgress("Connected, but no playable videos found$reason.")
                return lastErrorMsg == null
            }
            onProgress("Synced ${discovered.size} video(s) (${newFiles.size} new) from ${source.name}!")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV/HTTP sync error", e)
            onProgress("Sync failed: ${e.localizedMessage ?: "Check server address"}")
            return false
        }
    }

    private fun resolveHref(currentUrl: String, rawHref: String): String? {
        return try {
            if (rawHref.startsWith("http://", ignoreCase = true) || rawHref.startsWith("https://", ignoreCase = true)) {
                rawHref
            } else {
                val baseUri = URI(if (currentUrl.endsWith("/")) currentUrl else "$currentUrl/")
                baseUri.resolve(rawHref).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun injectCredentials(fullUrl: String, credsPrefix: String): String {
        if (credsPrefix.isBlank()) return fullUrl
        val scheme = if (fullUrl.startsWith("https://", ignoreCase = true)) "https://" else "http://"
        val cleanHost = fullUrl.removePrefix("https://").removePrefix("http://")
        return "$scheme$credsPrefix$cleanHost"
    }

    private suspend fun syncGoogleDrive(
        source: CloudSource,
        mediaDao: MediaDao,
        tmdbApiKey: String?,
        omdbApiKey: String?,
        onProgress: (String) -> Unit
    ): Boolean {
        val urlStr = source.serverUrl ?: ""
        val token = source.accessToken
        val folderId = extractGoogleDriveFolderId(urlStr)
        val fileId = extractGoogleDriveFileId(urlStr)
        val existingPaths = mediaDao.getAllFilePaths().toHashSet()

        if (folderId != null) {
            onProgress("Connecting to Google Drive folder...")
            val folderQueue = Stack<String>()
            folderQueue.push(folderId)
            var count = 0
            var hadAuthError = false

            try {
                while (folderQueue.isNotEmpty()) {
                    val currentFolderId = folderQueue.pop()
                    onProgress("Scanning Drive folder: $currentFolderId")
                    val builder = Request.Builder()
                    val q = "'$currentFolderId' in parents and trashed = false"
                    val encodedQ = URLEncoder.encode(q, "UTF-8")
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
                                val json = JSONObject(bodyStr)
                                val filesArr = json.optJSONArray("files")
                                if (filesArr != null) {
                                    for (i in 0 until filesArr.length()) {
                                        val f = filesArr.getJSONObject(i)
                                        val mimeType = f.optString("mimeType", "")
                                        val fId = f.getString("id")
                                        val fName = f.getString("name")

                                        if (mimeType == "application/vnd.google-apps.folder") {
                                            folderQueue.push(fId)
                                        } else if (mimeType.startsWith("video/") || mimeType.startsWith("audio/") ||
                                            VIDEO_EXTENSIONS.any { fName.lowercase().endsWith(it) }
                                        ) {
                                            val streamUrl = if (!token.isNullOrBlank() && (token.startsWith("ya29.") || token.contains("Bearer"))) {
                                                "https://www.googleapis.com/drive/v3/files/$fId?alt=media"
                                            } else {
                                                "https://drive.google.com/uc?export=download&id=$fId"
                                            }
                                            if (!existingPaths.contains(streamUrl)) {
                                                val mItem = try {
                                                    MetadataService.fetchMetadata(
                                                        fileName = fName,
                                                        filePath = streamUrl,
                                                        folderPath = source.name,
                                                        apiKey = tmdbApiKey,
                                                        omdbApiKey = omdbApiKey
                                                    ).copy(id = "gdrive_$fId")
                                                } catch (_: Exception) {
                                                    val parsed = MetadataService.parseFilename(fName)
                                                    MediaItem(
                                                        id = "gdrive_$fId",
                                                        filePath = streamUrl,
                                                        fileName = fName,
                                                        title = if (parsed.type == "SHOW_EPISODE") "${parsed.cleanTitle} - S${parsed.season}E${parsed.episode}" else parsed.cleanTitle,
                                                        type = parsed.type,
                                                        showName = if (parsed.type == "SHOW_EPISODE") parsed.cleanTitle else null,
                                                        season = parsed.season,
                                                        episodeNumber = parsed.episode,
                                                        overview = "Streamed from Google Drive.",
                                                        releaseDate = "Google Drive",
                                                        rating = 8.0f,
                                                        posterUrl = MetadataService.CINEMATIC_STILLS[0],
                                                        backdropUrl = MetadataService.CINEMATIC_STILLS[1],
                                                        folderPath = source.name
                                                    )
                                                }
                                                mediaDao.insertMediaItem(mItem)
                                            }
                                            count++
                                        }
                                    }
                                }
                            }
                        } else if (response.code == 403 || response.code == 401) {
                            hadAuthError = true
                        }
                    }
                    kotlinx.coroutines.yield()
                }

                syncPlaybackHistories(mediaDao)
                mediaDao.insertCloudSource(source.copy(isConnected = true, lastSynced = System.currentTimeMillis()))

                return if (hadAuthError) {
                    onProgress("Google Drive Error 403: Ensure shared folder is public or provide an OAuth/API token.")
                    false
                } else {
                    onProgress("Indexed $count video(s) from Google Drive!")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Google Drive folder sync error", e)
                onProgress("Google Drive sync error: ${e.localizedMessage}")
                return false
            }
        } else if (fileId != null) {
            val streamUrl = if (!token.isNullOrBlank() && (token.startsWith("ya29.") || token.contains("Bearer"))) {
                "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
            } else {
                "https://drive.google.com/uc?export=download&id=$fileId"
            }
            val mItem = MediaItem(
                id = "gdrive_$fileId",
                filePath = streamUrl,
                fileName = "${source.name}.mp4",
                title = source.name.ifBlank { "Google Drive Video" },
                type = "MOVIE",
                overview = "Direct video streamed from Google Drive.",
                releaseDate = "Google Drive",
                rating = 8.5f,
                posterUrl = MetadataService.CINEMATIC_STILLS[0],
                backdropUrl = MetadataService.CINEMATIC_STILLS[1],
                folderPath = source.name
            )
            mediaDao.insertMediaItem(mItem)
            mediaDao.insertCloudSource(source.copy(isConnected = true, lastSynced = System.currentTimeMillis()))
            onProgress("Linked Google Drive video!")
            return true
        } else {
            onProgress("Invalid Google Drive URL or ID.")
            return false
        }
    }

    private suspend fun syncPlaybackHistories(mediaDao: MediaDao) {
        try {
            val localHistories = mediaDao.getAllPlaybackHistory().first()
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
        } catch (e: Exception) {
            Log.w(TAG, "History merge warning: ${e.message}")
        }
    }

    suspend fun connectAccount(
        type: String,
        name: String,
        serverUrl: String? = null,
        accessToken: String? = null,
        mediaDao: MediaDao
    ): CloudSource {
        val uniqueSuffix = (serverUrl ?: name).hashCode().toString()
        val id = "${type.lowercase()}_$uniqueSuffix"
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
        val matchFolder = "folders/([a-zA-Z0-9-_]+)".toRegex().find(url)
        if (matchFolder != null) return matchFolder.groupValues[1]
        val matchId = "[?&]id=([a-zA-Z0-9-_]+)".toRegex().find(url)
        if (matchId != null && url.contains("drive", ignoreCase = true) && !url.contains("/file/")) return matchId.groupValues[1]
        if (!url.contains("/") && url.length > 15) return url.trim()
        return null
    }

    private fun extractGoogleDriveFileId(url: String): String? {
        if (url.isBlank()) return null
        val matchFile = "/d/([a-zA-Z0-9-_]+)".toRegex().find(url)
        if (matchFile != null) return matchFile.groupValues[1]
        val matchId = "[?&]id=([a-zA-Z0-9-_]+)".toRegex().find(url)
        if (matchId != null) return matchId.groupValues[1]
        return null
    }
}
