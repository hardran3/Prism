package com.ryans.nostrshare.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material.icons.filled.Search
import android.net.Uri
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import com.ryans.nostrshare.HistoryUiModel
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

@OptIn(ExperimentalLayoutApi::class)
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
    val drafts by vm.uiDrafts.collectAsState()
    val scheduled by vm.uiScheduled.collectAsState()
    val history by vm.uiHistory.collectAsState()
    val isFetchingRemoteHistory = vm.isFetchingRemoteHistory
    
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
                0 -> DraftList(
                    drafts = drafts, 
                    isFiltered = activeFilters.isNotEmpty() || vm.searchQuery.isNotBlank(),
                    onSelect = onEditDraft, 
                    onDelete = { vm.deleteDraft(it.id) }, 
                    vm = vm, 
                    onMediaClick = { showMediaDetail = it }
                )
                1 -> ScheduledList(
                    pending = scheduled, 
                    isFiltered = activeFilters.isNotEmpty() || vm.searchQuery.isNotBlank(),
                    vm = vm, 
                    onCancel = { vm.cancelScheduledNote(it) }, 
                    onEditAndReschedule = onEditAndReschedule, 
                    onSaveToDrafts = onSaveToDrafts, 
                    onMediaClick = { showMediaDetail = it }
                )
                2 -> HistoryList(
                    history = history,
                    vm = vm,
                    isFetching = isFetchingRemoteHistory,
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
                            pubkey = uiModel.pubkey
                        )
                        onRepost(draft)
                    },
                    onLoadMore = { vm.loadMoreRemoteHistory() }
                )
            }
        }

        // Full-screen click interceptor - placed over content but under filter UI
        if (showFilters) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.1f)) // Subtle dim to indicate interceptor is active
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { showFilters = false }
            )
        }
        
        // Filter FAB and Dropdown
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 16.dp, start = 16.dp),
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
                        .width(260.dp)
                        .clickable(enabled = true, onClick = {}, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null), // Consume clicks
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 8.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Filters & Search", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = vm.searchQuery,
                            onValueChange = { 
                                vm.searchQuery = it 
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search content...", style = MaterialTheme.typography.bodySmall) },
                            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                            trailingIcon = if (vm.searchQuery.isNotEmpty()) {
                                {
                                    IconButton(onClick = { vm.searchQuery = "" }) {
                                        Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                                    }
                                }
                            } else null,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                        
                        Spacer(Modifier.height(12.dp))

                        var showPostTypes by remember { mutableStateOf(true) }
                        var showMediaFilters by remember { mutableStateOf(false) }

                        // Section 1: Post Types
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showPostTypes = !showPostTypes }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Post Types", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Icon(
                                    imageVector = if (showPostTypes) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            
                            androidx.compose.animation.AnimatedVisibility(visible = showPostTypes) {
                                androidx.compose.foundation.layout.FlowRow(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    maxItemsInEachRow = 2
                                ) {
                                    val types = listOf(
                                        "Note" to ProcessTextViewModel.HistoryFilter.NOTE,
                                        "Highlight" to ProcessTextViewModel.HistoryFilter.HIGHLIGHT,
                                        "Media" to ProcessTextViewModel.HistoryFilter.MEDIA,
                                        "Repost" to ProcessTextViewModel.HistoryFilter.REPOST,
                                        "Quote" to ProcessTextViewModel.HistoryFilter.QUOTE
                                    )
                                    types.forEach { (label, filter) ->
                                        val selected = activeFilters.contains(filter)
                                        FilterChip(
                                            selected = selected,
                                            onClick = { vm.toggleHistoryFilter(filter) },
                                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                            leadingIcon = if (selected) { { Icon(Icons.Default.Check, null, Modifier.size(12.dp)) } } else null,
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // Section 2: Media Filters
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showMediaFilters = !showMediaFilters }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Media Filters", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Icon(
                                    imageVector = if (showMediaFilters) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            
                            androidx.compose.animation.AnimatedVisibility(visible = showMediaFilters) {
                                androidx.compose.foundation.layout.FlowRow(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    maxItemsInEachRow = 2
                                ) {
                                    val mediaFilters = listOf(
                                        "Has Media" to ProcessTextViewModel.HistoryFilter.HAS_MEDIA,
                                        "Images" to ProcessTextViewModel.HistoryFilter.IMAGE,
                                        "GIFs" to ProcessTextViewModel.HistoryFilter.GIF,
                                        "Videos" to ProcessTextViewModel.HistoryFilter.VIDEO
                                    )
                                    mediaFilters.forEach { (label, filter) ->
                                        val selected = activeFilters.contains(filter)
                                        FilterChip(
                                            selected = selected,
                                            onClick = { vm.toggleHistoryFilter(filter) },
                                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                            leadingIcon = if (selected) { { Icon(Icons.Default.Check, null, Modifier.size(12.dp)) } } else null,
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (activeFilters.isNotEmpty()) {
                            TextButton(
                                onClick = { vm.activeHistoryFilters.clear() },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Clear All Filters", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = { showFilters = !showFilters },
                containerColor = if (activeFilters.isNotEmpty() || vm.searchQuery.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
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
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun DraftItem(draft: Draft, onSelect: (Draft) -> Unit, onDelete: (Draft) -> Unit, vm: ProcessTextViewModel, onMediaClick: (MediaUploadState) -> Unit) {
    val date = remember(draft.lastEdited) { NostrUtils.formatDate(draft.lastEdited) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onSelect(draft) }
            .animateContentSize(), 
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val label = remember(draft.kind, draft.content) { NostrUtils.getKindLabel(draft.kind, draft.content) }
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(date, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            
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
                if (linkMetadata != null && linkMetadata!!.imageUrl != null) return@LaunchedEffect 
                firstUrl?.let { url ->
                    val mediaPattern = ".*\\.(jpg|jpeg|png|gif|webp|bmp|svg|mp4|mov|webm|zip|pdf|exe|dmg|iso|apk)$".toRegex(RegexOption.IGNORE_CASE)
                    if (!mediaPattern.matches(url.substringBefore("?"))) {
                        linkMetadata = com.ryans.nostrshare.utils.LinkPreviewManager.fetchMetadata(url)
                    }
                }
            }

            LaunchedEffect(nostrEntity) {
                if (nostrEvent != null) return@LaunchedEffect 
                nostrEntity?.let { entity ->
                    if (entity.type == "note" || entity.type == "nevent") {
                        nostrEvent = vm.relayManager.fetchEvent(entity.id, entity.relays)
                    }
                }
            }

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
                    IntegratedContent(
                        content = draft.content,
                        vm = vm,
                        onMediaClick = onMediaClick,
                        mediaItems = combinedMediaItems,
                        linkMetadata = linkMetadata,
                        isHighlight = draft.kind == 9802
                    )

                    val meta = linkMetadata 
                    val urlRegex = remember { "https?://[^\\s]+".toRegex() }
                    val ogUrl = meta?.url?.takeIf { meta.title != null || meta.imageUrl != null }
                    val ogShownInline = remember(ogUrl, draft.content) { 
                        ogUrl != null && urlRegex.findAll(draft.content).any { it.value.equals(ogUrl, ignoreCase = true) }
                    }
                    
                    val showFallbackOG = !ogShownInline && meta != null && (meta.title != null || meta.imageUrl != null)
                    val showNostrPreview = nostrEvent != null
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

                    val mediaUrlPattern = remember { "(https?://[^\\\\s]+(?:\\\\.jpg|\\\\.jpeg|\\\\.png|\\\\.gif|\\\\.webp|\\\\.bmp|\\\\.svg|\\\\.mp4|\\\\.mov|\\\\.webm)(?:\\\\?[^\\\\s]*)?)".toRegex(RegexOption.IGNORE_CASE) }
                    val mediaUrlsInText = remember(mediaUrlPattern, draft.content) { mediaUrlPattern.findAll(draft.content).map { it.value.lowercase() }.toSet() }
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
                    note = HistoryUiModel(
                        id = "local_${note.id}",
                        localId = note.id,
                        contentSnippet = note.content,
                        timestamp = note.scheduledAt ?: note.lastEdited,
                        pubkey = note.pubkey,
                        isRemote = false,
                        isScheduled = note.isScheduled,
                        isCompleted = note.isCompleted,
                        isSuccess = note.isCompleted && note.publishError == null,
                        isOfflineRetry = note.isOfflineRetry,
                        publishError = note.publishError,
                        kind = note.kind,
                        isQuote = note.isQuote,
                        actualPublishedAt = note.actualPublishedAt,
                        scheduledAt = note.scheduledAt,
                        sourceUrl = note.sourceUrl,
                        previewTitle = note.previewTitle,
                        previewImageUrl = note.previewImageUrl,
                        previewDescription = note.previewDescription,
                        previewSiteName = note.previewSiteName,
                        mediaJson = note.mediaJson,
                        originalEventJson = note.originalEventJson
                    ),
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
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun HistoryList(
    history: List<HistoryUiModel>,
    vm: ProcessTextViewModel,
    isFetching: Boolean = false, // Ignored, using manager
    onClearHistory: () -> Unit,
    onOpenInClient: (String) -> Unit,
    onMediaClick: (MediaUploadState) -> Unit,
    onRepost: (HistoryUiModel) -> Unit,
    onLoadMore: () -> Unit
) {
    // Recreate the list state when the pubkey changes to reset scroll position
    val listState: LazyListState = remember(vm.pubkey) { LazyListState() }
    val isSyncing by com.ryans.nostrshare.utils.HistorySyncManager.isSyncing.collectAsState()
    val discoveryCount by com.ryans.nostrshare.utils.HistorySyncManager.discoveryCount.collectAsState()
    val currentRelay by com.ryans.nostrshare.utils.HistorySyncManager.currentRelay.collectAsState()
    val activeSyncPubkey by com.ryans.nostrshare.utils.HistorySyncManager.activePubkey.collectAsState()

    LaunchedEffect(listState, history.size, isSyncing) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIndex ->
                if (lastIndex != null && lastIndex >= history.size - 5 && !isSyncing && !vm.hasReachedEndOfRemoteHistory) {
                    onLoadMore()
                }
            }
    }

    Column(Modifier.fillMaxSize()) {
        if (isSyncing && activeSyncPubkey == vm.pubkey) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Syncing History...",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$discoveryCount notes found",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    if (currentRelay != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Relay: $currentRelay",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
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
                            val emptyMessage = if (vm.activeHistoryFilters.isNotEmpty() || vm.searchQuery.isNotBlank()) {
                                "No items match your filters"
                            } else if (!vm.isFullHistoryEnabled) {
                                "Enable Full History in settings to fetch remote notes" 
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
                            if (note.id.startsWith("local_") || !note.isRemote) {
                                // Handled via other tabs
                            } else if (note.isSuccess) {
                                TextButton(onClick = { onRepost(note) }) {
                                    Icon(Icons.Default.Schedule, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Repost")
                                }
                                TextButton(onClick = { onOpenInClient(note.id) }) {
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
                
                item { Spacer(Modifier.height(80.dp)) }
            }

            FastScrollIndicator(
                lazyListState = listState,
                history = history,
                vm = vm,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
fun UnifiedPostItem(note: HistoryUiModel, vm: ProcessTextViewModel, onMediaClick: (MediaUploadState) -> Unit, actions: @Composable () -> Unit) {
    val date = remember(note.timestamp) { NostrUtils.formatDate(note.timestamp) }
    val isCompleted = note.isCompleted
    val isSuccess = note.isSuccess
    
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
            .animateContentSize(), 
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
                            
                            val displayTime = remember(note.scheduledAt, note.actualPublishedAt, note.timestamp) {
                                val scheduledStr = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(note.timestamp))
                                
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
                        val kindLabel = remember(note.isQuote, note.kind, note.contentSnippet) { if (note.isQuote) "Quote Post" else NostrUtils.getKindLabel(note.kind, note.contentSnippet) }
                        Text(kindLabel, style = MaterialTheme.typography.labelSmall)
                    }
                    
                    profile?.name?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }

                    Spacer(Modifier.height(4.dp))
                    
                    var linkMetadata by remember(note.id, note.previewTitle, note.previewImageUrl) { 
                        mutableStateOf<com.ryans.nostrshare.utils.LinkMetadata?>(
                            if (!note.previewTitle.isNullOrBlank() || !note.previewImageUrl.isNullOrBlank()) {
                                com.ryans.nostrshare.utils.LinkMetadata(
                                    url = note.sourceUrl ?: "",
                                    title = note.previewTitle,
                                    description = note.previewDescription,
                                    imageUrl = note.previewImageUrl,
                                    siteName = note.previewSiteName
                                )
                            } else null
                        )
                    }
                    var nostrEvent by remember(note.id, note.originalEventJson, note.contentSnippet) { 
                        mutableStateOf<org.json.JSONObject?>(
                            if (note.kind == 6 || note.kind == 16) {
                                try { org.json.JSONObject(note.contentSnippet) } catch (e: Exception) {
                                    note.originalEventJson?.let { try { org.json.JSONObject(it) } catch (_: Exception) { null } }
                                }
                            } else null
                        )
                    }
                    
                    val firstUrl = remember(note.contentSnippet, note.sourceUrl) {
                        val urlRegex = "https?://[^\\s]+".toRegex()
                        note.sourceUrl?.takeIf { it.startsWith("http") } 
                            ?: urlRegex.find(note.contentSnippet)?.value
                    }
                    
                    val nostrEntity = remember(note.contentSnippet, note.sourceUrl) {
                        note.sourceUrl?.let { NostrUtils.findNostrEntity(it) } ?: NostrUtils.findNostrEntity(note.contentSnippet)
                    }

                    LaunchedEffect(firstUrl) {
                        if (linkMetadata != null && linkMetadata!!.imageUrl != null) return@LaunchedEffect 
                        firstUrl?.let { url ->
                            val mediaPattern = ".*\\.(jpg|jpeg|png|gif|webp|bmp|svg|mp4|mov|webm|zip|pdf|exe|dmg|iso|apk)$".toRegex(RegexOption.IGNORE_CASE)
                            if (!mediaPattern.matches(url.substringBefore("?"))) {
                                linkMetadata = com.ryans.nostrshare.utils.LinkPreviewManager.fetchMetadata(url)
                            }
                        }
                    }

                    LaunchedEffect(nostrEntity) {
                        if (nostrEvent != null) return@LaunchedEffect 
                        nostrEntity?.let { entity ->
                            if (entity.type == "note" || entity.type == "nevent") {
                                nostrEvent = vm.relayManager.fetchEvent(entity.id, entity.relays)
                            }
                        }
                    }

                    val combinedMediaItems = remember(note.mediaJson, note.contentSnippet, note.kind) {
                        val attached = parseMediaJson(note.mediaJson)
                        if (note.kind == 6 || note.kind == 16) {
                            attached
                        } else {
                            val attachedUrls = attached.mapNotNull { it.uploadedUrl?.lowercase() }.toSet()
                            val mediaUrlPattern = "(https?://[^\\s]+(?:\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp|\\.bmp|\\.svg|\\.mp4|\\.mov|\\.webm)(?:\\?[^\\s]*)?)".toRegex(RegexOption.IGNORE_CASE)
                            val embeddedUrls = mediaUrlPattern.findAll(note.contentSnippet)
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

                    val showContent = remember(note.contentSnippet, combinedMediaItems, linkMetadata, nostrEvent, note.sourceUrl) {
                        note.contentSnippet.isNotBlank() || combinedMediaItems.isNotEmpty() || (linkMetadata != null && (linkMetadata!!.title != null || linkMetadata!!.imageUrl != null)) || nostrEvent != null || !note.sourceUrl.isNullOrBlank()
                    }

                    if (showContent) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                                    val contentToRender = remember(note.kind, note.contentSnippet) { if (note.kind == 6 || note.kind == 16) "" else note.contentSnippet }
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
                            val urlRegex = remember { "https?://[^\\s]+".toRegex() }
                            
                            val showFallbackSource = remember(linkMetadata, nostrEvent, note.sourceUrl, contentToRender) {
                                if (note.sourceUrl.isNullOrBlank()) return@remember false
                                val sourceUrlShownInline = (urlRegex.findAll(contentToRender).any { NostrUtils.urlsMatch(it.value, note.sourceUrl) } ||
                                                        contentToRender.contains(note.sourceUrl, ignoreCase = true))
                                
                                linkMetadata == null && 
                                nostrEvent == null && 
                                !note.sourceUrl.startsWith("nostr:", ignoreCase = true) &&
                                !sourceUrlShownInline
                            }

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
                        .aspectRatio(1.91f) 
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
    val pubkey = remember(event) { event.optString("pubkey") }
    val content = remember(event) { event.optString("content") }
    val profile = vm.usernameCache[pubkey]
    
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
                val filteredContent = remember(content) { filterImageUrls(content) }
                if (filteredContent.isNotBlank()) {
                    Text(
                        text = filteredContent,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                val mediaUrlPattern = remember { "(https?://[^\\s]+(?:\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp|\\.bmp|\\.svg|\\.mp4|\\.mov|\\.webm)(?:\\?[^\\s]*)?)".toRegex(RegexOption.IGNORE_CASE) }
                val mediaMatches = remember(content) { mediaUrlPattern.findAll(content).map { it.value }.toList() }
                
                if (mediaMatches.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    val previewItems = remember(mediaMatches) {
                        mediaMatches.take(3).map { url ->
                            MediaUploadState(
                                id = UUID.randomUUID().toString(),
                                uri = Uri.parse(url),
                                mimeType = if (url.lowercase().contains(".mp4")) "video/mp4" else "image/jpeg"
                            ).apply { uploadedUrl = url }
                        }
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
    val segments = remember(content, mediaItems, linkMetadata, nostrEntity, nostrEvent) {
        val mediaUrlPattern = "(https?://[^\\s]+(?:\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp|\\.bmp|\\.svg|\\.mp4|\\.mov|\\.webm)(?:\\?[^\\s]*)?)".toRegex(RegexOption.IGNORE_CASE)
        val urlRegex = "https?://[^\\s]+".toRegex()
        val nostrRegex = "(nostr:)?(nevent1|note1|naddr1|npub1|nprofile1)[a-z0-9]+".toRegex(RegexOption.IGNORE_CASE)

        val allUrlMatches = urlRegex.findAll(content).toList()
        val mediaMatches = allUrlMatches.filter { match ->
            mediaUrlPattern.matches(match.value) || 
            mediaItems.any { it.uploadedUrl?.equals(match.value, ignoreCase = true) == true }
        }
        
        val ogUrl = linkMetadata?.url?.takeIf { linkMetadata.title != null || linkMetadata.imageUrl != null }
        val ogMatch = ogUrl?.let { url -> 
            allUrlMatches
                .filter { match -> mediaMatches.none { it.range == match.range } }
                .find { NostrUtils.urlsMatch(it.value, url) }
        }

        val nostrMatch = if (nostrEvent != null && nostrEntity != null) {
            nostrRegex.findAll(content).find { match ->
                match.value.lowercase().removePrefix("nostr:") == nostrEntity.bech32.lowercase().removePrefix("nostr:")
            }
        } else null

        val localSegments = mutableListOf<ContentSegment>()
        var lastIndex = 0
        val usedMediaUrls = mutableSetOf<String>()
        
        val genericUrlMatches = allUrlMatches.filter { match ->
            mediaMatches.none { it.range == match.range } &&
            ogMatch?.range != match.range
        }
        
        val allMatches = (mediaMatches + genericUrlMatches + listOfNotNull(ogMatch) + listOfNotNull(nostrMatch)).sortedBy { it.range.first }

        var i = 0
        while (i < allMatches.size) {
            val match = allMatches[i]
            if (match.range.first > lastIndex) {
                localSegments.add(ContentSegment.Text(content.substring(lastIndex, match.range.first)))
            }
            
            if (match == ogMatch) {
                localSegments.add(ContentSegment.LinkPreview(linkMetadata!!))
                lastIndex = match.range.last + 1
                i++
            } else if (match == nostrMatch) {
                localSegments.add(ContentSegment.NostrPreview(nostrEvent!!))
                lastIndex = match.range.last + 1
                i++
            } else {
                val group = mutableListOf<String>()
                group.add(match.value)
                var nextI = i + 1
                var currentEnd = match.range.last + 1
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
                        } else break
                    }
                }
                
                val groupedItems = group.mapNotNull { url ->
                    mediaItems.find { it.uploadedUrl?.lowercase() == url.lowercase() }
                }
                if (groupedItems.isNotEmpty()) {
                    localSegments.add(ContentSegment.MediaGroup(groupedItems))
                    usedMediaUrls.addAll(group.map { it.lowercase() })
                } else {
                    localSegments.add(ContentSegment.Text(group.joinToString(" ")))
                }
                i = nextI
                lastIndex = currentEnd
            }
        }
        if (lastIndex < content.length) {
            localSegments.add(ContentSegment.Text(content.substring(lastIndex)))
        }
        val leftoverMedia = mediaItems.filter { item ->
            val url = item.uploadedUrl?.lowercase()
            url == null || url !in usedMediaUrls
        }
        if (leftoverMedia.isNotEmpty()) localSegments.add(ContentSegment.MediaGroup(leftoverMedia))
        if (ogMatch == null && linkMetadata != null && (linkMetadata.title != null || linkMetadata.imageUrl != null)) {
            localSegments.add(ContentSegment.LinkPreview(linkMetadata))
        }
        if (nostrMatch == null && nostrEvent != null) localSegments.add(ContentSegment.NostrPreview(nostrEvent))
        
        localSegments
    }
    
    Column(modifier = Modifier.fillMaxWidth()) {
        segments.forEach { segment ->
            when (segment) {
                is ContentSegment.Text -> {
                    if (segment.text.trim().isNotBlank()) {
                        val formattedText = remember(segment.text) {
                            var text = segment.text.trim()
                            val npubRegex = "(nostr:)?(npub1|nprofile1)[a-z0-9]+".toRegex(RegexOption.IGNORE_CASE)
                            npubRegex.replace(text) { match ->
                                val rawBech32 = match.value.lowercase().removePrefix("nostr:")
                                val entity = NostrUtils.findNostrEntity(rawBech32)
                                val profile = entity?.id?.let { vm.usernameCache[it] }
                                if (profile != null && profile.name?.isNotBlank() == true) "@${profile.name}"
                                else "@" + rawBech32.take(9) + ":" + rawBech32.takeLast(4)
                            }
                        }
                        
                        val isUrlOnly = remember(formattedText) { (formattedText.startsWith("http", ignoreCase = true) || formattedText.startsWith("nostr:", ignoreCase = true)) && !formattedText.contains(" ") }
                        val isSourceLabel = remember(formattedText) { formattedText.startsWith("Source:", ignoreCase = true) }
                        
                        if (isHighlight && !isUrlOnly && !isSourceLabel) {
                            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(vertical = 4.dp)) {
                                Box(modifier = Modifier.fillMaxHeight().width(4.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                                Spacer(Modifier.width(12.dp))
                                Text(text = formattedText, style = MaterialTheme.typography.bodyMedium)
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
    
    Box(modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(cornerSize))) {
        when (mediaItems.size) {
            1 -> MediaGridItem(mediaItems[0], Modifier.fillMaxWidth().aspectRatio(16f/9f), onMediaClick)
            2 -> Row(Modifier.fillMaxWidth().aspectRatio(2f/1f)) {
                MediaGridItem(mediaItems[0], Modifier.weight(1f).fillMaxHeight(), onMediaClick)
                Spacer(Modifier.width(spacing))
                MediaGridItem(mediaItems[1], Modifier.weight(1f).fillMaxHeight(), onMediaClick)
            }
            3 -> Row(Modifier.fillMaxWidth().aspectRatio(2f/1f)) {
                MediaGridItem(mediaItems[0], Modifier.weight(1f).fillMaxHeight(), onMediaClick)
                Spacer(Modifier.width(spacing))
                Column(Modifier.weight(0.5f).fillMaxHeight()) {
                    MediaGridItem(mediaItems[1], Modifier.weight(1f).fillMaxWidth(), onMediaClick)
                    Spacer(Modifier.height(spacing))
                    MediaGridItem(mediaItems[2], Modifier.weight(1f).fillMaxWidth(), onMediaClick)
                }
            }
            4 -> Column(Modifier.fillMaxWidth().aspectRatio(1.5f/1f)) {
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
            else -> Column(Modifier.fillMaxWidth().aspectRatio(1.5f/1f)) {
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
                        Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            Text(text = "+${mediaItems.size - 3}", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MediaGridItem(item: MediaUploadState, modifier: Modifier, onMediaClick: (MediaUploadState) -> Unit) {
    val model = remember(item.uploadedUrl, item.uri) { item.uploadedUrl ?: item.uri }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant).clickable { onMediaClick(item) }) {
        if (item.mimeType?.startsWith("video/") == true) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Video", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
            }
        } else {
            AsyncImage(model = model, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
