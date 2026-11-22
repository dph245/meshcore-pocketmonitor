package de.meshcore.pocketmonitor.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.protocol.CompanionPosition
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun PositionMapPicker(
    initialPosition: CompanionPosition?,
    selected: CompanionPosition?,
    onSelect: (CompanionPosition) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val uriHandler = LocalUriHandler.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val tilePixels = maxOf(with(LocalDensity.current) { 256.dp.toPx() }, viewportSize.width.toFloat())
    var viewport by rememberSaveable(stateSaver = viewportSaver) {
        val center = initialPosition?.let(PositionMapPoint::fromPosition) ?: PositionMapPoint(0.5, 0.5)
        mutableStateOf(PositionMapViewport(center.x, center.y, if (initialPosition == null) 0.0 else 15.0))
    }
    var retry by remember { mutableIntStateOf(0) }
    val frame = remember(viewport, viewportSize, tilePixels) { MapFrame(viewport, viewportSize, tilePixels) }
    val tiles = frame.placements.map { it.tile }.distinct().associateWith { tile ->
        key(tile) {
            val result by produceState<Result<ImageBitmap>?>(null, retry) {
                if (value?.isSuccess == true) return@produceState
                value = null
                delay(120)
                value = try {
                    Result.success(OpenStreetMapTiles.load(context, tile))
                } catch (error: IOException) {
                    Result.failure(error)
                }
            }
            result
        }
    }
    val currentTiles by rememberUpdatedState(tiles)
    val select by rememberUpdatedState(onSelect)

    Box(
        Modifier.fillMaxWidth().height(240.dp).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Canvas(
            Modifier.fillMaxSize().onSizeChanged { viewportSize = it }
                .semantics { contentDescription = "OpenStreetMap-Karte" }
                .pointerInput(tilePixels) {
                    detectTapGestures { tap ->
                        val dx = tap.x - size.width / 2f
                        val dy = tap.y - size.height / 2f
                        val point = viewport.pointAt(dx, dy, tilePixels)
                        val tile = MapTile.at(point, viewport.zoom.toInt())
                        if (currentTiles[tile]?.isSuccess == true) point.toPosition()?.let(select)
                    }
                }
                .pointerInput(tilePixels) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        viewport = viewport.transform(
                            centroid.x - size.width / 2f, centroid.y - size.height / 2f,
                            pan.x, pan.y, log2(zoom.toDouble()), tilePixels,
                        )
                    }
                },
        ) {
            for (placement in frame.placements) {
                val image = tiles[placement.tile]?.getOrNull() ?: continue
                val left = placement.left.roundToInt()
                val top = placement.top.roundToInt()
                drawImage(
                    image,
                    dstOffset = IntOffset(left, top),
                    dstSize = IntSize(
                        (placement.left + frame.tileSize).roundToInt() - left,
                        (placement.top + frame.tileSize).roundToInt() - top,
                    ),
                )
            }
            if (selected != null && abs(selected.latitudeE6 / 1e6) <= MAX_MAP_LATITUDE) {
                val tip = frame.offsetOf(PositionMapPoint.fromPosition(selected))
                val radius = 9.dp.toPx()
                val head = tip - Offset(0f, radius * 1.7f)
                val tail = Path().apply {
                    moveTo(head.x - radius * 0.8f, head.y + radius * 0.5f)
                    lineTo(tip.x, tip.y)
                    lineTo(head.x + radius * 0.8f, head.y + radius * 0.5f)
                    close()
                }
                val red = Color(0xFFC52437)
                drawPath(tail, red)
                drawCircle(Color.White, radius, head)
                drawCircle(red, radius * 0.77f, head)
                drawCircle(Color.White, radius * 0.28f, head)
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            MapZoomButton("+", "Vergrößern", viewport.zoom < MAX_MAP_ZOOM) {
                viewport = viewport.transform(0f, 0f, 0f, 0f, 1.0, tilePixels)
            }
            MapZoomButton("−", "Verkleinern", viewport.zoom > 0.0) {
                viewport = viewport.transform(0f, 0f, 0f, 0f, -1.0, tilePixels)
            }
        }
        if (tiles.values.any { it?.isFailure == true }) {
            TextButton(
                onClick = { retry++ },
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 32.dp)
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small),
            ) { Text("Karte neu laden") }
        } else if (tiles.isNotEmpty() && tiles.values.all { it == null }) {
            Text("Karte wird geladen …", modifier = Modifier.align(Alignment.Center))
        }
        Text(
            "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd)
                .background(MaterialTheme.colorScheme.surface)
                .clickable { uriHandler.openUri("https://www.openstreetmap.org/copyright") }
                .padding(horizontal = 5.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun MapZoomButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = label },
    ) { Text(symbol, style = MaterialTheme.typography.titleLarge) }
}

private val viewportSaver = listSaver<PositionMapViewport, Double>(
    save = { listOf(it.x, it.y, it.zoom) },
    restore = { PositionMapViewport(it[0], it[1], it[2]) },
)

private data class MapTile(val zoom: Int, val x: Int, val y: Int) {
    companion object {
        fun at(point: PositionMapPoint, zoom: Int): MapTile {
            val count = 1 shl zoom
            return wrapped(zoom, floor(point.x * count).toInt(), floor(point.y * count).toInt())
        }

        fun wrapped(zoom: Int, x: Int, y: Int): MapTile = MapTile(zoom, Math.floorMod(x, 1 shl zoom), y)
    }
}

private data class TilePlacement(val tile: MapTile, val left: Double, val top: Double)

private class MapFrame(viewport: PositionMapViewport, size: IntSize, tilePixels: Float) {
    private val zoom = viewport.zoom.toInt()
    private val count = 1 shl zoom
    private val worldSize = viewport.worldSize(tilePixels)
    val tileSize = worldSize / count
    private val left = viewport.x * worldSize - size.width / 2.0
    private val top = viewport.y * worldSize - size.height / 2.0
    private val centerX = viewport.x
    val placements = buildList {
        if (size.width == 0 || size.height == 0) return@buildList
        val firstX = floor(left / tileSize).toInt()
        val endX = ceil((left + size.width) / tileSize).toInt()
        val firstY = floor(top / tileSize).toInt().coerceAtLeast(0)
        val endY = ceil((top + size.height) / tileSize).toInt().coerceAtMost(count)
        for (y in firstY until endY) {
            for (x in firstX until endX) {
                add(TilePlacement(MapTile.wrapped(zoom, x, y), x * tileSize - left, y * tileSize - top))
            }
        }
    }

    fun offsetOf(point: PositionMapPoint): Offset {
        val nearestX = point.x + (centerX - point.x).roundToInt()
        return Offset((nearestX * worldSize - left).toFloat(), (point.y * worldSize - top).toFloat())
    }
}

private object OpenStreetMapTiles {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(4)

    suspend fun load(context: Context, tile: MapTile): ImageBitmap = withContext(dispatcher) {
        ensureCache(context)
        val connection = URL("https://tile.openstreetmap.org/${tile.zoom}/${tile.x}/${tile.y}.png")
            .openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty(
                "User-Agent", "MeshCore-PocketMonitor (+https://github.com/dph245/meshcore-pocketmonitor)",
            )
            connection.inputStream.use { stream ->
                // Read to EOF so HttpResponseCache can commit the full response.
                val bytes = stream.readBytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    ?: throw IOException("Invalid map tile")
            }
        } finally {
            connection.disconnect()
        }
    }

    @Synchronized
    private fun ensureCache(context: Context) {
        if (HttpResponseCache.getInstalled() == null) {
            HttpResponseCache.install(context.cacheDir.resolve("osm-tiles"), 32L * 1024 * 1024)
        }
    }
}
