package de.meshcore.pocketmonitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.meshcore.pocketmonitor.ble.PrivateSender
import de.meshcore.pocketmonitor.ble.ChannelManagementStatus
import de.meshcore.pocketmonitor.protocol.GroupSend
import androidx.lifecycle.repeatOnLifecycle
import de.meshcore.pocketmonitor.rx.CHAT_PAGE_SIZE
import de.meshcore.pocketmonitor.rx.HistoryChannel
import de.meshcore.pocketmonitor.rx.HistoryPage
import de.meshcore.pocketmonitor.rx.MessageCursor
import de.meshcore.pocketmonitor.rx.PRIVATE_ID_PREFIX
import de.meshcore.pocketmonitor.protocol.PrivateSendStatus
import android.database.sqlite.SQLiteException
import android.content.ClipData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

@Composable
internal fun ChannelView(
    model: MonitorViewModel,
    companion: String?,
    connectionError: String?,
    modifier: Modifier = Modifier,
    privateMessages: Boolean = false,
    unread: Set<String> = emptySet(),
    channelManagement: ChannelManagementStatus? = null,
) {
    var order by remember(model, privateMessages) { mutableStateOf(model.conversationOrder(privateMessages)) }
    var selectedIdentity by rememberSaveable(companion) { mutableStateOf<String?>(null) }
    var contactQuery by remember(companion) { mutableStateOf("") }
    // The tab's saveable state can be restored after process death. Start the private list at the top.
    val privateList = if (privateMessages) remember(companion) { LazyListState() } else null
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
    val privateSender = if (privateMessages) {
        model.privateSender.collectAsStateWithLifecycle().value?.takeIf { it.companion == companion }
    } else null
    val channels = remember(previews, privateSender?.contacts, order, unread) {
        val historical = previews.orEmpty()
        val existing = historical.mapTo(mutableSetOf()) { it.channel.identity }
        val new = privateSender?.contacts.orEmpty().mapNotNull { contact ->
            if (!contact.isPrivateMessageContact) return@mapNotNull null
            val id = PRIVATE_ID_PREFIX + contact.publicKey
            if (id in existing) null else ChannelPreview(
                HistoryChannel(id, contact.name.ifBlank { contact.publicKey.take(12) }, null), null,
            )
        }
        (historical + new).sortedWith(order.comparator(unread))
    }
    val channel = channels.firstOrNull { it.channel.identity == selectedIdentity }?.channel
    val visibleChannels = remember(channels, contactQuery, privateMessages) {
        val query = contactQuery.trim()
        if (!privateMessages || query.isEmpty()) channels else channels.filter { (contact) ->
            contact.name.contains(query, ignoreCase = true) ||
                contact.identity.substringAfterLast(':').contains(query, ignoreCase = true)
        }
    }
    val closeConversation = { selectedIdentity = null }
    var managementSession by remember(companion) { mutableStateOf<String?>(null) }
    val liveManagement = channelManagement?.takeIf { it.companion == companion && !privateMessages }
    managementSession?.let { session ->
        ChannelManagementDialog(
            status = liveManagement?.takeIf { it.session == session },
            submit = { action, completed -> model.manageCompanion(session, action, completed) },
            onDismiss = { managementSession = null },
        )
    }
    LaunchedEffect(previews, channel) {
        if (!privateMessages && previews != null && channel == null) closeConversation()
    }
    BackHandler(enabled = channel != null, onBack = closeConversation)
    Column(modifier) {
        if (channel == null && liveManagement != null) {
            TextButton(onClick = { managementSession = liveManagement.session }) { Text("Kanäle verwalten") }
        }
        (error ?: connectionError)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        when {
            companion == null -> Text("Companion verbinden, um Nachrichten anzuzeigen.")
            channel != null -> conversationState.SaveableStateProvider("$companion/${channel.identity}") {
                PagedConversation(model, companion, channel, closeConversation, Modifier.weight(1f), privateMessages, privateSender)
            }
            channels.isNotEmpty() || (privateMessages && previews != null) -> {
                val list = privateList ?: rememberLazyListState()
                if (privateMessages) {
                    val firstIdentity = visibleChannels.firstOrNull()?.channel?.identity
                    val wasAtTop = remember(list, firstIdentity) {
                        list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0
                    }
                    LaunchedEffect(list, firstIdentity) {
                        if (wasAtTop) list.requestScrollToItem(0)
                    }
                    OutlinedTextField(
                        value = contactQuery,
                        onValueChange = {
                            contactQuery = it
                            list.requestScrollToItem(0)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Kontakte suchen") },
                        trailingIcon = if (contactQuery.isEmpty()) null else {
                            {
                                TextButton(onClick = {
                                    contactQuery = ""
                                    list.requestScrollToItem(0)
                                }) { Text("Leeren") }
                            }
                        },
                        singleLine = true,
                    )
                }
                ConversationSortMenu(order) {
                    order = it
                    model.saveConversationOrder(privateMessages, it)
                    list.requestScrollToItem(0)
                }
                LazyColumn(Modifier.weight(1f), state = list) {
                    if (visibleChannels.isEmpty()) {
                        item { Text(if (contactQuery.isNotBlank()) "Keine passenden Kontakte." else "Keine privaten Unterhaltungen.") }
                    }
                    items(visibleChannels, key = { it.channel.identity }) { (item, last) ->
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
                                if (item.identity in unread) UnreadDot()
                                Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                when {
                                    privateMessages ->
                                        Text(item.identity.substringAfterLast(':').take(12), style = MaterialTheme.typography.labelSmall)
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
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        HorizontalDivider()
                    }
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

internal data class ChannelPreview(val channel: HistoryChannel, val last: ChannelMessage?)

internal enum class ConversationOrder(val label: String) {
    ALPHABETICAL("A–Z"),
    LAST_INCOMING("Letzter Eingang");

    fun comparator(unread: Set<String>): Comparator<ChannelPreview> {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val priority = compareByDescending<ChannelPreview> { it.channel.identity in unread }
        val ordering = if (this == LAST_INCOMING) priority.thenByDescending { it.channel.lastIncoming }
            else priority
        return ordering.thenComparator { left, right -> collator.compare(left.channel.name, right.channel.name) }
            .thenBy { it.channel.identity }
    }
}

@Composable
private fun ConversationSortMenu(order: ConversationOrder, select: (ConversationOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("Sortieren · ${order.label} ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ConversationOrder.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                    select(option)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun PagedConversation(
    model: MonitorViewModel, companion: String, channel: HistoryChannel, onBack: () -> Unit, modifier: Modifier,
    privateMessages: Boolean, privateSender: PrivateSender?,
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
                            model.channelMessages(companion, channel.identity, before, CHAT_PAGE_SIZE,
                                includeUnreadThrough = true)
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
    val liveSender by model.groupSender.collectAsStateWithLifecycle()
    val sender = liveSender?.takeIf { it.companion == companion && channel.identity in it.channels }
    var draft by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf<String?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    if (!privateMessages) {
        LaunchedEffect(model, companion, channel.identity) {
            if (scope == null) {
                try {
                    scope = withContext(Dispatchers.IO) { model.groupScope(companion, channel.identity) }
                        ?: GroupSend.DEFAULT
                } catch (_: SQLiteException) {
                    scope = GroupSend.DEFAULT
                    sendError = "Scope-Auswahl konnte nicht gelesen werden."
                }
            }
        }
        page?.rows?.find { it.sequence == detailId && it.outgoing != null }?.let { message ->
            GroupDetails(model, companion, message, onDismiss = { detailId = null }, onReuse = {
                draft = message.text
                scope = message.outgoing?.scope
                sendError = null
                detailId = null
            })
        }
    }
    val unreadThrough = page?.unreadThrough
    LaunchedEffect(lifecycle, unreadThrough, before) {
        if (before != null || unreadThrough == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                model.markConversationReadThrough(companion, channel.identity, unreadThrough)
            } catch (_: SQLiteException) {
                error = "Lesestatus konnte nicht gespeichert werden."
            }
        }
    }
    val privateKey = channel.identity.removePrefix(PRIVATE_ID_PREFIX)
    val eligible = privateSender?.takeIf { sender -> sender.contacts.any { it.publicKey == privateKey } }
    val conversationHeader: (@Composable () -> Unit)? = if (privateMessages) {
        { PrivatePathHeader(model, privateKey, eligible) }
    } else null
    Column(modifier) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ChannelConversation(channel.name, page?.rows.orEmpty(), onBack, Modifier.weight(1f), list,
            backLabel = if (privateMessages) "‹ Privat" else "‹ Kanäle",
            header = conversationHeader,
            followLatest = before == null,
            onRead = if (before == null) null else { ids ->
                try {
                    model.markMessagesRead(companion, channel.identity, ids)
                } catch (_: SQLiteException) {
                    error = "Lesestatus konnte nicht gespeichert werden."
                }
            },
            onReply = if (privateMessages) null else { nickname ->
                draft = "@[$nickname] $draft"
                sendError = null
            },
            onOutgoingDetails = { detailId = it }, onReusePrivate = { message ->
                draft = message.text
                sendError = null
            })
        val hasNewer = before != null
        val hasOlder = page?.hasOlder == true
        if (!privateMessages || hasOlder || hasNewer) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!privateMessages || hasNewer) TextButton(enabled = hasNewer, onClick = {
                    boundaries = boundaries.dropLast(1)
                    page = null
                    list.requestScrollToItem(0)
                }) { Text("Neuere") }
                if (!privateMessages || hasOlder) TextButton(enabled = hasOlder, onClick = {
                    page?.rows?.firstOrNull()?.let {
                        boundaries = boundaries + MessageCursor(it.timestampSeconds, it.sequence)
                        page = null
                        list.requestScrollToItem(0)
                    }
                }) { Text("Ältere") }
            }
        }
        if (privateMessages) {
            val optionsRevision by model.privateOptionsRevision.collectAsStateWithLifecycle()
            val retryOptions = remember(model, companion, privateKey, optionsRevision) {
                model.privateRetryOptions(companion, privateKey)
            }
            PrivateComposer(draft, { draft = it }, eligible, submitting, sendError, retryOptions, {
                model.savePrivateRetryOptions(companion, privateKey, it)
            }, onResetPath = {
                val session = eligible?.session
                if (session != null && !submitting) {
                    submitting = true
                    sendError = null
                    model.resetPrivatePath(session, companion, privateKey) { result ->
                        submitting = false
                        sendError = result
                    }
                }
            }) { text, options ->
                val session = eligible?.session ?: return@PrivateComposer
                if (submitting) return@PrivateComposer
                submitting = true
                sendError = null
                model.sendPrivate(session, companion, privateKey, text, options) { result ->
                    submitting = false
                    sendError = result
                    if (result == null && draft == text) {
                        draft = ""
                        boundaries = listOf(null)
                        list.requestScrollToItem(0)
                    }
                }
            }
        } else {
            GroupComposer(draft, { draft = it }, scope, { scope = it }, sender, submitting, sendError) { text, chosen ->
                val session = sender?.session ?: return@GroupComposer
                if (submitting) return@GroupComposer
                submitting = true
                sendError = null
                model.sendGroup(session, companion, channel.identity, text, chosen) { result ->
                    submitting = false
                    sendError = result
                    if (result == null && draft == text) {
                        draft = ""
                        boundaries = listOf(null)
                        list.requestScrollToItem(0)
                    }
                }
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
    followLatest: Boolean = true,
    onRead: (suspend (List<Long>) -> Unit)? = null,
    onOutgoingDetails: ((Long) -> Unit)? = null,
    onReusePrivate: ((ChannelMessage) -> Unit)? = null,
    onReply: ((String) -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    var displayed by remember { mutableStateOf(messages, referentialEqualityPolicy()) }
    var pendingLatest by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(messages, list, followLatest) {
        if (messages === displayed) return@LaunchedEffect
        // Conflate UI updates through a drag/fling. The journal reader continues to advance.
        snapshotFlow { list.isScrollInProgress }.first { !it }
        val lastSequence = displayed.maxOfOrNull { it.sequence }
        if (followLatest && lastSequence != null && messages.any { it.sequence > lastSequence }) {
            list.requestScrollToItem(0)
            pendingLatest = messages.last().sequence
        } else {
            val index = list.firstVisibleItemIndex
            val anchor = displayed.getOrNull(displayed.lastIndex - index)
            val position = anchor?.let { messages.binarySearch(it, channelMessageOrder) } ?: -1
            if (position >= 0 && messages.lastIndex - position != index) {
                // The lazy list's local key lookup can miss an anchor after a large insertion.
                list.requestScrollToItem(messages.lastIndex - position, list.firstVisibleItemScrollOffset)
            }
        }
        displayed = messages
    }
    LaunchedEffect(list, followLatest, pendingLatest) {
        val latest = pendingLatest ?: return@LaunchedEffect
        if (followLatest) {
            // Let the new rows and the keyboard-adjusted viewport reach the lazy list first.
            withFrameNanos { }
            snapshotFlow { list.isScrollInProgress }.first { !it }
            if (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0 ||
                list.layoutInfo.visibleItemsInfo.none { it.index == 0 && it.key == latest }
            ) list.scrollToItem(0)
        }
        pendingLatest = null
    }
    if (onRead != null) {
        val unreadIds = remember(displayed) { displayed.filter { it.unread }.map { it.sequence } }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle, unreadIds) {
            if (unreadIds.isEmpty()) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                onRead(unreadIds)
            }
        }
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(backLabel) }
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        header?.invoke()
        if (displayed.isEmpty()) {
            Text("Noch keine Nachrichten in dieser Unterhaltung.", Modifier.weight(1f))
        } else {
            val newestFirst = displayed.asReversed()
            val typography = MaterialTheme.typography
            val colors = MaterialTheme.colorScheme
            val shape = MaterialTheme.shapes.medium
            val outgoingBubble = Modifier.fillMaxWidth(0.88f).background(colors.primaryContainer, shape)
            val bubble = Modifier.fillMaxWidth(0.88f).background(colors.surfaceVariant, shape)
            val bubblePadding = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            val senderStyle = typography.titleMedium.copy(color = colors.onSurfaceVariant)
            val timeStyle = typography.labelMedium.copy(color = colors.onSurfaceVariant)
            val messageStyle = typography.bodyLarge.copy(color = colors.onSurfaceVariant)
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            ) {
                items(newestFirst, key = { it.sequence }) { message ->
                    val privateStatus = message.privateSend
                    if (privateStatus != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Column(outgoingBubble.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                ListRowText(message.text, messageStyle.copy(color = colors.onPrimaryContainer))
                                ListRowText(message.timestampLabel, timeStyle.copy(color = colors.onPrimaryContainer))
                                ListRowText(privateStatus.label, timeStyle.copy(color = colors.onPrimaryContainer))
                                message.privateProgressLabel?.let {
                                    ListRowText(it, timeStyle.copy(color = colors.onPrimaryContainer))
                                }
                                if (onReusePrivate != null && privateStatus != PrivateSendStatus.WAITING &&
                                    privateStatus != PrivateSendStatus.ACCEPTED
                                ) {
                                    TextButton(onClick = { onReusePrivate(message) }) { Text("Nochmal senden") }
                                }
                            }
                        }
                        return@items
                    }
                    val tx = message.outgoing
                    if (tx != null) {
                        val metadata = "${message.timestampLabel} · ${tx.repeats} Repeats"
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            MessageActions(outgoingBubble, message, onReply, onOutgoingDetails) {
                                Column(bubblePadding) {
                                    ListRowText(message.text, messageStyle.copy(color = colors.onPrimaryContainer))
                                    ListRowText(metadata, timeStyle.copy(color = colors.onPrimaryContainer))
                                    ListRowText(tx.status.label, timeStyle.copy(color = colors.onPrimaryContainer))
                                }
                            }
                        }
                        return@items
                    }
                    val metadata = "${message.timestampLabel} · ${message.receptionLabel}"
                    val announcement = remember(message) {
                        AnnotatedString("${message.senderLabel}\n$metadata\n${message.text}")
                    }
                    MessageActions(bubble, message, onReply) {
                        Column(bubblePadding.clearAndSetSemantics { text = announcement }) {
                            ListRowText(message.senderLabel, senderStyle)
                            ListRowText(metadata, timeStyle)
                            ListRowText(message.text, messageStyle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageActions(
    modifier: Modifier,
    message: ChannelMessage,
    onReply: ((String) -> Unit)?,
    onDetails: ((Long) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.combinedClickable(onClick = {}, onLongClickLabel = "Nachrichtenmenü",
        onLongClick = { expanded = true })) {
        content()
        if (expanded) {
            DropdownMenu(expanded = true, onDismissRequest = { expanded = false }) {
                if (onReply != null && message.outgoing == null) {
                    val nickname = message.sender?.takeIf { it.isNotBlank() }
                    DropdownMenuItem(text = { Text("Antworten") }, enabled = nickname != null, onClick = {
                        expanded = false
                        nickname?.let(onReply)
                    })
                }
                DropdownMenuItem(text = { Text("Nachricht kopieren") }, onClick = {
                    expanded = false
                    coroutineScope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Nachricht", message.text)))
                    }
                })
                if (onDetails != null) {
                    DropdownMenuItem(text = { Text("Sendedetails") }, onClick = {
                        expanded = false
                        onDetails(message.sequence)
                    })
                }
            }
        }
    }
}
