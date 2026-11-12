package de.meshcore.pocketmonitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

@Composable
internal fun ChannelView(
    journalName: String?,
    history: ChannelContent?,
    error: String?,
    modifier: Modifier = Modifier,
) {
    var selectedSlot by rememberSaveable(journalName) { mutableStateOf<Int?>(null) }
    var selectedIdentity by rememberSaveable(journalName) { mutableStateOf<String?>(null) }
    val conversationState = key(journalName) { rememberSaveableStateHolder() }
    val slots = history?.catalog?.slots.orEmpty()
    val selection = selectedSlot?.let { slot -> selectedIdentity?.let { ChannelSelection(slot, it) } }
    val channel = selection?.resolve(slots)
    val closeConversation = {
        selectedSlot = null
        selectedIdentity = null
    }
    LaunchedEffect(history?.catalog, selection) {
        if (history != null && channel == null) closeConversation()
    }
    BackHandler(enabled = channel != null, onBack = closeConversation)
    Column(modifier) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        when {
            journalName == null -> Text("Companion verbinden, um Kanäle anzuzeigen.")
            channel != null && history != null -> conversationState.SaveableStateProvider("$journalName/${channel.channel.identity}") {
                ChannelConversation(
                    channel.channel.name,
                    history.messagesFor(channel),
                    closeConversation,
                    Modifier.weight(1f),
                )
            }
            slots.isNotEmpty() && history != null -> LazyColumn {
                items(slots, key = { it.index }) { slot ->
                    val last = history.messagesFor(slot).lastOrNull()
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            selectedSlot = slot.index
                            selectedIdentity = slot.channel.identity
                        }.padding(horizontal = 4.dp, vertical = 10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(slot.channel.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (slots.count { it.channel.name == slot.channel.name } > 1) {
                                Text("Slot ${slot.index}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text(
                            last?.let { if (it.sender == null) it.text else "${it.sender}: ${it.text}" }
                                ?: "Noch keine Nachrichten",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                }
            }
            error == null -> Text(if (history == null) "Lese Kanäle vom Companion …" else "Keine Kanäle konfiguriert.")
        }
    }
}

@Composable
internal fun ChannelConversation(
    name: String,
    messages: List<ChannelMessage>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    list: LazyListState = rememberLazyListState(),
) {
    var displayed by remember { mutableStateOf(messages, referentialEqualityPolicy()) }
    LaunchedEffect(messages, list) {
        if (messages === displayed) return@LaunchedEffect
        // Conflate UI updates through a drag/fling. The journal reader continues to advance.
        snapshotFlow { list.isScrollInProgress }.first { !it }
        val index = list.firstVisibleItemIndex
        val anchor = displayed.getOrNull(displayed.lastIndex - index)
        if (anchor != null) {
            val position = messages.binarySearch(anchor, channelMessageOrder)
            if (position >= 0 && messages.lastIndex - position != index) {
                // The lazy list's local key lookup can miss an anchor after a large insertion.
                list.requestScrollToItem(messages.lastIndex - position, list.firstVisibleItemScrollOffset)
            }
        }
        displayed = messages
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Kanäle") }
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (displayed.isEmpty()) {
            Text("Noch keine Nachrichten in diesem Kanal.", Modifier.weight(1f))
        } else {
            val newestFirst = displayed.asReversed()
            val typography = MaterialTheme.typography
            val colors = MaterialTheme.colorScheme
            val shape = MaterialTheme.shapes.medium
            val bubble = Modifier.fillMaxWidth().background(colors.surfaceVariant, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
            val senderStyle = typography.titleSmall.copy(color = colors.onSurfaceVariant)
            val timeStyle = typography.labelSmall.copy(color = colors.onSurfaceVariant)
            val messageStyle = typography.bodyMedium.copy(color = colors.onSurfaceVariant)
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            ) {
                items(newestFirst, key = { it.sequence }) { message ->
                    val metadata = "${message.timestampLabel} · ${message.receptionLabel}"
                    val announcement = remember(message) {
                        AnnotatedString("${message.senderLabel}\n$metadata\n${message.text}")
                    }
                    Column(bubble.clearAndSetSemantics { text = announcement }) {
                        ListRowText(message.senderLabel, senderStyle)
                        ListRowText(metadata, timeStyle)
                        ListRowText(message.text, messageStyle)
                    }
                }
            }
        }
    }
}
