package com.example.data.service

import android.util.Log
import com.example.data.database.MediaDao
import com.example.data.model.CloudSource
import com.example.data.model.MediaItem
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.EnumSet
import java.util.Stack
import java.util.concurrent.TimeUnit

data class SmbTarget(
    val host: String,
    val port: Int = 445,
    val shareName: String,
    val subPath: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = ""
)

object SmbNetworkService {
    private const val TAG = "SmbNetworkService"
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "ts", "flv", "wmv")

    private fun createClient(): SMBClient {
        val config = SmbConfig.builder()
            .withTimeout(12, TimeUnit.SECONDS)
            .withSoTimeout(20, TimeUnit.SECONDS)
            .build()
        return SMBClient(config)
    }

    fun buildSmbUri(
        host: String,
        port: Int = 445,
        shareName: String,
        subPath: String = "",
        username: String = "",
        password: String = ""
    ): String {
        val cleanHost = host.trim().removePrefix("smb://").substringBefore("/")
        val cleanShare = shareName.trim().trim('/')
        val cleanPath = subPath.trim().trimStart('/')
        val authPart = if (username.isNotBlank()) {
            val encUser = URLEncoder.encode(username, "UTF-8")
            val encPass = URLEncoder.encode(password, "UTF-8")
            if (password.isNotBlank()) "$encUser:$encPass@" else "$encUser@"
        } else {
            ""
        }
        val portPart = if (port != 445 && port > 0) ":$port" else ""
        val pathPart = if (cleanPath.isNotEmpty()) "/$cleanPath" else ""
        return "smb://$authPart$cleanHost$portPart/$cleanShare$pathPart"
    }

    fun parseSmbUri(rawUri: String): SmbTarget? {
        return try {
            val normalized = if (rawUri.startsWith("smb://", ignoreCase = true)) rawUri else "smb://$rawUri"
            val afterScheme = normalized.substring(6)
            val atIdx = afterScheme.lastIndexOf('@')
            var username = ""
            var password = ""
            var domain = ""
            val hostAndPath = if (atIdx != -1) {
                val creds = afterScheme.substring(0, atIdx)
                val colonIdx = creds.indexOf(':')
                val rawUser = if (colonIdx != -1) creds.substring(0, colonIdx) else creds
                val rawPass = if (colonIdx != -1) creds.substring(colonIdx + 1) else ""
                val decodedUser = URLDecoder.decode(rawUser, "UTF-8")
                if (decodedUser.contains(";")) {
                    domain = decodedUser.substringBefore(";")
                    username = decodedUser.substringAfter(";")
                } else if (decodedUser.contains("\\")) {
                    domain = decodedUser.substringBefore("\\")
                    username = decodedUser.substringAfter("\\")
                } else {
                    username = decodedUser
                }
                password = URLDecoder.decode(rawPass, "UTF-8")
                afterScheme.substring(atIdx + 1)
            } else {
                afterScheme
            }

            val slashIdx = hostAndPath.indexOf('/')
            val hostPort = if (slashIdx != -1) hostAndPath.substring(0, slashIdx) else hostAndPath
            val fullPath = if (slashIdx != -1) hostAndPath.substring(slashIdx + 1).trimStart('/') else ""

            val colonPortIdx = hostPort.lastIndexOf(':')
            val host = if (colonPortIdx != -1) hostPort.substring(0, colonPortIdx) else hostPort
            val port = if (colonPortIdx != -1) hostPort.substring(colonPortIdx + 1).toIntOrNull() ?: 445 else 445

            val shareSlash = fullPath.indexOf('/')
            val shareName = if (shareSlash != -1) fullPath.substring(0, shareSlash) else fullPath
            val subPath = if (shareSlash != -1) fullPath.substring(shareSlash + 1) else ""

            if (host.isBlank() || shareName.isBlank()) return null

            SmbTarget(
                host = host,
                port = port,
                shareName = URLDecoder.decode(shareName, "UTF-8"),
                subPath = subPath,
                username = username,
                password = password,
                domain = domain
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SMB URI: $rawUri", e)
            null
        }
    }

    fun createAuthContext(target: SmbTarget): AuthenticationContext {
        return when {
            target.username.isBlank() -> AuthenticationContext.anonymous()
            target.username.equals("guest", ignoreCase = true) && target.password.isBlank() ->
                AuthenticationContext.guest()
            else -> AuthenticationContext(
                target.username,
                target.password.toCharArray(),
                target.domain.ifBlank { null }
            )
        }
    }

    suspend fun testConnection(smbUri: String): Result<String> = withContext(Dispatchers.IO) {
        val target = parseSmbUri(smbUri)
            ?: return@withContext Result.failure(IllegalArgumentException("Enter host and share name (e.g. smb://192.168.1.50/Movies)"))

        var client: SMBClient? = null
        try {
            client = createClient()
            client.connect(target.host, target.port).use { connection ->
                val auth = createAuthContext(target)
                connection.authenticate(auth).use { session ->
                    session.connectShare(target.shareName).use { share ->
                        if (share !is DiskShare) {
                            return@withContext Result.failure(IllegalStateException("'${target.shareName}' is not a file share."))
                        }
                        val entries = share.list(target.subPath.replace('/', '\\').trim('\\'))
                        val visibleCount = entries.count { it.fileName != "." && it.fileName != ".." }
                        Result.success("Connected to //${target.host}/${target.shareName} ($visibleCount items visible)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMB test connection failed", e)
            val msg = e.localizedMessage ?: e.javaClass.simpleName
            Result.failure(Exception("SMB connection failed: $msg"))
        } finally {
            try {
                client?.close()
            } catch (_: Exception) {}
        }
    }

    suspend fun scanSmbShare(
        source: CloudSource,
        mediaDao: MediaDao,
        tmdbApiKey: String? = null,
        omdbApiKey: String? = null,
        onProgress: (String) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        val rawUrl = source.serverUrl ?: return@withContext 0
        val target = parseSmbUri(rawUrl)
            ?: throw IllegalArgumentException("Invalid SMB share address: $rawUrl")

        onProgress("Connecting to SMB //${target.host}/${target.shareName}...")
        val existingPaths = mediaDao.getAllFilePaths().toHashSet()
        val discoveredFiles = mutableListOf<Pair<String, String>>() // smbUri to fileName

        val client = createClient()
        try {
            client.connect(target.host, target.port).use { connection ->
                val auth = createAuthContext(target)
                connection.authenticate(auth).use { session ->
                    session.connectShare(target.shareName).use { share ->
                        if (share !is DiskShare) {
                            throw IllegalStateException("'${target.shareName}' is not a file share.")
                        }

                        val rootSubPath = target.subPath.trim('/').replace('/', '\\')
                        val dirStack = Stack<String>()
                        dirStack.push(rootSubPath)

                        while (dirStack.isNotEmpty()) {
                            val currentRelPath = dirStack.pop()
                            val displayDir = currentRelPath.ifBlank { "/" }.replace('\\', '/')
                            onProgress("Browsing SMB: $displayDir")

                            val entries = try {
                                share.list(currentRelPath)
                            } catch (e: Exception) {
                                Log.w(TAG, "Cannot list SMB directory '$currentRelPath': ${e.message}")
                                emptyList()
                            }

                            for (entry in entries) {
                                val name = entry.fileName
                                if (name == "." || name == ".." || name.startsWith(".")) continue

                                val childRelPath = if (currentRelPath.isBlank()) name else "$currentRelPath\\$name"
                                val isDir = (entry.fileAttributes and 0x10L) != 0L // FILE_ATTRIBUTE_DIRECTORY

                                if (isDir) {
                                    dirStack.push(childRelPath)
                                } else {
                                    val ext = name.substringAfterLast('.', "").lowercase()
                                    if (ext in VIDEO_EXTENSIONS) {
                                        val urlPath = childRelPath.replace('\\', '/')
                                        val itemUri = buildSmbUri(
                                            host = target.host,
                                            port = target.port,
                                            shareName = target.shareName,
                                            subPath = urlPath,
                                            username = target.username,
                                            password = target.password
                                        )
                                        discoveredFiles.add(itemUri to name)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            try {
                client.close()
            } catch (_: Exception) {}
        }

        val newFiles = discoveredFiles.filter { (uri, _) -> !existingPaths.contains(uri) }
        onProgress("Found ${discoveredFiles.size} videos (${newFiles.size} new) on ${source.name}")

        if (newFiles.isNotEmpty()) {
            val semaphore = Semaphore(4)
            coroutineScope {
                newFiles.mapIndexed { idx, (smbUri, fileName) ->
                    launch {
                        semaphore.withPermit {
                            onProgress("Indexing SMB [${idx + 1}/${newFiles.size}]: $fileName")
                            val item = try {
                                MetadataService.fetchMetadata(
                                    fileName = fileName,
                                    filePath = smbUri,
                                    folderPath = source.name,
                                    apiKey = tmdbApiKey,
                                    omdbApiKey = omdbApiKey
                                ).copy(id = "smb_${smbUri.hashCode()}")
                            } catch (e: Exception) {
                                val parsed = MetadataService.parseFilename(fileName)
                                MediaItem(
                                    id = "smb_${smbUri.hashCode()}",
                                    filePath = smbUri,
                                    fileName = fileName,
                                    title = if (parsed.type == "SHOW_EPISODE") "${parsed.cleanTitle} - S${parsed.season}E${parsed.episode}" else parsed.cleanTitle,
                                    type = parsed.type,
                                    showName = if (parsed.type == "SHOW_EPISODE") parsed.cleanTitle else null,
                                    season = parsed.season,
                                    episodeNumber = parsed.episode,
                                    overview = "Network video streamed via SMB from ${source.name}.",
                                    releaseDate = "SMB Network",
                                    rating = 7.5f,
                                    posterUrl = MetadataService.CINEMATIC_STILLS[kotlin.math.abs(fileName.hashCode()) % MetadataService.CINEMATIC_STILLS.size],
                                    backdropUrl = MetadataService.CINEMATIC_STILLS[kotlin.math.abs(smbUri.hashCode()) % MetadataService.CINEMATIC_STILLS.size],
                                    folderPath = source.name
                                )
                            }
                            mediaDao.insertMediaItem(item)
                        }
                    }
                }
            }
        }

        discoveredFiles.size
    }

    suspend fun downloadSmbFile(
        smbUri: String,
        destFile: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val target = parseSmbUri(smbUri) ?: return@withContext false
        val client = createClient()
        try {
            client.connect(target.host, target.port).use { connection ->
                val auth = createAuthContext(target)
                connection.authenticate(auth).use { session ->
                    session.connectShare(target.shareName).use { share ->
                        if (share !is DiskShare) return@withContext false
                        val smbPath = target.subPath.replace('/', '\\').trim('\\')
                        share.openFile(
                            smbPath,
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null
                        ).use { remoteFile ->
                            val totalBytes = remoteFile.fileInformation.standardInformation.endOfFile
                            val buffer = ByteArray(128 * 1024)
                            var offset = 0L
                            destFile.outputStream().use { out ->
                                while (true) {
                                    val read = remoteFile.read(buffer, offset, 0, buffer.size)
                                    if (read <= 0) break
                                    out.write(buffer, 0, read)
                                    offset += read
                                    if (totalBytes > 0) {
                                        onProgress((offset.toFloat() / totalBytes.toFloat()).coerceIn(0.01f, 0.99f))
                                    }
                                }
                            }
                            true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMB download failed for $smbUri", e)
            if (destFile.exists()) destFile.delete()
            false
        } finally {
            try {
                client.close()
            } catch (_: Exception) {}
        }
    }
}
