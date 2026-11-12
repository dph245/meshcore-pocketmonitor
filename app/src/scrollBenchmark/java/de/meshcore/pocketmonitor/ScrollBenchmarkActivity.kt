package de.meshcore.pocketmonitor

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.rx.JournalSnapshot
import de.meshcore.pocketmonitor.rx.RxFrame
import de.meshcore.pocketmonitor.rx.RxRow
import de.meshcore.pocketmonitor.ui.ChannelConversation
import de.meshcore.pocketmonitor.ui.ChannelMessage
import de.meshcore.pocketmonitor.ui.LiveRows
import de.meshcore.pocketmonitor.ui.LiveSnapshot
import de.meshcore.pocketmonitor.ui.channelClock
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class ScrollBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val count = intent.getIntExtra("rows", 500).coerceIn(50, 50_000)
        val verifyAnchor = intent.getBooleanExtra("verifyAnchor", false)
        val chat = intent.getBooleanExtra("chat", false)
        val time = 1_700_000_000L
        val clock = channelClock()
        val messages = if (chat) List(count) { index ->
            message(index, time, clock.format(Instant.ofEpochSecond(time + index)))
        } else emptyList()
        val burst = if (chat && verifyAnchor) List(250) { offset ->
            val index = count + offset
            message(index, time, clock.format(Instant.ofEpochSecond(time + index)))
        } else emptyList()
        val snapshot = if (chat) null else {
            val frame = "8800b0150011ac636130151a26a9a6710a2cad0bb9a5b9d8b073aa850126e87c47509a61a47bf107".hexToByteArray()
            check(frame.first() == CompanionProtocol.RF_LOG.toByte())
            val rows = List(minOf(500, count)) { index ->
                RxRow(count - index.toLong(), RxFrame((time - index) * 1000, frame))
            }
            LiveSnapshot.update(JournalSnapshot("fixture", count.toLong(), rows, rows, null), null)
        }
        setContent {
            MaterialTheme {
                val modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                if (snapshot == null) {
                    val list = rememberLazyListState()
                    var displayed by remember { mutableStateOf(messages) }
                    ChannelConversation("Public", displayed, {}, modifier, list)
                    LaunchedEffect(verifyAnchor) {
                        if (!verifyAnchor) return@LaunchedEffect
                        val index = 30
                        val offset = 20
                        list.scrollToItem(index, offset)
                        val key = list.layoutInfo.visibleItemsInfo.first { it.index == index }.key
                        list.scroll {
                            displayed = messages + burst
                            delay(250)
                            check(list.layoutInfo.totalItemsCount == messages.size)
                        }
                        withTimeout(5_000) {
                            snapshotFlow { list.layoutInfo.totalItemsCount }.first { it == displayed.size }
                        }
                        check(list.firstVisibleItemIndex == index + burst.size)
                        check(list.firstVisibleItemScrollOffset == offset)
                        check(list.layoutInfo.visibleItemsInfo.first { it.index == list.firstVisibleItemIndex }.key == key)
                        Log.i("PocketScrollBenchmark", "250 appended messages kept the same anchor and offset")
                    }
                } else {
                    Box(modifier) {
                        LiveRows(snapshot, false, rememberLazyListState()) {}
                    }
                }
            }
        }
    }

    private fun message(index: Int, time: Long, timestamp: String) = ChannelMessage(
        index + 1L, "Station ${index % 40}", time + index,
        "Nachricht ${index + 1}: Test der Darstellung bei längeren MeshCore-Nachrichten.", timestamp,
    )
}
