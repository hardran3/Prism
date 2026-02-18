package com.ryans.nostrshare.ui

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
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
    onOpenInClient: (String) -> Unit
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
                    onOpenInClient = onOpenInClient
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
    onOpenInClient: (String) -> Unit
) {
    val drafts by vm.drafts.collectAsState(initial = emptyList<Draft>())
    val allScheduled by vm.allScheduled.collectAsState(initial = emptyList<Draft>())
    val scheduledHistory by vm.scheduledHistory.collectAsState(initial = emptyList<Draft>())
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }
    var showMediaDetail by remember { mutableStateOf<MediaUploadState?>(null) }

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
                    text = { Text("History (${scheduledHistory.size})", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            when (selectedTab) {
                0 -> DraftList(drafts, onSelect = onEditDraft, onDelete = {
                    vm.deleteDraft(it.id)
                }, vm = vm, onMediaClick = { showMediaDetail = it })
                1 -> ScheduledList(allScheduled, vm = vm, onCancel = {
                    vm.cancelScheduledNote(it)
                }, onEditAndReschedule = onEditAndReschedule, onSaveToDrafts = onSaveToDrafts, onMediaClick = { showMediaDetail = it })
                2 -> HistoryList(
                    history = scheduledHistory,
                    vm = vm,
                    onClearHistory = { vm.clearScheduledHistory() },
                    onOpenInClient = onOpenInClient,
                    onMediaClick = { showMediaDetail = it }
                )
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
}

@Composable
fun DraftList(drafts: List<Draft>, onSelect: (Draft) -> Unit, onDelete: (Draft) -> Unit, vm: ProcessTextViewModel, onMediaClick: (MediaUploadState) -> Unit) {
    if (drafts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No saved drafts", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(drafts, key = { it.id }) { draft ->
                DraftItem(draft, onSelect, onDelete, vm, onMediaClick)
            }
        }
    }
}

@Composable
fun DraftItem(draft: Draft, onSelect: (Draft) -> Unit, onDelete: (Draft) -> Unit, vm: ProcessTextViewModel, onMediaClick: (MediaUploadState) -> Unit) {
    val date = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(draft.lastEdited))
    
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
            val filteredContent = remember(draft.content) { filterImageUrls(draft.content) }
            
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

            val finalContent = remember(draft.content, linkMetadata, nostrEvent, combinedMediaItems) {
                var content = filteredContent
                
                // Hide Link Preview URL only if it has a title or image
                if (linkMetadata != null && firstUrl != null && (linkMetadata!!.title != null || linkMetadata!!.imageUrl != null)) {
                    content = content.replace(firstUrl, "").trim()
                }

                if (nostrEvent != null && nostrEntity != null) {
                    content = content.replace(nostrEntity.bech32, "").trim()
                }
                
                val pattern = "(https?://[^\\s]+(?:\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp|\\.bmp|\\.svg|\\.mp4|\\.mov|\\.webm)(?:\\?[^\\s]*)?)".toRegex(RegexOption.IGNORE_CASE)
                combinedMediaItems.forEach { item ->
                    item.uploadedUrl?.let { url ->
                        if (pattern.matches(url)) {
                            content = content.replace(url, "").trim()
                        }
                    }
                }
                content
            }

            if (finalContent.isNotBlank()) {
                Row(modifier = Modifier.height(IntrinsicSize.Max), verticalAlignment = Alignment.CenterVertically) {
                    if (draft.kind == 9802) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(4.dp)
                                .padding(vertical = 2.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = finalContent,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            // Mutually Exclusive Previews: Link -> Nostr -> Fallback
            if (linkMetadata != null && (linkMetadata!!.title != null || linkMetadata!!.imageUrl != null)) {
                Spacer(Modifier.height(8.dp))
                LinkPreviewCard(linkMetadata!!)
            } else if (nostrEvent != null) {
                Spacer(Modifier.height(8.dp))
                NostrEventPreview(nostrEvent!!, vm)
            } else if (!draft.sourceUrl.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Source: ${draft.sourceUrl}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            
            // Media Thumbnails for Draft
            if (combinedMediaItems.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                MediaThumbnailRow(combinedMediaItems, onMediaClick)
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                IconButton(onClick = { onDelete(draft) }) {
                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun ScheduledList(
    pending: List<Draft>,
    vm: ProcessTextViewModel,
    onCancel: (Draft) -> Unit,
    onEditAndReschedule: (Draft) -> Unit,
    onSaveToDrafts: (Draft) -> Unit,
    onMediaClick: (MediaUploadState) -> Unit
) {
    if (pending.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No pending scheduled notes", style = MaterialTheme.typography.bodyMedium)
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
                            IconButton(onClick = { onCancel(note) }) {
                                Icon(Icons.Default.Delete, "Cancel", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun HistoryList(
    history: List<Draft>,
    vm: ProcessTextViewModel,
    onClearHistory: () -> Unit,
    onOpenInClient: (String) -> Unit,
    onMediaClick: (MediaUploadState) -> Unit
) {
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No history yet", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Completed Posts", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClearHistory) {
                    Text("Clear All", style = MaterialTheme.typography.labelSmall)
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(history, key = { it.id }) { note ->
                    UnifiedPostItem(
                        note = note,
                        vm = vm,
                        onMediaClick = onMediaClick,
                        actions = {
                            if (note.publishedEventId != null && note.publishError == null) {
                                TextButton(onClick = { onOpenInClient(note.publishedEventId!!) }) {
                                    Icon(Icons.Default.Link, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Open")
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun UnifiedPostItem(note: Draft, vm: ProcessTextViewModel, onMediaClick: (MediaUploadState) -> Unit, actions: @Composable () -> Unit) {
    val date = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(note.scheduledAt ?: note.lastEdited))
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
        isSuccess -> if (isSystemInDarkTheme()) androidx.compose.ui.graphics.Color(0xFF1B5E20).copy(alpha = 0.4f) else androidx.compose.ui.graphics.Color(0xFFE8F5E9)
        isCompleted -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    }

    val statusColor = when {
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
                        Text(NostrUtils.getKindLabel(note.kind, note.content), style = MaterialTheme.typography.labelSmall)
                    }
                    
                    profile?.name?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }

                    Spacer(Modifier.height(4.dp))
                    val filteredContent = remember(note.content) { filterImageUrls(note.content) }
                    
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
                    var nostrEvent by remember(note.id, note.originalEventJson) { 
                        mutableStateOf<org.json.JSONObject?>(
                            note.originalEventJson?.let { 
                                try { org.json.JSONObject(it) } catch (e: Exception) { null } 
                            }
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
                        if (linkMetadata != null) return@LaunchedEffect // Use cache
                        
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
                    val combinedMediaItems = remember(note.mediaJson, note.content) {
                        val attached = parseMediaJson(note.mediaJson)
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

                    val finalContent = remember(filteredContent, linkMetadata, nostrEvent, combinedMediaItems) {
                        var content = filteredContent
                        
                        // Hide Link Preview URL only if it has a title or image
                        if (linkMetadata != null && firstUrl != null && (linkMetadata!!.title != null || linkMetadata!!.imageUrl != null)) {
                            content = content.replace(firstUrl, "").trim()
                        }
                        
                        if (nostrEvent != null && nostrEntity != null) {
                            content = content.replace(nostrEntity.bech32, "").trim()
                        }
                        
                        val pattern = "(https?://[^\\s]+(?:\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp|\\.bmp|\\.svg|\\.mp4|\\.mov|\\.webm)(?:\\?[^\\s]*)?)".toRegex(RegexOption.IGNORE_CASE)
                        // Also hide URLs that are now in the thumbnail row
                        combinedMediaItems.forEach { item ->
                            item.uploadedUrl?.let { url ->
                                // Only hide direct media links, not every link that might be in a preview card
                                if (pattern.matches(url)) {
                                    content = content.replace(url, "").trim()
                                }
                            }
                        }
                        content
                    }

                    if (finalContent.isNotBlank()) {
                        Row(modifier = Modifier.height(IntrinsicSize.Max), verticalAlignment = Alignment.CenterVertically) {
                            if (note.kind == 9802) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(4.dp)
                                        .padding(vertical = 2.dp)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(
                                text = finalContent,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                            // Mutually Exclusive Previews: Link -> Nostr -> Fallback
                            if (linkMetadata != null && (linkMetadata!!.title != null || linkMetadata!!.imageUrl != null)) {
                                Spacer(Modifier.height(8.dp))
                                LinkPreviewCard(linkMetadata!!)
                            } else if (nostrEvent != null) {
                                Spacer(Modifier.height(8.dp))
                                NostrEventPreview(nostrEvent!!, vm)
                            } else if (!note.sourceUrl.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Source: ${note.sourceUrl}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                    
                    // Media Thumbnails for UnifiedPostItem
                    if (combinedMediaItems.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        MediaThumbnailRow(combinedMediaItems, onMediaClick)
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
fun NostrEventPreview(event: org.json.JSONObject, vm: ProcessTextViewModel) {
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
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
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
                // STATIC ICON FOR VIDEOS (No Coil/Download)
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
