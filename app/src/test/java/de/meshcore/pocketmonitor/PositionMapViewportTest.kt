package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.CompanionPosition
import de.meshcore.pocketmonitor.ui.PositionMapPoint
import de.meshcore.pocketmonitor.ui.PositionMapViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionMapViewportTest {
    @Test
    fun projectionPreservesSignedMicrodegrees() {
        val position = CompanionPosition(-33_868_820, 151_209_290)
        assertEquals(position, PositionMapPoint.fromPosition(position).toPosition())
    }

    @Test
    fun wrappedWorldStillPicksTheSameLongitude() {
        val position = CompanionPosition(52_520_008, 179_999_500)
        val point = PositionMapPoint.fromPosition(position)
        assertEquals(position, point.copy(x = point.x - 1).toPosition())
        assertNull(point.copy(y = -0.001).toPosition())
    }

    @Test
    fun pinchKeepsThePointUnderTheMovingAnchor() {
        val viewport = PositionMapViewport(0.54, 0.33, 12.0)
        val before = viewport.pointAt(80f, -40f, 256f).toPosition()
        val after = viewport.transform(80f, -40f, -10f, 15f, 1.2, 256f)
        assertEquals(before, after.pointAt(70f, -25f, 256f).toPosition())
    }
}
