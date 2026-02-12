package com.ryans.nostrshare

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ryans.nostrshare.nip55.*
import com.ryans.nostrshare.ui.theme.NostrShareTheme
import com.ryans.nostrshare.ui.DraftsDialog
import com.ryans.nostrshare.data.Draft
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.OffsetMapping
import android.net.Uri
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

class ProcessTextActivity : ComponentActivity() {

    private val viewModel: ProcessTextViewModel by viewModels()

    // Library Contract: Sign Event
    private val signEventLauncher = registerForActivityResult(SignEventContract()) { result ->
        result.onSuccess { signed ->
             if (!signed.signedEventJson.isNullOrBlank()) {
                 viewModel.onEventSigned(signed.signedEventJson)
             } else if (!signed.signature.isNullOrBlank()) {
                 Toast.makeText(this, "Got signature, but event merge not impl.", Toast.LENGTH_SHORT).show()
             }
        }.onError { error ->
            Toast.makeText(this, "Signing failed: ${error.message}", Toast.LENGTH_SHORT).show()
            if (viewModel.isBatchUploading) {
                viewModel.resetBatchState()
            }
        }
    }

    private val signEventsLauncher = registerForActivityResult(SignEventsContract()) { result ->
        result.onSuccess { signed ->
            if (signed.signedEventsJson.isNotEmpty()) {
                if (viewModel.currentSigningPurpose == ProcessTextViewModel.SigningPurpose.BATCH_UPLOAD_AUTH) {
                    viewModel.onBatchEventsSigned(signed.signedEventsJson)
                } else {
                    viewModel.publishPosts(signed.signedEventsJson)
                }
            }
        }.onError { error ->
            Toast.makeText(this, "Batch signing failed: ${error.message}", Toast.LENGTH_SHORT).show()
            if (viewModel.isBatchUploading) {
                viewModel.resetBatchState()
            }
        }
    }

    // Library Contract: Get Public Key
    private val getPublicKeyLauncher = registerForActivityResult(GetPublicKeyContract()) { result ->
        result.onSuccess { pkResult ->
             viewModel.login(pkResult.pubkey, null, pkResult.packageName)
             // Retry pending actions
             if (viewModel.postKind == ProcessTextViewModel.PostKind.MEDIA) {
                 val failingItem = viewModel.mediaItems.find { it.uploadedUrl == null && !it.isUploading && !it.isProcessing }
                 if (failingItem != null) {
                      viewModel.initiateUploadAuth(this, failingItem)
                 }
             }
        }.onError { error ->
            Toast.makeText(this, "Login failed: ${error.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Media Picker
    private val pickMediaLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { uris: List<android.net.Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.onMediaSelected(this, uris)
        }
    }

    private var pendingScheduledTime: Long? = null
    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            pendingScheduledTime?.let { 
                checkExactAlarmAndSchedule(it)
            }
        } else {
            // Even if denied, we proceed with scheduling (inexact), but let's check exact alarm permission anyway?
            // User just won't get the alert.
            pendingScheduledTime?.let {
                checkExactAlarmAndSchedule(it)
            }
            Toast.makeText(this, "Notifications disabled. You won't be alerted when the note is sent.", Toast.LENGTH_SHORT).show()
        }
    }

    private var showExactAlarmDialog by mutableStateOf(false)

    private val exactAlarmLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        // Check permission again
        pendingScheduledTime?.let { time ->
             checkExactAlarmAndSchedule(time)
        }
    }

    private fun checkExactAlarmAndSchedule(time: Long) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(android.app.AlarmManager::class.java)
            if (alarmManager.canScheduleExactAlarms()) {
                viewModel.prepareScheduling(time)
                pendingScheduledTime = null
            } else {
                pendingScheduledTime = time
                showExactAlarmDialog = true
            }
        } else {
            viewModel.prepareScheduling(time)
            pendingScheduledTime = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        viewModel.checkDraft() // Check for saved work
        viewModel.verifyScheduledNotes(this) // Check scheduled notes and persistent notification
        
        // ... (Keep existing Intent handling logic) ...
         if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (text != null) {
                viewModel.updateQuote(text)
                viewModel.onHighlightShared()
            }
        } else if (intent.action == Intent.ACTION_SEND) {
            // Handle STREAM for images/video
            if (intent.type?.startsWith("image/") == true || intent.type?.startsWith("video/") == true) {
                 @Suppress("DEPRECATION")
                 val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                  if (uri != null) {
                      viewModel.onMediaSelected(this, listOf(uri))
                  }
            } else {
                // Existing text handling
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                // ... (Existing text extraction logic) ...
                // Match web URLs or Nostr entities (nostr:bech32 or raw bech32)
                val urlRegex = "(https?://[^\\s]+|nostr:[a-z0-9]+|nevent1[a-z0-9]+|naddr1[a-z0-9]+|note1[a-z0-9]+)".toRegex(RegexOption.IGNORE_CASE)
                val match = urlRegex.find(text)

                if (match != null) {
                    var entity = match.value
                    if (entity.contains("#:~:text=")) entity = entity.substringBefore("#:~:text=")
                    
                    val content = text.replace(match.value, "").trim()
                    val cleanContent = if (content.startsWith("\"") && content.endsWith("\"")) content.removeSurrounding("\"") else content
                    
                    if (cleanContent.isNotBlank()) {
                        viewModel.updateQuote(cleanContent)
                        viewModel.onHighlightShared()
                    } else if (!subject.isNullOrBlank()) {
                        viewModel.updateQuote(subject)
                    }
                    
                    val cleanEntity = if (entity.startsWith("http")) UrlUtils.cleanUrl(entity) else entity
                    viewModel.updateSource(cleanEntity)
                } else {
                    viewModel.updateQuote(text)
                }
            }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            if (intent.type?.startsWith("image/") == true || intent.type?.startsWith("video/") == true) {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (uris != null) {
                    viewModel.onMediaSelected(this, uris)
                }
            }
        } else {
             // Default / Launcher Case
             val launchMode = intent.getStringExtra("LAUNCH_MODE")
             if (launchMode == "NOTE") {
                 viewModel.setKind(ProcessTextViewModel.PostKind.NOTE)
             }
        }

        setContent {
            NostrShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!viewModel.isOnboarded) {
                        OnboardingScreen(viewModel)
                    } else {
                        ShareScreen(viewModel)
                    }

                    if (showExactAlarmDialog) {
                         AlertDialog(
                            onDismissRequest = { 
                                showExactAlarmDialog = false 
                                // On dismiss without action, maybe fall back to inexact?
                                pendingScheduledTime?.let { viewModel.prepareScheduling(it); pendingScheduledTime = null }
                            },
                            title = { Text("Exact Timing Permission") },
                            text = { 
                                Text("To publish your note at the exact requested time, Prism needs permission to schedule exact alarms.\n\nWithout this, your note may be delayed by Android's battery optimizations.") 
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showExactAlarmDialog = false
                                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    // Make sure to add package uri
                                    intent.data = android.net.Uri.parse("package:$packageName")
                                    exactAlarmLauncher.launch(intent)
                                }) { Text("Open Settings") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showExactAlarmDialog = false
                                    // Fallback to inexact
                                    pendingScheduledTime?.let { viewModel.prepareScheduling(it); pendingScheduledTime = null }
                                }) { Text("Skip (Inexact)") }
                            }
                        )
                    }
                }
            }
        }
    }
    
    @Composable
    fun OnboardingScreen(vm: ProcessTextViewModel) {
        val step = vm.currentOnboardingStep
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_prism_triangle),
                contentDescription = null,
                modifier = Modifier.size(100.dp).clip(CircleShape),
                tint = Color.Unspecified
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            when (step) {
                OnboardingStep.WELCOME -> {
                    Text(
                        text = "Welcome to Prism",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "Prism makes it easy to share text, links, and media to the Nostr network. Connect your favorite NIP-55 signer up front to get started.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(48.dp))
                    
                    Button(
                        onClick = {
                            if (Nip55.isSignerAvailable(this@ProcessTextActivity)) {
                                getPublicKeyLauncher.launch(
                                    GetPublicKeyContract.Input(
                                        permissions = listOf(
                                            Permission.signEvent(9802),
                                            Permission.signEvent(1),
                                            Permission.signEvent(24242),
                                            Permission.signEvent(20),
                                            Permission.signEvent(22),
                                            Permission.signEvent(10063)
                                        ) 
                                    )
                                )
                            } else {
                                Toast.makeText(this@ProcessTextActivity, "No NIP-55 Signer app found.", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sign in with Amber / Signer")
                    }
                }
                OnboardingStep.SYNCING -> {
                    Text(
                        text = "Setting up your environment",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Finding your Blossom server list (Kind 10063) on your relays...",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OnboardingStep.SERVER_SELECTION -> {
                    Text(
                        text = "Blossom Servers",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "Blossom servers store your media across the Nostr network, keeping you in control of your content.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "We couldn't find a server list on your relays. We recommend picking between 1-3 servers.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Local selection for the defaults
                    val repoDefaults = remember { viewModel.getFallBackServers() }
                    var selectedServers by remember { 
                        mutableStateOf(
                            repoDefaults.filter { it.url.contains("primal.net") || it.url.contains("blossom.band") }
                                .map { it.url }.toSet()
                        ) 
                    }
                    
                    Column(modifier = Modifier.fillMaxWidth()) {
                        repoDefaults.forEach { defaultServer ->
                            val isSelected = selectedServers.contains(defaultServer.url)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        selectedServers = if (isSelected) {
                                            selectedServers - defaultServer.url
                                        } else {
                                            selectedServers + defaultServer.url
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { 
                                        selectedServers = if (isSelected) {
                                            selectedServers - defaultServer.url
                                        } else {
                                            selectedServers + defaultServer.url
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(defaultServer.url, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Button(
                        onClick = {
                            val serversToSave = repoDefaults.map { 
                                BlossomServer(it.url, selectedServers.contains(it.url))
                            }
                            // Also ensure at least the checked ones are saved, or if none checked, maybe warn?
                            // For now, just save exactly what they picked (enabled=true for picked).
                            vm.finishOnboarding(serversToSave)
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        enabled = selectedServers.isNotEmpty()
                    ) {
                        Text("Save & Start Sharing")
                    }
                    
                    TextButton(onClick = { 
                        // Just use Primal/Band default and go
                        vm.finishOnboarding(vm.blossomServers) 
                    }) {
                        Text("Skip for now")
                    }
                }
            }
        }
    }



    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
    @Composable
    fun ShareScreen(vm: ProcessTextViewModel) {
        val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        var showActionOptions by remember { mutableStateOf(false) }
        
        // Auto-save Draft
        LaunchedEffect(vm.quoteContent, vm.sourceUrl, vm.postKind, vm.mediaUri, vm.uploadedMediaUrl) {
            if (vm.isDraftMonitoringActive) {
                kotlinx.coroutines.delay(1000) // Debounce 1s
                vm.saveDraft()
            }
        }

        var showConfirmClear by remember { mutableStateOf(false) }
        var showMediaDetail by remember { mutableStateOf<MediaUploadState?>(null) }
        var showDraftsDialog by remember { mutableStateOf(false) }
        var showDatePicker by remember { mutableStateOf(false) }
        var showTimePicker by remember { mutableStateOf(false) }
        var showQuickScheduleOptions by remember { mutableStateOf(false) }
        var tempDateMillis by remember { mutableStateOf<Long?>(null) }

        // Auto-focus
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        // Close on success
        LaunchedEffect(vm.publishSuccess) {
            if (vm.publishSuccess == true) {
                 Toast.makeText(this@ProcessTextActivity, vm.publishStatus, Toast.LENGTH_SHORT).show()
                 finish()
            }
        }
        
        // Observe Signing Requests from VM
        val eventToSign by vm.eventToSign.collectAsState()
        LaunchedEffect(eventToSign) {
            eventToSign?.let { eventJson ->
                vm.pubkey?.let { pubkey ->
                     signEventLauncher.launch(
                        SignEventContract.Input(
                            eventJson = eventJson,
                            currentUser = pubkey, 
                            id = System.currentTimeMillis().toString()
                        )
                    )
                }
            }
        }
        

        
        // Handle Signed Result is in callback: vm.onSignedEventReceived -> vm.onEventSigned

        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(), // Fix: Resize Scaffold for keyboard
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        // User Avatar / Login - Large
                        IconButton(
                            modifier = Modifier.padding(start = 8.dp).size(48.dp),
                            onClick = {
                                if (vm.isHapticEnabled()) {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                }
                                if (Nip55.isSignerAvailable(this@ProcessTextActivity)) {
                                    getPublicKeyLauncher.launch(
                                        GetPublicKeyContract.Input(
                                            permissions = listOf(Permission.signEvent(9802), Permission.signEvent(1), Permission.signEvent(24242), Permission.signEvent(20), Permission.signEvent(22)) 
                                        )
                                    )
                                } else {
                                     Toast.makeText(this@ProcessTextActivity, "No NIP-55 Signer app found.", Toast.LENGTH_LONG).show()
                                }
                            }
                        ) {
                            if (vm.userProfile?.pictureUrl != null) {
                                coil.compose.AsyncImage(
                                    model = vm.userProfile!!.pictureUrl,
                                    contentDescription = "User Avatar",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Login",
                                    modifier = Modifier.fillMaxSize().padding(8.dp)
                                )
                            }
                        }
                    },
                    title = { 
                        // Note Kind Dropdown
                        var showModeMenu by remember { mutableStateOf(false) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { 
                                if (vm.isHapticEnabled()) {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                }
                                showModeMenu = true 
                            }.padding(8.dp)
                        ) {
                            Column {
                                Text(vm.postKind.label, style = MaterialTheme.typography.titleMedium)
                                if (vm.postKind == ProcessTextViewModel.PostKind.MEDIA) {
                                    Text("Media Upload", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Icon(Icons.Filled.ArrowDropDown, "Select Mode")
                            
                            DropdownMenu(
                                expanded = showModeMenu, 
                                onDismissRequest = { showModeMenu = false },
                                properties = PopupProperties(focusable = false)
                            ) {
                                vm.availableKinds.forEach { kind ->
                                    DropdownMenuItem(
                                        text = { Text(kind.label) },
                                        onClick = { 
                                            if (vm.isHapticEnabled()) {
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            }
                                            vm.setKind(kind)
                                            showModeMenu = false 
                                        }
                                    )
                                }
                            }
                        }
                    },
                    actions = {


                        // Drafts - Small
                        IconButton(onClick = {
                            if (vm.isHapticEnabled()) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            }
                            showDraftsDialog = true
                        }) {
                             Icon(Icons.Default.Edit, "Manage Drafts")
                        }

                        // Settings - Small
                        IconButton(onClick = {
                            if (vm.isHapticEnabled()) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            }
                            startActivity(Intent(this@ProcessTextActivity, SettingsActivity::class.java))
                        }) {
                             Icon(Icons.Default.Settings, "Settings")
                        }
                    }
                )
            },
            bottomBar = {
                if (vm.isPublishing || vm.isUploading) {
                   BottomAppBar {
                       CircularProgressIndicator(modifier = Modifier.padding(start = 16.dp).size(24.dp))
                       Spacer(modifier = Modifier.width(16.dp))
                       val status = if (vm.isUploading) vm.uploadStatus else if (vm.isPublishing) vm.publishStatus else if (vm.uploadStatus.isNotBlank()) vm.uploadStatus else ""
                       if (status.isNotBlank()) {
                           Text(status, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                       }
                   }
                } else if (vm.showDraftPrompt) {
                    BottomAppBar {
                        Text(
                            text = "Resume Draft?",
                            modifier = Modifier.padding(start = 16.dp).weight(1f),
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        // Discard (X)
                        FloatingActionButton(
                            onClick = { 
                                if (vm.isHapticEnabled()) {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                }
                                vm.discardDraft() 
                            },
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Icon(Icons.Default.Close, "Discard Draft")
                        }
                        
                        // Resume (Check)
                        FloatingActionButton(
                            onClick = { 
                                if (vm.isHapticEnabled()) {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                }
                                vm.applyDraft() 
                            },
                            modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Icon(Icons.Default.Check, "Resume Draft")
                        }
                    }
                } else {
                    val canPost = if (vm.postKind == ProcessTextViewModel.PostKind.REPOST) {
                        vm.sourceUrl.isNotBlank()
                    } else {
                        vm.quoteContent.isNotBlank() || vm.mediaItems.isNotEmpty() || vm.sourceUrl.isNotBlank()
                    }
                    
                    BottomAppBar(
                        actions = {
                            // Left-side: Clear & Close
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FloatingActionButton(
                                    onClick = {
                                        if (vm.isHapticEnabled()) {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        }
                                        showConfirmClear = !showConfirmClear
                                    },
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(start = 16.dp).size(48.dp)
                                ) {
                                    if (showConfirmClear) {
                                        Icon(Icons.Default.Close, "Cancel Delete")
                                    } else {
                                        Icon(Icons.Default.Delete, "Clear & Close")
                                    }
                                }

                                if (showConfirmClear) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    FloatingActionButton(
                                        onClick = {
                                            if (vm.isHapticEnabled()) {
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            }
                                            vm.clearContent()
                                            finish()
                                        },
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.Check, "Confirm Delete")
                                    }
                                }
                            }

                            if (vm.publishSuccess == false || vm.uploadStatus.startsWith("Error")) {
                                Text(
                                    text = "Error",
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(start = 8.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // Right-side: Action Buttons
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Add Media
                                FloatingActionButton(
                                    onClick = {
                                        if (vm.isHapticEnabled()) {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        }
                                        pickMediaLauncher.launch("*/*")
                                    },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.AddPhotoAlternate, "Attach Media", modifier = Modifier.size(20.dp))
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Add User
                                FloatingActionButton(
                                    onClick = {
                                        if (vm.isHapticEnabled()) {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        }
                                        vm.showUserSearchDialog = true
                                    },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.PersonAdd, "Add User", modifier = Modifier.size(20.dp))
                                }

                                Spacer(modifier = Modifier.width(16.dp)) // Double spacer

                                // Expanding assembly
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Close
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = showActionOptions,
                                        enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                                        exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End)
                                    ) {
                                        FloatingActionButton(
                                            onClick = { 
                                                if (vm.isHapticEnabled()) {
                                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                }
                                                showActionOptions = false 
                                                showQuickScheduleOptions = false
                                            },
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(Icons.Default.Close, "Close", modifier = Modifier.size(20.dp))
                                        }
                                    }

                                    if (showActionOptions) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }

                                    // Quick Schedule (Clock)
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = showActionOptions,
                                        enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                                        exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End)
                                    ) {
                                        Box(contentAlignment = Alignment.BottomCenter) {
                                            // Presets Menu (Using Popup to render above the bar)
                                            if (showQuickScheduleOptions) {
                                                Popup(
                                                    alignment = Alignment.BottomCenter,
                                                    offset = androidx.compose.ui.unit.IntOffset(0, -160), // Position above FAB
                                                    onDismissRequest = { showQuickScheduleOptions = false },
                                                    properties = PopupProperties(focusable = false)
                                                ) {
                                                    Surface(
                                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                                        tonalElevation = 8.dp,
                                                        shadowElevation = 4.dp,
                                                        modifier = Modifier.width(80.dp)
                                                    ) {
                                                        Column(modifier = Modifier.padding(4.dp)) {
                                                            // Standard Picker Option at Top
                                                            TextButton(
                                                                onClick = {
                                                                    if (vm.isHapticEnabled()) {
                                                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                                    }
                                                                    showQuickScheduleOptions = false
                                                                    showActionOptions = false
                                                                    showDatePicker = true
                                                                },
                                                                modifier = Modifier.fillMaxWidth()
                                                            ) {
                                                                Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(20.dp))
                                                            }

                                                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                                            listOf(24, 12, 6, 3, 1).forEach { hours ->
                                                                TextButton(
                                                                    onClick = {
                                                                        if (vm.isHapticEnabled()) {
                                                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                                        }
                                                                        val scheduledTime = System.currentTimeMillis() + (hours * 3600 * 1000L)
                                                                        showQuickScheduleOptions = false
                                                                        showActionOptions = false
                                                                        
                                                                        // Handle Notification Permission (Android 13+)
                                                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                                                            val permission = android.Manifest.permission.POST_NOTIFICATIONS
                                                                            if (androidx.core.content.ContextCompat.checkSelfPermission(this@ProcessTextActivity, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                                                                checkExactAlarmAndSchedule(scheduledTime)
                                                                            } else {
                                                                                pendingScheduledTime = scheduledTime
                                                                                requestPermissionLauncher.launch(permission)
                                                                            }
                                                                        } else {
                                                                            checkExactAlarmAndSchedule(scheduledTime)
                                                                        }
                                                                    },
                                                                    modifier = Modifier.fillMaxWidth()
                                                                ) {
                                                                    Text("${hours}h", style = MaterialTheme.typography.labelLarge)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            FloatingActionButton(
                                                onClick = {
                                                    if (vm.isHapticEnabled()) {
                                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                    }
                                                    if (canPost) {
                                                        showQuickScheduleOptions = !showQuickScheduleOptions
                                                    }
                                                },
                                                containerColor = if (canPost) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                contentColor = if (canPost) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Icon(Icons.Default.AccessTime, contentDescription = "Quick Schedule", modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }

                                    if (showActionOptions) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }

                                    // Draft
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = showActionOptions,
                                        enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                                        exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End)
                                    ) {
                                        FloatingActionButton(
                                            onClick = {
                                                if (vm.isHapticEnabled()) {
                                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                }
                                                if (canPost) {
                                                    showActionOptions = false
                                                    vm.saveManualDraft()
                                                    Toast.makeText(this@ProcessTextActivity, "Draft saved!", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            containerColor = if (canPost) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            contentColor = if (canPost) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(Icons.Default.Save, contentDescription = "Save Draft", modifier = Modifier.size(20.dp))
                                        }
                                    }

                                    if (showActionOptions) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }

                                    // Publish
                                    FloatingActionButton(
                                        onClick = {
                                            if (vm.isHapticEnabled()) {
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            }
                                            if (!showActionOptions) {
                                                showActionOptions = true
                                            } else {
                                                if (canPost) {
                                                    showActionOptions = false
                                                    if (vm.pubkey == null) {
                                                        if (Nip55.isSignerAvailable(this@ProcessTextActivity)) {
                                                            getPublicKeyLauncher.launch(
                                                                GetPublicKeyContract.Input(
                                                                    permissions = listOf(Permission.signEvent(9802), Permission.signEvent(1), Permission.signEvent(24242), Permission.signEvent(20), Permission.signEvent(22))
                                                                )
                                                            )
                                                        } else {
                                                            Toast.makeText(this@ProcessTextActivity, "No NIP-55 Signer app found.", Toast.LENGTH_LONG).show()
                                                        }
                                                    } else {
                                                        vm.currentSigningPurpose = ProcessTextViewModel.SigningPurpose.POST
                                                        if (vm.postKind == ProcessTextViewModel.PostKind.MEDIA || vm.mediaItems.isNotEmpty()) {
                                                            val pendingItems = vm.mediaItems.filter { it.uploadedUrl == null }
                                                            if (pendingItems.isNotEmpty()) {
                                                                val readyToUpload = pendingItems.find { !it.isUploading && !it.isProcessing }
                                                                if (readyToUpload != null) {
                                                                    vm.initiateUploadAuth(this@ProcessTextActivity, readyToUpload)
                                                                } else {
                                                                    Toast.makeText(this@ProcessTextActivity, "Please wait for uploads to complete.", Toast.LENGTH_SHORT).show()
                                                                }
                                                                return@FloatingActionButton
                                                            }
                                                        }
                                                        vm.requestSignature(vm.prepareEventJson())
                                                    }
                                                } else {
                                                    showActionOptions = false // Just collapse if empty
                                                }
                                            }
                                        },
                                        containerColor = if (canPost) Color(0xFF81C784) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        contentColor = if (canPost) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                                        modifier = Modifier.padding(end = 16.dp).size(48.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Publish", modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            val batchEvents by vm.batchEventsToSign.collectAsState()
            LaunchedEffect(batchEvents) {
                if (batchEvents.isNotEmpty() && vm.currentSigningPurpose == ProcessTextViewModel.SigningPurpose.BATCH_UPLOAD_AUTH) {
                    signEventsLauncher.launch(
                        SignEventsContract.Input(
                            eventsJson = batchEvents,
                            currentUser = vm.pubkey!!
                        )
                    )
                }
            }
            // Main Content
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                // Title Input (Media Only)
                if (vm.postKind == ProcessTextViewModel.PostKind.MEDIA) {
                    OutlinedTextField(
                        value = vm.mediaTitle,
                        onValueChange = { vm.mediaTitle = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // User Tagging Logic
                val cacheSize = vm.usernameCache.size // Read size to trigger recomposition when new users are resolved
                
                LaunchedEffect(vm.quoteContent) {
                    val entityPattern = java.util.regex.Pattern.compile("(nostr:)?(npub1|nprofile1)[a-z0-9]+", java.util.regex.Pattern.CASE_INSENSITIVE)
                    val matcher = entityPattern.matcher(vm.quoteContent)
                    while (matcher.find()) {
                        val match = matcher.group()
                        val bech32 = if (match.startsWith("nostr:")) match.substring(6) else match
                        vm.resolveUsername(bech32)
                    }
                }

                if (vm.showUserSearchDialog) {
                    UserSearchDialog(
                        vm = vm,
                        onDismiss = { vm.showUserSearchDialog = false },
                        onUserSelected = { pubkey ->
                            val nprofile = NostrUtils.pubkeyToNprofile(pubkey)
                            val tag = "nostr:$nprofile"
                            vm.updateQuote(vm.quoteContent + (if (vm.quoteContent.isEmpty() || vm.quoteContent.endsWith(" ")) "" else " ") + tag)
                            vm.showUserSearchDialog = false
                            vm.userSearchQuery = ""
                            vm.userSearchResults.clear()
                        }
                    )
                }

                if (showDraftsDialog) {
                    DraftsDialog(
                        vm = vm,
                        onDismiss = { showDraftsDialog = false },
                        onEditAndReschedule = { draftToEdit ->
                            vm.loadDraft(draftToEdit)
                            vm.currentDraftId = draftToEdit.id
                            showDraftsDialog = false
                            Toast.makeText(this@ProcessTextActivity, "Loaded for editing. Reschedule to replace.", Toast.LENGTH_LONG).show()
                        },
                        onSaveToDrafts = { draftToSave ->
                            vm.cancelScheduledNote(draftToSave)
                            vm.currentDraftId = null
                            vm.loadDraft(draftToSave.copy(isScheduled = false, scheduledAt = null, signedJson = null, isAutoSave = false))
                            vm.saveManualDraft()
                            // showDraftsDialog remains open or closes? User said "same as editor", so maybe close.
                            showDraftsDialog = false
                            Toast.makeText(this@ProcessTextActivity, "Moved to drafts.", Toast.LENGTH_LONG).show()
                        },
                        onOpenInClient = { eventId ->
                            val noteId = NostrUtils.eventIdToNote(eventId)
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostr:$noteId"))
                            try {
                                startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(this@ProcessTextActivity, "No Nostr client found.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = when (vm.postKind) {
                            ProcessTextViewModel.PostKind.HIGHLIGHT -> "Highlighted Text"
                            ProcessTextViewModel.PostKind.NOTE -> "What's on your mind?"
                            else -> "Description"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    

                }

                // Main Text Input
                val highlightColor = MaterialTheme.colorScheme.primary
                OutlinedTextField(
                    value = vm.quoteContent,
                    onValueChange = { vm.updateQuote(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .focusRequester(focusRequester), // Focus request
                    textStyle = MaterialTheme.typography.bodyLarge,
                    visualTransformation = remember(cacheSize, highlightColor) { 
                        NpubVisualTransformation(vm.usernameCache, highlightColor) 
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // URL Field - visible if Highlight or if URL is present
                // Source URL Input - Hide for Kind 1 (Note)
                if (vm.postKind != ProcessTextViewModel.PostKind.NOTE) {
                    val isNostrEvent = remember(vm.sourceUrl) {
                        val entity = NostrUtils.findNostrEntity(vm.sourceUrl)
                        entity != null && entity.type != "npub" && entity.type != "nprofile"
                    }
                    OutlinedTextField(
                        value = vm.sourceUrl,
                        onValueChange = { vm.updateSource(it) },
                        label = { Text(if (isNostrEvent) "Source Event" else "Source URL") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Media Row Carousel (Reverted to bottom)
                if (vm.mediaItems.isNotEmpty()) {
                    androidx.compose.ui.platform.LocalHapticFeedback.current

                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        items(vm.mediaItems.size) { index ->
                            val item = vm.mediaItems[index]
                            MediaThumbnail(
                                item = item,
                                onClick = { 
                                    if (item.uploadedUrl != null) {
                                        showMediaDetail = item
                                    } else {
                                        vm.showSharingDialog = true 
                                    }
                                },
                                onRemove = { vm.mediaItems.remove(item) }
                            )
                        }
                        
                        // Bulk Upload Trigger
                        if (vm.mediaItems.size > 1 && vm.mediaItems.any { it.uploadedUrl == null }) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                        .clickable { vm.showSharingDialog = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Refresh, "Upload All", tint = MaterialTheme.colorScheme.primary)
                                        Text("Upload All", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        // Add "Add more" button
                        item {
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .clickable { pickMediaLauncher.launch("image/* video/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.AddPhotoAlternate, "Add", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                }
 
                // Media Detail Dialog
                showMediaDetail?.let { item ->
                    MediaDetailDialog(
                        item = item,
                        vm = vm,
                        onDismiss = { showMediaDetail = null }
                    )
                }

                // Sharing Dialog
                if (vm.showSharingDialog) {
                    SharingDialog(
                        vm = vm,
                        onDismiss = { vm.showSharingDialog = false }
                    )
                }

                if (showDatePicker) {
                    val datePickerState = rememberDatePickerState(
                        initialSelectedDateMillis = System.currentTimeMillis()
                    )
                    DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                showDatePicker = false
                                showTimePicker = true
                                tempDateMillis = datePickerState.selectedDateMillis
                            }) { Text("Next") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
                        }
                    ) {
                        DatePicker(state = datePickerState)
                    }
                }

                if (showTimePicker) {
                    val timePickerState = rememberTimePickerState(
                        initialHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
                        initialMinute = java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE)
                    )
                    AlertDialog(
                        onDismissRequest = { showTimePicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                val selectedDate = tempDateMillis ?: System.currentTimeMillis()
                                
                                // DatePicker gives midnight UTC. We need to extract Y/M/D from UTC and set in local calendar.
                                val utcCalendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                                utcCalendar.timeInMillis = selectedDate
                                
                                val calendar = java.util.Calendar.getInstance()
                                calendar.set(java.util.Calendar.YEAR, utcCalendar.get(java.util.Calendar.YEAR))
                                calendar.set(java.util.Calendar.MONTH, utcCalendar.get(java.util.Calendar.MONTH))
                                calendar.set(java.util.Calendar.DAY_OF_MONTH, utcCalendar.get(java.util.Calendar.DAY_OF_MONTH))
                                calendar.set(java.util.Calendar.HOUR_OF_DAY, timePickerState.hour)
                                calendar.set(java.util.Calendar.MINUTE, timePickerState.minute)
                                calendar.set(java.util.Calendar.SECOND, 0)
                                calendar.set(java.util.Calendar.MILLISECOND, 0)
                                
                                val scheduledTime = calendar.timeInMillis
                                if (scheduledTime <= System.currentTimeMillis()) {
                                     Toast.makeText(this@ProcessTextActivity, "Please pick a future time.", Toast.LENGTH_SHORT).show()
                                } else {
                                    showTimePicker = false
                                    
                                    // Handle Notification Permission (Android 13+)
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                        val permission = android.Manifest.permission.POST_NOTIFICATIONS
                                        when {
                                            androidx.core.content.ContextCompat.checkSelfPermission(this@ProcessTextActivity, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                                                checkExactAlarmAndSchedule(scheduledTime)
                                            }
                                            else -> {
                                                pendingScheduledTime = scheduledTime
                                                requestPermissionLauncher.launch(permission)
                                            }
                                        }
                                    } else {
                                        checkExactAlarmAndSchedule(scheduledTime)
                                    }
                                }
                            }) { Text("Schedule") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                        },
                        title = { Text("Select Time") },
                        text = {
                            TimePicker(state = timePickerState)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MediaDetailDialog(
    vm: ProcessTextViewModel,
    item: MediaUploadState,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var isServersExpanded by remember { mutableStateOf(false) }
    val enabledServersCount = vm.blossomServers.count { it.enabled }
    
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())
            ) {
                // Large Media Preview
                val mediaModel = item.uploadedUrl ?: item.uri
                if (item.mimeType?.startsWith("image/") == true) {
                    coil.compose.AsyncImage(
                        model = mediaModel,
                        contentDescription = "Media Preview",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                } else {
                    // Video placeholder
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, "Video", tint = Color.White, modifier = Modifier.size(64.dp))
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Status Header
                if (item.isProcessing) {
                     Row(verticalAlignment = Alignment.CenterVertically) {
                         CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                         Spacer(modifier = Modifier.width(8.dp))
                         Text(item.status, style = MaterialTheme.typography.bodyMedium)
                     }
                     Spacer(modifier = Modifier.height(16.dp))
                } else if (item.isUploading) {
                     LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                     Text(item.status, style = MaterialTheme.typography.labelSmall)
                     Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Compression Selection
                if (item.uploadedUrl == null && !item.isUploading) {
                    Text("Image Compression", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val currentLevel = vm.batchCompressionLevel ?: vm.settingsRepository.getCompressionLevel()
                        val levels = listOf(
                            SettingsRepository.COMPRESSION_NONE to "None",
                            SettingsRepository.COMPRESSION_MEDIUM to "Balanced",
                            SettingsRepository.COMPRESSION_HIGH to "High"
                        )
                        
                        levels.forEach { (level, label) ->
                            FilterChip(
                                selected = currentLevel == level,
                                onClick = {
                                    if (vm.isHapticEnabled()) {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    }
                                    vm.updateBatchCompressionLevel(context, level)
                                },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // File Details
                Text("File Details", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                // Hash
                if (item.hash != null) {
                    Text("SHA-256 Hash:", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(
                            item.hash!!,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // Size
                if (item.size > 0L) {
                    val sizeKb = item.size / 1024
                    val sizeMb = sizeKb / 1024.0
                    val sizeText = if (sizeMb >= 1.0) String.format(java.util.Locale.US, "%.2f MB", sizeMb) else "$sizeKb KB"
                    Text("File Size: $sizeText", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // MIME Type
                if (item.mimeType != null) {
                    Text("MIME Type: ${item.mimeType}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // URL
                if (item.uploadedUrl != null) {
                    Text("URL:", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(
                            item.uploadedUrl!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Server Selection
                if (vm.blossomServers.isNotEmpty() && item.uploadedUrl == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isServersExpanded = !isServersExpanded }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            "Select Blossom Servers ($enabledServersCount)", 
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (isServersExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isServersExpanded) "Collapse" else "Expand"
                        )
                    }
                    
                    androidx.compose.animation.AnimatedVisibility(visible = isServersExpanded) {
                        Column(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                            vm.blossomServers.forEach { server ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !item.isUploading) { vm.toggleBlossomServer(server.url) }
                                        .padding(vertical = 4.dp)
                                ) {
                                    Checkbox(
                                        checked = server.enabled,
                                        onCheckedChange = { vm.toggleBlossomServer(server.url) },
                                        enabled = !item.isUploading
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = server.url,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (server.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Server Upload Results
                val itemResults = item.serverResults.value
                if (itemResults.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Server Upload Status", style = MaterialTheme.typography.titleMedium)
                        
                        val hasFailures = itemResults.any { !it.second }
                        if (hasFailures && !item.isUploading) {
                            TextButton(
                                onClick = { 
                                    if (vm.isHapticEnabled()) {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    }
                                    vm.initiateUploadAuth(context, item) 
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Retry Failed", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val itemHashes = item.serverHashes.value
                    itemResults.forEach { (server, success) ->
                        val serverHash = itemHashes[server]
                        val localHash = item.hash
                        val hashMatch = serverHash != null && serverHash == localHash
                        
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = if (success) Icons.Default.Check else Icons.Default.Close,
                                    contentDescription = if (success) "Success" else "Failed",
                                    tint = if (success) Color(0xFF4CAF50) else Color(0xFFF44336),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    server,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (success && item.uploadedUrl != null) {
                                    val clipboardManager = LocalClipboardManager.current
                                    IconButton(
                                        onClick = {
                                            if (vm.isHapticEnabled()) {
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            }
                                            clipboardManager.setText(AnnotatedString(item.uploadedUrl!!))
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, "Copy Link", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            // Show server-reported hash if available
                            if (success && serverHash != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 28.dp, top = 2.dp)
                                ) {
                                    Text(
                                        "Hash: ${serverHash.take(12)}...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (hashMatch) Color(0xFF4CAF50) else Color(0xFFFF9800)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        if (hashMatch) "✓ Match" else "⚠ Different",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (hashMatch) Color(0xFF4CAF50) else Color(0xFFFF9800)
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (item.isProcessing || item.isUploading) {
                         // Busy state
                    } else if (item.uploadedUrl == null) {
                        // Not uploaded yet
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { vm.initiateUploadAuth(context, item) }) { Text("Upload") }
                    } else {
                        // Uploaded
                        TextButton(
                            onClick = { 
                                vm.deleteMedia(item) 
                                onDismiss()
                            }, 
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { 
                            Text("Delete") 
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onDismiss) { Text("Close") }
                    }
                }
            }
        }
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaThumbnail(
    item: MediaUploadState,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current
    
    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    item.uploadedUrl?.let { url ->
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        clipboardManager.setText(AnnotatedString(url))
                    }
                }
            )
    ) {
        // Thumbnail Image or Video Icon
        if (item.mimeType?.startsWith("image/") == true) {
            val model = item.uploadedUrl ?: item.uri
            coil.compose.AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White)
            }
        }
        
        // Overlay Status
        if (item.isUploading || item.isProcessing) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            }
        } else if (item.uploadedUrl != null) {
            // Success indicator
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp).clip(
                    CircleShape
                ).background(Color(0xFF4CAF50)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        
        // Remove Button
        IconButton(
            onClick = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onRemove()
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).background(Color.Black.copy(alpha = 0.5f),
                CircleShape
            )
        ) {
            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
fun SharingDialog(
    vm: ProcessTextViewModel,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var isServersExpanded by remember { mutableStateOf(false) }
    val enabledServersCount = vm.blossomServers.count { it.enabled }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 600.dp)
            ) {
                Text("Sharing", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(16.dp))

                // Media List (Thumbnails at top)
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(vm.mediaItems.size) { index ->
                        val item = vm.mediaItems[index]
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            if (item.mimeType?.startsWith("image/") == true) {
                                coil.compose.AsyncImage(
                                    model = item.uri,
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    null,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                            
                            // Status overlays
                            if (item.uploadedUrl != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4CAF50)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            } else if (item.isUploading) {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                // Compression Selection
                if (!vm.isBatchUploading) {
                    Text("Image Compression", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val currentLevel = vm.batchCompressionLevel ?: vm.settingsRepository.getCompressionLevel()
                        val levels = listOf(
                            SettingsRepository.COMPRESSION_NONE to "None",
                            SettingsRepository.COMPRESSION_MEDIUM to "Balanced",
                            SettingsRepository.COMPRESSION_HIGH to "High"
                        )
                        
                        levels.forEach { (level, label) ->
                            FilterChip(
                                selected = currentLevel == level,
                                onClick = {
                                    if (vm.isHapticEnabled()) {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    }
                                    vm.updateBatchCompressionLevel(context, level)
                                },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Server Selection
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isServersExpanded = !isServersExpanded }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        "Select Blossom Servers ($enabledServersCount)", 
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (isServersExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isServersExpanded) "Collapse" else "Expand"
                    )
                }
                
                androidx.compose.animation.AnimatedVisibility(visible = isServersExpanded) {
                    Column(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        vm.blossomServers.forEach { server ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.toggleBlossomServer(server.url) }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = server.enabled,
                                    onCheckedChange = { vm.toggleBlossomServer(server.url) },
                                    enabled = !vm.isBatchUploading
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(server.url, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                if (vm.isBatchUploading || vm.batchProgress > 0f) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(if (vm.batchProgress >= 1f) "Upload Complete" else vm.batchUploadStatus, style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(
                        progress = { vm.batchProgress },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !vm.isBatchUploading) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (vm.isHapticEnabled()) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            vm.initiateBatchUpload(context)
                        },
                        enabled = !vm.isBatchUploading && vm.mediaItems.any { it.uploadedUrl == null }
                    ) {
                        Text("Upload All")
                    }
                }
            }
        }
    }
}

@Composable
fun UserSearchDialog(
    vm: ProcessTextViewModel,
    onDismiss: () -> Unit,
    onUserSelected: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add User") },
        text = {
            Column {
                OutlinedTextField(
                    value = vm.userSearchQuery,
                    onValueChange = { vm.performUserSearch(it) },
                    label = { Text("Search by name or npub") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                if (vm.isSearchingUsers) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                ) {
                    items(vm.userSearchResults.size) { index ->
                        val (pubkey, profile) = vm.userSearchResults[index]
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .clickable { onUserSelected(pubkey) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (profile.pictureUrl != null) {
                                coil.compose.AsyncImage(
                                    model = profile.pictureUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp).clip(CircleShape),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Default.Person, null, modifier = Modifier.size(40.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                val npub = try { NostrUtils.pubkeyToNpub(pubkey) } catch (_: Exception) { pubkey.take(16) + "..." }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(profile.name ?: pubkey.take(8), fontWeight = FontWeight.Bold)
                                    if (vm.followedPubkeys.contains(pubkey)) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            Icons.Default.Check, 
                                            contentDescription = "Following",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(npub, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

class NpubVisualTransformation(
    private val usernameCache: Map<String, UserProfile>,
    private val highlightColor: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val originalText = text.text
        val builder = AnnotatedString.Builder()
        
        val entities = mutableListOf<Triple<Int, Int, String>>() // Start, End, Label
        
        // Find npubs/nprofiles in the text
        val entityPattern = java.util.regex.Pattern.compile("(nostr:)?(npub1|nprofile1)[a-z0-9]+", java.util.regex.Pattern.CASE_INSENSITIVE)
        val matcher = entityPattern.matcher(originalText)
        
        var lastEnd = 0
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val match = matcher.group()
            
            builder.append(originalText.substring(lastEnd, start))
            
            val bech32 = if (match.startsWith("nostr:")) match.substring(6) else match
            val entity = try { NostrUtils.findNostrEntity(bech32) } catch (_: Exception) { null }
            val pubkey = entity?.id
            val username = pubkey?.let { usernameCache[it]?.name }
            
            val label = if (username != null) "@$username" else match
            builder.length
            builder.withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
                append(label)
            }
            entities.add(Triple(start, end, label))
            lastEnd = end
        }
        builder.append(originalText.substring(lastEnd))
        
        val transformedText = builder.toAnnotatedString()
        
        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                var currentOriginal = 0
                var currentTransformed = 0
                
                for (entity in entities) {
                    val (start, end, label) = entity
                    if (offset <= start) {
                        return currentTransformed + (offset - currentOriginal)
                    }
                    if (offset < end) {
                        // Inside the npub, map to start of label
                        return currentTransformed + (start - currentOriginal)
                    }
                    // At the end or past the npub, map to end of label
                    currentTransformed += (start - currentOriginal) + label.length
                    currentOriginal = end
                }
                return currentTransformed + (offset - currentOriginal)
            }

            override fun transformedToOriginal(offset: Int): Int {
                var currentOriginal = 0
                var currentTransformed = 0
                
                for (entity in entities) {
                    val (start, end, label) = entity
                    val nextTransformedStart = currentTransformed + (start - currentOriginal)
                    if (offset <= nextTransformedStart) {
                        return currentOriginal + (offset - currentTransformed)
                    }
                    val nextTransformedEnd = nextTransformedStart + label.length
                    if (offset < nextTransformedEnd) {
                        // Clicked inside the label, jump to start
                        return start 
                    }
                    // Past the label
                    currentTransformed = nextTransformedEnd
                    currentOriginal = end
                }
                return currentOriginal + (offset - currentTransformed)
            }
        }
        
        return androidx.compose.ui.text.input.TransformedText(transformedText, offsetMapping)
    }
}
