package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.MediaItem
import com.example.ui.viewmodel.MediaViewModel

@Composable
fun FolderBrowserScreen(
    viewModel: MediaViewModel,
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val allMedia by viewModel.allMedia.collectAsStateWithLifecycle()
    
    // We can extract folders dynamically from allMedia
    val folders = remember(allMedia) {
        allMedia.map { it.folderPath }.distinct().sorted()
    }

    // Current navigation state
    // If null, we are showing the list of folders
    // If not null, we are showing the list of MediaItems in that folder
    var selectedFolder by remember { mutableStateOf<String?>(null) }

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
            Spacer(modifier = Modifier.height(56.dp)) // status bar padding

            // Header Section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedFolder != null) {
                    IconButton(
                        onClick = { selectedFolder = null },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .background(Color(0xFF1B1B2A), RoundedCornerShape(8.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFF00E5FF)
                        )
                    }
                }

                Column {
                    Text(
                        text = if (selectedFolder == null) "FOLDER NAVIGATION" else "FOLDER CONTENTS",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp,
                        color = Color.White
                    )
                    Text(
                        text = selectedFolder ?: "Browse directories of local media",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (folders.isEmpty()) {
                // Empty folder navigation
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = Color.Gray.copy(alpha = 0.3f),
                            modifier = Modifier.size(80.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No directories indexed yet",
                            color = Color.LightGray,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Initialize sample media or trigger a scan first.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            } else if (selectedFolder == null) {
                // Folder browsing mode
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(folders) { folder ->
                        val count = allMedia.count { it.folderPath == folder }
                        FolderRow(
                            folderPath = folder,
                            mediaCount = count,
                            onClick = { selectedFolder = folder }
                        )
                    }
                }
            } else {
                // Inside a folder mode
                val folderItems = remember(allMedia, selectedFolder) {
                    allMedia.filter { it.folderPath == selectedFolder }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(folderItems) { item ->
                        MediaFileRow(
                            item = item,
                            onClick = { onMediaClick(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FolderRow(
    folderPath: String,
    mediaCount: Int,
    onClick: () -> Unit
) {
    val folderName = folderPath.substringAfterLast("/")
    val displayFolderName = if (folderName.isEmpty()) folderPath else folderName

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("folder_row_$folderPath"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14141E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF00E5FF).copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Folder",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = displayFolderName,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$mediaCount files indexed",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun MediaFileRow(
    item: MediaItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("file_row_${item.id}"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14141E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFFFFD54F).copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = "Movie",
                    tint = Color(0xFFFFD54F),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.fileName,
                    color = Color.Gray,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            if (item.playbackPosition > 0) {
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(Color(0xFF00E5FF).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    val progressPercent = if (item.duration > 0) (item.playbackPosition * 100 / item.duration).toInt() else 0
                    Text(
                        text = "${progressPercent}%",
                        color = Color(0xFF00E5FF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
