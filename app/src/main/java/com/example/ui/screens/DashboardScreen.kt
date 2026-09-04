package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.model.MediaItem
import com.example.ui.viewmodel.MediaViewModel
import kotlinx.coroutines.launch
import android.util.Log

// TvShow helper model for Prime Video-style layout grouping
data class TvShow(
    val showName: String,
    val backdropUrl: String?,
    val posterUrl: String?,
    val overview: String?,
    val rating: Float?,
    val releaseDate: String?,
    val episodes: List<MediaItem>,
    val cast: String? = null,
    val director: String? = null
)

sealed interface DashboardItem {
    data class MovieItem(val item: MediaItem) : DashboardItem
    data class ShowItem(val show: TvShow) : DashboardItem
}

fun groupMediaItems(items: List<MediaItem>): List<DashboardItem> {
    val movies = items.filter { it.type == "MOVIE" }.map { DashboardItem.MovieItem(it) }
    val showEpisodes = items.filter { it.type == "SHOW_EPISODE" }
    val groupedShows = showEpisodes.groupBy { it.showName ?: "Unknown Show" }
        .map { (showName, episodes) ->
            val firstEp = episodes.firstOrNull()
            TvShow(
                showName = showName,
                backdropUrl = firstEp?.backdropUrl ?: firstEp?.posterUrl,
                posterUrl = firstEp?.posterUrl ?: firstEp?.backdropUrl,
                overview = firstEp?.overview,
                rating = firstEp?.rating,
                releaseDate = firstEp?.releaseDate,
                episodes = episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episodeNumber ?: 1 })),
                cast = firstEp?.cast,
                director = firstEp?.director
            )
        }
        .map { DashboardItem.ShowItem(it) }
    return movies + groupedShows
}

fun isRandomVideo(fileName: String): Boolean {
    val lower = fileName.lowercase()
    val randomKeywords = listOf(
        "vid_", "pxl_", "img_", "whatsapp", "zoom", "meeting_", 
        "screen-capture", "screencast", "recording", "camera", "untitled", "test", "dummy", "capture"
    )
    return randomKeywords.any { lower.contains(it) }
}

@Composable
fun DashboardScreen(
    viewModel: MediaViewModel,
    onMediaClick: (MediaItem) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allMedia by viewModel.allMedia.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanStatus by viewModel.scanStatus.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var selectedShowName by remember { mutableStateOf<String?>(null) }
    
    var selectedMovieForFixMatch by remember { mutableStateOf<MediaItem?>(null) }
    var selectedShowForFixMatch by remember { mutableStateOf<TvShow?>(null) }
    val savedTmdbApiKey by viewModel.tmdbApiKey.collectAsStateWithLifecycle()

    val prefs = remember { context.getSharedPreferences("lumina_player_prefs", android.content.Context.MODE_PRIVATE) }
    var showFirstTimePermissionPopup by remember {
        mutableStateOf(prefs.getBoolean("is_first_time_use", true))
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            val granted = permissions.values.all { it }
            if (granted) {
                viewModel.scanLocalMedia(context)
            } else {
                android.widget.Toast.makeText(context, "Storage permissions are required to scan media.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    )

    val onRequestPermission = {
        val permissionsToRequest = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        val allGranted = permissionsToRequest.all { perm ->
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                perm
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        
        if (allGranted) {
            viewModel.scanLocalMedia(context)
        } else {
            permissionLauncher.launch(permissionsToRequest)
        }
    }
    
    // Filter out random videos (unless they have been matched and have online cover art)
    val cleanMedia = remember(allMedia) {
        allMedia.filter { it.type == "MOVIE" || it.type == "SHOW_EPISODE" }
    }

    val filteredMedia = remember(cleanMedia, searchQuery) {
        if (searchQuery.isBlank()) {
            cleanMedia
        } else {
            cleanMedia.filter { 
                it.title.contains(searchQuery, ignoreCase = true) || 
                (it.showName?.contains(searchQuery, ignoreCase = true) ?: false)
            }
        }
    }

    // Group TV Show Episodes into distinct Shows
    val showsGrouped = remember(filteredMedia) {
        filteredMedia.filter { it.type == "SHOW_EPISODE" && !it.showName.isNullOrEmpty() }
            .groupBy { it.showName!! }
            .map { (showName, episodes) ->
                val firstEp = episodes.firstOrNull()
                TvShow(
                    showName = showName,
                    backdropUrl = firstEp?.backdropUrl ?: firstEp?.posterUrl,
                    posterUrl = firstEp?.posterUrl ?: firstEp?.backdropUrl,
                    overview = firstEp?.overview,
                    rating = firstEp?.rating,
                    releaseDate = firstEp?.releaseDate,
                    episodes = episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episodeNumber ?: 1 }))
                )
            }
    }

    // Get list of movies
    val moviesOnly = remember(filteredMedia) {
        filteredMedia.filter { it.type == "MOVIE" }
    }

    // Get list of other random videos/clips (excluding matched ones)
    val otherMedia = remember(allMedia, searchQuery) {
        val list = allMedia.filter { it.type == "OTHER" }
        if (searchQuery.isBlank()) {
            list
        } else {
            list.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
    }

    val localMediaOnly = remember(filteredMedia) {
        filteredMedia.filter { item ->
            val isDownloaded = (item.filePath.startsWith("/") && java.io.File(item.filePath).exists()) || viewModel.getDownloadedFile(context, item).exists()
            isDownloaded || (
                !item.id.startsWith("gdrive_") &&
                !item.id.startsWith("nas_") &&
                !item.id.startsWith("webdav_") &&
                !item.filePath.startsWith("smb://") &&
                !(item.filePath.startsWith("http") && !item.filePath.contains("google"))
            )
        }
    }

    val nasMediaOnly = remember(filteredMedia) {
        filteredMedia.filter { item ->
            item.id.startsWith("nas_") ||
            item.id.startsWith("webdav_") ||
            item.filePath.startsWith("smb://") ||
            (item.filePath.startsWith("http") && !item.filePath.contains("google"))
        }
    }

    val driveMediaOnly = remember(filteredMedia) {
        filteredMedia.filter { item ->
            item.id.startsWith("gdrive_") ||
            item.filePath.contains("drive.google.com") ||
            item.filePath.contains("googleapis.com")
        }
    }

    val localDashboardItems = remember(localMediaOnly) {
        groupMediaItems(localMediaOnly)
    }

    val nasDashboardItems = remember(nasMediaOnly) {
        groupMediaItems(nasMediaOnly)
    }

    val driveDashboardItems = remember(driveMediaOnly) {
        groupMediaItems(driveMediaOnly)
    }

    // If TV Details page is active
    val activeShow = remember(showsGrouped, selectedShowName) {
        showsGrouped.find { it.showName == selectedShowName }
    }

    var selectedMovieId by remember { mutableStateOf<String?>(null) }

    val activeMovie = remember(allMedia, selectedMovieId) {
        allMedia.find { it.id == selectedMovieId }
    }

    if (activeShow != null) {
        TvShowDetailsView(
            show = activeShow,
            viewModel = viewModel,
            onEpisodeClick = onMediaClick,
            onBack = { selectedShowName = null }
        )
    } else if (activeMovie != null) {
        MovieDetailsView(
            movie = activeMovie,
            onPlayClick = onMediaClick,
            onFixMatch = { selectedMovieForFixMatch = it },
            onMarkNotMovie = { item ->
                viewModel.markAsNotMovie(item.id)
                selectedMovieId = null
            },
            onBack = { selectedMovieId = null }
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0F0F15),
                            Color(0xFF0A0A0E)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(56.dp)) // Safe status bar padding

                // Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "LUMINA PLAYER",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp,
                            color = Color(0xFF00E5FF)
                        )
                        Text(
                            text = "Minimalist Local Cinema",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }

                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .background(Color(0xFF1B1B2A), CircleShape)
                            .testTag("settings_shortcut_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Quick Scan",
                            tint = Color(0xFF00E5FF)
                        )
                    }
                }

                // Interactive Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .testTag("search_field"),
                    placeholder = { Text("Search title, show name...", color = Color.Gray) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF2E2E3E),
                        focusedContainerColor = Color(0xFF14141E),
                        unfocusedContainerColor = Color(0xFF14141E),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                // Scanning Status banner
                AnimatedVisibility(visible = isScanning) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1F2C)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFF00E5FF),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = scanStatus,
                                fontSize = 12.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (cleanMedia.isEmpty()) {
                    EmptyStateCard(
                        onLoadDemo = { viewModel.loadDemoLibrary() },
                        onNavigateToSettings = onNavigateToSettings
                    )
                } else {
                    // Main Content List organized in separate horizontal carousels
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 100.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // Continue Watching Section
                        val cleanContinueWatching = continueWatching.filter { it.type == "MOVIE" || it.type == "SHOW_EPISODE" }
                        if (cleanContinueWatching.isNotEmpty() && searchQuery.isBlank()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Continue Watching",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(cleanContinueWatching) { item ->
                                        ContinueWatchingCard(
                                            item = item,
                                            onClick = { onMediaClick(item) },
                                            onRemoveFromContinueWatching = {
                                                viewModel.updatePlaybackPosition(item.id, 0L, item.duration)
                                            },
                                            onMarkAsFinished = {
                                                val targetPos = if (item.duration > 0) item.duration else 1000L
                                                val targetDur = if (item.duration > 0) item.duration else 1000L
                                                viewModel.updatePlaybackPosition(item.id, targetPos, targetDur)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Filtered TV Shows Carousel (Your TV Shows)
                        if (showsGrouped.isNotEmpty()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Your TV Shows",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(showsGrouped) { tvShow ->
                                        Box(modifier = Modifier.width(130.dp)) {
                                            ShowCatalogCard(
                                                show = tvShow,
                                                onClick = { selectedShowName = tvShow.showName },
                                                onFixMatch = { selectedShowForFixMatch = tvShow }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Filtered Movies Carousel (Your Movies)
                        if (moviesOnly.isNotEmpty()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Your Movies",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(moviesOnly) { movie ->
                                        Box(modifier = Modifier.width(130.dp)) {
                                            MediaCatalogCard(
                                                item = movie,
                                                onClick = { selectedMovieId = movie.id },
                                                onFixMatch = { selectedMovieForFixMatch = movie }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Local Media Carousel
                        if (localDashboardItems.isNotEmpty()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Local Storage",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(localDashboardItems) { dbItem ->
                                        Box(modifier = Modifier.width(130.dp)) {
                                            when (dbItem) {
                                                is DashboardItem.MovieItem -> {
                                                    MediaCatalogCard(
                                                        item = dbItem.item,
                                                        onClick = { selectedMovieId = dbItem.item.id },
                                                        onFixMatch = { selectedMovieForFixMatch = dbItem.item }
                                                    )
                                                }
                                                is DashboardItem.ShowItem -> {
                                                    ShowCatalogCard(
                                                        show = dbItem.show,
                                                        onClick = { selectedShowName = dbItem.show.showName },
                                                        onFixMatch = { selectedShowForFixMatch = dbItem.show }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // NAS Media Carousel
                        if (nasDashboardItems.isNotEmpty()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "TrueNAS / Network Storage",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(nasDashboardItems) { dbItem ->
                                        Box(modifier = Modifier.width(130.dp)) {
                                            when (dbItem) {
                                                is DashboardItem.MovieItem -> {
                                                    MediaCatalogCard(
                                                        item = dbItem.item,
                                                        onClick = { selectedMovieId = dbItem.item.id },
                                                        onFixMatch = { selectedMovieForFixMatch = dbItem.item }
                                                    )
                                                }
                                                is DashboardItem.ShowItem -> {
                                                    ShowCatalogCard(
                                                        show = dbItem.show,
                                                        onClick = { selectedShowName = dbItem.show.showName },
                                                        onFixMatch = { selectedShowForFixMatch = dbItem.show }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Google Drive Media Carousel
                        if (driveDashboardItems.isNotEmpty()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Google Drive",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(driveDashboardItems) { dbItem ->
                                        Box(modifier = Modifier.width(130.dp)) {
                                            when (dbItem) {
                                                is DashboardItem.MovieItem -> {
                                                    MediaCatalogCard(
                                                        item = dbItem.item,
                                                        onClick = { selectedMovieId = dbItem.item.id },
                                                        onFixMatch = { selectedMovieForFixMatch = dbItem.item }
                                                    )
                                                }
                                                is DashboardItem.ShowItem -> {
                                                    ShowCatalogCard(
                                                        show = dbItem.show,
                                                        onClick = { selectedShowName = dbItem.show.showName },
                                                        onFixMatch = { selectedShowForFixMatch = dbItem.show }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Other Clips & Videos Carousel
                        if (otherMedia.isNotEmpty()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Other Videos & Clips",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(otherMedia) { other ->
                                        Box(modifier = Modifier.width(130.dp)) {
                                            MediaCatalogCard(
                                                item = other,
                                                onClick = { selectedMovieId = other.id },
                                                onFixMatch = { selectedMovieForFixMatch = other }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Render First-time media access popup
                if (showFirstTimePermissionPopup) {
                    AlertDialog(
                        onDismissRequest = {
                            prefs.edit().putBoolean("is_first_time_use", false).apply()
                            showFirstTimePermissionPopup = false
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Movie,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(40.dp)
                            )
                        },
                        title = {
                            Text(
                                text = "Lumina Player Media Access",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Text(
                                text = "To automatically index and play your local Movies and TV Series, Lumina Player requires permission to access files on your device. This allows us to link your collection with beautiful covers and subtitles offline.",
                                color = Color.LightGray,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    prefs.edit().putBoolean("is_first_time_use", false).apply()
                                    showFirstTimePermissionPopup = false
                                    onRequestPermission()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                            ) {
                                Text("Grant Access", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    prefs.edit().putBoolean("is_first_time_use", false).apply()
                                    showFirstTimePermissionPopup = false
                                }
                            ) {
                                Text("Not Now", color = Color.Gray)
                            }
                        },
                        containerColor = Color(0xFF14141E)
                    )
                }

                // TMDb manual linking dialogues
                if (selectedMovieForFixMatch != null) {
                    FixMatchDialog(
                        movieItem = selectedMovieForFixMatch,
                        tvShow = null,
                        viewModel = viewModel,
                        apiKey = savedTmdbApiKey,
                        onDismiss = { selectedMovieForFixMatch = null }
                    )
                }

                if (selectedShowForFixMatch != null) {
                    FixMatchDialog(
                        movieItem = null,
                        tvShow = selectedShowForFixMatch,
                        viewModel = viewModel,
                        apiKey = savedTmdbApiKey,
                        onDismiss = { selectedShowForFixMatch = null }
                    )
                }
            }
        }
    }
}

@Composable
fun TvShowDetailsView(
    show: TvShow,
    viewModel: com.example.ui.viewmodel.MediaViewModel,
    onEpisodeClick: (MediaItem) -> Unit,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler {
        onBack()
    }
    var selectedSeason by remember { mutableStateOf(show.episodes.map { it.season ?: 1 }.distinct().firstOrNull() ?: 1) }
    val seasons = remember(show.episodes) {
        show.episodes.map { it.season ?: 1 }.distinct().sorted()
    }
    val seasonEpisodes = remember(show.episodes, selectedSeason) {
        show.episodes.filter { (it.season ?: 1) == selectedSeason }.sortedBy { it.episodeNumber ?: 1 }
    }

    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color(0xFF09090D))
    ) {
        // Hero Backdrop with Back button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF1E1E2F), Color(0xFF09090D))
                        )
                    ),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Tv,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF).copy(alpha = 0.5f),
                    modifier = Modifier.size(48.dp)
                )
            }
            AsyncImage(
                model = show.backdropUrl ?: show.posterUrl,
                contentDescription = show.showName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Gradient Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.5f),
                                Color.Transparent,
                                Color(0xFF09090D)
                            )
                        )
                    )
            )

            // Back button
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(top = 48.dp, start = 16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back to Catalog",
                    tint = Color.White
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Media Source Badge
            val showFirstEp = show.episodes.firstOrNull()
            val sourceLabel = if (showFirstEp != null) {
                when {
                    showFirstEp.id.startsWith("gdrive_") || showFirstEp.filePath.contains("drive.google.com") || showFirstEp.filePath.contains("googleapis.com") -> "GDrive"
                    showFirstEp.id.startsWith("nas_") || showFirstEp.id.startsWith("webdav_") || showFirstEp.filePath.startsWith("smb://") || (showFirstEp.filePath.startsWith("http") && !showFirstEp.filePath.contains("google")) -> "NAS"
                    else -> "Local"
                }
            } else {
                "Local"
            }
            
            val sourceDescription = when (sourceLabel) {
                "GDrive" -> "Google Drive stream"
                "NAS" -> "Network attached storage"
                else -> "Indexed local storage"
            }
            
            val badgeColor = when (sourceLabel) {
                "GDrive" -> Color(0xFF00E5FF)
                "NAS" -> Color(0xFFFF9100)
                else -> Color(0xFF39FF14)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(badgeColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = sourceLabel.uppercase(),
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    text = sourceDescription,
                    color = badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Show Name Title
            Text(
                text = show.showName,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Stats Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (show.rating != null && show.rating > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Rating",
                            tint = Color(0xFFFFD54F),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = String.format("%.1f", show.rating),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = show.releaseDate?.substringBefore("-") ?: "Series",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = "${seasons.size} " + if (seasons.size > 1) "Seasons" else "Season",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                Box(
                    modifier = Modifier
                        .border(1.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "Ultra HD",
                        color = Color.Gray,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Play/Resume Season button
            val firstEpisode = seasonEpisodes.firstOrNull()
            if (firstEpisode != null) {
                Button(
                    onClick = { onEpisodeClick(firstEpisode) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("prime_play_season_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A8E1)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Play Season ${selectedSeason} Episode 1",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!show.director.isNullOrBlank()) {
                Text(
                    text = "Director/Creators: ${show.director}",
                    color = Color(0xFF00E5FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            if (!show.cast.isNullOrBlank()) {
                Text(
                    text = "Cast: ${show.cast}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            // Show description overview
            Text(
                text = show.overview ?: "No description available for this series.",
                color = Color.LightGray,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Season Selection row
            if (seasons.size > 1) {
                Text(
                    text = "Seasons",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    seasons.forEach { sNum ->
                        val isSelected = sNum == selectedSeason
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isSelected) Color(0xFF00A8E1) else Color(0xFF1B1B2A))
                                .clickable { selectedSeason = sNum }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Season $sNum",
                                color = if (isSelected) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Episodes",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            // Episodes List Cards
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(bottom = 100.dp)
            ) {
                seasonEpisodes.forEach { ep ->
                    val epProgress = if (ep.duration > 0) ep.playbackPosition.toFloat() / ep.duration else 0f
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF14141E), RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onEpisodeClick(ep) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Thumbnail left
                        Box(
                            modifier = Modifier
                                .size(width = 110.dp, height = 70.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black)
                        ) {
                            AsyncImage(
                                model = ep.backdropUrl ?: ep.posterUrl,
                                contentDescription = ep.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            // Small Play Indicator Overlay
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .align(Alignment.Center)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            
                            // Progress bar
                            if (epProgress > 0f) {
                                LinearProgressIndicator(
                                    progress = { epProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .align(Alignment.BottomCenter),
                                    color = Color(0xFF00A8E1),
                                    trackColor = Color.Transparent
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Episode Title, details right
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Episode ${ep.episodeNumber ?: 1}",
                                color = Color(0xFF00A8E1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = ep.title.substringAfter(" - ").substringAfter("S01E01").trim(),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = ep.overview ?: "No episode description available.",
                                color = Color.Gray,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Download Action on the right of the row
                        val isNetworkFile = ep.filePath.startsWith("http://") || ep.filePath.startsWith("https://")
                        if (isNetworkFile) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val isDownloaded = (ep.filePath.startsWith("/") && java.io.File(ep.filePath).exists()) || viewModel.getDownloadedFile(context, ep).exists()
                            val progress = downloadProgress[ep.id]
                            val isDownloading = progress != null

                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E1E2E))
                                    .clickable {
                                        if (isDownloaded) {
                                            viewModel.deleteDownloadedEpisode(context, ep)
                                        } else if (!isDownloading) {
                                            viewModel.downloadEpisode(context, ep)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isDownloading) {
                                    val currentProgress = progress ?: 0.01f
                                    if (currentProgress <= 0.02f) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = Color(0xFF00A8E1),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            progress = currentProgress,
                                            modifier = Modifier.size(24.dp),
                                            color = Color(0xFF00A8E1),
                                            strokeWidth = 2.dp,
                                            trackColor = Color.Gray.copy(alpha = 0.3f)
                                        )
                                    }
                                } else if (isDownloaded) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Downloaded Offline",
                                        tint = Color(0xFF2ECC71),
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDownward,
                                        contentDescription = "Download Offline",
                                        tint = Color.LightGray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShowCatalogCard(
    show: TvShow,
    onClick: () -> Unit,
    onFixMatch: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("show_card_${show.showName.replace(" ", "_").lowercase()}")
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF14141E))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF1E1E2F), Color(0xFF09090D))
                        )
                    )
                    .padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Tv,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF).copy(alpha = 0.6f),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = show.showName,
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            AsyncImage(
                model = show.posterUrl,
                contentDescription = show.showName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            
            // Type Badge
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .background(Color(0xFF00A8E1), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .align(Alignment.TopEnd)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "SHOW",
                        color = Color.Black,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Rating Badge
            if (show.rating != null && show.rating > 0) {
                Row(
                    modifier = Modifier
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .align(Alignment.BottomStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Rating",
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = String.format("%.1f", show.rating),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = show.showName,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${show.episodes.size} Episodes",
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
            IconButton(
                onClick = onFixMatch,
                modifier = Modifier.size(24.dp).testTag("fix_match_show_${show.showName.replace(" ", "_").lowercase()}")
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Fix Match",
                    tint = Color(0xFF00E5FF).copy(alpha = 0.8f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
fun MovieDetailsView(
    movie: MediaItem,
    onPlayClick: (MediaItem) -> Unit,
    onFixMatch: (MediaItem) -> Unit,
    onMarkNotMovie: (MediaItem) -> Unit,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler {
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color(0xFF09090D))
    ) {
        // Hero Backdrop with Back button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        ) {
            AsyncImage(
                model = movie.backdropUrl ?: movie.posterUrl,
                contentDescription = movie.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Gradient Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.5f),
                                Color.Transparent,
                                Color(0xFF09090D)
                            )
                        )
                    )
            )

            // Back button
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(top = 48.dp, start = 16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back to Catalog",
                    tint = Color.White
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Movie info top row (Poster + title and details side by side)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Poster
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF14141E))
                ) {
                    AsyncImage(
                        model = movie.posterUrl,
                        contentDescription = movie.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Title
                    Text(
                        text = movie.title,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Stats Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (movie.rating != null && movie.rating > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Rating",
                                    tint = Color(0xFFFFD54F),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = String.format("%.1f", movie.rating),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            text = movie.releaseDate?.substringBefore("-") ?: "Local Movie",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Box(
                            modifier = Modifier
                                .border(1.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "Ultra HD",
                                color = Color.Gray,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Source badge or offline subtitle info
                    if (!movie.subtitlePath.isNullOrEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Subtitles",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Subtitles synchronized",
                                color = Color(0xFF00E5FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Play Button
            Button(
                onClick = { onPlayClick(movie) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("movie_play_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A8E1)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Play Movie",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Fix Metadata / Match Online Button
            OutlinedButton(
                onClick = { onFixMatch(movie) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("movie_fix_match_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E2E3E)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Fix Match",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Fix Match (Edit Metadata)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { onMarkNotMovie(movie) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("movie_mark_not_movie_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Not a Movie",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Mark as Not a Movie",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!movie.director.isNullOrBlank()) {
                Text(
                    text = "Director: ${movie.director}",
                    color = Color(0xFF00E5FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            if (!movie.cast.isNullOrBlank()) {
                Text(
                    text = "Cast: ${movie.cast}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            // Movie description overview
            Text(
                text = movie.overview ?: "No description available for this movie.",
                color = Color.LightGray,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 100.dp)
            )
        }
    }
}

@Composable
fun MediaCatalogCard(
    item: MediaItem,
    onClick: () -> Unit,
    onFixMatch: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("media_card_${item.id}")
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF14141E))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF1E1E2F), Color(0xFF09090D))
                        )
                    )
                    .padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val phIcon = when (item.type) {
                    "SHOW_EPISODE" -> Icons.Default.Tv
                    "OTHER" -> Icons.Default.Info
                    else -> Icons.Default.Movie
                }
                Icon(
                    imageVector = phIcon,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF).copy(alpha = 0.6f),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.title,
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            
            // Type Badge
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .align(Alignment.TopEnd)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val badgeIcon = when (item.type) {
                        "SHOW_EPISODE" -> Icons.Default.Tv
                        "OTHER" -> Icons.Default.Info
                        else -> Icons.Default.Movie
                    }
                    val badgeText = when (item.type) {
                        "SHOW_EPISODE" -> "TV SHOW"
                        "OTHER" -> "OTHER"
                        else -> "MOVIE"
                    }
                    val badgeColor = when (item.type) {
                        "SHOW_EPISODE" -> Color(0xFF00FF88)
                        "OTHER" -> Color(0xFFFF9800)
                        else -> Color(0xFF00E5FF)
                    }
                    Icon(
                        imageVector = badgeIcon,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Rating Badge
            if (item.rating != null && item.rating > 0) {
                Row(
                    modifier = Modifier
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .align(Alignment.BottomStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Rating",
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = String.format("%.1f", item.rating),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.releaseDate?.substringBefore("-") ?: "Local Movie",
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
            IconButton(
                onClick = onFixMatch,
                modifier = Modifier.size(24.dp).testTag("fix_match_movie_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Fix Match",
                    tint = Color(0xFF00E5FF).copy(alpha = 0.8f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinueWatchingCard(
    item: MediaItem,
    onClick: () -> Unit,
    onRemoveFromContinueWatching: () -> Unit,
    onMarkAsFinished: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val progress = if (item.duration > 0) item.playbackPosition.toFloat() / item.duration else 0f
    
    Box {
        Card(
            modifier = Modifier
                .width(220.dp)
                .height(130.dp)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                )
                .testTag("continue_watching_card_${item.id}"),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14141E))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF1E1E2F), Color(0xFF09090D))
                            )
                        ),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (item.type == "SHOW_EPISODE") Icons.Default.Tv else Icons.Default.Movie,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF).copy(alpha = 0.4f),
                        modifier = Modifier.size(28.dp)
                    )
                }
                AsyncImage(
                    model = item.backdropUrl ?: item.posterUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                
                // Bottom translucent scrim
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                            )
                        )
                        .padding(8.dp)
                ) {
                    Column {
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (item.type == "SHOW_EPISODE") "Season ${item.season} Ep ${item.episodeNumber}" else "Movie",
                            color = Color.LightGray,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(CircleShape),
                            color = Color(0xFF00E5FF),
                            trackColor = Color.Gray.copy(alpha = 0.3f),
                        )
                    }
                }

                // Play overlay icon
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Resume",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.Center)
                    )
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.background(Color(0xFF1A1A26))
        ) {
            DropdownMenuItem(
                text = { Text("Resume Playback", color = Color.White) },
                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF00E5FF)) },
                onClick = {
                    showMenu = false
                    onClick()
                }
            )
            DropdownMenuItem(
                text = { Text("Remove from Continue Watching", color = Color.White) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5252)) },
                onClick = {
                    showMenu = false
                    onRemoveFromContinueWatching()
                }
            )
            DropdownMenuItem(
                text = { Text("Mark as Finished", color = Color.White) },
                leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00E5FF)) },
                onClick = {
                    showMenu = false
                    onMarkAsFinished()
                }
            )
        }
    }
}

@Composable
fun EmptyStateCard(
    onLoadDemo: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .testTag("empty_state_card"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14141E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Movie,
                contentDescription = "Empty Cinema",
                tint = Color(0xFF00E5FF).copy(alpha = 0.4f),
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Your library is empty",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Lumina Player is an offline-first media hub. You can trigger a scan of your device's folders or link/sync your personal Cloud Sources (such as TrueNAS or Google Drive) in the settings menu.",
                fontSize = 12.sp,
                color = Color.Gray,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("settings_navigate_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E2E3E)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Scan Storage & Link Cloud Sources", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun FixMatchDialog(
    movieItem: MediaItem?,
    tvShow: TvShow?,
    viewModel: MediaViewModel,
    apiKey: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var searchQuery by remember { 
        mutableStateOf(movieItem?.title ?: tvShow?.showName ?: "") 
    }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<com.example.data.service.MetadataService.UnifiedTmdbResult>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    
    var manualTmdbId by remember { mutableStateOf("") }
    var isFetchingById by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (searchQuery.isNotBlank()) {
            isSearching = true
            searchError = null
            try {
                val results = com.example.data.service.MetadataService.searchTmdb(
                    apiKey, 
                    searchQuery, 
                    if (movieItem != null) "MOVIE" else "TV"
                )
                searchResults = results
                if (results.isEmpty()) {
                    searchError = "No results found for '$searchQuery'"
                }
            } catch (e: Exception) {
                Log.e("FixMatch", "Auto search failed", e)
                searchError = "Search failed: ${e.message}"
            } finally {
                isSearching = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (movieItem != null) "Fix Match - Movie" else "Fix Match - TV Show",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (apiKey.isBlank()) {
                    Text(
                        text = "⚠ TMDb API Key is not configured in Settings. Please set one up to enable online search and custom ID linking.",
                        color = Color(0xFFFF9800),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Title of item being fixed
                Text(
                    text = "Current Name: ${movieItem?.title ?: tvShow?.showName}",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Search Box Row
                Text("Search online TMDb database:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Enter search query...", color = Color.Gray, fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF2E2E3E),
                            focusedContainerColor = Color(0xFF1B1B2A),
                            unfocusedContainerColor = Color(0xFF1B1B2A)
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )

                    Button(
                        onClick = {
                            if (apiKey.isBlank() && (com.example.BuildConfig.GEMINI_API_KEY.isBlank() || com.example.BuildConfig.GEMINI_API_KEY == "MY_GEMINI_API_KEY")) {
                                android.widget.Toast.makeText(context, "Please set TMDb API Key in Settings or configure Gemini API key", android.widget.Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            if (searchQuery.isBlank()) return@Button
                            isSearching = true
                            searchError = null
                            scope.launch {
                                val results = com.example.data.service.MetadataService.searchTmdb(
                                    apiKey, 
                                    searchQuery, 
                                    if (movieItem != null) "MOVIE" else "TV"
                                )
                                searchResults = results
                                isSearching = false
                                if (results.isEmpty()) {
                                    searchError = "No results found for '$searchQuery'"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text("Search", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Or manual TMDb ID input
                Spacer(modifier = Modifier.height(4.dp))
                Text("Or Link by TMDb ID directly:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = manualTmdbId,
                        onValueChange = { manualTmdbId = it },
                        placeholder = { Text("e.g. 550 for Fight Club", color = Color.Gray, fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF2E2E3E),
                            focusedContainerColor = Color(0xFF1B1B2A),
                            unfocusedContainerColor = Color(0xFF1B1B2A)
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )

                    Button(
                        onClick = {
                            val idVal = manualTmdbId.trim().toIntOrNull()
                            if (idVal == null) {
                                android.widget.Toast.makeText(context, "Please enter a valid numeric TMDb ID", android.widget.Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (apiKey.isBlank() && com.example.BuildConfig.GEMINI_API_KEY.isBlank()) {
                                android.widget.Toast.makeText(context, "Please set TMDB API Key in Settings or configure Gemini API Key first", android.widget.Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            isFetchingById = true
                            scope.launch {
                                try {
                                    if (movieItem != null) {
                                        val mTitle: String
                                        val mOverview: String?
                                        val mReleaseDate: String?
                                        val mRating: Float?
                                        val mPosterUrl: String?
                                        val mBackdropUrl: String?

                                        if (apiKey.isBlank() || apiKey == "MY_TMDB_API_KEY") {
                                            val gem = com.example.data.service.MetadataService.fetchByIdFromGemini(idVal, "MOVIE")
                                                ?: throw Exception("Gemini fetch failed")
                                            mTitle = gem.title
                                            mOverview = gem.overview
                                            mReleaseDate = gem.releaseDate
                                            mRating = gem.rating
                                            mPosterUrl = gem.posterUrl
                                            mBackdropUrl = gem.backdropUrl
                                        } else {
                                            val m = com.example.data.service.MetadataService.api.getMovieDetails(idVal, apiKey)
                                            mTitle = m.title ?: "Untitled Movie"
                                            mOverview = m.overview
                                            mReleaseDate = m.release_date
                                            mRating = m.vote_average
                                            mPosterUrl = m.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                                            mBackdropUrl = m.backdrop_path?.let { "https://image.tmdb.org/t/p/w1280$it" }
                                        }

                                        viewModel.updateMovieMetadata(
                                            id = movieItem.id,
                                            title = mTitle,
                                            overview = mOverview,
                                            releaseDate = mReleaseDate,
                                            rating = mRating,
                                            posterUrl = mPosterUrl,
                                            backdropUrl = mBackdropUrl
                                        )
                                        android.widget.Toast.makeText(context, "Linked to '$mTitle' successfully!", android.widget.Toast.LENGTH_LONG).show()
                                    } else if (tvShow != null) {
                                        val tName: String
                                        val tOverview: String?
                                        val tReleaseDate: String?
                                        val tRating: Float?
                                        val tPosterUrl: String?
                                        val tBackdropUrl: String?

                                        if (apiKey.isBlank() || apiKey == "MY_TMDB_API_KEY") {
                                            val gem = com.example.data.service.MetadataService.fetchByIdFromGemini(idVal, "TV")
                                                ?: throw Exception("Gemini fetch failed")
                                            tName = gem.title
                                            tOverview = gem.overview
                                            tReleaseDate = gem.releaseDate
                                            tRating = gem.rating
                                            tPosterUrl = gem.posterUrl
                                            tBackdropUrl = gem.backdropUrl
                                        } else {
                                            val t = com.example.data.service.MetadataService.api.getTvShowDetails(idVal, apiKey)
                                            tName = t.name ?: "Untitled TV Show"
                                            tOverview = t.overview
                                            tReleaseDate = t.first_air_date
                                            tRating = t.vote_average
                                            tPosterUrl = t.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                                            tBackdropUrl = t.backdrop_path?.let { "https://image.tmdb.org/t/p/w1280$it" }
                                        }

                                        viewModel.updateShowMetadata(
                                            oldShowName = tvShow.showName,
                                            newShowName = tName,
                                            overview = tOverview,
                                            releaseDate = tReleaseDate,
                                            rating = tRating,
                                            posterUrl = tPosterUrl,
                                            backdropUrl = tBackdropUrl,
                                            tmdbId = idVal
                                        )
                                        android.widget.Toast.makeText(context, "Linked to '$tName' successfully! Syncing episodes...", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    onDismiss()
                                } catch (e: Exception) {
                                    Log.e("FixMatch", "Failed to fetch details for TMDB ID $idVal", e)
                                    android.widget.Toast.makeText(context, "Failed to find TMDb item with ID $idVal", android.widget.Toast.LENGTH_LONG).show()
                                } finally {
                                    isFetchingById = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF88)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text("Link ID", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (isSearching || isFetchingById) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF00E5FF))
                    }
                }

                if (searchError != null) {
                    Text(text = searchError!!, color = Color.Red, fontSize = 11.sp)
                }

                // Results list
                if (searchResults.isNotEmpty()) {
                    Text(
                        text = "Search Results (${searchResults.size}):",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        searchResults.forEach { result ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1B1B2A), RoundedCornerShape(8.dp))
                                    .clickable {
                                        scope.launch {
                                            if (movieItem != null) {
                                                if (result.type == "TV") {
                                                    val parsed = com.example.data.service.MetadataService.parseFilename(movieItem.fileName)
                                                    val sNum = parsed.season ?: 1
                                                    val eNum = parsed.episode ?: 1
                                                    val displayTitle = "${result.title} - S${String.format("%02d", sNum)}E${String.format("%02d", eNum)}"
                                                    viewModel.updateMovieMetadata(
                                                        id = movieItem.id,
                                                        title = displayTitle,
                                                        overview = result.overview,
                                                        releaseDate = result.releaseDate,
                                                        rating = result.rating,
                                                        posterUrl = result.posterUrl,
                                                        backdropUrl = result.backdropUrl,
                                                        type = "SHOW_EPISODE",
                                                        showName = result.title,
                                                        season = sNum,
                                                        episodeNumber = eNum
                                                    )
                                                    android.widget.Toast.makeText(context, "Linked to TV Show successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    viewModel.updateMovieMetadata(
                                                        id = movieItem.id,
                                                        title = result.title,
                                                        overview = result.overview,
                                                        releaseDate = result.releaseDate,
                                                        rating = result.rating,
                                                        posterUrl = result.posterUrl,
                                                        backdropUrl = result.backdropUrl,
                                                        type = "MOVIE"
                                                    )
                                                    android.widget.Toast.makeText(context, "Linked to Movie successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            } else if (tvShow != null) {
                                                viewModel.updateShowMetadata(
                                                    oldShowName = tvShow.showName,
                                                    newShowName = result.title,
                                                    overview = result.overview,
                                                    releaseDate = result.releaseDate,
                                                    rating = result.rating,
                                                    posterUrl = result.posterUrl,
                                                    backdropUrl = result.backdropUrl,
                                                    tmdbId = result.id
                                                )
                                                android.widget.Toast.makeText(context, "Linked successfully! Syncing episodes...", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            onDismiss()
                                        }
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp, 60.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Black)
                                ) {
                                    AsyncImage(
                                        model = result.posterUrl,
                                        contentDescription = result.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = result.title,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Release: ${result.releaseDate ?: "N/A"}",
                                        color = Color.Gray,
                                        fontSize = 10.sp
                                    )
                                    if (result.overview != null) {
                                        Text(
                                            text = result.overview,
                                            color = Color.LightGray,
                                            fontSize = 9.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            lineHeight = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        },
        containerColor = Color(0xFF14141F)
    )
}
