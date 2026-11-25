package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.tcp.TcpEndpoint

@Composable
internal fun TcpConnectionForm(
    savedHost: String,
    savedPort: Int,
    active: Boolean,
    ready: Boolean,
    autoReconnect: Boolean,
    onAutoReconnect: (Boolean) -> Unit,
    onConnect: (TcpEndpoint) -> Unit,
    onDisconnect: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(savedHost) }
    var port by rememberSaveable { mutableStateOf(savedPort.toString()) }
    val focus = LocalFocusManager.current
    val portNumber = port.takeIf { it.all { digit -> digit in '0'..'9' } }?.toIntOrNull()
    val endpoint = TcpEndpoint.parse(host, portNumber)
    val invalidHost = host.isNotBlank() && !TcpEndpoint.hasValidAddress(host)
    val invalidPort = port.isNotEmpty() && !TcpEndpoint.hasValidPort(portNumber)
    OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text("IP-Adresse") },
        placeholder = { Text("192.168.1.10") },
        supportingText = if (invalidHost) ({ Text("Gültige IPv4- oder IPv6-Adresse eingeben.") }) else null,
        modifier = Modifier.fillMaxWidth(),
        enabled = !active,
        singleLine = true,
        isError = invalidHost,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = port,
        onValueChange = { port = it },
        label = { Text("Port") },
        supportingText = if (invalidPort) ({ Text("Port von 1 bis 65535 eingeben.") }) else null,
        modifier = Modifier.fillMaxWidth(),
        enabled = !active,
        singleLine = true,
        isError = invalidPort,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(autoReconnect, role = Role.Switch, onValueChange = onAutoReconnect),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Automatisch wiederverbinden", Modifier.weight(1f))
        Switch(checked = autoReconnect, onCheckedChange = null)
    }
    Button(
        onClick = {
            focus.clearFocus()
            if (active) onDisconnect() else endpoint?.let(onConnect)
        },
        enabled = active || (ready && endpoint != null),
    ) { Text(if (active) "Trennen" else "Verbinden") }
}
