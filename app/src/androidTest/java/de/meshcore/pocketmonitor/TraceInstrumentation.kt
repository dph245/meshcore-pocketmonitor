package de.meshcore.pocketmonitor

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.database.sqlite.SQLiteDatabase
import de.meshcore.pocketmonitor.ble.CompanionConnection
import de.meshcore.pocketmonitor.ble.CompanionDevice
import de.meshcore.pocketmonitor.ble.CompanionScanner
import de.meshcore.pocketmonitor.protocol.HopResolver
import de.meshcore.pocketmonitor.protocol.TracePhase
import de.meshcore.pocketmonitor.protocol.TraceRadio
import de.meshcore.pocketmonitor.protocol.TraceRoute
import de.meshcore.pocketmonitor.protocol.TraceRun
import de.meshcore.pocketmonitor.protocol.TraceSeriesOptions
import de.meshcore.pocketmonitor.protocol.TraceSeriesStatus
import de.meshcore.pocketmonitor.rx.CompanionHistory
import de.meshcore.pocketmonitor.rx.HistoryDatabase
import de.meshcore.pocketmonitor.tcp.TcpEndpoint
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class TraceInstrumentation(private val context: Context) {
    private val identity = "3a".repeat(32)
    private val other = "4b".repeat(32)
    private val route = TraceRoute(2, listOf("abcd" + "0".repeat(60), "0123"), mirror = true)
    private val path = checkNotNull(route.path)
    private val options = TraceSeriesOptions(3, 1, 1)

    fun verifyPresetsAndMigration(directory: File) {
        val file = File(directory, "trace-history.db")
        HistoryDatabase(context, file.path).use { helper ->
            dropTraceTables(helper.writableDatabase)
            helper.writableDatabase.version = 10
        }
        val oldBackup = File(directory, "trace-v10.db").also { file.copyTo(it) }
        val presetId: Long
        val seriesId: Long
        HistoryDatabase(context, file.path).use { helper ->
            val history = CompanionHistory(helper)
            history.validateDatabase(oldBackup)
            history.restoreDatabase(oldBackup, "v10") {}
            val store = history.traceHistory
            presetId = store.savePreset(identity, null, "Strecke A", route)
            val duplicate = store.savePreset(identity, null, "Kopie", route)
            store.savePreset(identity, presetId, "Neue Strecke", route)
            store.savePreset(other, null, "Neue Strecke", route)
            store.deletePreset(other, duplicate)
            check(store.presets(identity).size == 2)
            store.deletePreset(identity, duplicate)
            val update = TraceRoute(1, listOf("CC", "AA", "BB", "DD"), mirror = false, turn = 2)
            store.savePreset(identity, presetId, "Neue Strecke", update)
            seriesId = store.beginSeries(identity, "Version beim Start", route, options, 17, TraceRadio())
            store.beginMeasurement(seriesId, 1, TraceRun("fixture", 1u, 2u, path, 18, HopResolver.EMPTY))
        }
        HistoryDatabase(context, file.path).use { helper ->
            val store = CompanionHistory(helper).traceHistory
            val preset = store.presets(identity).single()
            check(preset.id == presetId && preset.name == "Neue Strecke")
            check(preset.route.path?.hashes == listOf("CC", "AA", "BB", "DD") && preset.route.turn == 2)
            check(store.presets(other).single().route == route)
            val series = store.series(identity, Long.MAX_VALUE, 10).single()
            check(series.id == seriesId && series.route == route && series.status == TraceSeriesStatus.ABORTED)
            check(store.measurements(seriesId).single().phase == TracePhase.CANCELLED)
            check(store.series(other, Long.MAX_VALUE, 10).isEmpty())
        }
    }

    suspend fun verifySession() = coroutineScope {
        val name = "trace-test-${UUID.randomUUID()}.db"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("trace-fixture-$name", mode)
        }
        val sent = Channel<Long>(Channel.UNLIMITED)
        val allowCancelledConfirmation = CompletableDeferred<Unit>()
        val dropConnection = CompletableDeferred<Unit>()
        try {
            HistoryDatabase(context, name).use { helper ->
                val history = CompanionHistory(helper)
                val store = history.traceHistory
                val connection = CompanionConnection(isolated, history)
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 8_000
                    val fixture = launch(Dispatchers.IO) {
                        server.accept().use { socket ->
                            socket.soTimeout = 15_000
                            val input = DataInputStream(socket.getInputStream())
                            val output = socket.getOutputStream()
                            var traceCount = 0
                            var selfReads = 0
                            var late = byteArrayOf()
                            fun frame(payload: ByteArray) {
                                output.write(byteArrayOf(0x3e, payload.size.toByte(), 0) + payload)
                            }
                            while (true) {
                                val marker = input.read()
                                if (marker == -1) break
                                check(marker == 0x3c)
                                val size = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
                                val command = ByteArray(size).also(input::readFully)
                                when (command[0].toInt()) {
                                    1 -> frame(selfInfo(if (selfReads++ == 0) 8 else 7))
                                    4 -> {
                                        frame(byteArrayOf(2, 0, 0, 0, 0))
                                        frame(byteArrayOf(4, 0, 0, 0, 0))
                                    }
                                    0x16 -> frame(ByteArray(82).apply {
                                        this[0] = 13
                                        this[1] = 14
                                        "v1.fixture".encodeToByteArray().copyInto(this, 60)
                                    })
                                    0x0a -> frame(byteArrayOf(0x0a))
                                    0x14, 0x38 -> frame(byteArrayOf(15))
                                    0x28 -> frame(byteArrayOf(0x15))
                                    0x40 -> frame(byteArrayOf(0x1c))
                                    36 -> {
                                        check(command.copyOfRange(10, size).toHexString() == "abcd0123abcd")
                                        sent.send(SystemClock.elapsedRealtime())
                                        val reply = byteArrayOf(6, 0) + command.copyOfRange(1, 5) + ByteArray(4)
                                        val response = byteArrayOf(0x89.toByte(), 0, 6, command[9]) +
                                            command.copyOfRange(1, 9) + command.copyOfRange(10, size) + byteArrayOf(-8, 12, -20, 16)
                                        when (++traceCount) {
                                            1 -> { frame(response); frame(reply) }
                                            2 -> { late = response; frame(reply) }
                                            3 -> { frame(reply); frame(late); frame(response) }
                                            4 -> { allowCancelledConfirmation.await(); frame(reply); frame(response) }
                                            5 -> frame(byteArrayOf(1, 3))
                                            6 -> { frame(reply); dropConnection.await(); break }
                                            else -> error("Ungeplanter TRACE-Sendeversuch")
                                        }
                                    }
                                    else -> error("Unerwarteter Companion-Testbefehl ${command[0]}")
                                }
                            }
                        }
                    }
                    try {
                        connection.connect(checkNotNull(TcpEndpoint.parse("127.0.0.1", server.localPort))) {}
                        suspend fun start(label: String, config: TraceSeriesOptions) {
                            val ready = withTimeout(8_000) {
                                connection.status.first { it.traceSender?.unavailable == null && it.traceSender != null }
                            }
                            connection.startTraceSeries(checkNotNull(ready.traceSender).session, route, label, config)
                            withTimeout(8_000) { connection.traceSeries.first { it?.running == true } }
                        }
                        suspend fun finished(label: String, status: TraceSeriesStatus) = withTimeout(12_000) {
                            connection.traceSeries.first { it?.running == false }
                            store.series(identity, Long.MAX_VALUE, 1).single().also {
                                check(it.name == label && it.status == status) { "$label: $it" }
                            }
                        }

                        start("Wiederholungen", options)
                        val times = withTimeout(10_000) { List(3) { sent.receive() } }
                        val completed = finished("Wiederholungen", TraceSeriesStatus.COMPLETED)
                        check(times[1] - times[0] >= 950L && times[2] - times[1] >= 4_950L)
                        check(completed.radio == TraceRadio(869_525, 62_500, 7, 5, 22, "v1.fixture", 14))
                        val results = store.measurements(completed.id)
                        check(results.map { it.phase } == listOf(TracePhase.ANSWERED, TracePhase.TIMED_OUT, TracePhase.ANSWERED))
                        check(results.all { it.accepted })
                        check(results[1].snr.isEmpty() && results[1].responseMs == null)
                        check(results[2].snr == listOf(-2.0, 3.0, -5.0, 4.0) && results[2].responseMs != null)

                        start("Abbruch", options.copy(timeoutSeconds = 30))
                        withTimeout(3_000) { sent.receive() }
                        connection.cancelTraceSeries()
                        withTimeout(3_000) { connection.traceSeries.first { it?.message != null } }
                        allowCancelledConfirmation.complete(Unit)
                        val aborted = finished("Abbruch", TraceSeriesStatus.ABORTED)
                        check(store.measurements(aborted.id).single().let { it.phase == TracePhase.CANCELLED && it.accepted })

                        start("Ablehnung", options)
                        withTimeout(3_000) { sent.receive() }
                        val failed = finished("Ablehnung", TraceSeriesStatus.FAILED)
                        check(store.measurements(failed.id).single().let { it.phase == TracePhase.FAILED && !it.accepted })

                        start("Trennung", options.copy(timeoutSeconds = 30))
                        withTimeout(3_000) { sent.receive() }
                        withTimeout(3_000) { connection.traces.first { it.lastOrNull()?.phase == TracePhase.LISTENING } }
                        dropConnection.complete(Unit)
                        val disconnected = finished("Trennung", TraceSeriesStatus.DISCONNECTED)
                        check(store.measurements(disconnected.id).single().let { it.phase == TracePhase.DISCONNECTED && it.accepted })
                        check(store.measurements(completed.id) == results)
                        check(sent.tryReceive().isFailure)
                        connection.withDisconnectedHistory {}
                    } finally {
                        allowCancelledConfirmation.complete(Unit)
                        dropConnection.complete(Unit)
                        connection.disconnect()
                    }
                    fixture.join()
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    suspend fun verifyBleLoss() {
        val name = "trace-ble-${UUID.randomUUID()}.db"
        val scanner = CompanionScanner(context)
        val found = CompletableDeferred<CompanionDevice>()
        val peer = try {
            withContext(Dispatchers.Main) {
                scanner.start(
                    { if (it.name == "Trace Test") found.complete(it) },
                    { if (it != null) found.completeExceptionally(IllegalStateException(it)) },
                )
            }
            withTimeout(13_000) { found.await() }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { scanner.stop() }
        }
        try {
            HistoryDatabase(context, name).use { helper ->
                val history = CompanionHistory(helper)
                val connection = CompanionConnection(context, history)
                val stopped = CompletableDeferred<Unit>()
                try {
                    connection.connect(peer) { stopped.complete(Unit) }
                    val ready = withTimeout(20_000) {
                        connection.status.first { it.traceSender?.let { sender -> sender.unavailable == null } == true }
                    }
                    check(ready.contacts.journalName == identity) { "Die GATT-Fixture meldet eine unerwartete Identität." }
                    val session = checkNotNull(ready.traceSender).session
                    connection.startTraceSeries(session, route, "BLE-Abbruch", options.copy(timeoutSeconds = 10))
                    withTimeout(15_000) { connection.traceSeries.first { it?.running == false } }
                    val store = history.traceHistory
                    val series = store.series(identity, Long.MAX_VALUE, 1).single()
                    check(series.status == TraceSeriesStatus.DISCONNECTED)
                    val original = store.measurements(series.id)
                    check(original.map { it.phase } == listOf(TracePhase.ANSWERED, TracePhase.DISCONNECTED))
                    check(original.all { it.accepted })
                    withTimeout(20_000) {
                        connection.status.first {
                            it.traceSender?.let { sender -> sender.session != session && sender.unavailable == null } == true
                        }
                    }
                    delay(1_500)
                    check(store.measurements(series.id) == original)
                } finally {
                    connection.disconnect()
                    withTimeout(6_000) { stopped.await() }
                    connection.withDisconnectedHistory {}
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun selfInfo(sf: Int): ByteArray = ByteBuffer.allocate(62).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(5).put(1).put(22).put(22)
        put(identity.hexToByteArray()).putLong(0).putInt(0)
        putInt(869_525).putInt(62_500).put(sf.toByte()).put(5).put("Test".encodeToByteArray())
    }.array()
}

internal fun dropTraceTables(db: SQLiteDatabase) {
    db.execSQL("DROP TABLE trace_measurement")
    db.execSQL("DROP TABLE trace_series")
    db.execSQL("DROP TABLE trace_preset")
}
