package com.ryans.nostrshare

import android.content.Context
import androidx.lifecycle.ViewModel
import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.UUID
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Job
import android.net.Uri
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import com.ryans.nostrshare.data.Draft
import com.ryans.nostrshare.utils.HistorySyncManager
import com.ryans.nostrshare.utils.UnicodeStylizer

enum class OnboardingStep {
    WELCOME,
    SYNCING,
    SERVER_SELECTION,
    SCHEDULING_CONFIG
}
class ProcessTextViewModel : ViewModel() {
    enum class HistoryFilter {
        NOTE, HIGHLIGHT, MEDIA, REPOST, QUOTE, ARTICLE,
        HAS_MEDIA, IMAGE, GIF, VIDEO
    }
    var activeHistoryFilters = mutableStateListOf<HistoryFilter>()
    var activeHashtags = mutableStateListOf<String>()
    var isHashtagManageMode by mutableStateOf(false)

    fun toggleHistoryFilter(filter: HistoryFilter) {
        if (activeHistoryFilters.contains(filter)) {
            activeHistoryFilters.remove(filter)
        } else {
            activeHistoryFilters.add(filter)
        }
    }

    fun toggleHashtag(tag: String) {
        if (activeHashtags.contains(tag)) {
            activeHashtags.remove(tag)
        } else {
            activeHashtags.add(tag)
        }
    }

    // Advanced selection-aware content state
    var contentValue by mutableStateOf(TextFieldValue(""))

    // Legacy bridge for existing logic
    var quoteContent: String
        get() = contentValue.text
        set(value) { contentValue = contentValue.copy(text = value) }

    var sourceUrl by mutableStateOf("")
    var mediaTitle by mutableStateOf("")
    var isPublishing by mutableStateOf(false)
    var publishStatus by mutableStateOf("")
    var pubkey by mutableStateOf<String?>(null)
    var npub by mutableStateOf<String?>(null)
    var signerPackageName by mutableStateOf<String?>(null)
    var userProfile by mutableStateOf<UserProfile?>(null)

    // Long-form state
    var articleTitle by mutableStateOf("")
    var articleSummary by mutableStateOf("")
    var articleIdentifier by mutableStateOf<String?>(null)

    // Highlight Metadata (NIP-84)
    var highlightEventId by mutableStateOf<String?>(null)
    var highlightAuthor by mutableStateOf<String?>(null)
    var highlightKind by mutableStateOf<Int?>(null)
    var highlightIdentifier by mutableStateOf<String?>(null)
    var highlightRelays = mutableStateListOf<String>()
    var originalEventJson by mutableStateOf<String?>(null)
    private var savedContentBuffer by mutableStateOf("")

    // Onboarding State
    var isOnboarded by mutableStateOf(false)
    var isSchedulingEnabled by mutableStateOf(false)
    var currentOnboardingStep by mutableStateOf(OnboardingStep.WELCOME)
    var isSyncingServers by mutableStateOf(false)
    var isFullHistoryEnabled by mutableStateOf(false)

    // Search state
    var searchQuery by mutableStateOf("")

    // Delegated Sync State
    val isFetchingRemoteHistory: Boolean
        get() = HistorySyncManager.isSyncing.value

    val currentSyncRelay: String?
        get() = HistorySyncManager.currentRelay.value

    val syncDiscoveryCount: Int
        get() = HistorySyncManager.discoveryCount.value

    // Cached GUI data (Web Preview)
    var previewTitle by mutableStateOf<String?>(null)
    var previewDescription by mutableStateOf<String?>(null)
    var previewImageUrl by mutableStateOf<String?>(null)
    var previewSiteName by mutableStateOf<String?>(null)

    // Configured Blossom servers for the current session
    var blossomServers by mutableStateOf<List<BlossomServer>>(emptyList())

    fun toggleBlossomServer(url: String) {
        blossomServers = blossomServers.map {
            if (it.url == url) it.copy(enabled = !it.enabled) else it
        }
    }

    // Active Accounts
    var knownAccounts = mutableStateListOf<Account>()

    // User Profile Cache (pubkey -> profile)
    var usernameCache = mutableStateMapOf<String, UserProfile>()

    // Follow List for prioritization
    var followedPubkeys by mutableStateOf<Set<String>>(emptySet())

    private val prefs by lazy {
        NostrShareApp.getInstance().getSharedPreferences("nostr_share_prefs", Context.MODE_PRIVATE)
    }
    val settingsRepository by lazy { SettingsRepository(NostrShareApp.getInstance()) }
    val relayManager by lazy { RelayManager(NostrShareApp.getInstance().client, settingsRepository) }
    private val draftDao by lazy { NostrShareApp.getInstance().database.draftDao() }

    // --- Markdown Engine Logic ---
    fun applyUnicodeStyle(style: UnicodeStylizer.Style) {
        val selection = contentValue.selection
        val text = contentValue.text

        if (selection.collapsed) return

        val selStart = selection.min.coerceIn(0, text.length)
        val selEnd = selection.max.coerceIn(selStart, text.length)

        val selectedText = text.substring(selStart, selEnd)
        // First, normalize the text back to plain ASCII
        val normalizedText = UnicodeStylizer.normalize(selectedText)
        // Then, apply the new style
        val styledText = UnicodeStylizer.stylize(normalizedText, style)

        val newText = text.replaceRange(selStart, selEnd, styledText)

        contentValue = contentValue.copy(
            text = newText,
            selection = TextRange(selStart, selStart + styledText.length)
        )
    }

    fun applyStrikethrough() {
        val selection = contentValue.selection
        val text = contentValue.text
        if (selection.collapsed) return

        val selStart = selection.min.coerceIn(0, text.length)
        val selEnd = selection.max.coerceIn(selStart, text.length)

        val selectedText = text.substring(selStart, selEnd)
        val styledText = UnicodeStylizer.toggleStrikethrough(selectedText)

        val newText = text.replaceRange(selStart, selEnd, styledText)
        contentValue = contentValue.copy(
            text = newText,
            selection = TextRange(selStart, selStart + styledText.length)
        )
    }

    fun applyUnderline() {
        val selection = contentValue.selection
        val text = contentValue.text
        if (selection.collapsed) return

        val selStart = selection.min.coerceIn(0, text.length)
        val selEnd = selection.max.coerceIn(selStart, text.length)

        val selectedText = text.substring(selStart, selEnd)
        val styledText = UnicodeStylizer.toggleUnderline(selectedText)

        val newText = text.replaceRange(selStart, selEnd, styledText)
        contentValue = contentValue.copy(
            text = newText,
            selection = TextRange(selStart, selStart + styledText.length)
        )
    }

    fun applyInlineMarkdown(symbol: String) {
        val selection = contentValue.selection
        val text = contentValue.text
        val selStart = selection.min.coerceIn(0, text.length)
        val selEnd = selection.max.coerceIn(selStart, text.length)

        if (selection.collapsed) {
            val newText = text.substring(0, selStart) + symbol + symbol + text.substring(selEnd)
            contentValue = contentValue.copy(
                text = newText,
                selection = TextRange(selStart + symbol.length)
            )
        } else {
            val selectedText = text.substring(selStart, selEnd)
            val isAlreadyWrapped = selectedText.startsWith(symbol) && selectedText.endsWith(symbol)

            val newText = if (isAlreadyWrapped) {
                text.substring(0, selStart) + selectedText.removeSurrounding(symbol) + text.substring(selEnd)
            } else {
                text.substring(0, selStart) + symbol + selectedText + symbol + text.substring(selEnd)
            }

            contentValue = contentValue.copy(
                text = newText,
                selection = if (isAlreadyWrapped) {
                    TextRange(selStart, selEnd - (symbol.length * 2))
                } else {
                    TextRange(selStart, selEnd + (symbol.length * 2))
                }
            )
        }
    }

    fun applyBlockMarkdown(prefix: String) {
        val selection = contentValue.selection
        val text = contentValue.text
        val selStart = selection.min.coerceIn(0, text.length)
        val selEnd = selection.max.coerceIn(selStart, text.length)
        
        val lineStart = text.lastIndexOf('\n', (selStart - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', selEnd).let { if (it == -1) text.length else it }
        
        if (lineStart > lineEnd || lineStart > text.length || lineEnd > text.length) return
        
        val lines = text.substring(lineStart, lineEnd).split('\n')
        val allHavePrefix = lines.all { it.startsWith(prefix) }
        
        val newLines = if (allHavePrefix) {
            lines.map { it.removePrefix(prefix) }
        } else {
            lines.map { if (it.startsWith(prefix)) it else "$prefix$it" }
        }
        
        val newBlock = newLines.joinToString("\n")
        val newText = text.substring(0, lineStart) + newBlock + text.substring(lineEnd)
        
        contentValue = contentValue.copy(
            text = newText,
            selection = if (selection.collapsed) {
                TextRange(lineStart + newBlock.length)
            } else {
                TextRange(lineStart, lineStart + newBlock.length)
            }
        )
    }

    fun applyCodeMarkdown() {
        val selection = contentValue.selection
        val text = contentValue.text
        val selStart = selection.min.coerceIn(0, text.length)
        val selEnd = selection.max.coerceIn(selStart, text.length)
        val selectedText = text.substring(selStart, selEnd)

        if (selection.collapsed || !selectedText.contains('\n')) {
            // Single line or no selection: use inline backticks
            applyInlineMarkdown("`")
        } else {
            // Multi-line selection: use fenced code block
            val isAlreadyFenced = selectedText.startsWith("```") && selectedText.trimEnd().endsWith("```")
            if (isAlreadyFenced) {
                // Remove the fences
                val inner = selectedText.removePrefix("```").substringAfter('\n')
                    .let { s -> val lastFence = s.lastIndexOf("\n```"); if (lastFence >= 0) s.substring(0, lastFence) else s.removeSuffix("```") }
                val newText = text.substring(0, selStart) + inner + text.substring(selEnd)
                contentValue = contentValue.copy(
                    text = newText,
                    selection = TextRange(selStart, selStart + inner.length)
                )
            } else {
                val fenced = "```\n$selectedText\n```"
                val newText = text.substring(0, selStart) + fenced + text.substring(selEnd)
                contentValue = contentValue.copy(
                    text = newText,
                    selection = TextRange(selStart, selStart + fenced.length)
                )
            }
        }
    }

    var showLinkDialog by mutableStateOf(false)
    var linkDialogText by mutableStateOf("")
    var linkDialogUrl by mutableStateOf("")

    fun openLinkDialog(clipboardUrl: String?) {
        val selection = contentValue.selection
        val text = contentValue.text
        val selStart = selection.min.coerceIn(0, text.length)
        val selEnd = selection.max.coerceIn(selStart, text.length)
        
        linkDialogText = if (!selection.collapsed) text.substring(selStart, selEnd) else ""
        linkDialogUrl = if (clipboardUrl?.startsWith("http") == true) clipboardUrl else ""
        showLinkDialog = true
    }

    fun openLinkDialogForEdit(text: String, url: String) {
        linkDialogText = text
        linkDialogUrl = url
        showLinkDialog = true
    }

    fun applyLink(text: String, url: String) {
        val selection = contentValue.selection
        val currentText = contentValue.text
        val selStart = selection.min.coerceIn(0, currentText.length)
        val selEnd = selection.max.coerceIn(selStart, currentText.length)

        val finalLinkText = text.ifBlank { "text" }
        val finalLinkUrl = url.ifBlank { "url" }
        val linkMarkdown = "[$finalLinkText]($finalLinkUrl)"
        val newText = currentText.replaceRange(selStart, selEnd, linkMarkdown)
        
        contentValue = contentValue.copy(
            text = newText,
            selection = TextRange(selStart, selStart + linkMarkdown.length)
        )
        showLinkDialog = false
    }

    fun applyLinkMarkdown(clipboardUrl: String?) {
        // Deprecated by Link Dialog
    }

    // --- Optimized Data Flows ---

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiDrafts = snapshotFlow { pubkey }
        .flatMapLatest { pk ->
            if (pk == null) flowOf(emptyList())
            else draftDao.getAllDrafts(pk)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiScheduled = snapshotFlow { pubkey }
        .flatMapLatest { pk ->
            if (pk == null) flowOf(emptyList())
            else draftDao.getAllScheduled(pk)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Stage 1: Pre-process database history into lightweight models
    @OptIn(ExperimentalCoroutinesApi::class)
    private val processedHistoryItems: Flow<List<HistoryUiModel>> = combine(
        snapshotFlow { pubkey },
        snapshotFlow { isFullHistoryEnabled }
    ) { pk, isEnabled -> 
        pk to isEnabled 
    }.flatMapLatest { (pk, isEnabled) ->
        if (pk == null) return@flatMapLatest flowOf(emptyList<HistoryUiModel>())
        
        combine(
            draftDao.getScheduledHistory(pk),
            if (isEnabled) draftDao.getRemoteHistory(pk) else flowOf(emptyList())
        ) { scheduled, remote ->
            withContext(Dispatchers.Default) {
                (scheduled + remote.onEach { it.isRemote = true })
                    .distinctBy { it.publishedEventId ?: "local_${it.id}" }
                    .sortedByDescending { it.actualPublishedAt ?: it.scheduledAt ?: it.lastEdited }
                    .map { draft ->
                        HistoryUiModel(
                            id = draft.publishedEventId ?: "local_${draft.id}",
                            localId = draft.id,
                            contentSnippet = if (draft.content.length > 500) draft.content.take(500) + "..." else draft.content,
                            timestamp = draft.actualPublishedAt ?: draft.scheduledAt ?: draft.lastEdited,
                            pubkey = draft.pubkey,
                            isRemote = draft.isRemoteCache && !draft.isScheduled,
                            isScheduled = draft.isScheduled,
                            isCompleted = draft.isCompleted,
                            isSuccess = draft.isCompleted && draft.publishError == null,
                            isOfflineRetry = draft.isOfflineRetry,
                            publishError = draft.publishError,
                            kind = draft.kind,
                            isQuote = draft.isQuote,
                            actualPublishedAt = draft.actualPublishedAt,
                            scheduledAt = draft.scheduledAt,
                            sourceUrl = draft.sourceUrl,
                            previewTitle = draft.previewTitle,
                            previewImageUrl = draft.previewImageUrl,
                            previewDescription = draft.previewDescription,
                            previewSiteName = draft.previewSiteName,
                            mediaJson = draft.mediaJson,
                            originalEventJson = draft.originalEventJson,
                            articleTitle = draft.articleTitle,
                            articleSummary = draft.articleSummary,
                            articleIdentifier = draft.articleIdentifier
                        )
                    }
            }
        }
    }.conflate()
     .flowOn(Dispatchers.Default)

    // Hidden Hashtags state (per user)
    private val _hiddenHashtagsTrigger = MutableStateFlow(0)
    val hiddenHashtags: StateFlow<Set<String>> = combine(
        snapshotFlow { pubkey },
        _hiddenHashtagsTrigger
    ) { pk, _ ->
        settingsRepository.getHiddenHashtags(pk)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleHashtagHidden(tag: String) {
        settingsRepository.hideHashtag(pubkey, tag)
        if (activeHashtags.contains(tag)) activeHashtags.remove(tag)
        _hiddenHashtagsTrigger.value++
    }

    fun resetHiddenHashtags() {
        settingsRepository.resetHiddenHashtags(pubkey)
        _hiddenHashtagsTrigger.value++
    }

    // Derived Hashtag Counts Flow (Returns all top tags, UI will handle mode-based visibility)
    val topHashtags: StateFlow<List<Pair<String, Int>>> = processedHistoryItems
        .map { items ->
            withContext(Dispatchers.Default) {
                val tagMap = mutableMapOf<String, Int>()
                val hashtagRegex = "#([a-zA-Z0-9_]+)".toRegex()
                
                items.forEach { item ->
                    hashtagRegex.findAll(item.contentSnippet).forEach { match ->
                        val tag = match.groupValues[1].lowercase()
                        tagMap[tag] = tagMap.getOrDefault(tag, 0) + 1
                    }
                    
                    item.originalEventJson?.let { 
                        try {
                            val tags = JSONObject(it).optJSONArray("tags")
                            if (tags != null) {
                                for (i in 0 until tags.length()) {
                                    val tagArr = tags.optJSONArray(i)
                                    if (tagArr != null && tagArr.length() >= 2 && tagArr.getString(0) == "t") {
                                        val tag = tagArr.getString(1).lowercase()
                                        tagMap[tag] = tagMap.getOrDefault(tag, 0) + 1
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                tagMap.toList().sortedByDescending { it.second }.take(30)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Stage 2: Instant UI Filter - reactive to search and chips
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiHistory: StateFlow<List<HistoryUiModel>> = combine(
        processedHistoryItems,
        snapshotFlow { searchQuery }.debounce(250), 
        snapshotFlow { activeHistoryFilters.toList() },
        snapshotFlow { activeHashtags.toList() }
    ) { items, query, filters, selectedTags ->
        if (query.isBlank() && filters.isEmpty() && selectedTags.isEmpty()) return@combine items
        
        withContext(Dispatchers.Default) {
            items.filter { item ->
                val text = item.contentSnippet.lowercase()
                val matchesSearch = query.isBlank() || item.contentSnippet.contains(query, ignoreCase = true)
                
                val matchesTypeFilter = if (filters.none { it in listOf(HistoryFilter.NOTE, HistoryFilter.HIGHLIGHT, HistoryFilter.REPOST, HistoryFilter.QUOTE, HistoryFilter.MEDIA, HistoryFilter.ARTICLE) }) {
                    true
                } else {
                    filters.any { filter ->
                        when (filter) {
                            HistoryFilter.NOTE -> item.kind == 1 && !item.isQuote
                            HistoryFilter.HIGHLIGHT -> item.kind == 9802
                            HistoryFilter.MEDIA -> item.kind == 20 || item.kind == 22
                            HistoryFilter.REPOST -> item.kind == 6 || item.kind == 16
                            HistoryFilter.QUOTE -> item.isQuote
                            HistoryFilter.ARTICLE -> item.kind == 30023
                            else -> false
                        }
                    }
                }

                val matchesMediaFilter = if (filters.none { it in listOf(HistoryFilter.HAS_MEDIA, HistoryFilter.IMAGE, HistoryFilter.GIF, HistoryFilter.VIDEO) }) {
                    true
                } else {
                    val mediaUrls = item.mediaJson?.let { 
                        try { 
                            val arr = JSONArray(it)
                            (0 until arr.length()).map { i -> arr.getJSONObject(i).optString("uploadedUrl", "").lowercase() }
                        } catch (_: Exception) { emptyList<String>() }
                    } ?: emptyList()
                    
                    val hasAnyMedia = mediaUrls.isNotEmpty() || 
                                     text.contains(".jpg") || text.contains(".jpeg") || 
                                     text.contains(".png") || text.contains(".webp") || 
                                     text.contains(".gif") || text.contains(".mp4") || 
                                     text.contains(".mov") || text.contains(".webm")

                    filters.any { filter ->
                        when (filter) {
                            HistoryFilter.HAS_MEDIA -> hasAnyMedia
                            HistoryFilter.IMAGE -> mediaUrls.any { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") } ||
                                                  text.contains(".jpg") || text.contains(".jpeg") || text.contains(".png") || text.contains(".webp")
                            HistoryFilter.GIF -> mediaUrls.any { it.endsWith(".gif") } || text.contains(".gif")
                            HistoryFilter.VIDEO -> mediaUrls.any { it.endsWith(".mp4") || it.endsWith(".mov") || it.endsWith(".webm") } ||
                                                  text.contains(".mp4") || text.contains(".mov") || text.contains(".webm")
                            else -> false
                        }
                    }
                }

                val matchesHashtagFilter = if (selectedTags.isEmpty()) {
                    true
                } else {
                    // "Either" logic: item must contain at least one of the selected hashtags
                    selectedTags.any { tag -> text.contains("#$tag", ignoreCase = true) }
                }

                matchesSearch && matchesTypeFilter && matchesMediaFilter && matchesHashtagFilter
            }
        }
    }.flowOn(Dispatchers.Default)
     .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var hasReachedEndOfRemoteHistory by mutableStateOf(false)
    private var currentSearchJob: Job? = null
    var remoteHistoryCursor by mutableStateOf<Long?>(null)

    fun forceFullSync() {
        val pk = pubkey ?: return
        HistorySyncManager.startFullSync(pk, relayManager, draftDao)
    }

    fun fetchRemoteHistory() {
        val pk = pubkey ?: return
        HistorySyncManager.startDeltaSync(pk, relayManager, draftDao)
    }

    fun loadMoreRemoteHistory() {
        val pk = pubkey ?: return
        HistorySyncManager.startPaginationSync(pk, relayManager, draftDao) { count ->
            if (count == 0) {
                hasReachedEndOfRemoteHistory = true
                settingsRepository.setHistorySyncCompleted(pk, true)
            }
        }
    }

    fun triggerBackgroundSearchFetch(searchTerm: String) {
        val currentPk = pubkey ?: return
        if (searchTerm.isBlank()) return

        currentSearchJob?.cancel()
        currentSearchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            try {
                val kinds = listOf(1, 6, 16, 20, 22, 9802, 30023)
                val existingIds = withContext(Dispatchers.IO) {
                    draftDao.getAllRemoteIds(currentPk)
                }
                val existingIdsSet = existingIds.toSet()
                
                val noteChannel = kotlinx.coroutines.channels.Channel<Draft>(capacity = 500)
                val saverJob = launch(Dispatchers.IO) {
                    val batch = mutableListOf<Draft>()
                    for (draft in noteChannel) {
                        batch.add(draft)
                        if (batch.size >= 50) {
                            draftDao.syncRemoteNotes(batch.toList())
                            batch.clear()
                        }
                    }
                    if (batch.isNotEmpty()) draftDao.syncRemoteNotes(batch)
                }

                relayManager.fetchHistoryFromRelays(
                    currentPk,
                    kinds,
                    searchTerm,
                    null,
                    null,
                    onProgress = { url, current, total -> 
                        com.ryans.nostrshare.utils.NotificationHelper.showSyncProgressNotification(
                            NostrShareApp.getInstance(),
                            url,
                            current,
                            total
                        )
                    }
                ) { note ->
                    val noteId = note.optString("id")
                    if (!HistorySyncManager.isReply(note) && !existingIdsSet.contains(noteId)) {
                        val processed = HistorySyncManager.processRemoteNote(note, currentPk)
                        viewModelScope.launch {
                            noteChannel.send(processed)
                        }
                    }
                }
                
                noteChannel.close()
                saverJob.join()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                com.ryans.nostrshare.utils.NotificationHelper.showSyncProgressNotification(
                    NostrShareApp.getInstance(),
                    "",
                    0,
                    0,
                    isCompleted = true
                )
            }
        }
    }

    var isUploading by mutableStateOf(false)
    var isDeleting by mutableStateOf(false)
    var uploadStatus by mutableStateOf("")
    var uploadServerResults by mutableStateOf<List<Pair<String, Boolean>>>(emptyList())
    var deleteServerResults by mutableStateOf<List<Pair<String, Boolean>>>(emptyList())

    var mediaUri by mutableStateOf<Uri?>(null)
    var mediaMimeType by mutableStateOf<String?>(null)
    var uploadedMediaUrl by mutableStateOf<String?>(null)
    var uploadedMediaHash by mutableStateOf<String?>(null)
    var uploadedMediaSize by mutableStateOf<Long?>(null)

    init {
        isOnboarded = settingsRepository.isOnboarded()
        isSchedulingEnabled = settingsRepository.isSchedulingEnabled()
        
        if (isOnboarded) {
            val savedPubkey = prefs.getString("pubkey", null)
            isFullHistoryEnabled = settingsRepository.isFullHistoryEnabled(savedPubkey)
            hasReachedEndOfRemoteHistory = settingsRepository.isHistorySyncCompleted(savedPubkey)

            if (savedPubkey != null) {
                pubkey = savedPubkey
                npub = prefs.getString("npub", null)
                signerPackageName = prefs.getString("signer_package", null)
                
                val savedName = prefs.getString("user_name", null)
                val savedPic = prefs.getString("user_pic", null)
                val savedTime = prefs.getLong("user_created_at", 0L)
                if (savedName != null || savedPic != null) {
                    userProfile = UserProfile(savedName, savedPic, createdAt = savedTime)
                }
                
                refreshUserProfile()
            }
        } else {
            currentOnboardingStep = OnboardingStep.WELCOME
        }

        knownAccounts.addAll(settingsRepository.getKnownAccounts())
        blossomServers = settingsRepository.getBlossomServers(pubkey)
        followedPubkeys = settingsRepository.getFollowedPubkeys()
        
        settingsRepository.getUsernameCache().forEach { (pk, profile) ->
            usernameCache[pk] = profile
        }
    }

    fun toggleFullHistory() {
        val newState = !isFullHistoryEnabled
        isFullHistoryEnabled = newState
        settingsRepository.setFullHistoryEnabled(newState, pubkey)
    }

    fun isHapticEnabled(): Boolean = settingsRepository.isHapticEnabled()

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = NostrShareApp.getInstance().getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun login(hexKey: String, npubKey: String?, pkgName: String?) {
        pubkey = hexKey
        npub = npubKey
        signerPackageName = pkgName
        currentOnboardingStep = OnboardingStep.SYNCING
        isSyncingServers = true
        
        prefs.edit()
            .putString("pubkey", hexKey)
            .putString("npub", npubKey)
            .putString("signer_package", pkgName)
            .apply()

        val existingIndex = knownAccounts.indexOfFirst { it.pubkey == hexKey }
        val newAccount = Account(hexKey, npubKey, pkgName, userProfile?.name, userProfile?.pictureUrl)
        if (existingIndex >= 0) {
            knownAccounts[existingIndex] = newAccount
        } else {
            knownAccounts.add(newAccount)
        }
        settingsRepository.setKnownAccounts(knownAccounts.toList())
            
        refreshUserProfile()
    }

    fun switchUser(hexKey: String) {
        if (pubkey == hexKey) return
        
        HistorySyncManager.reset()
        
        val account = knownAccounts.find { it.pubkey == hexKey }
        
        pubkey = hexKey
        npub = account?.npub
        signerPackageName = account?.signerPackage
        userProfile = account?.let { UserProfile(it.name, it.pictureUrl, createdAt = it.createdAt) } ?: usernameCache[hexKey]
        
        prefs.edit()
            .putString("pubkey", hexKey)
            .putString("npub", account?.npub)
            .putString("signer_package", account?.signerPackage)
            .putString("user_name", userProfile?.name)
            .putString("user_pic", userProfile?.pictureUrl)
            .putLong("user_created_at", userProfile?.createdAt ?: 0L)
            .apply()
        
        blossomServers = settingsRepository.getBlossomServers(hexKey)
        isFullHistoryEnabled = settingsRepository.isFullHistoryEnabled(hexKey)
        hasReachedEndOfRemoteHistory = settingsRepository.isHistorySyncCompleted(hexKey)
        
        prefs.edit()
            .putString("pubkey", hexKey)
            .putString("npub", npub)
            .putString("signer_package", signerPackageName)
            .apply()
        
        refreshUserProfile()
    }
    

    private fun refreshUserProfile() {
        if (!isOnboarded && currentOnboardingStep != OnboardingStep.SYNCING) return
        val pk = pubkey ?: return
        viewModelScope.launch {
            try {
                val profile = relayManager.fetchUserProfile(pk)
                if (profile != null) {
                    val currentTs = userProfile?.createdAt ?: 0L
                    if (profile.createdAt > currentTs) {
                        userProfile = profile
                        prefs.edit()
                            .putString("user_name", profile.name)
                            .putString("user_pic", profile.pictureUrl)
                            .putLong("user_created_at", profile.createdAt)
                            .apply()
                        
                        val index = knownAccounts.indexOfFirst { it.pubkey == pk }
                        if (index >= 0) {
                            val updated = knownAccounts[index].copy(
                                name = profile.name, 
                                pictureUrl = profile.pictureUrl,
                                createdAt = profile.createdAt
                            )
                            knownAccounts[index] = updated
                            settingsRepository.setKnownAccounts(knownAccounts.toList())
                        }
                    }
                }
                
                syncBlossomServers()
                
                val userRelays = relayManager.fetchRelayList(pk)
                
                val follows = relayManager.fetchContactList(pk, userRelays)
                followedPubkeys = follows
                settingsRepository.setFollowedPubkeys(follows)
                
                if (follows.isNotEmpty()) {
                    val followsList = follows.toList()
                    relayManager.fetchUserProfiles(followsList) { p, profile ->
                        usernameCache[p] = profile
                    }
                    settingsRepository.setUsernameCache(usernameCache.toMap())
                }
            } catch (e: Exception) {
                android.util.Log.e("ProcessTextViewModel", "Failed to refresh user profile", e)
            } finally {
                fetchRemoteHistory()
            }
        }
    }

    fun syncBlossomServers() {
        val pk = pubkey ?: return
        viewModelScope.launch {
            try {
                isSyncingServers = true
                val discoveredUrls = relayManager.fetchBlossomServerList(pk)
                
                val existingLocal = settingsRepository.getBlossomServers(pk).associate { it.url to it.enabled }
                
                if (discoveredUrls.isNotEmpty()) {
                    val newServers = discoveredUrls.map { blossomServer ->
                        val cleanUrl = blossomServer.trim().removeSuffix("/")
                        val isEnabled = existingLocal[cleanUrl] ?: true
                        BlossomServer(cleanUrl, isEnabled)
                    }
                    
                    blossomServers = newServers
                    saveServersAndContinue(newServers)
                } else {
                    currentOnboardingStep = OnboardingStep.SERVER_SELECTION
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSyncingServers = false
            }
        }
    }

    fun saveServersAndContinue(servers: List<BlossomServer>) {
        settingsRepository.setBlossomServers(servers, pubkey)
        blossomServers = servers
        currentOnboardingStep = OnboardingStep.SCHEDULING_CONFIG
    }

    fun completeOnboarding() {
        settingsRepository.setOnboarded(true)
        isOnboarded = true
        refreshUserProfile()
    }

    fun startSchedulingOnboarding() {
        currentOnboardingStep = OnboardingStep.SCHEDULING_CONFIG
        isOnboarded = false
    }

    fun completeSchedulingConfig() {
        settingsRepository.setSchedulingEnabled(true)
        isSchedulingEnabled = true
        completeOnboarding()
    }

    fun startSchedulingSetup() {
        startSchedulingOnboarding()
    }

    fun skipSchedulingConfig() {
        settingsRepository.setSchedulingEnabled(false)
        isSchedulingEnabled = false
        completeOnboarding()
    }

    fun getFallBackServers(): List<BlossomServer> {
        return settingsRepository.fallBackBlossomServers.map { BlossomServer(it, true) }
    }

    fun updateQuote(newQuote: String) {
        val cleanedQuote = com.ryans.nostrshare.utils.UrlUtils.cleanText(newQuote)
        quoteContent = cleanedQuote

        if (sourceUrl.isBlank() && PostKind.HIGHLIGHT == postKind) {
             val entity = NostrUtils.findNostrEntity(cleanedQuote)
             if (entity != null && entity.type != "npub") {
                 updateSource(entity.bech32)
             }
        }
    }

    fun updateSource(newSource: String) {
        val cleanedSource = if (newSource.startsWith("http")) com.ryans.nostrshare.utils.UrlUtils.cleanUrl(newSource) else newSource
        val oldSource = sourceUrl
        sourceUrl = cleanedSource
        
        if (postKind == PostKind.NOTE && cleanedSource.isNotBlank() && !quoteContent.contains(cleanedSource)) {
             val prefix = if (quoteContent.isNotBlank()) "\n\n" else ""
             quoteContent += "$prefix$cleanedSource"
        }

        if (cleanedSource.isNotBlank() && cleanedSource != oldSource) {
            val entity = NostrUtils.findNostrEntity(cleanedSource)
            if (entity != null && (entity.type == "nevent" || entity.type == "note" || entity.type == "naddr" || entity.type == "nprofile")) {
                viewModelScope.launch {
                    try {
                        if (postKind != PostKind.HIGHLIGHT) {
                             setKind(PostKind.HIGHLIGHT)
                        }

                        if (entity.type == "nprofile") {
                             val profile = relayManager.fetchUserProfile(entity.id)
                            if (profile != null) {
                                userProfile = profile
                                usernameCache[entity.id] = profile
                            }
                            return@launch
                        }

                        val event = if (entity.type == "naddr") {
                            relayManager.fetchAddress(entity.kind!!, entity.author!!, entity.id, entity.relays)
                        } else {
                            relayManager.fetchEvent(entity.id, entity.relays)
                        }
                        
                        if (event != null) {
                            originalEventJson = event.toString()
                            if (entity.type == "naddr") {
                                val tags = event.optJSONArray("tags")
                                var title: String? = null
                                if (tags != null) {
                                    for (i in 0 until tags.length()) {
                                        val tag = tags.optJSONArray(i)
                                        if (tag != null && tag.length() >= 2 && tag.optString(0) == "title") {
                                            title = tag.getString(1)
                                            break
                                        }
                                    }
                                }
                                if (title != null) {
                                    quoteContent = title
                                    if (postKind == PostKind.ARTICLE) articleTitle = title
                                }
                            } else {
                                val content = event.optString("content")
                                if (content.isNotBlank() && (quoteContent.isBlank() || quoteContent == oldSource)) {
                                    quoteContent = content
                                }
                            }

                            val authorPubkey = event.optString("pubkey")
                            if (authorPubkey.isNotEmpty()) {
                                val profile = relayManager.fetchUserProfile(authorPubkey)
                                if (profile != null) {
                                    highlightAuthorName = profile.name
                                    highlightAuthorUrl = profile.pictureUrl
                                } else {
                                    highlightAuthorName = authorPubkey.take(8)
                                    highlightAuthorUrl = null
                                }
                                sourceUrl = "nostr:${entity.bech32}"
                                
                                highlightEventId = event.optString("id")
                                highlightAuthor = authorPubkey
                                highlightKind = event.optInt("kind", 1)
                                highlightIdentifier = if (entity.type == "naddr") entity.id else null
                                highlightRelays.clear()
                                highlightRelays.addAll(entity.relays)
                                
                                if (highlightAuthor == null) highlightAuthor = entity.author

                                if (!availableKinds.contains(PostKind.REPOST)) {
                                    availableKinds = availableKinds + PostKind.REPOST
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun fetchLinkPreview(url: String) {
        if (url == previewImageUrl || url == sourceUrl && previewTitle != null) return
        
        previewTitle = null
        previewDescription = null
        previewImageUrl = null
        previewSiteName = null

        viewModelScope.launch {
            try {
                val meta = com.ryans.nostrshare.utils.LinkPreviewManager.fetchMetadata(url)
                if (meta != null) {
                    previewTitle = meta.title
                    previewDescription = meta.description
                    previewImageUrl = meta.imageUrl
                    previewSiteName = meta.siteName
                    
                    if (postKind == PostKind.ARTICLE && articleTitle.isBlank()) {
                        articleTitle = meta.title ?: ""
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ProcessTextViewModel", "Error fetching preview: ${e.message}")
            }
        }
    }

    var publishSuccess by mutableStateOf<Boolean?>(null)

    var highlightAuthorName by mutableStateOf<String?>(null)
    var highlightAuthorUrl by mutableStateOf<String?>(null)

    private val blossomClient by lazy { BlossomClient(NostrShareApp.getInstance().client) }
    
    var mediaItems = mutableStateListOf<MediaUploadState>()
    var processedMediaUris = mutableMapOf<String, Uri>()
    
    var showSharingDialog by mutableStateOf(false)
    var isBatchUploading by mutableStateOf(false)
    var batchUploadStatus by mutableStateOf("")
    var batchCompressionLevel by mutableStateOf<Int?>(null)

    val batchProgress: Float
        get() {
            if (mediaItems.isEmpty()) return 0f
            val uploaded = mediaItems.count { it.uploadedUrl != null }
            return uploaded.toFloat() / mediaItems.size.toFloat()
        }
    
    var availableKinds by mutableStateOf(listOf(PostKind.NOTE, PostKind.HIGHLIGHT, PostKind.ARTICLE))
    
    enum class SigningPurpose {
        POST,
        UPLOAD_AUTH,
        DELETE_AUTH,
        BATCH_UPLOAD_AUTH,
        SERVER_LIST,
        SCHEDULE
    }
    var currentSigningPurpose = SigningPurpose.POST
    var currentScheduleTime: Long? = null

    fun prepareScheduling(timestamp: Long) {
        currentScheduleTime = timestamp
        currentSigningPurpose = SigningPurpose.SCHEDULE
        
        val eventJson = prepareEventJson(createdAt = timestamp / 1000)
        
        val pk = pubkey
        val pkg = signerPackageName
        if (pk != null && pkg != null) {
            val signed = com.ryans.nostrshare.nip55.Nip55.signEventBackground(NostrShareApp.getInstance(), pkg, eventJson, pk)
            if (signed != null) {
                onEventSigned(signed)
                return
            }
        }

        _eventToSign.value = eventJson
    }

    fun onScheduledEventSigned(signedJson: String) {
        val timestamp = currentScheduleTime ?: return
        currentScheduleTime = null
        
        viewModelScope.launch {
            val mediaJson = serializeMediaItems(mediaItems)
            val highlightRelaysJson = if (highlightRelays.isNotEmpty()) {
                org.json.JSONArray(highlightRelays).toString()
            } else {
                null
            }
            val draft = Draft(
                content = quoteContent,
                sourceUrl = sourceUrl,
                kind = if (postKind == PostKind.REPOST && quoteContent.isNotBlank()) 1 else postKind.kind,
                mediaJson = mediaJson,
                mediaTitle = mediaTitle,
                highlightEventId = highlightAuthor,
                highlightAuthor = highlightAuthor,
                highlightKind = highlightKind,
                highlightIdentifier = highlightIdentifier,
                highlightRelaysJson = highlightRelaysJson,
                originalEventJson = originalEventJson,
                pubkey = pubkey,
                isScheduled = true,
                scheduledAt = timestamp,
                signedJson = signedJson,
                isAutoSave = false,
                savedContentBuffer = savedContentBuffer,
                previewTitle = previewTitle,
                previewDescription = previewDescription,
                previewImageUrl = previewImageUrl,
                previewSiteName = previewSiteName,
                highlightAuthorName = highlightAuthorName,
                highlightAuthorAvatarUrl = highlightAuthorUrl,
                articleTitle = articleTitle,
                articleSummary = articleSummary,
                articleIdentifier = articleIdentifier
            )
            val id = draftDao.insertDraft(draft)
            com.ryans.nostrshare.utils.SchedulerUtils.enqueueScheduledWork(
                NostrShareApp.getInstance(),
                draft.copy(id = id.toInt()),
                forceRefresh = true
            )
            
            com.ryans.nostrshare.utils.NotificationHelper.updateScheduledNotification(NostrShareApp.getInstance())
            
            publishStatus = "Note scheduled!"
            publishSuccess = true
            
            clearContent()
        }
    }


    fun cancelScheduledNote(draft: Draft) {
        viewModelScope.launch {
            draftDao.deleteDraft(draft)
            
            val context = NostrShareApp.getInstance()
            com.ryans.nostrshare.utils.SchedulerUtils.cancelScheduledWork(context, draft.id)
            
            com.ryans.nostrshare.utils.NotificationHelper.updateScheduledNotification(context)
        }
    }

    fun unscheduleAndSaveToDrafts(draft: Draft) {
        viewModelScope.launch {
            val context = NostrShareApp.getInstance()
            com.ryans.nostrshare.utils.SchedulerUtils.cancelScheduledWork(context, draft.id)
            
            val updatedDraft = draft.copy(
                isScheduled = false,
                scheduledAt = null,
                signedJson = null,
                publishError = null,
                isCompleted = false,
                lastEdited = System.currentTimeMillis()
            )
            
            draftDao.insertDraft(updatedDraft)
            
            com.ryans.nostrshare.utils.NotificationHelper.updateScheduledNotification(context)
        }
    }

    fun verifyScheduledNotes(context: Context) {
        if (isSchedulingEnabled) {
            com.ryans.nostrshare.utils.SchedulerUtils.verifyAllScheduledNotes(context)
        }
    }

    fun clearScheduledHistory() {
        viewModelScope.launch {
            draftDao.deleteCompletedScheduled()
        }
    }
    var pendingAuthServerUrl: String? = null
    var pendingAuthItemId: String? = null

    var userSearchQuery by mutableStateOf("")
    var userSearchResults = mutableStateListOf<Pair<String, UserProfile>>()
    var isSearchingUsers by mutableStateOf(false)
    var showUserSearchDialog by mutableStateOf(false)
    
    fun onMediaSelected(context: Context, uris: List<Uri>) {
        uris.forEach { uri ->
            val mimeType = context.contentResolver.getType(uri) ?: "image/*"
            val item = MediaUploadState(
                id = UUID.randomUUID().toString(),
                uri = uri,
                mimeType = mimeType
            )
            mediaItems.add(item)
            
            if (mimeType.startsWith("video/") || mimeType.startsWith("image/")) {
                availableKinds = listOf(PostKind.MEDIA, PostKind.NOTE, PostKind.ARTICLE)
                if (!settingsRepository.isAlwaysUseKind1()) {
                    setKind(PostKind.MEDIA)
                }
            }
            
            prepareMedia(context, item)
        }
        
        blossomServers = settingsRepository.getBlossomServers(pubkey)
        showSharingDialog = true
    }
    
    private fun prepareMedia(context: Context, item: MediaUploadState) {
        item.status = "Processing..."
        item.isProcessing = true
        
        viewModelScope.launch {
            try {
                val isImage = item.mimeType?.startsWith("image/") == true
                var processedUri: Uri? = null
                if (isImage) {
                    item.status = "Optimizing..."
                    val level = batchCompressionLevel ?: settingsRepository.getCompressionLevel()
                    val result = com.ryans.nostrshare.utils.ImageProcessor.processImage(context, item.uri, item.mimeType, level)
                    
                    if (result != null) {
                        processedUri = result.uri
                    }
                }
                
                val stableUri = processedUri ?: run {
                    item.status = "Localizing..."
                    val tempSource = File.createTempFile("blossom_source_${item.id}_", ".tmp", context.cacheDir)
                    context.contentResolver.openInputStream(item.uri).use { input ->
                        tempSource.outputStream().use { output ->
                            input?.copyTo(output)
                        }
                    }
                    Uri.fromFile(tempSource)
                }
                processedMediaUris[item.id] = stableUri
                
                item.status = "Hashing..."
                val hashResult = blossomClient.hashFile(context, stableUri)
                item.hash = hashResult.first
                item.size = hashResult.second
                
                item.status = "Ready"
                item.isProcessing = false
            } catch (e: Exception) {
                item.status = "Error: ${e.message}"
                item.isProcessing = false
            }
        }
    }

    fun updateBatchCompressionLevel(context: Context, level: Int) {
        if (batchCompressionLevel == level) return
        batchCompressionLevel = level
        
        mediaItems.forEach { item ->
             if (item.uploadedUrl == null && !item.isUploading) {
                 prepareMedia(context, item)
             }
        }
    }
    fun onHighlightShared() {
        if (!settingsRepository.isAlwaysUseKind1(pubkey)) {
            setKind(PostKind.HIGHLIGHT)
        }
    }
    
    private var pendingServers: List<String> = emptyList()
    private var processedMediaUri: Uri? = null
    
    private var lastProcessedWithOptimize: Boolean? = null

    fun initiateUploadAuth(item: MediaUploadState) {
        val pk = pubkey ?: return
        
        val stableUri = processedMediaUris[item.id]
        if (stableUri == null) {
            item.status = "File not localized."
            return
        }
        
        val hash = item.hash
        val size = item.size
        if (hash == null || size == 0L) {
             item.status = "Hash/Size missing."
             return
        }
        
        item.status = "Preparing auth..."
        item.isUploading = true
        
        viewModelScope.launch {
            try {
                val servers = blossomServers.filter { it.enabled }.map { it.url }
                val targetServers = if (servers.isEmpty()) listOf("https://blossom.primal.net") else servers
                item.pendingServers = targetServers
                
                val authServer = targetServers.firstOrNull() ?: "https://blossom.primal.net"
                pendingAuthServerUrl = authServer
                pendingAuthItemId = item.id
                
                val fileName = stableUri.lastPathSegment
                val authEventJson = blossomClient.createAuthEventJson(hash, size, pk, "upload", fileName = fileName, mimeType = item.mimeType)
                
                currentSigningPurpose = SigningPurpose.UPLOAD_AUTH
                
                val pkg = signerPackageName
                if (pkg != null) {
                    val signed = com.ryans.nostrshare.nip55.Nip55.signEventBackground(NostrShareApp.getInstance(), pkg, authEventJson, pk)
                    if (signed != null) {
                        onEventSigned(signed)
                        return@launch
                    }
                }

                _eventToSign.value = authEventJson
                
            } catch (e: Exception) {
                item.status = "Auth Error: ${e.message}"
                item.isUploading = false
            }
        }
    }

    fun initiateBatchUpload(context: Context) {
        pubkey ?: return
        val itemsToUpload = mediaItems.filter { it.uploadedUrl == null && !it.isUploading && !it.isProcessing }
        if (itemsToUpload.isEmpty()) {
            isBatchUploading = false
            return
        }

        isBatchUploading = true
        batchUploadStatus = "Starting sequential upload..."
        
        val pkg = signerPackageName
        val pk = pubkey
        if (pkg != null && pk != null) {
            val authEvents = mutableListOf<String>()
            itemsToUpload.forEach { item ->
                val stableUri = processedMediaUris[item.id]
                if (stableUri != null) {
                    val authEventJson = blossomClient.createAuthEventJson(
                        item.hash ?: "",
                        item.size,
                        pk,
                        "upload",
                        fileName = stableUri.lastPathSegment,
                        mimeType = item.mimeType
                    )
                    authEvents.add(authEventJson)
                }
            }

            if (authEvents.isNotEmpty()) {
                val signed = com.ryans.nostrshare.nip55.Nip55.signEventsBackground(context, pkg, authEvents, pk)
                if (signed != null) {
                    currentSigningPurpose = SigningPurpose.BATCH_UPLOAD_AUTH
                    onBatchEventsSigned(signed)
                    return
                }
            }
        }

        val first = itemsToUpload.first()
        initiateUploadAuth(first)
    }

    fun resetBatchState() {
        isBatchUploading = false
        batchUploadStatus = ""
        mediaItems.forEach { item ->
            if (item.isUploading && item.uploadedUrl == null) {
                item.isUploading = false
                item.status = "Ready"
            }
        }
    }

    private var pendingBatchItemIds: List<String> = emptyList()
    private val _batchEventsToSign = MutableStateFlow<List<String>>(emptyList())
    val batchEventsToSign: StateFlow<List<String>> = _batchEventsToSign.asStateFlow()

    fun onBatchEventsSigned(signedEvents: List<String>) {
        val ids = pendingBatchItemIds
        if (signedEvents.size != ids.size) {
             batchUploadStatus = "Signature mismatch"
             isBatchUploading = false
             return
        }

        signedEvents.forEachIndexed { index, signedEvent ->
            val itemId = ids[index]
            val item = mediaItems.find { it.id == itemId }
            if (item != null) {
                finalizeUpload(item, signedEvent)
            }
        }
        
        _batchEventsToSign.value = emptyList()
        pendingBatchItemIds = emptyList()
    }
    
    fun retryFailedUploads(context: Context) {
        val failedServers = uploadServerResults.filter { !it.second }.map { it.first }
        if (failedServers.isEmpty()) return

        val pk = pubkey ?: return
        val hash = uploadedMediaHash ?: return
        val size = uploadedMediaSize ?: return

        isUploading = true
        uploadStatus = "Retrying ${failedServers.size} failed uploads..."

        viewModelScope.launch {
            try {
                pendingServers = failedServers
                pendingAuthServerUrl = pendingServers.firstOrNull()

                val fileName = processedMediaUri?.lastPathSegment
                val authEventJson = blossomClient.createAuthEventJson(hash, size, pk, "upload", fileName = fileName, mimeType = mediaMimeType)
                currentSigningPurpose = SigningPurpose.UPLOAD_AUTH
                _eventToSign.value = authEventJson
            } catch (e: Exception) {
                uploadStatus = "Retry prepare failed: ${e.message}"
                isUploading = false
            }
        }
    }

    private var pendingDeleteHash: String? = null

    fun deleteMedia(item: MediaUploadState) {
        val localHash = item.hash ?: return
        val pk = pubkey ?: return
        
        viewModelScope.launch {
            try {
                 item.status = "Preparing delete..."
                 item.isProcessing = true
                 
                 val servers = blossomServers.filter { it.enabled }.map { it.url }
                 val targetServers = if (servers.isNotEmpty()) servers else listOf("https://blossom.primal.net")
                 item.pendingServers = targetServers
                 
                 pendingAuthServerUrl = targetServers.firstOrNull()
                 pendingAuthItemId = item.id
                 
                 val hashForAuth = targetServers.firstNotNullOfOrNull { item.serverHashes.value[it] } ?: localHash
                 item.hash = hashForAuth
                 
                 val authEventJson = blossomClient.createAuthEventJson(hashForAuth, null, pk, "delete")
                 currentSigningPurpose = SigningPurpose.DELETE_AUTH

                 val pkg = signerPackageName
                 if (pkg != null) {
                     val signed = com.ryans.nostrshare.nip55.Nip55.signEventBackground(NostrShareApp.getInstance(), pkg, authEventJson, pk)
                     if (signed != null) {
                         onEventSigned(signed)
                         return@launch
                     }
                 }

                 _eventToSign.value = authEventJson
            } catch (e: Exception) {
                item.status = "Delete Error: ${e.message}"
                item.isProcessing = false
            }
        }
    }
                


    private val _eventToSign = MutableStateFlow<String?>(null)
    val eventToSign: StateFlow<String?> = _eventToSign.asStateFlow()
    
    fun requestSignature(json: String) {
        val pk = pubkey
        val pkg = signerPackageName
        if (pk != null && pkg != null) {
            val signed = com.ryans.nostrshare.nip55.Nip55.signEventBackground(NostrShareApp.getInstance(), pkg, json, pk)
            if (signed != null) {
                onEventSigned(signed)
                return
            }
        }
        _eventToSign.value = json
    }
    
    fun onEventSigned(signedEventJson: String) {
        val itemId = pendingAuthItemId
        val item = mediaItems.find { it.id == itemId }
        
        when (currentSigningPurpose) {
            SigningPurpose.POST -> publishPost(signedEventJson)
            SigningPurpose.UPLOAD_AUTH -> {
                if (item != null) finalizeUpload(item, signedEventJson)
            }
            SigningPurpose.DELETE_AUTH -> {
                if (item != null) finalizeDelete(item, signedEventJson)
            }
            SigningPurpose.SERVER_LIST -> finalizeBlossomServerListPublish(signedEventJson)
            SigningPurpose.SCHEDULE -> onScheduledEventSigned(signedEventJson)
            SigningPurpose.BATCH_UPLOAD_AUTH -> { }
        }
        _eventToSign.value = null
        pendingAuthItemId = null
    }

    private fun finalizeDelete(item: MediaUploadState, signedAuthEvent: String) {
        val hashToDelete = item.hash ?: return
        val servers = item.pendingServers
        
        viewModelScope.launch {
            try {
                isDeleting = true
                val urlToRemove = item.uploadedUrl
                uploadStatus = if (urlToRemove != null) "Deleting $urlToRemove..." else "Deleting from ${servers.size} servers..."
                
                val results = mutableListOf<Pair<String, Boolean>>()
                
                kotlinx.coroutines.coroutineScope {
                    val jobs = servers.map { server ->
                         async {
                             try {
                                 val success = blossomClient.delete(hashToDelete, signedAuthEvent, server)
                                 synchronized(results) { results.add(server to success) }
                             } catch (_: Exception) {
                                 synchronized(results) { results.add(server to false) }
                             }
                         }
                    }
                    jobs.awaitAll()
                }
                
                deleteServerResults = results.toList()
                val successCount = results.count { it.second }
                
                item.status = "Deleted from $successCount/${servers.size} servers."
                
                if (uploadedMediaUrl == item.uploadedUrl) {
                    uploadedMediaUrl = null
                    uploadedMediaHash = null
                    uploadedMediaSize = null
                }
                
                item.uploadedUrl = null
                item.hash = null
                item.size = 0L
                processedMediaUris.remove(item.id)
                mediaItems.remove(item)

                if (postKind == PostKind.NOTE && urlToRemove != null) {
                    var content = quoteContent.trim()
                    if (content.endsWith(urlToRemove)) {
                        content = content.removeSuffix(urlToRemove).trim()
                    } else if (content.contains(urlToRemove)) {
                        content = content.replace(urlToRemove, "").replace("\n\n\n", "\n\n").trim()
                    }
                    quoteContent = content
                }
                
            } catch (e: Exception) {
                item.status = "Delete error: ${e.message}"
            } finally {
                item.isProcessing = false
            }
        }
    }

    private fun finalizeUpload(item: MediaUploadState, signedAuthEvent: String) {
        val uri = processedMediaUris[item.id] ?: item.uri
        val servers = item.pendingServers
        if (servers.isEmpty()) return
        
        viewModelScope.launch {
            item.status = "Uploading to ${servers.size} server(s)..."
            item.isUploading = true
            
            val serverResults = mutableListOf<Pair<String, Boolean>>()
            val successfulUrls = mutableMapOf<String, String>()
            val serverHashes = mutableMapOf<String, String?>()
            
            kotlinx.coroutines.coroutineScope {
                val jobs = servers.map { server: String ->
                    async {
                        try {
                            val result = blossomClient.upload(
                                NostrShareApp.getInstance(),
                                null,
                                uri,
                                signedAuthEvent,
                                server,
                                item.mimeType
                            )
                            synchronized(serverResults) {
                                serverResults.add(server to true)
                                successfulUrls[server] = result.url
                                serverHashes[server] = result.serverHash
                            }
                            true
                        } catch (e: Exception) {
                            synchronized(serverResults) {
                                serverResults.add(server to false) 
                            }
                            false
                        }
                    }
                }
                jobs.awaitAll()
            }
            
            val firstSuccessUrl = servers.firstNotNullOfOrNull { successfulUrls[it] }
            
            item.serverResults.value = serverResults
            item.serverHashes.value = serverHashes.filterValues { it != null }.mapValues { it.value!! }
            
            if (firstSuccessUrl != null) {
                item.uploadedUrl = firstSuccessUrl
                val successCount = serverResults.count { it.second }
                item.status = "Uploaded to $successCount/${servers.size} servers"
                
                if (mediaItems.firstOrNull()?.id == item.id) {
                    uploadedMediaUrl = item.uploadedUrl
                    uploadedMediaHash = item.hash
                    uploadedMediaSize = item.size
                }

                if (postKind == PostKind.NOTE) {
                     val prefix = if (quoteContent.isNotBlank()) "\n\n" else ""
                     quoteContent += "$prefix$firstSuccessUrl"
                }
            } else {
                item.status = "All uploads failed"
            }
            
            item.isUploading = false
            
            if (isBatchUploading) {
                val stillUploading = mediaItems.any { it.isUploading }
                if (!stillUploading) {
                    val nextItem = mediaItems.find { it.uploadedUrl == null && !it.isUploading && !it.isProcessing }
                    if (nextItem != null) {
                         batchUploadStatus = "Uploading next item..."
                         initiateUploadAuth(nextItem)
                    } else {
                        isBatchUploading = false
                        batchUploadStatus = "Batch Complete"
                        showSharingDialog = false
                    }
                }
            }
        }
    }
    enum class PostKind(val kind: Int, val label: String) {
        NOTE(1, "Note"), 
        HIGHLIGHT(9802, "Highlight"),
        REPOST(6, "Repost"),
        MEDIA(0, "Media"),
        FILE_METADATA(1063, "File Meta"),
        ARTICLE(30023, "Article")
    }
    
    var postKind by mutableStateOf(PostKind.NOTE)

    var showDraftPrompt by mutableStateOf(false)
    var currentDraftId: Int? = null
    var showDatePicker by mutableStateOf(false)
    var showDraftsHistory by mutableStateOf(false)
    var isVisualMode by mutableStateOf(false)
    var isDraftMonitoringActive by mutableStateOf(false)

    fun clearContent() {
        contentValue = TextFieldValue("")
        sourceUrl = ""
        mediaUri = null
        mediaMimeType = null
        uploadedMediaUrl = null
        uploadedMediaHash = null
        uploadedMediaSize = null
        postKind = PostKind.NOTE
        mediaItems.clear()
        mediaTitle = ""
        articleTitle = ""
        articleSummary = ""
        articleIdentifier = null
        
        val draftIdToDelete = currentDraftId
        currentDraftId = null
        
        viewModelScope.launch {
            draftDao.deleteAutoSaveDraft(pubkey)
            if (draftIdToDelete != null) {
                draftDao.deleteById(draftIdToDelete)
            }
        }
    }
    fun saveDraft() {
        if (!isDraftMonitoringActive) return
        
        if (quoteContent.isBlank() && sourceUrl.isBlank() && mediaItems.isEmpty()) {
             viewModelScope.launch { draftDao.deleteAutoSaveDraft(pubkey) }
             return
        }

        viewModelScope.launch {
            val mediaJson = serializeMediaItems(mediaItems)
            val highlightRelaysJson = if (highlightRelays.isNotEmpty()) {
                org.json.JSONArray(highlightRelays).toString()
            } else {
                null
            }
            val draft = Draft(
                content = quoteContent,
                sourceUrl = sourceUrl,
                kind = if (postKind == PostKind.REPOST && quoteContent.isNotBlank()) 1 else postKind.kind,
                mediaJson = mediaJson,
                mediaTitle = mediaTitle,
                highlightEventId = highlightAuthor,
                highlightAuthor = highlightAuthor,
                highlightKind = highlightKind,
                highlightIdentifier = highlightIdentifier,
                highlightRelaysJson = highlightRelaysJson,
                originalEventJson = originalEventJson,
                pubkey = pubkey,
                isAutoSave = true,
                savedContentBuffer = savedContentBuffer,
                previewTitle = previewTitle,
                previewDescription = previewDescription,
                previewImageUrl = previewImageUrl,
                previewSiteName = previewSiteName,
                highlightAuthorName = highlightAuthorName,
                highlightAuthorAvatarUrl = highlightAuthorUrl,
                articleTitle = articleTitle,
                articleSummary = articleSummary,
                articleIdentifier = articleIdentifier
            )
            draftDao.deleteAutoSaveDraft(pubkey)
            draftDao.insertDraft(draft)
        }
    }

    fun saveManualDraft() {
        viewModelScope.launch {
            val mediaJson = serializeMediaItems(mediaItems)
            val highlightRelaysJson = if (highlightRelays.isNotEmpty()) {
                org.json.JSONArray(highlightRelays).toString()
            } else {
                null
            }
            val draft = Draft(
                id = currentDraftId ?: 0,
                content = quoteContent,
                sourceUrl = sourceUrl,
                kind = if (postKind == PostKind.REPOST && quoteContent.isNotBlank()) 1 else postKind.kind,
                mediaJson = mediaJson,
                mediaTitle = mediaTitle,
                highlightEventId = highlightAuthor,
                highlightAuthor = highlightAuthor,
                highlightKind = highlightKind,
                highlightIdentifier = highlightIdentifier,
                highlightRelaysJson = highlightRelaysJson,
                originalEventJson = originalEventJson,
                pubkey = pubkey,
                isAutoSave = false,
                savedContentBuffer = savedContentBuffer,
                previewTitle = previewTitle,
                previewDescription = previewDescription,
                previewImageUrl = previewImageUrl,
                previewSiteName = previewSiteName,
                highlightAuthorName = highlightAuthorName,
                highlightAuthorAvatarUrl = highlightAuthorUrl,
                articleTitle = articleTitle,
                articleSummary = articleSummary,
                articleIdentifier = articleIdentifier
            )
            val newId = draftDao.insertDraft(draft)
            if (currentDraftId == null) {
                currentDraftId = newId.toInt()
            }
        }
    }

    fun discardDraft() {
        showDraftPrompt = false
        val draftIdToDelete = currentDraftId
        currentDraftId = null
        viewModelScope.launch {
            draftDao.deleteAutoSaveDraft(pubkey)
            if (draftIdToDelete != null) {
                draftDao.deleteById(draftIdToDelete)
            }
        }
    }

    fun deleteDraft(id: Int) {
        viewModelScope.launch {
            draftDao.deleteById(id)
        }
    }

    fun checkDraft() {
        viewModelScope.launch {
            val autoDraft = draftDao.getAutoSaveDraft(pubkey)
            if (autoDraft != null) {
                if (quoteContent.isBlank() && sourceUrl.isBlank() && mediaItems.isEmpty()) {
                     showDraftPrompt = true
                }
            }
        }
    }

    fun applyDraft() {
        viewModelScope.launch {
            val autoDraft = draftDao.getAutoSaveDraft(pubkey)
            autoDraft?.let { loadDraft(it) }
            showDraftPrompt = false
        }
    }

    fun loadDraftById(id: Int) {
        viewModelScope.launch {
            val draft = draftDao.getDraftById(id)
            if (draft != null) {
                loadDraft(draft)
            }
        }
    }

    fun loadDraft(draft: Draft) {
        contentValue = TextFieldValue(draft.content)
        sourceUrl = draft.sourceUrl
        mediaTitle = draft.mediaTitle
        currentDraftId = draft.id
        
        highlightEventId = draft.highlightEventId
        highlightAuthor = draft.highlightAuthor
        highlightKind = draft.highlightKind
        highlightIdentifier = draft.highlightIdentifier
        originalEventJson = draft.originalEventJson
        
        articleTitle = draft.articleTitle ?: ""
        articleSummary = draft.articleSummary ?: ""
        articleIdentifier = draft.articleIdentifier

        if (highlightEventId == null && !originalEventJson.isNullOrBlank()) {
            try {
                val originalEvent = JSONObject(originalEventJson!!)
                highlightEventId = originalEvent.optString("id").takeIf { it.isNotBlank() }
                highlightAuthor = originalEvent.optString("pubkey").takeIf { it.isNotBlank() }
                highlightKind = originalEvent.optInt("kind", 1)
            } catch (_: Exception) {}
        }
        
        savedContentBuffer = draft.savedContentBuffer ?: ""
        previewTitle = draft.previewTitle
        previewDescription = draft.previewDescription
        previewImageUrl = draft.previewImageUrl
        previewSiteName = draft.previewSiteName
        highlightAuthorName = draft.highlightAuthorName
        highlightAuthorUrl = draft.highlightAuthorAvatarUrl
        try {
            draft.highlightRelaysJson?.let { jsonString ->
                if (jsonString.isNotEmpty()) {
                    val jsonArray = org.json.JSONArray(jsonString)
                    highlightRelays.clear()
                    for (i in 0 until jsonArray.length()) {
                        highlightRelays.add(jsonArray.getString(i))
                    }
                } else {
                    highlightRelays.clear()
                }
            } ?: highlightRelays.clear()
        } catch (e: Exception) {
            Log.e("ProcessTextViewModel", "Error parsing highlightRelaysJson: ${e.message}")
            highlightRelays.clear()
        }
        
        val loadedKind = PostKind.entries.find { it.kind == draft.kind } ?: PostKind.NOTE
        postKind = loadedKind
        
        if (loadedKind == PostKind.REPOST && !availableKinds.contains(PostKind.REPOST)) {
            availableKinds = availableKinds + PostKind.REPOST
        } else if (highlightEventId != null && !availableKinds.contains(PostKind.REPOST)) {
            availableKinds = availableKinds + PostKind.REPOST
        }
        
        deserializeMediaItems(draft.mediaJson)
        
        mediaItems.forEach { item ->
            processedMediaUris[item.id] = item.uri
        }
    }

    private fun serializeMediaItems(items: List<MediaUploadState>): String {
        val array = org.json.JSONArray()
        items.forEach { item ->
            val obj = JSONObject()
            obj.put("uri", item.uri.toString())
            obj.putOpt("uploadedUrl", item.uploadedUrl)
            obj.putOpt("mimeType", item.mimeType)
            obj.putOpt("hash", item.hash)
            obj.put("size", item.size)
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeMediaItems(json: String) {
        try {
            val array = org.json.JSONArray(json)
            mediaItems.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val item = MediaUploadState(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    uri = Uri.parse(obj.getString("uri")),
                    mimeType = obj.optString("mimeType").takeIf { it.isNotEmpty() }
                ).apply {
                    uploadedUrl = obj.optString("uploadedUrl").takeIf { it != "null" && it.isNotEmpty() }
                    hash = obj.optString("hash").takeIf { it != "null" && it.isNotEmpty() }
                    size = obj.optLong("size", 0L)
                }
                mediaItems.add(item)
            }
        } catch (_: Exception) {}
    }

    fun setKind(kind: PostKind) {
        val oldKind = postKind
        postKind = kind
        
        if (kind == PostKind.REPOST && !availableKinds.contains(PostKind.REPOST)) {
            availableKinds = availableKinds + PostKind.REPOST
        }
        
        if (kind == PostKind.REPOST) {
            savedContentBuffer = quoteContent
            quoteContent = ""
        } else if (oldKind == PostKind.REPOST) {
            quoteContent = savedContentBuffer
        }

        val sUrl = sourceUrl
        
        if (oldKind == PostKind.NOTE && kind != PostKind.NOTE) {
            var content = quoteContent.trim()
            
            mediaItems.reversed().forEach { item ->
                val mUrl = item.uploadedUrl
                if (mUrl != null && content.endsWith(mUrl)) {
                    content = content.removeSuffix(mUrl).trim()
                }
            }
            
            if (sUrl.isNotBlank() && content.endsWith(sUrl)) {
                 content = content.removeSuffix(sUrl).trim()
            }
            
            mediaItems.reversed().forEach { item ->
                val mUrl = item.uploadedUrl
                if (mUrl != null && content.endsWith(mUrl)) {
                    content = content.removeSuffix(mUrl).trim()
                }
            }
            
            quoteContent = content
        }
        
        if (kind == PostKind.NOTE) {
            var content = quoteContent
            
            if (sUrl.isNotBlank() && !content.contains(sUrl)) {
                 val prefix = if (content.isNotBlank()) "\n\n" else ""
                 content += "$prefix$sUrl"
            }
            
            mediaItems.forEach { item ->
                val mUrl = item.uploadedUrl
                if (mUrl != null && !content.contains(mUrl)) {
                    val prefix = if (content.isNotBlank()) "\n\n" else ""
                    content += "$prefix$mUrl"
                }
            }
            
            quoteContent = content
        }
    }

    fun prepareEventJson(createdAt: Long? = null): String {
        val event = JSONObject()
        event.put("created_at", createdAt ?: (System.currentTimeMillis() / 1000))
        event.put("pubkey", pubkey ?: "")
        event.put("id", "")
        event.put("sig", "")
        
        val tags = org.json.JSONArray()
        
        val effectiveKind = postKind
        
        when (effectiveKind) {
             PostKind.NOTE -> {
                 event.put("kind", 1)
                 var content = quoteContent.trim()
                 if (sourceUrl.isNotBlank() && !content.contains(sourceUrl)) {
                     content += "\n\n$sourceUrl"
                 }
                 mediaItems.filter { it.uploadedUrl != null }.forEach { item ->
                    val url = item.uploadedUrl!!
                    if (!content.contains(url)) {
                        val prefix = if (content.isNotBlank()) "\n\n" else ""
                        content += "$prefix$url"
                    }
                 }
                 
                 event.put("content", content)
                 event.put("tags", tags)
             }
             PostKind.REPOST -> {
                 if (quoteContent.isBlank()) {
                     if (highlightEventId == null && !originalEventJson.isNullOrBlank()) {
                         try {
                             val originalEvent = JSONObject(originalEventJson!!)
                             highlightEventId = originalEvent.optString("id").takeIf { it.isNotBlank() }
                             highlightAuthor = originalEvent.optString("pubkey").takeIf { it.isNotBlank() }
                             highlightKind = originalEvent.optInt("kind", 1)
                         } catch (_: Exception) {}
                     }

                     val kind = if (highlightKind == 1 || highlightKind == null) 6 else 16
                     event.put("kind", kind)
                     event.put("content", originalEventJson ?: "")
                     
                     if (highlightEventId != null) {
                         tags.put(org.json.JSONArray().put("e").put(highlightEventId).put(highlightRelays.firstOrNull() ?: ""))
                     } else {
                         originalEventJson?.let { 
                             try {
                                 val tagsArr = JSONObject(it).optJSONArray("tags")
                                 if (tagsArr != null) {
                                     for (i in 0 until tagsArr.length()) {
                                         val t = tagsArr.optJSONArray(i)
                                         if (t != null && t.length() >= 2 && t.getString(0) == "e") {
                                              tags.put(t)
                                              break
                                         }
                                     }
                                 }
                             } catch (_: Exception) {}
                         }
                     }

                     if (highlightAuthor != null) {
                         tags.put(org.json.JSONArray().put("p").put(highlightAuthor))
                     }
                     
                     if (highlightKind != null && highlightKind != 1) {
                         tags.put(org.json.JSONArray().put("k").put(highlightKind.toString()))
                     }
                 } else {
                     event.put("kind", 1)
                     var content = quoteContent.trim()
                     if (sourceUrl.isNotBlank() && !content.contains(sourceUrl)) {
                         content += "\n\n$sourceUrl"
                     }
                     event.put("content", content)
                     
                     if (highlightEventId != null) {
                         val qTag = org.json.JSONArray().put("q").put(highlightEventId)
                         highlightRelays.firstOrNull()?.let { qTag.put(it) }
                         highlightAuthor?.let { qTag.put(it) }
                         tags.put(qTag)
                     }
                 }
                 event.put("tags", tags)
             }
             PostKind.HIGHLIGHT -> {
                 event.put("kind", 9802)
                 event.put("content", quoteContent.trim())
                 
                 val firstHighlightRelay = highlightRelays.firstOrNull()
                 if (highlightEventId != null) {
                     val eTag = org.json.JSONArray().put("e").put(highlightEventId)
                     firstHighlightRelay?.let { eTag.put(it) }
                     tags.put(eTag)
                 }
                 if (highlightAuthor != null) {
                     val pTag = org.json.JSONArray().put("p").put(highlightAuthor)
                     firstHighlightRelay?.let { pTag.put(it) }
                     tags.put(pTag)
                 }
                 if (highlightKind != null) {
                     tags.put(org.json.JSONArray().put("k").put(highlightKind.toString()))
                 }
                 if (highlightIdentifier != null && highlightAuthor != null && highlightKind != null) {
                     tags.put(org.json.JSONArray().put("a").put("${highlightKind}:${highlightAuthor}:${highlightIdentifier}"))
                 }

                 if (sourceUrl.isNotBlank()) {
                     tags.put(org.json.JSONArray().put("r").put(sourceUrl))
                 }
                 val altText = if (highlightKind == 1) "A Short Note" else "Highlight: \"${quoteContent.take(50)}...\""
                 tags.put(org.json.JSONArray().put("alt").put(altText))
                 event.put("tags", tags)
             }
             PostKind.MEDIA -> {
                 val firstMime = mediaItems.firstOrNull()?.mimeType ?: "image/"
                 val kind = if (firstMime.startsWith("image/")) 20 else 22
                 event.put("kind", kind)
                 
                 event.put("content", quoteContent.trim())
                 
                 val label = if (kind == 20) "Image" else "Video"
                 val title = if (mediaTitle.isNotBlank()) mediaTitle else "My $label"
                 tags.put(org.json.JSONArray().put("title").put(title))
                 
                 mediaItems.filter { it.uploadedUrl != null }.forEach { item ->
                     val primaryUploadedUrl = item.uploadedUrl!!
                     
                     primaryUploadedUrl.substringAfterLast('.', "")
                     val filename = primaryUploadedUrl.substringAfterLast('/')

                     val imeta = org.json.JSONArray()
                     imeta.put("imeta")
                     imeta.put("url $primaryUploadedUrl")
                     item.mimeType?.let { imeta.put("m $it") }
                     item.hash?.let { imeta.put("x $it") }
                     if (item.size > 0) imeta.put("size ${item.size}")
                     
                     val primaryBaseUrl = Uri.parse(primaryUploadedUrl).let { "${it.scheme}://${it.host}" }

                     item.serverResults.value.filter { it.second }.forEach { (baseUrl, _) ->
                         val fallbackUrl = "$baseUrl/$filename"
                         imeta.put("fallback $fallbackUrl")
                     }
                     
                     tags.put(imeta)
                     
                     item.mimeType?.let { tags.put(org.json.JSONArray().put("m").put(it)) }
                     item.hash?.let { tags.put(org.json.JSONArray().put("x").put(it)) }
                     
                     item.serverResults.value.filter { it.second }.forEach { (baseUrl, _) ->
                         tags.put(org.json.JSONArray().put("url").put(baseUrl))
                     }
                 }
                 event.put("tags", tags)
             }
             PostKind.FILE_METADATA -> {
                 val firstItem = mediaItems.firstOrNull { it.uploadedUrl != null }
                 if (firstItem != null) {
                     event.put("kind", 1063)
                     event.put("content", quoteContent.trim())
                     tags.put(org.json.JSONArray().put("url").put(firstItem.uploadedUrl))
                     firstItem.mimeType?.let { tags.put(org.json.JSONArray().put("m").put(it)) }
                     firstItem.hash?.let { tags.put(org.json.JSONArray().put("x").put(it)) }
                     event.put("tags", tags)
                 }
             }
             PostKind.ARTICLE -> {
                 event.put("kind", 30023)
                 event.put("content", quoteContent.trim())
                 
                 val id = articleIdentifier ?: UUID.randomUUID().toString().take(8)
                 tags.put(org.json.JSONArray().put("d").put(id))
                 
                 if (articleTitle.isNotBlank()) tags.put(org.json.JSONArray().put("title").put(articleTitle))
                 if (articleSummary.isNotBlank()) tags.put(org.json.JSONArray().put("summary").put(articleSummary))
                 
                 // Use first image as cover
                 mediaItems.firstOrNull { it.uploadedUrl != null && it.mimeType?.startsWith("image/") == true }?.let {
                     tags.put(org.json.JSONArray().put("image").put(it.uploadedUrl))
                 }
                 
                 tags.put(org.json.JSONArray().put("published_at").put((System.currentTimeMillis() / 1000).toString()))
                 
                 // Extract hashtags as topics
                 "#([a-zA-Z0-9_]+)".toRegex().findAll(quoteContent).forEach { match ->
                     tags.put(org.json.JSONArray().put("t").put(match.groupValues[1].lowercase()))
                 }
                 
                 event.put("tags", tags)
             }
        }
        return event.toString()
    }

    fun prepareBulkFileMetadataEvents(): List<String> {
        val events = mutableListOf<String>()
        mediaItems.filter { it.uploadedUrl != null }.forEach { item ->
            val event = JSONObject()
            event.put("created_at", System.currentTimeMillis() / 1000)
            event.put("pubkey", pubkey ?: "")
            event.put("kind", 1063)
            event.put("content", quoteContent.trim())
            
            val tags = org.json.JSONArray()
            tags.put(org.json.JSONArray().put("url").put(item.uploadedUrl!!))
            item.mimeType?.let { tags.put(org.json.JSONArray().put("m").put(it)) }
            item.hash?.let { tags.put(org.json.JSONArray().put("x").put(it)) }
            item.size.takeIf { it > 0 }?.let { tags.put(org.json.JSONArray().put("size").put(it.toString())) }
            
            if (mediaTitle.isNotBlank()) tags.put(org.json.JSONArray().put("title").put(mediaTitle))
            
            event.put("tags", tags)
            events.add(event.toString())
        }
        return events
    }

    fun publishPost(signedEventJson: String) {
        publishPosts(listOf(signedEventJson))
    }

    fun publishPosts(signedEventsJson: List<String>) {
        isPublishing = true
        publishSuccess = null
        publishStatus = "Fetching relay list..."
        viewModelScope.launch {
            try {
                val relaysToPublish = withContext(Dispatchers.IO) {
                    val baseRelays = try {
                        val fetched = relayManager.fetchRelayList(pubkey!!, isRead = false)
                        if (fetched.isEmpty()) listOf("wss://relay.damus.io", "wss://nos.lol") else fetched
                    } catch (e: Exception) {
                        listOf("wss://relay.damus.io", "wss://nos.lol")
                    }
                    
                    val combinedRelays = mutableListOf<String>().apply { addAll(baseRelays) }
                    if (settingsRepository.isCitrineRelayEnabled(pubkey)) {
                        combinedRelays.add("ws://127.0.0.1:4869")
                    }
                    combinedRelays.distinct()
                }
                
                publishStatus = "Broadcasting ${signedEventsJson.size} post(s) to ${relaysToPublish.size} relays..."
                val relaySuccessMap = mutableMapOf<String, Boolean>()
                relaysToPublish.forEach { relaySuccessMap[it] = false }
                
                var totalPostSuccess = 0
                signedEventsJson.forEach { signedEvent ->
                    val results = withContext(Dispatchers.IO) {
                         relayManager.publishEvent(signedEvent, relaysToPublish)
                    }
                    val nonLocalhostSuccess = results.filter { it.key != "ws://127.0.0.1:4869" }.any { it.value }
                    if (nonLocalhostSuccess) {
                        totalPostSuccess++
                    }
                    results.forEach { (relay, success) ->
                        if (success) {
                            relaySuccessMap[relay] = true
                        }
                    }
                }
                
                delay(1000)
                
                val successfulRelaysCount = relaySuccessMap.count { it.value }
                if (totalPostSuccess > 0) {
                    publishStatus = "Success! Published to $successfulRelaysCount/${relaysToPublish.size} relays."
                    publishSuccess = true
                    discardDraft()
                } else {
                    if (!isNetworkAvailable()) {
                        val mediaJson = serializeMediaItems(mediaItems)
                        val highlightRelaysJson = if (highlightRelays.isNotEmpty()) {
                            org.json.JSONArray(highlightRelays).toString()
                        } else {
                            null
                        }
                        val draft = Draft(
                            content = quoteContent,
                            sourceUrl = sourceUrl,
                            kind = postKind.kind,
                            mediaJson = mediaJson,
                            mediaTitle = mediaTitle,
                            highlightEventId = highlightAuthor,
                            highlightAuthor = highlightAuthor,
                            highlightKind = highlightKind,
                            highlightIdentifier = highlightIdentifier,
                            highlightRelaysJson = highlightRelaysJson,
                            originalEventJson = originalEventJson,
                            pubkey = pubkey,
                            isScheduled = true,
                            isOfflineRetry = true,
                            scheduledAt = System.currentTimeMillis(),
                            signedJson = signedEventsJson.firstOrNull(),
                            isAutoSave = false,
                            articleTitle = articleTitle,
                            articleSummary = articleSummary,
                            articleIdentifier = articleIdentifier
                        )
                        val id = draftDao.insertDraft(draft)
                        com.ryans.nostrshare.utils.SchedulerUtils.enqueueOfflineRetry(
                            NostrShareApp.getInstance(),
                            draft.copy(id = id.toInt())
                        )
                        publishStatus = "Offline. Note will be sent when internet returns."
                        publishSuccess = true
                        clearContent()
                    } else {
                        publishStatus = "Failed to publish."
                        publishSuccess = false
                    }
                }
                isPublishing = false
            } catch (e: Exception) {
                if (!isNetworkAvailable()) {
                    viewModelScope.launch {
                        val mediaJson = serializeMediaItems(mediaItems)
                        val highlightRelaysJson = if (highlightRelays.isNotEmpty()) {
                            org.json.JSONArray(highlightRelays).toString()
                        } else {
                            null
                        }
                        val draft = Draft(
                            content = quoteContent,
                            sourceUrl = sourceUrl,
                            kind = postKind.kind,
                            mediaJson = mediaJson,
                            mediaTitle = mediaTitle,
                            highlightEventId = highlightAuthor,
                            highlightAuthor = highlightAuthor,
                            highlightKind = highlightKind,
                            highlightIdentifier = highlightIdentifier,
                            highlightRelaysJson = highlightRelaysJson,
                            originalEventJson = originalEventJson,
                            pubkey = pubkey,
                            isScheduled = true,
                            isOfflineRetry = true,
                            scheduledAt = System.currentTimeMillis(),
                            signedJson = signedEventsJson.firstOrNull(),
                            isAutoSave = false,
                            articleTitle = articleTitle,
                            articleSummary = articleSummary,
                            articleIdentifier = articleIdentifier
                        )
                        val id = draftDao.insertDraft(draft)
                        com.ryans.nostrshare.utils.SchedulerUtils.enqueueOfflineRetry(
                            NostrShareApp.getInstance(),
                            draft.copy(id = id.toInt())
                        )
                        publishStatus = "Offline. Note will be sent when internet returns."
                        publishSuccess = true
                        clearContent()
                        isPublishing = false
                    }
                } else {
                    publishStatus = "Error: ${e.message}"
                    publishSuccess = false
                    isPublishing = false
                }
            }
        }
    }

    fun finalizeBlossomServerListPublish(signedEventJson: String) {
        isPublishing = true
        publishStatus = "Publishing server list to relays..."
        viewModelScope.launch {
            try {
                val hexKey = pubkey ?: return@launch
                val baseRelays = withContext(Dispatchers.IO) {
                    relayManager.fetchRelayList(hexKey, isRead = false)
                }
                
                val combinedRelays = mutableListOf<String>().apply { addAll(baseRelays) }
                if (settingsRepository.isCitrineRelayEnabled(pubkey)) {
                    combinedRelays.add("ws://127.0.0.1:4869")
                }
                
                val results = withContext(Dispatchers.IO) {
                    relayManager.publishEvent(signedEventJson, combinedRelays.distinct())
                }
                val successCount = results.count { it.value }
                if (successCount > 0) {
                    publishStatus = "Server list published to $successCount relays."
                    publishSuccess = true
                } else {
                    publishStatus = "Failed to publish server list."
                    publishSuccess = false
                }
            } catch (e: Exception) {
                publishStatus = "Error: ${e.message}"
                publishSuccess = false
            } finally {
                isPublishing = false
            }
        }
    }

    fun performUserSearch(query: String) {
        userSearchQuery = query
        val cleanQuery = query.trim().removePrefix("@").removePrefix("nostr:")
        
        if (cleanQuery.length < 2) {
            userSearchResults.clear()
            return
        }
        
        val localMatches = mutableListOf<Pair<String, UserProfile>>()
        usernameCache.forEach { (pk, profile) ->
            if (profile.name?.contains(cleanQuery, ignoreCase = true) == true || pk.contains(cleanQuery, ignoreCase = true)) {
                localMatches.add(pk to profile)
            }
        }
        
        followedPubkeys.forEach { pk ->
            if (pk.contains(cleanQuery, ignoreCase = true) && !usernameCache.containsKey(pk)) {
                localMatches.add(pk to UserProfile(name = null, pictureUrl = null))
            }
        }

        userSearchResults.clear()
        userSearchResults.addAll(localMatches.sortedByDescending { followedPubkeys.contains(it.first) })
        
        isSearchingUsers = true
        viewModelScope.launch {
            try {
                val relayResults = relayManager.searchUsers(cleanQuery)
                
                val merged = (localMatches + relayResults).distinctBy { it.first }
                val sortedResults = merged.sortedWith(compareByDescending<Pair<String, UserProfile>> { 
                    followedPubkeys.contains(it.first)
                }.thenBy { it.second.name?.lowercase() ?: "" })
                
                userSearchResults.clear()
                userSearchResults.addAll(sortedResults)
                
                var cacheUpdated = false
                relayResults.forEach { pair ->
                    val pk = pair.first
                    val profile = pair.second
                    if (profile.name?.isNotEmpty() == true) {
                        val current = usernameCache[pk]
                        if (current == null || (profile.createdAt ?: 0L) > (current.createdAt ?: 0L)) {
                            usernameCache[pk] = profile
                            cacheUpdated = true
                        }
                    }
                }
                if (cacheUpdated) {
                    settingsRepository.setUsernameCache(usernameCache.toMap())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSearchingUsers = false
            }
        }
    }

    fun resolveUsername(npub: String) {
        val resolvedPk = if (npub.length == 64 && npub.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            npub
        } else {
            val entity = NostrUtils.findNostrEntity(npub) ?: return
            if (entity.type != "npub" && entity.type != "nprofile") return
            entity.id
        }
        
        if (usernameCache.containsKey(resolvedPk) && usernameCache[resolvedPk]?.name != null) return
        
        viewModelScope.launch {
            try {
                val profile = relayManager.fetchUserProfile(resolvedPk)
                if (profile != null) {
                    usernameCache[resolvedPk] = profile
                    settingsRepository.setUsernameCache(usernameCache.toMap())
                }
            } catch (_: Exception) {}
        }
    }
}
