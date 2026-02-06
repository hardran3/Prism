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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ryans.nostrshare.nip55.*
import com.ryans.nostrshare.ui.theme.NostrShareTheme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.ContentCopy

class ProcessTextActivity : ComponentActivity() {

    private val viewModel: ProcessTextViewModel by viewModels()

    // Library Contract: Sign Event
    private val signEventLauncher = registerForActivityResult(SignEventContract()) { result ->
        result.onSuccess { signed ->
             if (!signed.signedEventJson.isNullOrBlank()) {
                 viewModel.onEventSigned(signed.signedEventJson)
             } else if (!signed.signature.isNullOrBlank()) {
                 Toast.makeText(this, "Got signature, but event merge not impl.", Toast.LENGTH_SHORT).show()
             }
        }.onError { error ->
            Toast.makeText(this, "Signing failed: ${error.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Library Contract: Get Public Key
    private val getPublicKeyLauncher = registerForActivityResult(GetPublicKeyContract()) { result ->
        result.onSuccess { pkResult ->
             viewModel.login(pkResult.pubkey, null, pkResult.packageName)
             // Retry pending actions
             if (viewModel.postKind == ProcessTextViewModel.PostKind.MEDIA) {
                 if (viewModel.mediaUri != null && viewModel.uploadedMediaUrl == null) {
                      viewModel.initiateUploadAuth(this)
                 }
             }
        }.onError { error ->
            Toast.makeText(this, "Login failed: ${error.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Media Picker
    private val pickMediaLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
        if (uri != null) {
            val type = contentResolver.getType(uri) ?: "application/octet-stream"
            viewModel.onMediaSelected(this, uri, type)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        viewModel.checkDraft() // Check for saved work
        
        // ... (Keep existing Intent handling logic) ...
         if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (text != null) {
                viewModel.updateQuote(text)
                viewModel.onHighlightShared()
            }
        } else if (intent.action == Intent.ACTION_SEND) {
            // Handle STREAM for images/video
            if (intent.type?.startsWith("image/") == true || intent.type?.startsWith("video/") == true) {
                 @Suppress("DEPRECATION")
                 val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                 if (uri != null) {
                     val type = intent.type ?: contentResolver.getType(uri) ?: "application/octet-stream"
                     viewModel.onMediaSelected(this, uri, type)
                 }
            } else {
                // Existing text handling
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                // ... (Existing text extraction logic) ...
                val urlRegex = "https?://[^\\s]+".toRegex()
                val match = urlRegex.find(text)

                if (match != null) {
                     // ...
                     var url = match.value
                    if (url.contains("#:~:text=")) url = url.substringBefore("#:~:text=")
                    val content = text.replace(match.value, "").trim()
                    val cleanContent = if (content.startsWith("\"") && content.endsWith("\"")) content.removeSurrounding("\"") else content
                    
                    if (cleanContent.isNotBlank()) {
                        viewModel.updateQuote(cleanContent)
                        // If we have quoted content and a URL, treat as highlight
                        viewModel.onHighlightShared()
                    }
                    else if (!subject.isNullOrBlank()) viewModel.updateQuote(subject)
                    
                    viewModel.updateSource(UrlUtils.cleanUrl(url))
                } else {
                    viewModel.updateQuote(text)
                }
            }
        } else {
             // Default / Launcher Case
             val launchMode = intent.getStringExtra("LAUNCH_MODE")
             if (launchMode == "NOTE") {
                 viewModel.setKind(ProcessTextViewModel.PostKind.NOTE)
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
        val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
        
        // Auto-save Draft
        LaunchedEffect(vm.quoteContent, vm.sourceUrl, vm.postKind, vm.mediaUri, vm.uploadedMediaUrl) {
            if (vm.isDraftMonitoringActive) {
                kotlinx.coroutines.delay(1000) // Debounce 1s
                vm.saveDraft()
            }
        }

        // Draft Prompt

        
        // Auto-focus
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        // Close on success
        LaunchedEffect(vm.publishSuccess) {
            if (vm.publishSuccess == true) {
                 Toast.makeText(this@ProcessTextActivity, vm.publishStatus, Toast.LENGTH_SHORT).show()
                 finish()
            }
        }
        
        // Observe Signing Requests from VM
        val eventToSign by vm.eventToSign.collectAsState()
        LaunchedEffect(eventToSign) {
            eventToSign?.let { eventJson ->
                vm.pubkey?.let { pubkey ->
                     signEventLauncher.launch(
                        SignEventContract.Input(
                            eventJson = eventJson,
                            currentUser = pubkey, 
                            id = System.currentTimeMillis().toString()
                        )
                    )
                }
            }
        }
        
        // Handle Signed Result is in callback: vm.onSignedEventReceived -> vm.onEventSigned

        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(), // Fix: Resize Scaffold for keyboard
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        // User Avatar / Login - Large
                        IconButton(
                            modifier = Modifier.padding(start = 8.dp).size(48.dp),
                            onClick = {
                                if (Nip55.isSignerAvailable(this@ProcessTextActivity)) {
                                    getPublicKeyLauncher.launch(
                                        GetPublicKeyContract.Input(
                                            permissions = listOf(Permission.signEvent(9802), Permission.signEvent(1), Permission.signEvent(24242), Permission.signEvent(20), Permission.signEvent(22)) 
                                        )
                                    )
                                } else {
                                     Toast.makeText(this@ProcessTextActivity, "No NIP-55 Signer app found.", Toast.LENGTH_LONG).show()
                                }
                            }
                        ) {
                            if (vm.userProfile?.pictureUrl != null) {
                                coil.compose.AsyncImage(
                                    model = vm.userProfile!!.pictureUrl,
                                    contentDescription = "User Avatar",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.Person,
                                    contentDescription = "Login",
                                    modifier = Modifier.fillMaxSize().padding(8.dp)
                                )
                            }
                        }
                    },
                    title = { 
                        // Note Kind Dropdown
                        var showModeMenu by remember { mutableStateOf(false) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showModeMenu = true }.padding(8.dp)
                        ) {
                            Column {
                                Text(vm.postKind.label, style = MaterialTheme.typography.titleMedium)
                                if (vm.postKind == ProcessTextViewModel.PostKind.MEDIA) {
                                    Text("Media Upload", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Icon(Icons.Filled.ArrowDropDown, "Select Mode")
                            
                            DropdownMenu(expanded = showModeMenu, onDismissRequest = { showModeMenu = false }) {
                                vm.availableKinds.forEach { kind ->
                                    DropdownMenuItem(
                                        text = { Text(kind.label) },
                                        onClick = { 
                                            vm.setKind(kind)
                                            showModeMenu = false 
                                        }
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // Attach Media - Small
                        IconButton(onClick = { pickMediaLauncher.launch("*/*") }) {
                            Icon(Icons.Default.AddPhotoAlternate, "Attach Media") 
                        }

                        // Settings - Small
                        IconButton(onClick = {
                            startActivity(Intent(this@ProcessTextActivity, SettingsActivity::class.java))
                        }) {
                             Icon(Icons.Default.Settings, "Settings")
                        }
                    }
                )
            },
            bottomBar = {
                if (vm.isPublishing || vm.isUploading) {
                   BottomAppBar {
                       CircularProgressIndicator(modifier = Modifier.padding(start = 16.dp).size(24.dp))
                       Spacer(modifier = Modifier.width(16.dp))
                       val status = if (vm.isUploading) vm.uploadStatus else if (vm.isPublishing) vm.publishStatus else if (vm.uploadStatus.isNotBlank()) vm.uploadStatus else ""
                       if (status.isNotBlank()) {
                           Text(status, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                       }
                   }
                } else if (vm.showDraftPrompt) {
                    BottomAppBar {
                        Text(
                            text = "Unsaved draft found",
                            modifier = Modifier.weight(1f).padding(start = 16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { vm.discardDraft() }) {
                            Text("Discard")
                        }
                        Button(
                            onClick = { vm.applyDraft() },
                            modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Text("Resume")
                        }
                    }
                } else {
                    BottomAppBar(
                         actions = {
                            if (vm.publishSuccess == false || vm.uploadStatus.startsWith("Error") || vm.uploadStatus.startsWith("Upload failed")) {
                                Text(
                                    text = if (vm.uploadStatus.isNotBlank()) vm.uploadStatus else "Failed. Retry?", 
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(start = 16.dp)
                                )
                            }
                        },
                        floatingActionButton = {
                            FloatingActionButton(
                                onClick = {
                                    if (vm.pubkey == null) {
                                        if (Nip55.isSignerAvailable(this@ProcessTextActivity)) {
                                            getPublicKeyLauncher.launch(
                                                GetPublicKeyContract.Input(
                                                    permissions = listOf(Permission.signEvent(9802), Permission.signEvent(1), Permission.signEvent(24242), Permission.signEvent(20), Permission.signEvent(22)) 
                                                )
                                            )
                                        } else {
                                            Toast.makeText(this@ProcessTextActivity, "No NIP-55 Signer app found.", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        vm.currentSigningPurpose = ProcessTextViewModel.SigningPurpose.POST
                                        
                                        // If uploading is still in progress, we should wait or error.
                                        // If media is selected but not uploaded (and not currently uploading), start upload first?
                                        // For MVP, assume flow is: Select Media -> Auto Upload -> Sign Post.
                                        // If upload failed, user can retry by re-selecting or we add a retry button.
                                        
                                        if (vm.postKind == ProcessTextViewModel.PostKind.MEDIA) {
                                            if (vm.uploadedMediaUrl == null) {
                                                if (!vm.isUploading && vm.mediaUri != null) {
                                                     vm.initiateUploadAuth(this@ProcessTextActivity) // Retry upload
                                                     return@FloatingActionButton
                                                }
                                                Toast.makeText(this@ProcessTextActivity, "Please wait for media upload.", Toast.LENGTH_SHORT).show()
                                                return@FloatingActionButton
                                            }
                                        }

                                        val eventJson = vm.prepareEventJson()
                                        signEventLauncher.launch(
                                            SignEventContract.Input(
                                                eventJson = eventJson,
                                                currentUser = vm.pubkey!!, 
                                                id = System.currentTimeMillis().toString()
                                            )
                                        )
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Send, "Post")
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
                    //.imePadding() // Removed as Scaffold handles it
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                // Title Input (Media Only)
                if (vm.postKind == ProcessTextViewModel.PostKind.MEDIA) {
                    OutlinedTextField(
                        value = vm.mediaTitle,
                        onValueChange = { vm.mediaTitle = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Main Text Input
                OutlinedTextField(
                    value = vm.quoteContent,
                    onValueChange = { vm.updateQuote(it) },
                    label = { 
                        Text(when (vm.postKind) {
                            ProcessTextViewModel.PostKind.HIGHLIGHT -> "Highlighted Text"
                            ProcessTextViewModel.PostKind.NOTE -> "What's on your mind?"
                            else -> "Description"
                        })
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .focusRequester(focusRequester), // Focus request
                    textStyle = MaterialTheme.typography.bodyLarge
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // URL Field - visible if Highlight or if URL is present
                // Source URL Input - Hide for Kind 1 (Note)
                if (vm.postKind != ProcessTextViewModel.PostKind.NOTE) {
                    OutlinedTextField(
                        value = vm.sourceUrl,
                        onValueChange = { vm.sourceUrl = it },
                        label = { Text("Source URL") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true
                    )
                }
                
                // Media Preview Card
                if (vm.mediaUri != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    var showMediaDetail by remember { mutableStateOf(false) }
                    
                    // Sync with VM requests
                    LaunchedEffect(vm.showMediaDialog) {
                        if (vm.showMediaDialog) {
                            showMediaDetail = true
                            vm.showMediaDialog = false
                        }
                    }
                    
                    val clipboardManager = LocalClipboardManager.current
                    
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { 
                            if (!vm.isUploading) showMediaDetail = true 
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp)
                        ) {
                             Row(
                                 verticalAlignment = Alignment.CenterVertically,
                                 modifier = Modifier.fillMaxWidth()
                             ) {
                                  // Thumbnail - use URL or local URI
                                  val thumbModel = vm.uploadedMediaUrl ?: vm.mediaUri
                                  if (vm.mediaMimeType?.startsWith("image/") == true) {
                                      coil.compose.AsyncImage(
                                          model = thumbModel,
                                          contentDescription = "Preview (Tap to Copy URL)",
                                          modifier = Modifier
                                              .size(80.dp)
                                              .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                              .clickable {
                                                  vm.uploadedMediaUrl?.let { url ->
                                                      clipboardManager.setText(AnnotatedString(url))
                                                      Toast.makeText(this@ProcessTextActivity, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                                  }
                                              },
                                          contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                      )
                                  } else {
                                      // Video icon
                                      Box(
                                          modifier = Modifier
                                              .size(80.dp)
                                              .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                              .background(Color.Black)
                                              .clickable {
                                                  vm.uploadedMediaUrl?.let { url ->
                                                      clipboardManager.setText(AnnotatedString(url))
                                                      Toast.makeText(this@ProcessTextActivity, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                                  }
                                              },
                                          contentAlignment = Alignment.Center
                                      ) {
                                           Icon(Icons.Filled.PlayArrow, "Video", tint = Color.White)
                                      }
                                  }
                                  
                                  Spacer(modifier = Modifier.width(16.dp))
                                  
                                  Column(modifier = Modifier.weight(1f)) {
                                      if (vm.isUploading) {
                                          Text("Uploading Media...", style = MaterialTheme.typography.titleSmall)
                                          Spacer(modifier = Modifier.height(8.dp))
                                          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                          Spacer(modifier = Modifier.height(4.dp))
                                      } else {
                                          Row(verticalAlignment = Alignment.CenterVertically) {
                                              Text("Media Uploaded", style = MaterialTheme.typography.titleSmall)
                                              Spacer(modifier = Modifier.width(8.dp))
                                              // Server count badge
                                              if (vm.uploadTotalCount > 0) {
                                                  Surface(
                                                      shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                                      color = if (vm.uploadSuccessCount == vm.uploadTotalCount) 
                                                          MaterialTheme.colorScheme.primary 
                                                      else MaterialTheme.colorScheme.tertiary
                                                  ) {
                                                      Text(
                                                          "${vm.uploadSuccessCount}/${vm.uploadTotalCount}",
                                                          modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                          style = MaterialTheme.typography.labelSmall,
                                                          color = MaterialTheme.colorScheme.onPrimary
                                                      )
                                                  }
                                              }
                                          }
                                      }
                                      
                                      if (vm.uploadedMediaSize != null) {
                                          val sizeKb = vm.uploadedMediaSize!! / 1024
                                          Text("${sizeKb} KB", style = MaterialTheme.typography.bodySmall)
                                      }
                                      
                                      if (!vm.isUploading && vm.uploadedMediaHash != null) {
                                          Text("Hash: ${vm.uploadedMediaHash?.take(8)}...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                      }
                                  }
                                  
                                  if (!vm.isUploading) {
                                      if (vm.uploadedMediaUrl != null) {
                                          IconButton(onClick = { 
                                              vm.uploadedMediaUrl?.let { url ->
                                                  clipboardManager.setText(AnnotatedString(url))
                                                  Toast.makeText(this@ProcessTextActivity, "URL copied", Toast.LENGTH_SHORT).show()
                                              }
                                          }) {
                                              Icon(Icons.Default.ContentCopy, "Copy URL", tint = MaterialTheme.colorScheme.primary)
                                          }
                                      }
                                      
                                      IconButton(onClick = { vm.deleteMedia() }) {
                                          Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                      }
                                  }
                             }
                        }
                    }
                    
                    // Media Detail Dialog
                    if (showMediaDetail) {
                        MediaDetailDialog(
                            vm = vm,
                            onDismiss = { showMediaDetail = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MediaDetailDialog(
    vm: ProcessTextViewModel,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())
            ) {
                // Large Media Preview
                val mediaModel = vm.uploadedMediaUrl ?: vm.mediaUri
                if (vm.mediaMimeType?.startsWith("image/") == true) {
                    coil.compose.AsyncImage(
                        model = mediaModel,
                        contentDescription = "Media Preview",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                } else {
                    // Video placeholder
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, "Video", tint = Color.White, modifier = Modifier.size(64.dp))
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Status Header
                if (vm.isProcessingMedia) {
                     Row(verticalAlignment = Alignment.CenterVertically) {
                         CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                         Spacer(modifier = Modifier.width(8.dp))
                         Text(vm.uploadStatus, style = MaterialTheme.typography.bodyMedium)
                     }
                     Spacer(modifier = Modifier.height(16.dp))
                } else if (vm.isUploading) {
                     LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                     Text(vm.uploadStatus, style = MaterialTheme.typography.labelSmall)
                     Spacer(modifier = Modifier.height(16.dp))
                }
                
                // File Details
                Text("File Details", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                // Hash
                if (vm.uploadedMediaHash != null) {
                    Text("SHA-256 Hash:", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(
                            vm.uploadedMediaHash!!,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // Size
                if (vm.uploadedMediaSize != null) {
                    val sizeKb = vm.uploadedMediaSize!! / 1024
                    val sizeMb = sizeKb / 1024.0
                    val sizeText = if (sizeMb >= 1.0) String.format(java.util.Locale.US, "%.2f MB", sizeMb) else "$sizeKb KB"
                    Text("File Size: $sizeText", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // MIME Type
                if (vm.mediaMimeType != null) {
                    Text("MIME Type: ${vm.mediaMimeType}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // URL
                if (vm.uploadedMediaUrl != null) {
                    Text("URL:", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(
                            vm.uploadedMediaUrl!!,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Server Upload Results
                if (vm.uploadServerResults.isNotEmpty()) {
                    Text("Server Upload Status", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    vm.uploadServerResults.forEach { (server, success) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (success) Icons.Default.Check else Icons.Default.Close,
                                contentDescription = if (success) "Success" else "Failed",
                                tint = if (success) Color(0xFF4CAF50) else Color(0xFFF44336),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                server,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                
                // Server Delete Results
                 if (vm.deleteServerResults.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Server DELETE Status", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    vm.deleteServerResults.forEach { (server, success) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (success) Icons.Default.Delete else Icons.Default.Warning,
                                contentDescription = if (success) "Deleted" else "Failed",
                                tint = if (success) Color.Gray else Color(0xFFF44336),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                server,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (vm.isProcessingMedia || vm.isUploading || vm.isDeleting) {
                         // Busy state
                         /*Wait*/
                    } else if (vm.uploadedMediaUrl == null) {
                        // Not uploaded yet
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { vm.initiateUploadAuth(context) }) { Text("Upload") }
                    } else {
                        // Uploaded
                        TextButton(onClick = { vm.deleteMedia() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { 
                            Text("Delete") 
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onDismiss) { Text("Close") }
                    }
                }
            }
        }
    }
}

