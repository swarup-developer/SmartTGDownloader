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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swarupbaral.smarttgdownloader.data.TelegramRepository

@Composable
fun LoginScreen(viewModel: LoginViewModel = viewModel()) {
    val authState by viewModel.authState.collectAsState()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

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
            "Sign in with your phone number. You will receive a code inside Telegram.",
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
                Button(onClick = { viewModel.retry() }) { Text("Retry") }
            }

            is TelegramRepository.AuthState.AwaitingCode -> {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone number (with country code)") },
                    placeholder = { Text("+919876543210") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.requestOtp(phone) },
                    enabled = phone.length >= 6,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Get OTP") }

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("OTP code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.confirmOtp(code) },
                    enabled = code.length >= 4,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Login") }
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
                    modifier = Modifier.fillMaxWidth()
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
    }
}
