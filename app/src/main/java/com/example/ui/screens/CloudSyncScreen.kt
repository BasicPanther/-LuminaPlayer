package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.CloudSource
import com.example.data.model.MediaItem
import com.example.data.service.CloudSyncService
import com.example.data.service.SmbNetworkService
import com.example.ui.viewmodel.MediaViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

private data class DiscoveredLanServer(
    val label: String,
    val protocol: String, // "SMB" or "WEBDAV"
    val host: String,
    val port: Int,
    val url: String
)

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
                    if (!ip.isNullOrBlank() && ip != "127.0.0.1") {
                        return ip
                    }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("NasScanner", "Error getting local IP", e)
    }
    return "192.168.1.100"
}

@Composable
fun CloudSyncScreen(
    viewModel: MediaViewModel,
    onMediaClick: (MediaItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloudSources by viewModel.cloudSources.collectAsStateWithLifecycle()
    val allMedia by viewModel.allMedia.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

    // LAN discovery states
    var showNetworkWizardDialog by remember { mutableStateOf(false) }
    var wizardInitialProtocol by remember { mutableStateOf("SMB") } // "SMB" or "WEBDAV"
    var isNasScanning by remember { mutableStateOf(false) }
    var nasScanProgressText by remember { mutableStateOf("") }
    var discoveredServers by remember { mutableStateOf<List<DiscoveredLanServer>>(emptyList()) }
    var showManualForm by remember { mutableStateOf(false) }

    // SMB form fields
    var smbName by remember { mutableStateOf("Home NAS (SMB)") }
    var smbHost by remember { mutableStateOf("") }
    var smbPort by remember { mutableStateOf("445") }
    var smbShare by remember { mutableStateOf("Movies") }
    var smbSubfolder by remember { mutableStateOf("") }
    var smbUsername by remember { mutableStateOf("") }
    var smbPassword by remember { mutableStateOf("") }
    var showSmbPass by remember { mutableStateOf(false) }

    // WebDAV / HTTP form fields
    var webdavName by remember { mutableStateOf("NAS WebDAV") }
    var webdavUrl by remember { mutableStateOf("http://192.168.1.100:8080/dav") }
    var webdavUsername by remember { mutableStateOf("") }
    var webdavPassword by remember { mutableStateOf("") }
    var showWebdavPass by remember { mutableStateOf(false) }

    // Connection test states
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // Direct stream URL dialog
    var showDirectUrlDialog by remember { mutableStateOf(false) }
    var directStreamName by remember { mutableStateOf("Network Stream") }
    var directStreamUrl by remember { mutableStateOf("") }

    // Google Drive dialog
    var showGoogleDriveDialog by remember { mutableStateOf(false) }
    var googleDriveName by remember { mutableStateOf("My Google Drive") }
    var googleDriveUrl by remember { mutableStateOf("") }
    var googleDriveToken by remember { mutableStateOf("") }

    // Browse source modal
    var browsingSource by remember { mutableStateOf<CloudSource?>(null) }

    // Real LAN scanner for SMB (445) + WebDAV/HTTP (8080, 5005, 5006, 80, 8000, 9000)
    LaunchedEffect(isNasScanning) {
        if (isNasScanning) {
            discoveredServers = emptyList()
            val localIp = getLocalIpAddress()
            val baseIp = localIp.substringBeforeLast(".") + "."
            nasScanProgressText = "Scanning ${baseIp}1–254 for SMB (445) & WebDAV/HTTP media servers..."

            val foundServers = withContext(Dispatchers.IO) {
                (1..254).map { octet ->
                    async {
                        val ip = "$baseIp$octet"
                        if (ip == localIp) return@async emptyList<DiscoveredLanServer>()
                        val hits = mutableListOf<DiscoveredLanServer>()
                        val ports = listOf(445, 8080, 5005, 5006, 8000, 9000, 80)
                        for (port in ports) {
                            try {
                                Socket().use { socket ->
                                    socket.connect(InetSocketAddress(ip, port), 130)
                                }
                                when (port) {
                                    445 -> hits.add(
                                        DiscoveredLanServer(
                                            label = "SMB Share ($ip)",
                                            protocol = "SMB",
                                            host = ip,
                                            port = 445,
                                            url = "smb://$ip/Movies"
                                        )
                                    )
                                    5005 -> hits.add(
                                        DiscoveredLanServer(
                                            label = "Synology WebDAV ($ip:5005)",
                                            protocol = "WEBDAV",
                                            host = ip,
                                            port = 5005,
                                            url = "http://$ip:5005"
                                        )
                                    )
                                    5006 -> hits.add(
                                        DiscoveredLanServer(
                                            label = "Synology WebDAV HTTPS ($ip:5006)",
                                            protocol = "WEBDAV",
                                            host = ip,
                                            port = 5006,
                                            url = "https://$ip:5006"
                                        )
                                    )
                                    8080 -> hits.add(
                                        DiscoveredLanServer(
                                            label = "TrueNAS / WebDAV ($ip:8080)",
                                            protocol = "WEBDAV",
                                            host = ip,
                                            port = 8080,
                                            url = "http://$ip:8080/dav"
                                        )
                                    )
                                    else -> hits.add(
                                        DiscoveredLanServer(
                                            label = "HTTP / Media Server ($ip:$port)",
                                            protocol = "WEBDAV",
                                            host = ip,
                                            port = port,
                                            url = "http://$ip:$port/"
                                        )
                                    )
                                }
                            } catch (_: Exception) {
                                // Port closed/unreachable
                            }
                        }
                        hits
                    }
                }.awaitAll().flatten()
            }

            discoveredServers = foundServers
            nasScanProgressText = if (foundServers.isEmpty()) {
                "No open SMB or WebDAV ports found on ${baseIp}0/24."
            } else {
                "Found ${foundServers.size} network endpoint(s) on your LAN."
            }
            isNasScanning = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F0F15), Color(0xFF0A0A0E))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(56.dp))

            // Header
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                Text(
                    text = "NETWORK & CLOUD STORAGE",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.4.sp,
                    color = Color.White
                )
                Text(
                    text = "Stream directly from SMB shares, NAS WebDAV, HTTP servers & Drive",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            // Live Sync Terminal Banner
            AnimatedVisibility(visible = isSyncing || syncStatus.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141A26)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.25f))
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
                                    contentDescription = "Status",
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Network Sync",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E5FF)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = syncStatus,
                            fontSize = 11.sp,
                            color = Color.LightGray,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Text(
                text = "Add Network or Cloud Source",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp, bottom = 10.dp)
            )

            // 2x2 Grid of real working connectors
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CloudConnectorCard(
                        title = "SMB Share",
                        subtitle = "Windows / Mac / NAS",
                        icon = Icons.Default.FolderShared,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            wizardInitialProtocol = "SMB"
                            testResult = null
                            showManualForm = false
                            isNasScanning = true
                            showNetworkWizardDialog = true
                        }
                    )
                    CloudConnectorCard(
                        title = "WebDAV / HTTP",
                        subtitle = "TrueNAS / Synology",
                        icon = Icons.Default.Storage,
                        tint = Color(0xFFFF9100),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            wizardInitialProtocol = "WEBDAV"
                            testResult = null
                            showManualForm = false
                            isNasScanning = true
                            showNetworkWizardDialog = true
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CloudConnectorCard(
                        title = "Direct Stream URL",
                        subtitle = "HTTP / HLS / SMB Link",
                        icon = Icons.Default.Link,
                        tint = Color(0xFFBB86FC),
                        modifier = Modifier.weight(1f),
                        onClick = { showDirectUrlDialog = true }
                    )
                    CloudConnectorCard(
                        title = "Google Drive",
                        subtitle = "Shared Folder / File",
                        icon = Icons.Default.Cloud,
                        tint = Color(0xFF34A853),
                        modifier = Modifier.weight(1f),
                        onClick = { showGoogleDriveDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Connected Storages (${cloudSources.size})",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            if (cloudSources.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF14141E)),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF222232))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiTethering,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF).copy(alpha = 0.45f),
                            modifier = Modifier.size(38.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No network storages connected yet",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap SMB Share or WebDAV / HTTP above to scan your Wi-Fi for a NAS or PC shared folder.",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(cloudSources, key = { it.id }) { source ->
                        val itemCount = allMedia.count { it.folderPath == source.name }
                        ConnectedSourceCard(
                            source = source,
                            itemCount = itemCount,
                            onBrowse = { browsingSource = source },
                            onSync = { viewModel.syncCloudSource(context, source) },
                            onDelete = { viewModel.disconnectCloudAccount(source, removeMedia = true) }
                        )
                    }
                }
            }
        }
    }

    // Unified SMB + WebDAV/HTTP Network Storage Connection Dialog
    if (showNetworkWizardDialog) {
        AlertDialog(
            onDismissRequest = {
                isNasScanning = false
                showNetworkWizardDialog = false
            },
            title = {
                Text(
                    text = when {
                        isNasScanning -> "Scanning Local Network..."
                        !showManualForm -> "Select or Enter Network Server"
                        wizardInitialProtocol == "SMB" -> "Connect SMB Network Share"
                        else -> "Connect WebDAV / HTTP Server"
                    },
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            containerColor = Color(0xFF14141F),
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isNasScanning) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color(0xFF00E5FF),
                                strokeWidth = 2.5.dp
                            )
                            Text(
                                text = nasScanProgressText,
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            text = "Checking your local Wi-Fi subnet for SMB shares (port 445) and WebDAV / HTTP servers...",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    } else if (!showManualForm) {
                        Text(
                            text = nasScanProgressText,
                            color = if (discoveredServers.isNotEmpty()) Color(0xFF00E5FF) else Color.Gray,
                            fontSize = 11.sp
                        )

                        if (discoveredServers.isNotEmpty()) {
                            discoveredServers.forEach { server ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            wizardInitialProtocol = server.protocol
                                            if (server.protocol == "SMB") {
                                                smbHost = server.host
                                                smbPort = server.port.toString()
                                                smbName = server.label
                                            } else {
                                                webdavUrl = server.url
                                                webdavName = server.label
                                            }
                                            testResult = null
                                            showManualForm = true
                                        },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F2F)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (server.protocol == "SMB") Icons.Default.FolderShared else Icons.Default.Storage,
                                            contentDescription = null,
                                            tint = if (server.protocol == "SMB") Color(0xFF00E5FF) else Color(0xFFFF9100),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(server.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            Text(server.url, color = Color.Gray, fontSize = 11.sp)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFF00E5FF).copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(server.protocol, color = Color(0xFF00E5FF), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        } else {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A28)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = "No servers responded automatically. That's normal if your NAS uses a custom port or firewall—tap below to enter your IP or hostname manually.",
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                testResult = null
                                showManualForm = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
                        ) {
                            Text("Enter Server Address Manually", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    } else {
                        // Protocol switcher tabs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = wizardInitialProtocol == "SMB",
                                onClick = {
                                    wizardInitialProtocol = "SMB"
                                    testResult = null
                                },
                                label = { Text("SMB Share", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = wizardInitialProtocol == "WEBDAV",
                                onClick = {
                                    wizardInitialProtocol = "WEBDAV"
                                    testResult = null
                                },
                                label = { Text("WebDAV / HTTP", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (wizardInitialProtocol == "SMB") {
                            OutlinedTextField(
                                value = smbName,
                                onValueChange = { smbName = it },
                                label = { Text("Display Name") },
                                singleLine = true,
                                colors = networkFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = smbHost,
                                    onValueChange = { smbHost = it; testResult = null },
                                    label = { Text("Server IP / Host") },
                                    placeholder = { Text("192.168.1.50", color = Color.DarkGray) },
                                    singleLine = true,
                                    colors = networkFieldColors(),
                                    modifier = Modifier.weight(2.2f)
                                )
                                OutlinedTextField(
                                    value = smbPort,
                                    onValueChange = { smbPort = it.filter { ch -> ch.isDigit() } },
                                    label = { Text("Port") },
                                    singleLine = true,
                                    colors = networkFieldColors(),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            OutlinedTextField(
                                value = smbShare,
                                onValueChange = { smbShare = it; testResult = null },
                                label = { Text("Share Folder Name (required)") },
                                placeholder = { Text("e.g. Movies, Media, Public", color = Color.DarkGray) },
                                singleLine = true,
                                colors = networkFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            // Quick share name chips
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(listOf("Movies", "Media", "Videos", "TV", "Public")) { quickShare ->
                                    AssistChip(
                                        onClick = { smbShare = quickShare; testResult = null },
                                        label = { Text(quickShare, fontSize = 10.sp) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = smbSubfolder,
                                onValueChange = { smbSubfolder = it },
                                label = { Text("Subfolder Path (optional)") },
                                placeholder = { Text("e.g. 4K/SciFi", color = Color.DarkGray) },
                                singleLine = true,
                                colors = networkFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = smbUsername,
                                onValueChange = { smbUsername = it; testResult = null },
                                label = { Text("Username (blank for Guest/Anonymous)") },
                                singleLine = true,
                                colors = networkFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = smbPassword,
                                onValueChange = { smbPassword = it; testResult = null },
                                label = { Text("Password") },
                                visualTransformation = if (showSmbPass) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showSmbPass = !showSmbPass }) {
                                        Icon(
                                            imageVector = if (showSmbPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle password",
                                            tint = Color.Gray
                                        )
                                    }
                                },
                                singleLine = true,
                                colors = networkFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            OutlinedTextField(
                                value = webdavName,
                                onValueChange = { webdavName = it },
                                label = { Text("Display Name") },
                                singleLine = true,
                                colors = networkFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = webdavUrl,
                                onValueChange = { webdavUrl = it; testResult = null },
                                label = { Text("WebDAV or HTTP Directory URL") },
                                placeholder = { Text("http://192.168.1.50:8080/dav", color = Color.DarkGray) },
                                singleLine = true,
                                colors = networkFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = webdavUsername,
                                onValueChange = { webdavUsername = it; testResult = null },
                                label = { Text("Username (optional)") },
                                singleLine = true,
                                colors = networkFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = webdavPassword,
                                onValueChange = { webdavPassword = it; testResult = null },
                                label = { Text("Password (optional)") },
                                visualTransformation = if (showWebdavPass) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showWebdavPass = !showWebdavPass }) {
                                        Icon(
                                            imageVector = if (showWebdavPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle password",
                                            tint = Color.Gray
                                        )
                                    }
                                },
                                singleLine = true,
                                colors = networkFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Test Connection Button
                        OutlinedButton(
                            onClick = {
                                isTestingConnection = true
                                testResult = null
                                scope.launch {
                                    val res = if (wizardInitialProtocol == "SMB") {
                                        val uri = SmbNetworkService.buildSmbUri(
                                            host = smbHost,
                                            port = smbPort.toIntOrNull() ?: 445,
                                            shareName = smbShare,
                                            subPath = smbSubfolder,
                                            username = smbUsername,
                                            password = smbPassword
                                        )
                                        SmbNetworkService.testConnection(uri)
                                    } else {
                                        val fullUrl = CloudSyncService.buildAuthenticatedHttpUrl(
                                            baseUrl = webdavUrl,
                                            username = webdavUsername,
                                            password = webdavPassword
                                        )
                                        CloudSyncService.testWebDavOrHttpConnection(fullUrl)
                                    }
                                    isTestingConnection = false
                                    testResult = res.fold(
                                        onSuccess = { true to it },
                                        onFailure = { false to (it.message ?: "Connection failed") }
                                    )
                                }
                            },
                            enabled = !isTestingConnection,
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFF00E5FF), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Testing...", color = Color(0xFF00E5FF), fontSize = 12.sp)
                            } else {
                                Text("Test Connection", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        testResult?.let { (ok, msg) ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = (if (ok) Color(0xFF00E676) else Color(0xFFFF5252)).copy(alpha = 0.12f)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = msg,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                when {
                    isNasScanning -> {}
                    !showManualForm -> {
                        Button(
                            onClick = { isNasScanning = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                        ) {
                            Text("Rescan LAN", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> {
                        Button(
                            onClick = {
                                if (wizardInitialProtocol == "SMB") {
                                    if (smbHost.isBlank() || smbShare.isBlank()) return@Button
                                    val assembledSmb = SmbNetworkService.buildSmbUri(
                                        host = smbHost,
                                        port = smbPort.toIntOrNull() ?: 445,
                                        shareName = smbShare,
                                        subPath = smbSubfolder,
                                        username = smbUsername,
                                        password = smbPassword
                                    )
                                    viewModel.connectCloudAccount(
                                        context = context,
                                        type = "SMB",
                                        name = smbName.ifBlank { "SMB ($smbHost/$smbShare)" },
                                        url = assembledSmb,
                                        autoSync = true
                                    )
                                } else {
                                    if (webdavUrl.isBlank()) return@Button
                                    val assembledWebdav = CloudSyncService.buildAuthenticatedHttpUrl(
                                        baseUrl = webdavUrl,
                                        username = webdavUsername,
                                        password = webdavPassword
                                    )
                                    viewModel.connectCloudAccount(
                                        context = context,
                                        type = "TRUENAS",
                                        name = webdavName.ifBlank { "WebDAV Storage" },
                                        url = assembledWebdav,
                                        autoSync = true
                                    )
                                }
                                showNetworkWizardDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                        ) {
                            Text("Connect & Scan", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (showManualForm && !isNasScanning) {
                            showManualForm = false
                        } else {
                            isNasScanning = false
                            showNetworkWizardDialog = false
                        }
                    }
                ) {
                    Text(
                        text = if (showManualForm && !isNasScanning) "Back" else "Cancel",
                        color = Color.LightGray
                    )
                }
            }
        )
    }

    // Direct Stream URL Dialog
    if (showDirectUrlDialog) {
        AlertDialog(
            onDismissRequest = { showDirectUrlDialog = false },
            title = { Text("Add Direct Stream URL", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            containerColor = Color(0xFF14141F),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Paste a direct HTTP, HTTPS, HLS (.m3u8), or SMB video URL to add and stream immediately.",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                    OutlinedTextField(
                        value = directStreamName,
                        onValueChange = { directStreamName = it },
                        label = { Text("Title") },
                        singleLine = true,
                        colors = networkFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = directStreamUrl,
                        onValueChange = { directStreamUrl = it },
                        label = { Text("Stream URL") },
                        placeholder = { Text("http://192.168.1.50/movie.mp4 or smb://...", color = Color.DarkGray) },
                        singleLine = true,
                        colors = networkFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (directStreamUrl.isNotBlank()) {
                            viewModel.connectCloudAccount(
                                context = context,
                                type = "DIRECT_URL",
                                name = directStreamName.ifBlank { "Network Stream" },
                                url = directStreamUrl.trim(),
                                autoSync = true
                            )
                            showDirectUrlDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBB86FC))
                ) {
                    Text("Add & Sync", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDirectUrlDialog = false }) {
                    Text("Cancel", color = Color.LightGray)
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
                        colors = networkFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = googleDriveUrl,
                        onValueChange = { googleDriveUrl = it },
                        label = { Text("Google Drive Folder or File URL") },
                        placeholder = { Text("https://drive.google.com/drive/folders/...", color = Color.DarkGray) },
                        colors = networkFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = googleDriveToken,
                        onValueChange = { googleDriveToken = it },
                        label = { Text("API Key / OAuth Token (Optional)") },
                        placeholder = { Text("Bearer ya29... or Google API Key", color = Color.DarkGray) },
                        colors = networkFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.connectCloudAccount(
                            context = context,
                            type = "GOOGLE_DRIVE",
                            name = googleDriveName,
                            url = googleDriveUrl,
                            token = googleDriveToken,
                            autoSync = true
                        )
                        showGoogleDriveDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                ) {
                    Text("Connect & Scan", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showGoogleDriveDialog = false }) {
                    Text("Cancel", color = Color.LightGray)
                }
            }
        )
    }

    // Browse & Play indexed files from a connected network storage
    browsingSource?.let { source ->
        val sourceItems = remember(allMedia, source) {
            allMedia.filter { it.folderPath == source.name }
        }
        AlertDialog(
            onDismissRequest = { browsingSource = null },
            title = {
                Column {
                    Text(source.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("${sourceItems.size} indexed video(s)", color = Color(0xFF00E5FF), fontSize = 11.sp)
                }
            },
            containerColor = Color(0xFF14141F),
            text = {
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp)) {
                    if (sourceItems.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No videos indexed yet. Tap Sync to scan this storage.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(sourceItems, key = { it.id }) { media ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            browsingSource = null
                                            onMediaClick(media)
                                        },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B2A)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayCircleFilled,
                                            contentDescription = "Play",
                                            tint = Color(0xFF00E5FF),
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = media.title,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = media.fileName,
                                                color = Color.Gray,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { browsingSource = null }) {
                    Text("Close", color = Color(0xFF00E5FF))
                }
            }
        )
    }
}

@Composable
private fun networkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Color(0xFF00E5FF),
    unfocusedBorderColor = Color.Gray
)

@Composable
fun CloudConnectorCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(92.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("connector_card_${title.replace(" ", "_").lowercase()}"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14141E)),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.22f))
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
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Text(
                text = subtitle,
                color = Color.Gray,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
fun ConnectedSourceCard(
    source: CloudSource,
    itemCount: Int,
    onBrowse: () -> Unit,
    onSync: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = when (source.type) {
        "GOOGLE_DRIVE" -> Color(0xFF34A853)
        "SMB" -> Color(0xFF00E5FF)
        "DIRECT_URL" -> Color(0xFFBB86FC)
        else -> Color(0xFFFF9100)
    }
    val typeLabel = when (source.type) {
        "SMB" -> "SMB"
        "GOOGLE_DRIVE" -> "DRIVE"
        "DIRECT_URL" -> "STREAM"
        else -> "WEBDAV"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBrowse)
            .testTag("connected_source_${source.id}"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14141E)),
        border = BorderStroke(1.dp, Color(0xFF232334))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (source.type) {
                            "SMB" -> Icons.Default.FolderShared
                            "DIRECT_URL" -> Icons.Default.Link
                            "GOOGLE_DRIVE" -> Icons.Default.Cloud
                            else -> Icons.Default.Storage
                        },
                        contentDescription = source.type,
                        tint = accent,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = source.name,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(accent.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(typeLabel, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        text = "$itemCount video(s) • " + if (source.lastSynced > 0) {
                            "Synced ${java.text.SimpleDateFormat("MMM dd, HH:mm").format(java.util.Date(source.lastSynced))}"
                        } else {
                            "Not synced yet"
                        },
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBrowse,
                    modifier = Modifier.testTag("browse_button_${source.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Browse & Play",
                        tint = Color.White
                    )
                }
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
                        tint = Color(0xFFFF5252).copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
