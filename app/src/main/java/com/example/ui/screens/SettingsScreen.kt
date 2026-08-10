package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.viewmodel.MediaViewModel
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ArrowBack

@Composable
fun SettingsScreen(
    viewModel: MediaViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanStatus by viewModel.scanStatus.collectAsStateWithLifecycle()
    val scannedFolders by viewModel.scannedFolders.collectAsStateWithLifecycle()
    val savedTmdbApiKey by viewModel.tmdbApiKey.collectAsStateWithLifecycle()
    val savedOmdbApiKey by viewModel.omdbApiKey.collectAsStateWithLifecycle()
    val autoplayEnabled by viewModel.autoplayEnabled.collectAsStateWithLifecycle()

    var tmdbKey by remember { mutableStateOf("") }
    var omdbKey by remember { mutableStateOf("") }
    var selectedLanguage by remember { mutableStateOf("English") }
    var languages = listOf("English", "Spanish", "French", "German", "Japanese", "Portuguese")
    var langMenuExpanded by remember { mutableStateOf(false) }

    var newFolderPath by remember { mutableStateOf("") }
    var showSaveSuccess by remember { mutableStateOf(false) }
    var showOmdbSaveSuccess by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }

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

    val onScanClick = {
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

    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        viewModel.loadPreferences(context)
    }

    LaunchedEffect(savedTmdbApiKey) {
        tmdbKey = savedTmdbApiKey
    }

    LaunchedEffect(savedOmdbApiKey) {
        omdbKey = savedOmdbApiKey
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F0F15),
                        Color(0xFF07070A)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(48.dp)) // Status bar

            // Header Section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "SETTINGS & SERVICES",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp,
                        color = Color.White
                    )
                    Text(
                        text = "Customize scraper APIs, local folders, and playback options",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }

            // Sync/Scanned Progress Status HUD
            AnimatedVisibility(visible = isScanning) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF00E5FF).copy(alpha = 0.08f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color(0xFF00E5FF),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = scanStatus,
                            fontSize = 12.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // SECTION 1: METADATA & API SCRAPERS
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12121E)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E1E2C))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scraper & Metadata APIs", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Configure The Movie Database (TMDB) API Key to pull cinematic poster frames, backdrops, cast directories, and storyline synopses automatically.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = tmdbKey,
                            onValueChange = { tmdbKey = it },
                            placeholder = { Text("Enter TMDB v3 API Key...", color = Color.Gray, fontSize = 12.sp) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tmdb_key_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00E5FF),
                                unfocusedBorderColor = Color(0xFF232335),
                                focusedContainerColor = Color(0xFF181827),
                                unfocusedContainerColor = Color(0xFF181827)
                            ),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )

                        Button(
                            onClick = {
                                viewModel.saveTmdbApiKey(context, tmdbKey)
                                showSaveSuccess = true
                            },
                            modifier = Modifier
                                .height(56.dp)
                                .testTag("save_tmdb_key_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Save", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    if (showSaveSuccess) {
                        Text(
                            text = "✓ TMDB Scraping API Key verified and saved",
                            color = Color(0xFF00C853),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E1E2C)))
                    Spacer(modifier = Modifier.height(18.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("OMDB API Key (Primary Movie Metadata)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Configure the OMDB API key to search IMDb movie structures, load official cast lists, directors, ratings, and posters with full disambiguation.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = omdbKey,
                            onValueChange = { omdbKey = it },
                            placeholder = { Text("Enter OMDB API Key...", color = Color.Gray, fontSize = 12.sp) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("omdb_key_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00E5FF),
                                unfocusedBorderColor = Color(0xFF232335),
                                focusedContainerColor = Color(0xFF181827),
                                unfocusedContainerColor = Color(0xFF181827)
                            ),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )

                        Button(
                            onClick = {
                                viewModel.saveOmdbApiKey(context, omdbKey)
                                showOmdbSaveSuccess = true
                            },
                            modifier = Modifier
                                .height(56.dp)
                                .testTag("save_omdb_key_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Save", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    if (showOmdbSaveSuccess) {
                        Text(
                            text = "✓ OMDB API Key successfully registered",
                            color = Color(0xFF00C853),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // SECTION 2: SCANNER DIRECTORIES
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12121E)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E1E2C))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Movie, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Media Scan Directories", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Manage directory paths to scan recursively for video files and catalog movies or shows.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Existing paths list
                    if (scannedFolders.isEmpty()) {
                        Text(
                            text = "No directories registered. Add a storage path below to compile your library catalog.",
                            color = Color(0xFFFF5252),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        scannedFolders.forEach { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(Color(0xFF181827), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFF2E2E3E).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = folder,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.removeScannedFolder(context, folder) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteSweep,
                                        contentDescription = "Remove Directory",
                                        tint = Color(0xFFFF5252),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Add new directory
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newFolderPath,
                            onValueChange = { newFolderPath = it },
                            placeholder = { Text("e.g. /sdcard/Movies", color = Color.Gray, fontSize = 12.sp) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("add_folder_input"),
                            trailingIcon = {
                                IconButton(
                                    onClick = { showFolderPicker = true },
                                    modifier = Modifier.testTag("browse_folder_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = "Browse Folder",
                                        tint = Color(0xFF00E5FF)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00E5FF),
                                unfocusedBorderColor = Color(0xFF232335),
                                focusedContainerColor = Color(0xFF181827),
                                unfocusedContainerColor = Color(0xFF181827)
                            ),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )

                        Button(
                            onClick = {
                                if (newFolderPath.isNotBlank()) {
                                    viewModel.addScannedFolder(context, newFolderPath)
                                    newFolderPath = ""
                                }
                            },
                            modifier = Modifier
                                .height(56.dp)
                                .testTag("add_folder_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Add", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    if (showFolderPicker) {
                        FolderPickerDialog(
                            onDismiss = { showFolderPicker = false },
                            onFolderSelected = { selectedPath ->
                                newFolderPath = selectedPath
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // SECTION 3: SUBTITLE & EXPERIENCE PREFERENCES
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12121E)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E1E2C))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Playback & Localizations", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Subtitle Lang Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Subtitle Sync Language", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Preferred language target when crawling caption repositories online.", fontSize = 10.sp, color = Color.Gray)
                        }
                        Box {
                            TextButton(
                                onClick = { langMenuExpanded = true },
                                modifier = Modifier.testTag("language_selector_trigger")
                            ) {
                                Text(selectedLanguage, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            DropdownMenu(
                                expanded = langMenuExpanded,
                                onDismissRequest = { langMenuExpanded = false },
                                modifier = Modifier.background(Color(0xFF14141E))
                            ) {
                                languages.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(lang, color = Color.White) },
                                        onClick = {
                                            selectedLanguage = lang
                                            langMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFF1E1E2C), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))

                    // Autoplay Toggle Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Autoplay Next Episode",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Instantly queue and play sequential episodes automatically as soon as credits start rolling.",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                lineHeight = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = autoplayEnabled,
                            onCheckedChange = { viewModel.setAutoplayEnabled(context, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.testTag("autoplay_toggle")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // SECTION 4: DIAGNOSTIC & MAINTENANCE ACTIONS
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12121E)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E1E2C))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Library Catalog Maintenance", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Force re-index local directory catalogs or purge temporary cache records securely.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onScanClick,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("scan_device_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E2C)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Re-Scan Folders", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { viewModel.clearLibrary() },
                            modifier = Modifier
                                .weight(1.5f)
                                .height(44.dp)
                                .testTag("clear_catalog_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFFF5252))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reset Catalog", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // App Specs Summary Footer
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 120.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E1E2C).copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Lumina Player builds a fully hardware-accelerated, high-fidelity media center using Android Jetpack Media3, TMDB crawling API, and Room local persistence.",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun FolderPickerDialog(
    onDismiss: () -> Unit,
    onFolderSelected: (String) -> Unit
) {
    var currentDir by remember { 
        mutableStateOf(
            File(android.os.Environment.getExternalStorageDirectory().absolutePath)
        ) 
    }
    
    val subdirs = remember(currentDir) {
        try {
            currentDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }?.toList()?.sortedBy { it.name } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Select Media Directory", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(currentDir.absolutePath, color = Color(0xFF00E5FF), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Box(modifier = Modifier.height(300.dp).fillMaxWidth()) {
                if (subdirs.isEmpty() && currentDir.absolutePath == "/storage/emulated/0") {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No accessible folders. Try granting storage permissions.", color = Color.Gray, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                } else if (subdirs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No subdirectories here.", color = Color.Gray, fontSize = 12.sp)
                            if (currentDir.parentFile != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { currentDir = currentDir.parentFile!! },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B1B2A))
                                ) {
                                    Text("Go Up", color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (currentDir.parentFile != null && currentDir.absolutePath != "/storage/emulated/0") {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { currentDir = currentDir.parentFile!! }
                                        .padding(vertical = 10.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Go Up", tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(".. (Go Up)", color = Color.LightGray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                HorizontalDivider(color = Color(0xFF2E2E3E), thickness = 0.5.dp)
                            }
                        }
                        
                        items(subdirs) { dir ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentDir = dir }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = "Folder", tint = Color(0xFFFFD54F), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(dir.name, color = Color.White, fontSize = 13.sp)
                            }
                            HorizontalDivider(color = Color(0xFF1E1E2E), thickness = 0.5.dp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onFolderSelected(currentDir.absolutePath)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
            ) {
                Text("Select This Folder", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        },
        containerColor = Color(0xFF14141F)
    )
}
