package com.ryans.nostrshare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ryans.nostrshare.ui.theme.NostrShareTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
    val tabs = listOf("General", "Blossom")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            }
        }
    }
}

@Composable
fun GeneralSettingsTab(repo: SettingsRepository) {
    var alwaysKind1 by remember { mutableStateOf(repo.isAlwaysUseKind1()) }
    var optimizeMedia by remember { mutableStateOf(repo.isOptimizeMediaEnabled()) }
    var blastrEnabled by remember { mutableStateOf(repo.isBlastrEnabled()) }

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
                    blastrEnabled = it
                    repo.setBlastrEnabled(it)
                }
            )
        }
    }
}

@Composable
fun BlossomSettingsTab(repo: SettingsRepository) {
    var servers by remember { mutableStateOf(repo.getBlossomServers()) }
    var showAddDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(servers) { server ->
                BlossomServerCard(
                    server = server,
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
                    }
                )
            }
            
            // Spacer for FAB
            item { Spacer(modifier = Modifier.height(72.dp)) }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Server")
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
    server: BlossomServer,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = server.url, style = MaterialTheme.typography.bodyLarge)
            }
            
            Switch(
                checked = server.enabled,
                onCheckedChange = onToggle
            )
            
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
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
