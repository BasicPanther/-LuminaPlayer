package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.viewmodel.MediaViewModel

@Composable
fun MainScreen(
    viewModel: MediaViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    var activePlayerMediaId by remember { mutableStateOf<String?>(null) }

    val isScanning by viewModel.isScanning.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()

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
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
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
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
                        icon = { Icon(Icons.Default.CloudQueue, contentDescription = "Sync") },
                        label = { Text("Cloud", fontSize = 10.sp) },
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
                        modifier = Modifier.fillMaxSize()
                    )
                    1 -> FolderBrowserScreen(
                        viewModel = viewModel,
                        onMediaClick = { activePlayerMediaId = it.id },
                        modifier = Modifier.fillMaxSize()
                    )
                    2 -> CloudSyncScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                    3 -> SettingsScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
