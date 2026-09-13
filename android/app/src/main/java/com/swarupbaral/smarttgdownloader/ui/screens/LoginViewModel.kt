package com.swarupbaral.smarttgdownloader.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swarupbaral.smarttgdownloader.AppContainer
import com.swarupbaral.smarttgdownloader.data.TelegramRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LoginViewModel : ViewModel() {

    private val repository = AppContainer.repository

    val authState: StateFlow<TelegramRepository.AuthState> =
        repository.authState.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = repository.authState.value
        )

    // --- Phone number --------------------------------------------------------

    private val _phone = MutableStateFlow(AppContainer.appPreferences.lastPhoneNumber.value)

    /** Phone field contents; pre-filled from the last successful sign-in. */
    val phone: StateFlow<String> = _phone.asStateFlow()

    fun updatePhone(value: String) {
        _phone.value = value
    }

    // --- Action errors -------------------------------------------------------

    /**
     * Failures of the phone/code/password actions.
     *
     * Deliberately separate from [TelegramRepository.AuthState.Error]: a wrong
     * code or a too-early resend must not tear the form down, or the user
     * loses everything they typed. Only connection-level failures replace the
     * whole screen.
     */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    fun clearActionError() {
        _actionError.value = null
    }

    // --- Resend countdown ----------------------------------------------------

    /**
     * Seconds left before Telegram will accept another code request.
     *
     * Telegram's own cooldown is server-side and grows with every request, so
     * the countdown is the larger of the value TDLib reported and the user-
     * facing ladder (see [RESEND_LADDER_SECONDS]). Runs here rather than in the
     * composable so it survives recomposition and rotation.
     */
    private val _resendSecondsLeft = MutableStateFlow(0)
    val resendSecondsLeft: StateFlow<Int> = _resendSecondsLeft.asStateFlow()

    /**
     * Bumped on every accepted code request. The login screen uses it to
     * (a) re-focus the code field and (b) re-key the live-region status text,
     * so a second "OTP sent successfully" is actually spoken again instead of
     * being swallowed as an unchanged node.
     */
    private val _codeGeneration = MutableStateFlow(0)
    val codeGeneration: StateFlow<Int> = _codeGeneration.asStateFlow()

    private var countdownJob: Job? = null
    private var resendAttempt = 0

    fun retry() {
        clearActionError()
        repository.start()
    }

    fun requestOtp(rawPhone: String = _phone.value) {
        val normalized = normalizePhone(rawPhone)
        clearActionError()
        viewModelScope.launch {
            runCatching { repository.requestOtp(normalized) }
                .onSuccess {
                    _phone.value = normalized
                    AppContainer.appPreferences.setLastPhoneNumber(normalized)
                    // First code of this login: back to the bottom of the ladder.
                    resendAttempt = 0
                    // Drives the "OTP sent successfully" live region.
                    _codeGeneration.value += 1
                    startCountdown(serverTimeoutSeconds())
                }
                .onFailure { _actionError.value = it.message ?: "Could not send the code." }
        }
    }

    fun resendOtp() {
        if (_resendSecondsLeft.value > 0) return
        clearActionError()
        viewModelScope.launch {
            runCatching { repository.resendCode() }
                .onSuccess {
                    if (resendAttempt < RESEND_LADDER_SECONDS.lastIndex) resendAttempt++
                    // Re-keying this is what makes the repeat announcement.
                    _codeGeneration.value += 1
                    startCountdown(serverTimeoutSeconds())
                }
                .onFailure { _actionError.value = it.message ?: "Could not resend the code." }
        }
    }

    /**
     * Starts or stops the countdown to match [state]. Called whenever the auth
     * state changes, so a request that happened elsewhere (or before a
     * process restart) still gets a countdown.
     */
    fun syncCountdown(state: TelegramRepository.AuthState) {
        if (state !is TelegramRepository.AuthState.AwaitingCode) {
            countdownJob?.cancel()
            countdownJob = null
            _resendSecondsLeft.value = 0
            return
        }
        if (countdownJob?.isActive == true || _resendSecondsLeft.value > 0) return
        startCountdown(state.resendInSeconds)
    }

    fun confirmOtp(code: String) {
        clearActionError()
        viewModelScope.launch {
            runCatching { repository.confirmOtp(code.trim()) }
                .onFailure { _actionError.value = it.message ?: "OTP verification failed." }
        }
    }

    fun confirmPassword(password: String) {
        clearActionError()
        viewModelScope.launch {
            runCatching { repository.confirmPassword(password) }
                .onFailure { _actionError.value = it.message ?: "Password verification failed." }
        }
    }

    private fun serverTimeoutSeconds(): Int {
        val state = repository.authState.value
        return (state as? TelegramRepository.AuthState.AwaitingCode)?.resendInSeconds ?: 0
    }

    private fun startCountdown(serverTimeout: Int) {
        countdownJob?.cancel()
        val wait = waitSecondsFor(resendAttempt, serverTimeout)
        countdownJob = viewModelScope.launch {
            _resendSecondsLeft.value = wait
            while (_resendSecondsLeft.value > 0) {
                delay(1_000L)
                _resendSecondsLeft.value -= 1
            }
        }
    }

    override fun onCleared() {
        countdownJob?.cancel()
        countdownJob = null
        super.onCleared()
    }

    private fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() || it == '+' }
        return when {
            digits.startsWith("+") -> digits
            else -> "+$digits"
        }
    }

    companion object {
        /**
         * User-facing wait before a resend is offered, escalating per attempt:
         * 30s, then 50s, then a minute for every request after that. Mirrors
         * the behavior the product asked for; TDLib's own (often longer)
         * cooldown always wins when it reports one.
         */
        val RESEND_LADDER_SECONDS = listOf(30, 50, 60)

        fun waitSecondsFor(attempt: Int, serverTimeoutSeconds: Int): Int {
            val ladder = RESEND_LADDER_SECONDS[attempt.coerceIn(0, RESEND_LADDER_SECONDS.lastIndex)]
            return maxOf(ladder, serverTimeoutSeconds.coerceAtLeast(0))
        }
    }
}
