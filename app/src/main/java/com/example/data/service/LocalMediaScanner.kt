package com.example.data.service

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.data.database.MediaDao
import com.example.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

object LocalMediaScanner {
    private const val TAG = "LocalMediaScanner"

    // Scan a local directory and insert items to Room DB using an iterative, chunked approach
    suspend fun scanDirectory(
        context: Context,
        directory: File,
        mediaDao: MediaDao,
        tmdbApiKey: String? = null,
        omdbApiKey: String? = null,
        onProgress: (String) -> Unit = {}
    ): Unit {
        withContext(Dispatchers.IO) {
            if (!directory.exists() || !directory.isDirectory) {
                Log.w(TAG, "Directory does not exist or is not a directory: ${directory.absolutePath}")
                return@withContext
            }

            val videoExtensions = listOf("mp4", "mkv", "avi", "webm", "mov", "m4v")
            val candidateFiles = mutableListOf<File>()
            val dirStack = java.util.Stack<File>()
            dirStack.push(directory)

            // Step 1: Iterative traversal to gather all files (avoids deep recursive stack overflow)
            while (!dirStack.isEmpty()) {
                val currentDir = dirStack.pop()
                onProgress("Indexing folder: ${currentDir.absolutePath}")

                val files = currentDir.listFiles()
                if (files != null) {
                    for (file in files) {
                        if (file.isDirectory) {
                            dirStack.push(file)
                        } else {
                            val ext = file.extension.lowercase()
                            if (videoExtensions.contains(ext)) {
                                candidateFiles.add(file)
                            }
                        }
                    }
                }
                kotlinx.coroutines.yield() // Allow UI to update
            }

            // Step 2: Process files in a chunked, asynchronous manner with yields
            val totalFiles = candidateFiles.size
            for ((index, file) in candidateFiles.withIndex()) {
                val filePath = file.absolutePath
                onProgress("Parsing [${index + 1}/$totalFiles]: ${file.parentFile?.name ?: ""}/${file.name}")

                val nameLower = file.name.lowercase()
                val isRandom = listOf(
                    "vid_", "pxl_", "img_", "whatsapp", "zoom", "meeting_", 
                    "screen-capture", "screencast", "recording", "camera", "untitled", "test", "dummy", "capture"
                ).any { nameLower.contains(it) }

                val existing = mediaDao.getMediaItemByFilePath(filePath)
                val needsUpdate = existing != null && (
                    existing.type == "OTHER" || 
                    existing.posterUrl.isNullOrBlank() || 
                    existing.posterUrl?.contains("unsplash.com") == true ||
                    existing.overview?.contains("locally indexed") == true
                )

                if (existing == null || needsUpdate) {
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

                // yield to keep thread pool active for UI rendering, delay slightly
                kotlinx.coroutines.yield()
                delay(30)
            }
        }
    }

    // Standard device scan using user-selected directories
    suspend fun scanDeviceStorage(
        context: Context,
        mediaDao: MediaDao,
        foldersList: List<String>,
        tmdbApiKey: String? = null,
        omdbApiKey: String? = null,
        onProgress: (String) -> Unit = {}
    ) {
        for (path in foldersList) {
            val dir = File(path)
            if (dir.exists()) {
                onProgress("Scanning directory: ${dir.name}")
                scanDirectory(context, dir, mediaDao, tmdbApiKey, omdbApiKey, onProgress)
            } else {
                Log.w(TAG, "Specified directory does not exist: $path")
            }
        }
    }

    // Populate a mock metadata-rich local catalog using streaming videos so users can test immediately.
    suspend fun populateSampleMediaHub(mediaDao: MediaDao) {
        // Demo data removed per user request
    }
}
