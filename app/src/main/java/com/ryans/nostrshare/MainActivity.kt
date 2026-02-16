package com.ryans.nostrshare

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.ryans.nostrshare.ui.theme.NostrShareTheme

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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (intent.getBooleanExtra("START_SCHEDULING_SETUP", false)) {
            viewModel.startSchedulingOnboarding()
        }
        
        setContent {
            NostrShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!viewModel.isOnboarded) {
                        OnboardingScreen(viewModel, getPublicKeyLauncher)
                    } else {
                        Scaffold(
                            topBar = {
                                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                                CenterAlignedTopAppBar(
                                    navigationIcon = {
                                        IconButton(
                                            modifier = Modifier.padding(start = 8.dp).size(40.dp),
                                            onClick = {
                                                if (viewModel.isHapticEnabled()) {
                                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                }
                                                if (Nip55.isSignerAvailable(this@MainActivity)) {
                                                    getPublicKeyLauncher.launch(
                                                        GetPublicKeyContract.Input(
                                                            permissions = listOf(Permission.signEvent(9802), Permission.signEvent(1), Permission.signEvent(24242), Permission.signEvent(20), Permission.signEvent(22)) 
                                                        )
                                                    )
                                                } else {
                                                     Toast.makeText(this@MainActivity, "No NIP-55 Signer app found.", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        ) {
                                            if (viewModel.userProfile?.pictureUrl != null) {
                                                AsyncImage(
                                                    model = viewModel.userProfile!!.pictureUrl,
                                                    contentDescription = "User Avatar",
                                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                                    contentScale = ContentScale.Crop
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Person,
                                                    contentDescription = "Login",
                                                    modifier = Modifier.fillMaxSize().padding(8.dp)
                                                )
                                            }
                                        }
                                    },
                                    title = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_prism_triangle),
                                            contentDescription = "Prism",
                                            modifier = Modifier.size(32.dp),
                                            tint = Color.Unspecified
                                        )
                                    },
                                    actions = {
                                        IconButton(onClick = {
                                            if (viewModel.isHapticEnabled()) {
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            }
                                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                        }) {
                                            Icon(Icons.Default.Settings, "Settings")
                                        }
                                    }
                                )
                            },
                            floatingActionButton = {
                                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                                FloatingActionButton(
                                    onClick = {
                                        if (viewModel.isHapticEnabled()) {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        }
                                        val intent = Intent(this@MainActivity, ProcessTextActivity::class.java)
                                        intent.putExtra("LAUNCH_MODE", "NOTE")
                                        startActivity(intent)
                                    },
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ) {
                                    Icon(Icons.Default.Add, "New Note")
                                }
                            }
                        ) { padding ->
                            Box(modifier = Modifier.padding(padding)) {
                                DraftsHistoryContent(
                                    vm = viewModel,
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
                                        viewModel.cancelScheduledNote(draft)
                                        // No need to navigate if we just want to move it to drafts
                                        // But DraftsHistoryContent handles it via vm calls.
                                    },
                                    onOpenInClient = { eventId ->
                                        val noteId = NostrUtils.eventIdToNote(eventId)
                                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("nostr:$noteId"))
                                        try {
                                            startActivity(intent)
                                        } catch (e: Exception) {
                                            Toast.makeText(this@MainActivity, "No Nostr client found.", Toast.LENGTH_SHORT).show()
                                        }
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
