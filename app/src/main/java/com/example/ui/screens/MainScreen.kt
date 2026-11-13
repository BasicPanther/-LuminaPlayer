package com.example.ui.screens

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.viewmodel.MediaViewModel
import kotlinx.coroutines.delay

@Composable
fun MainScreen(
    viewModel: MediaViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedTab by remember { mutableStateOf(0) }
    var activePlayerMediaId by remember { mutableStateOf<String?>(null) }

    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanStatus by viewModel.scanStatus.collectAsStateWithLifecycle()
    val autoWatchEnabled by viewModel.autoWatchEnabled.collectAsStateWithLifecycle()
    val lastScanBanner by viewModel.lastScanBanner.collectAsStateWithLifecycle()

    // Start MediaStore ContentObserver + FileObservers for instant new-file detection
    LaunchedEffect(autoWatchEnabled) {
        if (autoWatchEnabled) {
            viewModel.startWatchingMedia(context)
        } else {
            viewModel.stopWatchingMedia()
        }
    }

    // Automatically run an ultra-fast incremental scan whenever the app returns to the foreground
    DisposableEffect(lifecycleOwner, autoWatchEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && autoWatchEnabled) {
                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    android.Manifest.permission.READ_MEDIA_VIDEO
                } else {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                }
                val hasStoragePerm = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                if (hasStoragePerm) {
                    viewModel.scanNewMediaOnly(context, silent = true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Auto-dismiss detection banner after 4 seconds
    LaunchedEffect(lastScanBanner) {
        if (lastScanBanner.isNotBlank()) {
            delay(4000)
            viewModel.clearScanBanner()
        }
    }

    if (activePlayerMediaId != null) {
        androidx.activity.compose.BackHandler {
            activePlayerMediaId = null
        }
        // Render Fullscreen player
        PlayerScreen(
            viewModel = viewModel,
            mediaItemId = activePlayerMediaId!!,
            onPlayNext = { activePlayerMediaId = it },
            onBack = { activePlayerMediaId = null },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        if (selectedTab != 0) {
            androidx.activity.compose.BackHandler {
                selectedTab = 0
            }
        }
        // Standard Tab navigation Layout
        Scaffold(
            modifier = modifier.fillMaxSize(),
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = lastScanBanner.isNotBlank() && !isScanning,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Surface(
                            color = Color(0xFF0E241C),
                            border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.45f)),
                            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF00E676),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = lastScanBanner,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.clearScanBanner() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss notification",
                                        tint = Color.LightGray,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (isScanning) {
                        Surface(
                            color = Color(0xFF141923),
                            tonalElevation = 6.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        color = Color(0xFF00E5FF),
                                        strokeWidth = 1.5.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = scanStatus.ifBlank { "Scanning storage..." },
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    color = Color(0xFF00E5FF),
                                    trackColor = Color.White.copy(alpha = 0.1f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                )
                            }
                        }
                    }

                    NavigationBar(
                        containerColor = Color(0xFF101018),
                        tonalElevation = 8.dp,
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .testTag("main_navigation_bar")
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                            label = { Text("Home", fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF00E5FF),
                                unselectedIconColor = Color.Gray,
                                selectedTextColor = Color(0xFF00E5FF),
                                unselectedTextColor = Color.Gray,
                                indicatorColor = Color(0xFF1B1B2A)
                            ),
                            modifier = Modifier.testTag("tab_home")
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.FolderOpen, contentDescription = "Library") },
                            label = { Text("Library", fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF00E5FF),
                                unselectedIconColor = Color.Gray,
                                selectedTextColor = Color(0xFF00E5FF),
                                unselectedTextColor = Color.Gray,
                                indicatorColor = Color(0xFF1B1B2A)
                            ),
                            modifier = Modifier.testTag("tab_library")
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.CloudQueue, contentDescription = "Network") },
                            label = { Text("Network", fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF00E5FF),
                                unselectedIconColor = Color.Gray,
                                selectedTextColor = Color(0xFF00E5FF),
                                unselectedTextColor = Color.Gray,
                                indicatorColor = Color(0xFF1B1B2A)
                            ),
                            modifier = Modifier.testTag("tab_cloud")
                        )
                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                            label = { Text("Settings", fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF00E5FF),
                                unselectedIconColor = Color.Gray,
                                selectedTextColor = Color(0xFF00E5FF),
                                unselectedTextColor = Color.Gray,
                                indicatorColor = Color(0xFF1B1B2A)
                            ),
                            modifier = Modifier.testTag("tab_settings")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0A0F))
            ) {
                when (selectedTab) {
                    0 -> DashboardScreen(
                        viewModel = viewModel,
                        onMediaClick = { activePlayerMediaId = it.id },
                        onNavigateToSettings = { selectedTab = 3 },
                        onNavigateToCloud = { selectedTab = 2 },
                        modifier = Modifier.fillMaxSize()
                    )
                    1 -> FolderBrowserScreen(
                        viewModel = viewModel,
                        onMediaClick = { activePlayerMediaId = it.id },
                        modifier = Modifier.fillMaxSize()
                    )
                    2 -> CloudSyncScreen(
                        viewModel = viewModel,
                        onMediaClick = { activePlayerMediaId = it.id },
                        modifier = Modifier.fillMaxSize()
                    )
                    3 -> SettingsScreen(
                        viewModel = viewModel,
                        onNavigateToCloud = { selectedTab = 2 },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

