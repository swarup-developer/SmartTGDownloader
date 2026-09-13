package com.swarupbaral.smarttgdownloader.data

import android.content.Context
import com.swarupbaral.smarttgdownloader.BuildConfig
import io.github.tdlibandroid.ktx.TdClient
import io.github.tdlibandroid.ktx.TdException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import java.io.File

/**
 * Thin repository over TDLib (via the tdlib-android [TdClient] coroutine wrapper).
 *
 * Handles the Telegram authorization state machine, link analysis (scan) and
 * file downloads, exposing everything as Kotlin Flows for the Compose UI.
 */
class TelegramRepository(context: Context) {

    sealed interface AuthState {
        data object Idle : AuthState
        data object Connecting : AuthState

        /** Telegram is waiting for a phone number (first run, or after log-out). */
        data object WaitPhoneNumber : AuthState

        /**
         * A code was requested and Telegram is waiting for it.
         *
         * [resendInSeconds] is TDLib's server-authoritative cooldown before
         * another code may be requested. It is not a fixed value: Telegram
         * raises it on each resend (roughly 30s, then 50s, then a minute), so
         * the UI reads this value instead of running its own timer.
         */
        data class AwaitingCode(
            /** Masked number Telegram echoed back, when it provides one. */
            val phoneNumber: String?,
            /** How the code was or will be delivered. */
            val channel: CodeChannel,
            /** Expected code length; 0 when unknown. */
            val codeLength: Int,
            val resendInSeconds: Int
        ) : AuthState

        data class AwaitingPassword(val hint: String?) : AuthState
        data class Ready(val userName: String) : AuthState
        data class Error(val message: String) : AuthState
    }

    /** How Telegram delivers the login code; drives the screen-reader wording. */
    enum class CodeChannel(
        val label: String,
        /** Spoken form, for announcements: "Code sent via <spoken>". */
        val spoken: String
    ) {
        SMS("SMS", "an SMS"),
        TELEGRAM("Telegram message", "a Telegram message"),
        CALL("Phone call", "a phone call"),
        FLASH_CALL("Flash call", "a flash call"),
        OTHER("Telegram", "Telegram")
    }

    data class ScannedFile(
        val messageId: Long,
        /**
         * TDLib file id of the attachment. Carried on the row itself so
         * download progress can be routed without keeping every
         * [TdApi.Message] of a large channel in memory.
         */
        val fileId: Int,
        val name: String,
        val sizeBytes: Long,
        val category: String,
        val dateEpoch: Long
    )

    data class ScanResult(
        val sourceTitle: String,
        val files: List<ScannedFile>,
        val totalCount: Int,
        /**
         * How many chat messages the scan actually walked. Reported in the
         * "no files found" state so the user can tell a genuinely empty link
         * apart from one that was never analyzed.
         */
        val scannedMessages: Int,
        /**
         * True when collection stopped at the configured file limit rather
         * than at the end of the channel. The UI must say so, otherwise a
         * truncated result looks like the whole channel.
         */
        val limitReached: Boolean = false,
        /** The limit that applied, 0 when unlimited. */
        val fileLimit: Int = 0,
        /**
         * How many media files the walk passed over because they were not the
         * requested type. Analysis now runs for one type at a time, so this is
         * what lets an empty result say "no Audio files, but there are 342
         * video and document files here — try another file type" instead of a
         * bare "nothing found" that hides the rest of the channel.
         *
         * Counted only up to the point the walk stopped, so it is a lower
         * bound whenever [limitReached] is true.
         */
        val otherMediaCount: Int = 0,
        /**
         * True when the history walk gave up before reaching the start of the
         * chat, while Telegram's own message count showed there was more to
         * come.
         *
         * This is the difference between "this channel has no Audio files" and
         * "the channel's history had not finished loading yet". The second is
         * retryable, and it previously looked exactly like the first — which is
         * how a channel full of files came back as zero.
         */
        val historyIncomplete: Boolean = false
    )

    data class DownloadProgress(
        /** TDLib file id — the key callers use to route progress to the right row. */
        val fileId: Int,
        val name: String,
        val downloadedBytes: Long,
        val totalBytes: Long
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val filesDir: File by lazy {
        File(appContext.filesDir, "tdlib").apply { mkdirs() }
    }

    private var client: TdClient? = null

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * Live per-file download progress, keyed by TDLib [DownloadProgress.fileId].
     *
     * Fed straight from TDLib's UpdateFile stream. Drop-oldest keeps emission
     * non-suspending and the newest snapshot wins; the ViewModel collapses the
     * stream into per-row state, so a dropped intermediate tick is harmless.
     */
    private val _downloadProgress =
        MutableSharedFlow<DownloadProgress>(
            replay = 1,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    val downloadProgress: SharedFlow<DownloadProgress> = _downloadProgress.asSharedFlow()

    fun start() {
        if (client != null) return
        _authState.value = AuthState.Connecting
        scope.launch {
            try {
                val newClient = TdClient(
                    filesDir = filesDir.absolutePath,
                    verbosityLevel = 1,
                    apiId = BuildConfig.TG_API_ID,
                    apiHash = BuildConfig.TG_API_HASH
                )
                newClient.init()
                client = newClient

                scope.launch {
                    newClient.updates.collect { update ->
                        handleUpdate(update)
                    }
                }

                // Kick the state machine by querying the current authorization state.
                send(TdApi.GetOption("version"))
                send(TdApi.GetMe())
            } catch (e: TdException) {
                // Not logged in yet: GetMe fails with 401 and the
                // UpdateAuthorizationState flow drives the UI from here.
                if (!isAuthStateError(e)) {
                    // TdException.message is declared non-null, so "no message"
                    // can only mean a blank one — and showing the user a blank
                    // error is a silent failure, which is why the fallback is
                    // kept rather than dropped along with the null check.
                    _authState.value = AuthState.Error(
                        e.message.takeIf { it.isNotBlank() } ?: "Telegram client error"
                    )
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error(
                    e.message ?: "Failed to initialize Telegram client"
                )
            }
        }
    }

    private fun isAuthStateError(error: TdException): Boolean {
        val code = error.code
        // Read straight through: TdException.message is declared non-null, so
        // there is no missing case to substitute an empty string for.
        val msg = error.message
        return code == 401 || msg.contains("AUTHORIZED", ignoreCase = true)
    }

    private suspend fun handleUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> applyAuthState(update.authorizationState)
            is TdApi.UpdateFile -> {
                val file = update.file
                val total = when {
                    file.expectedSize > 0 -> file.expectedSize
                    file.size > 0 -> file.size
                    else -> 0L
                }
                val downloading = file.local.isDownloadingActive
                // isDownloadingCompleted can be true for files that were already
                // on disk before this request; only treat it as a finish tick
                // while this file is (or just was) part of an active download.
                val completed =
                    file.local.isDownloadingCompleted &&
                        (downloading || file.local.downloadedSize > 0)
                if (downloading || completed) {
                    _downloadProgress.tryEmit(
                        DownloadProgress(
                            fileId = file.id,
                            name = file.local.path.substringAfterLast('/'),
                            downloadedBytes = file.local.downloadedSize,
                            totalBytes = total
                        )
                    )
                }
            }
            else -> Unit
        }
    }

    private suspend fun applyAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitPhoneNumber ->
                _authState.value = AuthState.WaitPhoneNumber

            is TdApi.AuthorizationStateWaitCode -> {
                val info = state.codeInfo
                _authState.value = AuthState.AwaitingCode(
                    phoneNumber = info?.phoneNumber,
                    channel = channelFor(info?.type),
                    codeLength = codeLengthFor(info?.type),
                    resendInSeconds = (info?.timeout ?: 0).coerceAtLeast(0)
                )
            }

            is TdApi.AuthorizationStateWaitPassword ->
                _authState.value = AuthState.AwaitingPassword(hint = state.passwordHint)

            is TdApi.AuthorizationStateWaitRegistration ->
                _authState.value = AuthState.Error(
                    "This phone number is not registered on Telegram yet."
                )

            is TdApi.AuthorizationStateReady -> {
                val name = runCatching {
                    val me = send(TdApi.GetMe())
                    me.firstName.ifBlank { me.phoneNumber }
                }.getOrDefault("Telegram")
                _authState.value = AuthState.Ready(name)
            }

            is TdApi.AuthorizationStateClosed -> {
                client = null
                _authState.value = AuthState.Idle
            }

            else -> Unit
        }
    }

    /** Requests the OTP for [phoneNumber] (E.164, e.g. +919876543210). */
    suspend fun requestOtp(phoneNumber: String) {
        try {
            send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null))
        } catch (e: TdException) {
            throw IllegalArgumentException(mapAuthError(e), e)
        }
    }

    /**
     * Asks Telegram to send the login code again.
     *
     * TDLib tracks the server's cooldown itself, so this is a plain request:
     * if Telegram is still rate-limiting, it answers with FLOOD_WAIT and
     * [mapAuthError] turns that into a readable message. The refreshed state
     * arrives through the authorization-state stream with a new
     * [AuthState.AwaitingCode.resendInSeconds].
     */
    suspend fun resendCode() {
        try {
            send(TdApi.ResendAuthenticationCode())
        } catch (e: TdException) {
            throw IllegalArgumentException(mapAuthError(e), e)
        }
    }

    /** Confirms the OTP code. Returns true once fully signed in. */
    suspend fun confirmOtp(code: String): Boolean {
        send(TdApi.CheckAuthenticationCode(code))
        return _authState.value is AuthState.Ready
    }

    /** Confirms the 2FA password. Returns true once fully signed in. */
    suspend fun confirmPassword(password: String): Boolean {
        send(TdApi.CheckAuthenticationPassword(password))
        return _authState.value is AuthState.Ready
    }

    suspend fun logOut() {
        runCatching { send(TdApi.LogOut()) }
    }

    private fun channelFor(type: TdApi.AuthenticationCodeType?): CodeChannel = when (type) {
        is TdApi.AuthenticationCodeTypeSms,
        is TdApi.AuthenticationCodeTypeSmsWord,
        is TdApi.AuthenticationCodeTypeSmsPhrase -> CodeChannel.SMS

        is TdApi.AuthenticationCodeTypeTelegramMessage -> CodeChannel.TELEGRAM
        is TdApi.AuthenticationCodeTypeCall -> CodeChannel.CALL
        is TdApi.AuthenticationCodeTypeFlashCall,
        is TdApi.AuthenticationCodeTypeMissedCall -> CodeChannel.FLASH_CALL

        else -> CodeChannel.OTHER
    }

    /** Digits the code will have, when Telegram states it; 0 = unknown. */
    private fun codeLengthFor(type: TdApi.AuthenticationCodeType?): Int = when (type) {
        is TdApi.AuthenticationCodeTypeSms -> type.length
        is TdApi.AuthenticationCodeTypeTelegramMessage -> type.length
        is TdApi.AuthenticationCodeTypeCall -> type.length
        is TdApi.AuthenticationCodeTypeMissedCall -> type.length
        is TdApi.AuthenticationCodeTypeFragment -> type.length
        else -> 0
    }

    /** Human-readable message for common TDLib auth errors. */
    private fun mapAuthError(e: TdException): String {
        // A blank message is the one way this can arrive without words to match
        // on — a null one cannot happen. Without this the fallback below would
        // read "Telegram error: " with nothing after the colon.
        val msg = e.message
        if (msg.isBlank()) return "Telegram error"
        return when {
            msg.contains("PHONE_NUMBER_INVALID", true) -> "That phone number is not valid."
            msg.contains("PHONE_NUMBER_FLOOD", true) -> "Too many attempts. Try again later."
            msg.contains("PHONE_CODE_INVALID", true) -> "The OTP code is not valid."
            msg.contains("PHONE_CODE_EXPIRED", true) -> "The OTP code has expired. Request a new one."
            msg.contains("PASSWORD_HASH_INVALID", true) -> "Incorrect authentication password."
            msg.contains("FLOOD_WAIT", true) -> "Rate limited by Telegram. Please wait and retry."
            else -> "Telegram error: $msg"
        }
    }

    // ------------------------------------------------------------------
    // Link parsing (t.me and tg://)
    // ------------------------------------------------------------------

    sealed interface ParsedLink {
        data class PublicChannel(val username: String, val messageId: Long?) : ParsedLink
        data class PrivateChannel(val rawChatId: Long, val messageId: Long?) : ParsedLink
        data class Invite(val hash: String, val messageId: Long?) : ParsedLink
    }

    /**
     * Parses Telegram deep links:
     *  - https://t.me/channel[/123]
     *  - https://t.me/s/channel[/123]
     *  - https://t.me/c/<internalId>[/<messageId>]
     *  - https://t.me/+invitehash, t.me/joinchat/<hash>
     *  - tg://resolve?domain=channel[&post=123]
     *  - tg://privatepost?channel=123&post=45
     *  - tg://join?invite=<hash>
     */
    fun parseLink(raw: String): ParsedLink {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Telegram link is required." }

        val withScheme = if (
            trimmed.startsWith("http://") ||
            trimmed.startsWith("https://") ||
            trimmed.startsWith("tg://")
        ) trimmed else "https://$trimmed"

        val uri = runCatching { java.net.URI(withScheme) }
            .getOrElse { throw IllegalArgumentException("Invalid Telegram link.") }

        if (uri.scheme.equals("tg", ignoreCase = true)) {
            return parseTgUri(uri)
        }

        val host = uri.host?.lowercase() ?: ""
        require(host in ALLOWED_HOSTS) { "Only Telegram links from t.me are supported." }

        val path = uri.path?.trim('/') ?: ""
        val parts = path.split('/').filter { it.isNotBlank() }
        require(parts.isNotEmpty()) { "Incomplete Telegram link." }

        val effectiveParts = if (parts[0].equals("s", ignoreCase = true)) parts.drop(1) else parts
        require(effectiveParts.isNotEmpty()) { "Invalid public channel link." }

        val messageId = effectiveParts.getOrNull(1)?.toLongOrNull()
            ?: uri.rawQuery?.let { query ->
                Regex("post=(\\d+)").find(query)?.groupValues?.get(1)?.toLongOrNull()
            }

        return when {
            effectiveParts[0].equals("c", ignoreCase = true) -> {
                val rawId = effectiveParts.getOrNull(1)?.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid private channel link.")
                ParsedLink.PrivateChannel(rawId, messageId)
            }
            effectiveParts[0].startsWith("+") -> {
                val hash = effectiveParts[0].removePrefix("+").trim()
                require(hash.isNotEmpty()) { "Invalid invite link." }
                ParsedLink.Invite(hash, messageId)
            }
            effectiveParts[0].equals("joinchat", ignoreCase = true) -> {
                val hash = effectiveParts.getOrNull(1)?.trim().orEmpty()
                require(hash.isNotEmpty()) { "Invalid joinchat link." }
                ParsedLink.Invite(hash, messageId)
            }
            else -> ParsedLink.PublicChannel(effectiveParts[0], messageId)
        }
    }

    private fun parseTgUri(uri: java.net.URI): ParsedLink {
        val ssp = uri.schemeSpecificPart // e.g. resolve?domain=foo&post=12
        val queryIdx = ssp.indexOf('?')
        val action = (if (queryIdx >= 0) ssp.substring(0, queryIdx) else ssp).trim('/').lowercase()
        val query = if (queryIdx >= 0) ssp.substring(queryIdx + 1) else ""

        fun param(name: String): String? =
            query.split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it.first() == name }
                ?.getOrNull(1)

        return when (action) {
            "resolve" -> {
                val domain = param("domain")
                    ?: throw IllegalArgumentException("Invalid tg://resolve link.")
                ParsedLink.PublicChannel(domain, param("post")?.toLongOrNull())
            }
            "privatepost" -> {
                val channel = param("channel")?.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid tg://privatepost link.")
                ParsedLink.PrivateChannel(channel, param("post")?.toLongOrNull())
            }
            "join" -> {
                val invite = param("invite")
                    ?: throw IllegalArgumentException("Invalid tg://join link.")
                ParsedLink.Invite(invite, null)
            }
            else -> throw IllegalArgumentException("Unsupported tg:// link format.")
        }
    }

    // ------------------------------------------------------------------
    // Scan (link analysis)
    // ------------------------------------------------------------------

    /** A chat resolved from a link, before any of its history has been read. */
    data class ResolvedSource(val chatId: Long, val title: String)

    /**
     * Resolves [link] to a chat, joining an invite link when membership is
     * required to read it.
     *
     * Split out of the scan so the caller can load this channel's remembered
     * preferences — the scan limit in particular — before its history is walked.
     */
    suspend fun resolveSource(link: String): ResolvedSource = withContext(Dispatchers.IO) {
        val chat = resolveChat(parseLink(link))
        ResolvedSource(chatId = chat.id, title = chat.title.ifBlank { "Telegram" })
    }

    /**
     * Scans an already-resolved channel, returning every media file matching
     * [categories] ("Audio", "Video", "Images", "Docs").
     *
     * Callers pass exactly the type the user chose to download, so the walk
     * looks for one type instead of collecting everything and discarding most
     * of it. Media of other types is counted into
     * [ScanResult.otherMediaCount] as it goes past, which is what an empty
     * result needs in order to point at the rest of the channel.
     *
     * A monotonically increasing [scanToken] guarantees results from an older
     * scan can never overwrite a newer request — the stale-cache bug from the
     * previous version is structurally impossible here because each scan owns
     * its token and results are returned (never cached) per token.
     *
     * [fileLimit] caps how many matching files are collected (0 = unlimited).
     * History is walked newest-first, so a limit keeps the 
     * most recent files; [ScanResult.limitReached] reports that the result was 
     * cut short.
     *
     * [onProgress] is invoked after each history page with the running message
     * and file counts, plus whether the walk is currently waiting for Telegram
     * to load older messages rather than making progress. A busy channel takes
     * many pages to walk, so the UI needs something truthful to show (and
     * announce) meanwhile. It is called on the IO dispatcher.
     */
    suspend fun scanChat(
        source: ResolvedSource,
        categories: Set<String>,
        scanToken: Long,
        fileLimit: Int = 0,
        onProgress: (
            scannedMessages: Int,
            filesFound: Int,
            waitingForHistory: Boolean
        ) -> Unit = { _, _, _ -> }
    ): ScanResult = withContext(Dispatchers.IO) {
        val collected = mutableListOf<ScannedFile>()
        var limitReached = false
        var otherMedia = 0
        // All paging decisions live in HistoryWalk, which is unit-tested: they
        // are what a large channel got wrong before, and none of it is about
        // the network.
        val walk = HistoryWalk()

        // fromMessageId = 0 starts at the newest message and walks backwards,
        // so results naturally arrive most-recent-first.
        while (true) {
            val history = send(
                TdApi.GetChatHistory(
                    source.chatId,
                    walk.fromMessageId,
                    0,
                    walk.requestedPageSize,
                    false
                )
            )

            val messages = history.messages ?: emptyArray()
            // Maps the ids the walk approved back to the messages they came
            // from, so the walk itself never has to know about TdApi.
            val messagesById = messages.associateBy { it.id }

            when (val step = walk.next(messages.map { it.id }, history.totalCount)) {
                is HistoryWalk.Step.End -> break

                is HistoryWalk.Step.Retry -> {
                    // TDLib answered out of the history it has loaded so far and
                    // is fetching the rest in the background. Saying so beats an
                    // unexplained pause — and beats ending the scan here, which
                    // is what used to make a big channel look empty.
                    onProgress(walk.scannedMessages, collected.size, true)
                    delay(step.delayMs)
                }

                is HistoryWalk.Step.Advance -> {
                    for (id in step.fresh) {
                        val message = messagesById[id] ?: continue
                        val file = messageFile(message) ?: continue
                        val category = categoryFor(message) ?: continue
                        if (categories.none { it.equals(category, true) }) {
                            // Media, just not the type that was asked for.
                            // Counted so a "no Audio files" answer can still
                            // point at what IS here.
                            otherMedia++
                            continue
                        }

                        collected += ScannedFile(
                            messageId = message.id,
                            fileId = file.id,
                            name = buildFileName(message),
                            sizeBytes = file.expectedSize,
                            category = category,
                            dateEpoch = message.date.toLong()
                        )
                        if (fileLimit > 0 && collected.size >= fileLimit) {
                            limitReached = true
                            break
                        }
                    }

                    onProgress(walk.scannedMessages, collected.size, false)
                }
            }

            if (limitReached) break
        }

        // Publish this scan as the single source of truth for its token. Only
        // the chat id is retained: the messages themselves can be an enormous
        // map on a large channel, and each one is one cheap TDLib lookup away
        // when a download actually starts.
        ScanRegistry.record(scanToken, source.chatId)

        // Deliberately NOT re-sorted: the walk order (newest first) is the order
        // the user sees, so "file 1" in the range picker is the most recent
        // file — and stays the most recent when a limit truncates the scan.
        ScanResult(
            sourceTitle = source.title,
            files = collected,
            totalCount = collected.size,
            scannedMessages = walk.scannedMessages,
            limitReached = limitReached,
            fileLimit = fileLimit,
            otherMediaCount = otherMedia,
            historyIncomplete = walk.historyIncomplete
        )
    }

    /** Chat id captured for the scan with this token (0 if superseded). */
    fun chatIdForToken(token: Long): Long = ScanRegistry.chatIdFor(token)

    private suspend fun resolveChat(parsed: ParsedLink): TdApi.Chat {
        return when (parsed) {
            is ParsedLink.PublicChannel ->
                send(TdApi.SearchPublicChat(parsed.username))

            is ParsedLink.PrivateChannel -> {
                // Bot-API style -100 prefix + chat id, per TDLib conventions.
                val chatId = -100L * 1_000_000_000_000L - parsed.rawChatId * 1_000_000L
                getChat(chatId)
            }

            is ParsedLink.Invite -> {
                val info = send(TdApi.CheckChatInviteLink(parsed.hash))
                if (info.chatId != 0L) {
                    runCatching { getChat(info.chatId) }.onSuccess { return it }
                }
                // Not a member yet: join, then re-resolve.
                send(TdApi.JoinChatByInviteLink(parsed.hash))
                val joined = send(TdApi.CheckChatInviteLink(parsed.hash))
                getChat(joined.chatId)
            }
        }
    }

    private suspend fun getChat(chatId: Long): TdApi.Chat = send(TdApi.GetChat(chatId))

    private fun messageFile(message: TdApi.Message): TdApi.File? {
        return when (val content = message.content) {
            is TdApi.MessageAnimation -> content.animation.animation
            is TdApi.MessageAudio -> content.audio.audio
            is TdApi.MessageDocument -> content.document.document
            is TdApi.MessagePhoto -> content.photo.sizes.maxByOrNull { it.width * it.height }?.photo
            is TdApi.MessageVideo -> content.video.video
            is TdApi.MessageVideoNote -> content.videoNote.video
            is TdApi.MessageVoiceNote -> content.voiceNote.voice
            else -> null
        }
    }

    private fun categoryFor(message: TdApi.Message): String? {
        return when (val content = message.content) {
            is TdApi.MessageAudio -> "Audio"
            is TdApi.MessageVoiceNote -> "Audio"
            is TdApi.MessageVideo -> "Video"
            is TdApi.MessageVideoNote -> "Video"
            is TdApi.MessageAnimation -> "Video"
            is TdApi.MessagePhoto -> "Images"
            is TdApi.MessageDocument -> mimeCategory(content.document.mimeType)
            else -> null
        }
    }

    private fun mimeCategory(mime: String?): String {
        return when (mime?.lowercase()?.substringBefore('/')) {
            "audio" -> "Audio"
            "video" -> "Video"
            "image" -> "Images"
            else -> "Docs"
        }
    }

    private fun buildFileName(message: TdApi.Message): String {
        val (suggested, mime, fallback) = when (val content = message.content) {
            is TdApi.MessageDocument ->
                Triple(content.document.fileName, content.document.mimeType, "document")
            is TdApi.MessageAudio ->
                Triple(content.audio.fileName, content.audio.mimeType, "audio")
            is TdApi.MessageVideo ->
                Triple(content.video.fileName, content.video.mimeType, "video")
            is TdApi.MessageAnimation ->
                Triple(content.animation.fileName, content.animation.mimeType, "animation")
            is TdApi.MessageVideoNote -> Triple(null, null, "video_note_${message.id}.mp4")
            is TdApi.MessageVoiceNote -> Triple(null, content.voiceNote.mimeType, "voice_message_${message.id}")
            is TdApi.MessagePhoto -> Triple(null, null, "photo_${message.id}.jpg")
            else -> Triple(null, null, "file_${message.id}")
        }

        val extensionFromMime = mime?.substringAfter('/', "")
            ?.takeIf { it.isNotBlank() && it.length <= 5 }

        val base = (suggested?.takeIf { it.isNotBlank() } ?: fallback)
            .replace(Regex("[<>:\"/\\\\|?*\u0000]"), "_")
            .trim()
            .ifBlank { "file_${message.id}" }

        return when {
            base.contains('.') -> base
            extensionFromMime != null -> "$base.$extensionFromMime"
            else -> "$base.bin"
        }
    }

    // ------------------------------------------------------------------
    // Downloads
    // ------------------------------------------------------------------

    /**
     * Fetches the media attached to [message] into TDLib's own cache and
     * returns the local file, reporting progress through [downloadProgress].
     *
     * TDLib never writes outside app storage, so publishing the result into
     * the user's Download folder is the caller's job — see `DownloadsStore`,
     * which does it through MediaStore and therefore needs no permission.
     */
    suspend fun downloadToCache(message: TdApi.Message): File = withContext(Dispatchers.IO) {
        val file = messageFile(message)
            ?: throw IllegalStateException("Message has no downloadable media.")

        val downloaded = send(
            TdApi.DownloadFile(
                file.id,
                32, // high priority
                0,  // offset
                0,  // limit 0 = whole file
                true // synchronous: result arrives fully downloaded
            )
        )

        val local = File(downloaded.local.path)
        if (!local.exists() || local.length() == 0L) {
            throw IllegalStateException("Telegram did not return the downloaded file.")
        }

        // The synchronous result is authoritative — publish a 100% tick keyed
        // by fileId in case no completion UpdateFile arrived for this file, so
        // the row's progress bar actually fills.
        _downloadProgress.tryEmit(
            DownloadProgress(
                fileId = downloaded.id,
                name = local.name,
                downloadedBytes = local.length(),
                totalBytes = downloaded.expectedSize.takeIf { it > 0 } ?: local.length()
            )
        )
        local
    }

    suspend fun getMessage(chatId: Long, messageId: Long): TdApi.Message? =
        runCatching { send(TdApi.GetMessage(chatId, messageId)) }.getOrNull()

    // ------------------------------------------------------------------
    // Low-level request plumbing
    // ------------------------------------------------------------------

    private suspend fun <T : TdApi.Object> send(request: TdApi.Function<T>): T {
        val current = client ?: throw IllegalStateException("Telegram client is not connected.")
        return withTimeout(30_000L) {
            current.send(request)
        }
    }

    fun shutdown() {
        scope.cancel()
        runCatching { client?.close() }
        client = null
    }

    companion object {
        private val ALLOWED_HOSTS = setOf("t.me", "telegram.me", "www.t.me", "www.telegram.me")
    }
}
