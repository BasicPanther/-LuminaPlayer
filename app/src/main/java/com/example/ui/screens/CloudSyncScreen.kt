package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.CloudSource
import com.example.ui.viewmodel.MediaViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.net.Inet4Address
import java.net.Socket
import java.net.InetSocketAddress

private fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    val ip = address.hostAddress
                    if (ip != null && ip.isNotBlank() && ip != "127.0.0.1") {
                        return ip
                    }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("NasScanner", "Error getting local IP", e)
    }
    return "192.168.1.100" // Fallback standard IP if none detected
}

@Composable
fun CloudSyncScreen(
    viewModel: MediaViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cloudSources by viewModel.cloudSources.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

    var showTrueNasDialog by remember { mutableStateOf(false) }
    var trueNasUrl by remember { mutableStateOf("http://truenas.local:8080/dav") }
    var trueNasName by remember { mutableStateOf("TrueNAS Storage") }
    var trueNasUsername by remember { mutableStateOf("admin") }
    var trueNasPassword by remember { mutableStateOf("") }

    var isNasScanning by remember { mutableStateOf(false) }
    var nasScanProgressText by remember { mutableStateOf("") }
    var discoveredServers by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedDiscoveredServer by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showNasManualInput by remember { mutableStateOf(false) }

    LaunchedEffect(isNasScanning) {
        if (isNasScanning) {
            discoveredServers = emptyList()
            val localIp = getLocalIpAddress()
            val baseIp = localIp.substringBeforeLast(".") + "."
            nasScanProgressText = "Detecting subnet... Scanning ${baseIp}1 to ${baseIp}254..."
            kotlinx.coroutines.delay(500)
            
            nasScanProgressText = "Searching subnet ${baseIp}0/24 on WebDAV ports (8080/5005/80)..."
            
            val foundServers = withContext(Dispatchers.IO) {
                (1..254).map { octet ->
                    async {
                        val ip = "$baseIp$octet"
                        if (ip == localIp) return@async null
                        
                        val portsToTry = listOf(8080, 5005, 80)
                        for (port in portsToTry) {
                            try {
                                val socket = Socket()
                                socket.connect(InetSocketAddress(ip, port), 120) // 120ms timeout per socket
                                socket.close()
                                
                                val hostName = when (port) {
                                    5005 -> "Synology NAS ($ip)"
                                    8080 -> "TrueNAS Core/Scale ($ip)"
                                    else -> "WebDAV Storage ($ip)"
                                }
                                return@async hostName to "http://$ip:$port/dav"
                            } catch (e: Exception) {
                                // Try next port
                            }
                        }
                        null
                    }
                }.awaitAll().filterNotNull()
            }
            
            val finalServers = foundServers.toMutableList()
            if (finalServers.isEmpty()) {
                nasScanProgressText = "Scan completed. No active WebDAV servers detected. Showing demo defaults..."
                kotlinx.coroutines.delay(1000)
                finalServers.add("TrueNAS Core (truenas.local)" to "http://truenas.local:8080/dav")
                finalServers.add("TrueNAS Scale (192.168.1.144)" to "http://192.168.1.144:8080/dav")
                finalServers.add("Synology DiskStation (192.168.1.100)" to "http://192.168.1.100:5005/dav")
                finalServers.add("Local WebDAV Server (192.168.1.10)" to "http://192.168.1.10/dav")
            } else {
                nasScanProgressText = "Scan completed! Found ${finalServers.size} active storage servers on your network."
                kotlinx.coroutines.delay(1000)
            }
            
            discoveredServers = finalServers
            isNasScanning = false
        }
    }

    var showGoogleDriveDialog by remember { mutableStateOf(false) }
    var googleDriveName by remember { mutableStateOf("My Google Drive") }
    var googleDriveUrl by remember { mutableStateOf("") }
    var googleDriveToken by remember { mutableStateOf("") }

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
            Spacer(modifier = Modifier.height(56.dp)) // Status bar

            // Header Section
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = "CLOUD & LOCAL NAS",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                    color = Color.White
                )
                Text(
                    text = "Synchronize playback history & library states cross-device",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            // Sync Status Terminal Banner
            AnimatedVisibility(visible = isSyncing || syncStatus.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141A26)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color(0xFF00E5FF),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Synced",
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "LuminaSync Terminal",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E5FF)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = syncStatus,
                            fontSize = 11.sp,
                            color = Color.LightGray,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Cloud Storage Options Title
            Text(
                text = "Connect New Service",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 16.dp, bottom = 10.dp)
            )

            // Connection Buttons Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CloudConnectorCard(
                    title = "Google Drive",
                    icon = Icons.Default.Cloud,
                    tint = Color(0xFF34A853),
                    modifier = Modifier.weight(1f),
                    onClick = { showGoogleDriveDialog = true }
                )
                CloudConnectorCard(
                    title = "Dropbox",
                    icon = Icons.Default.Cloud,
                    tint = Color(0xFF0061FE),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.connectCloudAccount(
                            type = "DROPBOX",
                            name = "My Dropbox"
                        )
                    }
                )
                CloudConnectorCard(
                    title = "TrueNAS WebDAV",
                    icon = Icons.Default.Storage,
                    tint = Color(0xFFFF9100),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showTrueNasDialog = true
                        isNasScanning = true
                        selectedDiscoveredServer = null
                        showNasManualInput = false
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Connected Services list
            Text(
                text = "Connected Storages",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            if (cloudSources.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(Color(0xFF14141E), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No storages connected. Working entirely offline.",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(cloudSources) { source ->
                        ConnectedSourceCard(
                            source = source,
                            onSync = { viewModel.syncCloudSource(context, source) },
                            onDelete = { viewModel.disconnectCloudAccount(source.id) }
                        )
                    }
                }
            }
        }
    }

    // TrueNAS Connection Dialog (VLC-style)
    if (showTrueNasDialog) {
        AlertDialog(
            onDismissRequest = { showTrueNasDialog = false },
            title = {
                Text(
                    text = when {
                        isNasScanning -> "Scanning Local Network..."
                        selectedDiscoveredServer == null && !showNasManualInput -> "Discovered Local Servers"
                        else -> "Enter Server Credentials"
                    },
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            containerColor = Color(0xFF14141F),
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when {
                        isNasScanning -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color(0xFFFF9100),
                                    strokeWidth = 2.5.dp
                                )
                                Text(
                                    text = nasScanProgressText,
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                text = "Lumina Player is scanning local subnets for TrueNAS, SMB, or WebDAV media pools...",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                        selectedDiscoveredServer == null && !showNasManualInput -> {
                            Text(
                                text = "Select an automatically discovered local server below, or enter coordinates manually:",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            
                            discoveredServers.forEach { server ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedDiscoveredServer = server
                                            trueNasName = server.first.substringBefore(" (")
                                            trueNasUrl = server.second
                                        },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F2F)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Storage,
                                            contentDescription = "Server",
                                            tint = Color(0xFFFF9100),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column {
                                            Text(
                                                text = server.first,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = server.second,
                                                color = Color.Gray,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            OutlinedButton(
                                onClick = { showNasManualInput = true },
                                modifier = Modifier.fillMaxWidth(),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) {
                                Text("Enter Server IP Manually", fontSize = 12.sp)
                            }
                        }
                        else -> {
                            // Credentials entry
                            Text(
                                text = "Connecting to ${selectedDiscoveredServer?.first ?: "Custom Server"}",
                                color = Color(0xFFFF9100),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            
                            OutlinedTextField(
                                value = trueNasName,
                                onValueChange = { trueNasName = it },
                                label = { Text("Storage Name") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFFF9100),
                                    unfocusedBorderColor = Color.Gray
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = trueNasUrl,
                                onValueChange = { trueNasUrl = it },
                                label = { Text("Server URL / WebDAV Address") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFFF9100),
                                    unfocusedBorderColor = Color.Gray
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = trueNasUsername,
                                onValueChange = { trueNasUsername = it },
                                label = { Text("Username") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFFF9100),
                                    unfocusedBorderColor = Color.Gray
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = trueNasPassword,
                                onValueChange = { trueNasPassword = it },
                                label = { Text("Password") },
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFFF9100),
                                    unfocusedBorderColor = Color.Gray
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (isNasScanning) {
                    // No confirm button while scanning
                } else if (selectedDiscoveredServer == null && !showNasManualInput) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { isNasScanning = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9100))
                        ) {
                            Text("Rescan", color = Color.Black)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            var formattedUrl = trueNasUrl.trim()
                            if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
                                formattedUrl = "http://$formattedUrl"
                            }
                            // Assemble URL with credentials to mimic VLC/WebDAV connection string or store as is
                            val assembledUrl = if (trueNasUsername.isNotBlank() && trueNasPassword.isNotBlank()) {
                                // Convert http://ip:port/dav to http://username:password@ip:port/dav
                                try {
                                    val cleanUrl = formattedUrl.removePrefix("http://").removePrefix("https://")
                                    val prefix = if (formattedUrl.startsWith("https://")) "https://" else "http://"
                                    "$prefix$trueNasUsername:$trueNasPassword@$cleanUrl"
                                } catch (e: Exception) {
                                    formattedUrl
                                }
                            } else {
                                formattedUrl
                            }
                            viewModel.connectCloudAccount(
                                type = "TRUENAS",
                                name = trueNasName,
                                url = assembledUrl
                            )
                            showTrueNasDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9100))
                    ) {
                        Text("Connect", color = Color.Black)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (selectedDiscoveredServer != null || showNasManualInput) {
                            // Go back to list
                            selectedDiscoveredServer = null
                            showNasManualInput = false
                        } else {
                            showTrueNasDialog = false
                        }
                    }
                ) {
                    Text(
                        text = if (selectedDiscoveredServer != null || showNasManualInput) "Back to List" else "Cancel",
                        color = Color.LightGray
                    )
                }
            }
        )
    }

    // Google Drive Connection Dialog
    if (showGoogleDriveDialog) {
        AlertDialog(
            onDismissRequest = { showGoogleDriveDialog = false },
            title = { Text("Connect Google Drive", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            containerColor = Color(0xFF14141F),
            text = {
                Column {
                    Text(
                        text = "Paste a shared Google Drive folder link, file link, or directory ID below to index and stream videos directly inside Lumina Player.",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = googleDriveName,
                        onValueChange = { googleDriveName = it },
                        label = { Text("Storage Label Name") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = googleDriveUrl,
                        onValueChange = { googleDriveUrl = it },
                        label = { Text("Google Drive Folder or File URL") },
                        placeholder = { Text("https://drive.google.com/drive/folders/...", color = Color.DarkGray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = googleDriveToken,
                        onValueChange = { googleDriveToken = it },
                        label = { Text("API Key / OAuth Token (Optional)") },
                        placeholder = { Text("Bearer ya29... or Google API Key", color = Color.DarkGray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.connectCloudAccount(
                            type = "GOOGLE_DRIVE",
                            name = googleDriveName,
                            url = googleDriveUrl,
                            token = googleDriveToken
                        )
                        showGoogleDriveDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                ) {
                    Text("Connect & Scan", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showGoogleDriveDialog = false }) {
                    Text("Cancel", color = Color.LightGray)
                }
            }
        )
    }
}

@Composable
fun CloudConnectorCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("connector_card_${title.replace(" ", "_").lowercase()}"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14141E))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = tint,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
fun ConnectedSourceCard(
    source: CloudSource,
    onSync: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connected_source_${source.id}"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14141E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            when (source.type) {
                                "GOOGLE_DRIVE" -> Color(0xFF34A853)
                                "DROPBOX" -> Color(0xFF0061FE)
                                else -> Color(0xFFFF9100)
                            }.copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (source.type == "TRUENAS") Icons.Default.Storage else Icons.Default.Cloud,
                        contentDescription = source.type,
                        tint = when (source.type) {
                            "GOOGLE_DRIVE" -> Color(0xFF34A853)
                            "DROPBOX" -> Color(0xFF0061FE)
                            else -> Color(0xFFFF9100)
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = source.name,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (source.lastSynced > 0) "Synced: ${java.text.SimpleDateFormat("MMM dd, HH:mm").format(java.util.Date(source.lastSynced))}" else "Never Synced",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }
            }

            Row {
                IconButton(
                    onClick = onSync,
                    modifier = Modifier.testTag("sync_button_${source.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Sync",
                        tint = Color(0xFF00E5FF)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_button_${source.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Disconnect",
                        tint = Color.Red.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
