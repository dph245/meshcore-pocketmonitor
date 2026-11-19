package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.ble.PrivateSender
import de.meshcore.pocketmonitor.protocol.GroupSend
import de.meshcore.pocketmonitor.protocol.PrivateRetryOptions

@Composable
internal fun PrivateComposer(
    draft: String, changeDraft: (String) -> Unit, sender: PrivateSender?, submitting: Boolean, error: String?,
    options: PrivateRetryOptions, changeOptions: (PrivateRetryOptions) -> Unit,
    onSend: (String, PrivateRetryOptions) -> Unit,
) {
    var showOptions by remember { mutableStateOf(false) }
    val size = remember(draft) { GroupSend.textBytes(draft)?.size }
    val limit = sender?.textLimit ?: 158
    OutlinedTextField(
        value = draft,
        onValueChange = changeDraft,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Private Nachricht") },
        supportingText = { Text("${size ?: 0} / $limit Bytes") },
        isError = size != null && size > limit,
        maxLines = 4,
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (sender == null) {
        Text("Senden benötigt einen verbundenen Companion mit eindeutigem Kontakt.",
            style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { onSend(draft, options) },
            enabled = sender != null && !sender.busy && !submitting && size != null && size <= limit,
        ) { Text("Senden") }
        TextButton(onClick = { showOptions = true }) {
            Text("Versand · ${if (options.manualRouting) "MANUELL" else "AUTO"}")
        }
        if (sender?.busy == true) Text("Companion beschäftigt", style = MaterialTheme.typography.bodySmall)
    }
    if (showOptions) {
        PrivateSendOptionsDialog(options, sender, onSave = {
            changeOptions(it)
            showOptions = false
        }, onDismiss = { showOptions = false })
    }
}
