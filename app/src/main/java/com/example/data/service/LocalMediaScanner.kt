package com.example.data.service

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.example.data.database.MediaDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.Stack
import java.util.concurrent.atomic.AtomicInteger

data class ScanResult(
    val addedCount: Int,
    val removedCount: Int,
    val totalScannedFiles: Int
)

object LocalMediaScanner {
    private const val TAG = "LocalMediaScanner"
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "ts", "flv", "wmv")

    /**
     * Collects all video files inside the configured local folders using both fast iterative
     * filesystem traversal and Android MediaStore query (catches scoped/newly-downloaded media).
     */
    private suspend fun collectLocalVideoFiles(
        context: Context,
        foldersList: List<String>,
        includeMediaStoreAll: Boolean = false
    ): List<File> = withContext(Dispatchers.IO) {
        val uniquePaths = LinkedHashMap<String, File>()

        // 1. Direct filesystem traversal of configured folders
        for (path in foldersList) {
            val rootDir = File(path)
            if (!rootDir.exists() || !rootDir.isDirectory) continue

            val dirStack = Stack<File>()
            dirStack.push(rootDir)

            while (dirStack.isNotEmpty()) {
                val currentDir = dirStack.pop()
                val files = currentDir.listFiles() ?: continue
                for (file in files) {
                    if (file.name.startsWith(".")) continue
                    if (file.isDirectory) {
                        dirStack.push(file)
                    } else {
                        val ext = file.extension.lowercase()
                        if (ext in VIDEO_EXTENSIONS) {
                            uniquePaths[file.absolutePath] = file
                        }
                    }
                }
                yield()
            }
        }

        // 2. Query MediaStore.Video.Media to immediately pick up newly downloaded/moved videos
        try {
            val projection = arrayOf(MediaStore.Video.Media.DATA)
            val normalizedFolders = foldersList.map { it.trimEnd('/') + "/" }
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                if (dataCol != -1) {
                    while (cursor.moveToNext()) {
                        val filePath = cursor.getString(dataCol) ?: continue
                        val inWatchedFolder = includeMediaStoreAll ||
                            normalizedFolders.any { folderPrefix ->
                                filePath.startsWith(folderPrefix, ignoreCase = true) ||
                                    filePath.substringBeforeLast('/') == folderPrefix.trimEnd('/')
                            }
                        if (inWatchedFolder && !uniquePaths.containsKey(filePath)) {
                            val file = File(filePath)
                            if (file.exists() && file.extension.lowercase() in VIDEO_EXTENSIONS) {
                                uniquePaths[file.absolutePath] = file
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore video query skipped/failed: ${e.message}")
        }

        uniquePaths.values.toList()
    }

    /**
     * Ultra-fast incremental delta scan:
     * - Loads all existing DB paths in 1 single query (O(1) HashSet lookup).
     * - Skips all already-indexed files immediately with zero delay or network calls.
     * - Only fetches metadata concurrently (up to 4 in parallel) for brand-new files.
     * - Prunes deleted local files that no longer exist on disk.
     */
    suspend fun scanIncremental(
        context: Context,
        mediaDao: MediaDao,
        foldersList: List<String>,
        tmdbApiKey: String? = null,
        omdbApiKey: String? = null,
        includeMediaStoreAll: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): ScanResult = withContext(Dispatchers.IO) {
        onProgress("Checking for new media files...")
        val existingPaths = mediaDao.getAllFilePaths().toHashSet()
        val diskFiles = collectLocalVideoFiles(context, foldersList, includeMediaStoreAll)
        val diskPathSet = diskFiles.mapTo(HashSet(diskFiles.size)) { it.absolutePath }

        // Prune local files that were deleted from watched folders
        val missingPaths = existingPaths.filter { path ->
            path.startsWith("/") &&
                !path.contains("/downloads/") &&
                !path.contains("/LuminaPlayer/") &&
                foldersList.any { folder -> path.startsWith(folder.trimEnd('/') + "/") } &&
                !diskPathSet.contains(path) &&
                !File(path).exists()
        }
        if (missingPaths.isNotEmpty()) {
            missingPaths.chunked(200).forEach { chunk ->
                mediaDao.deleteByFilePaths(chunk)
            }
        }

        // Only process files not already in Room
        val newFiles = diskFiles.filter { !existingPaths.contains(it.absolutePath) }
        if (newFiles.isEmpty()) {
            val msg = if (missingPaths.isNotEmpty()) {
                "Cleaned ${missingPaths.size} removed files. Library is up to date."
            } else {
                "Library up to date (${diskFiles.size} files)."
            }
            onProgress(msg)
            return@withContext ScanResult(0, missingPaths.size, diskFiles.size)
        }

        val totalNew = newFiles.size
        val processed = AtomicInteger(0)
        val added = AtomicInteger(0)
        val semaphore = Semaphore(4)

        coroutineScope {
            newFiles.forEach { file ->
                launch {
                    semaphore.withPermit {
                        val idx = processed.incrementAndGet()
                        onProgress("Adding new [$idx/$totalNew]: ${file.name}")
                        try {
                            val mediaItem = MetadataService.fetchMetadata(
                                fileName = file.name,
                                filePath = file.absolutePath,
                                folderPath = file.parent ?: "/local_storage",
                                apiKey = tmdbApiKey,
                                omdbApiKey = omdbApiKey
                            )
                            mediaDao.insertMediaItem(mediaItem)
                            added.incrementAndGet()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed parsing metadata for new file: ${file.absolutePath}", e)
                        }
                    }
                }
            }
        }

        onProgress("Added ${added.get()} new video(s) instantly.")
        ScanResult(added.get(), missingPaths.size, diskFiles.size)
    }

    /**
     * Full deep scan: refreshes missing/fallback metadata across all files in watched folders
     * using parallel workers without artificial per-file sleep delays.
     */
    suspend fun scanDeviceStorage(
        context: Context,
        mediaDao: MediaDao,
        foldersList: List<String>,
        tmdbApiKey: String? = null,
        omdbApiKey: String? = null,
        forceDeepRefresh: Boolean = true,
        onProgress: (String) -> Unit = {}
    ): ScanResult = withContext(Dispatchers.IO) {
        if (!forceDeepRefresh) {
            return@withContext scanIncremental(
                context = context,
                mediaDao = mediaDao,
                foldersList = foldersList,
                tmdbApiKey = tmdbApiKey,
                omdbApiKey = omdbApiKey,
                onProgress = onProgress
            )
        }

        onProgress("Indexing local directories...")
        val diskFiles = collectLocalVideoFiles(context, foldersList, includeMediaStoreAll = false)
        val totalFiles = diskFiles.size
        val processed = AtomicInteger(0)
        val addedCount = AtomicInteger(0)
        val semaphore = Semaphore(4)

        coroutineScope {
            diskFiles.forEach { file ->
                launch {
                    semaphore.withPermit {
                        val idx = processed.incrementAndGet()
                        val filePath = file.absolutePath
                        val existing = mediaDao.getMediaItemByFilePath(filePath)
                        val needsUpdate = existing != null && (
                            existing.type == "OTHER" ||
                                existing.posterUrl.isNullOrBlank() ||
                                existing.posterUrl?.contains("unsplash.com") == true ||
                                existing.overview?.contains("locally indexed") == true
                            )

                        if (existing == null || needsUpdate) {
                            onProgress("Enriching [$idx/$totalFiles]: ${file.name}")
                            try {
                                val mediaItem = MetadataService.fetchMetadata(
                                    fileName = file.name,
                                    filePath = filePath,
                                    folderPath = file.parent ?: "/local_storage",
                                    apiKey = tmdbApiKey,
                                    omdbApiKey = omdbApiKey
                                )
                                if (existing == null) {
                                    mediaDao.insertMediaItem(mediaItem)
                                    addedCount.incrementAndGet()
                                } else {
                                    val updated = existing.copy(
                                        title = mediaItem.title,
                                        type = mediaItem.type,
                                        showName = mediaItem.showName,
                                        season = mediaItem.season,
                                        episodeNumber = mediaItem.episodeNumber,
                                        overview = mediaItem.overview,
                                        releaseDate = mediaItem.releaseDate,
                                        rating = if (mediaItem.rating != null && mediaItem.rating > 0f) mediaItem.rating else existing.rating,
                                        posterUrl = mediaItem.posterUrl,
                                        backdropUrl = mediaItem.backdropUrl
                                    )
                                    mediaDao.updateMediaItem(updated)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed parsing metadata for: $filePath", e)
                            }
                        }
                    }
                }
            }
        }

        onProgress("Deep scan finished ($totalFiles files checked).")
        ScanResult(addedCount.get(), 0, totalFiles)
    }

    suspend fun populateSampleMediaHub(mediaDao: MediaDao) {
        // Intentionally empty — real local & network files only
    }
}
