package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.ble.ContactStatus

@Composable
internal fun ContactOverview(status: ContactStatus, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val contacts = remember(status.book) {
        status.book?.list?.contacts.orEmpty().sortedBy { it.displayName.lowercase() }
    }
    val counts = remember(contacts) {
        contacts.groupingBy { it.typeLabel }.eachCount().entries.sortedBy { it.key }
            .joinToString(" · ") { "${it.key} ${it.value}" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kontakte") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        dismissButton = {
            TextButton(onClick = onRefresh, enabled = status.canRefresh) { Text("Aktualisieren") }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status.loading) Text("Kontaktliste wird geladen …")
                status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (status.book != null) {
                    Text(if (contacts.isEmpty()) "Keine Kontakte gespeichert." else counts)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(contacts, key = { it.publicKey }) { contact ->
                            HorizontalDivider()
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(contact.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${contact.typeLabel} · ${contact.publicKey.take(6)}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
    )
}
