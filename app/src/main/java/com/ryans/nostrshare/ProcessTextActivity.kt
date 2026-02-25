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
import com.ryans.nostrshare.nip55.PostKind
import com.ryans.nostrshare.ui.theme.NostrShareTheme
import com.ryans.nostrshare.utils.UrlUtils
import com.ryans.nostrshare.ui.DraftsDialog
import com.ryans.nostrshare.ui.AccountSelectorMenu
import com.ryans.nostrshare.ui.UserAvatar
import com.ryans.nostrshare.data.Draft
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.animation.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import android.net.Uri
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.VerticalDivider
import androidx.compose.material.icons.filled.FormatSize
import com.ryans.nostrshare.utils.UnicodeStylizer
import com.ryans.nostrshare.utils.MarkdownUtils
import kotlinx.coroutines.launch
import java.util.UUID

class ProcessTextActivity : ComponentActivity() {

    private val viewModel: ProcessTextViewModel by viewModels()

    private val signEventLauncher = registerForActivityResult(SignEventContract()) { result ->
        result.onSuccess { signed ->
             if (!signed.signedEventJson.isNullOrBlank()) {
                 viewModel.onEventSigned(signed.signedEventJson)
             }
        }.onError { error ->
            Toast.makeText(this, "Signing failed: ${error.message}", Toast.LENGTH_SHORT).show()
            if (viewModel.isBatchUploading) viewModel.resetBatchState()
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
            if (viewModel.isBatchUploading) viewModel.resetBatchState()
        }
    }

    private val getPublicKeyLauncher = registerForActivityResult(GetPublicKeyContract()) { result ->
        result.onSuccess { pkResult ->
             viewModel.login(pkResult.pubkey, null, pkResult.packageName)
        }.onError { error ->
            Toast.makeText(this, "Login failed: ${error.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickMediaLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.onMediaSelected(this, uris)
    }

    private var pendingScheduledTime: Long? = null
    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        pendingScheduledTime?.let { checkExactAlarmAndSchedule(it) }
    }

    private var showExactAlarmDialog by mutableStateOf(false)

    private val exactAlarmLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        pendingScheduledTime?.let { checkExactAlarmAndSchedule(it) }
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

        viewModel.checkDraft()
        viewModel.verifyScheduledNotes(this)

         if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.let {
                viewModel.updateQuote(it)
                viewModel.onHighlightShared()
            }
        } else if (intent.action == Intent.ACTION_SEND) {
            if (intent.type?.startsWith("image/") == true || intent.type?.startsWith("video/") == true) {
                 @Suppress("DEPRECATION")
                 intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { viewModel.onMediaSelected(this, listOf(it)) }
            } else {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                val urlRegex = "(https?://[^\\s]+|nostr:[a-z0-9]+|nevent1[a-z0-9]+|naddr1[a-z0-9]+|note1[a-z0-9]+)".toRegex(RegexOption.IGNORE_CASE)
                val match = urlRegex.find(text)

                if (match != null) {
                    var entity = match.value
                    if (entity.contains("#:~:text=")) entity = entity.substringBefore("#:~:text=")
                    val content = text.replace(match.value, "").trim().removeSurrounding("\"")
                    if (content.isNotBlank()) {
                        viewModel.updateQuote(content)
                        viewModel.onHighlightShared()
                    } else if (!subject.isNullOrBlank()) {
                        viewModel.updateQuote(subject)
                    }
                    viewModel.updateSource(if (entity.startsWith("http")) UrlUtils.cleanUrl(entity) else entity)
                } else {
                    viewModel.updateQuote(text)
                }
            }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { viewModel.onMediaSelected(this, it) }
        }

        val draftId = intent.getIntExtra("DRAFT_ID", -1)
        if (draftId != -1) viewModel.loadDraftById(draftId)

        setContent {
            NostrShareTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!viewModel.isOnboarded) {
                        com.ryans.nostrshare.ui.OnboardingScreen(viewModel, getPublicKeyLauncher)
                    } else {
                        ShareScreen(viewModel, intent.getStringExtra("LAUNCH_MODE") == "REPOST")
                    }

                    if (showExactAlarmDialog) {
                         AlertDialog(
                            onDismissRequest = { showExactAlarmDialog = false; pendingScheduledTime?.let { viewModel.prepareScheduling(it); pendingScheduledTime = null } },
                            title = { Text("Exact Timing Permission") },
                            text = { Text("To publish at the exact time, Prism needs permission to schedule exact alarms.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showExactAlarmDialog = false
                                    val i = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    i.data = Uri.parse("package:$packageName")
                                    exactAlarmLauncher.launch(i)
                                }) { Text("Open Settings") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showExactAlarmDialog = false; pendingScheduledTime?.let { viewModel.prepareScheduling(it); pendingScheduledTime = null } }) { Text("Skip (Inexact)") }
                            }
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ShareScreen(vm: ProcessTextViewModel, isRepostIntent: Boolean) {
        val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        var showActionOptions by remember { mutableStateOf(false) }
        var isFullscreenMode by remember { mutableStateOf(false) }

        // System Bar Control
        val view = androidx.compose.ui.platform.LocalView.current
        val window = (androidx.compose.ui.platform.LocalContext.current as android.app.Activity).window
        val insetsController = remember(view, window) {
            androidx.core.view.WindowCompat.getInsetsController(window, view)
        }

        LaunchedEffect(isFullscreenMode) {
            if (isFullscreenMode) {
                insetsController?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                insetsController?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }

        LaunchedEffect(vm.contentValue, vm.sourceUrl, vm.postKind, vm.mediaItems.size, vm.previewTitle, vm.articleTitle, vm.articleSummary) {
            vm.saveDraft()
        }

        LaunchedEffect(vm.sourceUrl) {
            if (NostrUtils.findNostrEntity(vm.sourceUrl) == null && vm.sourceUrl.isNotBlank()) vm.fetchLinkPreview(vm.sourceUrl)
        }

        var showConfirmClear by remember { mutableStateOf(false) }
        var showMediaDetail by remember { mutableStateOf<MediaUploadState?>(null) }
        var showDatePicker by remember { mutableStateOf(false) }
        var showTimePicker by remember { mutableStateOf(false) }
        var showQuickScheduleOptions by remember { mutableStateOf(false) }
        var tempDateMillis by remember { mutableStateOf<Long?>(null) }

        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        LaunchedEffect(vm.publishSuccess) {
            if (vm.publishSuccess == true) {
                 Toast.makeText(this@ProcessTextActivity, vm.publishStatus, Toast.LENGTH_SHORT).show()
                 finish()
            }
        }

        val eventToSign by vm.eventToSign.collectAsState()
        LaunchedEffect(eventToSign) {
            eventToSign?.let { eventJson ->
                vm.pubkey?.let { pk ->
                     signEventLauncher.launch(SignEventContract.Input(eventJson = eventJson, currentUser = pk, id = System.currentTimeMillis().toString()))
                }
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                if (!isFullscreenMode) {
                    var showAccountMenu by remember { mutableStateOf(false) }
                    TopAppBar(
                        navigationIcon = {
                            Box {
                                IconButton(modifier = Modifier.padding(start = 8.dp).size(48.dp), onClick = { showAccountMenu = !showAccountMenu }) {
                                                                    UserAvatar(
                                                                        pictureUrl = vm.userProfile?.pictureUrl,
                                                                        pubkey = vm.pubkey,
                                                                        vm = vm,
                                                                        size = 48.dp
                                                                    )
                                    
                                }
                                AccountSelectorMenu(expanded = showAccountMenu, onDismiss = { showAccountMenu = false }, vm = vm, onAddAccount = { showAccountMenu = false; getPublicKeyLauncher.launch(GetPublicKeyContract.Input(permissions = listOf(Permission.signEvent(9802), Permission.signEvent(1), Permission.signEvent(30023), Permission.signEvent(20), Permission.signEvent(22)))) }, onSwitchAccount = { pk -> showAccountMenu = false; vm.switchUser(pk) })
                            }
                        },
                        title = {
                            var showModeMenu by remember { mutableStateOf(false) }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showModeMenu = true }.padding(8.dp)) {
                                Column {
                                    Text(vm.postKind.label, style = MaterialTheme.typography.titleMedium)
                                    if (vm.postKind == PostKind.MEDIA) Text("Media Upload", style = MaterialTheme.typography.labelSmall)
                                }
                                Icon(Icons.Default.ArrowDropDown, null)
                                DropdownMenu(expanded = showModeMenu, onDismissRequest = { showModeMenu = false }, properties = PopupProperties(focusable = false)) {
                                    vm.availableKinds.forEach { kind ->
                                        DropdownMenuItem(text = { Text(kind.label) }, onClick = { vm.setKind(kind); showModeMenu = false })
                                    }
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { vm.showDraftsHistory = true }) { Icon(Icons.Default.History, "History") }
                            IconButton(onClick = { startActivity(Intent(this@ProcessTextActivity, SettingsActivity::class.java)) }) { Icon(Icons.Default.Settings, "Settings") }
                        }
                    )
                }
            },
            bottomBar = {
                if (!isFullscreenMode) {
                    if (vm.isPublishing || vm.isUploading) {
                       BottomAppBar {
                           CircularProgressIndicator(modifier = Modifier.padding(start = 16.dp).size(24.dp))
                           Spacer(Modifier.width(16.dp))
                           val status = if (vm.isUploading) vm.uploadStatus else vm.publishStatus
                           Text(status, style = MaterialTheme.typography.bodyMedium)
                       }
                    } else if (vm.showDraftPrompt) {
                        BottomAppBar {
                            Text("Resume Draft?", modifier = Modifier.padding(start = 16.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
                            FloatingActionButton(onClick = { vm.discardDraft() }, containerColor = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(end = 16.dp)) { Icon(Icons.Default.Close, null) }
                            FloatingActionButton(onClick = { vm.applyDraft() }, modifier = Modifier.padding(end = 16.dp)) { Icon(Icons.Default.Check, null) }
                        }
                    } else {
                        val canPost = vm.quoteContent.isNotBlank() || vm.mediaItems.isNotEmpty() || vm.sourceUrl.isNotBlank()
                        BottomAppBar(
                            actions = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FloatingActionButton(onClick = { showConfirmClear = !showConfirmClear }, containerColor = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(start = 16.dp).size(48.dp)) {
                                        Icon(if (showConfirmClear) Icons.Default.Close else Icons.Default.Delete, null)
                                    }
                                    if (showConfirmClear) {
                                        Spacer(Modifier.width(8.dp))
                                        FloatingActionButton(onClick = { vm.clearContent(); finish() }, containerColor = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Check, null) }
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FloatingActionButton(onClick = { pickMediaLauncher.launch("image/* video/*") }, containerColor = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.AddPhotoAlternate, null, modifier = Modifier.size(20.dp)) }
                                    Spacer(Modifier.width(8.dp))
                                    FloatingActionButton(onClick = { vm.showUserSearchDialog = true }, containerColor = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(20.dp)) }
                                    Spacer(Modifier.width(16.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AnimatedVisibility(visible = showActionOptions, enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End), exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End)) {
                                            FloatingActionButton(onClick = { showActionOptions = false; showQuickScheduleOptions = false }, containerColor = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, null, modifier = Modifier.size(20.dp)) }
                                        }
                                        if (showActionOptions) {
                                            Spacer(Modifier.width(8.dp))
                                            if (vm.isSchedulingEnabled) {
                                                Box(contentAlignment = Alignment.BottomCenter) {
                                                    if (showQuickScheduleOptions) {
                                                        Popup(alignment = Alignment.BottomCenter, offset = androidx.compose.ui.unit.IntOffset(0, -160), onDismissRequest = { showQuickScheduleOptions = false }, properties = PopupProperties(focusable = false)) {
                                                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 8.dp, modifier = Modifier.width(80.dp)) {
                                                                Column(Modifier.padding(4.dp)) {
                                                                    TextButton(onClick = { showQuickScheduleOptions = false; showActionOptions = false; showDatePicker = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(20.dp)) }
                                                                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                                                    listOf(24, 12, 6, 3, 1).forEach { hours ->
                                                                        TextButton(onClick = { val t = System.currentTimeMillis() + (hours * 3600 * 1000L); checkExactAlarmAndSchedule(t); showQuickScheduleOptions = false; showActionOptions = false }, modifier = Modifier.fillMaxWidth()) { Text("${hours}h") }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                    FloatingActionButton(onClick = { if (canPost) showQuickScheduleOptions = !showQuickScheduleOptions }, containerColor = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.AccessTime, null, modifier = Modifier.size(20.dp)) }
                                                }
                                                Spacer(Modifier.width(8.dp))
                                            }
                                            FloatingActionButton(onClick = { if (canPost) { vm.saveManualDraft(); finish() } }, containerColor = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Save, null, modifier = Modifier.size(20.dp)) }
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        FloatingActionButton(onClick = { if (!showActionOptions) showActionOptions = true else if (canPost) { if (vm.pubkey == null) getPublicKeyLauncher.launch(GetPublicKeyContract.Input(permissions = listOf(Permission.signEvent(9802), Permission.signEvent(1), Permission.signEvent(30023), Permission.signEvent(20), Permission.signEvent(22)))) else { if (vm.mediaItems.any { it.uploadedUrl == null }) vm.initiateUploadAuth(vm.mediaItems.first { it.uploadedUrl == null }) else vm.requestSignature(vm.prepareEventJson()) } } }, containerColor = if (canPost) Color(0xFF81C784) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.padding(end = 16.dp).size(48.dp)) { Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(20.dp)) }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            val batchEvents by vm.batchEventsToSign.collectAsState()
            LaunchedEffect(batchEvents) {
                if (batchEvents.isNotEmpty() && vm.currentSigningPurpose == ProcessTextViewModel.SigningPurpose.BATCH_UPLOAD_AUTH) {
                    signEventsLauncher.launch(SignEventsContract.Input(eventsJson = batchEvents, currentUser = vm.pubkey!!))
                }
            }

            Box(Modifier.fillMaxSize()) {
                Column(Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .displayCutoutPadding()
                    .padding(if (isFullscreenMode) 8.dp else 16.dp)
                    .fillMaxSize()) {
                    if (isFullscreenMode && vm.postKind == PostKind.ARTICLE) {
                        OutlinedTextField(
                            value = vm.articleTitle,
                            onValueChange = { vm.articleTitle = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Article Title", style = MaterialTheme.typography.headlineMedium) },
                            textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent)
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }

                    if (!isFullscreenMode) {
                        if (vm.postKind == PostKind.ARTICLE) {
                            OutlinedTextField(value = vm.articleTitle, onValueChange = { vm.articleTitle = it }, label = { Text("Article Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.titleMedium, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = vm.articleSummary, onValueChange = { vm.articleSummary = it }, label = { Text("Article Summary (Optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 2, textStyle = MaterialTheme.typography.bodySmall, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                            Spacer(Modifier.height(8.dp))
                        } else if (vm.postKind == PostKind.MEDIA) {
                            OutlinedTextField(value = vm.mediaTitle, onValueChange = { vm.mediaTitle = it }, label = { Text("Media Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(text = if (vm.postKind == PostKind.ARTICLE) "Body (Markdown)" else "Content", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    
                    val highlightColor = MaterialTheme.colorScheme.primary
                    val codeColor = MaterialTheme.colorScheme.secondary
                    val quoteColor = MaterialTheme.colorScheme.tertiary
                    val linkColor = Color(0xFF2196F3)
                    val nostrColor = Color(0xFF9C27B0)
                    val h1Style = MaterialTheme.typography.headlineLarge
                    val h2Style = MaterialTheme.typography.headlineMedium
                    val h3Style = MaterialTheme.typography.headlineSmall

                    OutlinedTextField(
                        value = vm.contentValue,
                        onValueChange = { newValue ->
                            if (!vm.isVisualMode && newValue.text.length > vm.contentValue.text.length && newValue.text.endsWith("\n")) {
                                val lastLine = vm.contentValue.text.lines().last()
                                val prefix = when {
                                    lastLine.startsWith("- ") -> "- "
                                    lastLine.startsWith("> ") -> "> "
                                    else -> null
                                }
                                if (prefix != null) {
                                    if (lastLine.trim() == prefix.trim()) {
                                        val cleared = newValue.text.substring(0, newValue.text.length - lastLine.length - 1) + "\n"
                                        vm.contentValue = newValue.copy(text = cleared)
                                    } else {
                                        val padded = newValue.text + prefix
                                        vm.contentValue = newValue.copy(text = padded, selection = androidx.compose.ui.text.TextRange(padded.length))
                                    }
                                } else vm.contentValue = newValue
                            } else vm.contentValue = newValue
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(focusRequester),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        visualTransformation = remember(vm.usernameCache.size, highlightColor, vm.isVisualMode, h1Style, h2Style, h3Style) {
                            MarkdownVisualTransformation(vm.usernameCache, highlightColor, codeColor, quoteColor, linkColor, nostrColor, h1Style, h2Style, h3Style, stripDelimiters = vm.isVisualMode)
                        },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        shape = if (isFullscreenMode) RoundedCornerShape(0.dp) else OutlinedTextFieldDefaults.shape,
                        colors = if (isFullscreenMode) OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent) else OutlinedTextFieldDefaults.colors()
                    )

                    MarkdownToolbar(vm, isFullscreenMode, onToggleFullscreen = { isFullscreenMode = !isFullscreenMode })

                    if (!isFullscreenMode) {
                        if (vm.postKind != PostKind.NOTE && vm.postKind != PostKind.ARTICLE) {
                            OutlinedTextField(value = vm.sourceUrl, onValueChange = { vm.updateSource(it) }, label = { Text("Source URL / Event") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true)
                        }
                        if (vm.mediaItems.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.foundation.lazy.LazyRow(modifier = Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(vm.mediaItems.size) { i -> val item = vm.mediaItems[i]; MediaThumbnail(item = item, onClick = { if (item.uploadedUrl != null) showMediaDetail = item else vm.showSharingDialog = true }, onRemove = { vm.mediaItems.remove(item) }) }
                                item { Box(Modifier.size(100.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).clickable { pickMediaLauncher.launch("image/* video/*") }, contentAlignment = Alignment.Center) { Icon(Icons.Default.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.primary) } }
                            }
                        }
                    }

                    if (vm.showUserSearchDialog) UserSearchDialog(vm = vm, onDismiss = { vm.showUserSearchDialog = false }, onUserSelected = { pk -> vm.updateQuote(vm.quoteContent + (if (vm.quoteContent.isEmpty() || vm.quoteContent.endsWith(" ")) "" else " ") + "nostr:${NostrUtils.pubkeyToNprofile(pk)}"); vm.showUserSearchDialog = false })
                    showMediaDetail?.let { item -> MediaDetailDialog(item = item, vm = vm, onDismiss = { showMediaDetail = null }) }
                    if (vm.showSharingDialog) SharingDialog(vm = vm, onDismiss = { vm.showSharingDialog = false })
                    if (showDatePicker) { val ds = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis()); DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = { TextButton(onClick = { showDatePicker = false; showTimePicker = true; tempDateMillis = ds.selectedDateMillis }) { Text("Next") } }) { DatePicker(state = ds) } }
                    if (showTimePicker) { val ts = rememberTimePickerState(); AlertDialog(onDismissRequest = { showTimePicker = false }, confirmButton = { TextButton(onClick = { val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = tempDateMillis ?: System.currentTimeMillis() }; val cal = java.util.Calendar.getInstance().apply { set(utc.get(java.util.Calendar.YEAR), utc.get(java.util.Calendar.MONTH), utc.get(java.util.Calendar.DAY_OF_MONTH), ts.hour, ts.minute, 0) }; if (cal.timeInMillis <= System.currentTimeMillis()) Toast.makeText(this@ProcessTextActivity, "Pick future time", Toast.LENGTH_SHORT).show() else checkExactAlarmAndSchedule(cal.timeInMillis); showTimePicker = false }) { Text("Schedule") } }, title = { Text("Select Time") }, text = { TimePicker(state = ts) }) }
                    if (vm.showDraftsHistory) DraftsDialog(vm = vm, onDismiss = { vm.showDraftsHistory = false }, onEditAndReschedule = { d -> vm.showDraftsHistory = false; vm.loadDraftById(d.id) }, onSaveToDrafts = {}, onOpenInClient = { note ->
                        val targetId = if (note.kind == 6 || note.kind == 16) {
                            NostrUtils.getTargetEventIdFromRepost(note.originalEventJson ?: "") ?: note.id
                        } else {
                            note.id
                        }
                        val noteId = NostrUtils.eventIdToNote(targetId)
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("nostr:$noteId"))) } catch (_: Exception) {}
                    }, onRepost = { d -> vm.showDraftsHistory = false; vm.quoteContent = ""; vm.sourceUrl = d.sourceUrl; vm.setKind(PostKind.REPOST); vm.originalEventJson = d.originalEventJson; vm.highlightEventId = d.highlightEventId; vm.highlightAuthor = d.highlightAuthor; vm.highlightKind = d.highlightKind; vm.highlightIdentifier = d.highlightIdentifier; showDatePicker = true })
                }

                if (vm.showLinkDialog) {
                    LinkDialog(vm, focusRequester)
                }
            }
        }
    }

    @Composable
    fun MarkdownToolbar(vm: ProcessTextViewModel, isFullscreen: Boolean, onToggleFullscreen: () -> Unit) {
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        val textToolbar = androidx.compose.ui.platform.LocalTextToolbar.current
        var showStyleMenu by remember { mutableStateOf(false) }

        LaunchedEffect(showStyleMenu) {
            if (showStyleMenu) {
                textToolbar.hide()
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = if (isFullscreen) 8.dp else 0.dp, vertical = 4.dp)) {
            Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 2.dp, shape = RoundedCornerShape(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(modifier = Modifier.weight(1f).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item { ToolbarButton(Icons.Default.FormatBold) { vm.applyInlineMarkdown("**") } }
                        item { ToolbarButton(Icons.Default.FormatItalic) { vm.applyInlineMarkdown("_") } }
                        item { ToolbarButton(Icons.Default.FormatStrikethrough) { vm.applyStrikethrough() } }
                        item { ToolbarButton(Icons.Default.FormatUnderlined) { vm.applyUnderline() } }
                        item {
                            var showHeadingMenu by remember { mutableStateOf(false) }
                            Box {
                                ToolbarButton(Icons.Default.Title) { showHeadingMenu = true }
                                DropdownMenu(
                                    expanded = showHeadingMenu,
                                    onDismissRequest = { showHeadingMenu = false },
                                    properties = PopupProperties(focusable = false)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("H1 (Heading 1)") },
                                        onClick = { vm.applyBlockMarkdown("# "); showHeadingMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("H2 (Heading 2)") },
                                        onClick = { vm.applyBlockMarkdown("## "); showHeadingMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("H3 (Heading 3)") },
                                        onClick = { vm.applyBlockMarkdown("### "); showHeadingMenu = false }
                                    )
                                }
                            }
                        }
                        item { ToolbarButton(Icons.Default.FormatQuote) { vm.applyBlockMarkdown("> ") } }
                        item { ToolbarButton(Icons.Default.Code) { vm.applyCodeMarkdown() } }
                        item { ToolbarButton(Icons.Default.FormatListBulleted) { vm.applyBlockMarkdown("- ") } }
                        item {
                            Box {
                                ToolbarButton(Icons.Default.FormatSize) { showStyleMenu = true }
                                DropdownMenu(
                                    expanded = showStyleMenu,
                                    onDismissRequest = { showStyleMenu = false },
                                    properties = PopupProperties(focusable = false)
                                ) {
                                    val selection = vm.contentValue.selection
                                    val selectedText = if (!selection.collapsed) {
                                        val raw = vm.contentValue.text.substring(selection.min, selection.max)
                                        UnicodeStylizer.normalize(raw)
                                    } else null

                                    val col1 = listOf(
                                        UnicodeStylizer.Style.DEFAULT,
                                        UnicodeStylizer.Style.SANS_BOLD,
                                        UnicodeStylizer.Style.SANS_ITALIC,
                                        UnicodeStylizer.Style.SANS_BOLD_ITALIC,
                                        UnicodeStylizer.Style.SERIF_BOLD,
                                        UnicodeStylizer.Style.SERIF_ITALIC,
                                        UnicodeStylizer.Style.SERIF_BOLD_ITALIC,
                                        UnicodeStylizer.Style.MONOSPACE
                                    )
                                    val col2 = UnicodeStylizer.Style.values().filter { it !in col1 }

                                    @Composable
                                    fun StyleItem(style: UnicodeStylizer.Style) {
                                        DropdownMenuItem(
                                            text = {
                                                val textToStylize = selectedText ?: style.preview
                                                val rawPreview = UnicodeStylizer.stylize(textToStylize, style)
                                                val previewText = if (selectedText != null && rawPreview.codePointCount(0, rawPreview.length) > 10) {
                                                    val endOffset = rawPreview.offsetByCodePoints(0, 10)
                                                    rawPreview.substring(0, endOffset) + "..."
                                                } else {
                                                    rawPreview
                                                }
                                                Text(previewText)
                                            },
                                            onClick = {
                                                vm.applyUnicodeStyle(style)
                                                showStyleMenu = false
                                            }
                                        )
                                    }

                                    Row {
                                        Column(Modifier.width(IntrinsicSize.Max)) {
                                            col1.forEach { StyleItem(it) }
                                        }
                                        Column(Modifier.width(IntrinsicSize.Max)) {
                                            col2.forEach { StyleItem(it) }
                                        }
                                    }
                                }
                            }
                        }
                        item { ToolbarButton(Icons.Default.Link) { val clip = clipboardManager.getText()?.text; vm.openLinkDialog(clip) } }
                        item { VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp)) }
                        item {
                            IconButton(
                                onClick = { vm.isVisualMode = !vm.isVisualMode },
                                modifier = Modifier.size(36.dp),
                                colors = if (vm.isVisualMode) IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else IconButtonDefaults.iconButtonColors()
                            ) {
                                Icon(if (vm.isVisualMode) Icons.Default.Visibility else Icons.Default.VisibilityOff, null, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(36.dp)) { Icon(if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, null, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }

    @Composable
    fun ToolbarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) { Icon(icon, null, modifier = Modifier.size(20.dp)) }
    }

    @Composable
    fun LinkDialog(vm: ProcessTextViewModel, editorFocusRequester: androidx.compose.ui.focus.FocusRequester) {
        val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable { 
                    vm.showLinkDialog = false
                    editorFocusRequester.requestFocus()
                },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .padding(24.dp)
                    .clickable(enabled = false) { }
                    .widthIn(max = 400.dp),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 8.dp
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Add/Edit Link", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = vm.linkDialogText,
                        onValueChange = { vm.linkDialogText = it },
                        label = { Text("Text") },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vm.linkDialogUrl,
                        onValueChange = { vm.linkDialogUrl = it },
                        label = { Text("URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { 
                            vm.showLinkDialog = false
                            editorFocusRequester.requestFocus()
                        }) {
                            Text("Cancel")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { 
                            vm.applyLink(vm.linkDialogText, vm.linkDialogUrl)
                            editorFocusRequester.requestFocus()
                        }) {
                            Text("Apply")
                        }
                    }
                }
            }
        }
    }
}

class MarkdownVisualTransformation(
    private val usernameCache: Map<String, UserProfile>, 
    private val highlightColor: Color, 
    private val codeColor: Color, 
    private val quoteColor: Color,
    private val linkColor: Color = Color.Blue,
    private val nostrColor: Color = Color.Magenta,
    private val h1Style: androidx.compose.ui.text.TextStyle? = null,
    private val h2Style: androidx.compose.ui.text.TextStyle? = null,
    private val h3Style: androidx.compose.ui.text.TextStyle? = null,
    private val stripDelimiters: Boolean = false
) : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val original = text.text
        
        val result = com.ryans.nostrshare.utils.MarkdownUtils.renderMarkdown(
            text = original,
            usernameCache = usernameCache,
            highlightColor = highlightColor,
            codeColor = codeColor,
            linkColor = linkColor,
            nostrColor = nostrColor,
            h1Style = h1Style,
            h2Style = h2Style,
            h3Style = h3Style,
            stripDelimiters = stripDelimiters
        )

        val offsetMapping = object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val idx = offset.coerceIn(0, original.length)
                return if (idx < result.originalToTransformed.size) result.originalToTransformed[idx] else result.annotatedString.length
            }

            override fun transformedToOriginal(offset: Int): Int {
                if (result.transformedToOriginal.isEmpty()) return 0
                val idx = offset.coerceIn(0, result.transformedToOriginal.size - 1)
                return result.transformedToOriginal[idx]
            }
        }

        return androidx.compose.ui.text.input.TransformedText(result.annotatedString, offsetMapping)
    }
}

@Composable
fun MediaDetailDialog(vm: ProcessTextViewModel, item: MediaUploadState, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                val model = item.uploadedUrl ?: item.uri
                if (item.mimeType?.startsWith("image/") == true) AsyncImage(model = model, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                else Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).clip(RoundedCornerShape(8.dp)).background(Color.Black).clickable { try { context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(if (model is String) Uri.parse(model) else model as Uri, item.mimeType ?: "video/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) } catch (_: Exception) {} }, contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(64.dp)) }
                Spacer(Modifier.height(16.dp))
                if (item.isProcessing) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("File: ${item.mimeType} | ${item.size / 1024} KB", style = MaterialTheme.typography.bodySmall)
                if (item.uploadedUrl != null) {
                    val cb = LocalClipboardManager.current
                    TextButton(onClick = { cb.setText(AnnotatedString(item.uploadedUrl!!)) }) { Icon(Icons.Default.ContentCopy, null); Text("Copy Link") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (item.uploadedUrl == null && !item.isUploading) Button(onClick = { vm.initiateUploadAuth(item) }) { Text("Upload") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
fun MediaThumbnail(item: MediaUploadState, onRemove: () -> Unit, onClick: () -> Unit) {
    Box(Modifier.size(100.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onClick() }) {
        if (item.mimeType?.startsWith("video/") == true) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp)) }
        else AsyncImage(model = item.uploadedUrl ?: item.uri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)) { Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}

@Composable
fun SharingDialog(vm: ProcessTextViewModel, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
            Column(Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 600.dp)) {
                Text("Sharing", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.mediaItems.size) { i -> val item = vm.mediaItems[i]; Box(Modifier.size(100.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) { if (item.mimeType?.startsWith("image/") == true) AsyncImage(model = item.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) else Icon(Icons.Default.PlayArrow, null, Modifier.align(Alignment.Center)); if (item.uploadedUrl != null) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(Color(0xFF4CAF50)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) } else if (item.isUploading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(24.dp)) } }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !vm.isBatchUploading) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { vm.initiateBatchUpload(context) }, enabled = !vm.isBatchUploading && vm.mediaItems.any { it.uploadedUrl == null }) { Text("Upload All") }
                }
            }
        }
    }
}

@Composable
fun UserSearchDialog(vm: ProcessTextViewModel, onDismiss: () -> Unit, onUserSelected: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add User") }, text = { Column { OutlinedTextField(value = vm.userSearchQuery, onValueChange = { vm.performUserSearch(it) }, label = { Text("Search by name or npub") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)); Spacer(Modifier.height(16.dp)); if (vm.isSearchingUsers) LinearProgressIndicator(Modifier.fillMaxWidth()); androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) { items(vm.userSearchResults.size) { i -> val (pk, p) = vm.userSearchResults[i]; Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { onUserSelected(pk) }, verticalAlignment = Alignment.CenterVertically) { UserAvatar(pictureUrl = p.pictureUrl, pubkey = pk, vm = vm, size = 40.dp); Spacer(Modifier.width(12.dp)); Column { Row(verticalAlignment = Alignment.CenterVertically) { Text(p.name ?: pk.take(8), fontWeight = FontWeight.Bold); if (vm.followedPubkeys.contains(pk)) { Spacer(Modifier.width(4.dp)); Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) } }; Text(try { NostrUtils.pubkeyToNpub(pk) } catch (_: Exception) { pk.take(16) + "..." }, style = MaterialTheme.typography.labelSmall) } } } } } }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
