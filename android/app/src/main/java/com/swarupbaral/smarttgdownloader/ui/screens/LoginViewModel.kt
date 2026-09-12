package com.swarupbaral.smarttgdownloader.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swarupbaral.smarttgdownloader.AppContainer
import com.swarupbaral.smarttgdownloader.data.TelegramRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    fun retry() {
        repository.start()
    }

    fun dismissError() {
        // Re-entering the phone step re-syncs from TDLib's current state.
        repository.start()
    }

    fun requestOtp(phone: String) {
        viewModelScope.launch {
            runCatching { repository.requestOtp(normalizePhone(phone)) }
                .onFailure { repository.reportError(it.message ?: "Could not send OTP.") }
        }
    }

    fun confirmOtp(code: String) {
        viewModelScope.launch {
            runCatching { repository.confirmOtp(code.trim()) }
                .onFailure { repository.reportError(it.message ?: "OTP verification failed.") }
        }
    }

    fun confirmPassword(password: String) {
        viewModelScope.launch {
            runCatching { repository.confirmPassword(password) }
                .onFailure { repository.reportError(it.message ?: "Password verification failed.") }
        }
    }

    private fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() || it == '+' }
        return when {
            digits.startsWith("+") -> digits
            else -> "+$digits"
        }
    }
}
