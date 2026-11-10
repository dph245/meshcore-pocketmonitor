package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.CompanionEvent
import de.meshcore.pocketmonitor.protocol.CompanionEventDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionEventDecoderTest {
    @Test
    fun separatesSignedSignalBytesFromRadioBytes() {
        val source = "88E3970D0001020304".hexToByteArray()
        val original = source.copyOf()
        val event = CompanionEventDecoder.decode(source) as CompanionEvent.RadioRx
        assertEquals(-7.25, event.snrDb)
        assertEquals(-105, event.rssiDbm)
        assertArrayEquals("0D0001020304".hexToByteArray(), event.radioBytes)
        assertNull(event.issue)
        event.radioBytes!![0] = 0
        assertArrayEquals(original, source)

        val extremes = CompanionEventDecoder.decode("887F8001".hexToByteArray()) as CompanionEvent.RadioRx
        assertEquals(31.75, extremes.snrDb)
        assertEquals(-128, extremes.rssiDbm)
        val inverse = CompanionEventDecoder.decode("88807F01".hexToByteArray()) as CompanionEvent.RadioRx
        assertEquals(-32.0, inverse.snrDb)
        assertEquals(127, inverse.rssiDbm)
    }

    @Test
    fun preservesPartialMetadataAndDistinguishesOtherEvents() {
        val noMetadata = CompanionEventDecoder.decode("88".hexToByteArray()) as CompanionEvent.RadioRx
        assertNotNull(noMetadata.issue)
        assertNull(noMetadata.snrDb)
        assertNull(noMetadata.rssiDbm)
        assertNull(noMetadata.radioBytes)

        val noRssi = CompanionEventDecoder.decode("8801".hexToByteArray()) as CompanionEvent.RadioRx
        assertNotNull(noRssi.issue)
        assertEquals(0.25, noRssi.snrDb)
        assertNull(noRssi.rssiDbm)

        val noPacket = CompanionEventDecoder.decode("880197".hexToByteArray()) as CompanionEvent.RadioRx
        assertNotNull(noPacket.issue)
        assertEquals(-105, noPacket.rssiDbm)
        assertArrayEquals(byteArrayOf(), noPacket.radioBytes)
        assertEquals(CompanionEvent.Other(null), CompanionEventDecoder.decode(byteArrayOf()))
        assertEquals(CompanionEvent.Other(0xff), CompanionEventDecoder.decode("FF880197".hexToByteArray()))
    }
}
