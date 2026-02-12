package com.ryans.nostrshare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
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
    val drafts by vm.drafts.collectAsState(initial = emptyList<Draft>())
    val allScheduled by vm.allScheduled.collectAsState(initial = emptyList<Draft>())
    val scheduledHistory by vm.scheduledHistory.collectAsState(initial = emptyList<Draft>())
    var selectedTab by remember { mutableIntStateOf(0) }
    var showMediaDetail by remember { mutableStateOf<MediaUploadState?>(null) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                    TabRow(
                        selectedTabIndex = selectedTab,
                        modifier = Modifier.weight(1f),
                        divider = {} // Remove default divider for cleaner look on same line
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Drafts (${drafts.size})", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
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
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                when (selectedTab) {
                    0 -> DraftList(drafts, onSelect = {
                        vm.loadDraft(it)
                        onDismiss()
                    }, onDelete = {
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
}

@Composable
fun DraftList(drafts: List<Draft>, onSelect: (Draft) -> Unit, onDelete: (Draft) -> Unit, vm: ProcessTextViewModel, onMediaClick: (MediaUploadState) -> Unit) {
    if (drafts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No saved drafts", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(drafts) { draft ->
                DraftItem(draft, onSelect, onDelete, vm, onMediaClick)
            }
        }
    }
}

@Composable
fun DraftItem(draft: Draft, onSelect: (Draft) -> Unit, onDelete: (Draft) -> Unit, vm: ProcessTextViewModel, onMediaClick: (MediaUploadState) -> Unit) {
    val date = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(draft.lastEdited))
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onSelect(draft) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(NostrUtils.getKindLabel(draft.kind, draft.content), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(date, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            val filteredContent = remember(draft.content) { filterImageUrls(draft.content) }
            if (filteredContent.isNotBlank()) {
                Text(
                    text = filteredContent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            // Media Thumbnails for Draft
            val mediaItems = remember(draft.mediaJson) { parseMediaJson(draft.mediaJson) }
            if (mediaItems.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                MediaThumbnailRow(mediaItems, onMediaClick)
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
            items(pending) { note ->
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
                items(history) { note ->
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                            Text(date, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(NostrUtils.getKindLabel(note.kind, note.content), style = MaterialTheme.typography.labelSmall)
                    }
                    
                    profile?.name?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }

                    Spacer(Modifier.height(4.dp))
                    val filteredContent = remember(note.content) { filterImageUrls(note.content) }
                    if (filteredContent.isNotBlank()) {
                        Text(
                            text = filteredContent,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    
                    // Media Thumbnails for UnifiedPostItem
                    val mediaItems = remember(note.mediaJson) { parseMediaJson(note.mediaJson) }
                    if (mediaItems.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        MediaThumbnailRow(mediaItems, onMediaClick)
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

fun filterImageUrls(content: String): String {
    val imageUrlPattern = "(https?://[^\\s]+(?:\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp|\\.bmp|\\.svg)(?:\\?[^\\s]*)?)".toRegex(RegexOption.IGNORE_CASE)
    return content.replace(imageUrlPattern, "").trim()
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
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .size(60.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onMediaClick(item) },
                contentScale = ContentScale.Crop
            )
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
