package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.rx.HistoryChannel
import de.meshcore.pocketmonitor.ui.ChannelPreview
import de.meshcore.pocketmonitor.ui.ConversationOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationOrderTest {
    @Test
    fun unreadFirstInBothOrders() {
        val previews = listOf(
            preview("Ada", 2), preview("Berta", 7), preview("Clara", null),
            preview("Dora", 10), preview("Emil", 15),
        )
        val unread = setOf("Berta", "Dora")
        assertEquals(listOf("Berta", "Dora", "Ada", "Clara", "Emil"),
            previews.sortedWith(ConversationOrder.ALPHABETICAL.comparator(unread)).map { it.channel.name })
        assertEquals(listOf("Dora", "Berta", "Emil", "Ada", "Clara"),
            previews.sortedWith(ConversationOrder.LAST_INCOMING.comparator(unread)).map { it.channel.name })
    }

    private fun preview(name: String, receipt: Long?) =
        ChannelPreview(HistoryChannel(name, name, null, receipt), null)
}
