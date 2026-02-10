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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ryans.nostrshare.ProcessTextViewModel
import com.ryans.nostrshare.data.Draft
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DraftsDialog(
    vm: ProcessTextViewModel,
    onDismiss: () -> Unit
) {
    val drafts by vm.drafts.collectAsState(initial = emptyList())
    val allScheduled by vm.allScheduled.collectAsState(initial = emptyList())
    val scheduledHistory by vm.scheduledHistory.collectAsState(initial = emptyList())
    var selectedTab by remember { mutableIntStateOf(0) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, "Close")
                        }
                        Text(
                            "Manage Drafts & History",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Drafts (${drafts.size})") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Scheduled (${allScheduled.size})") }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("History (${scheduledHistory.size})") }
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                when (selectedTab) {
                    0 -> DraftList(drafts, onSelect = {
                        vm.loadDraft(it)
                        onDismiss()
                    }, onDelete = {
                        vm.deleteDraft(it.id)
                    })
                    1 -> ScheduledList(allScheduled, onCancel = {
                        vm.cancelScheduledNote(it)
                    })
                    2 -> HistoryList(
                        history = scheduledHistory,
                        onClearHistory = { vm.clearScheduledHistory() }
                    )
                }
            }
        }
    }
}

@Composable
fun DraftList(drafts: List<Draft>, onSelect: (Draft) -> Unit, onDelete: (Draft) -> Unit) {
    if (drafts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No saved drafts", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(drafts) { draft ->
                DraftItem(draft, onSelect, onDelete)
            }
        }
    }
}

@Composable
fun getKindLabel(kind: Int): String {
    return when (kind) {
        1 -> "Text Note"
        9802 -> "Highlight"
        0, 20, 22, 1063 -> "Media Note"
        else -> "Kind $kind"
    }
}

@Composable
fun DraftItem(draft: Draft, onSelect: (Draft) -> Unit, onDelete: (Draft) -> Unit) {
    val date = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(draft.lastEdited))
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onSelect(draft) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(getKindLabel(draft.kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(date, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = draft.content.ifBlank { "[No text content]" },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            if (draft.sourceUrl.isNotBlank()) {
                Text(draft.sourceUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
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
    onCancel: (Draft) -> Unit
) {
    if (pending.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No pending scheduled notes", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(pending) { note ->
                ScheduledItem(note, onCancel)
            }
        }
    }
}

@Composable
fun HistoryList(
    history: List<Draft>,
    onClearHistory: () -> Unit
) {
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No history yet", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
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
                    ScheduledItem(note, {})
                }
            }
        }
    }
}

@Composable
fun ScheduledItem(note: Draft, onCancel: (Draft) -> Unit) {
    val date = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(note.scheduledAt ?: 0L))
    val isCompleted = note.isCompleted
    val isSuccess = isCompleted && note.publishError == null
    
    // Adaptive colors: Use tokens from MaterialTheme.colorScheme for theme awareness
    val containerColor = when {
        isSuccess -> {
           // Success: A subtle green that adapts to dark/light
           if (isSystemInDarkTheme()) androidx.compose.ui.graphics.Color(0xFF1B5E20).copy(alpha = 0.4f)
           else androidx.compose.ui.graphics.Color(0xFFE8F5E9)
        }
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
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isCompleted) {
                            if (isSuccess) Icons.Default.Check
                            else Icons.Default.Warning
                        } else Icons.Default.Schedule,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = statusColor
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(date, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Text(getKindLabel(note.kind), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = note.content.ifBlank { "[No text content]" },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            
            if (isCompleted && note.publishError != null) {
                Text(
                    text = "Error: ${note.publishError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!isCompleted) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = { onCancel(note) }) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel", style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        if (isSuccess) "Sent" else "Failed",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }
        }
    }
}
