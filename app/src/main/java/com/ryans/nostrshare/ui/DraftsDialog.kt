package com.ryans.nostrshare.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.PlayArrow
import coil.request.videoFrameMillis
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import android.net.Uri
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.ryans.nostrshare.ProcessTextViewModel
import com.ryans.nostrshare.data.Draft
import com.ryans.nostrshare.NostrUtils
import com.ryans.nostrshare.MediaUploadState
import com.ryans.nostrshare.MediaDetailDialog
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DraftsDialog(
    vm: ProcessTextViewModel,
    onDismiss: () -> Unit,
    onEditAndReschedule: (Draft) -> Unit,
    onSaveToDrafts: (Draft) -> Unit,
    onOpenInClient: (String) -> Unit,
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
    onOpenInClient: (String) -> Unit,
    onRepost: (Draft) -> Unit
) {
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }
    val drafts by vm.drafts.collectAsState(initial = emptyList<Draft>())
    val allScheduled by vm.allScheduled.collectAsState(initial = emptyList<Draft>())
    val scheduledHistory by vm.scheduledHistory.collectAsState(initial = emptyList<Draft>())

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
                    text = { Text("Scheduled (${allScheduled.size})", maxLines = 1, overflow = TextOverflow.Ellipsis) }
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

@Composable
fun DraftsHistoryList(
    vm: ProcessTextViewModel,
    selectedTab: Int,
    onEditDraft: (Draft) -> Unit,
    onEditAndReschedule: (Draft) -> Unit,
    onSaveToDrafts: (Draft) -> Unit,
    onOpenInClient: (String) -> Unit,
    onRepost: (Draft) -> Unit
) {
    val drafts by vm.drafts.collectAsState(initial = emptyList<Draft>())
    val allScheduled by vm.allScheduled.collectAsState(initial = emptyList<Draft>())
    val scheduledHistory by vm.scheduledHistory.collectAsState(initial = emptyList<Draft>())
    val remoteHistory by vm.remoteHistory.collectAsState(initial = emptyList<Draft>())
    val isFetchingRemoteHistory = vm.isFetchingRemoteHistory
    var showMediaDetail by remember { mutableStateOf<MediaUploadState?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    
    val activeFilters = vm.activeHistoryFilters
    
    val filterPredicate: (Draft) -> Boolean = { draft ->
        if (activeFilters.isEmpty()) true
        else {
            activeFilters.any { filter ->
                when (filter) {
                    ProcessTextViewModel.HistoryFilter.NOTE -> draft.kind == 1 && !draft.isQuote
                    ProcessTextViewModel.HistoryFilter.HIGHLIGHT -> draft.kind == 9802
                    ProcessTextViewModel.HistoryFilter.MEDIA -> draft.kind == 20 || draft.kind == 22
                    ProcessTextViewModel.HistoryFilter.REPOST -> draft.kind == 6 || draft.kind == 16
                    ProcessTextViewModel.HistoryFilter.QUOTE -> draft.isQuote
                }
            }
        }
    }

    val filteredDrafts by remember(drafts) {
        derivedStateOf { drafts.filter(filterPredicate) }
    }
    
    val filteredScheduled by remember(allScheduled) {
        derivedStateOf { allScheduled.filter(filterPredicate) }
    }

    val combinedHistory by remember(scheduledHistory, remoteHistory) {
        derivedStateOf {
            (scheduledHistory + remoteHistory).distinctBy { 
                it.publishedEventId ?: "local_${it.id}" 
            }.filter(filterPredicate).sortedByDescending { it.actualPublishedAt ?: it.scheduledAt ?: it.lastEdited }
        }
    }
    
    LaunchedEffect(selectedTab) {
        if (selectedTab == 2 && !isFetchingRemoteHistory) {
            vm.fetchRemoteHistory()
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxSize()) {
            when (selectedTab) {
                0 -> DraftList(
                    drafts = filteredDrafts, 
                    isFiltered = activeFilters.isNotEmpty(),
                    onSelect = onEditDraft, 
                    onDelete = { vm.deleteDraft(it.id) }, 
                    vm = vm, 
                    onMediaClick = { showMediaDetail = it }
                )
                1 -> ScheduledList(
                    pending = filteredScheduled, 
                    isFiltered = activeFilters.isNotEmpty(),
                    vm = vm, 
                    onCancel = { vm.cancelScheduledNote(it) }, 
                    onEditAndReschedule = onEditAndReschedule, 
                    onSaveToDrafts = onSaveToDrafts, 
                    onMediaClick = { showMediaDetail = it }
                )
                2 -> HistoryList(
                    history = combinedHistory,
                    vm = vm,
                    isFetching = isFetchingRemoteHistory,
                    onClearHistory = { vm.clearScheduledHistory() },
                    onOpenInClient = onOpenInClient,
                    onMediaClick = { showMediaDetail = it },
                    onRepost = onRepost,
                    onLoadMore = { vm.loadMoreRemoteHistory() }
                )
            }
        }
        
        // Filter FAB and Dropdown (Now universal)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = showFilters,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .width(220.dp),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 8.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Filters", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        
                        // Full History Toggle - Only in History Tab
                        if (selectedTab == 2) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Full History", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = vm.isFullHistoryEnabled,
                                    onCheckedChange = { vm.toggleFullHistory() },
                                    modifier = Modifier.scale(0.8f)
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                        
                        // Kind Filters
                        val filterPairs = listOf(
                            "Note" to ProcessTextViewModel.HistoryFilter.NOTE,
                            "Highlight" to ProcessTextViewModel.HistoryFilter.HIGHLIGHT,
                            "Media" to ProcessTextViewModel.HistoryFilter.MEDIA,
                            "Repost" to ProcessTextViewModel.HistoryFilter.REPOST,
                            "Quote" to ProcessTextViewModel.HistoryFilter.QUOTE
                        )
                        
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            filterPairs.forEach { (label, filter) ->
                                val selected = activeFilters.contains(filter)
                                FilterChip(
                                    selected = selected,
                                    onClick = { vm.toggleHistoryFilter(filter) },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                    leadingIcon = if (selected) {
                                        { Icon(Icons.Default.Check, null, Modifier.size(12.dp)) }
                                    } else null,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }

                        if (activeFilters.isNotEmpty()) {
                            TextButton(
                                onClick = { vm.activeHistoryFilters.clear() },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Clear All", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = { showFilters = !showFilters },
                containerColor = if (activeFilters.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (showFilters) Icons.Default.Close else Icons.Default.FilterList,
                    contentDescription = "Filters",
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        showMediaDetail?.let { item ->
            MediaDetailDialog(
                item = item,
                vm = vm,
                onDismiss = { showMediaDetail = null }
            )
        }
    }
}

@Composable
fun DraftList(
    drafts: List<Draft>, 
    isFiltered: Boolean,
    onSelect: (Draft) -> Unit, 
    onDelete: (Draft) -> Unit, 
    vm: ProcessTextViewModel, 
    onMediaClick: (MediaUploadState) -> Unit
) {
    if (drafts.isEmpty()) {
        Box(
            Modifier.fillMaxSize().padding(bottom = 64.dp), 
            contentAlignment = Alignment.Center
        ) {
            val message = if (isFiltered) "No drafts match your filters" else "No saved drafts"
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(drafts, key = { it.id }) { draft ->
                DraftItem(draft, onSelect, onDelete, vm, onMediaClick)
            }
            item { Spacer(Modifier.height(80.dp)) } // Bottom padding for FAB
        }
    }
}

@Composable
fun DraftItem(draft: Draft, onSelect: (Draft) -> Unit, onDelete: (Draft) -> Unit, vm: ProcessTextViewModel, onMediaClick: (MediaUploadState) -> Unit) {
    val date = NostrUtils.formatDate(draft.lastEdited)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onSelect(draft) }
            .animateContentSize(), // Smoothly animate height changes
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(NostrUtils.getKindLabel(draft.kind, draft.content), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(date, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            // Rich Previews for Draft
            var linkMetadata by remember(draft.id, draft.previewTitle, draft.previewImageUrl) { 
                mutableStateOf<com.ryans.nostrshare.utils.LinkMetadata?>(
                    if (!draft.previewTitle.isNullOrBlank() || !draft.previewImageUrl.isNullOrBlank()) {
                        com.ryans.nostrshare.utils.LinkMetadata(
                            url = draft.sourceUrl,
                            title = draft.previewTitle,
                            description = draft.previewDescription,
                            imageUrl = draft.previewImageUrl,
                            siteName = draft.previewSiteName
                        )
                    } else null
                )
            }
            var nostrEvent by remember(draft.id, draft.originalEventJson) { 
                mutableStateOf<org.json.JSONObject?>(
                    draft.originalEventJson?.let { 
                        try { org.json.JSONObject(it) } catch (e: Exception) { null } 
                    }
                )
            }
            
            val firstUrl = remember(draft.content, draft.sourceUrl) {
                val urlRegex = "https?://[^\\s]+".toRegex()
                draft.sourceUrl.takeIf { it.startsWith("http") } 
                    ?: urlRegex.find(draft.content)?.value
            }
            
            val nostrEntity = remember(draft.content, draft.sourceUrl) {
                NostrUtils.findNostrEntity(draft.sourceUrl) ?: NostrUtils.findNostrEntity(draft.content)
            }

            LaunchedEffect(firstUrl) {
                if (linkMetadata != null) return@LaunchedEffect // Use cache if available
                
                firstUrl?.let { url ->
                    val mediaPattern = ".*\\.(jpg|jpeg|png|gif|webp|bmp|svg|mp4|mov|webm|zip|pdf|exe|dmg|iso|apk)$".toRegex(RegexOption.IGNORE_CASE)
                    if (!mediaPattern.matches(url.substringBefore("?"))) {
                        linkMetadata = com.ryans.nostrshare.utils.LinkPreviewManager.fetchMetadata(url)
                    }
                }
            }

            LaunchedEffect(nostrEntity) {
                if (nostrEvent != null) return@LaunchedEffect // Use cache if available
                
                nostrEntity?.let { entity ->
                    if (entity.type == "note" || entity.type == "nevent") {
                        nostrEvent = vm.relayManager.fetchEvent(entity.id, entity.relays)
                    }
                }
            }

            // Combined Media Logic for Draft
            val combinedMediaItems = remember(draft.mediaJson, draft.content) {
                val attached = parseMediaJson(draft.mediaJson)
                val attachedUrls = attached.mapNotNull { it.uploadedUrl?.lowercase() }.toSet()
                
                val mediaUrlPattern = "(https?://[^\\s]+(?:\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp|\\.bmp|\\.svg|\\.mp4|\\.mov|\\.webm)(?:\\?[^\\s]*)?)".toRegex(RegexOption.IGNORE_CASE)
                val embeddedUrls = mediaUrlPattern.findAll(draft.content)
                    .map { it.value }
                    .filter { it.lowercase() !in attachedUrls }
                    .map { url ->
                        MediaUploadState(
                            id = java.util.UUID.randomUUID().toString(),
                            uri = android.net.Uri.parse(url),
                            mimeType = when {
                                url.lowercase().contains(".mp4") || url.lowercase().contains(".mov") || url.lowercase().contains(".webm") -> "video/mp4"
                                url.lowercase().contains(".gif") -> "image/gif"
                                else -> "image/jpeg"
                            }
                        ).apply { uploadedUrl = url }
                    }.toList()
                
                attached + embeddedUrls
            }

            val baseContent = remember(draft.content, linkMetadata, nostrEvent, nostrEntity) {
                var content = draft.content
                
                // Hide Link Preview URL only if it has a title or image
                if (linkMetadata != null && firstUrl != null && (linkMetadata!!.title != null || linkMetadata!!.imageUrl != null)) {
                    content = content.replace(firstUrl, "").trim()
                }

                if (nostrEvent != null && nostrEntity != null) {
                    content = content.replace(nostrEntity.bech32, "").trim()
                }
                content
            }

            val hasPreview = (linkMetadata != null && (linkMetadata!!.title != null || linkMetadata!!.imageUrl != null)) || 
                             nostrEvent != null || 
                             !draft.sourceUrl.isNullOrBlank()

            if (baseContent.isNotBlank() || combinedMediaItems.isNotEmpty() || hasPreview) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.height(IntrinsicSize.Max), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            IntegratedContent(
                                content = draft.content,
                                vm = vm,
                                onMediaClick = onMediaClick,
                                mediaItems = combinedMediaItems,
                                linkMetadata = linkMetadata,
                                isHighlight = draft.kind == 9802
                            )
                        }
                    }

                    // Previews and Leftover Media (Outside the highlight bar)
                    val meta = linkMetadata // Capture delegated property for smart casting
                    val urlRegex = "https?://[^\\s]+".toRegex()
                    val ogUrl = meta?.url?.takeIf { meta.title != null || meta.imageUrl != null }
                    val ogShownInline = ogUrl != null && urlRegex.findAll(draft.content).any { it.value.equals(ogUrl, ignoreCase = true) }
                    
                    val showFallbackOG = !ogShownInline && meta != null && (meta.title != null || meta.imageUrl != null)
                    val showNostrPreview = nostrEvent != null
                    // Hide source link if any rich preview (OG card or Nostr event) is shown
                    val hasRichPreview = (meta != null && (meta.title != null || meta.imageUrl != null)) || showNostrPreview
                    val showFallbackSource = !hasRichPreview && !draft.sourceUrl.isNullOrBlank()

                    if (showFallbackOG) {
                        Spacer(Modifier.height(8.dp))
                        LinkPreviewCard(meta!!)
                    } else if (showNostrPreview) {
                        Spacer(Modifier.height(8.dp))
                        NostrEventPreview(nostrEvent!!, vm, onMediaClick)
                    } else if (showFallbackSource) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Source: ${draft.sourceUrl}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp).padding(start = if (draft.kind == 9802) 16.dp else 0.dp)
                        )
                    }

                    // Leftover Media
                    val mediaUrlPattern = "(https?://[^\\s]+(?:\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp|\\.bmp|\\.svg|\\.mp4|\\.mov|\\.webm)(?:\\?[^\\s]*)?)".toRegex(RegexOption.IGNORE_CASE)
                    val mediaUrlsInText = mediaUrlPattern.findAll(draft.content).map { it.value.lowercase() }.toSet()
                    val leftoverMedia = combinedMediaItems.filter { item ->
                        val url = item.uploadedUrl?.lowercase()
                        url == null || url !in mediaUrlsInText
                    }
                    if (leftoverMedia.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        ResponsiveMediaGrid(leftoverMedia, onMediaClick)
                    }
                }
            }

            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterEnd) {
                TextButton(onClick = { onSelect(draft) }) {
                    Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
            }
        }
    }
}

@Composable
fun ScheduledList(
    pending: List<Draft>,
    isFiltered: Boolean,
    vm: ProcessTextViewModel,
    onCancel: (Draft) -> Unit,
    onEditAndReschedule: (Draft) -> Unit,
    onSaveToDrafts: (Draft) -> Unit,
    onMediaClick: (MediaUploadState) -> Unit
) {
    if (pending.isEmpty()) {
        Box(
            Modifier.fillMaxSize().padding(bottom = 64.dp), 
            contentAlignment = Alignment.Center
        ) {
            val message = if (isFiltered) "No scheduled notes match your filters" else "No pending scheduled notes"
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(pending, key = { it.id }) { note ->
                UnifiedPostItem(
                    note = note,
                    vm = vm,
                    onMediaClick = onMediaClick,
                    actions = {
                        Row {
                            TextButton(onClick = { onSaveToDrafts(note) }) {
                                Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("To Drafts")
                            }
                            TextButton(onClick = { onEditAndReschedule(note) }) {
                                Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Edit")
                            }
                        }
                    }
                )
            }
            item { Spacer(Modifier.height(80.dp)) } // Bottom padding for FAB
        }
    }
}

@Composable
fun HistoryList(
    history: List<Draft>,
    vm: ProcessTextViewModel,
    isFetching: Boolean = false,
    onClearHistory: () -> Unit,
    onOpenInClient: (String) -> Unit,
    onMediaClick: (MediaUploadState) -> Unit,
    onRepost: (Draft) -> Unit,
    onLoadMore: () -> Unit
) {
    val listState = rememberLazyListState()

    // Trigger load more when reaching the end
    LaunchedEffect(listState, history.size, isFetching) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIndex ->
                if (lastIndex != null && lastIndex >= history.size - 5 && !isFetching && !vm.hasReachedEndOfRemoteHistory) {
                    onLoadMore()
                }
            }
    }

    Column(Modifier.fillMaxSize()) {
        if (isFetching && history.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        } else if (isFetching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (history.isEmpty() && !isFetching) {
                    item {
                        Box(
                            Modifier
                                .fillParentMaxSize()
                                .padding(bottom = 64.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val emptyMessage = if (vm.activeHistoryFilters.isNotEmpty()) {
                                "No items match your filters"
                            } else if (!vm.isFullHistoryEnabled) {
                                "Enable Full History to fetch remote notes"
                            } else {
                                "No history found"
                            }
                            Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                items(history, key = { it.id }) { note ->
                    UnifiedPostItem(
                        note = note,
                        vm = vm,
                        onMediaClick = onMediaClick,
                        actions = {
                            if (note.publishedEventId != null && note.publishError == null) {
                                TextButton(onClick = { onRepost(note) }) {
                                    Icon(Icons.Default.Schedule, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Repost")
                                }
                                TextButton(onClick = { onOpenInClient(note.publishedEventId!!) }) {
                                    Icon(Icons.Default.Link, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Open")
                                }
                            }
                        }
                    )
                }

                if (isFetching && history.isNotEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (vm.hasReachedEndOfRemoteHistory && history.isNotEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            Text("End of history", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                
                item { Spacer(Modifier.height(80.dp)) } // Bottom padding for FAB
            }
        }
    }
}

@Composable
fun UnifiedPostItem(note: Draft, vm: ProcessTextViewModel, onMediaClick: (MediaUploadState) -> Unit, actions: @Composable () -> Unit) {
    val date = NostrUtils.formatDate(note.scheduledAt ?: note.lastEdited)
    val isCompleted = note.isCompleted
    val isSuccess = isCompleted && note.publishError == null
    
    val profile = note.pubkey?.let { vm.usernameCache[it] }
    
    LaunchedEffect(note.pubkey) {
        note.pubkey?.let { pk ->
            if (vm.usernameCache[pk]?.name == null) {
                vm.resolveUsername(pk)
            }
        }
    }

    val containerColor = when {
        note.isRemote -> MaterialTheme.colorScheme.surfaceVariant
        isSuccess -> if (isSystemInDarkTheme()) androidx.compose.ui.graphics.Color(0xFF1B5E20).copy(alpha = 0.4f) else androidx.compose.ui.graphics.Color(0xFFE8F5E9)
        isCompleted -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    }

    val statusColor = when {
        note.isRemote -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        isSuccess -> if (isSystemInDarkTheme()) androidx.compose.ui.graphics.Color(0xFF81C784) else androidx.compose.ui.graphics.Color(0xFF4CAF50)
        isCompleted -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .animateContentSize(), // Smoothly animate height changes
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(modifier = Modifier.size(40.dp).padding(top = 4.dp)) {
                    if (profile?.pictureUrl != null) {
                        AsyncImage(
                            model = profile.pictureUrl,
                            contentDescription = "User Avatar",
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "User Avatar",
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).padding(8.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(Modifier.width(12.dp))
                
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (note.isOfflineRetry && !isCompleted) {
                                Icon(
                                    imageVector = Icons.Default.WifiOff,
                                    contentDescription = "Offline",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Icon(
                                if (isCompleted) {
                                    if (isSuccess) Icons.Default.Check
                                    else Icons.Default.Warning
                                } else if (note.isScheduled) Icons.Default.Schedule
                                else Icons.Default.Edit,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = statusColor
                            )
                            Spacer(Modifier.width(4.dp))
                            
                            val displayTime = remember(note.scheduledAt, note.actualPublishedAt) {
                                val scheduled = note.scheduledAt ?: note.lastEdited
                                val scheduledStr = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(scheduled))
                                
                                if (isSuccess && note.actualPublishedAt != null && note.scheduledAt != null) {
                                    val diffMs = note.actualPublishedAt - note.scheduledAt
                                    val diffSeconds = (diffMs / 1000) % 60
                                    val diffMinutes = (diffMs / (1000 * 60))
                                    val offsetStr = String.format("(+%d:%02d)", diffMinutes, diffSeconds)
                                    "$scheduledStr $offsetStr"
                                } else {
                                    scheduledStr
                                }
                            }

                            Text(
                                text = if (note.isOfflineRetry && !isCompleted) "Offline - Waiting for Internet" else displayTime,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        val kindLabel = if (note.isQuote) "Quote Post" else NostrUtils.getKindLabel(note.kind, note.content)
                        Text(kindLabel, style = MaterialTheme.typography.labelSmall)
                    }
                    
                    profile?.name?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }

                    Spacer(Modifier.height(4.dp))
                    
                    // Rich Previews
                    var linkMetadata by remember(note.id, note.previewTitle, note.previewImageUrl) { 
                        mutableStateOf<com.ryans.nostrshare.utils.LinkMetadata?>(
                            if (!note.previewTitle.isNullOrBlank() || !note.previewImageUrl.isNullOrBlank()) {
                                com.ryans.nostrshare.utils.LinkMetadata(
                                    url = note.sourceUrl,
                                    title = note.previewTitle,
                                    description = note.previewDescription,
                                    imageUrl = note.previewImageUrl,
                                    siteName = note.previewSiteName
                                )
                            } else null
                        )
                    }
                    var nostrEvent by remember(note.id, note.originalEventJson, note.content) { 
                        mutableStateOf<org.json.JSONObject?>(
                            if (note.kind == 6 || note.kind == 16) {
                                try { org.json.JSONObject(note.content) } catch (e: Exception) {
                                    // Fallback: local reposts store the event in originalEventJson, not content
                                    note.originalEventJson?.let { try { org.json.JSONObject(it) } catch (_: Exception) { null } }
                                }
                            } else null
                        )
                    }
                    
                    val firstUrl = remember(note.content, note.sourceUrl) {
                        val urlRegex = "https?://[^\\s]+".toRegex()
                        // Prioritize sourceUrl for previews, fallback to content
                        note.sourceUrl.takeIf { it.startsWith("http") } 
                            ?: urlRegex.find(note.content)?.value
                    }
                    
                    val nostrEntity = remember(note.content, note.sourceUrl) {
                        NostrUtils.findNostrEntity(note.sourceUrl) ?: NostrUtils.findNostrEntity(note.content)
                    }

                    LaunchedEffect(firstUrl) {
                        // Only skip if we have a full result (title + image)
                        if (linkMetadata != null && linkMetadata!!.imageUrl != null) return@LaunchedEffect 
                        
                        firstUrl?.let { url ->
                            val mediaPattern = ".*\\.(jpg|jpeg|png|gif|webp|bmp|svg|mp4|mov|webm|zip|pdf|exe|dmg|iso|apk)$".toRegex(RegexOption.IGNORE_CASE)
                            if (!mediaPattern.matches(url.substringBefore("?"))) {
                                linkMetadata = com.ryans.nostrshare.utils.LinkPreviewManager.fetchMetadata(url)
                            }
                        }
                    }

                    LaunchedEffect(nostrEntity) {
                        if (nostrEvent != null) return@LaunchedEffect // Use cache
                        
                        nostrEntity?.let { entity ->
                            if (entity.type == "note" || entity.type == "nevent") {
                                nostrEvent = vm.relayManager.fetchEvent(entity.id, entity.relays)
                            }
                        }
                    }


                    // Combined Media Logic (Attachments + Embedded Links)
                    val combinedMediaItems = remember(note.mediaJson, note.content, note.kind) {
                        val attached = parseMediaJson(note.mediaJson)
                        
                        // For reposts (Kind 6/16), skip embedded URL extraction — media belongs to the inner event card
                        if (note.kind == 6 || note.kind == 16) {
                            attached
                        } else {
                            val attachedUrls = attached.mapNotNull { it.uploadedUrl?.lowercase() }.toSet()
                            
                            val mediaUrlPattern = "(https?://[^\\s]+(?:\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp|\\.bmp|\\.svg|\\.mp4|\\.mov|\\.webm)(?:\\?[^\\s]*)?)".toRegex(RegexOption.IGNORE_CASE)
                            val embeddedUrls = mediaUrlPattern.findAll(note.content)
                                .map { it.value }
                                .filter { it.lowercase() !in attachedUrls }
                                .map { url ->
                                    MediaUploadState(
                                        id = java.util.UUID.randomUUID().toString(),
                                        uri = android.net.Uri.parse(url),
                                        mimeType = when {
                                            url.lowercase().contains(".mp4") || url.lowercase().contains(".mov") || url.lowercase().contains(".webm") -> "video/mp4"
                                            url.lowercase().contains(".gif") -> "image/gif"
                                            else -> "image/jpeg"
                                        }
                                    ).apply { uploadedUrl = url }
                                }.toList()
                            
                            attached + embeddedUrls
                        }
                    }

                    val showContent = note.content.isNotBlank() || combinedMediaItems.isNotEmpty() || (linkMetadata != null && (linkMetadata!!.title != null || linkMetadata!!.imageUrl != null)) || nostrEvent != null || !note.sourceUrl.isNullOrBlank()

                    if (showContent) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                                    val contentToRender = if (note.kind == 6 || note.kind == 16) "" else note.content
                                    IntegratedContent(
                                        content = contentToRender,
                                        vm = vm,
                                        onMediaClick = onMediaClick,
                                        mediaItems = combinedMediaItems,
                                        linkMetadata = linkMetadata,
                                        nostrEntity = nostrEntity,
                                        nostrEvent = nostrEvent,
                                        isHighlight = note.kind == 9802
                                    )
                            val currentText = if (note.kind == 6 || note.kind == 16) "" else note.content
                            val urlRegex = "https?://[^\\s]+".toRegex()
                            
                            val sourceUrlShownInline = !note.sourceUrl.isNullOrBlank() && 
                                                       (urlRegex.findAll(currentText).any { NostrUtils.urlsMatch(it.value, note.sourceUrl!!) } ||
                                                        currentText.contains(note.sourceUrl!!, ignoreCase = true))
                            
                            val showFallbackSource = linkMetadata == null && 
                                                     nostrEvent == null && 
                                                     !note.sourceUrl.isNullOrBlank() && 
                                                     !note.sourceUrl!!.startsWith("nostr:", ignoreCase = true) &&
                                                     !sourceUrlShownInline

                            if (showFallbackSource) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Source: ${note.sourceUrl}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 4.dp).padding(start = if (note.kind == 9802) 16.dp else 0.dp)
                                )
                            }
                        }
                    }
                    
                    if (isCompleted && note.publishError != null) {
                        Text(text = "Error: ${note.publishError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
        }
    }
}

@Composable
fun LinkPreviewCard(meta: com.ryans.nostrshare.utils.LinkMetadata) {
    if (meta.title == null && meta.imageUrl == null) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column {
            if (meta.imageUrl != null) {
                AsyncImage(
                    model = meta.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.91f) // Standard OpenGraph aspect ratio for stability
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    contentScale = ContentScale.Crop
                )
            }
            Column(Modifier.padding(8.dp)) {
                meta.title?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold
                    )
                }
                meta.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                meta.siteName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun NostrEventPreview(event: org.json.JSONObject, vm: ProcessTextViewModel, onMediaClick: (MediaUploadState) -> Unit) {
    val pubkey = event.optString("pubkey")
    val content = event.optString("content")
    val profile = remember(pubkey) { vm.usernameCache[pubkey] }
    
    LaunchedEffect(pubkey) {
        if (vm.usernameCache[pubkey]?.name == null) {
            vm.resolveUsername(pubkey)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Row(Modifier.padding(8.dp)) {
            if (profile?.pictureUrl != null) {
                AsyncImage(
                    model = profile.pictureUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.Person, 
                    null, 
                    Modifier.size(24.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).padding(4.dp)
                )
            }
            
            Spacer(Modifier.width(8.dp))
            
            Column {
                Text(
                    text = profile?.name ?: pubkey.take(8),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                val filteredContent = filterImageUrls(content)
                if (filteredContent.isNotBlank()) {
                    Text(
                        text = filteredContent,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // Scrape media from nested event content
                val mediaUrlPattern = "(https?://[^\\s]+(?:\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp|\\.bmp|\\.svg|\\.mp4|\\.mov|\\.webm)(?:\\?[^\\s]*)?)".toRegex(RegexOption.IGNORE_CASE)
                val mediaMatches = mediaUrlPattern.findAll(content).map { it.value }.toList()
                
                if (mediaMatches.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    val previewItems = mediaMatches.take(3).map { url ->
                        MediaUploadState(
                            id = UUID.randomUUID().toString(),
                            uri = Uri.parse(url),
                            mimeType = if (url.lowercase().contains(".mp4")) "video/mp4" else "image/jpeg"
                        ).apply { uploadedUrl = url }
                    }
                    ResponsiveMediaGrid(
                        mediaItems = previewItems,
                        onMediaClick = onMediaClick
                    )
                }
            }
        }
    }
}

fun filterImageUrls(content: String): String {
    val mediaUrlPattern = "(https?://[^\\s]+(?:\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp|\\.bmp|\\.svg|\\.mp4|\\.mov|\\.webm)(?:\\?[^\\s]*)?)".toRegex(RegexOption.IGNORE_CASE)
    return content.replace(mediaUrlPattern, "").trim()
}

fun parseMediaJson(json: String?): List<MediaUploadState> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(json)
        val items = mutableListOf<MediaUploadState>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val uriStr = obj.optString("uri").takeIf { it.isNotBlank() } ?: continue
            val item = MediaUploadState(
                id = obj.optString("id", UUID.randomUUID().toString()),
                uri = android.net.Uri.parse(uriStr),
                mimeType = obj.optString("mimeType").takeIf { it.isNotBlank() }
            ).apply {
                uploadedUrl = obj.optString("uploadedUrl").takeIf { it != "null" && it.isNotBlank() }
                hash = obj.optString("hash").takeIf { it != "null" && it.isNotBlank() }
                size = obj.optLong("size", 0L)
            }
            items.add(item)
        }
        items
    } catch (_: Exception) {
        emptyList()
    }
}

@Composable
fun MediaThumbnailRow(mediaItems: List<MediaUploadState>, onMediaClick: (MediaUploadState) -> Unit) {
    // Keeping this for now in case other parts of the app use it, but marking as deprecated or updating its style if needed.
    // However, for Drafts we now use ResponsiveMediaGrid.
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        mediaItems.take(5).forEach { item ->
            val model = item.uploadedUrl ?: item.uri
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onMediaClick(item) }
            ) {
                if (item.mimeType?.startsWith("video/") == true) {
                     Box(
                         modifier = Modifier.fillMaxSize(),
                         contentAlignment = Alignment.Center
                     ) {
                         Icon(
                             imageVector = Icons.Default.PlayArrow,
                             contentDescription = "Video",
                             tint = MaterialTheme.colorScheme.onSurfaceVariant,
                             modifier = Modifier.size(24.dp)
                         )
                     }
                } else {
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        if (mediaItems.size > 5) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("+${mediaItems.size - 5}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun IntegratedContent(
    content: String,
    vm: ProcessTextViewModel,
    onMediaClick: (MediaUploadState) -> Unit,
    mediaItems: List<MediaUploadState>,
    linkMetadata: com.ryans.nostrshare.utils.LinkMetadata? = null,
    nostrEntity: NostrUtils.NostrEntity? = null,
    nostrEvent: org.json.JSONObject? = null,
    isHighlight: Boolean = false
) {
    val mediaUrlPattern = "(https?://[^\\s]+(?:\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp|\\.bmp|\\.svg|\\.mp4|\\.mov|\\.webm)(?:\\?[^\\s]*)?)".toRegex(RegexOption.IGNORE_CASE)
    val urlRegex = "https?://[^\\s]+".toRegex()
    val nostrRegex = "(nostr:)?(nevent1|note1|naddr1|npub1|nprofile1)[a-z0-9]+".toRegex(RegexOption.IGNORE_CASE)

    // 1. Identify all media URLs and their positions
    val allUrlMatches = urlRegex.findAll(content).toList()
    val mediaMatches = allUrlMatches.filter { match ->
        mediaUrlPattern.matches(match.value) || 
        mediaItems.any { it.uploadedUrl?.equals(match.value, ignoreCase = true) == true }
    }
    
    // 3. Identify the Nostr entity card if it qualifies
    // 2. Identify the OpenGraph URL if it qualifies for an inline card
    val ogUrl = linkMetadata?.url?.takeIf { linkMetadata.title != null || linkMetadata.imageUrl != null }
    val ogMatch = ogUrl?.let { url -> 
        allUrlMatches
            .filter { match -> mediaMatches.none { it.range == match.range } }
            .find { NostrUtils.urlsMatch(it.value, url) }
    }

    // 3. Identify the Nostr entity card if it qualifies
    val nostrMatch = if (nostrEvent != null && nostrEntity != null) {
        nostrRegex.findAll(content).find { match ->
            match.value.lowercase().removePrefix("nostr:") == nostrEntity.bech32.lowercase().removePrefix("nostr:")
        }
    } else null

    // 4. Split content into segments (Text, MediaGroup, LinkPreview, or NostrPreview)
    val segments = mutableListOf<ContentSegment>()
    var lastIndex = 0
    val usedMediaUrls = mutableSetOf<String>()
    
    // Include all generic URLs in the segmentation to isolate them from quotes
    val genericUrlMatches = allUrlMatches.filter { match ->
        mediaMatches.none { it.range == match.range } &&
        ogMatch?.range != match.range
    }
    
    val allMatches = (mediaMatches + genericUrlMatches + listOfNotNull(ogMatch) + listOfNotNull(nostrMatch)).sortedBy { it.range.first }

    var i = 0
    while (i < allMatches.size) {
        val match = allMatches[i]
        
        if (match.range.first > lastIndex) {
            segments.add(ContentSegment.Text(content.substring(lastIndex, match.range.first)))
        }
        
        if (match == ogMatch) {
            segments.add(ContentSegment.LinkPreview(linkMetadata!!))
            lastIndex = match.range.last + 1
            i++
        } else if (match == nostrMatch) {
            segments.add(ContentSegment.NostrPreview(nostrEvent!!))
            lastIndex = match.range.last + 1
            i++
        } else {
            val group = mutableListOf<String>()
            group.add(match.value)
            
            var nextI = i + 1
            var currentEnd = match.range.last + 1
            
            // Only group MediaMatches. Generic URLs should stay separate to avoid highlight bar leaks.
            val isMediaMatch = mediaMatches.any { it.range == match.range }
            
            if (isMediaMatch) {
                while (nextI < allMatches.size) {
                    val nextMatch = allMatches[nextI]
                    if (nextMatch == ogMatch || nextMatch == nostrMatch) break 
                    if (genericUrlMatches.any { it.range == nextMatch.range }) break
                    
                    val gap = content.substring(currentEnd, nextMatch.range.first)
                    if (gap.isBlank()) {
                        group.add(nextMatch.value)
                        currentEnd = nextMatch.range.last + 1
                        nextI++
                    } else {
                        break
                    }
                }
            }
            
            val groupedItems = group.mapNotNull { url ->
                mediaItems.find { it.uploadedUrl?.lowercase() == url.lowercase() }
            }
            
            if (groupedItems.isNotEmpty()) {
                segments.add(ContentSegment.MediaGroup(groupedItems))
                usedMediaUrls.addAll(group.map { it.lowercase() })
            } else {
                segments.add(ContentSegment.Text(group.joinToString(" ")))
            }
            
            i = nextI
            lastIndex = currentEnd
        }
    }
    
    if (lastIndex < content.length) {
        segments.add(ContentSegment.Text(content.substring(lastIndex)))
    }

    // 5. Add leftover media that wasn't mentioned in the text
    val leftoverMedia = mediaItems.filter { item ->
        val url = item.uploadedUrl?.lowercase()
        url == null || url !in usedMediaUrls
    }
    if (leftoverMedia.isNotEmpty()) {
        segments.add(ContentSegment.MediaGroup(leftoverMedia))
    }
    
    // 5. Add fallback rich cards if they weren't matched inline
    if (ogMatch == null && linkMetadata != null && (linkMetadata.title != null || linkMetadata.imageUrl != null)) {
        segments.add(ContentSegment.LinkPreview(linkMetadata))
    }
    if (nostrMatch == null && nostrEvent != null) {
        segments.add(ContentSegment.NostrPreview(nostrEvent))
    }
    
    // Render Segments
    Column(modifier = Modifier.fillMaxWidth()) {
        segments.forEach { segment ->
            when (segment) {
                is ContentSegment.Text -> {
                    if (segment.text.trim().isNotBlank()) {
                        // Replace nostr:npub... and nostr:nprofile... with @name
                        var formattedText = segment.text.trim()
                        val npubRegex = "(nostr:)?(npub1|nprofile1)[a-z0-9]+".toRegex(RegexOption.IGNORE_CASE)
                        formattedText = npubRegex.replace(formattedText) { match ->
                            val rawBech32 = match.value.lowercase().removePrefix("nostr:")
                            val entity = NostrUtils.findNostrEntity(rawBech32)
                            val pubkey = entity?.id
                            if (pubkey != null) {
                                val profile = vm.usernameCache[pubkey]
                                if (profile != null && profile.name?.isNotBlank() == true) {
                                    "@${profile.name}"
                                } else {
                                    // Fallback to truncated npub if name isn't loaded
                                    "@" + rawBech32.take(9) + ":" + rawBech32.takeLast(4)
                                }
                            } else {
                                match.value // Keep original if it fails to decode
                            }
                        }
                        
                        val isSourceLabel = formattedText.startsWith("Source:", ignoreCase = true)
                        val isUrlOnly = (formattedText.startsWith("http", ignoreCase = true) || formattedText.startsWith("nostr:", ignoreCase = true)) && !formattedText.contains(" ")
                        
                        if (isHighlight && !isUrlOnly && !isSourceLabel) {
                            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(vertical = 4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(4.dp)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = formattedText,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            Text(
                                text = formattedText,
                                style = if (isUrlOnly || isSourceLabel) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                                color = if (isUrlOnly) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                modifier = Modifier.padding(vertical = 4.dp).padding(start = if (isHighlight && (isUrlOnly || isSourceLabel)) 16.dp else 0.dp)
                            )
                        }
                    }
                }
                is ContentSegment.MediaGroup -> {
                    ResponsiveMediaGrid(segment.items, onMediaClick)
                    Spacer(Modifier.height(8.dp))
                }
                is ContentSegment.LinkPreview -> {
                    LinkPreviewCard(segment.meta)
                    Spacer(Modifier.height(8.dp))
                }
                is ContentSegment.NostrPreview -> {
                    NostrEventPreview(segment.event, vm, onMediaClick)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

sealed class ContentSegment {
    data class Text(val text: String) : ContentSegment()
    data class MediaGroup(val items: List<MediaUploadState>) : ContentSegment()
    data class LinkPreview(val meta: com.ryans.nostrshare.utils.LinkMetadata) : ContentSegment()
    data class NostrPreview(val event: org.json.JSONObject) : ContentSegment()
}

@Composable
fun ResponsiveMediaGrid(mediaItems: List<MediaUploadState>, onMediaClick: (MediaUploadState) -> Unit) {
    val cornerSize = 12.dp
    val spacing = 2.dp
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(cornerSize))
    ) {
        when (mediaItems.size) {
            1 -> {
                MediaGridItem(mediaItems[0], Modifier.fillMaxWidth().aspectRatio(16f/9f), onMediaClick)
            }
            2 -> {
                Row(Modifier.fillMaxWidth().aspectRatio(2f/1f)) {
                    MediaGridItem(mediaItems[0], Modifier.weight(1f).fillMaxHeight(), onMediaClick)
                    Spacer(Modifier.width(spacing))
                    MediaGridItem(mediaItems[1], Modifier.weight(1f).fillMaxHeight(), onMediaClick)
                }
            }
            3 -> {
                Row(Modifier.fillMaxWidth().aspectRatio(2f/1f)) {
                    MediaGridItem(mediaItems[0], Modifier.weight(1f).fillMaxHeight(), onMediaClick)
                    Spacer(Modifier.width(spacing))
                    Column(Modifier.weight(0.5f).fillMaxHeight()) {
                        MediaGridItem(mediaItems[1], Modifier.weight(1f).fillMaxWidth(), onMediaClick)
                        Spacer(Modifier.height(spacing))
                        MediaGridItem(mediaItems[2], Modifier.weight(1f).fillMaxWidth(), onMediaClick)
                    }
                }
            }
            4 -> {
                Column(Modifier.fillMaxWidth().aspectRatio(1.5f/1f)) {
                    Row(Modifier.weight(1f)) {
                        MediaGridItem(mediaItems[0], Modifier.weight(1f).fillMaxHeight(), onMediaClick)
                        Spacer(Modifier.width(spacing))
                        MediaGridItem(mediaItems[1], Modifier.weight(1f).fillMaxHeight(), onMediaClick)
                    }
                    Spacer(Modifier.height(spacing))
                    Row(Modifier.weight(1f)) {
                        MediaGridItem(mediaItems[2], Modifier.weight(1f).fillMaxHeight(), onMediaClick)
                        Spacer(Modifier.width(spacing))
                        MediaGridItem(mediaItems[3], Modifier.weight(1f).fillMaxHeight(), onMediaClick)
                    }
                }
            }
            else -> {
                // 5+ items: 2x2 grid with overlay on the last one
                Column(Modifier.fillMaxWidth().aspectRatio(1.5f/1f)) {
                    Row(Modifier.weight(1f)) {
                        MediaGridItem(mediaItems[0], Modifier.weight(1f).fillMaxHeight(), onMediaClick)
                        Spacer(Modifier.width(spacing))
                        MediaGridItem(mediaItems[1], Modifier.weight(1f).fillMaxHeight(), onMediaClick)
                    }
                    Spacer(Modifier.height(spacing))
                    Row(Modifier.weight(1f)) {
                        MediaGridItem(mediaItems[2], Modifier.weight(1f).fillMaxHeight(), onMediaClick)
                        Spacer(Modifier.width(spacing))
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            MediaGridItem(mediaItems[3], Modifier.fillMaxSize(), onMediaClick)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "+${mediaItems.size - 3}",
                                    color = androidx.compose.ui.graphics.Color.White,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MediaGridItem(item: MediaUploadState, modifier: Modifier, onMediaClick: (MediaUploadState) -> Unit) {
    val model = item.uploadedUrl ?: item.uri
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onMediaClick(item) }
    ) {
        if (item.mimeType?.startsWith("video/") == true) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Video",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
            }
        } else {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}
