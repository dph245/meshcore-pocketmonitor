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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.meshcore.pocketmonitor.rx.CHAT_PAGE_SIZE
import de.meshcore.pocketmonitor.rx.HistoryChannel
import de.meshcore.pocketmonitor.rx.HistoryPage
import de.meshcore.pocketmonitor.rx.MessageCursor
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun ChannelView(
    model: MonitorViewModel,
    companion: String?,
    connectionError: String?,
    modifier: Modifier = Modifier,
    privateMessages: Boolean = false,
) {
    var selectedIdentity by rememberSaveable(companion) { mutableStateOf<String?>(null) }
    val conversationState = key(companion) { rememberSaveableStateHolder() }
    var previews by remember(companion) { mutableStateOf<List<ChannelPreview>?>(null) }
    var error by remember(companion) { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle, companion, selectedIdentity) {
        if (companion == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var revision = -1L
            var nextRefresh = 0L
            while (isActive) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!privateMessages && now >= nextRefresh) {
                    model.refreshChannels()
                    nextRefresh = now + 30_000
                }
                val current = model.historyRevision
                if (revision != current) {
                    try {
                        val updated = withContext(Dispatchers.IO) {
                            model.historyChannels(companion, privateMessages).map { channel ->
                                val last = if (selectedIdentity == null) {
                                    model.channelMessages(companion, channel.identity, null, 1).rows.lastOrNull()
                                } else null
                                ChannelPreview(channel, last)
                            }
                        }
                        previews = updated
                        revision = current
                        error = null
                    } catch (_: SQLiteException) {
                        error = "Nachrichtenverlauf konnte nicht gelesen werden."
                    }
                }
                delay(350)
            }
        }
    }
    val channels = previews.orEmpty()
    val channel = channels.firstOrNull { it.channel.identity == selectedIdentity }?.channel
    val closeConversation = { selectedIdentity = null }
    LaunchedEffect(previews, selectedIdentity) {
        if (previews != null && channel == null) closeConversation()
    }
    BackHandler(enabled = channel != null, onBack = closeConversation)
    Column(modifier) {
        (error ?: connectionError)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        when {
            companion == null -> Text("Companion verbinden, um Nachrichten anzuzeigen.")
            channel != null -> conversationState.SaveableStateProvider("$companion/${channel.identity}") {
                PagedConversation(model, companion, channel, closeConversation, Modifier.weight(1f), privateMessages)
            }
            channels.isNotEmpty() -> LazyColumn {
                items(channels, key = { it.channel.identity }) { (item, last) ->
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            selectedIdentity = item.identity
                        }.padding(horizontal = 4.dp, vertical = 10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            when {
                                privateMessages && channels.count { it.channel.name == item.name } > 1 ->
                                    Text(item.identity.substringAfterLast(':').take(12), style = MaterialTheme.typography.labelSmall)
                                privateMessages -> Unit
                                item.slot == null && !item.identity.startsWith("slot:") -> Text("Archiv · ${item.identity.take(8)}", style = MaterialTheme.typography.labelSmall)
                                item.slot != null && channels.count { it.channel.name == item.name } > 1 ->
                                    Text("Slot ${item.slot}", style = MaterialTheme.typography.labelSmall)
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
            else -> Text(when {
                previews == null -> "Lese Nachrichten …"
                privateMessages -> "Keine privaten Unterhaltungen."
                else -> "Keine Kanäle verfügbar."
            })
        }
    }
}

private data class ChannelPreview(val channel: HistoryChannel, val last: ChannelMessage?)

@Composable
private fun PagedConversation(
    model: MonitorViewModel, companion: String, channel: HistoryChannel, onBack: () -> Unit, modifier: Modifier,
    privateMessages: Boolean,
) {
    // Keep only one page of messages, plus the keys needed to navigate back.
    var boundaries by remember { mutableStateOf(listOf<MessageCursor?>(null)) }
    val before = boundaries.last()
    var page by remember { mutableStateOf<HistoryPage<ChannelMessage>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle, companion, channel.identity, before) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var revision = -1L
            while (isActive) {
                val current = model.historyRevision
                if (revision != current) {
                    try {
                        val updated = withContext(Dispatchers.IO) {
                            model.channelMessages(companion, channel.identity, before, CHAT_PAGE_SIZE)
                        }
                        if (updated != page) page = updated
                        error = null
                        revision = current
                    } catch (_: SQLiteException) {
                        error = "Nachrichten konnten nicht gelesen werden."
                    }
                }
                delay(350)
            }
        }
    }
    Column(modifier) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ChannelConversation(channel.name, page?.rows.orEmpty(), onBack, Modifier.weight(1f), list,
            backLabel = if (privateMessages) "‹ Privat" else "‹ Kanäle")
        if (page?.hasOlder == true || before != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (before != null) TextButton(onClick = {
                    boundaries = boundaries.dropLast(1)
                    page = null
                    list.requestScrollToItem(0)
                }) { Text("Neuere") }
                if (page?.hasOlder == true) TextButton(onClick = {
                    page?.rows?.firstOrNull()?.let {
                        boundaries = boundaries + MessageCursor(it.timestampSeconds, it.sequence)
                        page = null
                        list.requestScrollToItem(0)
                    }
                }) { Text("Ältere") }
            }
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
    backLabel: String = "‹ Kanäle",
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
            TextButton(onClick = onBack) { Text(backLabel) }
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (displayed.isEmpty()) {
            Text("Noch keine Nachrichten in dieser Unterhaltung.", Modifier.weight(1f))
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
