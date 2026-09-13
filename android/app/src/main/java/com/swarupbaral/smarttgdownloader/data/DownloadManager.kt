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
        val message: String? = null
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
        job?.isActive == true || DownloadQueueStore.load(context).isNotEmpty()

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
     * Picks up a batch that a process kill interrupted.
     *
     * Called by the UI once Telegram reports an authorized state — resuming
     * with no live TDLib client would just fail every remaining file. Starting
     * the service is what actually runs it.
     */
    fun resumePendingWork(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        if (DownloadQueueStore.load(appContext).isEmpty()) return
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
        if (items.isEmpty()) {
            _state.value = RunState()
            _pendingIds.value = emptySet()
            return
        }
        // Publish RUNNING synchronously so the service's observer never sees a
        // stale IDLE and mistake a real batch for an empty one.
        _state.value = RunState(phase = Phase.RUNNING, total = items.size)
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
                var attempt = 0
                while (attempt < MAX_ATTEMPTS) {
                    attempt++
                    try {
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
                        savedLocation = DownloadsStore.save(
                            context = context,
                            source = cached,
                            sourceTitle = item.sourceTitle,
                            category = item.category,
                            fileName = item.fileName
                        ).location
                        break
                    } catch (e: CancellationException) {
                        // Deliberately do NOT drop the item: it stays at the
                        // head of the queue as the resume point. (A user-initiated
                        // cancel clears the queue separately.)
                        throw e
                    } catch (e: Exception) {
                        lastError = e.message ?: "Download failed."
                        // A message that no longer exists will not come back, so
                        // retrying it would only burn battery.
                        if (isPermanentFailure(lastError) || attempt >= MAX_ATTEMPTS) break
                        delay(RETRY_DELAYS_MS[attempt - 1])
                    }
                }

                if (savedLocation != null) {
                    recordHistory(item, savedLocation)
                    completed++
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

            _state.value = _state.value.copy(
                phase = Phase.FINISHED,
                completed = completed,
                failed = failed,
                currentName = null,
                message = when {
                    failed == 0 -> "Saved $completed file(s) to your Download folder."
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
                succeeded = location != null
            )
        )
    }
}
