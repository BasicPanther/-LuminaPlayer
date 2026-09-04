package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.service.SubtitleCue
import com.example.data.service.SubtitleParser
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.data.model.MediaItem as LuminaMediaItem
import com.example.ui.viewmodel.MediaViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// SRT Subtitle timing shift engine
fun shiftSrtTiming(srtContent: String, shiftMs: Int): String {
    if (shiftMs == 0) return srtContent
    val pattern = java.util.regex.Pattern.compile("(\\d{2}:\\d{2}:\\d{2}[,\\.]\\d{3})\\s+-->\\s+(\\d{2}:\\d{2}:\\d{2}[,\\.]\\d{3})")
    val matcher = pattern.matcher(srtContent)
    val sb = StringBuffer()
    
    fun parseToMs(timeStr: String): Long {
        val cleanTime = timeStr.replace('.', ',')
        val parts = cleanTime.split(":")
        val h = parts[0].toLong()
        val m = parts[1].toLong()
        val secParts = parts[2].split(",")
        val s = secParts[0].toLong()
        val ms = secParts[1].toLong()
        return h * 3600000 + m * 60000 + s * 1000 + ms
    }
    
    fun formatMs(msVal: Long): String {
        val positiveMs = msVal.coerceAtLeast(0L)
        val h = positiveMs / 3600000
        val m = (positiveMs % 3600000) / 60000
        val s = (positiveMs % 60000) / 1000
        val ms = positiveMs % 1000
        return String.format("%02d:%02d:%02d,%03d", h, m, s, ms)
    }
    
    while (matcher.find()) {
        val startTimeStr = matcher.group(1)
        val endTimeStr = matcher.group(2)
        try {
            val newStart = parseToMs(startTimeStr) + shiftMs
            val newEnd = parseToMs(endTimeStr) + shiftMs
            matcher.appendReplacement(sb, "${formatMs(newStart)} --> ${formatMs(newEnd)}")
        } catch (e: Exception) {
            matcher.appendReplacement(sb, matcher.group(0))
        }
    }
    matcher.appendTail(sb)
    return sb.toString()
}

@kotlin.OptIn(androidx.media3.common.util.UnstableApi::class)
fun getCaptionStyle(
    bgColorName: String,
    bgOpacity: Float,
    fontName: String
): androidx.media3.ui.CaptionStyleCompat {
    val baseColor = when (bgColorName) {
        "BLACK" -> android.graphics.Color.BLACK
        "DARK_GRAY" -> android.graphics.Color.DKGRAY
        "BLUE" -> android.graphics.Color.BLUE
        "RED" -> android.graphics.Color.RED
        "YELLOW" -> android.graphics.Color.YELLOW
        "GREEN" -> android.graphics.Color.GREEN
        else -> android.graphics.Color.TRANSPARENT
    }
    
    val finalBgColor = if (bgColorName == "DEFAULT") {
        android.graphics.Color.argb(128, 0, 0, 0)
    } else {
        val alpha = (bgOpacity * 255).toInt().coerceIn(0, 255)
        android.graphics.Color.argb(
            alpha,
            android.graphics.Color.red(baseColor),
            android.graphics.Color.green(baseColor),
            android.graphics.Color.blue(baseColor)
        )
    }
    
    val typeface = when (fontName) {
        "SERIF" -> android.graphics.Typeface.SERIF
        "SANS_SERIF" -> android.graphics.Typeface.SANS_SERIF
        "MONOSPACE" -> android.graphics.Typeface.MONOSPACE
        "DEFAULT" -> android.graphics.Typeface.DEFAULT
        else -> android.graphics.Typeface.DEFAULT
    }
    
    return androidx.media3.ui.CaptionStyleCompat(
        android.graphics.Color.WHITE,
        finalBgColor,
        android.graphics.Color.TRANSPARENT,
        androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
        android.graphics.Color.BLACK,
        typeface
    )
}

@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    viewModel: MediaViewModel,
    mediaItemId: String,
    onPlayNext: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // Subtitle Customization States
    val subBgColor by viewModel.subStyleBgColor.collectAsStateWithLifecycle()
    val subBgOpacity by viewModel.subStyleBgOpacity.collectAsStateWithLifecycle()
    val subTextSize by viewModel.subStyleTextSize.collectAsStateWithLifecycle()
    val subFont by viewModel.subStyleFont.collectAsStateWithLifecycle()

    // Live media collection
    val allMedia by viewModel.allMedia.collectAsStateWithLifecycle()
    val item = remember(allMedia, mediaItemId) {
        allMedia.find { it.id == mediaItemId }
    } ?: return

    val srtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val tempFile = java.io.File.createTempFile("sub_import", ".srt", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                viewModel.linkLocalSubtitleFile(context, item, tempFile)
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Failed to load subtitle: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val nextEpisode = remember(allMedia, item) {
        if (item.type == "SHOW_EPISODE") {
            val showEpisodes = allMedia.filter { 
                it.type == "SHOW_EPISODE" && it.showName == item.showName 
            }.sortedWith(compareBy({ it.season ?: 1 }, { it.episodeNumber ?: 1 }))
            
            val currentIndex = showEpisodes.indexOfFirst { it.id == item.id }
            if (currentIndex != -1 && currentIndex + 1 < showEpisodes.size) {
                showEpisodes[currentIndex + 1]
            } else {
                null
            }
        } else {
            null
        }
    }

    val autoplayEnabled by viewModel.autoplayEnabled.collectAsStateWithLifecycle()

    // Position and duration state tracked dynamically
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var showAutoplayCountdown by remember { mutableStateOf(true) }

    // ExoPlayer Instance
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var availableTracks by remember { mutableStateOf<androidx.media3.common.Tracks?>(null) }

    // Reset countdown and position trackers when media file changes
    LaunchedEffect(mediaItemId) {
        showAutoplayCountdown = true
        currentPosition = 0L
        duration = 0L
    }

    // Side Double-Tap HUD States
    var leftTapCount by remember { mutableStateOf(0) }
    var rightTapCount by remember { mutableStateOf(0) }
    var middleTapCount by remember { mutableStateOf(0) }
    var showLeftSeekHud by remember { mutableStateOf(false) }
    var showRightSeekHud by remember { mutableStateOf(false) }
    var leftSeekSeconds by remember { mutableStateOf(0) }
    var rightSeekSeconds by remember { mutableStateOf(0) }
    var leftTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var rightTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var middleTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var areControlsVisible by remember { mutableStateOf(true) }
    var lastUserActivityTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var isPlayerPlaying by remember { mutableStateOf(false) }

    // Auto-hide controls after 3 seconds of inactivity during playback
    LaunchedEffect(lastUserActivityTime, areControlsVisible, exoPlayer?.isPlaying) {
        if (areControlsVisible) {
            delay(3000)
            val isPlaying = exoPlayer?.isPlaying ?: false
            if (isPlaying) {
                areControlsVisible = false
            }
        }
    }

    // Toggle system notification and navigation bars (home, back, recents) dynamically
    LaunchedEffect(areControlsVisible) {
        val window = activity?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (areControlsVisible) {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Subtitle configurations
    var subtitleEnabled by remember { mutableStateOf(true) }
    var showSubtitlesMenu by remember { mutableStateOf(false) }
    var subtitleDelayMs by remember { mutableIntStateOf(0) } // timing delay in ms (-10000ms to +10000ms)

    // Active subtitle path and parsed cues for instant micro-second offset
    var activeSubtitleFilePath by remember { mutableStateOf<String?>(null) }
    var parsedSubtitleCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }

    // Playback Speed states (YouTube long-press 2X & speed selector)
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var isFastForwarding2x by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }

    // Auto-detect and parse external or sidecar subtitle file
    LaunchedEffect(item.subtitlePath, item.filePath) {
        withContext(Dispatchers.IO) {
            val path = if (!item.subtitlePath.isNullOrEmpty() && File(item.subtitlePath).exists()) {
                item.subtitlePath
            } else {
                val localPath = if (item.filePath.startsWith("/")) item.filePath else null
                val sidecars = if (localPath != null) findSidecarSubtitleFiles(localPath) else emptyList()
                sidecars.firstOrNull()?.absolutePath
            }

            activeSubtitleFilePath = path
            if (path != null) {
                val cues = SubtitleParser.parseFile(File(path))
                parsedSubtitleCues = cues
            } else {
                parsedSubtitleCues = emptyList()
            }
        }
    }

    // Active subtitle cue calculated in real-time with instant millisecond offset!
    val activeSubtitleCue = remember(currentPosition, subtitleDelayMs, parsedSubtitleCues, subtitleEnabled) {
        if (!subtitleEnabled || parsedSubtitleCues.isEmpty()) {
            null
        } else {
            val targetPlaybackTimeMs = currentPosition - subtitleDelayMs
            SubtitleParser.getActiveCue(parsedSubtitleCues, targetPlaybackTimeMs)
        }
    }

    // Full screen / scale controls (0 = FIT, 3 = FILL, 4 = ZOOM)
    var currentResizeMode by remember { mutableStateOf(0) } 

    // Swipe HUD Overlay States
    var showHud by remember { mutableStateOf(false) }
    var hudJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var hudIcon by remember { mutableStateOf(Icons.Default.VolumeUp) }
    var hudText by remember { mutableStateOf("") }
    var hudProgress by remember { mutableStateOf(0f) }

    // Cast Device Picker States
    var showCastPicker by remember { mutableStateOf(false) }
    var castingDevice by remember { mutableStateOf<String?>(null) }
    var isCastingLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Sync active player state with MainActivity for PiP trigger
    DisposableEffect(Unit) {
        com.example.MainActivity.isPlayerActive = true
        onDispose {
            com.example.MainActivity.isPlayerActive = false
        }
    }

    val cloudSources by viewModel.cloudSources.collectAsStateWithLifecycle()
    val gdriveSource = remember(cloudSources) {
        cloudSources.find { it.type == "GOOGLE_DRIVE" && !it.accessToken.isNullOrBlank() }
    }
    val accessToken = gdriveSource?.accessToken

    var resolvedStreamUrl by remember { mutableStateOf<String?>(null) }
    var isResolvingUrl by remember { mutableStateOf(false) }

    LaunchedEffect(mediaItemId, accessToken) {
        isResolvingUrl = true
        val downloadedFile = viewModel.getDownloadedFile(context, item)
        val isDownloaded = (item.filePath.startsWith("/") && java.io.File(item.filePath).exists()) || downloadedFile.exists()
        if (isDownloaded) {
            resolvedStreamUrl = if (downloadedFile.exists()) downloadedFile.absolutePath else item.filePath
        } else {
            val fileId = if (item.id.startsWith("gdrive_")) item.id.removePrefix("gdrive_") else null
            val rawUrl = if (fileId != null && !accessToken.isNullOrBlank()) {
                "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
            } else {
                item.filePath
            }
            
            if (fileId != null) {
                resolvedStreamUrl = com.example.data.service.GoogleDriveResolver.resolveGoogleDriveUrl(rawUrl, accessToken)
            } else {
                resolvedStreamUrl = rawUrl
            }
        }
        isResolvingUrl = false
    }

    // Setup updated state references for listeners
    val currentAutoplayEnabled = rememberUpdatedState(autoplayEnabled)
    val currentNextEpisode = rememberUpdatedState(nextEpisode)
    
    // ExoPlayer Lifetime managed by resolvedStreamUrl and accessToken
    DisposableEffect(resolvedStreamUrl, accessToken) {
        val streamUrl = resolvedStreamUrl
        if (streamUrl == null) {
            return@DisposableEffect onDispose {}
        }

        var finalStreamUrl = streamUrl
        val fileId = if (item.id.startsWith("gdrive_")) item.id.removePrefix("gdrive_") else null
        val headers = mutableMapOf<String, String>()

        // Support basic authentication in URL (e.g. TrueNAS/WebDAV http://user:pass@host/path)
        if (streamUrl.startsWith("http") && streamUrl.contains("@")) {
            try {
                val schemeIdx = streamUrl.indexOf("://")
                if (schemeIdx != -1) {
                    val scheme = streamUrl.substring(0, schemeIdx + 3)
                    val withoutScheme = streamUrl.substring(schemeIdx + 3)
                    val atIdx = withoutScheme.indexOf("@")
                    if (atIdx != -1) {
                        val credentials = withoutScheme.substring(0, atIdx)
                        val rest = withoutScheme.substring(atIdx + 1)
                        val colonIdx = credentials.indexOf(":")
                        if (colonIdx != -1) {
                            val username = java.net.URLDecoder.decode(credentials.substring(0, colonIdx), "UTF-8")
                            val password = java.net.URLDecoder.decode(credentials.substring(colonIdx + 1), "UTF-8")
                            headers["Authorization"] = okhttp3.Credentials.basic(username, password)
                        } else {
                            val username = java.net.URLDecoder.decode(credentials, "UTF-8")
                            headers["Authorization"] = okhttp3.Credentials.basic(username, "")
                        }
                        finalStreamUrl = scheme + rest
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerScreen", "Error extracting basic auth headers", e)
            }
        }

        val defaultHttpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        
        if (fileId != null && !accessToken.isNullOrBlank() && finalStreamUrl.startsWith("https://www.googleapis.com/")) {
            headers["Authorization"] = "Bearer $accessToken"
        }

        val cookies = com.example.data.service.GoogleDriveResolver.gdriveCookies[finalStreamUrl] ?: com.example.data.service.GoogleDriveResolver.gdriveCookies[streamUrl]
        if (!cookies.isNullOrBlank()) {
            headers["Cookie"] = cookies
        }

        headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        if (headers.isNotEmpty()) {
            defaultHttpDataSourceFactory.setDefaultRequestProperties(headers)
        }

        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, defaultHttpDataSourceFactory)

        // Wrap with CacheDataSource for segment-based chunked buffering to save bandwidth!
        val cacheDataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(getSimpleCache(context))
            .setUpstreamDataSourceFactory(dataSourceFactory)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Custom minimalistic load control to limit buffer duration (only buffer 15-30 seconds of video to avoid hitting GDrive quota)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000, // minBufferMs
                30000, // maxBufferMs
                1500,  // bufferForPlaybackMs
                2000   // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableDecoderFallback(true)
        }

        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory().apply {
            setTextTrackTranscodingEnabled(true)
        }

        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            cacheDataSourceFactory,
            extractorsFactory
        )

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            .build()
            .apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
                // Force-enable subtitle/text track type and prefer English/undetermined by default
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitleEnabled)
                    .setPreferredTextLanguages("en", "eng")
                    .setSelectUndeterminedTextLanguage(true)
                    .setIgnoredTextSelectionFlags(0)
                    .build()
            }

        val mediaSession = androidx.media3.session.MediaSession.Builder(context, player)
            .build()

        com.example.data.service.MediaSessionHolder.activeSession = mediaSession
        try {
            val serviceIntent = android.content.Intent(context, com.example.data.service.PlaybackService::class.java)
            context.startService(serviceIntent)
        } catch (e: Exception) {
            android.util.Log.e("PlayerScreen", "Failed to start PlaybackService", e)
        }

        // Initially load media item
        val isLocalFile = finalStreamUrl.startsWith("/")
        val mediaUri = if (isLocalFile) {
            Uri.fromFile(File(finalStreamUrl))
        } else {
            Uri.parse(finalStreamUrl)
        }

        val metaBuilder = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(item.title)
            .setDisplayTitle(item.title)
        if (item.type == "SHOW_EPISODE") {
            metaBuilder.setArtist(item.showName ?: "Lumina Player")
            metaBuilder.setAlbumTitle(item.showName)
        } else {
            metaBuilder.setArtist("Lumina Player")
        }
        item.posterUrl?.let { poster ->
            metaBuilder.setArtworkUri(Uri.parse(poster))
        }

        val builder = MediaItem.Builder()
            .setUri(mediaUri)
            .setMediaMetadata(metaBuilder.build())

        val subConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()

        val primarySubPath = activeSubtitleFilePath ?: item.subtitlePath
        if (!primarySubPath.isNullOrEmpty()) {
            val subFile = File(primarySubPath)
            if (subFile.exists()) {
                val mimeType = getMimeTypeForSubFile(subFile)
                subConfigs.add(
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subFile))
                        .setMimeType(mimeType)
                        .setLanguage("en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                        .setLabel("Attached: ${subFile.name}")
                        .build()
                )
            }
        }

        val localSubScanPath = if (finalStreamUrl.startsWith("/")) finalStreamUrl else if (item.filePath.startsWith("/")) item.filePath else null
        if (!localSubScanPath.isNullOrEmpty()) {
            val sidecars = findSidecarSubtitleFiles(localSubScanPath)
            sidecars.forEach { sf ->
                if (subConfigs.none { it.uri == Uri.fromFile(sf) }) {
                    val mimeType = getMimeTypeForSubFile(sf)
                    val lang = extractSubLanguageFromName(sf.name)
                    subConfigs.add(
                        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sf))
                            .setMimeType(mimeType)
                            .setLanguage(lang)
                            .setSelectionFlags(if (subConfigs.isEmpty()) C.SELECTION_FLAG_DEFAULT else 0)
                            .setLabel("Sidecar: ${sf.name}")
                            .build()
                    )
                }
            }
        }

        if (subConfigs.isNotEmpty()) {
            builder.setSubtitleConfigurations(subConfigs)
        }

        player.setMediaItem(builder.build())

        // Set watch progress restore state (prefer current tracked position first)
        val restorePos = if (currentPosition > 0L) currentPosition else item.playbackPosition
        if (restorePos > 0) {
            player.seekTo(restorePos)
        }

        player.prepare()
        exoPlayer = player

        // Listen for track ending, video size changes, and tracks changed to select CC
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (currentAutoplayEnabled.value && currentNextEpisode.value != null) {
                        scope.launch {
                            onPlayNext(currentNextEpisode.value!!.id)
                        }
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > videoSize.height) {
                    // Landscape video - rotate screen to landscape!
                    activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else if (videoSize.width > 0 && videoSize.height > 0) {
                    // Portrait video - keep or force portrait/unspecified
                    activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                availableTracks = tracks
                if (subtitleEnabled) {
                    val anyTextTrackSelected = tracks.groups.any { group ->
                        group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { group.isTrackSelected(it) }
                    }
                    if (!anyTextTrackSelected) {
                        val allTextTracks = tracks.groups
                            .filter { it.type == C.TRACK_TYPE_TEXT }
                            .flatMap { g -> (0 until g.length).map { idx -> Pair(g, idx) } }

                        val firstSelected = allTextTracks.firstOrNull { (g, idx) -> g.isTrackSupported(idx) }
                            ?: allTextTracks.firstOrNull()

                        if (firstSelected != null) {
                            val (g, idx) = firstSelected
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(
                                    androidx.media3.common.TrackSelectionOverride(
                                        g.mediaTrackGroup,
                                        idx
                                    )
                                )
                                .build()
                        }
                    }
                } else {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                }
            }
        }
        player.addListener(listener)

        // Start watch progress sync loop
        val syncJob = scope.launch {
            var lastDbUpdateTime = 0L
            while (true) {
                delay(80)
                exoPlayer?.let { p ->
                    currentPosition = p.currentPosition
                    duration = p.duration
                    isPlayerPlaying = p.isPlaying
                    if (p.isPlaying) {
                        val now = System.currentTimeMillis()
                        if (now - lastDbUpdateTime >= 5000L) {
                            viewModel.updatePlaybackPosition(mediaItemId, p.currentPosition, p.duration)
                            lastDbUpdateTime = now
                        }
                    }
                }
            }
        }

        onDispose {
            com.example.data.service.MediaSessionHolder.activeSession = null
            try {
                context.stopService(android.content.Intent(context, com.example.data.service.PlaybackService::class.java))
            } catch (e: Exception) {
                android.util.Log.e("PlayerScreen", "Failed to stop PlaybackService", e)
            }
            player.removeListener(listener)
            syncJob.cancel()
            // Restore portrait/default orientation on exit
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // Restore system bars on exit
            activity?.window?.let { window ->
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
            // Save position on exit
            viewModel.updatePlaybackPosition(mediaItemId, player.currentPosition, player.duration)
            mediaSession.release()
            player.release()
            exoPlayer = null
        }
    }

    // Instant subtitle toggle without playback stutter/re-preparing
    LaunchedEffect(subtitleEnabled) {
        exoPlayer?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitleEnabled)
                .build()
        }
    }

    // Swipe gestures execution helper
    val handleDrag: (Float, Boolean) -> Unit = { dragAmount, isLeftHalf ->
        lastUserActivityTime = System.currentTimeMillis()
        hudJob?.cancel()
        hudJob = scope.launch {
            showHud = true
            if (isLeftHalf) {
                // Adjust Window Brightness
                activity?.window?.let { window ->
                    val attrs = window.attributes
                    var currentBrightness = attrs.screenBrightness
                    if (currentBrightness < 0) currentBrightness = 0.5f // system default approx
                    
                    val change = -dragAmount / 1500f // smooth scale
                    val newBrightness = (currentBrightness + change).coerceIn(0.01f, 1.0f)
                    
                    attrs.screenBrightness = newBrightness
                    window.attributes = attrs
                    
                    hudIcon = Icons.Default.BrightnessMedium
                    hudText = "Brightness"
                    hudProgress = newBrightness
                }
            } else {
                // Adjust System Volume
                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                
                val volFactor = maxVol.toFloat()
                val change = if (dragAmount > 0) -1 else 1 // single index steps
                val newVol = (currentVol + change).coerceIn(0, maxVol)
                
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                
                hudIcon = if (newVol == 0) Icons.Default.VolumeMute else Icons.Default.VolumeUp
                hudText = "Volume"
                hudProgress = newVol.toFloat() / maxVol.toFloat()
            }
            delay(1200)
            showHud = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Underlay: Android View Player
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = exoPlayer
                    resizeMode = currentResizeMode
                    removePlayerViewScrim(this)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.player = exoPlayer
                view.resizeMode = currentResizeMode
                view.useController = false
                removePlayerViewScrim(view)
                
                // Apply dynamic subtitle styling & visibility
                view.subtitleView?.let { subtitleView ->
                    val hasParsedCues = parsedSubtitleCues.isNotEmpty()
                    subtitleView.visibility = if (subtitleEnabled && !hasParsedCues) android.view.View.VISIBLE else android.view.View.GONE
                    val captionStyle = getCaptionStyle(subBgColor, subBgOpacity, subFont)
                    subtitleView.setStyle(captionStyle)
                    subtitleView.setFractionalTextSize(subTextSize * 0.053f)
                }
            }
        )

        // Compose Subtitle Overlay (Real-Time Zero-Latency Offset & Sync Engine)
        if (subtitleEnabled && parsedSubtitleCues.isNotEmpty() && activeSubtitleCue != null) {
            val bottomPadding = if (areControlsVisible) 94.dp else 36.dp
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = bottomPadding),
                contentAlignment = Alignment.BottomCenter
            ) {
                val currentBgColor = when (subBgColor) {
                    "BLACK" -> Color.Black
                    "DARK_GRAY" -> Color(0xFF212121)
                    "BLUE" -> Color(0xFF0D47A1)
                    "RED" -> Color(0xFFB71C1C)
                    "YELLOW" -> Color(0xFFF57F17)
                    else -> Color.Black
                }.copy(alpha = subBgOpacity)

                val currentFontFamily = when (subFont) {
                    "SANS_SERIF" -> FontFamily.SansSerif
                    "SERIF" -> FontFamily.Serif
                    "MONOSPACE" -> FontFamily.Monospace
                    "CURSIVE" -> FontFamily.Cursive
                    else -> FontFamily.Default
                }

                Surface(
                    color = currentBgColor,
                    shape = RoundedCornerShape(6.dp),
                    border = if (subBgOpacity < 0.2f) BorderStroke(1.dp, Color.Black.copy(alpha = 0.5f)) else null,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = activeSubtitleCue.text,
                        color = Color.White,
                        fontSize = (16 * subTextSize).sp,
                        fontFamily = currentFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        lineHeight = (22 * subTextSize).sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = TextStyle(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.95f),
                                offset = Offset(2f, 2f),
                                blurRadius = 5f
                            )
                        )
                    )
                }
            }
        }

        if (isResolvingUrl || exoPlayer == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00E5FF))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (isResolvingUrl) "Resolving Google Drive stream..." else "Preparing media engine...",
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Central Gesture Pad (handles decreased sensitivity swipes, taps, double-taps, and YouTube-style 2X speed long-press)
        var accumulatedDragY by remember { mutableStateOf(0f) }
        val haptic = LocalHapticFeedback.current
        var wasLongPressed by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f) // center 70% of screen to avoid blocking bottom seekbar / top buttons
                .align(Alignment.Center)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            accumulatedDragY = 0f
                        },
                        onVerticalDrag = { change, dragAmount ->
                            accumulatedDragY += dragAmount
                            val isLeftHalf = change.position.x < size.width / 2f
                            val threshold = 35f // decreased sensitivity step threshold
                            if (kotlin.math.abs(accumulatedDragY) >= threshold) {
                                handleDrag(accumulatedDragY, isLeftHalf)
                                accumulatedDragY = 0f
                            }
                        }
                    )
                }
                .pointerInput(playbackSpeed, isPlayerPlaying) {
                    detectTapGestures(
                        onPress = { offset ->
                            val pressJob = scope.launch {
                                delay(380) // YouTube long press hold threshold
                                if (isPlayerPlaying) {
                                    wasLongPressed = true
                                    isFastForwarding2x = true
                                    exoPlayer?.setPlaybackSpeed(2.0f)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                            try {
                                tryAwaitRelease()
                            } finally {
                                pressJob.cancel()
                                if (isFastForwarding2x) {
                                    isFastForwarding2x = false
                                    exoPlayer?.setPlaybackSpeed(playbackSpeed)
                                }
                            }
                        },
                        onTap = { offset ->
                            if (wasLongPressed) {
                                wasLongPressed = false
                                return@detectTapGestures
                            }
                            lastUserActivityTime = System.currentTimeMillis()
                            val screenWidth = size.width
                            val x = offset.x
                            val fraction = x / screenWidth

                            if (fraction < 0.33f) {
                                // left side tap
                                leftTapJob?.cancel()
                                leftTapCount++
                                if (leftTapCount >= 2) {
                                    leftSeekSeconds = (leftTapCount - 1) * 10
                                    showLeftSeekHud = true
                                    showRightSeekHud = false
                                    exoPlayer?.let { player ->
                                        val target = (player.currentPosition - 10000).coerceAtLeast(0)
                                        player.seekTo(target)
                                        currentPosition = target
                                    }
                                    leftTapJob = scope.launch {
                                        delay(800)
                                        showLeftSeekHud = false
                                        leftTapCount = 0
                                    }
                                } else {
                                    // First tap on left: wait 250ms to see if it's a single tap or double tap
                                    leftTapJob = scope.launch {
                                        delay(250)
                                        if (leftTapCount == 1) {
                                            areControlsVisible = !areControlsVisible
                                        }
                                        leftTapCount = 0
                                    }
                                }
                            } else if (fraction > 0.66f) {
                                // right side tap
                                rightTapJob?.cancel()
                                rightTapCount++
                                if (rightTapCount >= 2) {
                                    rightSeekSeconds = (rightTapCount - 1) * 10
                                    showRightSeekHud = true
                                    showLeftSeekHud = false
                                    exoPlayer?.let { player ->
                                        val target = (player.currentPosition + 10000).coerceAtMost(player.duration)
                                        player.seekTo(target)
                                        currentPosition = target
                                    }
                                    rightTapJob = scope.launch {
                                        delay(800)
                                        showRightSeekHud = false
                                        rightTapCount = 0
                                    }
                                } else {
                                    // First tap on right
                                    rightTapJob = scope.launch {
                                        delay(250)
                                        if (rightTapCount == 1) {
                                            areControlsVisible = !areControlsVisible
                                        }
                                        rightTapCount = 0
                                    }
                                }
                            } else {
                                // middle tap
                                middleTapJob?.cancel()
                                middleTapCount++
                                if (middleTapCount == 2) {
                                    exoPlayer?.let { player ->
                                        player.playWhenReady = !player.playWhenReady
                                        isPlayerPlaying = player.playWhenReady
                                    }
                                    middleTapCount = 0
                                } else {
                                    middleTapJob = scope.launch {
                                        delay(250)
                                        if (middleTapCount == 1) {
                                            areControlsVisible = !areControlsVisible
                                        }
                                        middleTapCount = 0
                                    }
                                }
                            }
                        }
                    )
                }
        )

        // YouTube-style 2X Speed HUD Floating Badge
        AnimatedVisibility(
            visible = isFastForwarding2x,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 44.dp)
        ) {
            Surface(
                color = Color(0xEE0D0D17),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.5.dp, Color(0xFF00E5FF)),
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = "2X Speed",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "2X Speed",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                }
            }
        }

        // Overlay Control Triggers
        if (!com.example.MainActivity.isInPipMode.value) {
            AnimatedVisibility(
                visible = areControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    // Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                            .align(Alignment.TopCenter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.testTag("player_back_button")
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Exit Player", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                val displayText = if (item.type == "SHOW_EPISODE") {
                                    val seasonStr = item.season?.let { "S$it" } ?: ""
                                    val episodeStr = item.episodeNumber?.let { "E$it" } ?: ""
                                    val epPrefix = if (seasonStr.isNotEmpty() || episodeStr.isNotEmpty()) {
                                        "$seasonStr$episodeStr - "
                                    } else ""
                                    "$epPrefix${item.title}"
                                } else {
                                    item.title
                                }

                                if (item.type == "SHOW_EPISODE" && !item.showName.isNullOrEmpty()) {
                                    Text(
                                        text = item.showName,
                                        color = Color.LightGray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = displayText,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Next Episode Button
                            if (nextEpisode != null) {
                                IconButton(
                                    onClick = { onPlayNext(nextEpisode.id) },
                                    modifier = Modifier.testTag("player_next_episode_button")
                                ) {
                                    Icon(Icons.Default.SkipNext, contentDescription = "Next Episode", tint = Color(0xFF00E5FF))
                                }
                            }

                            // Fullscreen aspect ratio toggle (FIT, FILL, ZOOM)
                            IconButton(
                                onClick = {
                                    currentResizeMode = when (currentResizeMode) {
                                        0 -> 3  // FIT to FILL
                                        3 -> 4  // FILL to ZOOM
                                        else -> 0 // ZOOM to FIT
                                    }
                                    scope.launch {
                                        showHud = true
                                        hudIcon = Icons.Default.AspectRatio
                                        hudText = when (currentResizeMode) {
                                            0 -> "Aspect: FIT"
                                            3 -> "Aspect: FILL (Stretch)"
                                            else -> "Aspect: ZOOM (Crop)"
                                        }
                                        hudProgress = when (currentResizeMode) {
                                            0 -> 0.33f
                                            3 -> 0.66f
                                            else -> 1.0f
                                        }
                                        delay(1200)
                                        showHud = false
                                    }
                                },
                                modifier = Modifier.testTag("fullscreen_ratio_button")
                            ) {
                                Icon(Icons.Default.AspectRatio, contentDescription = "Fullscreen scaling", tint = Color.White)
                            }

                            // Picture in Picture Button
                            IconButton(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        activity?.enterPictureInPictureMode(
                                            android.app.PictureInPictureParams.Builder().build()
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("pip_button")
                            ) {
                                Icon(Icons.Default.PictureInPicture, contentDescription = "PiP", tint = Color.White)
                            }

                            // Orientation Lock Toggle Button
                            IconButton(
                                onClick = {
                                    val act = context as? Activity
                                    if (act != null) {
                                        val currentOrientation = act.requestedOrientation
                                        act.requestedOrientation = if (currentOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                        } else {
                                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                        }
                                        scope.launch {
                                            showHud = true
                                            hudIcon = Icons.Default.ScreenRotation
                                            hudText = if (act.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                                                "Locked: Landscape"
                                            } else {
                                                "Locked: Portrait"
                                            }
                                            hudProgress = 0.5f
                                            delay(1200)
                                            showHud = false
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("orientation_toggle_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ScreenRotation,
                                    contentDescription = "Toggle Orientation",
                                    tint = Color.White
                                )
                            }

                            // Playback Speed Button
                            IconButton(
                                onClick = { showSpeedDialog = true },
                                modifier = Modifier.testTag("speed_button")
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (playbackSpeed != 1.0f) Color(0xFF00E5FF).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.12f))
                                        .padding(horizontal = 7.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (playbackSpeed == 1.0f) "1x" else "${playbackSpeed}x",
                                        color = if (playbackSpeed != 1.0f) Color(0xFF00E5FF) else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }

                            // Subtitle Settings & Overhaul Button
                            IconButton(
                                onClick = { showSubtitlesMenu = true },
                                modifier = Modifier.testTag("subtitles_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Subtitles, 
                                    contentDescription = "Subtitles Menu", 
                                    tint = if (subtitleEnabled && (!item.subtitlePath.isNullOrEmpty() || parsedSubtitleCues.isNotEmpty())) Color(0xFF00E5FF) else Color.White
                                )
                            }

                            // Cast Button
                            IconButton(
                                onClick = { showCastPicker = true },
                                modifier = Modifier.testTag("cast_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cast, 
                                    contentDescription = "Cast", 
                                    tint = if (castingDevice != null) Color(0xFFFF9100) else Color.White
                                )
                            }
                        }
                    }

                    // Central Playback Controls
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                lastUserActivityTime = System.currentTimeMillis()
                                exoPlayer?.let { player ->
                                    player.playWhenReady = !player.playWhenReady
                                    isPlayerPlaying = player.playWhenReady
                                }
                            },
                            modifier = Modifier
                                .size(84.dp)
                                .background(Color(0xFF00E5FF).copy(alpha = 0.15f), CircleShape)
                                .border(2.dp, Color(0xFF00E5FF), CircleShape)
                                .testTag("hud_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (isPlayerPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play or Pause",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }

                    // Bottom Seekbar & Position indicators
                    var isSeeking by remember { mutableStateOf(false) }
                    var sliderValue by remember { mutableStateOf(0f) }
                    LaunchedEffect(currentPosition, isSeeking) {
                        if (!isSeeking) {
                            sliderValue = currentPosition.toFloat()
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp, start = 24.dp, end = 24.dp)
                    ) {
                        Slider(
                            value = sliderValue,
                            onValueChange = { newValue ->
                                isSeeking = true
                                lastUserActivityTime = System.currentTimeMillis()
                                sliderValue = newValue
                            },
                            onValueChangeFinished = {
                                exoPlayer?.seekTo(sliderValue.toLong())
                                currentPosition = sliderValue.toLong()
                                isSeeking = false
                            },
                            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00E5FF),
                                activeTrackColor = Color(0xFF00E5FF),
                                inactiveTrackColor = Color.Gray.copy(alpha = 0.5f)
                            ),
                            thumb = {
                                SliderDefaults.Thumb(
                                    interactionSource = remember { MutableInteractionSource() },
                                    colors = SliderDefaults.colors(thumbColor = Color(0xFF00E5FF)),
                                    modifier = Modifier.size(10.dp)
                                )
                            },
                            track = { sliderState ->
                                SliderDefaults.Track(
                                    colors = SliderDefaults.colors(
                                        activeTrackColor = Color(0xFF00E5FF),
                                        inactiveTrackColor = Color.Gray.copy(alpha = 0.5f)
                                    ),
                                    sliderState = sliderState,
                                    modifier = Modifier.height(2.dp)
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .testTag("player_seekbar")
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatTime(currentPosition),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = formatTime(duration),
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // Mid-screen Gestures HUD indicator overlay
        AnimatedVisibility(
            visible = showHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = hudIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (hudText.startsWith("Aspect")) hudText else "$hudText: ${(hudProgress * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black,
                            offset = androidx.compose.ui.geometry.Offset(1.5f, 1.5f),
                            blurRadius = 3f
                        )
                    )
                )
                if (!hudText.startsWith("Aspect")) {
                    Spacer(modifier = Modifier.width(12.dp))
                    LinearProgressIndicator(
                        progress = { hudProgress },
                        modifier = Modifier
                            .width(80.dp)
                            .height(4.dp)
                            .clip(CircleShape),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.25f),
                    )
                }
            }
        }

        // Left Side Custom Seek HUD
        AnimatedVisibility(
            visible = showLeftSeekHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.FastRewind,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "-$leftSeekSeconds secs",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black,
                            offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                            blurRadius = 4f
                        )
                    )
                )
            }
        }

        // Right Side Custom Seek HUD
        AnimatedVisibility(
            visible = showRightSeekHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 48.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "+$rightSeekSeconds secs",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black,
                            offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                            blurRadius = 4f
                        )
                    )
                )
            }
        }

        // Subtitle Alert Logs Overlay
        val isDownloadingSubs by viewModel.isDownloadingSubs.collectAsStateWithLifecycle()
        val subDownloadStatus by viewModel.subDownloadStatus.collectAsStateWithLifecycle()

        if (!com.example.MainActivity.isInPipMode.value) {
            AnimatedVisibility(
                visible = isDownloadingSubs || subDownloadStatus.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isDownloadingSubs) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color(0xFF00E5FF), strokeWidth = 1.5.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(text = subDownloadStatus, color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }

        // Subtitle Overhaul Dialog Box
        if (showSubtitlesMenu) {
            val hasLocalSub = !item.subtitlePath.isNullOrEmpty() && File(item.subtitlePath).exists()
            var selectedSubTab by remember { mutableStateOf(0) }

            AlertDialog(
                onDismissRequest = { showSubtitlesMenu = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = Color(0xFF10101A),
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Header Bar with Master Switch and Close Icon
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF00E5FF).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Subtitles,
                                        contentDescription = null,
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Subtitles & Captions",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (subtitleEnabled) "Subtitles Active" else "Subtitles Disabled",
                                        color = if (subtitleEnabled) Color(0xFF00E5FF) else Color.Gray,
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = subtitleEnabled,
                                    onCheckedChange = { enabled ->
                                        subtitleEnabled = enabled
                                        exoPlayer?.let { player ->
                                            if (enabled) {
                                                player.trackSelectionParameters = player.trackSelectionParameters
                                                    .buildUpon()
                                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                    .build()
                                            } else {
                                                player.trackSelectionParameters = player.trackSelectionParameters
                                                    .buildUpon()
                                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                                    .build()
                                            }
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF00E5FF),
                                        checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.4f),
                                        uncheckedThumbColor = Color.Gray,
                                        uncheckedTrackColor = Color(0xFF1F1F2F)
                                    )
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { showSubtitlesMenu = false },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        // Segmented Tab Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF181826))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val subTabs = listOf("Tracks & Files", "Style & Font", "Sync Offset")
                            subTabs.forEachIndexed { index, tabTitle ->
                                val isSelected = selectedSubTab == index
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) Color(0xFF00E5FF) else Color.Transparent)
                                        .clickable { selectedSubTab = index }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = tabTitle,
                                        color = if (isSelected) Color.Black else Color.Gray,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        when (selectedSubTab) {
                            0 -> {
                                // TAB 0: TRACKS & SOURCES
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // Status Summary Card
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF181826), RoundedCornerShape(10.dp))
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("ACTIVE SUBTITLE SOURCE", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = when {
                                                    !subtitleEnabled -> "None (Subtitles Switched Off)"
                                                    hasLocalSub -> "Local File: ${File(item.subtitlePath ?: "").name}"
                                                    else -> "Embedded or Online Stream"
                                                },
                                                color = if (subtitleEnabled) Color(0xFF00E5FF) else Color.LightGray,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        if (hasLocalSub) {
                                            IconButton(
                                                onClick = {
                                                    viewModel.removeLocalSubtitle(context, item)
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Remove Local Subtitle",
                                                    tint = Color(0xFFFF5252),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Embedded Tracks List
                                    val currentTracksObj = availableTracks ?: exoPlayer?.currentTracks
                                    val textGroups = currentTracksObj?.groups?.filter { it.type == C.TRACK_TYPE_TEXT } ?: emptyList()

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF181826), RoundedCornerShape(10.dp))
                                            .padding(12.dp)
                                    ) {
                                        Text("EMBEDDED SUBTITLE TRACKS", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Off / Disable Option
                                        val isOffSelected = !subtitleEnabled || textGroups.all { group ->
                                            (0 until group.length).none { group.isTrackSelected(it) }
                                        }

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isOffSelected) Color(0xFF00E5FF).copy(alpha = 0.12f) else Color(0xFF10101A))
                                                .clickable {
                                                    subtitleEnabled = false
                                                    exoPlayer?.let { player ->
                                                        player.trackSelectionParameters = player.trackSelectionParameters
                                                            .buildUpon()
                                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                                            .build()
                                                    }
                                                }
                                                .padding(horizontal = 10.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Subtitles,
                                                    contentDescription = null,
                                                    tint = if (isOffSelected) Color(0xFF00E5FF) else Color.Gray,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Disabled / No Subtitles",
                                                    color = if (isOffSelected) Color(0xFF00E5FF) else Color.LightGray,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isOffSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                            if (isOffSelected) {
                                                Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                                            }
                                        }

                                        if (textGroups.isNotEmpty()) {
                                            var trackCounter = 0
                                            textGroups.forEach { group ->
                                                for (i in 0 until group.length) {
                                                    val format = group.getTrackFormat(i)
                                                    val isSelected = subtitleEnabled && group.isTrackSelected(i)
                                                    val label = getSubtitleTrackLabel(format, trackCounter)
                                                    trackCounter++

                                                    Spacer(modifier = Modifier.height(6.dp))

                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.12f) else Color(0xFF10101A))
                                                            .clickable {
                                                                subtitleEnabled = true
                                                                exoPlayer?.let { player ->
                                                                    player.trackSelectionParameters = player.trackSelectionParameters
                                                                        .buildUpon()
                                                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                                                        .setOverrideForType(
                                                                            androidx.media3.common.TrackSelectionOverride(
                                                                                group.mediaTrackGroup,
                                                                                i
                                                                            )
                                                                        )
                                                                        .build()
                                                                }
                                                            }
                                                            .padding(horizontal = 10.dp, vertical = 9.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Subtitles,
                                                                contentDescription = null,
                                                                tint = if (isSelected) Color(0xFF00E5FF) else Color.Gray,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            Text(
                                                                text = label,
                                                                color = if (isSelected) Color(0xFF00E5FF) else Color.White,
                                                                fontSize = 12.sp,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                            )
                                                        }
                                                        if (isSelected) {
                                                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Action Buttons Section
                                    Text("EXTERNAL SUBTITLE ACTIONS", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                    
                                    Button(
                                        onClick = {
                                            try {
                                                srtLauncher.launch("*/*")
                                            } catch (e: Exception) {
                                                android.widget.Toast.makeText(context, "Cannot open file picker", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(38.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF181826)),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Icon(Icons.Default.Upload, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Import SRT / VTT File from Device", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                    }

                                    Button(
                                        onClick = {
                                            viewModel.downloadSubtitles(context, item, "English")
                                        },
                                        modifier = Modifier.fillMaxWidth().height(38.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color.Black, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Download Online Subtitles", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }

                                    Button(
                                        onClick = {
                                            viewModel.generateCcLocally(context, item)
                                        },
                                        modifier = Modifier.fillMaxWidth().height(38.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Generate CC Offline (AI Transcribe)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }

                                    if (hasLocalSub) {
                                        Button(
                                            onClick = {
                                                viewModel.removeLocalSubtitle(context, item)
                                            },
                                            modifier = Modifier.fillMaxWidth().height(38.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252).copy(alpha = 0.15f)),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Remove Attached Subtitle File", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }

                            1 -> {
                                // TAB 1: STYLE & APPEARANCE
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    // Live Caption Preview Box
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF181826)),
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.25f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text("LIVE PREVIEW", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.Gray, letterSpacing = 1.sp)
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color.Black, RoundedCornerShape(6.dp))
                                                    .padding(vertical = 10.dp, horizontal = 12.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                val sampleTextBg = when (subBgColor) {
                                                    "BLACK" -> Color.Black
                                                    "DARK_GRAY" -> Color.DarkGray
                                                    "BLUE" -> Color.Blue
                                                    "RED" -> Color.Red
                                                    "YELLOW" -> Color.Yellow
                                                    "GREEN" -> Color.Green
                                                    else -> Color.Transparent
                                                }.copy(alpha = subBgOpacity)
                                                
                                                val sampleFontFamily = when (subFont) {
                                                    "SANS_SERIF" -> androidx.compose.ui.text.font.FontFamily.SansSerif
                                                    "SERIF" -> androidx.compose.ui.text.font.FontFamily.Serif
                                                    "MONOSPACE" -> androidx.compose.ui.text.font.FontFamily.Monospace
                                                    else -> androidx.compose.ui.text.font.FontFamily.Default
                                                }

                                                Text(
                                                    text = "Sample Subtitle Text Preview",
                                                    color = Color.White,
                                                    fontSize = when (subTextSize) {
                                                        0.75f -> 11.sp
                                                        1.25f -> 15.sp
                                                        1.5f -> 18.sp
                                                        else -> 13.sp
                                                    },
                                                    fontWeight = FontWeight.Medium,
                                                    fontFamily = sampleFontFamily,
                                                    modifier = Modifier
                                                        .background(sampleTextBg, RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Text Size Selector
                                    Column {
                                        Text("TEXT SIZE", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            val sizes = listOf(0.75f, 1.0f, 1.25f, 1.5f)
                                            val labels = listOf("Small", "Normal", "Large", "X-Large")
                                            sizes.forEachIndexed { idx, szOption ->
                                                val isSel = subTextSize == szOption
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(if (isSel) Color(0xFF00E5FF) else Color(0xFF181826))
                                                        .clickable {
                                                            viewModel.updateSubtitleStyle(context, subBgColor, subBgOpacity, szOption, subFont)
                                                        }
                                                        .padding(vertical = 7.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = labels[idx],
                                                        color = if (isSel) Color.Black else Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Font Family Selector
                                    Column {
                                        Text("FONT FAMILY", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            val fonts = listOf("DEFAULT", "SANS_SERIF", "SERIF", "MONOSPACE")
                                            val fontLabels = listOf("Default", "Sans", "Serif", "Mono")
                                            fonts.forEachIndexed { idx, ftOption ->
                                                val isSel = subFont == ftOption
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(if (isSel) Color(0xFF00E5FF) else Color(0xFF181826))
                                                        .clickable {
                                                            viewModel.updateSubtitleStyle(context, subBgColor, subBgOpacity, subTextSize, ftOption)
                                                        }
                                                        .padding(vertical = 7.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = fontLabels[idx],
                                                        color = if (isSel) Color.Black else Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Background Shade Opacity Selector
                                    Column {
                                        Text("BACKGROUND OPACITY", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            val opacities = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
                                            opacities.forEach { opOption ->
                                                val isSel = subBgOpacity == opOption
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(if (isSel) Color(0xFF00E5FF) else Color(0xFF181826))
                                                        .clickable {
                                                            viewModel.updateSubtitleStyle(context, subBgColor, opOption, subTextSize, subFont)
                                                        }
                                                        .padding(vertical = 7.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = "${(opOption * 100).toInt()}%",
                                                        color = if (isSel) Color.Black else Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Background Color Grid
                                    Column {
                                        Text("BACKGROUND COLOR SHADE", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            val bgColors = listOf("DEFAULT", "BLACK", "DARK_GRAY", "BLUE", "RED", "YELLOW")
                                            bgColors.forEach { colorOpt ->
                                                val isSel = subBgColor == colorOpt
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(if (isSel) Color(0xFF00E5FF) else Color(0xFF181826))
                                                        .clickable {
                                                            viewModel.updateSubtitleStyle(context, colorOpt, subBgOpacity, subTextSize, subFont)
                                                        }
                                                        .padding(vertical = 7.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = colorOpt.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
                                                        color = if (isSel) Color.Black else Color.White,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Reset Style Row
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            onClick = {
                                                viewModel.updateSubtitleStyle(context, "DEFAULT", 0.5f, 1.0f, "DEFAULT")
                                            }
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Reset to Defaults", fontSize = 11.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }

                            2 -> {
                                // TAB 2: TIMING & SYNC (Live Interactive Sync Engine)
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    // Hero Offset Readout Card
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF181826))
                                            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                            .padding(vertical = 14.dp, horizontal = 14.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = "CURRENT TIMING OFFSET",
                                                fontSize = 9.sp,
                                                color = Color.Gray,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = if (subtitleDelayMs == 0) "0.0s (In Sync)" else String.format("%+.2fs (%+dms)", subtitleDelayMs / 1000f, subtitleDelayMs),
                                                color = Color(0xFF00E5FF),
                                                fontSize = 24.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = when {
                                                    subtitleDelayMs > 0 -> "Subtitles delayed by ${subtitleDelayMs}ms (appear later)"
                                                    subtitleDelayMs < 0 -> "Subtitles advanced by ${-subtitleDelayMs}ms (appear earlier)"
                                                    else -> "Subtitles synchronized to original video stream"
                                                },
                                                color = Color.LightGray,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }

                                    // Live Real-Time Subtitle Preview Box
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF0C0C16))
                                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                            .padding(12.dp)
                                    ) {
                                        Column {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Default.Visibility,
                                                        contentDescription = null,
                                                        tint = Color(0xFF00E5FF),
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "LIVE SYNC PREVIEW AT ${formatTime((currentPosition - subtitleDelayMs).coerceAtLeast(0))}",
                                                        fontSize = 9.sp,
                                                        color = Color(0xFF00E5FF),
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 0.5.sp
                                                    )
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(if (activeSubtitleCue != null) Color(0xFF00E676).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f))
                                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = if (activeSubtitleCue != null) "CUE MATCH" else "IDLE",
                                                        fontSize = 8.sp,
                                                        color = if (activeSubtitleCue != null) Color(0xFF00E676) else Color.Gray,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(6.dp))

                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 42.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = activeSubtitleCue?.text
                                                        ?: if (parsedSubtitleCues.isEmpty()) "Load or download a subtitle file (.srt/.vtt) in Tracks tab to enable live microsecond sync preview."
                                                        else "(No spoken dialogue at this video timestamp)",
                                                    color = if (activeSubtitleCue != null) Color.White else Color.Gray,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (activeSubtitleCue != null) FontWeight.SemiBold else FontWeight.Normal,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.padding(horizontal = 6.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Continuous Slider Scrubber
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("CONTINUOUS TIMING SCRUBBER", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                            Text(
                                                text = "${subtitleDelayMs}ms",
                                                fontSize = 10.sp,
                                                color = Color(0xFF00E5FF),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Slider(
                                            value = subtitleDelayMs.toFloat(),
                                            onValueChange = {
                                                subtitleDelayMs = (it / 50).toInt() * 50 // 50ms quantize
                                            },
                                            valueRange = -5000f..5000f,
                                            colors = SliderDefaults.colors(
                                                thumbColor = Color(0xFF00E5FF),
                                                activeTrackColor = Color(0xFF00E5FF),
                                                inactiveTrackColor = Color(0xFF1E1E30)
                                            ),
                                            modifier = Modifier.fillMaxWidth().height(28.dp)
                                        )
                                    }

                                    // Large Step Adjusters
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text("QUICK OFFSET ADJUSTMENT", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    subtitleDelayMs -= 1000
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF181826)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f).height(36.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text("-1.0s", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }

                                            Button(
                                                onClick = {
                                                    subtitleDelayMs -= 500
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF181826)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f).height(36.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text("-0.5s", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }

                                            Button(
                                                onClick = {
                                                    subtitleDelayMs = 0
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF).copy(alpha = 0.18f)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1.1f).height(36.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text("Reset 0s", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                            }

                                            Button(
                                                onClick = {
                                                    subtitleDelayMs += 500
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF181826)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f).height(36.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text("+0.5s", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }

                                            Button(
                                                onClick = {
                                                    subtitleDelayMs += 1000
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF181826)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f).height(36.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text("+1.0s", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }

                                    // Micro-Fine Precision Adjusters
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text("FINE MICROSECOND ADJUSTMENT", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    subtitleDelayMs -= 100
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF181826)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f).height(34.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text("-100ms", color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                            }

                                            Button(
                                                onClick = {
                                                    subtitleDelayMs -= 50
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF181826)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f).height(34.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text("-50ms", color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                            }

                                            Button(
                                                onClick = {
                                                    subtitleDelayMs += 50
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF181826)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f).height(34.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text("+50ms", color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                            }

                                            Button(
                                                onClick = {
                                                    subtitleDelayMs += 100
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF181826)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f).height(34.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text("+100ms", color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }

                                    // Helpful Sync Guidance Banner
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF121220))
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Actors talking before words show? Tap [+] Delay. Words showing before speech? Tap [-] Advance.",
                                            color = Color.LightGray,
                                            fontSize = 10.sp,
                                            lineHeight = 13.sp
                                        )
                                    }

                                    // Download Button if no subtitle cues
                                    if (parsedSubtitleCues.isEmpty()) {
                                        Button(
                                            onClick = {
                                                viewModel.downloadSubtitles(context, item, "English")
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF).copy(alpha = 0.2f)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth().height(36.dp)
                                        ) {
                                            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Download Subtitles for Real-Time Sync", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showSubtitlesMenu = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Text("Done", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            )
        }

        // Playback Speed Selector Dialog
        if (showSpeedDialog) {
            AlertDialog(
                onDismissRequest = { showSpeedDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Playback Speed", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF12121E),
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Tip: You can also press and hold anywhere on screen during video playback to jump to 2X speed instantly.",
                            color = Color(0xFF00E5FF).copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        val speedOptions = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                        speedOptions.forEach { spd ->
                            val isSelected = kotlin.math.abs(playbackSpeed - spd) < 0.05f
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable {
                                        playbackSpeed = spd
                                        exoPlayer?.setPlaybackSpeed(spd)
                                        showSpeedDialog = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (spd == 1.0f) "1.0x (Normal)" else "${spd}x",
                                    color = if (isSelected) Color(0xFF00E5FF) else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSpeedDialog = false }) {
                        Text("Close", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // Dynamic Cast Device Picker Sheet
        if (showCastPicker) {
            AlertDialog(
                onDismissRequest = { showCastPicker = false },
                title = { Text("Cast to External Display", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
                containerColor = Color(0xFF14141F),
                text = {
                    Column {
                        Text("Select a casting receiver destination found on your local network:", fontSize = 11.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(14.dp))

                        if (isCastingLoading) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFFFF9100))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Connecting to screen...", color = Color.White, fontSize = 12.sp)
                            }
                        } else {
                            val targets = listOf("Living Room TV", "Bedroom Chromecast", "Office Display", "TrueNAS Smart Hub")
                            targets.forEach { target ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            scope.launch {
                                                isCastingLoading = true
                                                delay(1200) // Connect handshake
                                                isCastingLoading = false
                                                castingDevice = target
                                                showCastPicker = false
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (castingDevice == target) Color(0xFFFF9100).copy(alpha = 0.15f) else Color(0xFF1F1F2F)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(target, color = Color.White, fontSize = 13.sp)
                                        Icon(
                                            imageVector = Icons.Default.Tv, 
                                            contentDescription = null, 
                                            tint = if (castingDevice == target) Color(0xFFFF9100) else Color.Gray,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    if (castingDevice != null) {
                        TextButton(
                            onClick = {
                                castingDevice = null
                                showCastPicker = false
                            }
                        ) {
                            Text("Disconnect Cast", color = Color.Red, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCastPicker = false }) {
                        Text("Close", color = Color.LightGray)
                    }
                }
            )
        }

        // Netflix-style Autoplay Countdown Overlay (Bottom Right)
        val remainingSeconds = if (duration > 0) ((duration - currentPosition) / 1000).coerceAtLeast(0) else 15L
        val isCreditsActive = duration > 0 && (duration - currentPosition) <= 15000 && (duration - currentPosition) > 0
        
        // Auto-navigate when countdown hits 0
        LaunchedEffect(remainingSeconds, isCreditsActive, showAutoplayCountdown, autoplayEnabled, nextEpisode) {
            if (isCreditsActive && remainingSeconds <= 0L && showAutoplayCountdown && autoplayEnabled && nextEpisode != null) {
                onPlayNext(nextEpisode.id)
            }
        }

        if (!com.example.MainActivity.isInPipMode.value) {
            AnimatedVisibility(
                visible = isCreditsActive && showAutoplayCountdown && autoplayEnabled && nextEpisode != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 24.dp)
                    .width(320.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF14141F).copy(alpha = 0.95f)),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f)),
                    modifier = Modifier.testTag("autoplay_countdown_overlay")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "NEXT EPISODE STARTING IN",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.Gray,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "S${nextEpisode?.season ?: 1}:E${nextEpisode?.episodeNumber ?: 1} - ${nextEpisode?.title ?: "Next Episode"}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${remainingSeconds}s",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF00E5FF)
                            )
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = { showAutoplayCountdown = false },
                                    modifier = Modifier.testTag("autoplay_cancel_button")
                                ) {
                                    Text("Cancel", color = Color.LightGray, fontSize = 12.sp)
                                }
                                Button(
                                    onClick = { nextEpisode?.let { onPlayNext(it.id) } },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .height(32.dp)
                                        .testTag("autoplay_play_now_button")
                                ) {
                                    Text("Play Now", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getSubtitleTrackLabel(format: androidx.media3.common.Format, trackIndex: Int): String {
    val trackNumStr = "Track ${trackIndex + 1}"
    
    val langTag = format.language
    val langStr = if (!langTag.isNullOrBlank() && langTag != "und") {
        try {
            val loc = java.util.Locale.forLanguageTag(langTag)
            val disp = loc.getDisplayName(java.util.Locale.ENGLISH)
            if (disp.isNotBlank()) disp else langTag.uppercase()
        } catch (e: Exception) {
            langTag.uppercase()
        }
    } else ""

    val mime = format.sampleMimeType ?: format.containerMimeType ?: ""
    val codecStr = when {
        mime.contains("subrip") -> "SRT"
        mime.contains("vtt") -> "VTT"
        mime.contains("vobsub") -> "VobSub"
        mime.contains("ass") -> "ASS"
        mime.contains("ssa") -> "SSA"
        mime.contains("pgs") -> "PGS"
        mime.contains("ttml") -> "TTML"
        else -> ""
    }

    val rawLabel = format.label?.trim()
    val flags = mutableListOf<String>()
    if ((format.selectionFlags and androidx.media3.common.C.SELECTION_FLAG_FORCED) != 0) {
        flags.add("Forced")
    }
    if ((format.selectionFlags and androidx.media3.common.C.SELECTION_FLAG_DEFAULT) != 0) {
        flags.add("Default")
    }
    val flagSuffix = if (flags.isNotEmpty()) " (${flags.joinToString(", ")})" else ""

    val infoParts = mutableListOf<String>()
    if (!rawLabel.isNullOrBlank()) {
        infoParts.add(rawLabel)
    }
    if (langStr.isNotBlank() && (rawLabel == null || !rawLabel.lowercase().contains(langStr.lowercase()))) {
        infoParts.add(langStr)
    }
    if (codecStr.isNotBlank() && (rawLabel == null || !rawLabel.contains(codecStr))) {
        infoParts.add(codecStr)
    }

    val detailString = if (infoParts.isNotEmpty()) {
        " - [${infoParts.joinToString(" - ")}]"
    } else ""

    return "$trackNumStr$detailString$flagSuffix"
}

private fun getMimeTypeForSubFile(file: File): String {
    return when {
        file.extension.equals("vtt", ignoreCase = true) -> "text/vtt"
        file.extension.equals("ass", ignoreCase = true) -> "text/x-ass"
        file.extension.equals("ssa", ignoreCase = true) -> "text/x-ssa"
        file.extension.equals("sub", ignoreCase = true) -> "application/x-subrip"
        else -> "application/x-subrip"
    }
}

private fun extractSubLanguageFromName(fileName: String): String {
    val lower = fileName.lowercase()
    return when {
        lower.contains(".en.") || lower.contains(".eng.") || lower.endsWith("_en.srt") || lower.endsWith("_eng.srt") -> "en"
        lower.contains(".es.") || lower.contains(".spa.") || lower.endsWith("_es.srt") || lower.endsWith("_spa.srt") -> "es"
        lower.contains(".fr.") || lower.contains(".fre.") || lower.endsWith("_fr.srt") || lower.endsWith("_fre.srt") -> "fr"
        lower.contains(".de.") || lower.contains(".ger.") || lower.endsWith("_de.srt") || lower.endsWith("_ger.srt") -> "de"
        lower.contains(".hi.") || lower.contains(".hin.") || lower.endsWith("_hi.srt") || lower.endsWith("_hin.srt") -> "hi"
        else -> "en"
    }
}

private fun findSidecarSubtitleFiles(videoFilePath: String): List<File> {
    try {
        if (!videoFilePath.startsWith("/")) return emptyList()
        val videoFile = File(videoFilePath)
        val parentDir = videoFile.parentFile ?: return emptyList()
        if (!parentDir.exists() || !parentDir.isDirectory) return emptyList()

        val videoBaseName = videoFile.nameWithoutExtension.lowercase()
        val subExtensions = setOf("srt", "vtt", "ass", "ssa", "sub")

        val allFiles = parentDir.listFiles() ?: return emptyList()
        val sidecars = allFiles.filter { file ->
            val ext = file.extension.lowercase()
            if (ext in subExtensions && file.length() > 0) {
                val fileNameLower = file.nameWithoutExtension.lowercase()
                fileNameLower.startsWith(videoBaseName) || 
                videoBaseName.startsWith(fileNameLower) || 
                allFiles.count { it.extension.lowercase() in subExtensions } == 1
            } else false
        }
        return sidecars
    } catch (e: Exception) {
        android.util.Log.e("PlayerScreen", "Error finding sidecar subtitles: ", e)
        return emptyList()
    }
}

private fun removePlayerViewScrim(view: android.view.View) {
    if (view is android.view.ViewGroup) {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            val idName = try { child.resources.getResourceEntryName(child.id) } catch (e: Exception) { "" }
            if (idName.contains("scrim") || idName.contains("background") || idName.contains("placeholder") || idName == "exo_controller_placeholder") {
                child.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            removePlayerViewScrim(child)
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

private var simpleVideoCache: androidx.media3.datasource.cache.SimpleCache? = null

@Synchronized
private fun getSimpleCache(context: Context): androidx.media3.datasource.cache.SimpleCache {
    if (simpleVideoCache == null) {
        val cacheDir = File(context.cacheDir, "video_cache")
        val evictor = androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(200 * 1024 * 1024) // 200MB Cache for streaming parts
        val databaseProvider = androidx.media3.database.StandaloneDatabaseProvider(context)
        simpleVideoCache = androidx.media3.datasource.cache.SimpleCache(cacheDir, evictor, databaseProvider)
    }
    return simpleVideoCache!!
}
