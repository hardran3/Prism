package com.ryans.nostrshare.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import coil.compose.AsyncImage
import com.ryans.nostrshare.ProcessTextViewModel
import com.ryans.nostrshare.data.Draft
import com.ryans.nostrshare.NostrUtils
import com.ryans.nostrshare.toUiModel
import com.ryans.nostrshare.MediaUploadState
import com.ryans.nostrshare.MediaDetailDialog
import com.ryans.nostrshare.HistoryUiModel
import com.ryans.nostrshare.utils.LinkMetadata
import com.ryans.nostrshare.utils.LinkPreviewManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DraftsDialog(
    vm: ProcessTextViewModel,
    onDismiss: () -> Unit,
    onEditAndReschedule: (Draft) -> Unit,
    onSaveToDrafts: (Draft) -> Unit,
    onOpenInClient: (HistoryUiModel) -> Unit,
    onRepost: (Draft) -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                IconButton(onClick = onDismiss, modifier = Modifier.padding(8.dp)) {
                    Icon(Icons.Default.Close, "Close")
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                DraftsHistoryContent(
                    vm = vm,
                    onEditDraft = {
                        vm.loadDraft(it)
                        onDismiss()
                    },
                    onEditAndReschedule = onEditAndReschedule,
                    onSaveToDrafts = onSaveToDrafts,
                    onOpenInClient = onOpenInClient,
                    onRepost = onRepost
                )
            }
        }
    }
}

@Composable
fun DraftsHistoryContent(
    vm: ProcessTextViewModel,
    initialTab: Int = 0,
    onEditDraft: (Draft) -> Unit,
    onEditAndReschedule: (Draft) -> Unit,
    onSaveToDrafts: (Draft) -> Unit,
    onOpenInClient: (HistoryUiModel) -> Unit,
    onRepost: (Draft) -> Unit
) {
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }
    val drafts by vm.uiDrafts.collectAsState()
    val scheduledCount by vm.uiScheduled.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.fillMaxWidth(),
            divider = {}
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Drafts (${drafts.size})", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
            if (vm.isSchedulingEnabled) {
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Scheduled (${scheduledCount.size})", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("History", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }

        DraftsHistoryList(
            vm = vm,
            selectedTab = selectedTab,
            onEditDraft = onEditDraft,
            onEditAndReschedule = onEditAndReschedule,
            onSaveToDrafts = onSaveToDrafts,
            onOpenInClient = onOpenInClient,
            onRepost = onRepost
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DraftsHistoryList(
    vm: ProcessTextViewModel,
    selectedTab: Int,
    onEditDraft: (Draft) -> Unit,
    onEditAndReschedule: (Draft) -> Unit,
    onSaveToDrafts: (Draft) -> Unit,
    onOpenInClient: (HistoryUiModel) -> Unit,
    onRepost: (Draft) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val drafts by vm.uiDrafts.collectAsState()
    val scheduled by vm.uiScheduled.collectAsState()
    val history by vm.uiHistory.collectAsState()
    val isSyncing by com.ryans.nostrshare.utils.HistorySyncManager.isSyncing.collectAsState()
    
    var showMediaDetail by remember { mutableStateOf<MediaUploadState?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    
    val activeFilters = vm.activeHistoryFilters
    
    LaunchedEffect(selectedTab) {
        if (selectedTab == 2) {
            vm.fetchRemoteHistory()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            when (selectedTab) {
                0 -> UnifiedList(
                    items = drafts.map { it.toUiModel(isRemote = false) }, 
                    isFiltered = activeFilters.isNotEmpty() || vm.searchQuery.isNotBlank() || vm.activeHashtags.isNotEmpty(),
                    vm = vm,
                    onMediaClick = { showMediaDetail = it },
                    actions = { note ->
                        val draft = drafts.find { it.id == note.localId }
                        if (draft != null) {
                            TextButton(onClick = { onEditDraft(draft) }) {
                                Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Edit")
                            }
                        }
                    }
                )
                1 -> UnifiedList(
                    items = scheduled.map { it.toUiModel(isRemote = false) }, 
                    isFiltered = activeFilters.isNotEmpty() || vm.searchQuery.isNotBlank() || vm.activeHashtags.isNotEmpty(),
                    vm = vm, 
                    onMediaClick = { showMediaDetail = it },
                    actions = { note ->
                        val draft = scheduled.find { it.id == note.localId }
                        if (draft != null) {
                            Row {
                                TextButton(onClick = { onSaveToDrafts(draft) }) {
                                    Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("To Drafts")
                                }
                                TextButton(onClick = { onEditAndReschedule(draft) }) {
                                    Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Edit")
                                }
                            }
                        }
                    }
                )
                2 -> HistoryList(
                    history = history,
                    vm = vm,
                    isFetching = isSyncing,
                    onClearHistory = { vm.clearScheduledHistory() },
                    onOpenInClient = onOpenInClient,
                    onMediaClick = { showMediaDetail = it },
                    onRepost = { uiModel ->
                        val draft = Draft(
                            id = uiModel.localId,
                            content = "",
                            sourceUrl = uiModel.sourceUrl ?: "",
                            kind = uiModel.kind,
                            mediaJson = uiModel.mediaJson ?: "[]",
                            mediaTitle = "",
                            originalEventJson = uiModel.originalEventJson,
                            pubkey = uiModel.pubkey,
                            articleTitle = uiModel.articleTitle,
                            articleSummary = uiModel.articleSummary,
                            articleIdentifier = uiModel.articleIdentifier
                        )
                        onRepost(draft)
                    },
                    onLoadMore = { vm.loadMoreRemoteHistory() }
                )
            }
        }

        if (showFilters) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.1f)).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { showFilters = false })
        }
        
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 16.dp, start = 16.dp), horizontalAlignment = Alignment.Start) {
            androidx.compose.animation.AnimatedVisibility(visible = showFilters, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Surface(modifier = Modifier.padding(bottom = 12.dp).width(260.dp).heightIn(max = 750.dp).clickable(enabled = true, onClick = {}, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null), shape = MaterialTheme.shapes.medium, tonalElevation = 8.dp, shadowElevation = 6.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))) {
                    Column(Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Full History", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Switch(checked = vm.isFullHistoryEnabled, onCheckedChange = { vm.toggleFullHistory() }, modifier = Modifier.scale(0.8f))
                        }
                        OutlinedTextField(
                            value = vm.searchQuery, 
                            onValueChange = { vm.searchQuery = it }, 
                            modifier = Modifier.fillMaxWidth(), 
                            placeholder = { Text("Search content...", style = MaterialTheme.typography.bodySmall) }, 
                            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) }, 
                            trailingIcon = if (vm.searchQuery.isNotEmpty()) { { IconButton(onClick = { vm.searchQuery = "" }) { Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp)) } } } else null, 
                            singleLine = true, 
                            textStyle = MaterialTheme.typography.bodySmall, 
                            shape = RoundedCornerShape(8.dp),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                        )
                        Spacer(Modifier.height(12.dp))
                        
                        var showPostTypes by remember { mutableStateOf(false) }
                        var showMediaFilters by remember { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth().clickable { showPostTypes = !showPostTypes }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Post Types", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold); Icon(imageVector = if (showPostTypes) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                            androidx.compose.animation.AnimatedVisibility(visible = showPostTypes) {
                                FlowRow(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                                    val types = listOf("Note" to ProcessTextViewModel.HistoryFilter.NOTE, "Article" to ProcessTextViewModel.HistoryFilter.ARTICLE, "Highlight" to ProcessTextViewModel.HistoryFilter.HIGHLIGHT, "Media" to ProcessTextViewModel.HistoryFilter.MEDIA, "Repost" to ProcessTextViewModel.HistoryFilter.REPOST, "Quote" to ProcessTextViewModel.HistoryFilter.QUOTE)
                                    types.forEach { (label, filter) -> val selected = activeFilters.contains(filter); FilterChip(selected = selected, onClick = { vm.toggleHistoryFilter(filter) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) }, leadingIcon = if (selected) { { Icon(Icons.Default.Check, null, Modifier.size(12.dp)) } } else null, shape = RoundedCornerShape(16.dp)) }
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Column(Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth().clickable { showMediaFilters = !showMediaFilters }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Media Filters", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold); Icon(imageVector = if (showMediaFilters) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                            androidx.compose.animation.AnimatedVisibility(visible = showMediaFilters) {
                                FlowRow(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                                    val mediaFilters = listOf("Has Media" to ProcessTextViewModel.HistoryFilter.HAS_MEDIA, "Images" to ProcessTextViewModel.HistoryFilter.IMAGE, "GIFs" to ProcessTextViewModel.HistoryFilter.GIF, "Videos" to ProcessTextViewModel.HistoryFilter.VIDEO)
                                    mediaFilters.forEach { (label, filter) -> val selected = activeFilters.contains(filter); FilterChip(selected = selected, onClick = { vm.toggleHistoryFilter(filter) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) }, leadingIcon = if (selected) { { Icon(Icons.Default.Check, null, Modifier.size(12.dp)) } } else null, shape = RoundedCornerShape(16.dp)) }
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        val topTags by vm.topHashtags.collectAsState()
                        if (topTags.isNotEmpty()) {
                            var showHashtags by remember { mutableStateOf(false) }
                            Column(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                                Row(modifier = Modifier.fillMaxWidth().clickable { showHashtags = !showHashtags }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Hashtags", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp)); IconButton(onClick = { vm.isHashtagManageMode = !vm.isHashtagManageMode }, modifier = Modifier.size(24.dp)) { Icon(imageVector = if (vm.isHashtagManageMode) Icons.Default.Check else Icons.Default.Edit, contentDescription = "Manage", modifier = Modifier.size(16.dp), tint = if (vm.isHashtagManageMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                    Icon(imageVector = if (showHashtags) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                                androidx.compose.animation.AnimatedVisibility(visible = showHashtags, modifier = Modifier.weight(1f, fill = false)) {
                                    val hiddenTags by vm.hiddenHashtags.collectAsState()
                                    Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                        FlowRow(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            topTags.forEach { (tag, _) -> val isHidden = hiddenTags.contains(tag); val selected = vm.activeHashtags.contains(tag); if (vm.isHashtagManageMode || !isHidden) { FilterChip(selected = if (vm.isHashtagManageMode) isHidden else selected, onClick = { if (vm.isHashtagManageMode) vm.toggleHashtagHidden(tag) else vm.toggleHashtag(tag) }, label = { Text(tag, style = MaterialTheme.typography.labelSmall) }, leadingIcon = { if (vm.isHashtagManageMode && isHidden) Icon(Icons.Default.Close, null, Modifier.size(12.dp)) else if (!vm.isHashtagManageMode && selected) Icon(Icons.Default.Check, null, Modifier.size(10.dp)) }, shape = RoundedCornerShape(12.dp), colors = if (vm.isHashtagManageMode && isHidden) FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f), selectedLabelColor = MaterialTheme.colorScheme.error) else FilterChipDefaults.filterChipColors()) } }
                                            if (hiddenTags.isNotEmpty()) { IconButton(onClick = { vm.resetHiddenHashtags() }, modifier = Modifier.size(32.dp).align(Alignment.CenterVertically)) { Icon(Icons.Default.Refresh, "Reset hidden tags", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) } }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                        if (activeFilters.isNotEmpty() || vm.activeHashtags.isNotEmpty()) { TextButton(onClick = { vm.activeHistoryFilters.clear(); vm.activeHashtags.clear() }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) { Text("Clear All Filters", style = MaterialTheme.typography.labelMedium) } }
                    }
                }
            }
            FloatingActionButton(onClick = { showFilters = !showFilters }, containerColor = if (activeFilters.isNotEmpty() || vm.searchQuery.isNotEmpty() || vm.activeHashtags.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) { Icon(imageVector = if (showFilters) Icons.Default.Close else Icons.Default.FilterList, contentDescription = "Filters", modifier = Modifier.size(24.dp)) }
        }

        showMediaDetail?.let { item -> MediaDetailDialog(item = item, vm = vm, onDismiss = { showMediaDetail = null }) }
    }
}

@Composable
fun UnifiedList(items: List<HistoryUiModel>, isFiltered: Boolean, vm: ProcessTextViewModel, onMediaClick: (MediaUploadState) -> Unit, actions: @Composable (HistoryUiModel) -> Unit) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.Center) {
            val message = if (isFiltered) "No items match your filters" else "No items found"
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { item ->
                UnifiedPostItem(note = item, vm = vm, onMediaClick = onMediaClick, actions = { actions(item) })
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun HistoryList(history: List<HistoryUiModel>, vm: ProcessTextViewModel, isFetching: Boolean, onClearHistory: () -> Unit, onOpenInClient: (HistoryUiModel) -> Unit, onMediaClick: (MediaUploadState) -> Unit, onRepost: (HistoryUiModel) -> Unit, onLoadMore: () -> Unit) {
    val listState = remember(vm.pubkey) { LazyListState() }
    val activeSyncPubkey by com.ryans.nostrshare.utils.HistorySyncManager.activePubkey.collectAsState()
    val discoveryCount by com.ryans.nostrshare.utils.HistorySyncManager.discoveryCount.collectAsState()
    val currentRelay by com.ryans.nostrshare.utils.HistorySyncManager.currentRelay.collectAsState()

    LaunchedEffect(listState, history.size, isFetching) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { lastIndex ->
            if (lastIndex != null && lastIndex >= history.size - 5 && !isFetching && vm.isFullHistoryEnabled && !vm.hasReachedEndOfRemoteHistory) {
                onLoadMore()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (isFetching && activeSyncPubkey == vm.pubkey) {
            Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Syncing History...", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text("$discoveryCount notes found", style = MaterialTheme.typography.labelMedium)
                    }
                    if (currentRelay != null) { Text("Relay: $currentRelay", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Spacer(Modifier.height(8.dp)); LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
        }
        
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (history.isEmpty() && !isFetching) {
                    item {
                        Box(Modifier.fillParentMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.Center) {
                            val msg = if (vm.activeHistoryFilters.isNotEmpty() || vm.searchQuery.isNotBlank()) "No matches" else if (!vm.isFullHistoryEnabled) "Enable Full History" else "No history"
                            Text(msg, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(history, key = { it.id }) { note ->
                    UnifiedPostItem(note = note, vm = vm, onMediaClick = onMediaClick, actions = {
                        if (note.isRemote && note.isSuccess) {
                            TextButton(onClick = { onRepost(note) }) { Icon(Icons.Default.Schedule, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Repost") }
                            TextButton(onClick = { onOpenInClient(note) }) { Icon(Icons.Default.Link, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Open") }
                        }
                    })
                }
                if (isFetching && activeSyncPubkey == vm.pubkey && history.isNotEmpty()) {
                    item { Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) } }
                } else if (vm.hasReachedEndOfRemoteHistory && history.isNotEmpty()) {
                    item { Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { Text("End of history", style = MaterialTheme.typography.bodySmall) } }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
            com.ryans.nostrshare.ui.FastScrollIndicator(lazyListState = listState, history = history, vm = vm, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
fun UnifiedPostItem(note: HistoryUiModel, vm: ProcessTextViewModel, onMediaClick: (MediaUploadState) -> Unit, actions: @Composable () -> Unit) {
    val date = remember(note.timestamp) { NostrUtils.formatDate(note.timestamp) }
    val profile = note.pubkey?.let { vm.usernameCache[it] }
    var isExpanded by remember { mutableStateOf(false) }
    
    LaunchedEffect(note.pubkey) {
        note.pubkey?.let { if (vm.usernameCache[it]?.name == null) vm.resolveUsername(it) }
    }

    val containerColor = when {
        !note.isRemote && note.isSuccess -> if (isSystemInDarkTheme()) Color(0xFF1B5E20).copy(alpha = 0.4f) else Color(0xFFE8F5E9)
        note.isRemote -> MaterialTheme.colorScheme.surfaceVariant
        note.isCompleted -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    }

    val statusColor = when {
        !note.isRemote && note.isSuccess -> if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF4CAF50)
        note.isRemote -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        note.isCompleted -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).animateContentSize(), colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(modifier = Modifier.size(40.dp).padding(top = 4.dp)) {
                    UserAvatar(pictureUrl = profile?.pictureUrl, size = 40.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (note.isOfflineRetry && !note.isCompleted) { Icon(Icons.Default.WifiOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(4.dp)) }
                            val icon = when {
                                !note.isCompleted -> if (note.isScheduled) Icons.Default.Schedule else Icons.Default.Edit
                                !note.isSuccess -> Icons.Default.Warning
                                !note.isRemote -> Icons.Default.Check
                                else -> null
                            }
                            if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint = statusColor); Spacer(Modifier.width(4.dp)) }
                            Text(date, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(NostrUtils.getKindLabel(note.kind, note.contentSnippet, note.isQuote), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(profile?.name ?: note.pubkey?.take(8) ?: "Draft", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    var trueContentHeightPx by remember { mutableIntStateOf(0) }
                    val trueContentHeightDp = with(density) { trueContentHeightPx.toDp() }
                    
                    // Stabilize truncation state: once it's true, it stays true unless the content fundamentally changes
                    val isTruncated = remember(trueContentHeightPx) { trueContentHeightDp > 360.dp }

                    Box(modifier = Modifier.fillMaxWidth().heightIn(max = if (isExpanded || !isTruncated) androidx.compose.ui.unit.Dp.Unspecified else 300.dp)) {
                        // Use onSizeChanged for better stability, and only measure when NOT clamped if possible
                        Column(modifier = Modifier.onSizeChanged { size -> 
                            // Only update if the measured height is larger than our current "true" height
                            // or if we are expanded (meaning we are seeing the full size)
                            if (isExpanded || size.height > trueContentHeightPx || !isTruncated) {
                                trueContentHeightPx = size.height
                            }
                        }) {
                            if (note.kind == 30023) {
                                if (note.previewImageUrl != null) {
                                    AsyncImage(model = note.previewImageUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
                                    Spacer(Modifier.height(8.dp))
                                }
                                Text(note.articleTitle ?: "Untitled", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                                if (!note.articleSummary.isNullOrBlank()) {
                                    Text(note.articleSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                } else {
                                    Text(note.contentSnippet, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            } else {
                                val contentToRender = remember(note.kind, note.contentSnippet) { 
                                    if (note.kind == 6 || note.kind == 16) "" else note.contentSnippet 
                                }
                                
                                var linkMeta: com.ryans.nostrshare.utils.LinkMetadata? = null
                                if (!note.previewTitle.isNullOrBlank() || !note.previewImageUrl.isNullOrBlank()) {
                                    linkMeta = com.ryans.nostrshare.utils.LinkMetadata(url = note.sourceUrl ?: "", title = note.previewTitle, description = note.previewDescription, imageUrl = note.previewImageUrl, siteName = note.previewSiteName)
                                }
                                
                                IntegratedContent(
                                    content = contentToRender, 
                                    vm = vm, 
                                    onMediaClick = onMediaClick, 
                                    mediaItems = parseMediaJson(note.mediaJson), 
                                    linkMetadata = linkMeta, 
                                    nostrEvent = note.nostrEvent, 
                                    targetLink = note.targetLink,
                                    isHighlight = note.kind == 9802,
                                    sourceUrl = note.sourceUrl,
                                    kind = note.kind
                                )
                            }
                        }
                        
                        // Fading edge overlay when collapsed AND truncated
                        if (!isExpanded && isTruncated) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        androidx.compose.ui.graphics.Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, containerColor)
                                        )
                                    )
                            )
                        }
                    }

                    if (isTruncated || isExpanded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { isExpanded = !isExpanded },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                            ) {
                                Text(if (isExpanded) "Show Less" else "Show More", style = MaterialTheme.typography.labelSmall)
                            }
                            
                            if (note.isCompleted && note.publishError != null) {
                                Text("Error: ${note.publishError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else if (note.isCompleted && note.publishError != null) {
                        Text("Error: ${note.publishError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 0.dp), horizontalArrangement = Arrangement.End) { actions() }
        }
    }
}

fun parseMediaJson(json: String?): List<MediaUploadState> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(json); val items = mutableListOf<MediaUploadState>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i); val uriStr = obj.optString("uri").takeIf { it.isNotBlank() } ?: continue
            items.add(MediaUploadState(id = obj.optString("id", UUID.randomUUID().toString()), uri = Uri.parse(uriStr), mimeType = obj.optString("mimeType").takeIf { it.isNotBlank() }).apply { uploadedUrl = obj.optString("uploadedUrl").takeIf { it != "null" && it.isNotBlank() }; hash = obj.optString("hash").takeIf { it != "null" && it.isNotBlank() }; size = obj.optLong("size", 0L) })
        }
        items
    } catch (_: Exception) { emptyList() }
}

@Composable
fun IntegratedContent(
    content: String, 
    vm: ProcessTextViewModel, 
    onMediaClick: (MediaUploadState) -> Unit, 
    mediaItems: List<MediaUploadState>, 
    linkMetadata: com.ryans.nostrshare.utils.LinkMetadata? = null, 
    nostrEvent: JSONObject? = null,
    targetLink: String? = null, // New parameter from model
    isHighlight: Boolean = false,
    sourceUrl: String? = null,
    kind: Int = 1, // New parameter
    isNested: Boolean = false // New parameter for compact rendering
) {
    val segments = remember(content, mediaItems, linkMetadata, nostrEvent, targetLink, vm.usernameCache.size, sourceUrl) {
        val urlRegex = "(https?://[^\\s]+)".toRegex(RegexOption.IGNORE_CASE)
        val mediaUrlPattern = "(https?://[^\\s]+(?:\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp|\\.bmp|\\.svg|\\.mp4|\\.mov|\\.webm)(?:\\?[^\\s]*)?)".toRegex(RegexOption.IGNORE_CASE)
        val nostrRegex = "(nostr:)?(nevent1|note1|naddr1|npub1|nprofile1)[a-z0-9]+".toRegex(RegexOption.IGNORE_CASE)
        val eventLinkRegex = "(nostr:)?(nevent1|note1|naddr1)[a-z0-9]+".toRegex(RegexOption.IGNORE_CASE)

        val allUrlMatches = urlRegex.findAll(content).toList()
        val mediaMatches = allUrlMatches.filter { match ->
            mediaUrlPattern.matches(match.value) || 
            mediaItems.any { it.uploadedUrl?.equals(match.value, ignoreCase = true) == true }
        }
        
        val ogUrl = linkMetadata?.url
        val ogMatch = if (ogUrl != null) {
            allUrlMatches.find { match -> 
                NostrUtils.urlsMatch(match.value, ogUrl) || (linkMetadata.title != null && match.value.contains(ogUrl.removePrefix("https://").removePrefix("http://").removeSuffix("/")))
            }
        } else null

        val nostrMatch = if (nostrEvent != null) {
            nostrRegex.findAll(content).find { match ->
                val bech32 = if (match.value.startsWith("nostr:")) match.value.substring(6) else match.value
                val entity = try { NostrUtils.findNostrEntity(bech32) } catch(_: Exception) { null }
                entity?.id == nostrEvent.optString("id")
            }
        } else null

        val eventLinkMatches = eventLinkRegex.findAll(content).filter { it.range != nostrMatch?.range }.toList()

        val localSegments = mutableListOf<com.ryans.nostrshare.ui.ContentSegment>()
        var lastIndex = 0
        val usedMediaUrls = mutableSetOf<String>()
        
        val genericUrlMatches = allUrlMatches.filter { match ->
            mediaMatches.none { it.range == match.range } &&
            ogMatch?.range != match.range
        }
        
        val allMatches = (mediaMatches + genericUrlMatches + 
                          listOfNotNull(ogMatch) + listOfNotNull(nostrMatch) + eventLinkMatches).distinctBy { it.range }.sortedBy { it.range.first }

        var i = 0
        var nostrTargetAddedInline = false
        while (i < allMatches.size) {
            val match = allMatches[i]
            if (match.range.first > lastIndex) {
                val text = content.substring(lastIndex, match.range.first)
                if (text.trim().isNotBlank()) {
                    localSegments.add(com.ryans.nostrshare.ui.ContentSegment.Text(text.trim()))
                }
            }
            
            if (match == ogMatch || genericUrlMatches.contains(match)) {
                if (!isHighlight) {
                    // Kind 1: Hide URL text and render preview card In-line (with fallback)
                    val meta = if (match == ogMatch) linkMetadata!! else com.ryans.nostrshare.utils.LinkMetadata(url = match.value)
                    localSegments.add(com.ryans.nostrshare.ui.ContentSegment.LinkPreview(meta, match.value))
                    lastIndex = match.range.last + 1
                } else {
                    // Highlight: KEEP URL text visible in the content
                    localSegments.add(com.ryans.nostrshare.ui.ContentSegment.Text(match.value))
                    lastIndex = match.range.last + 1
                }
                i++
            } else if (match == nostrMatch || eventLinkMatches.any { it.range == match.range }) {
                // Kind 1/6/16: Hide Nostr link in text and render at the end
                // Kind 30023: Render inline
                if (kind == 30023) {
                    val bech32 = if (match.value.startsWith("nostr:")) match.value.substring(6) else match.value
                    localSegments.add(com.ryans.nostrshare.ui.ContentSegment.NostrLink(bech32))
                    nostrTargetAddedInline = true
                }
                lastIndex = match.range.last + 1
                i++
            } else {
                val group = mutableListOf<String>()
                group.add(match.value)
                var nextI = i + 1
                var currentEnd = match.range.last + 1
                
                // Group consecutive media
                if (mediaMatches.any { it.range == match.range }) {
                    while (nextI < allMatches.size) {
                        val nextMatch = allMatches[nextI]
                        if (nextMatch == ogMatch || nextMatch == nostrMatch || genericUrlMatches.contains(nextMatch)) break 
                        if (genericUrlMatches.any { it.range == nextMatch.range }) break
                        val gap = content.substring(currentEnd, nextMatch.range.first)
                        if (gap.isBlank()) {
                            group.add(nextMatch.value)
                            currentEnd = nextMatch.range.last + 1
                            nextI++
                        } else break
                    }
                }
                
                val groupedItems = group.mapNotNull { url ->
                    mediaItems.find { it.uploadedUrl?.equals(url, ignoreCase = true) == true } ?: run {
                        if (mediaUrlPattern.matches(url)) {
                            val lc = url.lowercase().substringBefore("?")
                            val detectedMime = when {
                                lc.endsWith(".mp4") || lc.endsWith(".mov") || lc.endsWith(".webm") || lc.endsWith(".avi") || lc.endsWith(".mkv") -> "video/mp4"
                                lc.endsWith(".gif") -> "image/gif"
                                lc.endsWith(".png") -> "image/png"
                                lc.endsWith(".webp") -> "image/webp"
                                lc.endsWith(".svg") -> "image/svg+xml"
                                lc.endsWith(".jpg") || lc.endsWith(".jpeg") || lc.endsWith(".bmp") -> "image/jpeg"
                                else -> null
                            }
                            MediaUploadState(id = UUID.randomUUID().toString(), uri = Uri.parse(url), mimeType = detectedMime).apply { uploadedUrl = url }
                        } else null
                    }
                }
                
                if (groupedItems.isNotEmpty()) {
                    localSegments.add(com.ryans.nostrshare.ui.ContentSegment.MediaGroup(groupedItems))
                    usedMediaUrls.addAll(group.map { it.lowercase() })
                } else {
                    localSegments.add(com.ryans.nostrshare.ui.ContentSegment.Text(group.joinToString(" ").trim()))
                }
                i = nextI
                lastIndex = currentEnd
            }
        }
        
        if (lastIndex < content.length) {
            val text = content.substring(lastIndex)
            if (text.trim().isNotBlank()) {
                localSegments.add(com.ryans.nostrshare.ui.ContentSegment.Text(text.trim()))
            }
        }
        
        val leftoverMedia = mediaItems.filter { item ->
            val url = item.uploadedUrl?.lowercase()
            url == null || url !in usedMediaUrls
        }
        if (leftoverMedia.isNotEmpty()) localSegments.add(com.ryans.nostrshare.ui.ContentSegment.MediaGroup(leftoverMedia))
        
        // Final fallback checks for metadata not in content
        if (isHighlight) {
            // Highlights render previews at the end
            if (ogMatch == null && linkMetadata != null && (linkMetadata.title != null || linkMetadata.imageUrl != null)) {
                localSegments.add(com.ryans.nostrshare.ui.ContentSegment.LinkPreview(linkMetadata, linkMetadata.url))
            } else if (ogMatch == null && sourceUrl?.startsWith("http") == true) {
                localSegments.add(com.ryans.nostrshare.ui.ContentSegment.LinkPreview(com.ryans.nostrshare.utils.LinkMetadata(url = sourceUrl), sourceUrl))
            }
        } else {
            // Kind 1: Only add fallback if NO URLs were found in text at all
            if (ogMatch == null && genericUrlMatches.isEmpty() && linkMetadata != null && (linkMetadata.title != null || linkMetadata.imageUrl != null)) {
                localSegments.add(com.ryans.nostrshare.ui.ContentSegment.LinkPreview(linkMetadata, linkMetadata.url))
            }
        }

        // CRITICAL: Explicit Target Rendering for Reposts or Quotes missing from text
        if (!nostrTargetAddedInline) {
            if (nostrEvent != null) {
                localSegments.add(com.ryans.nostrshare.ui.ContentSegment.NostrPreview(nostrEvent))
            } else if (targetLink != null) {
                localSegments.add(com.ryans.nostrshare.ui.ContentSegment.NostrLink(targetLink))
            } else if (sourceUrl != null && isHighlight) {
                val bech32 = if (sourceUrl.startsWith("nostr:")) sourceUrl.substring(6) else sourceUrl
                val entity = try { NostrUtils.findNostrEntity(bech32) } catch(_: Exception) { null }
                if (entity != null && (entity.type == "nevent" || entity.type == "note" || entity.type == "naddr")) {
                    localSegments.add(com.ryans.nostrshare.ui.ContentSegment.NostrLink(entity.bech32))
                }
            }
        }
        
        localSegments
    }
    
    Column(Modifier.fillMaxWidth()) {
        if (isNested && kind == 30023) {
            // Compact Article View for reposts/quotes
            val title = nostrEvent?.optJSONArray("tags")?.let { tags ->
                (0 until tags.length()).mapNotNull { tags.optJSONArray(it) }.find { it.optString(0) == "title" }?.optString(1)
            } ?: "Untitled Article"
            val summary = nostrEvent?.optJSONArray("tags")?.let { tags ->
                (0 until tags.length()).mapNotNull { tags.optJSONArray(it) }.find { it.optString(0) == "summary" }?.optString(1)
            } ?: content.take(150)
            
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (summary.isNotBlank()) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            segments.forEach { seg ->
                when (seg) {
                    is com.ryans.nostrshare.ui.ContentSegment.Text -> {
                        if (seg.text.trim().isNotBlank()) {
                            val highlightColor = MaterialTheme.colorScheme.primary
                            val codeColor = MaterialTheme.colorScheme.secondary
                            val h1Style = MaterialTheme.typography.headlineLarge
                            val h2Style = MaterialTheme.typography.headlineMedium
                            val h3Style = MaterialTheme.typography.headlineSmall
                            
                            val styledText = remember(seg.text, vm.usernameCache.size) {
                                com.ryans.nostrshare.utils.MarkdownUtils.renderMarkdown(
                                    text = seg.text,
                                    usernameCache = vm.usernameCache,
                                    highlightColor = highlightColor,
                                    codeColor = codeColor,
                                    h1Style = h1Style,
                                    h2Style = h2Style,
                                    h3Style = h3Style,
                                    stripDelimiters = true
                                )
                            }
                            
                            if (isHighlight) {
                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 4.dp)) {
                                    Box(Modifier.width(2.dp).fillMaxHeight().background(LocalContentColor.current, RoundedCornerShape(1.dp)))
                                    Spacer(Modifier.width(12.dp))
                                    Text(styledText, style = MaterialTheme.typography.bodyLarge)
                                }
                            } else {
                                Text(styledText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                    is com.ryans.nostrshare.ui.ContentSegment.MediaGroup -> { ResponsiveMediaGrid(seg.items, onMediaClick); Spacer(Modifier.height(8.dp)) }
                    is com.ryans.nostrshare.ui.ContentSegment.LinkPreview -> { DynamicLinkPreview(seg.meta.url, seg.meta, seg.originalText); Spacer(Modifier.height(8.dp)) }
                    is com.ryans.nostrshare.ui.ContentSegment.NostrPreview -> { NostrEventPreview(seg.event, vm, onMediaClick); Spacer(Modifier.height(8.dp)) }
                    is com.ryans.nostrshare.ui.ContentSegment.NostrLink -> { NostrLinkPreview(seg.bech32, vm, onMediaClick); Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
fun DynamicLinkPreview(url: String, initialMeta: com.ryans.nostrshare.utils.LinkMetadata? = null, originalText: String? = null) {
    var meta by remember(url) { mutableStateOf(initialMeta) }
    var isLoading by remember(url) { mutableStateOf(initialMeta == null || (initialMeta.title == null && initialMeta.imageUrl == null)) }

    LaunchedEffect(url) {
        if (isLoading) {
            val fetched = LinkPreviewManager.fetchMetadata(url)
            if (fetched != null) {
                meta = fetched
            }
            isLoading = false
        }
    }

    if (isLoading) {
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(8.dp)) {
            Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    } else {
        meta?.let { m ->
            if (m.title != null || m.imageUrl != null) {
                LinkPreviewCard(m)
            } else if (!originalText.isNullOrBlank()) {
                Text(originalText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
            }
        } ?: run {
            if (!originalText.isNullOrBlank()) {
                Text(originalText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
fun NostrLinkPreview(bech32: String, vm: ProcessTextViewModel, onMediaClick: (MediaUploadState) -> Unit) {
    var resolvedEvent by remember(bech32) { mutableStateOf<JSONObject?>(null) }
    var isLoading by remember(bech32) { mutableStateOf(false) }

    LaunchedEffect(bech32) {
        val entity = try { NostrUtils.findNostrEntity(bech32) } catch (_: Exception) { null }
        if (entity != null) {
            isLoading = true
            val event = if (entity.type == "naddr") {
                vm.relayManager.fetchAddress(entity.kind!!, entity.author!!, entity.id, entity.relays)
            } else {
                vm.relayManager.fetchEvent(entity.id, entity.relays)
            }
            resolvedEvent = event
            isLoading = false
        }
    }

    if (isLoading) {
        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
    } else {
        resolvedEvent?.let { event ->
            NostrEventPreview(event, vm, onMediaClick)
        } ?: run {
            Text("nostr:$bech32", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun NostrEventPreview(event: JSONObject, vm: ProcessTextViewModel, onMediaClick: (MediaUploadState) -> Unit) {
    val pubkey = remember(event) { event.optString("pubkey") }
    val content = remember(event) { event.optString("content") }
    val kind = remember(event) { event.optInt("kind", 1) }
    val profile = vm.usernameCache[pubkey]
    
    LaunchedEffect(pubkey) {
        if (vm.usernameCache[pubkey]?.name == null) vm.resolveUsername(pubkey)
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), 
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)), 
        shape = RoundedCornerShape(8.dp), 
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(pictureUrl = profile?.pictureUrl, size = 24.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(text = profile?.name ?: pubkey.take(8), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Text(NostrUtils.getKindLabel(kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(8.dp))
            
            // Recursive use of IntegratedContent ensures media/links in quotes work consistently
            IntegratedContent(
                content = content,
                vm = vm,
                onMediaClick = onMediaClick,
                mediaItems = parseMediaJsonFromTags(event.optJSONArray("tags")),
                kind = kind
            )
        }
    }
}

fun parseMediaJsonFromTags(tags: JSONArray?): List<MediaUploadState> {
    if (tags == null) return emptyList()
    val items = mutableListOf<MediaUploadState>()
    for (i in 0 until tags.length()) {
        val tag = tags.optJSONArray(i) ?: continue
        if (tag.length() >= 2 && (tag.getString(0) == "url" || tag.getString(0) == "image" || tag.getString(0) == "thumb")) {
            val url = tag.getString(1)
            items.add(MediaUploadState(id = UUID.randomUUID().toString(), uri = Uri.parse(url)).apply { uploadedUrl = url })
        }
    }
    return items
}

@Composable
fun LinkPreviewCard(meta: com.ryans.nostrshare.utils.LinkMetadata) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(8.dp)) {
        Column {
            if (meta.imageUrl != null) AsyncImage(model = meta.imageUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(1.91f), contentScale = ContentScale.Crop)
            Column(Modifier.padding(8.dp)) {
                meta.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1) }
                meta.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
        }
    }
}

@Composable
fun ResponsiveMediaGrid(mediaItems: List<MediaUploadState>, onMediaClick: (MediaUploadState) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) {
        when (mediaItems.size) {
            1 -> MediaGridItem(mediaItems[0], Modifier.fillMaxWidth().aspectRatio(16f/9f), onMediaClick)
            2 -> Row(Modifier.fillMaxWidth().aspectRatio(2f/1f)) { MediaGridItem(mediaItems[0], Modifier.weight(1f).fillMaxHeight(), onMediaClick); Spacer(Modifier.width(2.dp)); MediaGridItem(mediaItems[1], Modifier.weight(1f).fillMaxHeight(), onMediaClick) }
            else -> Row(Modifier.fillMaxWidth().aspectRatio(2f/1f)) { MediaGridItem(mediaItems[0], Modifier.weight(1f).fillMaxHeight(), onMediaClick); Spacer(Modifier.width(2.dp)); Box(Modifier.weight(1f).fillMaxHeight()) { MediaGridItem(mediaItems[1], Modifier.fillMaxSize(), onMediaClick); if (mediaItems.size > 2) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) { Text("+${mediaItems.size - 1}", color = Color.White, style = MaterialTheme.typography.titleLarge) } } }
        }
    }
}

@Composable
fun MediaGridItem(item: MediaUploadState, modifier: Modifier, onMediaClick: (MediaUploadState) -> Unit) {
    val model = remember(item.uploadedUrl, item.uri) { item.uploadedUrl ?: item.uri }
    val isVideo = remember(item.mimeType, item.uploadedUrl, item.uri) {
        item.mimeType?.startsWith("video/") == true || run {
            val url = (item.uploadedUrl ?: item.uri?.toString())?.lowercase()?.substringBefore("?") ?: ""
            url.endsWith(".mp4") || url.endsWith(".mov") || url.endsWith(".webm") || url.endsWith(".avi") || url.endsWith(".mkv")
        }
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant).clickable { onMediaClick(item) }) {
        if (isVideo) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        else AsyncImage(model = model, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

