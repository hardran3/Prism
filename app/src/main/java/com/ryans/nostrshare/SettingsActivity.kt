package com.ryans.nostrshare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.widget.Toast
import android.content.Intent
import com.ryans.nostrshare.nip55.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Save
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import com.ryans.nostrshare.ui.theme.NostrShareTheme
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalUriHandler
import com.ryans.nostrshare.nip55.npubToHex
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

class SettingsActivity : ComponentActivity() {
    private val viewModel: SettingsViewModel by viewModels()

    private val signEventLauncher = registerForActivityResult(SignEventContract()) { result ->
        result.onSuccess { signed ->
            if (!signed.signedEventJson.isNullOrBlank()) {
                viewModel.onEventSigned(signed.signedEventJson)
            }
        }.onError { error ->
            Toast.makeText(this, "Signing failed: ${error.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val getPublicKeyLauncher = registerForActivityResult(GetPublicKeyContract()) { result ->
        result.onSuccess { pkResult ->
            viewModel.pubkey = pkResult.pubkey
            viewModel.signerPackageName = pkResult.packageName
            // Optional: refresh profile or something
        }.onError { error ->
            Toast.makeText(this, "Login failed: ${error.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository(this)
        
        setContent {
            val eventToSign by viewModel.eventToSign.collectAsState()
            
            LaunchedEffect(eventToSign) {
                eventToSign?.let { json ->
                    val currentUser = viewModel.npub ?: viewModel.pubkey ?: ""
                    val input = SignEventContract.Input(eventJson = json, currentUser = currentUser)
                    signEventLauncher.launch(input)
                }
            }

            NostrShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        repo = repo,
                        viewModel = viewModel,
                        onBack = { finish() },
                        onLogin = {
                            getPublicKeyLauncher.launch(GetPublicKeyContract.Input())
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: SettingsRepository,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onLogin: () -> Unit
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("General", "Nostr", "Blossom", "About")
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel.publishStatus) {
        if (viewModel.publishStatus.isNotEmpty() && !viewModel.isPublishing) {
            snackbarHostState.showSnackbar(viewModel.publishStatus)
            viewModel.publishStatus = ""
        }
    }

    Scaffold(
        topBar = {
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (repo.isHapticEnabled()) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        }
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }
            
            when (selectedTabIndex) {
                0 -> GeneralSettingsTab(repo)
                1 -> NostrSettingsTab(repo)
                2 -> BlossomSettingsTab(repo, viewModel, onLogin)
                3 -> AboutSettingsTab(repo)
            }
        }
    }
}

@Composable
fun GeneralSettingsTab(repo: SettingsRepository) {
    var hapticEnabled by remember { mutableStateOf(repo.isHapticEnabled()) }
    
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Image Compression", style = MaterialTheme.typography.bodyLarge)
            Text("Balance between file size and visual quality.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            Spacer(modifier = Modifier.height(12.dp))
            
            var compressionLevel by remember { mutableStateOf(repo.getCompressionLevel()) }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val levels = listOf(
                    SettingsRepository.COMPRESSION_NONE to "None",
                    SettingsRepository.COMPRESSION_MEDIUM to "Balanced",
                    SettingsRepository.COMPRESSION_HIGH to "High"
                )
                
                levels.forEach { (level, label) ->
                    FilterChip(
                        selected = compressionLevel == level,
                        onClick = {
                            if (repo.isHapticEnabled()) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            }
                            compressionLevel = level
                            repo.setCompressionLevel(level)
                        },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Haptic Feedback", style = MaterialTheme.typography.bodyLarge)
                Text("Tactile vibrations on interactions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = hapticEnabled,
                onCheckedChange = { 
                    if (it) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    hapticEnabled = it
                    repo.setHapticEnabled(it)
                }
            )
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        
        var showLogDialog by remember { mutableStateOf(false) }
        
        OutlinedButton(
            onClick = { showLogDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("View Scheduler Logs")
        }
        
        if (showLogDialog) {
            SchedulerLogDialog(onDismiss = { showLogDialog = false })
        }
    }
}

@Composable
fun NostrSettingsTab(repo: SettingsRepository) {
    var alwaysKind1 by remember { mutableStateOf(repo.isAlwaysUseKind1()) }
    var blastrEnabled by remember { mutableStateOf(repo.isBlastrEnabled()) }
    var citrineRelayEnabled by remember { mutableStateOf(repo.isCitrineRelayEnabled()) }
    
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Default To Kind 1", style = MaterialTheme.typography.bodyLarge)
                Text("Start all posts as Text Notes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = alwaysKind1,
                onCheckedChange = { 
                    if (repo.isHapticEnabled()) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    alwaysKind1 = it
                    repo.setAlwaysUseKind1(it)
                }
            )
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enable Blastr", style = MaterialTheme.typography.bodyLarge)
                Text("Broadcast via wss://sendit.nosflare.com/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = blastrEnabled,
                onCheckedChange = { 
                    if (repo.isHapticEnabled()) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    blastrEnabled = it
                    repo.setBlastrEnabled(it)
                }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Citrine Local Relay", style = MaterialTheme.typography.bodyLarge)
                Text("Post to ws://localhost:4869 (e.g., Citrine app)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = citrineRelayEnabled,
                onCheckedChange = {
                    if (repo.isHapticEnabled()) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    citrineRelayEnabled = it
                    repo.setCitrineRelayEnabled(it)
                }
            )
        }
    }
}

@Composable
fun SchedulerLogDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var logs by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        logs = com.ryans.nostrshare.utils.SchedulerLog.getLogs(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scheduler Logs") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = logs,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState()),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        com.ryans.nostrshare.utils.SchedulerLog.clearLogs(context)
                        logs = "Logs cleared."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear Logs")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun BlossomSettingsTab(
    repo: SettingsRepository,
    viewModel: SettingsViewModel,
    onLogin: () -> Unit
) {
    var servers by remember { mutableStateOf(repo.getBlossomServers()) }
    var draggingItemIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showPublishConfirm by remember { mutableStateOf(false) }
    
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        if (viewModel.isPublishing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = viewModel.publishStatus,
                modifier = Modifier.padding(8.dp).align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(servers.size, key = { index -> servers[index].url }) { index ->
                    val server = servers[index]
                    val isDragging = draggingItemIndex == index
                    val currentIndex by rememberUpdatedState(index)
                    
                    BlossomServerCard(
                        modifier = Modifier.pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { 
                                    draggingItemIndex = currentIndex
                                    if (repo.isHapticEnabled()) {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount.y
                                    
                                    // Threshold should roughly match card height (approx 72dp including spacing)
                                    // 72dp * 2.75 (avg density) ≈ 200f
                                    val threshold = 180f 
                                    val currentIdx = currentIndex
                                    if (dragOffset > threshold && currentIdx < servers.size - 1) {
                                        val newServers = servers.toMutableList()
                                        val item = newServers.removeAt(currentIdx)
                                        newServers.add(currentIdx + 1, item)
                                        servers = newServers
                                        repo.setBlossomServers(newServers)
                                        draggingItemIndex = currentIdx + 1
                                        // Subtract the distance moved to keep item under finger
                                        dragOffset -= threshold + 20f 
                                        if (repo.isHapticEnabled()) {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                        }
                                    } else if (dragOffset < -threshold && currentIdx > 0) {
                                        val newServers = servers.toMutableList()
                                        val item = newServers.removeAt(currentIdx)
                                        newServers.add(currentIdx - 1, item)
                                        servers = newServers
                                        repo.setBlossomServers(newServers)
                                        draggingItemIndex = currentIdx - 1
                                        // Add back the distance moved
                                        dragOffset += threshold + 20f
                                        if (repo.isHapticEnabled()) {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    draggingItemIndex = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    draggingItemIndex = null
                                    dragOffset = 0f
                                }
                            )
                        },
                        server = server,
                        isDragging = isDragging,
                        dragOffset = if (isDragging) dragOffset else 0f,
                        onToggle = { enabled ->
                            val updated = servers.map { 
                                if (it.url == server.url) it.copy(enabled = enabled) else it 
                            }
                            servers = updated
                            repo.setBlossomServers(updated)
                        },
                        onDelete = {
                            val updated = servers.filter { it.url != server.url }
                            servers = updated
                            repo.setBlossomServers(updated)
                        },
                        repo = repo
                    )
                }
                
                item { Spacer(modifier = Modifier.height(72.dp)) }
            }

            FloatingActionButton(
                onClick = { 
                    if (repo.isHapticEnabled()) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    showAddDialog = true 
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Server")
            }

            FloatingActionButton(
                onClick = {
                    if (repo.isHapticEnabled()) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    showPublishConfirm = true
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(Icons.Default.Save, contentDescription = "Publish to Nostr")
            }
        }
    }

    if (showPublishConfirm) {
        AlertDialog(
            onDismissRequest = { showPublishConfirm = false },
            title = { Text("Publish to Nostr?") },
            text = { Text("This will update your Blossom server list (Kind 10063) on your relays.") },
            confirmButton = {
                TextButton(onClick = {
                    showPublishConfirm = false
                    if (viewModel.pubkey == null) {
                        onLogin()
                    } else {
                        viewModel.publishBlossomList(servers.map { it.url })
                    }
                }) {
                    Text("Publish")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPublishConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { url ->
                val newServer = BlossomServer(url.trim(), true)
                val updated = servers + newServer
                servers = updated
                repo.setBlossomServers(updated)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun BlossomServerCard(
    modifier: Modifier = Modifier,
    server: BlossomServer,
    isDragging: Boolean = false,
    dragOffset: Float = 0f,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    repo: SettingsRepository
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isDragging) {
                    Modifier.graphicsLayer {
                        translationY = dragOffset
                        alpha = 0.9f
                        scaleX = 1.02f
                        scaleY = 1.02f
                    }
                } else Modifier
            ),
        elevation = if (isDragging) CardDefaults.cardElevation(defaultElevation = 12.dp) else CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Reorder",
                modifier = Modifier.padding(end = 12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(text = server.url, style = MaterialTheme.typography.bodyLarge)
            }
            
            Switch(
                checked = server.enabled,
                onCheckedChange = {
                    if (repo.isHapticEnabled()) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    onToggle(it)
                }
            )
            
            IconButton(onClick = {
                if (repo.isHapticEnabled()) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                }
                onDelete()
            }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
fun AboutSettingsTab(repo: SettingsRepository) {
    var creatorProfile by remember { mutableStateOf<UserProfile?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showDonateDialog by remember { mutableStateOf(false) }
    
    val creatorNpub = "npub1m64hnkh6rs47fd9x6wk2zdtmdj4qkazt734d22d94ery9zzhne5qw9uaks"
    val creatorHex = creatorNpub.npubToHex()
    val githubUrl = "https://github.com/hardran3/Prism"
    
    val uriHandler = LocalUriHandler.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        val rm = RelayManager(NostrShareApp.getInstance().client, repo)
        creatorProfile = rm.fetchUserProfile(creatorHex)
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Identity Section
        Spacer(modifier = Modifier.height(16.dp))
        Image(
            painter = painterResource(id = R.drawable.ic_prism),
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Version 1.0.3",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(48.dp))

        // Creator Info Section (Horizontal Card)
        Text(
            text = "Created By",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.Start),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading) {
                    Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else {
                    AsyncImage(
                        model = creatorProfile?.pictureUrl ?: "https://robohash.org/$creatorHex?set=set4",
                        contentDescription = "Creator Avatar",
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column {
                    Text(
                        text = creatorProfile?.name ?: "ryan",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Developing open source Nostr tools.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Actions Section
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (repo.isHapticEnabled()) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    showDonateDialog = true
                },
                modifier = Modifier.weight(1f),
                enabled = !isLoading && !creatorProfile?.lud16.isNullOrBlank()
            ) {
                Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Donate")
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            OutlinedButton(
                onClick = {
                    if (repo.isHapticEnabled()) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    uriHandler.openUri(githubUrl)
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("GitHub")
            }
        }
    }

    if (showDonateDialog) {
        val lud16 = creatorProfile?.lud16 ?: ""
        DonateDialog(
            lnAddress = lud16,
            onDismiss = { showDonateDialog = false },
            onSend = { amountSats ->
                if (repo.isHapticEnabled()) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                }
                val msats = amountSats * 1000L
                uriHandler.openUri("lightning:$lud16?amount=$msats")
                showDonateDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonateDialog(
    lnAddress: String,
    onDismiss: () -> Unit,
    onSend: (Long) -> Unit
) {
    var amountText by remember { mutableStateOf("5000") }
    val presets = listOf(1000L, 5000L, 10000L, 21000L)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Donate via Lightning") },
        text = {
            Column {
                Text(
                    text = "Sending to $lnAddress",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { if (it.all { char -> char.isDigit() }) amountText = it },
                    label = { Text("Amount (Sats)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { amount ->
                        FilterChip(
                            selected = amountText == amount.toString(),
                            onClick = { amountText = amount.toString() },
                            label = { Text(amount.toString()) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = amountText.toLongOrNull() ?: 0L
                    if (amount > 0) onSend(amount)
                }
            ) {
                Text("Send Satoshis")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AddServerDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("https://") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Blossom Server") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Server URL") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { onAdd(text) }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
