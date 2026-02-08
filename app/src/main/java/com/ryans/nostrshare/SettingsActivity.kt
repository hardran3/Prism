package com.ryans.nostrshare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository(this)
        
        setContent {
            NostrShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        repo = repo,
                        onBack = { finish() }
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
    onBack: () -> Unit
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("General", "Blossom", "About")

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
        floatingActionButton = {
            if (selectedTabIndex == 1) {
                // Add Server FAB logic could go here, or handled within the tab content
            }
        }
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
                1 -> BlossomSettingsTab(repo)
                2 -> AboutSettingsTab(repo)
            }
        }
    }
}

@Composable
fun GeneralSettingsTab(repo: SettingsRepository) {
    var alwaysKind1 by remember { mutableStateOf(repo.isAlwaysUseKind1()) }
    var optimizeMedia by remember { mutableStateOf(repo.isOptimizeMediaEnabled()) }
    var blastrEnabled by remember { mutableStateOf(repo.isBlastrEnabled()) }
    var hapticEnabled by remember { mutableStateOf(repo.isHapticEnabled()) }
    
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
        
        Divider(modifier = Modifier.padding(vertical = 16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Compress Images", style = MaterialTheme.typography.bodyLarge)
                Text("Resize before uploading.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = optimizeMedia,
                onCheckedChange = { 
                    if (repo.isHapticEnabled()) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    optimizeMedia = it
                    repo.setOptimizeMediaEnabled(it)
                }
            )
        }
        
        Divider(modifier = Modifier.padding(vertical = 16.dp))
        
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

        Divider(modifier = Modifier.padding(vertical = 16.dp))

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
    }
}

@Composable
fun BlossomSettingsTab(repo: SettingsRepository) {
    var servers by remember { mutableStateOf(repo.getBlossomServers()) }
    var draggingItemIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var showAddDialog by remember { mutableStateOf(false) }
    
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        // Sync Button Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = {
                    if (repo.isHapticEnabled()) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    coroutineScope.launch {
                        val rm = RelayManager(NostrShareApp.getInstance().client, repo)
                        val prefs = NostrShareApp.getInstance().getSharedPreferences("nostr_share_prefs", android.content.Context.MODE_PRIVATE)
                        val pk = prefs.getString("pubkey", null)
                        if (pk != null) {
                            val discovered = rm.fetchBlossomServerList(pk)
                            val current = repo.getBlossomServers()
                            val existing = current.map { it.url }.toSet()
                            val updated = current.toMutableList()
                            discovered.forEach { url ->
                                val clean = url.trim().removeSuffix("/")
                                if (!existing.contains(clean) && !existing.contains("$clean/")) {
                                    updated.add(BlossomServer(clean, true))
                                }
                            }
                            repo.setBlossomServers(updated)
                            servers = updated
                        }
                    }
                }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sync from Nostr")
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp),
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
                                    
                                    val threshold = 50f
                                    val currentIdx = currentIndex
                                    if (dragOffset > threshold && currentIdx < servers.size - 1) {
                                        val newServers = servers.toMutableList()
                                        val item = newServers.removeAt(currentIdx)
                                        newServers.add(currentIdx + 1, item)
                                        servers = newServers
                                        repo.setBlossomServers(newServers)
                                        draggingItemIndex = currentIdx + 1
                                        dragOffset = 0f
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
                                        dragOffset = 0f
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
        }
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
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    repo: SettingsRepository
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isDragging) Modifier.graphicsLayer(alpha = 0.8f, scaleX = 1.02f, scaleY = 1.02f) else Modifier),
        elevation = if (isDragging) CardDefaults.cardElevation(defaultElevation = 8.dp) else CardDefaults.cardElevation(defaultElevation = 2.dp)
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
        Icon(
            painter = painterResource(id = R.drawable.ic_prism),
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Version 1.0.2",
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
