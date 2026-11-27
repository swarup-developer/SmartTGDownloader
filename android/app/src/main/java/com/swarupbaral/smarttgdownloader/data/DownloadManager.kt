package com.swarupbaral.smarttgdownloader.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.swarupbaral.smarttgdownloader.AppContainer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs the download queue for the whole process, deliberately independent of
 * any ViewModel or Activity.
 *
 * Previously the download loop lived in `DownloadViewModel`: leaving the app
 * destroyed the ViewModel and with it the transfer. The work now lives here and
 * is kept alive by [DownloadService] (a foreground service), so it continues
 * while the user is elsewhere, and the queue is persisted after every completed
 * file so a killed process resumes instead of starting over.
 *
 * Resumption has two layers:
 *  - **Per file** — TDLib keeps partial downloads in its own cache and resumes
 *    from the bytes it already has, so an interrupted file is not re-fetched
 *    from the beginning.
 *  - **Per batch** — [DownloadQueueStore] holds the remaining items, so the
 *    rest of the batch survives a process death.
 *
 * This object holds no Activity or View references; the service owns the
 * notification and the UI observes the flows.
 */
object DownloadManager {

    data class FileProgress(val downloadedBytes: Long, val totalBytes: Long) {
        val fraction: Float
            get() = if (totalBytes > 0) {
                (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            } else 0f

        val isDone: Boolean
            get() = totalBytes > 0 && downloadedBytes >= totalBytes
    }

    enum class Phase { IDLE, RUNNING, FINISHED, FAILED, CANCELLED }

    data class RunState(
        val phase: Phase = Phase.IDLE,
        val total: Int = 0,
        val completed: Int = 0,
        val failed: Int = 0,
        /** Name of the file currently being fetched, for the notification. */
        val currentName: String? = null,
        /** Final summary, set when the run reaches a terminal phase. */
        val message: String? = null,
        /**
         * The channel folder this run last wrote a file into, or null when it
         * has not saved anything yet.
         *
         * Set only on a real save on purpose: offering to open a channel's
         * folder before a file has gone into it would take the user to a folder
         * that does not exist.
         */
        val lastSavedFolder: String? = null,
        /**
         * True while a run is writing an archive rather than fetching files.
         *
         * The wait is local disk work at that point, so the notification and
         * the progress line say "creating ZIP" instead of "downloading".
         */
        val packing: Boolean = false
    ) {
        val isRunning: Boolean get() = phase == Phase.RUNNING

        val fraction: Float get() = if (total > 0) completed.toFloat() / total else 0f

        val isTerminal: Boolean
            get() = phase == Phase.FINISHED || phase == Phase.FAILED || phase == Phase.CANCELLED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val _fileProgress = MutableStateFlow<Map<Long, FileProgress>>(emptyMap())
    val fileProgress: StateFlow<Map<Long, FileProgress>> = _fileProgress.asStateFlow()

    /** Message ids still waiting in this run, for per-row spinners in the UI. */
    private val _pendingIds = MutableStateFlow<Set<Long>>(emptySet())
    val pendingIds: StateFlow<Set<Long>> = _pendingIds.asStateFlow()

    private var job: Job? = null

    /**
     * Why each failed file failed, keyed by message id, for per-row wording.
     * Cleared when a new run starts or a retry is requested.
     */
    private val _failures = MutableStateFlow<Map<Long, String>>(emptyMap())
    val failures: StateFlow<Map<Long, String>> = _failures.asStateFlow()

    /**
     * Files from the last run that could not be saved, ready to be retried.
     *
     * Separate from the pending queue on purpose: a failed file is not
     * outstanding work, so nothing resumes it automatically. The user asks.
     */
    private val _failedItems = MutableStateFlow<List<QueuedDownload>>(emptyList())
    val failedItems: StateFlow<List<QueuedDownload>> = _failedItems.asStateFlow()

    /**
     * TDLib file id to message id for the current run, so progress updates can
     * be routed. Concurrent because a run can add to it while the progress
     * collector is reading it from another thread.
     */
    private val messageIdsByFileId = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /**
     * Identifies the run that currently owns the queue.
     *
     * Bumped whenever a run is started or cancelled. A run that finds a
     * different epoch must stop writing to the queue: its in-flight work can
     * still be mid-flight (writing an item back is not suspending, so
     * cancellation alone does not stop it), and it would otherwise resurrect a
     * queue the user just cleared — or clobber a newer batch.
     */
    private val runEpoch = AtomicInteger(0)

    /**
     * How many times one file is fetched before it is reported as failed.
     * Three attempts with a short backoff covers a flaky connection without
     * holding up the rest of the queue for long.
     */
    private const val MAX_ATTEMPTS = 3

    /** Waits between attempts, indexed by the attempt that just failed. */
    private val RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L)

    /** True when there is work the user could resume. */
    fun hasPendingWork(context: Context): Boolean =
        job?.isActive == true ||
            DownloadQueueStore.load(context).isNotEmpty() ||
            DownloadQueueStore.loadZipRequest(context) != null

    /**
     * Adds [items] to the queue and asks [DownloadService] to run it.
     *
     * Anything already queued is kept and de-duplicated by message id, so
     * tapping Download again mid-run tops the batch up instead of discarding it.
     */
    fun enqueue(context: Context, items: List<QueuedDownload>) {
        if (items.isEmpty()) return
        val appContext = context.applicationContext
        val alreadyQueued = if (job?.isActive == true) DownloadQueueStore.load(appContext) else emptyList()
        val merged = (items + alreadyQueued).distinctBy { it.messageId }

        DownloadQueueStore.save(appContext, merged)
        _pendingIds.value = merged.map { it.messageId }.toSet()
        if (_fileProgress.value.isEmpty()) {
            _fileProgress.value = merged.associate { it.messageId to FileProgress(0L, it.sizeBytes) }
        }
        // Only reset the run counters when nothing is running; otherwise an
        // additional tap would make the visible progress jump backwards.
        if (job?.isActive != true) {
            _state.value = RunState(phase = Phase.RUNNING, total = merged.size)
        }

        // startForegroundService: the service must post its notification before
        // doing any long work, which it does before touching the queue.
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, DownloadService::class.java)
        )
    }

    /**
     * Queues [request]'s selection as a single .zip archive.
     *
     * Returns the reason it was refused, or null when it was accepted. One
     * archive at a time: an archive is written in a single pass, so a second
     * request could not be interleaved with the first, and quietly replacing it
     * would lose the selection the user asked for.
     */
    fun enqueueZip(context: Context, request: ZipRequest): String? {
        if (request.items.isEmpty()) return null
        val appContext = context.applicationContext
        if (job?.isActive == true || DownloadQueueStore.loadZipRequest(appContext) != null) {
            return "A download is already running. Wait for it to finish, or cancel it."
        }

        DownloadQueueStore.saveZipRequest(appContext, request)
        _pendingIds.value = request.items.map { it.messageId }.toSet()
        _fileProgress.value = request.items.associate {
            it.messageId to FileProgress(0L, it.sizeBytes)
        }
        _state.value = RunState(phase = Phase.RUNNING, total = request.items.size)

        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, DownloadService::class.java)
        )
        return null
    }

    /**
     * Picks up a batch that a process kill interrupted.
     *
     * Called by the UI once Telegram reports an authorized state — resuming
     * with no live TDLib client would just fail every remaining file. Starting
     * the service is what actually runs it.
     */
    fun resumePendingWork(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        if (DownloadQueueStore.load(appContext).isEmpty() &&
            DownloadQueueStore.loadZipRequest(appContext) == null
        ) {
            return
        }
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, DownloadService::class.java)
        )
    }

    /**
     * Reloads the failures a previous process left behind, so the Retry action
     * survives the app being killed mid-batch.
     */
    fun restoreFailedState(context: Context) {
        if (_failedItems.value.isNotEmpty()) return
        _failedItems.value = DownloadQueueStore.loadFailed(context.applicationContext)
    }

    /**
     * Re-queues [messageId] only, or everything that failed when it is null.
     *
     * Returns the reason it was refused, or null when the retry was queued.
     * Failed items are moved back into the normal pending queue and deleted
     * from the failed set first, so a retry that dies halfway does not leave a
     * file in both places and fetch it twice.
     */
    fun retryFailed(context: Context, messageId: Long? = null): String? {
        val appContext = context.applicationContext
        val all = _failedItems.value.ifEmpty { DownloadQueueStore.loadFailed(appContext) }
        val items = if (messageId == null) all else all.filter { it.messageId == messageId }
        if (items.isEmpty()) return null

        // Same connection rule as any other download, checked here so every
        // retry entry point (card, row, notification) gets it for free.
        NetworkGuard.blockedReason(
            appContext,
            AppContainer.appPreferences.downloadNetwork.value
        )?.let { return it }

        val remaining = all - items.toSet()
        _failedItems.value = remaining
        DownloadQueueStore.saveFailed(appContext, remaining)
        _failures.value = _failures.value - items.map { it.messageId }.toSet()
        enqueue(appContext, items)
        return null
    }

    /** Forgets the failures without retrying them. */
    fun dismissFailures(context: Context) {
        _failedItems.value = emptyList()
        _failures.value = emptyMap()
        DownloadQueueStore.clearFailed(context.applicationContext)
    }

    /**
     * Begins (or continues) the persisted queue. Called by [DownloadService]
     * once it is in the foreground. A no-op while a run is already active.
     */
    fun launchRun(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        val items = DownloadQueueStore.load(appContext)
        val archive = DownloadQueueStore.loadZipRequest(appContext)
        if (items.isEmpty() && archive == null) {
            _state.value = RunState()
            _pendingIds.value = emptySet()
            return
        }
        // Publish RUNNING synchronously so the service's observer never sees a
        // stale IDLE and mistake a real batch for an empty one. The total covers
        // both phases, so the progress bar does not restart when the archive
        // begins.
        _state.value = RunState(
            phase = Phase.RUNNING,
            total = items.size + (archive?.items?.size ?: 0)
        )
        val epoch = runEpoch.incrementAndGet()
        job = scope.launch { run(appContext, epoch) }
    }

    /**
     * Stops the current run and discards what is left.
     *
     * Cancel means stop: keeping the remainder around would make the app
     * silently restart downloads the user just asked it to abandon.
     */
    fun cancel(context: Context) {
        if (job?.isActive != true) return
        // Orphan the running loop first: it may be between "finished a file"
        // and "write the queue back", and that write must not happen.
        runEpoch.incrementAndGet()
        DownloadQueueStore.clear(context.applicationContext)
        // Cancel means stop for the archive too: leaving the request behind
        // would rebuild it on the next launch.
        DownloadQueueStore.clearZipRequest(context.applicationContext)
        _pendingIds.value = emptySet()
        job?.cancel()
    }

    /** Clears a finished run so the UI goes back to idle. */
    fun acknowledge() {
        if (_state.value.isRunning) return
        _state.value = RunState()
        _fileProgress.value = emptyMap()
        _pendingIds.value = emptySet()
    }
    /**
     * Drains the persisted queue, one file at a time.
     *
     * The stored queue IS the progress state: each item is removed only after it
     * has been handled, so a process death at any point leaves an accurate
     * resume point, and a second "Download" tap mid-run simply appends work that
     * this same loop will pick up.
     */
    private suspend fun run(context: Context, epoch: Int) {
        val initial = DownloadQueueStore.load(context)
        _fileProgress.value = initial.associate { it.messageId to FileProgress(0L, it.sizeBytes) }
        _pendingIds.value = initial.map { it.messageId }.toSet()
        messageIdsByFileId.clear()
        // Per-row failure reasons belong to this run. The failed-file list is
        // NOT cleared: files from an earlier batch that the user has not
        // retried yet are still theirs to retry, and they stay on offer until
        // they succeed or are dismissed.
        _failures.value = emptyMap()

        // Route TDLib's per-file updates to the matching row for the whole run.
        val progressJob = scope.launch {
            AppContainer.repository.downloadProgress.collect { progress ->
                val messageId = messageIdsByFileId[progress.fileId] ?: return@collect
                _fileProgress.value = _fileProgress.value + (
                    messageId to FileProgress(progress.downloadedBytes, progress.totalBytes)
                    )
            }
        }

        var completed = 0
        var failed = 0
        try {
            while (true) {
                val remaining = DownloadQueueStore.load(context)
                if (remaining.isEmpty()) break
                val item = remaining.first()

                // Items queued from History carry no file id; skipping them here
                // also keeps them off the 0 key where they would collide.
                if (item.fileId > 0) messageIdsByFileId[item.fileId] = item.messageId
                _fileProgress.value = _fileProgress.value + (
                    item.messageId to FileProgress(0L, item.sizeBytes)
                    )
                _pendingIds.value = remaining.map { it.messageId }.toSet()
                _state.value = _state.value.copy(
                    phase = Phase.RUNNING,
                    total = completed + failed + remaining.size,
                    completed = completed,
                    failed = failed,
                    currentName = item.fileName
                )

                // Retry before giving up. Failures here are mostly transient —
                // a dropped connection, a TDLib hiccup — and TDLib resumes from
                // the bytes it already holds, so a retry costs almost nothing,
                // while reporting a failure the user must clear by hand costs a
                // lot.
                var savedLocation: String? = null
                var lastError: String? = null
                try {
                    savedLocation = withRetries {
                        val message =
                            AppContainer.repository.getMessage(item.chatId, item.messageId)
                                ?: throw IllegalStateException(
                                    "Message is no longer available."
                                )
                        // TDLib fetches into its own cache, resuming any partial
                        // copy, then DownloadsStore publishes it to Downloads.
                        val cached = AppContainer.repository.downloadToCache(message)
                        // Mark the row complete from the bytes we actually got.
                        // The per-file TDLib ticks drive the live bar, but they
                        // are keyed by file id — this keeps the outcome correct
                        // even for a row queued from History, which has no file
                        // id to match on.
                        val bytes = cached.length()
                        _fileProgress.value = _fileProgress.value + (
                            item.messageId to FileProgress(bytes, bytes)
                            )
                        DownloadsStore.save(
                            context = context,
                            source = cached,
                            sourceTitle = item.sourceTitle,
                            category = item.category,
                            fileName = item.fileName
                        ).location
                    }
                } catch (e: CancellationException) {
                    // Deliberately do NOT drop the item: it stays at the head of
                    // the queue as the resume point. (A user-initiated cancel
                    // clears the queue separately.)
                    throw e
                } catch (e: DownloadFailure) {
                    lastError = e.message
                }

                if (savedLocation != null) {
                    recordHistory(item, savedLocation)
                    completed++
                    // Remember where this batch is landing, so the completion
                    // surface can offer to open that channel's folder.
                    _state.value = _state.value.copy(
                        lastSavedFolder = DownloadsStore.channelFolder(item.sourceTitle)
                    )
                    // A file that has now succeeded drops off the retry list,
                    // e.g. one re-downloaded by hand from History.
                    if (_failedItems.value.any { it.messageId == item.messageId }) {
                        _failedItems.value =
                            _failedItems.value.filterNot { it.messageId == item.messageId }
                        DownloadQueueStore.saveFailed(context, _failedItems.value)
                    }
                } else {
                    failed++
                    val reason = lastError ?: "Download failed."
                    _failures.value = _failures.value + (item.messageId to reason)
                    if (_failedItems.value.none { it.messageId == item.messageId }) {
                        _failedItems.value = _failedItems.value + item
                        DownloadQueueStore.saveFailed(context, _failedItems.value)
                    }
                    recordHistory(item, location = null)
                }

                // Only forget the item once it is fully handled, and only while
                // this run still owns the queue.
                if (epoch != runEpoch.get()) return
                DownloadQueueStore.save(context, remaining.drop(1))
                _pendingIds.value = remaining.drop(1).map { it.messageId }.toSet()
            }

            // An archive the user asked for, written only after the fetching is
            // done. Deliberately afterwards: an archive is built from TDLib's
            // cached copies, and a half-built one is worth nothing, so nothing
            // about it should start until the work that can fail has finished.
            val requested = DownloadQueueStore.loadZipRequest(context)
            val archive = if (requested != null && epoch == runEpoch.get()) {
                buildArchive(context, requested, epoch)
            } else {
                null
            }
            if (epoch != runEpoch.get()) return
            if (archive != null) {
                completed += archive.packed
                failed += archive.failed
            }
            val filesAlsoSaved = completed - (archive?.packed ?: 0)

            // Name the folder in the summary, so "where did it go?" is answered
            // where the batch finishes rather than only in Settings.
            val savedFolder = archive?.folder
                ?: _state.value.lastSavedFolder
                ?: DownloadsStore.ROOT
            _state.value = _state.value.copy(
                phase = Phase.FINISHED,
                completed = completed,
                failed = failed,
                currentName = null,
                packing = false,
                message = when {
                    archive != null -> archive.summary(filesAlsoSaved)
                    failed == 0 ->
                        "Saved $completed file(s) to ${DownloadsStore.displayPath(savedFolder)}."
                    completed == 0 ->
                        "All $failed file(s) failed after $MAX_ATTEMPTS attempts. " +
                            "Use Retry to try again."
                    else ->
                        "Finished — $failed of ${completed + failed} file(s) failed " +
                            "after $MAX_ATTEMPTS attempts. Use Retry to try them again."
                }
            )
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(
                phase = Phase.CANCELLED,
                currentName = null,
                message = "Download stopped."
            )
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                phase = Phase.FAILED,
                currentName = null,
                message = e.message ?: "Download failed."
            )
        } finally {
            progressJob.cancel()
        }
    }

    /** A fetch that failed every attempt, carrying the reason for the user. */
    private class DownloadFailure(override val message: String) : Exception(message)

    /**
     * Runs [block], retrying transient failures with a short backoff.
     *
     * One policy for both download shapes. Failures are mostly transient — a
     * dropped connection, a TDLib hiccup — and TDLib resumes from the bytes it
     * already holds, so a retry costs almost nothing, while reporting a failure
     * the user has to clear by hand costs a lot.
     */
    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var attempt = 0
        var lastError: String? = null
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: "Download failed."
                // A message that no longer exists will not come back, so retrying
                // it would only burn battery.
                if (isPermanentFailure(lastError) || attempt >= MAX_ATTEMPTS) break
                delay(RETRY_DELAYS_MS[attempt - 1])
            }
        }
        throw DownloadFailure(lastError ?: "Download failed.")
    }

    /**
     * Writes one archive from TDLib's cached copies of [request]'s selection.
     *
     * The two phases matter. Every file is fetched into TDLib's cache first, then
     * the archive is written in a single pass: writing as we fetch would mean a
     * failed fetch leaves a half-built archive that can never be completed — a
     * zip cannot be appended to from the middle — so one bad file would cost the
     * whole selection instead of just itself.
     *
     * A run that is killed part-way throws its partial archive away and starts a
     * new one on the next launch, which is cheap rather than wasteful: the files
     * are already in TDLib's cache, so they are read from disk, not fetched
     * again.
     */
    private suspend fun buildArchive(
        context: Context,
        request: ZipRequest,
        epoch: Int
    ): ArchiveOutcome {
        var failed = 0
        var savedFiles = 0
        val keepFiles = request.keepFiles
        val sources = mutableListOf<ZipPacker.Entry>()

        for ((index, item) in request.items.withIndex()) {
            // A cancelled run stops between files, never mid-write.
            if (epoch != runEpoch.get()) break
            if (item.fileId > 0) messageIdsByFileId[item.fileId] = item.messageId
            _fileProgress.value = _fileProgress.value + (
                item.messageId to FileProgress(0L, item.sizeBytes)
                )
            _pendingIds.value = request.items.drop(index).map { it.messageId }.toSet()
            _state.value = _state.value.copy(
                phase = Phase.RUNNING,
                total = maxOf(_state.value.total, request.items.size),
                completed = index,
                failed = failed,
                currentName = item.fileName,
                packing = false
            )
            var savedLocation: String? = null
            try {
                val cached = withRetries {
                    val message =
                        AppContainer.repository.getMessage(item.chatId, item.messageId)
                            ?: throw IllegalStateException("Message is no longer available.")
                    val file = AppContainer.repository.downloadToCache(message)
                    val bytes = file.length()
                    _fileProgress.value = _fileProgress.value + (
                        item.messageId to FileProgress(bytes, bytes)
                        )
                    // Last in the block, and only when the files are wanted.
                    // Publishing as we go is what makes a failed archive cheap:
                    // the files fetched so far are already safely in Downloads,
                    // so a bad archive costs the user nothing they have paid for.
                    if (keepFiles) {
                        savedLocation = DownloadsStore.save(
                            context = context,
                            source = file,
                            sourceTitle = item.sourceTitle,
                            category = item.category,
                            fileName = item.fileName
                        ).location
                    }
                    file
                }
                // Bookkeeping outside the retry loop, so a failure here can never
                // publish a second copy of a file that is already in place.
                if (savedLocation != null) {
                    savedFiles++
                    recordHistory(item, savedLocation)
                    _state.value = _state.value.copy(
                        lastSavedFolder = DownloadsStore.channelFolder(item.sourceTitle)
                    )
                }
                sources += ZipPacker.Entry(item.fileName, cached)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadFailure) {
                failed++
                _failures.value = _failures.value + (item.messageId to e.message)
                // The retry list is only for a run that keeps the files: a Retry
                // for a file that lives inside an archive would download it as a
                // separate file, which is not what archive-only mode promises.
                if (keepFiles && _failedItems.value.none { it.messageId == item.messageId }) {
                    _failedItems.value = _failedItems.value + item
                    DownloadQueueStore.saveFailed(context, _failedItems.value)
                }
            }
        }

        if (sources.isEmpty()) {
            DownloadQueueStore.clearZipRequest(context)
            return ArchiveOutcome(
                fileName = request.fileName,
                folder = request.folder,
                packed = 0,
                savedFiles = savedFiles,
                keptFiles = keepFiles,
                failed = failed,
                items = request.items.size,
                error = null
            )
        }

        // Fetching is over; the rest is local disk work, and the wording says so
        // — "downloading 19 of 20" while packing would be a small lie.
        _state.value = _state.value.copy(
            packing = true,
            completed = 0,
            currentName = request.fileName
        )
        var packed = 0
        // Stays null when the write failed, which is what tells the caller apart
        // from a successful archive of zero files.
        var saved: DownloadsStore.SavedFile? = null
        var error: String? = null
        try {
            saved = DownloadsStore.saveArchive(context, request.folder, request.fileName) { output ->
                packed = ZipPacker.write(output, sources) { entry ->
                    // Cancelling during packing stops between files, not after the
                    // whole archive is written: throwing here abandons the write,
                    // and saveArchive's failure path removes what was written, so
                    // a cancelled run never publishes an archive the user
                    // cancelled.
                    if (epoch != runEpoch.get()) {
                        throw CancellationException("Cancelled while creating the ZIP.")
                    }
                    _state.value = _state.value.copy(
                        completed = _state.value.completed + 1,
                        currentName = entry.name
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not retried on its own: the cause is usually about this device —
            // space, a file that is gone — and would fail the same way again.
            // Clearing the request lets the user ask again once it is fixed.
            DownloadQueueStore.clearZipRequest(context)
            error = e.message ?: "The ZIP could not be created."
        }
        val written = saved
        if (error == null && written != null) {
            DownloadQueueStore.clearZipRequest(context)
            // Recorded only once the archive is really on disk: a row for a
            // failed archive would offer an Open action that opens nothing.
            recordArchiveHistory(request, written)
            _state.value = _state.value.copy(
                lastSavedFolder = request.folder,
                packing = false
            )
        }
        return ArchiveOutcome(
            fileName = request.fileName,
            folder = request.folder,
            packed = if (error == null) packed else 0,
            savedFiles = savedFiles,
            keptFiles = keepFiles,
            failed = failed,
            items = request.items.size,
            error = error
        )
    }

    /**
     * Failures that retrying cannot fix, so the engine reports them at once
     * instead of burning two backoff waits on them.
     *
     * Matched on TDLib's own wording, which is stable for these two cases: the
     * message was deleted, or the row was never downloadable media.
     */
    private fun isPermanentFailure(message: String): Boolean =
        message.contains("no longer available", ignoreCase = true) ||
            message.contains("no downloadable media", ignoreCase = true)

    /**
     * Persists a finished attempt (success or failure) to Room. Done here rather
     * than in the UI so the History tab is correct even if no screen was ever
     * open while the download ran.
     */
    private suspend fun recordHistory(item: QueuedDownload, location: String?) {
        AppContainer.historyDao.upsert(
            DownloadHistoryEntity(
                messageId = item.messageId,
                chatId = item.chatId,
                fileName = item.fileName,
                category = item.category,
                sourceTitle = item.sourceTitle,
                sizeBytes = item.sizeBytes,
                completedAtEpochMs = System.currentTimeMillis(),
                localPath = location,
                succeeded = location != null,
                entryKind = DownloadEntryKind.FILE
            )
        )
    }

    /**
     * Records a finished archive as its own kind of entry.
     *
     * An archive has no Telegram message behind it, so it cannot be re-fetched
     * and is never offered as such: History shows it with Open and Delete only.
     * Its id is allocated in the negative range ([archiveHistoryId]) because the
     * message id is the primary key and the positive range belongs to Telegram.
     * An archive re-downloaded into the same place updates its existing row, so
     * the list does not fill with rows all pointing at one file.
     *
     * The size is the archive's own size on disk, not the sum of the files inside
     * it — that is what the entry actually costs the user's storage.
     */
    private suspend fun recordArchiveHistory(
        request: ZipRequest,
        saved: DownloadsStore.SavedFile
    ) {
        val dao = AppContainer.historyDao
        val existing = dao.archiveByLocalPath(saved.location)
        val title = request.items.firstOrNull()
            ?.sourceTitle
            ?.takeIf { it.isNotBlank() }
            ?: DownloadsStore.channelName(request.folder)
        dao.upsert(
            DownloadHistoryEntity(
                messageId = archiveHistoryId(existing?.messageId, dao.lowestArchiveId()),
                chatId = request.items.firstOrNull()?.chatId ?: 0L,
                fileName = request.fileName,
                category = archiveCategory(request.items.map { it.category }),
                sourceTitle = title,
                sizeBytes = saved.bytes,
                completedAtEpochMs = System.currentTimeMillis(),
                localPath = saved.location,
                succeeded = true,
                entryKind = DownloadEntryKind.ARCHIVE
            )
        )
    }
}
