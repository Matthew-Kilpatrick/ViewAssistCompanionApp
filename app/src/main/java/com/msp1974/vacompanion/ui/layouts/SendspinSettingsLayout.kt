package com.msp1974.vacompanion.ui.layouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.MenuLayout

@Composable
fun SendspinSettingsLayout(
    viewModel: VAViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.vacaState.collectAsState()

    var hostDraft by remember(state.sendspinHost) { mutableStateOf(state.sendspinHost) }
    var portDraft by remember(state.sendspinPort) { mutableStateOf(state.sendspinPort.toString()) }
    var pathDraft by remember(state.sendspinPath) { mutableStateOf(state.sendspinPath) }

    MenuLayout(
        title = "Sendspin",
        level = 1,
        onClose = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Enable Sendspin receiver",
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Switch(
                    checked = state.sendspinEnabled,
                    onCheckedChange = viewModel::setSendspinEnabled,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Auto reconnect",
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Switch(
                    checked = state.sendspinReconnect,
                    onCheckedChange = viewModel::setSendspinReconnect,
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(
                    text = "Runtime status: ${state.sendspinStatus}",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = hostDraft,
                onValueChange = { hostDraft = it },
                label = { Text("Server host (optional)") },
                singleLine = true,
                enabled = !state.sendspinEnabled,
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = portDraft,
                onValueChange = { portDraft = it.filter { c -> c.isDigit() } },
                label = { Text("Server port") },
                singleLine = true,
                enabled = !state.sendspinEnabled,
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = pathDraft,
                onValueChange = { pathDraft = it },
                label = { Text("WebSocket path") },
                singleLine = true,
                enabled = !state.sendspinEnabled,
            )

            Button(
                onClick = {
                    viewModel.setSendspinHost(hostDraft)
                    viewModel.setSendspinPort(portDraft.toIntOrNull() ?: state.sendspinPort)
                    viewModel.setSendspinPath(pathDraft)
                },
                enabled = !state.sendspinEnabled,
            ) {
                Text("Save endpoint")
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "For auto-discovery, leave host blank. Disable Sendspin to edit endpoint values.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
