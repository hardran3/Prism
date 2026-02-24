package com.ryans.nostrshare

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ryans.nostrshare.nip55.*
import com.ryans.nostrshare.ui.DraftsHistoryContent
import com.ryans.nostrshare.ui.OnboardingScreen
import com.ryans.nostrshare.ui.AccountSelectorMenu
import com.ryans.nostrshare.ui.UserAvatar
import com.ryans.nostrshare.ui.theme.NostrShareTheme
import androidx.compose.material.icons.filled.PersonAdd

class MainActivity : ComponentActivity() {
    private val viewModel: ProcessTextViewModel by viewModels()

    // Library Contract: Get Public Key
    private val getPublicKeyLauncher = registerForActivityResult(GetPublicKeyContract()) { result ->
        result.onSuccess { pkResult ->
            viewModel.login(pkResult.pubkey, null, pkResult.packageName)
        }.onError { error ->
            Toast.makeText(this, "Login failed: ${error.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.isOnboarded) {
            viewModel.verifyScheduledNotes(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private var initialTab by mutableIntStateOf(0)

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra("START_SCHEDULING_SETUP", false)) {
            viewModel.startSchedulingOnboarding()
        }
        
        val selectPubkey = intent.getStringExtra("SELECT_PUBKEY")
        if (selectPubkey != null) {
            viewModel.switchUser(selectPubkey)
        }

        val openTab = intent.getIntExtra("OPEN_TAB", -1)
        if (openTab != -1) {
            initialTab = openTab
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        handleIntent(intent)
        
        setContent {
            NostrShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!viewModel.isOnboarded) {
                        OnboardingScreen(viewModel, getPublicKeyLauncher)
                    } else {
                        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                        var showAccountMenu by remember { mutableStateOf(false) }

                        val draftCount by viewModel.uiDrafts.collectAsState()
                        val scheduledCount by viewModel.uiScheduled.collectAsState()

                        Scaffold(
                            bottomBar = {
                                    Surface(
                                        tonalElevation = 3.dp,
                                        modifier = Modifier.height(72.dp).fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // 1. Add
                                            val isAddSelected = false
                                            IconButton(
                                                onClick = {
                                                    if (viewModel.isHapticEnabled()) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                    val intent = Intent(this@MainActivity, ProcessTextActivity::class.java)
                                                    intent.putExtra("LAUNCH_MODE", "NOTE")
                                                    startActivity(intent)
                                                },
                                                modifier = Modifier.weight(1f).padding(bottom = 6.dp)
                                            ) {
                                                Icon(Icons.Default.Add, "Add Note", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }

                                            // 2. Drafts
                                            val isDraftsSelected = initialTab == 0
                                            IconButton(
                                                onClick = {
                                                    initialTab = 0
                                                },
                                                modifier = Modifier.weight(1f).padding(bottom = 6.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(CircleShape)
                                                        .background(if (isDraftsSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    BadgedBox(
                                                        badge = {
                                                            if (draftCount.isNotEmpty()) {
                                                                Badge { Text(draftCount.size.toString()) }
                                                            }
                                                        }
                                                    ) {
                                                        Icon(Icons.Default.Edit, "Drafts", Modifier.size(32.dp), tint = if (isDraftsSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                            }

                                            // 3. Scheduled
                                            val isSchedSelected = initialTab == 1
                                            IconButton(
                                                onClick = {
                                                    initialTab = 1
                                                },
                                                modifier = Modifier.weight(1f).padding(bottom = 6.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(CircleShape)
                                                        .background(if (isSchedSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    BadgedBox(
                                                        badge = {
                                                            if (scheduledCount.isNotEmpty()) {
                                                                Badge { Text(scheduledCount.size.toString()) }
                                                            }
                                                        }
                                                    ) {
                                                        Icon(Icons.Default.Schedule, "Scheduled", Modifier.size(32.dp), tint = if (isSchedSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                            }

                                            // 4. History
                                            val isHistorySelected = initialTab == 2
                                            IconButton(
                                                onClick = {
                                                    initialTab = 2
                                                },
                                                modifier = Modifier.weight(1f).padding(bottom = 6.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(CircleShape)
                                                        .background(if (isHistorySelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.History, "History", Modifier.size(32.dp), tint = if (isHistorySelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }

                                            // 5. Settings
                                            IconButton(
                                                onClick = {
                                                    if (viewModel.isHapticEnabled()) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                                },
                                                modifier = Modifier.weight(1f).padding(bottom = 6.dp)
                                            ) {
                                                Icon(Icons.Default.Settings, "Settings", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }

                                            // 6. User
                                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                                IconButton(
                                                    onClick = {
                                                        if (viewModel.isHapticEnabled()) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                        showAccountMenu = true
                                                    },
                                                    modifier = Modifier.align(Alignment.Center).padding(bottom = 6.dp)
                                                ) {
                                                    UserAvatar(
                                                        pictureUrl = viewModel.userProfile?.pictureUrl,
                                                        size = 32.dp
                                                    )
                                                }

                                                Box(modifier = Modifier.align(Alignment.TopEnd).size(1.dp)) {
                                                    AccountSelectorMenu(
                                                        expanded = showAccountMenu,
                                                        onDismiss = { showAccountMenu = false },
                                                        vm = viewModel,
                                                        onAddAccount = {
                                                            showAccountMenu = false
                                                            if (Nip55.isSignerAvailable(this@MainActivity)) {
                                                                getPublicKeyLauncher.launch(
                                                                    GetPublicKeyContract.Input(
                                                                        permissions = listOf(Permission.signEvent(9802), Permission.signEvent(1), Permission.signEvent(24242), Permission.signEvent(20), Permission.signEvent(22)) 
                                                                    )
                                                                )
                                                            } else {
                                                                 Toast.makeText(this@MainActivity, "No NIP-55 Signer app found.", Toast.LENGTH_LONG).show()
                                                            }
                                                        },
                                                        onSwitchAccount = { pubkey ->
                                                            showAccountMenu = false
                                                            viewModel.switchUser(pubkey)
                                                        },
                                                        addAccountAtTop = true
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                        ) { padding ->
                            Box(modifier = Modifier.padding(padding)) {
                                com.ryans.nostrshare.ui.DraftsHistoryList(
                                    vm = viewModel,
                                    selectedTab = initialTab,
                                    onEditDraft = { draft ->
                                        val intent = Intent(this@MainActivity, ProcessTextActivity::class.java)
                                        intent.putExtra("DRAFT_ID", draft.id)
                                        startActivity(intent)
                                    },
                                    onEditAndReschedule = { draft ->
                                        val intent = Intent(this@MainActivity, ProcessTextActivity::class.java)
                                        intent.putExtra("DRAFT_ID", draft.id)
                                        intent.putExtra("RESCHEDULE", true)
                                        startActivity(intent)
                                    },
                                    onSaveToDrafts = { draft ->
                                        viewModel.unscheduleAndSaveToDrafts(draft)
                                    },
                                    onOpenInClient = { note ->
                                        val targetId = if (note.kind == 6 || note.kind == 16) {
                                            NostrUtils.getTargetEventIdFromRepost(note.originalEventJson ?: "") ?: note.id
                                        } else {
                                            note.id
                                        }
                                        val noteId = NostrUtils.eventIdToNote(targetId)
                                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("nostr:$noteId"))
                                        try {
                                            startActivity(intent)
                                        } catch (e: Exception) {
                                            Toast.makeText(this@MainActivity, "No Nostr client found.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onRepost = { draft ->
                                        val intent = Intent(this@MainActivity, ProcessTextActivity::class.java)
                                        intent.putExtra("REPOST_EVENT_JSON", draft.originalEventJson)
                                        intent.putExtra("LAUNCH_MODE", "REPOST")
                                        startActivity(intent)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
