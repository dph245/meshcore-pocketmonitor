package de.meshcore.pocketmonitor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.ChannelQuery
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.CompanionProtocol.Command
import de.meshcore.pocketmonitor.protocol.ContactListTransfer
import de.meshcore.pocketmonitor.protocol.DiscoverCommandReply
import de.meshcore.pocketmonitor.protocol.DiscoverReply
import de.meshcore.pocketmonitor.protocol.DiscoverSearches
import de.meshcore.pocketmonitor.protocol.FloodScope
import de.meshcore.pocketmonitor.protocol.PendingReply
import de.meshcore.pocketmonitor.protocol.ChannelSlot
import de.meshcore.pocketmonitor.protocol.TelemetryMetric
import de.meshcore.pocketmonitor.protocol.TelemetryQuery
import de.meshcore.pocketmonitor.rx.RxFrame
import de.meshcore.pocketmonitor.rx.RxJournal
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ConnectionStatus(
    val message: String = "Getrennt",
    val active: Boolean = false,
    val error: Boolean = false,
    val contacts: ContactStatus = ContactStatus(),
    val companionReadBusy: Boolean = false,
    val channelError: String? = null,
    val batteryMv: Int? = null,
    val noiseFloorDbm: Int? = null,
    val discoverUnavailable: String? = "Zuerst einen Companion verbinden.",
)

internal class ChannelCatalog(
    val journalName: String,
    val slots: List<ChannelSlot>,
    scope: FloodScope?,
) {
    val liveDecoding = ChannelDecoding(slots.mapNotNull { it.channel.forLiveDiagnostics() }, scope)
}

@SuppressLint("MissingPermission") // Runtime permissions come from the activity; revocation is handled below.
internal class CompanionConnection(private val context: Context) {
    private val thread = HandlerThread("Companion-GATT").apply { start() }
    private val handler = Handler(thread.looper)
    private val mutableStatus = MutableStateFlow(ConnectionStatus())
    val status = mutableStatus.asStateFlow()
    private val searches = DiscoverSearches()
    val discover = searches.history

    @Volatile
    var latestJournal: RxJournal? = null
        private set

    @Volatile
    var channelCatalog: ChannelCatalog? = null
        private set

    fun channelDecoding(journalName: String): ChannelDecoding =
        channelCatalog?.takeIf { it.journalName == journalName }?.liveDecoding ?: ChannelDecoding.default

    private var current: Session? = null

    fun connect(device: CompanionDevice) {
        handler.post {
            if (current == null) {
                val session = Session(device)
                current = session
                channelCatalog = null
                latestJournal = session.journal
                session.start()
            }
        }
    }

    fun disconnect() {
        handler.post { current?.finish("Getrennt") }
    }

    fun refreshContacts() {
        handler.post { current?.refreshContacts() }
    }

    fun refreshChannels() {
        handler.post { current?.refreshChannels() }
    }

    fun sendDiscover() {
        handler.post { current?.sendDiscover() }
    }

    fun close() {
        handler.post {
            current?.finish("Getrennt")
            thread.quitSafely()
        }
    }

    // connectGatt delivers all callbacks on this same handler.
    private inner class Session(private val device: CompanionDevice) : BluetoothGattCallback() {
        val journal = RxJournal(File(context.filesDir, "rx")) { message ->
            handler.post { if (current === this) finish(message, error = true) }
        }
        private val journalName = journal.file.name
        private var gatt: BluetoothGatt? = null
        private lateinit var rx: BluetoothGattCharacteristic
        private var mtu = 23
        private var pending: PendingReply? = null
        private var contactTransfer: ContactListTransfer? = null
        private var firmwareVersion: Int? = null
        private var channelSlots = 0
        private var nextSlot = 0
        private var scopeQueried = false
        private var initialMetadataPending = true
        private var channelQuery: ChannelQuery? = null
        private val configuredSlots = mutableListOf<ChannelSlot>()
        private var channelReadFailed = false
        private var defaultScope: FloodScope? = null
        private var telemetryQuery: TelemetryQuery? = null
        private val unavailableMetrics = mutableSetOf<TelemetryMetric>()
        private val pollTelemetry = Runnable { pollTelemetryWhenIdle() }
        private var discoverReply: DiscoverCommandReply? = null
        private var discoverDisabled: String? = null
        private var commandChannelUncertain = false
        private var timeout: Runnable? = null

        fun start() = guarded {
            waitFor("Verbinde mit ${device.name} …", 20_000)
            gatt = device.device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK, handler)
            if (gatt == null) finish("GATT-Verbindung konnte nicht gestartet werden.", error = true)
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = guarded {
            when {
                status != BluetoothGatt.GATT_SUCCESS ->
                    finish("BLE-Verbindung beendet (GATT $status).", error = true)
                newState == BluetoothProfile.STATE_DISCONNECTED ->
                    finish("Companion hat die Verbindung getrennt.")
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    waitFor("Handle BLE-MTU aus …", 10_000)
                    if (!gatt.requestMtu(CompanionProtocol.REQUESTED_MTU)) {
                        finish("BLE-MTU-Anfrage konnte nicht gestartet werden.", error = true)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = guarded {
            if (status != BluetoothGatt.GATT_SUCCESS || mtu <= 23) {
                finish("Keine für Companion-Frames geeignete BLE-MTU (MTU $mtu, GATT $status).", error = true)
                return@guarded
            }
            this.mtu = mtu
            waitFor("Suche Companion-GATT-Dienst …", 15_000)
            if (!gatt.discoverServices()) finish("GATT-Dienstsuche konnte nicht gestartet werden.", error = true)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = guarded {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish("GATT-Dienstsuche fehlgeschlagen (GATT $status).", error = true)
                return@guarded
            }
            val service = gatt.getService(CompanionProtocol.service)
            val write = service?.getCharacteristic(CompanionProtocol.radioRx)
            val notify = service?.getCharacteristic(CompanionProtocol.radioTx)
            val cccd = notify?.getDescriptor(CompanionProtocol.cccd)
            if (write == null || notify == null || cccd == null ||
                write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ||
                notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
            ) {
                finish("Companion-GATT-Dienst oder benötigte Merkmale fehlen.", error = true)
                return@guarded
            }
            rx = write
            waitFor("Aktiviere Notifications · ggf. BLE-PIN bestätigen …", 60_000)
            if (!gatt.setCharacteristicNotification(notify, true) || !enableNotifications(gatt, cccd)) {
                finish("Companion-Notifications konnten nicht aktiviert werden.", error = true)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            guarded {
                if (descriptor.uuid != CompanionProtocol.cccd ||
                    descriptor.characteristic.uuid != CompanionProtocol.radioTx) return@guarded
                if (status == BluetoothGatt.GATT_SUCCESS) send(gatt, Command.APP_START)
                else finish("Notification-Abo fehlgeschlagen (GATT $status).", error = true)
            }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int,
        ) = guarded {
            if (characteristic.uuid != CompanionProtocol.radioRx) return@guarded
            channelQuery?.let { query ->
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    query.acknowledgeWrite()
                    channelProgress(gatt, query)
                } else {
                    finishMetadata(uncertain = true)
                }
                return@guarded
            }
            telemetryQuery?.let { query ->
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    query.acknowledgeWrite()
                    telemetryProgress(gatt, query)
                } else {
                    failTelemetry(query)
                }
                return@guarded
            }
            discoverReply?.let { reply ->
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    reply.acknowledgeWrite()
                    discoverProgress(reply)
                } else {
                    failDiscover("BLE-Schreibbestätigung fehlgeschlagen (GATT $status).", uncertain = true)
                }
                return@guarded
            }
            val transfer = contactTransfer
            if (transfer != null) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    transfer.acknowledgeWrite()
                    contactProgress(transfer)
                } else {
                    stopContactTransfer("Kontaktabfrage fehlgeschlagen (GATT $status). Erneut verbinden.")
                }
                return@guarded
            }
            val transaction = pending ?: return@guarded
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish("${transaction.command} fehlgeschlagen (GATT $status).", error = true)
            } else if (transaction.acknowledgeWrite()) {
                next(gatt, transaction.command)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) = receive(gatt, characteristic, value)

        @Deprecated("Android 8 bis 12")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) characteristic.value?.let { receive(gatt, characteristic, it) }
        }

        private fun receive(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            guarded {
                if (characteristic.uuid != CompanionProtocol.radioTx ||
                    characteristic.service.uuid != CompanionProtocol.service) return@guarded
                val elapsedMs = SystemClock.elapsedRealtime()
                val frame = RxFrame(System.currentTimeMillis(), value.copyOf())
                if (!journal.offer(frame)) {
                    finish("RX-Journal konnte ein Ereignis nicht übernehmen. Empfang beendet.", error = true)
                    return@guarded
                }
                searches.receive(journalName, frame, elapsedMs)
                channelQuery?.let { query ->
                    query.receive(frame.bytes)
                    channelProgress(gatt, query)
                    return@guarded
                }
                telemetryQuery?.let { query ->
                    query.receive(frame.bytes)
                    telemetryProgress(gatt, query)
                    return@guarded
                }
                discoverReply?.let { reply ->
                    reply.receive(frame.bytes)
                    discoverProgress(reply)
                }
                contactTransfer?.let { transfer ->
                    if (transfer.receive(frame.bytes)) contactProgress(transfer)
                }
                val transaction = pending ?: return@guarded
                if (transaction.command == Command.DEVICE_QUERY && frame.code == Command.DEVICE_QUERY.expectedReply) {
                    firmwareVersion = frame.bytes.getOrNull(1)?.toInt()?.and(0xff)
                    if ((firmwareVersion ?: 0) >= 3 && mtu >= ChannelQuery.CHANNEL_MTU) {
                        channelSlots = frame.bytes.getOrNull(3)?.toInt()?.and(0xff) ?: 0
                    }
                }
                if (frame.code == CompanionProtocol.ERROR) {
                    val code = frame.bytes.getOrNull(1)?.toInt()?.and(0xff)
                    finish("Companion meldet Fehler bei ${transaction.command} (Code ${code ?: "unbekannt"}).", error = true)
                } else if (transaction.receive(frame.code)) {
                    next(gatt, transaction.command)
                }
            }

        private fun next(gatt: BluetoothGatt, command: Command) {
            when (command) {
                Command.APP_START -> send(gatt, Command.DEVICE_QUERY)
                Command.DEVICE_QUERY -> {
                    pending = null
                    clearTimeout()
                    val limit = if (mtu < CompanionProtocol.FULL_FRAME_MTU) " · lange Frames ggf. unvollständig" else ""
                    val canLoad = mtu >= CompanionProtocol.CONTACT_FRAME_MTU
                    mutableStatus.value = ConnectionStatus(
                        "Verbunden mit ${device.name} · MTU $mtu$limit", active = true,
                        contacts = ContactStatus(
                            journalName = journalName,
                            canRefresh = canLoad,
                            error = if (canLoad) null else "Kontaktliste benötigt BLE-MTU ≥ 151 (aktuell $mtu).",
                        ),
                        discoverUnavailable = discoverUnavailable(),
                    )
                    refreshContacts()
                    startInitialMetadata()
                }
            }
        }

        fun refreshContacts() = guarded {
            val contacts = mutableStatus.value.contacts
            val connection = gatt ?: return@guarded
            if (!contacts.canRefresh || channelQuery != null || telemetryQuery != null ||
                searches.history.value.busy || commandChannelUncertain) {
                return@guarded
            }
            val transfer = ContactListTransfer()
            contactTransfer = transfer
            publishContacts(contacts.copy(canRefresh = false, loading = true, error = null))
            if (write(connection, byteArrayOf(CompanionProtocol.GET_CONTACTS.toByte()))) {
                contactProgress(transfer)
            } else {
                contactTransfer = null
                publishContacts(contacts.copy(error = "Kontaktabfrage konnte nicht gestartet werden."))
            }
        }

        private fun contactProgress(transfer: ContactListTransfer) {
            clearTimeout()
            val result = transfer.result
            if (result != null) {
                contactTransfer = null
                val previous = mutableStatus.value.contacts
                val contacts = previous.completed(result)
                if (contacts.book != previous.book) {
                    searches.updateResolver(journalName, contacts.resolverFor(journalName))
                }
                publishContacts(contacts)
                startInitialMetadata()
            } else {
                // There is no request ID: after an idle timeout a late list could overlap a retry.
                timeout = Runnable {
                    stopContactTransfer("Zeitüberschreitung bei der Kontaktabfrage. Erneut verbinden.")
                }.also { handler.postDelayed(it, 10_000) }
            }
        }

        private fun stopContactTransfer(message: String) {
            clearTimeout()
            contactTransfer = null
            commandChannelUncertain = true
            if (initialMetadataPending) {
                mutableStatus.value = mutableStatus.value.copy(channelError = "Kanalabfrage nicht möglich. Companion erneut verbinden.")
            }
            publishContacts(mutableStatus.value.contacts.copy(canRefresh = false, loading = false, error = message))
        }

        private fun publishContacts(contacts: ContactStatus) {
            mutableStatus.value = mutableStatus.value.copy(
                contacts = contacts,
                companionReadBusy = channelQuery != null || telemetryQuery != null,
                discoverUnavailable = discoverUnavailable(),
            )
        }

        private fun startInitialMetadata() {
            val connection = gatt ?: return
            if (!initialMetadataPending || contactTransfer != null || commandChannelUncertain) return
            initialMetadataPending = false
            nextMetadataQuery(connection)
        }

        fun refreshChannels() = guarded {
            val connection = gatt ?: return@guarded
            if (initialMetadataPending || channelSlots == 0 || commandChannelUncertain ||
                channelQuery != null || telemetryQuery != null || contactTransfer != null ||
                searches.history.value.busy
            ) return@guarded
            configuredSlots.clear()
            nextSlot = 0
            channelReadFailed = false
            nextMetadataQuery(connection)
        }

        private fun nextMetadataQuery(connection: BluetoothGatt) {
            val query = when {
                nextSlot < channelSlots -> ChannelQuery(nextSlot++)
                !scopeQueried && mtu >= ChannelQuery.SCOPE_MTU &&
                    (firmwareVersion ?: 0) >= ChannelQuery.SCOPE_MIN_VERSION ->
                    ChannelQuery().also { scopeQueried = true }
                else -> {
                    finishMetadata()
                    return
                }
            }
            channelQuery = query
            publishContacts(mutableStatus.value.contacts)
            if (!write(connection, query.command)) {
                channelReadFailed = true
                finishMetadata()
                return
            }
            timeout = Runnable { finishMetadata(uncertain = true) }
                .also { handler.postDelayed(it, 5_000) }
        }

        private fun channelProgress(connection: BluetoothGatt, query: ChannelQuery) {
            val frame = query.result ?: return
            clearTimeout()
            ChannelQuery.configuredSlot(frame)?.let(configuredSlots::add)
            ChannelQuery.defaultScope(frame)?.let { defaultScope = it }
            if (query.slotReadFailed(frame)) channelReadFailed = true
            nextMetadataQuery(connection)
        }

        private fun finishMetadata(uncertain: Boolean = false) {
            clearTimeout()
            channelQuery = null
            val failed = channelReadFailed || uncertain
            val previous = channelCatalog
            val slots = configuredSlots.toList()
            if (previous == null || (!failed && slots.map { it.signature() } != previous.slots.map { it.signature() })) {
                channelCatalog = ChannelCatalog(journalName, slots, defaultScope)
            }
            mutableStatus.value = mutableStatus.value.copy(channelError = when {
                uncertain -> "Kanalabfrage unterbrochen. Companion erneut verbinden."
                failed -> "Kanalliste konnte nicht vollständig gelesen werden."
                channelSlots == 0 -> "Dieser Companion stellt keine Kanal-Slots bereit."
                else -> null
            })
            if (uncertain) commandChannelUncertain = true
            val contacts = mutableStatus.value.contacts
            publishContacts(if (uncertain) contacts.copy(
                canRefresh = false, error = "Companion-Befehlsstatus unklar. Erneut verbinden.",
            ) else contacts)
            if (!uncertain && previous == null) handler.post(pollTelemetry)
        }

        private fun pollTelemetryWhenIdle(): Unit = guarded {
            val connection = gatt ?: return@guarded
            if (commandChannelUncertain) return@guarded
            if (contactTransfer != null || channelQuery != null || searches.history.value.busy) {
                handler.postDelayed(pollTelemetry, 1_000)
                return@guarded
            }
            nextTelemetryQuery(connection)
        }

        private fun nextTelemetryQuery(connection: BluetoothGatt, after: TelemetryMetric? = null) {
            val metrics = TelemetryMetric.entries.filter {
                it !in unavailableMetrics && (firmwareVersion ?: 0) >= it.minimumVersion
            }
            val metric = metrics.firstOrNull { after == null || it.ordinal > after.ordinal }
            if (metric == null) {
                telemetryQuery = null
                publishContacts(mutableStatus.value.contacts)
                if (metrics.isNotEmpty()) handler.postDelayed(pollTelemetry, 30_000)
                return
            }

            val query = TelemetryQuery(metric)
            telemetryQuery = query
            publishContacts(mutableStatus.value.contacts)
            if (write(connection, query.command)) {
                timeout = Runnable { failTelemetry(query) }.also { handler.postDelayed(it, 5_000) }
            } else {
                // The write never started. Retry at the next regular poll.
                publishTelemetry(query.metric, null)
                nextTelemetryQuery(connection, query.metric)
            }
        }

        private fun telemetryProgress(connection: BluetoothGatt, query: TelemetryQuery) {
            val frame = query.result ?: return
            clearTimeout()
            val code = frame[0].toInt() and 0xff
            if (code == CompanionProtocol.DISABLED ||
                (code == CompanionProtocol.ERROR && frame.getOrNull(1) == 1.toByte())
            ) {
                unavailableMetrics += query.metric
            }
            publishTelemetry(query.metric, query.value(frame))
            nextTelemetryQuery(connection, query.metric)
        }

        private fun publishTelemetry(metric: TelemetryMetric, value: Int?) {
            val state = mutableStatus.value
            mutableStatus.value = when (metric) {
                TelemetryMetric.BATTERY -> state.copy(batteryMv = value)
                TelemetryMetric.NOISE_FLOOR -> state.copy(noiseFloorDbm = value)
            }
        }

        private fun failTelemetry(query: TelemetryQuery) {
            clearTimeout()
            telemetryQuery = null
            commandChannelUncertain = true
            publishTelemetry(query.metric, null)
            publishContacts(mutableStatus.value.contacts.copy(
                canRefresh = false, error = "Companion-Befehlsstatus unklar. Erneut verbinden.",
            ))
        }

        private fun discoverUnavailable(): String? = when {
            commandChannelUncertain -> "Companion-Befehlsstatus unklar. Für DISCOVER erneut verbinden."
            discoverDisabled != null -> discoverDisabled
            firmwareVersion == null -> "Companion-Protokollversion für DISCOVER fehlt."
            (firmwareVersion ?: 0) < CompanionProtocol.DISCOVER_MIN_VERSION ->
                "DISCOVER benötigt Companion-Protokollversion 8 oder höher (aktuell $firmwareVersion)."
            mtu < CompanionProtocol.DISCOVER_MIN_MTU ->
                "DISCOVER benötigt BLE-MTU ≥ ${CompanionProtocol.DISCOVER_MIN_MTU} (aktuell $mtu)."
            contactTransfer != null -> "Warte auf die Kontaktliste …"
            channelQuery != null -> "Warte auf Kanal- und Scope-Daten …"
            telemetryQuery != null -> "Warte auf Akku- und Funkwerte …"
            else -> null
        }

        fun sendDiscover() = guarded {
            val connection = gatt ?: return@guarded
            val contacts = mutableStatus.value.contacts
            if (contacts.journalName == null || discoverUnavailable() != null) return@guarded
            val run = searches.start(
                journalName, System.currentTimeMillis(), SystemClock.elapsedRealtime(), contacts.resolverFor(journalName),
            ) ?: return@guarded
            handler.postDelayed(
                { searches.endCooldown(SystemClock.elapsedRealtime()) }, DiscoverSearches.COOLDOWN_MS,
            )
            discoverReply = DiscoverCommandReply()
            timeout = Runnable {
                failDiscover("Zeitüberschreitung bei der Companion-Bestätigung.", uncertain = true)
            }.also { handler.postDelayed(it, DiscoverSearches.REPLY_TIMEOUT_MS) }
            if (!write(connection, CompanionProtocol.encodeDiscover(run.tag))) {
                failDiscover("BLE-Schreibvorgang konnte nicht gestartet werden.")
            }
        }

        private fun discoverProgress(transaction: DiscoverCommandReply) {
            when (val result = transaction.result) {
                DiscoverReply.Accepted -> {
                    clearTimeout()
                    discoverReply = null
                    searches.accept(SystemClock.elapsedRealtime())
                    timeout = Runnable {
                        clearTimeout()
                        searches.complete()
                    }.also { handler.postDelayed(it, DiscoverSearches.LISTEN_MS) }
                }
                is DiscoverReply.Rejected -> {
                    if (result.unsupported) discoverDisabled = result.message
                    failDiscover(result.message)
                }
                DiscoverReply.Malformed -> failDiscover("Ungültige Companion-Antwort.", uncertain = true)
                null -> Unit
            }
        }

        private fun failDiscover(message: String, uncertain: Boolean = false) {
            clearTimeout()
            discoverReply = null
            if (uncertain) commandChannelUncertain = true
            val contacts = mutableStatus.value.contacts
            publishContacts(if (uncertain) contacts.copy(
                canRefresh = false, error = "Companion-Befehlsstatus unklar. Erneut verbinden.",
            ) else contacts)
            searches.fail(message)
        }

        private fun send(gatt: BluetoothGatt, command: Command) {
            pending = PendingReply(command)
            val pairing = if (command == Command.APP_START) " · ggf. BLE-PIN bestätigen" else ""
            waitFor("$command$pairing …", if (command == Command.APP_START) 60_000 else 10_000)
            if (!write(gatt, command.encode())) finish("$command konnte nicht gesendet werden.", error = true)
        }

        @Suppress("DEPRECATION")
        private fun write(gatt: BluetoothGatt, bytes: ByteArray): Boolean {
            val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            return if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(rx, bytes, type) == BluetoothStatusCodes.SUCCESS
            } else {
                rx.writeType = type
                rx.value = bytes
                gatt.writeCharacteristic(rx)
            }
        }

        @Suppress("DEPRECATION")
        private fun enableNotifications(gatt: BluetoothGatt, cccd: BluetoothGattDescriptor): Boolean {
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            return if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = value
                gatt.writeDescriptor(cccd)
            }
        }

        private fun waitFor(message: String, millis: Long) {
            clearTimeout()
            mutableStatus.value = ConnectionStatus(message, active = true)
            timeout = Runnable { finish("Zeitüberschreitung bei „$message“", error = true) }
                .also { handler.postDelayed(it, millis) }
        }

        private fun clearTimeout() {
            timeout?.let(handler::removeCallbacks)
            timeout = null
        }

        private inline fun guarded(action: () -> Unit) {
            if (current !== this) return
            try {
                action()
            } catch (_: SecurityException) {
                finish("Bluetooth-Berechtigung fehlt.", error = true)
            } catch (_: IllegalStateException) {
                finish("Bluetooth-Verbindung ist nicht mehr verfügbar.", error = true)
            }
        }

        fun finish(message: String, error: Boolean = false) {
            if (current !== this) return
            current = null
            handler.removeCallbacks(pollTelemetry)
            clearTimeout()
            searches.disconnect(journalName, message)
            journal.close()
            gatt?.let {
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            gatt = null
            mutableStatus.value = ConnectionStatus(message, error = error)
        }
    }
}
