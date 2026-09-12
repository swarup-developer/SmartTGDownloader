package com.swarupbaral.smarttgdownloader.data

import android.content.Context
import com.swarupbaral.smarttgdownloader.BuildConfig
import io.github.tdlibandroid.ktx.TdClient
import io.github.tdlibandroid.ktx.TdException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        data class AwaitingCode(val hint: String?) : AuthState
        data class AwaitingPassword(val hint: String?) : AuthState
        data class Ready(val userName: String) : AuthState
        data class Error(val message: String) : AuthState
    }

    data class ScannedFile(
        val messageId: Long,
        val name: String,
        val sizeBytes: Long,
        val category: String,
        val dateEpoch: Long
    )

    data class ScanResult(
        val sourceTitle: String,
        val files: List<ScannedFile>,
        val totalCount: Int
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
                    _authState.value = AuthState.Error(e.message ?: "Telegram client error")
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
        val msg = error.message ?: ""
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
                _authState.value = AuthState.AwaitingCode(hint = null)

            is TdApi.AuthorizationStateWaitCode ->
                _authState.value = AuthState.AwaitingCode(hint = null)

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

    /** Surfaces a user-facing auth error from the ViewModel layer. */
    fun reportError(message: String) {
        val current = _authState.value
        if (current !is AuthState.Ready) {
            _authState.value = AuthState.Error(message)
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

    /** Human-readable message for common TDLib auth errors. */
    private fun mapAuthError(e: TdException): String {
        val msg = e.message ?: return "Telegram error"
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

    /**
     * Analyzes [link], returning every media file matching [categories]
     * ("Audio", "Video", "Images", "Docs").
     *
     * A monotonically increasing [scanToken] guarantees results from an older
     * scan can never overwrite a newer request — the stale-cache bug from the
     * previous version is structurally impossible here because each scan owns
     * its token and results are returned (never cached) per token.
     */
    suspend fun scanLink(
        link: String,
        categories: Set<String>,
        scanToken: Long
    ): ScanResult = withContext(Dispatchers.IO) {
        val parsed = parseLink(link)
        val chat = resolveChat(parsed)
        val sourceTitle = chat.title.ifBlank { "Telegram" }

        val collected = mutableListOf<ScannedFile>()
        val messagesById = mutableMapOf<Long, TdApi.Message>()
        var fromMessageId = 0L
        val pageSize = 100

        while (true) {
            val history = send(
                TdApi.GetChatHistory(
                    chat.id,
                    fromMessageId,
                    0,
                    pageSize,
                    false
                )
            )

            val messages = history.messages ?: emptyArray()
            if (messages.isEmpty()) break

            for (message in messages) {
                val file = messageFile(message) ?: continue
                val category = categoryFor(message)
                if (category == null || categories.none { it.equals(category, true) }) continue

                messagesById[message.id] = message
                collected += ScannedFile(
                    messageId = message.id,
                    name = buildFileName(message),
                    sizeBytes = file.expectedSize,
                    category = category,
                    dateEpoch = message.date.toLong()
                )
            }

            if (messages.size < pageSize) break
            fromMessageId = messages.last().id
        }

        collected.sortBy { it.messageId }

        // Publish this scan as the single source of truth for its token.
        ScanRegistry.record(scanToken, chat.id, messagesById)

        ScanResult(
            sourceTitle = sourceTitle,
            files = collected,
            totalCount = collected.size
        )
    }

    /** TDLib file id of the media attached to [message], if any. */
    fun fileIdOf(message: TdApi.Message): Int? = messageFile(message)?.id

    /** Chat id captured for the scan with this token (0 if superseded). */
    fun chatIdForToken(token: Long): Long = ScanRegistry.chatIdFor(token)

    /** Raw TDLib messages captured for the scan with this token. */
    fun messagesForToken(token: Long): Map<Long, TdApi.Message> =
        ScanRegistry.messagesFor(token)

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
     * Downloads the media attached to [message] into [destinationDir],
     * reporting progress through [downloadProgress]. Returns the local path.
     */
    suspend fun downloadFile(
        message: TdApi.Message,
        destinationDir: File
    ): String = withContext(Dispatchers.IO) {
        val file = messageFile(message)
            ?: throw IllegalStateException("Message has no downloadable media.")

        destinationDir.mkdirs()
        val target = File(destinationDir, buildFileName(message))
        if (target.exists() && file.expectedSize > 0 && target.length() == file.expectedSize) {
            return@withContext target.absolutePath
        }

        val downloaded = send(
            TdApi.DownloadFile(
                file.id,
                32, // high priority
                0,  // offset
                0,  // limit 0 = whole file
                true // synchronous: result arrives fully downloaded
            )
        )

        val finalLocal = downloaded.local.path
        val finalFile = File(finalLocal)
        if (finalFile.exists() && finalFile.absolutePath != target.absolutePath) {
            finalFile.copyTo(target, overwrite = true)
        }
        // The synchronous result is authoritative — publish a 100% tick keyed
        // by fileId in case no completion UpdateFile arrived for this file.
        _downloadProgress.tryEmit(
            DownloadProgress(
                fileId = downloaded.id,
                name = target.name,
                downloadedBytes = downloaded.expectedSize.takeIf { it > 0 } ?: target.length(),
                totalBytes = downloaded.expectedSize.takeIf { it > 0 } ?: target.length()
            )
        )
        target.absolutePath
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
