package com.swarupbaral.smarttgdownloader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swarupbaral.smarttgdownloader.data.TelegramRepository

/**
 * Telegram sign-in, split into two explicit steps so a screen reader user is
 * only ever shown the controls that make sense for the current step:
 *
 *  1. **Phone** ([TelegramRepository.AuthState.WaitPhoneNumber]) — number + Get OTP.
 *  2. **Code** ([TelegramRepository.AuthState.AwaitingCode]) — the code field, Log in,
 *     and a resend control that stays disabled for Telegram's cooldown.
 *
 * The only announcement this screen makes is "OTP sent successfully." — fired
 * when Telegram accepts a code request. Nothing else is pushed into the
 * accessibility layer, so the user is never talked over.
 */
@Composable
fun LoginScreen(viewModel: LoginViewModel = viewModel()) {
    val authState by viewModel.authState.collectAsState()
    val phone by viewModel.phone.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val secondsLeft by viewModel.resendSecondsLeft.collectAsState()
    val codeGeneration by viewModel.codeGeneration.collectAsState()
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val codeFocus = remember { FocusRequester() }

    // Keep the resend countdown in step with TDLib's authorization state.
    LaunchedEffect(authState) { viewModel.syncCountdown(authState) }

    // Drop the cursor into the code field the moment a code is expected — and
    // again after every resend, so the user lands in the right control instead
    // of hunting for it.
    LaunchedEffect(codeGeneration, authState) {
        if (authState is TelegramRepository.AuthState.AwaitingCode) {
            runCatching { codeFocus.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Telegram Login",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in with your phone number. Telegram sends you a login code.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(24.dp))

        when (val state = authState) {
            is TelegramRepository.AuthState.Connecting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Connecting to Telegram…")
            }

            is TelegramRepository.AuthState.Error -> {
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Assertive
                        error(state.message)
                    }
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = viewModel::retry) { Text("Retry") }
            }

            is TelegramRepository.AuthState.WaitPhoneNumber,
            is TelegramRepository.AuthState.AwaitingCode -> {
                val awaiting = state as? TelegramRepository.AuthState.AwaitingCode

                // The number field is shown until Telegram knows the number.
                // If a session was restored mid-login we may have no number to
                // echo back, so keep the field reachable rather than stranding
                // the user on a code step they cannot retry from.
                if (awaiting == null || phone.isBlank()) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = viewModel::updatePhone,
                        label = { Text("Phone number (with country code)") },
                        placeholder = { Text("+919876543210") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Phone number" }
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.requestOtp(phone) },
                        enabled = phone.count { it.isDigit() } >= 6,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Get OTP") }
                    if (awaiting != null) Spacer(Modifier.height(24.dp))
                }

                if (awaiting != null) {
                    // The one announcement this screen makes: the code went out.
                    //
                    // A live region rather than a forced announcement, because
                    // Android 16 dropped TYPE_ANNOUNCEMENT events entirely, so
                    // the old approach would be silently dead on a current
                    // device. Keying on the code generation means each resend
                    // produces a fresh node, so a repeated "OTP sent
                    // successfully" is spoken again instead of being treated as
                    // an unchanged node and swallowed.
                    if (codeGeneration > 0) {
                        key(codeGeneration) {
                            Text(
                                "OTP sent successfully.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                }
                            )
                        }
                    }
                    // Where the code went. Readable on focus, deliberately not
                    // a live region so it does not compete with the message
                    // above.
                    Text(
                        buildString {
                            append("Code sent")
                            awaiting.phoneNumber
                                ?.takeIf { it.isNotBlank() }
                                ?.let { append(" to $it") }
                            append(" via ${awaiting.channel.label}.")
                            if (awaiting.codeLength > 0) {
                                append(" ${awaiting.codeLength} digits.")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { input -> code = input.filter(Char::isDigit).take(8) },
                        label = { Text("OTP code") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(codeFocus)
                            .semantics {
                                contentDescription = if (awaiting.codeLength > 0) {
                                    "OTP code, ${awaiting.codeLength} digits"
                                } else {
                                    "OTP code"
                                }
                            }
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.confirmOtp(code) },
                        enabled = code.length >= 4,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Login") }
                    Spacer(Modifier.height(8.dp))
                    ResendControl(
                        secondsLeft = secondsLeft,
                        onResend = viewModel::resendOtp
                    )
                }
            }

            is TelegramRepository.AuthState.AwaitingPassword -> {
                Text("Two-factor authentication required")
                state.hint?.takeIf { it.isNotBlank() }?.let {
                    Text("Hint: $it", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Telegram password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Telegram password" }
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.confirmPassword(password) },
                    enabled = password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Verify password") }
            }

            else -> Unit
        }

        actionError?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Assertive
                    error(message)
                }
            )
        }
    }
}

/**
 * Resend button with a countdown.
 *
 * The remaining wait is part of the button's own label and exposed as a state
 * description, so TalkBack announces "Resend code in 30 seconds, wait 30
 * seconds, button, disabled" — the user learns why the control is unavailable
 * instead of finding a silently greyed-out button.
 */
@Composable
private fun ResendControl(secondsLeft: Int, onResend: () -> Unit) {
    val available = secondsLeft <= 0
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedButton(
            onClick = onResend,
            enabled = available,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    stateDescription = if (available) {
                        "Available"
                    } else {
                        "Wait $secondsLeft seconds"
                    }
                }
        ) {
            Text(if (available) "Resend code" else "Resend code in ${secondsLeft}s")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (available) {
                "Didn't get the code? You can request a new one."
            } else {
                "You can request a new code after the countdown."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}
