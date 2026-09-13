package com.swarupbaral.smarttgdownloader.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swarupbaral.smarttgdownloader.AppContainer
import com.swarupbaral.smarttgdownloader.data.AppPreferences
import com.swarupbaral.smarttgdownloader.data.ChannelDefaults
import com.swarupbaral.smarttgdownloader.data.ChannelSettings
import com.swarupbaral.smarttgdownloader.data.DownloadManager
import com.swarupbaral.smarttgdownloader.data.DownloadsMigration
import com.swarupbaral.smarttgdownloader.data.DownloadsStore
import com.swarupbaral.smarttgdownloader.data.NetworkGuard
import com.swarupbaral.smarttgdownloader.data.ZipRequest
import com.swarupbaral.smarttgdownloader.data.QueuedDownload
import com.swarupbaral.smarttgdownloader.data.SavedRange
import com.swarupbaral.smarttgdownloader.data.TelegramRepository
import com.swarupbaral.smarttgdownloader.data.resolveChannelSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates the analyze → filter → select → download pipeline.
 *
 * Link analysis is deliberately *complete*: [analyze] walks the whole chat
 * history and collects every downloadable file, then the user's filter decides
 * what is shown. That split is what lets the UI say "this link has no files at
 * all" and "this link has files, but none of them are Audio" as two different,
 * truthful messages instead of one vague empty screen.
 *
 * The download itself is NOT run here. It is handed to [DownloadManager], which
 * runs it in a foreground service so it survives this ViewModel being cleared.
 * This class mirrors the engine's state into the UI and does nothing else with
 * it.
 *
 * Scan tokens: every Analyze press mints a monotonically increasing token.
 * A new token immediately invalidates any in-flight scan, so results from a
 * previous link can never repopulate the results sheet.
 */
class DownloadViewModel : ViewModel() {

    data class FileProgress(
        val downloadedBytes: Long,
        val totalBytes: Long
    ) {
        val fraction: Float
            get() = if (totalBytes > 0) {
                (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            } else 0f

        val isDone: Boolean
            get() = totalBytes > 0 && downloadedBytes >= totalBytes
    }

    /** Why the last scan produced nothing to show, phrased for the user. */
    data class EmptyResult(val title: String, val detail: String)

    /**
     * Stage of the results flow: find out what is there, then choose how much
     * of it to take, then confirm the size before anything is fetched.
     *
     * The order matters for a screen reader user: the count is announced
     * first, and the download button does not become reachable until a
     * selection (and therefore a total size) exists.
     */
    enum class SelectionStage {
        /** "N files found. Download all, or select a range?" */
        CHOOSE,

        /** The range picker is open, and nothing is selected yet. */
        RANGE,

        /** A selection exists; the totals are shown and Start download is live. */
        CONFIRM
    }

    data class UiState(
        val link: String = "",
        val analyzing: Boolean = false,
        /** Live "N messages checked, M found" text while a scan is running. */
        val scanProgress: String? = null,
        val statusMessage: String? = null,
        val errorMessage: String? = null,
        val scanResult: TelegramRepository.ScanResult? = null,
        val selected: Set<Long> = emptySet(),
        val downloading: Boolean = false,
        val progressText: String? = null,
        /** Live per-file progress keyed by the scanned row's messageId. */
        val fileProgress: Map<Long, FileProgress> = emptyMap(),
        /** Active filter; one of [AppPreferences.DOWNLOAD_CATEGORIES]. */
        val categoryFilter: String = AppPreferences.CATEGORY_ALL,
        /** Set when the link yielded nothing to display. */
        val emptyResult: EmptyResult? = null,
        /** Which part of the choose → range → confirm flow is on screen. */
        val stage: SelectionStage = SelectionStage.CHOOSE,
        /**
         * The file type the loaded results were scanned for. A scan now looks
         * for one type only, so results stop being valid the moment the filter
         * changes — comparing against this is how that is detected.
         */
        val scannedFilter: String? = null,
        /** Why individual files failed, keyed by messageId, for row wording. */
        val failures: Map<Long, String> = emptyMap(),
        /**
         * The engine's run, mirrored whole.
         *
         * The completion surface needs more than "is it running": the phase,
         * the summary and the folder the files landed in. The status line stays
         * free for scan messages rather than repeating the run's summary.
         */
        val runState: DownloadManager.RunState = DownloadManager.RunState(),
        /**
         * Whether "Download ZIP" should also keep the files it packs.
         *
         * Mirrored from preferences so the checkbox beside the ZIP button and the
         * request that gets queued can never disagree.
         */
        val keepZipFiles: Boolean = false
    ) {
        /** Files from the last scan that pass the active filter. */
        val visibleFiles: List<TelegramRepository.ScannedFile>
            get() = scanResult?.files.orEmpty().filter { passesFilter(it) }

        val visibleSelected: List<TelegramRepository.ScannedFile>
            get() = visibleFiles.filter { it.messageId in selected }

        /**
         * Total bytes of the selection, from the sizes Telegram reported when
         * the channel was scanned.
         */
        val selectedBytes: Long get() = visibleSelected.sumOf { it.sizeBytes }

        /**
         * Selected files Telegram did not give a size for. They cannot be
         * counted, so the displayed total is stated as a floor rather than as
         * a number that would turn out wrong.
         */
        val unknownSizeCount: Int get() = visibleSelected.count { it.sizeBytes <= 0 }

        private fun passesFilter(file: TelegramRepository.ScannedFile): Boolean =
            categoryFilter == AppPreferences.CATEGORY_ALL || file.category == categoryFilter
    }

    private val repository = AppContainer.repository

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _lastRange = MutableStateFlow(AppContainer.appPreferences.lastRange.value)

    /**
     * Range picker values for the channel currently loaded — that channel's
     * remembered range when it has one, otherwise the app-wide default. Held
     * here rather than read from preferences so switching channels switches the
     * picker with it.
     */
    val lastRange: StateFlow<SavedRange> = _lastRange.asStateFlow()

    /** The channel the current results belong to, once a link has resolved. */
    private var currentChatId: Long? = null

    /** True while the engine holds queued work, including across restarts. */
    val downloadRunning: StateFlow<DownloadManager.RunState> = DownloadManager.state

    /**
     * Files the engine could not save, so the UI can offer to try them again.
     * Read straight from the engine: failures belong to the run, not a screen.
     */
    val failedDownloads: StateFlow<List<QueuedDownload>> = DownloadManager.failedItems

    private var scanJob: Job? = null
    private var scanToken: Long = 0L

    init {
        // The filter lives in Settings, so Home follows it rather than keeping
        // a second, divergent copy of the choice.
        viewModelScope.launch {
            AppContainer.appPreferences.defaultCategory.collect { category ->
                applyFilter(category)
            }
        }

        // Failure reasons come straight from the engine, so a run that failed
        // while this screen was closed still shows up in the rows.
        viewModelScope.launch {
            DownloadManager.failures.collect { reasons ->
                _uiState.value = _uiState.value.copy(failures = reasons)
            }
        }

        // The ZIP-then-keep-files choice lives beside the ZIP button, and is
        // remembered here rather than in the dialog so it is set once.
        viewModelScope.launch {
            AppContainer.appPreferences.zipKeepsFiles.collect { keep ->
                _uiState.value = _uiState.value.copy(keepZipFiles = keep)
            }
        }

        // The one-time folder migration reports here, so a user whose files were
        // moved by an update is told where they went rather than discovering an
        // empty folder. A StateFlow, so a report from before this screen existed
        // still arrives.
        viewModelScope.launch {
            DownloadsMigration.lastReport.collect { report ->
                report?.summary()?.let { message ->
                    _uiState.value = _uiState.value.copy(statusMessage = message)
                }
            }
        }

        // Mirror the engine's per-file progress onto the scanned rows.
        viewModelScope.launch {
            DownloadManager.fileProgress.collect { progress ->
                _uiState.value = _uiState.value.copy(
                    fileProgress = progress.mapValues { (_, value) ->
                        FileProgress(value.downloadedBytes, value.totalBytes)
                    }
                )
            }
        }

        // Mirror the run itself: whether it is running, what it is saving now,
        // and how it finished.
        viewModelScope.launch {
            DownloadManager.state.collect { run ->
                _uiState.value = _uiState.value.copy(
                    downloading = run.isRunning,
                    progressText = if (run.isRunning && run.currentName != null) {
                        "Saving ${run.currentName} (${run.completed + 1} of ${run.total})"
                    } else {
                        null
                    },
                    runState = run
                )
            }
        }
    }

    /**
     * Clears a finished run so the completion card goes away.
     *
     * Only the report is cleared: the files are in Downloads and the history
     * entries stay, so nothing the user downloaded depends on this.
     */
    fun acknowledgeRun() {
        DownloadManager.acknowledge()
    }

    fun updateLink(value: String) {
        _uiState.value = _uiState.value.copy(link = value)
    }

    /**
     * Changes the active filter. Persists it, so the option list and the
     * selection stay identical wherever the filter is edited (Settings or
     * Home) — there is one filter, not one per screen.
     */
    fun setFilter(category: String) {
        // With a channel loaded this is a per-channel choice; before any
        // analysis it edits the app-wide default instead.
        if (currentChatId == null) {
            AppContainer.appPreferences.setDefaultCategory(category)
        } else {
            rememberOnCurrentChannel { it.copy(filter = category) }
        }
        applyFilter(category)
    }

    /**
     * Adopts [category] as the active filter, discarding results that were
     * scanned for a different type.
     *
     * Because a scan now looks for exactly one file type, yesterday's answer for
     * "Audio" says nothing about "Video". Keeping the old rows on screen would
     * invite the user to download files that were never looked for, so the stale
     * result is dropped and the sheet's empty state says to analyze again.
     */
    private fun applyFilter(category: String) {
        val state = _uiState.value
        if (state.categoryFilter == category) return
        val stale = state.scanResult != null && state.scannedFilter != category
        _uiState.value = state.copy(
            categoryFilter = category,
            scanResult = if (stale) null else state.scanResult,
            selected = if (stale) emptySet() else state.selected,
            stage = SelectionStage.CHOOSE,
            emptyResult = null,
            statusMessage = if (stale) {
                "File type changed to $category. Analyze the link again to scan for " +
                    "$category files."
            } else {
                state.statusMessage
            }
        )
        if (!stale) {
            pruneSelectionToVisible()
            refreshEmptyResult()
        }
    }

    /**
     * Loads [source]'s remembered preferences and makes them current.
     *
     * [filter] is the type the user picked on screen before pressing Analyze,
     * and it is what the scan uses — a remembered per-channel filter must never
     * quietly replace a choice the user just made. Range and scan limit *are*
     * read from the channel: those only make sense per channel, and unlike the
     * type they cannot be chosen before the link resolves.
     *
     * The entry is written back straight away, so a channel is recorded the
     * first time it is analyzed and shows up under Settings even if the user
     * never touches anything about it.
     */
    private fun applyChannelSettings(
        source: TelegramRepository.ResolvedSource,
        filter: String
    ): ChannelSettings {
        currentChatId = source.chatId

        val saved = AppContainer.channelPreferences.get(source.chatId)
        val defaults = AppContainer.appPreferences.lastRange.value
        val settings = resolveChannelSettings(
            chatId = source.chatId,
            title = source.title,
            chosenFilter = filter,
            saved = saved,
            defaults = ChannelDefaults(
                rangeFrom = defaults.from,
                rangeTo = defaults.to,
                scanLimit = AppContainer.appPreferences.scanLimit.value
            )
        )
        AppContainer.channelPreferences.save(settings)
        _lastRange.value = SavedRange(settings.rangeFrom, settings.rangeTo)
        return settings
    }

    /** Edits the current channel's remembered preferences, if there is one. */
    private fun rememberOnCurrentChannel(transform: (ChannelSettings) -> ChannelSettings) {
        val chatId = currentChatId ?: return
        val existing = AppContainer.channelPreferences.get(chatId) ?: return
        AppContainer.channelPreferences.save(
            transform(existing).copy(updatedAtEpochMs = System.currentTimeMillis())
        )
    }

    /**
     * Raises this channel's scan limit one step and analyzes again.
     *
     * Offered right where the truncation is reported, so deepening a single
     * channel's search does not mean hunting for a global setting that would
     * then apply to every other channel too.
     */
    fun searchDeeper() {
        val current = _uiState.value.scanResult?.fileLimit ?: return
        val next = AppPreferences.nextScanLimit(current) ?: return
        rememberOnCurrentChannel { it.copy(scanLimit = next) }
        analyze()
    }

    /**
     * Adopts [category] and starts the scan in one step.
     *
     * The type has to be settled before the walk begins — the scan collects one
     * type — so the dialog that asks for it hands the answer straight here. Doing
     * it in one call keeps the two from being separated by a recomposition in
     * which the filter is set but nothing is scanning, or worse, scanning with
     * the previous type.
     */
    fun analyzeWithFilter(category: String) {
        setFilter(category)
        analyze()
    }

    fun analyze() {
        val link = _uiState.value.link.trim()
        if (link.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Telegram link is required.")
            return
        }

        val token = ++scanToken
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(
            analyzing = true,
            errorMessage = null,
            statusMessage = null,
            scanProgress = "Resolving the channel…",
            scanResult = null,
            scannedFilter = null,
            selected = emptySet(),
            stage = SelectionStage.CHOOSE,
            emptyResult = null
        )

        scanJob = viewModelScope.launch {
            try {
                // Resolve before scanning: the channel identity is what its
                // remembered scan limit is keyed on, and that limit decides how
                // deep the walk goes.
                val source = repository.resolveSource(link)
                if (token != scanToken) return@launch
                val settings = applyChannelSettings(source, _uiState.value.categoryFilter)

                // Scan for the chosen type only. The dialog that appears when
                // Analyze is pressed has already settled which one, so the walk
                // looks for exactly that — and "All" means every media type, so
                // a big channel is never trawled for types the user ruled out.
                val result = repository.scanChat(
                    source = source,
                    categories = categoriesFor(settings.filter),
                    scanToken = token,
                    // Bounds how long a huge channel can take to analyze.
                    fileLimit = settings.scanLimit,
                    // Called on the IO dispatcher once per history page, so a
                    // slow walk through a big channel still shows progress.
                    onProgress = { scanned, found, waiting ->
                        if (token == scanToken) {
                            _uiState.value = _uiState.value.copy(
                                scanProgress = scanProgressText(scanned, found, waiting)
                            )
                        }
                    }
                )
                if (token != scanToken) return@launch // superseded by a newer scan

                val filter = settings.filter

                _uiState.value = _uiState.value.copy(
                    analyzing = false,
                    scanProgress = null,
                    statusMessage = null,
                    categoryFilter = filter,
                    scannedFilter = filter,
                    scanResult = result,
                    // Nothing is selected yet: the first thing the user is told
                    // is how many files there are, then they choose to take all
                    // of them or a range of them.
                    selected = emptySet(),
                    stage = SelectionStage.CHOOSE
                )
                refreshEmptyResult()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == scanToken) {
                    _uiState.value = _uiState.value.copy(
                        analyzing = false,
                        scanProgress = null,
                        statusMessage = null,
                        errorMessage = e.message ?: "Analysis failed."
                    )
                }
            }
        }
    }

    /**
     * Stops an in-flight analysis. A channel with tens of thousands of messages
     * can take minutes to walk, so the user must be able to back out.
     */
    fun cancelScan() {
        if (!_uiState.value.analyzing) return
        scanToken++ // invalidate anything the running scan might still return
        scanJob?.cancel()
        scanJob = null
        _uiState.value = _uiState.value.copy(
            analyzing = false,
            scanProgress = null,
            statusMessage = "Analysis stopped."
        )
    }

    /**
     * What the scan is doing right now.
     *
     * The waiting case is called out rather than hidden: it is when the scan
     * pauses for seconds on a large channel, and previously the only signal was
     * a frozen count — indistinguishable from a hung scan.
     */
    private fun scanProgressText(scanned: Int, found: Int, waitingForHistory: Boolean): String =
        if (waitingForHistory) {
            "Waiting for Telegram to load older messages… $scanned checked, $found found"
        } else {
            "Scanning… $scanned message(s) checked, $found file(s) found"
        }

    /** The categories a scan for [filter] should collect. */
    private fun categoriesFor(filter: String): Set<String> =
        if (filter == AppPreferences.CATEGORY_ALL) AppPreferences.MEDIA_CATEGORIES.toSet()
        else setOf(filter)

    /**
     * "Download all N files" — takes everything the scan found.
     *
     * Selecting rather than starting, so the size is stated before anything is
     * fetched. Someone on a metered connection needs that chance to back out.
     */
    fun chooseAll() {
        val files = _uiState.value.visibleFiles
        if (files.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            selected = files.map { it.messageId }.toSet(),
            stage = SelectionStage.CONFIRM,
            statusMessage = "All ${files.size} file(s) selected.",
            errorMessage = null
        )
    }

    /** "Select a range" — opens the picker without selecting anything yet. */
    fun chooseRange() {
        _uiState.value = _uiState.value.copy(
            stage = SelectionStage.RANGE,
            statusMessage = null,
            errorMessage = null
        )
    }

    /** Returns to the two starting choices, dropping the selection. */
    fun backToChoose() {
        _uiState.value = _uiState.value.copy(
            selected = emptySet(),
            stage = SelectionStage.CHOOSE,
            statusMessage = null,
            errorMessage = null
        )
    }

    /**
     * Re-queues failed downloads — one file, or all of them.
     *
     * The retry runs through the same engine as any other download, so it
     * survives leaving the app, resumes after a process death, and respects the
     * Wi-Fi-only setting (whose refusal is shown as an error, not swallowed).
     */
    fun retryFailedDownloads(context: Context, messageId: Long? = null) {
        val blocked = DownloadManager.retryFailed(context.applicationContext, messageId)
        _uiState.value = _uiState.value.copy(
            errorMessage = blocked,
            statusMessage = if (blocked == null) "Retrying failed download(s)…" else null
        )
    }

    /** Forgets the failed downloads without retrying them. */
    fun dismissFailedDownloads(context: Context) {
        DownloadManager.dismissFailures(context.applicationContext)
    }

    /**
     * Selects the 1-based inclusive range [from] to [to] of the currently
     * visible files.
     *
     * This is the answer to a channel holding tens of thousands of matches:
     * asking someone to scroll and tick 20 rows out of 50,000 is not a usable
     * selection method. The range is expressed in the same numbering the result
     * list shows, and every rejection explains itself so a screen reader user
     * hears why the range was refused.
     */
    fun selectRange(from: Int, to: Int) {
        val state = _uiState.value
        val files = state.visibleFiles
        if (files.isEmpty()) {
            _uiState.value = state.copy(
                errorMessage = "Analyze a link first, then choose a range."
            )
            return
        }
        val total = files.size
        val problem = when {
            from < 1 || to < 1 -> "Enter a file number of 1 or more."
            from > total || to > total ->
                "This list has $total file(s). Enter numbers between 1 and $total."
            from > to -> "The first number must not be greater than the last number."
            else -> null
        }
        if (problem != null) {
            _uiState.value = state.copy(errorMessage = problem)
            return
        }

        val chosen = files.subList(from - 1, to).map { it.messageId }
        // Remembered only once the range proved valid, so a rejected entry
        // never overwrites a range that worked. Per channel when one is loaded,
        // otherwise it becomes the app-wide default.
        val range = SavedRange(from = from, to = to)
        _lastRange.value = range
        if (currentChatId == null) {
            AppContainer.appPreferences.setLastRange(range)
        } else {
            rememberOnCurrentChannel { it.copy(rangeFrom = from, rangeTo = to) }
        }
        // Applying a range confirms the selection, which is what reveals the
        // Start download action and the total size.
        _uiState.value = state.copy(
            selected = chosen.toSet(),
            stage = SelectionStage.CONFIRM,
            errorMessage = null,
            statusMessage = "Selected ${chosen.size} file(s), number $from to $to."
        )
    }

    fun toggleSelection(messageId: Long) {
        // Only rows the user can actually see may be toggled.
        if (_uiState.value.visibleFiles.none { it.messageId == messageId }) return
        val current = _uiState.value
        val next = current.selected.toMutableSet()
        if (!next.add(messageId)) {
            next.remove(messageId)
        }
        // Hand-picking rows is its own way of confirming a selection, so it
        // moves the sheet forward too; emptying it goes back to the start.
        _uiState.value = current.copy(
            selected = next,
            stage = if (next.isEmpty()) SelectionStage.CHOOSE else SelectionStage.CONFIRM
        )
    }

    fun dismissResults() {
        _uiState.value = _uiState.value.copy(
            scanResult = null,
            scannedFilter = null,
            selected = emptySet(),
            stage = SelectionStage.CHOOSE,
            emptyResult = null
        )
    }

    /**
     * Hands the selected files to [DownloadManager] and returns immediately.
     *
     * Nothing here waits for a download: the engine runs it in a foreground
     * service, so the batch continues if the user leaves the app or this
     * ViewModel goes away.
     */
    fun startDownload(context: Context) {
        val state = _uiState.value
        val result = state.scanResult ?: return
        val targets = state.visibleSelected
        if (targets.isEmpty() || state.downloading) return

        val appContext = context.applicationContext

        // Honour the connection choice from Settings before fetching anything.
        NetworkGuard.blockedReason(
            appContext,
            AppContainer.appPreferences.downloadNetwork.value
        )?.let { blocked ->
            _uiState.value = state.copy(errorMessage = blocked)
            return
        }

        // Resolve the chat from the current scan token only — a stale token's
        // registry entry was already evicted by ScanRegistry.record().
        val chatId = repository.chatIdForToken(scanToken)

        DownloadManager.enqueue(
            context = appContext,
            items = queuedItems(targets, result, chatId)
        )
        _uiState.value = state.copy(errorMessage = null)
    }

    /**
     * Hands the selection to [DownloadManager] to be written as one .zip archive
     * instead of as separate files.
     *
     * Same fetched copies either way, so this shares the connection check, the
     * foreground service and the notification with a normal download; what
     * differs is only where the bytes end up.
     */
    fun startZip(context: Context) {
        val state = _uiState.value
        val result = state.scanResult ?: return
        val targets = state.visibleSelected
        if (targets.isEmpty() || state.downloading) return

        val appContext = context.applicationContext
        NetworkGuard.blockedReason(
            appContext,
            AppContainer.appPreferences.downloadNetwork.value
        )?.let { blocked ->
            _uiState.value = state.copy(errorMessage = blocked)
            return
        }

        val chatId = repository.chatIdForToken(scanToken)
        val refusal = DownloadManager.enqueueZip(
            appContext,
            ZipRequest(
                fileName = DownloadsStore.archiveName(
                    sourceTitle = result.sourceTitle,
                    category = state.categoryFilter,
                    fileCount = targets.size
                ),
                folder = DownloadsStore.channelFolder(result.sourceTitle),
                items = queuedItems(targets, result, chatId),
                keepFiles = state.keepZipFiles
            )
        )
        _uiState.value = state.copy(errorMessage = refusal)
    }

    /**
     * Turns "keep the separate files too" on or off for the next ZIP download.
     *
     * Persisted, because it is a preference about how the user wants their files
     * rather than a choice about one batch.
     */
    fun setKeepZipFiles(value: Boolean) {
        AppContainer.appPreferences.setZipKeepsFiles(value)
        _uiState.value = _uiState.value.copy(keepZipFiles = value)
    }

    /**
     * The selection as queue items, shared by both download paths so they can
     * never disagree about what a queued file is.
     */
    private fun queuedItems(
        targets: List<TelegramRepository.ScannedFile>,
        result: TelegramRepository.ScanResult,
        chatId: Long
    ): List<QueuedDownload> = targets.map { file ->
        QueuedDownload(
            messageId = file.messageId,
            fileId = file.fileId,
            chatId = chatId,
            fileName = file.name,
            category = file.category,
            sourceTitle = result.sourceTitle,
            sizeBytes = file.sizeBytes
        )
    }

    /**
     * Recomputes [UiState.emptyResult] for the current scan + filter.
     *
     * The two cases are kept distinct on purpose: "the link has nothing
     * downloadable" and "nothing matches your filter" need different fixes
     * from the user, and merging them into one message would send someone
     * hunting for content that is actually there.
     */
    private fun refreshEmptyResult() {
        val state = _uiState.value
        val result = state.scanResult
        if (result == null) {
            _uiState.value = state.copy(emptyResult = null)
            return
        }
        val filter = state.categoryFilter
        val all = filter == AppPreferences.CATEGORY_ALL
        val visited = result.scannedMessages
        // An unfinished walk must never be reported as "the channel has none",
        // which is exactly how a large channel came back looking empty.
        if (result.files.isEmpty() && result.historyIncomplete) {
            _uiState.value = state.copy(
                emptyResult = EmptyResult(
                    title = "Scan incomplete",
                    detail = "\"${result.sourceTitle}\" has more messages than Telegram had " +
                        "finished loading, so this scan only reached $visited of them. " +
                        "Analyze the link again to pick up where it left off."
                )
            )
            return
        }
        // A truncated scan must never read as "that is the whole channel".
        val limitNote = if (result.limitReached) {
            " Note: the scan stopped at the " +
                "${AppPreferences.scanLimitLabel(result.fileLimit)}-file limit for this " +
                "channel, so older files were not examined — use \"Search deeper\" to go further."
        } else {
            ""
        }
        // What the walk passed over on its way through. This is what lets an
        // empty answer point at the rest of the channel instead of leaving the
        // user to guess which file type to try next.
        val othersNote = if (!all && result.otherMediaCount > 0) {
            " It does contain about ${result.otherMediaCount} other media file(s), " +
                "so try another file type."
        } else {
            ""
        }
        val empty = when {
            result.files.isEmpty() -> EmptyResult(
                title = if (all) "No files found" else "No $filter files found",
                detail = buildString {
                    append("\"${result.sourceTitle}\" was analyzed — ")
                    append("$visited message(s) scanned — and contains no downloadable ")
                    append(
                        if (all) "audio, video, images or documents."
                        else "$filter files."
                    )
                    append(othersNote)
                    append(limitNote)
                }
            )
            state.visibleFiles.isEmpty() -> EmptyResult(
                title = "No $filter files found",
                detail = "This link has ${result.files.size} downloadable file(s) from the " +
                    "loaded scan, but none are in the \"$filter\" filter." + limitNote
            )
            else -> null
        }
        _uiState.value = state.copy(emptyResult = empty)
    }

    /** Drops selections that the active filter no longer shows. */
    private fun pruneSelectionToVisible() {
        val state = _uiState.value
        if (state.selected.isEmpty()) return
        val visibleIds = state.visibleFiles.map { it.messageId }.toSet()
        if (state.selected.any { it !in visibleIds }) {
            _uiState.value = state.copy(selected = state.selected.intersect(visibleIds))
        }
    }
}
