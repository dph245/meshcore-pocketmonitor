package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import de.meshcore.pocketmonitor.ble.PrivateSender
import de.meshcore.pocketmonitor.protocol.HopResolver
import de.meshcore.pocketmonitor.protocol.HopStatus
import de.meshcore.pocketmonitor.protocol.PrivateSend
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun PrivatePathHeader(model: MonitorViewModel, key: String, sender: PrivateSender?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session = sender?.session
    LaunchedEffect(model, lifecycle, session, key) {
        if (session == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.watchPrivatePath(session, key, watching = true)
            try {
                awaitCancellation()
            } finally {
                model.watchPrivatePath(session, key, watching = false)
            }
        }
    }
    val current by model.privatePath.collectAsStateWithLifecycle()
    val snapshot = current?.takeIf { it.session == session && it.key == key }
    val contact = snapshot?.contact
    val path = remember(contact) { contact?.let(PrivateSend::outgoingPath) }
    val repeaters = sender?.repeaters.orEmpty()
    val resolver = remember(repeaters) { HopResolver(repeaters) }
    val hops = remember(resolver, path) {
        path?.let(resolver::resolve).orEmpty().map {
            when (it.status) {
                HopStatus.UNIQUE -> "${it.matches.single().displayName} (${it.prefix})"
                HopStatus.AMBIGUOUS -> "${it.prefix} (mehrdeutig)"
                HopStatus.UNKNOWN -> it.prefix
            }
        }
    }
    var expanded by remember(session, key) { mutableStateOf(false) }
    val value = when {
        session == null -> "Nicht verfügbar"
        snapshot == null -> "Wird gelesen …"
        contact?.outPathLength == PrivateSend.UNKNOWN_PATH -> "Flood · kein gelernter Direct-Pfad"
        path == null -> "Nicht verfügbar"
        hops.isEmpty() -> "Direct · 0 Hops"
        else -> "Direct · ${hops.joinToString(" → ")}"
    }
    Text(
        "Aktueller Pfad · $value",
        Modifier.fillMaxWidth().then(if (hops.isNotEmpty()) Modifier.clickable { expanded = true } else Modifier)
            .padding(bottom = 6.dp),
        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text("Aktueller Pfad") },
            text = { Text(value, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text("Schließen") } },
        )
    }
}
