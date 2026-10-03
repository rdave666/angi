package com.example.angi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.angi.bridge.PhoneBridgePreferences
import com.example.angi.bridge.PhoneBridgeService
import com.example.ui.theme.AngiDarkSurface
import com.example.ui.theme.AngiTextPrimary

@Composable
fun PhoneBridgeSettings() {
    val context = LocalContext.current
    val preferences = remember { PhoneBridgePreferences(context) }
    var config by remember { mutableStateOf(preferences.load()) }
    var error by remember { mutableStateOf<String?>(null) }
    val state by PhoneBridgeService.state.collectAsState()
    Card(colors = CardDefaults.cardColors(containerColor = AngiDarkSurface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Remote Phone Bridge", color = AngiTextPrimary)
            Text("Connect this phone to your relay. Requires Debian installed in Diagnostics. Enable the local API separately for model requests.", color = AngiTextPrimary)
            OutlinedTextField(value = config.relayUrl, onValueChange = { config = config.copy(relayUrl = it) },
                label = { Text("Relay HTTPS URL") }, singleLine = true, enabled = !state.isRunning, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = config.workerToken, onValueChange = { config = config.copy(workerToken = it) },
                label = { Text("Worker token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                enabled = !state.isRunning, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(checked = config.allowShell, onCheckedChange = { config = config.copy(allowShell = it) }, enabled = !state.isRunning)
                Text("Allow remote Debian commands", color = AngiTextPrimary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(checked = config.allowCodex, onCheckedChange = { config = config.copy(allowCodex = it) }, enabled = !state.isRunning)
                Text("Allow Codex tasks (CLI required in Debian)", color = AngiTextPrimary)
            }
            Text("Enabled commands can read and change files accessible to Debian. Only share the controller token with trusted clients.", color = AngiTextPrimary)
            Text(state.message, color = AngiTextPrimary)
            error?.let { Text(it, color = AngiTextPrimary) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !state.isRunning, onClick = {
                    runCatching {
                        config = config.copy(relayUrl = config.relayUrl.trim(), workerToken = config.workerToken.trim())
                        preferences.save(config)
                        PhoneBridgeService.start(context)
                    }.onSuccess { error = null }.onFailure { error = it.message ?: "Could not connect" }
                }) { Text("Connect") }
                OutlinedButton(enabled = state.isRunning, onClick = { PhoneBridgeService.stop(context) }) { Text("Disconnect") }
                OutlinedButton(enabled = !state.isRunning, onClick = {
                    preferences.clear(); config = preferences.load(); error = null
                }) { Text("Forget") }
            }
        }
    }
}
