package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.service.MetadataService
import com.example.ui.viewmodel.MediaViewModel
import kotlinx.coroutines.launch

@Composable
fun ApiSetupWizardDialog(
    viewModel: MediaViewModel,
    initialStep: Int = 0,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val savedGeminiKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val savedTmdbKey by viewModel.tmdbApiKey.collectAsStateWithLifecycle()
    val savedOmdbKey by viewModel.omdbApiKey.collectAsStateWithLifecycle()

    var currentStep by remember { mutableIntStateOf(initialStep.coerceIn(0, 3)) }

    var geminiInput by remember(savedGeminiKey) {
        mutableStateOf(savedGeminiKey.ifBlank { MetadataService.resolveGeminiApiKey() })
    }
    var tmdbInput by remember(savedTmdbKey) { mutableStateOf(savedTmdbKey) }
    var omdbInput by remember(savedOmdbKey) { mutableStateOf(savedOmdbKey) }

    var showGeminiText by remember { mutableStateOf(false) }
    var isTestingGemini by remember { mutableStateOf(false) }
    var geminiStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var isTestingTmdb by remember { mutableStateOf(false) }
    var tmdbStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var isTestingOmdb by remember { mutableStateOf(false) }
    var omdbStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    val totalSteps = 4
    val stepTitles = listOf(
        "1. How AI Metadata Works",
        "2. Get Your Free Gemini Key",
        "3. Enter & Verify Gemini Key",
        "4. Optional TMDb & OMDb Keys"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .heightIn(max = 680.dp)
                .testTag("api_setup_wizard_dialog"),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF11111B)),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color(0xFF00E5FF).copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "AI Setup",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "AI & API Setup Wizard",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = stepTitles[currentStep],
                                color = Color(0xFF00E5FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Step progress bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    repeat(totalSteps) { index ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (index <= currentStep) Color(0xFF00E5FF)
                                    else Color(0xFF232336)
                                )
                                .clickable { currentStep = index }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Scrollable step content
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (currentStep) {
                        0 -> {
                            Text(
                                text = "Lumina Player works out of the box, and adding a free AI key unlocks smart movie/TV detection even for messy filenames.",
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )

                            ApiStatusSummaryCard(
                                title = "Gemini AI Key (Recommended)",
                                subtitle = "Identifies messy filenames, searches movies & shows, fetches cast/directors and plot summaries. 100% free tier from Google.",
                                isConfigured = MetadataService.resolveGeminiApiKey(savedGeminiKey).isNotBlank(),
                                accentColor = Color(0xFF00E5FF)
                            )

                            ApiStatusSummaryCard(
                                title = "TMDb v3 Key (Optional)",
                                subtitle = "Adds official HD poster artwork, backdrops, and episode stills directly from The Movie Database.",
                                isConfigured = savedTmdbKey.isNotBlank(),
                                accentColor = Color(0xFF01D277)
                            )

                            ApiStatusSummaryCard(
                                title = "OMDb Key (Optional)",
                                subtitle = "Adds IMDb ratings and movie disambiguation.",
                                isConfigured = savedOmdbKey.isNotBlank(),
                                accentColor = Color(0xFFFFB300)
                            )

                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF171A29)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Your API keys stay strictly on your device in private storage.",
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }

                        1 -> {
                            Text(
                                text = "Follow these 4 quick steps to create your free Google Gemini API key (takes under 60 seconds):",
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )

                            WizardInstructionStep(
                                number = "1",
                                title = "Open Google AI Studio",
                                body = "Tap the button below to open Google AI Studio in your browser."
                            )
                            WizardInstructionStep(
                                number = "2",
                                title = "Sign in with Google",
                                body = "Use any standard Google account. No credit card is required for the free tier."
                            )
                            WizardInstructionStep(
                                number = "3",
                                title = "Tap 'Create API key'",
                                body = "Choose 'Create API key in new project' (or pick an existing project)."
                            )
                            WizardInstructionStep(
                                number = "4",
                                title = "Copy the key (starts with AIza...)",
                                body = "Tap Copy, then come back here and tap 'Next' to paste and verify it."
                            )

                            Button(
                                onClick = {
                                    runCatching { uriHandler.openUri("https://aistudio.google.com/apikey") }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("open_aistudio_link_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Open Google AI Studio", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }

                        2 -> {
                            Text(
                                text = "Paste your Gemini API key below, then tap 'Test & Save Key' to confirm it works:",
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )

                            OutlinedTextField(
                                value = geminiInput,
                                onValueChange = {
                                    geminiInput = it
                                    geminiStatus = null
                                },
                                label = { Text("Gemini API Key (AIza...)") },
                                placeholder = { Text("Paste your AIza... key here", color = Color.Gray, fontSize = 12.sp) },
                                visualTransformation = if (showGeminiText) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = { showGeminiText = !showGeminiText }) {
                                            Icon(
                                                imageVector = if (showGeminiText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = "Toggle key visibility",
                                                tint = Color.Gray
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                val clip = clipboard.getText()?.text?.trim().orEmpty()
                                                if (clip.isNotBlank()) {
                                                    geminiInput = clip
                                                    geminiStatus = null
                                                }
                                            }
                                        ) {
                                            Text("PASTE", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF00E5FF),
                                    unfocusedBorderColor = Color(0xFF2B2B40),
                                    focusedContainerColor = Color(0xFF181827),
                                    unfocusedContainerColor = Color(0xFF181827)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("wizard_gemini_input")
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        isTestingGemini = true
                                        geminiStatus = null
                                        scope.launch {
                                            val res = viewModel.verifyAndSaveGeminiKey(context, geminiInput)
                                            isTestingGemini = false
                                            geminiStatus = res.fold(
                                                onSuccess = { true to it },
                                                onFailure = { false to (it.message ?: "Verification failed") }
                                            )
                                        }
                                    },
                                    enabled = !isTestingGemini && geminiInput.isNotBlank(),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("wizard_test_gemini_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    if (isTestingGemini) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Testing...", color = Color.Black, fontWeight = FontWeight.Bold)
                                    } else {
                                        Icon(Icons.Default.Verified, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Test & Save Key", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (savedGeminiKey.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.saveGeminiApiKey(context, "")
                                            geminiInput = ""
                                            geminiStatus = false to "Gemini API key cleared."
                                        },
                                        modifier = Modifier.height(48.dp),
                                        border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Clear")
                                    }
                                }
                            }

                            geminiStatus?.let { (ok, msg) ->
                                StatusBanner(isSuccess = ok, message = msg)
                            }
                        }

                        3 -> {
                            Text(
                                text = "Optional: Add TMDb and/or OMDb keys for official high-res movie posters and IMDb ratings. (Lumina already has keyless fallbacks if you skip these.)",
                                color = Color.LightGray,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )

                            // TMDB block
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF171726)),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color(0xFF252538))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("TMDb v3 API Key", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        TextButton(
                                            onClick = { runCatching { uriHandler.openUri("https://www.themoviedb.org/settings/api") } },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Get Free Key", color = Color(0xFF00E5FF), fontSize = 11.sp)
                                        }
                                    }
                                    OutlinedTextField(
                                        value = tmdbInput,
                                        onValueChange = { tmdbInput = it; tmdbStatus = null },
                                        placeholder = { Text("Paste TMDb v3 API Key...", color = Color.Gray, fontSize = 11.sp) },
                                        trailingIcon = {
                                            TextButton(onClick = {
                                                clipboard.getText()?.text?.trim()?.takeIf { it.isNotBlank() }?.let { tmdbInput = it }
                                            }) {
                                                Text("PASTE", color = Color(0xFF00E5FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = Color(0xFF00E5FF),
                                            unfocusedBorderColor = Color(0xFF2B2B40)
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = {
                                            isTestingTmdb = true
                                            scope.launch {
                                                val res = viewModel.verifyAndSaveTmdbKey(context, tmdbInput)
                                                isTestingTmdb = false
                                                tmdbStatus = res.fold(
                                                    onSuccess = { true to it },
                                                    onFailure = { false to (it.message ?: "Invalid TMDb key") }
                                                )
                                            }
                                        },
                                        enabled = !isTestingTmdb && tmdbInput.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2A3E)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(if (isTestingTmdb) "Verifying..." else "Test & Save TMDb Key", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    tmdbStatus?.let { (ok, msg) -> StatusBanner(ok, msg) }
                                }
                            }

                            // OMDB block
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF171726)),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color(0xFF252538))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("OMDb API Key", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        TextButton(
                                            onClick = { runCatching { uriHandler.openUri("https://www.omdbapi.com/apikey.aspx") } },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Get Free Key", color = Color(0xFF00E5FF), fontSize = 11.sp)
                                        }
                                    }
                                    OutlinedTextField(
                                        value = omdbInput,
                                        onValueChange = { omdbInput = it; omdbStatus = null },
                                        placeholder = { Text("Paste OMDb API Key...", color = Color.Gray, fontSize = 11.sp) },
                                        trailingIcon = {
                                            TextButton(onClick = {
                                                clipboard.getText()?.text?.trim()?.takeIf { it.isNotBlank() }?.let { omdbInput = it }
                                            }) {
                                                Text("PASTE", color = Color(0xFF00E5FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = Color(0xFF00E5FF),
                                            unfocusedBorderColor = Color(0xFF2B2B40)
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = {
                                            isTestingOmdb = true
                                            scope.launch {
                                                val res = viewModel.verifyAndSaveOmdbKey(context, omdbInput)
                                                isTestingOmdb = false
                                                omdbStatus = res.fold(
                                                    onSuccess = { true to it },
                                                    onFailure = { false to (it.message ?: "Invalid OMDb key") }
                                                )
                                            }
                                        },
                                        enabled = !isTestingOmdb && omdbInput.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2A3E)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(if (isTestingOmdb) "Verifying..." else "Test & Save OMDb Key", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    omdbStatus?.let { (ok, msg) -> StatusBanner(ok, msg) }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Footer navigation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentStep > 0) {
                        TextButton(onClick = { currentStep-- }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Back", color = Color.LightGray)
                        }
                    } else {
                        TextButton(onClick = onDismiss) {
                            Text("Skip for Now", color = Color.Gray)
                        }
                    }

                    if (currentStep < totalSteps - 1) {
                        Button(
                            onClick = { currentStep++ },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Next", color = Color.Black, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTutorialDialog(
    viewModel: MediaViewModel,
    onDismiss: () -> Unit,
    onRequestStorageAndScan: () -> Unit,
    onOpenApiWizard: () -> Unit,
    onNavigateToCloud: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val pageCount = 5
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .heightIn(max = 660.dp)
                .testTag("app_tutorial_dialog"),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF10101A)),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Top bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LUMINA GUIDE  •  ${pagerState.currentPage + 1}/$pageCount",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Color.Gray, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) { page ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (page) {
                            0 -> TutorialPageContent(
                                icon = Icons.Default.Movie,
                                accent = Color(0xFF00E5FF),
                                title = "Welcome to Lumina Player",
                                subtitle = "Your high-performance cinema hub for local videos, home NAS shares, and cloud media.",
                                bullets = listOf(
                                    "Organizes Movies and TV Shows into seasons and episodes automatically.",
                                    "Plays MKV, MP4, AVI, WebM, MOV with hardware acceleration.",
                                    "Remembers your exact playback progress across every episode."
                                ),
                                actionLabel = "Grant Storage & Scan Folders",
                                onAction = {
                                    onDismiss()
                                    onRequestStorageAndScan()
                                }
                            )

                            1 -> TutorialPageContent(
                                icon = Icons.Default.Bolt,
                                accent = Color(0xFF00E5FF),
                                title = "Instant Auto-Detection",
                                subtitle = "New videos you download or copy appear automatically—no slow full rescans required.",
                                bullets = listOf(
                                    "Auto-Watch monitors MediaStore & your watched folders in real time.",
                                    "Switching back to Lumina runs an instant delta check for new files.",
                                    "Tap the Refresh icon on Home anytime for a 1-second Quick Scan."
                                ),
                                actionLabel = null,
                                onAction = {}
                            )

                            2 -> TutorialPageContent(
                                icon = Icons.Default.AutoAwesome,
                                accent = Color(0xFFBB86FC),
                                title = "Smart AI & Poster Setup",
                                subtitle = "Turn plain filenames into rich posters, episode summaries, and cast details.",
                                bullets = listOf(
                                    "Gemini AI automatically identifies messy filenames for free.",
                                    "Use our 60-second Guided Wizard to paste & test your key right inside the app.",
                                    "Optional TMDb & OMDb keys add official posters and IMDb ratings."
                                ),
                                actionLabel = "Launch AI Setup Wizard",
                                onAction = {
                                    onDismiss()
                                    onOpenApiWizard()
                                }
                            )

                            3 -> TutorialPageContent(
                                icon = Icons.Default.Storage,
                                accent = Color(0xFFFF9100),
                                title = "Network Storage & Cloud",
                                subtitle = "Stream movies directly from your PC, TrueNAS, Synology, or Google Drive.",
                                bullets = listOf(
                                    "SMB Shares (Windows / Mac / NAS) with instant seeking.",
                                    "WebDAV & HTTP directories with auto-discovery on your Wi-Fi.",
                                    "Download episodes from your NAS to watch offline anywhere."
                                ),
                                actionLabel = "Open Network & Cloud Tab",
                                onAction = {
                                    onDismiss()
                                    onNavigateToCloud()
                                }
                            )

                            4 -> TutorialPageContent(
                                icon = Icons.Default.TouchApp,
                                accent = Color(0xFF00E5FF),
                                title = "Player Gestures & Subtitles",
                                subtitle = "Master cinema playback with intuitive touch controls and real-time subtitle sync.",
                                bullets = listOf(
                                    "Press & Hold anywhere: Instant 2X speed (release to return to 1X).",
                                    "Double-tap Left / Right: Jump back or forward 10 seconds.",
                                    "Swipe Left / Right side vertically: Adjust Brightness or Volume.",
                                    "Subtitle Studio (CC button): Shift timing -5s to +5s live with zero stutter."
                                ),
                                actionLabel = null,
                                onAction = {}
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Dot indicators
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(pageCount) { idx ->
                        val active = pagerState.currentPage == idx
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .height(6.dp)
                                .width(if (active) 22.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (active) Color(0xFF00E5FF) else Color(0xFF2D2D42))
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(idx) }
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom nav buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pagerState.currentPage > 0) {
                        TextButton(onClick = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        }) {
                            Text("Previous", color = Color.LightGray)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    if (pagerState.currentPage < pageCount - 1) {
                        Button(
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Next", color = Color.Black, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        Button(
                            onClick = {
                                onDismiss()
                                onRequestStorageAndScan()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Get Started", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialPageContent(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    bullets: List<String>,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(
                Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.25f), accent.copy(alpha = 0.05f))
                ),
                CircleShape
            )
            .border(1.dp, accent.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = title, tint = accent, modifier = Modifier.size(34.dp))
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = title,
        color = Color.White,
        fontSize = 19.sp,
        fontWeight = FontWeight.ExtraBold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(6.dp))

    Text(
        text = subtitle,
        color = Color.LightGray,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        bullets.forEach { bullet ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF171726)),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF232336))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = bullet,
                        color = Color.White,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }

    if (actionLabel != null) {
        Spacer(modifier = Modifier.height(14.dp))
        OutlinedButton(
            onClick = onAction,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(actionLabel, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ApiStatusSummaryCard(
    title: String,
    subtitle: String,
    isConfigured: Boolean,
    accentColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171726)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (isConfigured) Color(0xFF00C853).copy(alpha = 0.45f) else Color(0xFF252538)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Box(
                    modifier = Modifier
                        .background(
                            if (isConfigured) Color(0xFF00C853).copy(alpha = 0.16f) else accentColor.copy(alpha = 0.12f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (isConfigured) "Active" else "Not Set",
                        color = if (isConfigured) Color(0xFF00E676) else accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(subtitle, color = Color.Gray, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun WizardInstructionStep(
    number: String,
    title: String,
    body: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171726)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF232336))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(Color(0xFF00E5FF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(number, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(body, color = Color.Gray, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun StatusBanner(isSuccess: Boolean, message: String) {
    val color = if (isSuccess) Color(0xFF00E676) else Color(0xFFFF5252)
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(message, color = Color.White, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}
