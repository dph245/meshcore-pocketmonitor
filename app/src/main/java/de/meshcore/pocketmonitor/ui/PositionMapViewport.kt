package de.meshcore.pocketmonitor.ui

import de.meshcore.pocketmonitor.protocol.CompanionPosition
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

internal const val MAX_MAP_ZOOM = 19.0
internal const val MAX_MAP_LATITUDE = 85.0511287798066

// Coordinates in a Web Mercator world with width and height 1.
internal data class PositionMapPoint(val x: Double, val y: Double) {
    fun toPosition(): CompanionPosition? {
        if (y !in 0.0..1.0) return null
        val latitude = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))
        val longitude = wrapMapX(x) * 360 - 180
        return CompanionPosition.fromDegrees(latitude, longitude)
    }

    companion object {
        fun fromPosition(position: CompanionPosition): PositionMapPoint {
            val latitude = (position.latitudeE6 / 1e6).coerceIn(-MAX_MAP_LATITUDE, MAX_MAP_LATITUDE)
            val y = (1 - ln(tan(PI / 4 + Math.toRadians(latitude) / 2)) / PI) / 2
            return PositionMapPoint((position.longitudeE6 / 1e6 + 180) / 360, y.coerceIn(0.0, 1.0))
        }
    }
}

internal data class PositionMapViewport(val x: Double, val y: Double, val zoom: Double) {
    fun worldSize(tilePixels: Float): Double = tilePixels * 2.0.pow(zoom)

    fun pointAt(dx: Float, dy: Float, tilePixels: Float): PositionMapPoint {
        val size = worldSize(tilePixels)
        return PositionMapPoint(x + dx / size, y + dy / size)
    }

    fun transform(
        anchorX: Float,
        anchorY: Float,
        panX: Float,
        panY: Float,
        zoomDelta: Double,
        tilePixels: Float,
    ): PositionMapViewport {
        val anchor = pointAt(anchorX, anchorY, tilePixels)
        val newZoom = (zoom + zoomDelta).coerceIn(0.0, MAX_MAP_ZOOM)
        val newSize = tilePixels * 2.0.pow(newZoom)
        return PositionMapViewport(
            x = wrapMapX(anchor.x - (anchorX + panX) / newSize),
            y = (anchor.y - (anchorY + panY) / newSize).coerceIn(0.0, 1.0),
            zoom = newZoom,
        )
    }
}

private fun wrapMapX(x: Double): Double = x - kotlin.math.floor(x)
