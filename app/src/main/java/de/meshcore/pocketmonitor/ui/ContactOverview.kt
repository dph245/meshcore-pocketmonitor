package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.ble.ContactStatus
import de.meshcore.pocketmonitor.protocol.CompanionAction
import de.meshcore.pocketmonitor.protocol.CompanionContact
import de.meshcore.pocketmonitor.protocol.CompanionManagement
import de.meshcore.pocketmonitor.protocol.ContactCategory

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContactOverview(
    status: ContactStatus,
    ready: Boolean,
    onRefresh: () -> Unit,
    onChange: (CompanionAction, (Result<String?>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var category by remember { mutableStateOf(ContactCategory.CHAT) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CompanionContact?>(null) }
    val contacts = remember(status.book) {
        status.book?.list?.contacts.orEmpty().sortedBy { it.displayName.lowercase() }
    }
    val groups = remember(contacts) {
        ContactCategory.entries.associateWith { group -> contacts.filter(group::contains) }
    }
    val canChange = ready && status.canRefresh && !status.loading
    if (adding) {
        AddContactDialog(category, canChange, onChange) { adding = false }
        return
    }
    deleting?.let { contact ->
        RemoveContactDialog(contact, canChange, onChange) { deleting = null }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kontakte") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        dismissButton = {
            TextButton(onClick = onRefresh, enabled = canChange) { Text("Aktualisieren") }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ContactCategory.entries.forEach { option ->
                        val count = groups.getValue(option).size
                        if (option != ContactCategory.OTHER || count > 0 || category == option) {
                            FilterChip(
                                selected = category == option,
                                onClick = { category = option },
                                label = { Text("${option.label} $count") },
                            )
                        }
                    }
                }
                if (category != ContactCategory.OTHER) {
                    TextButton(onClick = { adding = true }, enabled = canChange) { Text("Kontakt hinzufügen") }
                }
                if (status.loading) Text("Kontaktliste wird geladen …")
                status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (status.book != null) {
                    val rows = groups.getValue(category)
                    if (rows.isEmpty()) Text("Keine Kontakte in dieser Kategorie.")
                    key(category) {
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            items(rows, key = { it.publicKey }) { contact ->
                                HorizontalDivider()
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(contact.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("${contact.typeLabel} · ${contact.publicKey.take(12)}",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { deleting = contact }, enabled = canChange) { Text("Löschen") }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun AddContactDialog(
    category: ContactCategory, enabled: Boolean,
    onChange: (CompanionAction, (Result<String?>) -> Unit) -> Unit, onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf("") }
    val action = CompanionAction.AddContact(publicKey, name, category)
    val valid = CompanionManagement.addContact(action) != null
    ContactChangeDialog("Kontakt hinzufügen · ${category.label}", "Hinzufügen", enabled && valid, action, onChange, onDismiss) {
        OutlinedTextField(
            name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            publicKey, { publicKey = it }, label = { Text("Öffentlicher Schlüssel") }, singleLine = true,
            supportingText = { Text("64 Hex-Zeichen") }, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RemoveContactDialog(
    contact: CompanionContact, enabled: Boolean,
    onChange: (CompanionAction, (Result<String?>) -> Unit) -> Unit, onDismiss: () -> Unit,
) {
    ContactChangeDialog("Kontakt löschen?", "Löschen", enabled, CompanionAction.RemoveContact(contact.publicKey),
        onChange, onDismiss) {
        Text(contact.displayName, style = MaterialTheme.typography.titleSmall)
        Text(contact.publicKey, style = MaterialTheme.typography.bodySmall)
        Text("Der Kontakt wird aus dem Kontaktbuch des Companions entfernt.")
    }
}

@Composable
private fun ContactChangeDialog(
    title: String, confirm: String, enabled: Boolean, action: CompanionAction,
    onChange: (CompanionAction, (Result<String?>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        confirmButton = {
            TextButton(onClick = {
                focusManager.clearFocus()
                saving = true
                error = null
                onChange(action) { result ->
                    saving = false
                    result.fold(onSuccess = { onDismiss() }, onFailure = { error = it.message })
                }
            }, enabled = enabled && !saving) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Abbrechen") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                content()
                if (saving) Text("Kontaktbuch wird abgeglichen …")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}
