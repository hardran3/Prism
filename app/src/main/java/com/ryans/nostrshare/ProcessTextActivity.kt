package com.ryans.nostrshare

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ryans.nostrshare.nip55.*
import com.ryans.nostrshare.ui.theme.NostrShareTheme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Link
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester

class ProcessTextActivity : ComponentActivity() {

    private val viewModel: ProcessTextViewModel by viewModels()

    // Library Contract: Sign Event
    private val signEventLauncher = registerForActivityResult(SignEventContract()) { result ->
        result.onSuccess { signed ->
             if (!signed.signedEventJson.isNullOrBlank()) {
                 viewModel.onSignedEventReceived(signed.signedEventJson)
             } else if (!signed.signature.isNullOrBlank()) {
                 // We have signature, but not event. 
                 Toast.makeText(this, "Got signature, but event merge not impl.", Toast.LENGTH_SHORT).show()
             }
        }.onError { error ->
            Toast.makeText(this, "Signing failed: ${error.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Library Contract: Get Public Key
    private val getPublicKeyLauncher = registerForActivityResult(GetPublicKeyContract()) { result ->
        result.onSuccess { pkResult ->
             // Call login to persist data
             // We don't have the original NPUB here from the library result easily, 
             // but we can pass null or regenerate it if needed. 
             // For now, passing hex as both or null for npub is okay as long as we store hex.
             viewModel.login(pkResult.pubkey, null, pkResult.packageName)
             
        }.onError { error ->
            Toast.makeText(this, "Login failed: ${error.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Extract text logic... (omitted for brevity, keep existing)
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (text != null) {
                viewModel.updateQuote(text)
            }
        } else if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)

            // Strategy:
            // 1. Check if 'subject' is the title, and 'text' is the URL (common in some apps).
            // 2. Check if 'text' contains a URL at the end (Chrome "Quote" format).
            // 3. Regex for URL.

            val urlRegex = "https?://[^\\s]+".toRegex() // improved regex [^\s]+ instead of \S+ just to be explicit
            val match = urlRegex.find(text)
            
            if (match != null) {
                var url = match.value
                // Remove text fragment if present (Chrome adds #:~:text=...)
                if (url.contains("#:~:text=")) {
                    url = url.substringBefore("#:~:text=")
                }

                // Remove the URL from the text to get the content
                val content = text.replace(match.value, "").trim()
                
                // If content is wrapped in quotes (Chrome does this: "Selected Text" https://...), remove them.
                val cleanContent = if (content.startsWith("\"") && content.endsWith("\"")) {
                    content.removeSurrounding("\"")
                } else {
                    content
                }

                if (cleanContent.isNotBlank()) {
                    viewModel.updateQuote(cleanContent)
                } else if (!subject.isNullOrBlank()) {
                     // If text was just a URL, maybe subject is the content?
                     viewModel.updateQuote(subject)
                }
                
                // Clean the URL of tracking parameters
                val cleanUrl = UrlUtils.cleanUrl(url)
                
                viewModel.updateSource(cleanUrl)
            } else {
                // No URL found, treat all as content
                viewModel.updateQuote(text)
            }
        } else {
             // Default / Launcher Case
             val launchMode = intent.getStringExtra("LAUNCH_MODE")
             if (launchMode == "NOTE") {
                 viewModel.isHighlightMode = false // Default to note for launcher
             }
        }

        setContent {
            NostrShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ShareScreen(viewModel)
                }
            }
        }
    }
    
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    fun ShareScreen(vm: ProcessTextViewModel) {
        val title = if (vm.isHighlightMode) "New Highlight" else "New Note"
        val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
        
        // Auto-focus the text field when screen opens
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
        
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    actions = {
                        // Toggle Mode Action
                        IconButton(onClick = { vm.isHighlightMode = !vm.isHighlightMode }) {
                             Icon(
                                 imageVector = if (vm.isHighlightMode) androidx.compose.material.icons.Icons.Default.Lightbulb else androidx.compose.material.icons.Icons.Default.Edit,
                                 contentDescription = "Toggle Mode",
                                 tint = if (vm.isHighlightMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                             )
                        }
                        
                        // User Avatar Action
                        IconButton(
                            onClick = {
                                if (Nip55.isSignerAvailable(this@ProcessTextActivity)) {
                                    getPublicKeyLauncher.launch(
                                        GetPublicKeyContract.Input(
                                            permissions = listOf(Permission.signEvent(9802), Permission.signEvent(1)) 
                                        )
                                    )
                                }
                            }
                        ) {
                            if (vm.userProfile?.pictureUrl != null) {
                                coil.compose.AsyncImage(
                                    model = vm.userProfile!!.pictureUrl,
                                    contentDescription = "User Avatar",
                                    modifier = Modifier.fillMaxSize().padding(6.dp).clip(CircleShape)
                                )
                            } else {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.Person,
                                    contentDescription = "Switch User"
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (vm.isPublishing) {
                   // Show progress in bottom bar
                   BottomAppBar {
                       CircularProgressIndicator(modifier = Modifier.padding(start = 16.dp).size(24.dp))
                       Spacer(modifier = Modifier.width(16.dp))
                       Text(vm.publishStatus, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                   }
                } else {
                    BottomAppBar(
                        actions = {
                            // Link Icon / Field toggle could go here, but for now let's keep it simple.
                            // Maybe just a character count later?
                        },
                        floatingActionButton = {
                            FloatingActionButton(
                                onClick = {
                                    if (vm.pubkey == null) {
                                        // Trigger Login
                                        if (Nip55.isSignerAvailable(this@ProcessTextActivity)) {
                                            getPublicKeyLauncher.launch(
                                                GetPublicKeyContract.Input(
                                                    permissions = listOf(Permission.signEvent(9802), Permission.signEvent(1)) 
                                                )
                                            )
                                        } else {
                                            Toast.makeText(this@ProcessTextActivity, "No NIP-55 Signer app found.", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        // Sign & Post
                                        val eventJson = vm.prepareEventJson()
                                        // vm.lastEventJson = eventJson // Removed debug field
                                        
                                        signEventLauncher.launch(
                                            SignEventContract.Input(
                                                eventJson = eventJson,
                                                currentUser = vm.pubkey!!, 
                                                id = System.currentTimeMillis().toString()
                                            )
                                        )
                                    }
                                },
                                containerColor = BottomAppBarDefaults.bottomAppBarFabColor,
                                elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Send, 
                                    contentDescription = "Post" 
                                )
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            // Main Content
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding() // Responsiveness to keyboard
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                // Main Text Input
                OutlinedTextField(
                    value = vm.quoteContent,
                    onValueChange = { vm.updateQuote(it) },
                    label = { Text(if (vm.isHighlightMode) "Highlighted Text" else "What's on your mind?") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .focusRequester(focusRequester), // Focus request
                    textStyle = MaterialTheme.typography.bodyLarge
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // URL Field - visible if Highlight or if URL is present
                if (vm.isHighlightMode || vm.sourceUrl.isNotBlank()) {
                    OutlinedTextField(
                        value = vm.sourceUrl,
                        onValueChange = { vm.updateSource(it) },
                        label = { Text("Source URL") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Filled.Link, "URL") },
                        singleLine = true
                    )
                }
            }
        }
    }
}
