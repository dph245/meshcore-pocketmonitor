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
import android.database.sqlite.SQLiteException
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import de.meshcore.pocketmonitor.protocol.GroupSend
import de.meshcore.pocketmonitor.protocol.GroupSendStatus
import de.meshcore.pocketmonitor.protocol.PrivateRetry
import de.meshcore.pocketmonitor.protocol.PrivateRetryOptions
import de.meshcore.pocketmonitor.protocol.PrivateSend
import de.meshcore.pocketmonitor.protocol.PrivateSendReceipt
import de.meshcore.pocketmonitor.protocol.PrivateSendStatus
import de.meshcore.pocketmonitor.protocol.PrivateSendStep
import de.meshcore.pocketmonitor.protocol.HeardRepeater
import de.meshcore.pocketmonitor.protocol.RepeaterAttribution
import de.meshcore.pocketmonitor.protocol.RepeaterWindow
import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.ChannelQuery
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.CompanionRead
import de.meshcore.pocketmonitor.protocol.CompanionProtocol.Command
import de.meshcore.pocketmonitor.protocol.ContactListTransfer
import de.meshcore.pocketmonitor.protocol.DiscoverCommandReply
import de.meshcore.pocketmonitor.protocol.DiscoverReply
import de.meshcore.pocketmonitor.protocol.DiscoverSearches
import de.meshcore.pocketmonitor.protocol.FloodScope
import de.meshcore.pocketmonitor.protocol.PendingReply
import de.meshcore.pocketmonitor.protocol.CompanionContact
import de.meshcore.pocketmonitor.protocol.MessageSyncStep
import de.meshcore.pocketmonitor.protocol.QueuedMessage
import de.meshcore.pocketmonitor.protocol.ChannelSlot
import de.meshcore.pocketmonitor.protocol.TelemetryMetric
import de.meshcore.pocketmonitor.protocol.TelemetryQuery
import de.meshcore.pocketmonitor.rx.RxFrame
import de.meshcore.pocketmonitor.rx.CompanionHistory
import de.meshcore.pocketmonitor.rx.KnownCompanion
import de.meshcore.pocketmonitor.rx.HistoryChannel
import de.meshcore.pocketmonitor.rx.ReceptionWriter
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ConnectionStatus(
    val message: String = "Getrennt",
    val active: Boolean = false,
    val error: Boolean = false,
    val contacts: ContactStatus = ContactStatus(),
    val companionReadBusy: Boolean = false,
    val channelError: String? = null,
    val syncingMessages: Boolean = false,
    val messageSyncError: String? = null,
    val batteryMv: Int? = null,
    val noiseFloorDbm: Int? = null,
    val discoverUnavailable: String? = "Zuerst einen Companion verbinden.",
)

internal data class GroupSender(
    val session: String,
    val companion: String,
    val channels: Set<String>,
    val scopes: List<String>,
    val maxBytes: Int,
    val busy: Boolean,
)

internal data class LiveRepeaterSnapshot(val companion: String, val rows: List<HeardRepeater>)

internal data class PrivateSender(
    val session: String,
    val companion: String,
    val contacts: List<CompanionContact>,
    val textLimit: Int,
    val busy: Boolean,
)

private class PrivateTransmission(
    val id: Long, val key: String, val wireTime: Long, val text: ByteArray, val retry: PrivateRetry,
) {
    val wireKey = key.hexToByteArray()
    var status = PrivateSendStatus.WAITING
    var timer: Runnable? = null
}

private enum class PrivateStage { RESTORE_SCOPE, RESET_PATH, SEND }
private class PrivateCommand(val stage: PrivateStage, command: ByteArray) {
    val reply = CompanionRead(command, if (stage == PrivateStage.SEND) PrivateSend.SENT else CompanionProtocol.OK)
    val earlyAcks = mutableSetOf<Long>()
}

private enum class GroupStage { PREPARE, SEND, RESTORE }

private class GroupTransaction(
    val id: Long,
    val scope: String,
    val command: ByteArray,
    val completed: (String?) -> Unit,
) {
    var stage = GroupStage.PREPARE
    lateinit var reply: CompanionRead
    var status = GroupSendStatus.FAILED
    var error: String? = null
}

internal class ChannelCatalog(
    val journalName: String,
    val slots: List<ChannelSlot>,
    scope: FloodScope?,
    val complete: Boolean,
) {
    val liveDecoding = ChannelDecoding(slots.mapNotNull { it.channel.forLiveDiagnostics() }, scope)
}

@SuppressLint("MissingPermission") // Runtime permissions come from the activity; revocation is handled below.
internal class CompanionConnection(private val context: Context, private val history: CompanionHistory) {
    private val thread = HandlerThread("Companion-GATT").apply { start() }
    private val handler = Handler(thread.looper)
    private val mutableStatus = MutableStateFlow(ConnectionStatus())
    val status = mutableStatus.asStateFlow()
    private val mutableGroupSender = MutableStateFlow<GroupSender?>(null)
    val groupSender = mutableGroupSender.asStateFlow()
    private val mutablePrivateSender = MutableStateFlow<PrivateSender?>(null)
    val privateSender = mutablePrivateSender.asStateFlow()
    private val mutableLiveRepeaters = MutableStateFlow<LiveRepeaterSnapshot?>(null)
    val liveRepeaters = mutableLiveRepeaters.asStateFlow()
    private val searches = DiscoverSearches()
    val discover = searches.history

    private val mutableCompanion = MutableStateFlow<KnownCompanion?>(null)
    val companion = mutableCompanion.asStateFlow()

    @Volatile
    var historyError: String? = null
        private set

    @Volatile
    private var channelCatalog: ChannelCatalog? = null

    fun channelDecoding(journalName: String): ChannelDecoding? =
        channelCatalog?.takeIf { it.journalName == journalName }?.liveDecoding

    private var current: Session? = null
    private var previousWriter: Job? = null

    fun connect(device: CompanionDevice) {
        handler.post {
            if (current == null) {
                val session = Session(device)
                current = session
                channelCatalog = null
                historyError = null
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

    fun sendPrivate(session: String, companion: String, key: String, text: String,
                    options: PrivateRetryOptions, completed: (String?) -> Unit) {
        handler.post {
            val connection = current
            if (connection == null) completed("Companion ist getrennt.")
            else connection.sendPrivate(session, companion, key, text, options, completed)
        }
    }

    fun sendGroup(session: String, companion: String, channel: String, text: String, scope: String,
                  completed: (String?) -> Unit) {
        handler.post {
            val connection = current
            if (connection == null) completed("Companion ist nicht verbunden.")
            else connection.sendGroup(session, companion, channel, text, scope, completed)
        }
    }

    fun close() {
        handler.post {
            current?.finish("Getrennt")
            thread.quitSafely()
        }
    }

    // connectGatt delivers all callbacks on this same handler.
    private inner class Session(private val device: CompanionDevice) : BluetoothGattCallback() {
        private val discoverSession = UUID.randomUUID().toString()
        private var journalName: String? = null
        private var selfName: ByteArray? = null
        private var group: GroupTransaction? = null
        private var privateTx: PrivateTransmission? = null
        private var privateCommand: PrivateCommand? = null
        private var privateCommandTimeout: Runnable? = null
        private var sendableContacts = emptyList<CompanionContact>()
        private val stalePrivatePrefixes = mutableSetOf<String>()
        private var repeaterAttribution = RepeaterAttribution(emptyList())
        private val repeaterWindow = RepeaterWindow()
        private var repeaterPublishPending = false
        private val publishRepeaters = Runnable { publishRepeaterWindow() }
        private var groupBlocked = false
        private val writer = ReceptionWriter(history, previousWriter, onIdentified = { identified ->
            handler.post { if (current === this) mutableCompanion.value = identified }
        }, onError = { message ->
            handler.post {
                if (current === this) {
                    historyError = message
                    finish(message, error = true)
                }
            }
        })
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
        private var messageSync: MessageSyncStep? = null
        private var messagesWaiting = true
        private var initialTelemetryPending = true
        private val resumeMessageSync = Runnable { guarded { startMessageSync() } }
        private var contactsByPrefix = emptyMap<String, List<CompanionContact>>()

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
            privateCommand?.let { command ->
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    command.reply.acknowledgeWrite()
                    privateProgress(command)
                } else {
                    finish("BLE-Schreibbestätigung für PM fehlgeschlagen.", error = true)
                }
                return@guarded
            }
            group?.let { tx ->
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    tx.reply.acknowledgeWrite()
                    groupProgress(gatt, tx)
                } else {
                    finishGroup(tx, "BLE-Schreibbestätigung fehlgeschlagen.", uncertain = true)
                }
                return@guarded
            }
            messageSync?.let { step ->
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    step.acknowledgeWrite()
                    messageSyncProgress(step)
                } else {
                    endMessageSync("BLE-Schreibbestätigung fehlgeschlagen", uncertain = true)
                }
                return@guarded
            }
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
                val step = messageSync
                val queueEntry = step?.receive(frame.bytes) ?: QueuedMessage.isQueueEntry(frame.code)
                if (queueEntry) {
                    // A reply may arrive after a timeout while the GATT connection is still open.
                    val accepted = writer.offerQueued(frame, configuredSlots.toList(), contactsByPrefix) {
                        if (step != null) handler.post {
                            guarded {
                                if (messageSync === step) {
                                    step.entryCommitted()
                                    messageSyncProgress(step)
                                }
                            }
                        }
                    }
                    if (!accepted) finish("Nachricht konnte nicht ins RX-Journal übernommen werden.", error = true)
                    return@guarded
                }
                if (!writer.offer(frame)) {
                    finish("RX-Journal konnte ein Ereignis nicht übernehmen. Empfang beendet.", error = true)
                    return@guarded
                }
                if (journalName != null && frame.code == CompanionProtocol.RF_LOG) {
                    repeaterAttribution.receive(frame.bytes, frame.timeMs, elapsedMs)?.let {
                        repeaterWindow.add(it)
                        if (!repeaterPublishPending) {
                            repeaterPublishPending = true
                            handler.removeCallbacks(publishRepeaters)
                            handler.postDelayed(publishRepeaters, 200)
                        }
                    }
                }
                searches.receive(discoverSession, frame, elapsedMs)
                PrivateSend.changedContact(frame.bytes)?.let { invalidatePrivateContact(it, frame.code) }
                receivePrivatePush(frame)
                if (frame.code == QueuedMessage.WAITING && frame.bytes.size == 1) {
                    messagesWaiting = true
                    handler.post(resumeMessageSync)
                }
                privateCommand?.let { command ->
                    command.reply.receive(frame.bytes)
                    privateProgress(command)
                    return@guarded
                }
                group?.let { tx ->
                    tx.reply.receive(frame.bytes)
                    groupProgress(gatt, tx)
                    return@guarded
                }
                if (step != null) {
                    messageSyncProgress(step)
                    return@guarded
                }
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
                if (transaction.command == Command.APP_START && frame.code == Command.APP_START.expectedReply &&
                    journalName == null
                ) {
                    val identity = CompanionProtocol.selfIdentity(frame.bytes)
                    if (identity == null || !writer.identify(KnownCompanion(identity, device.name))) {
                        finish("Companion hat keine gültige MeshCore-Identität geliefert.", error = true)
                        return@guarded
                    }
                    journalName = identity
                    selfName = GroupSend.selfName(frame.bytes)
                }
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
            if (!contacts.canRefresh || channelQuery != null || telemetryQuery != null || messageSync != null ||
                searches.history.value.busy || group != null || privateCommand != null || commandChannelUncertain) {
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
                    if (!writer.contacts(contacts.book?.list?.contacts.orEmpty())) {
                        finish("Kontakte konnten nicht in der Historie gespeichert werden.", error = true)
                        return
                    }
                    contactsByPrefix = contacts.book?.list?.contacts.orEmpty().groupBy { it.publicKey.take(12) }
                    stalePrivatePrefixes.clear()
                    updateSendableContacts()
                    repeaterAttribution = RepeaterAttribution(contacts.book?.list?.contacts.orEmpty())
                    journalName?.let { searches.updateResolver(discoverSession, contacts.resolverFor(it)) }
                }
                publishContacts(contacts)
                startInitialMetadata()
                handler.post(resumeMessageSync)
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
                mutableStatus.value = mutableStatus.value.copy(
                    channelError = "Kanalabfrage nicht möglich. Companion erneut verbinden.",
                    messageSyncError = "Nachrichtenabgleich nicht gestartet. Companion erneut verbinden.",
                )
            }
            publishContacts(mutableStatus.value.contacts.copy(canRefresh = false, loading = false, error = message))
        }

        private fun publishRepeaterWindow() {
            repeaterPublishPending = false
            val id = journalName ?: return
            val now = SystemClock.elapsedRealtime()
            mutableLiveRepeaters.value = LiveRepeaterSnapshot(id, repeaterWindow.snapshot(now))
            repeaterWindow.nextExpiry()?.let { expiry ->
                handler.postDelayed(publishRepeaters, maxOf(expiry - now, 200))
            }
        }

        private fun publishContacts(contacts: ContactStatus) {
            publishGroupSender()
            mutableStatus.value = mutableStatus.value.copy(
                contacts = contacts,
                companionReadBusy = channelQuery != null || telemetryQuery != null || messageSync != null,
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
            if (initialMetadataPending || channelSlots == 0 || commandChannelUncertain || group != null || privateCommand != null ||
                channelQuery != null || telemetryQuery != null || contactTransfer != null || messageSync != null ||
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
            val id = journalName ?: return
            groupBlocked = failed
            val slots = configuredSlots.toList()
            val changed = previous == null || (!failed && !previous.complete) ||
                slots.map { it.signature() } != previous.slots.map { it.signature() }
            if (previous == null || (!failed && changed)) {
                channelCatalog = ChannelCatalog(id, slots, defaultScope, !failed)
            }
            if (changed && (slots.isNotEmpty() || (!failed && channelSlots > 0))) {
                if (!writer.configure(slots, complete = !failed)) {
                    finish("Kanal-Konfiguration konnte nicht gespeichert werden.", error = true)
                    return
                }
            }
            mutableStatus.value = mutableStatus.value.copy(channelError = when {
                uncertain -> "Kanalabfrage unterbrochen. Companion erneut verbinden."
                failed -> "Kanalliste konnte nicht vollständig gelesen werden."
                channelSlots == 0 -> "Dieser Companion stellt keine Kanal-Slots bereit."
                else -> null
            })
            if (uncertain) {
                commandChannelUncertain = true
                if (messagesWaiting) mutableStatus.value = mutableStatus.value.copy(
                    messageSyncError = "Nachrichtenabgleich ausstehend. Companion erneut verbinden.",
                )
            }
            val contacts = mutableStatus.value.contacts
            publishContacts(if (uncertain) contacts.copy(
                canRefresh = false, error = "Companion-Befehlsstatus unklar. Erneut verbinden.",
            ) else contacts)
            if (!uncertain && initialTelemetryPending) handler.post(pollTelemetry)
            else startMessageSync()
        }

        private fun startMessageSync() {
            val connection = gatt ?: return
            if (initialTelemetryPending || !messagesWaiting || messageSync != null || !mutableStatus.value.active || initialMetadataPending ||
                channelQuery != null || contactTransfer != null || telemetryQuery != null ||
                searches.history.value.busy || group != null || privateCommand != null || commandChannelUncertain
            ) return
            if (mtu < CompanionProtocol.FULL_FRAME_MTU) {
                messagesWaiting = false
                mutableStatus.value = mutableStatus.value.copy(
                    messageSyncError = "Nachrichtenabruf benötigt BLE-MTU ≥ ${CompanionProtocol.FULL_FRAME_MTU}.",
                )
                return
            }
            nextMessage(connection)
        }

        private fun nextMessage(connection: BluetoothGatt) {
            messagesWaiting = false
            messageSync = MessageSyncStep()
            if (!mutableStatus.value.syncingMessages) {
                mutableStatus.value = mutableStatus.value.copy(syncingMessages = true, messageSyncError = null)
                publishContacts(mutableStatus.value.contacts)
            }
            val step = checkNotNull(messageSync)
            if (!write(connection, byteArrayOf(QueuedMessage.NEXT.toByte()))) {
                endMessageSync("BLE-Schreibvorgang konnte nicht gestartet werden")
                return
            }
            timeout = Runnable {
                val reason = when {
                    !step.writeComplete -> "BLE-Schreibbestätigung fehlt"
                    step.receivedReply -> "SQLite-Bestätigung fehlt"
                    else -> "Zeitüberschreitung bei der Companion-Antwort"
                }
                // Queue replies use distinct codes. After a confirmed GATT write, a missing
                // reply must not disable telemetry. Do not immediately repeat a dequeue.
                endMessageSync(reason, uncertain = !step.writeComplete)
            }.also { handler.postDelayed(it, 10_000) }
        }

        private fun messageSyncProgress(step: MessageSyncStep) {
            if (timeout == null) return
            when (step.result) {
                MessageSyncStep.Result.NEXT -> {
                    clearTimeout()
                    // Some BLE UART implementations drain multiple writes as one frame.
                    // Give the peripheral a connection interval before sending another command.
                    handler.postDelayed({
                        guarded { if (messageSync === step) gatt?.let(::nextMessage) }
                    }, 100)
                }
                MessageSyncStep.Result.EMPTY -> {
                    val repeat = messagesWaiting
                    endMessageSync()
                    if (repeat) {
                        messagesWaiting = true
                        startMessageSync()
                    }
                }
                MessageSyncStep.Result.REJECTED -> endMessageSync("Companion hat den Abruf abgelehnt")
                MessageSyncStep.Result.MALFORMED -> endMessageSync("Ungültige Companion-Antwort", uncertain = true)
                null -> Unit
            }
        }

        private fun endMessageSync(reason: String? = null, uncertain: Boolean = false) {
            clearTimeout()
            messageSync = null
            messagesWaiting = false
            if (uncertain) commandChannelUncertain = true
            val state = mutableStatus.value
            mutableStatus.value = state.copy(
                syncingMessages = false,
                messageSyncError = reason?.let { "Nachrichtenabgleich unterbrochen. $it. Companion erneut verbinden." },
            )
            publishContacts(if (uncertain) state.contacts.copy(canRefresh = false) else state.contacts)
        }

        private fun pollTelemetryWhenIdle(): Unit = guarded {
            val connection = gatt ?: return@guarded
            if (commandChannelUncertain) return@guarded
            if (contactTransfer != null || channelQuery != null || messageSync != null || telemetryQuery != null ||
                searches.history.value.busy || group != null || privateCommand != null
            ) {
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
                initialTelemetryPending = false
                publishContacts(mutableStatus.value.contacts)
                if (metrics.isNotEmpty()) handler.postDelayed(pollTelemetry, 30_000)
                handler.postDelayed(resumeMessageSync, 100)
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

        private fun publishGroupSender() {
            val catalog = channelCatalog
            val id = journalName
            mutablePrivateSender.value = if (id != null && mutableStatus.value.active &&
                !commandChannelUncertain && sendableContacts.isNotEmpty()
            ) {
                PrivateSender(discoverSession, id, sendableContacts, PrivateSend.textLimit(mtu),
                    privateTx != null || commandBusy())
            } else null
            mutableGroupSender.value = if (id != null && catalog?.complete == true && !groupBlocked &&
                !commandChannelUncertain && mutableStatus.value.active
            ) {
                GroupSender(discoverSession, id, catalog.slots.mapTo(mutableSetOf()) { it.channel.identity },
                    GroupSend.scopeOptions(firmwareVersion ?: 0), GroupSend.textLimit(mtu),
                    commandBusy())
            } else null
        }

        private fun invalidatePrivateContact(key: String, code: Int?) {
            val prefix = key.take(12)
            val currentContact = PrivateSend.contact(key, contactsByPrefix)
            val stale = if (code == PrivateSend.CONTACT_DELETED) currentContact != null
                else currentContact == null && prefix in contactsByPrefix
            if (stale && stalePrivatePrefixes.add(prefix)) {
                updateSendableContacts()
                publishGroupSender()
            }
        }

        private fun updateSendableContacts() {
            sendableContacts = contactsByPrefix.entries.mapNotNull { (prefix, matches) ->
                matches.singleOrNull()?.takeUnless { prefix in stalePrivatePrefixes }
            }
        }

        private fun privateContact(key: String): CompanionContact? =
            PrivateSend.contact(key, contactsByPrefix)?.takeUnless { key.take(12) in stalePrivatePrefixes }

        private fun commandBusy(): Boolean = pending != null || initialMetadataPending || initialTelemetryPending ||
            group != null || privateCommand != null || contactTransfer != null || channelQuery != null ||
            telemetryQuery != null || messageSync != null || searches.history.value.busy

        fun sendPrivate(session: String, companion: String, key: String, text: String,
                        options: PrivateRetryOptions, completed: (String?) -> Unit) = guarded {
            val sender = mutablePrivateSender.value
            val contact = privateContact(key)
            if (sender == null || sender.session != session || sender.companion != companion ||
                contact == null || privateTx != null || commandBusy() || commandChannelUncertain
            ) {
                completed("Kontakt oder Companion derzeit nicht sendebereit.")
                return@guarded
            }
            val bytes = GroupSend.textBytes(text)
            if (bytes == null || bytes.size > sender.textLimit) {
                completed("Text ist leer, ungültig oder zu lang.")
                return@guarded
            }
            try {
                val (id, timestamp) = history.beginPrivate(companion, contact, text, System.currentTimeMillis())
                val tx = PrivateTransmission(id, key, timestamp, bytes, PrivateRetry(options))
                privateTx = tx
                publishContacts(mutableStatus.value.contacts)
                completed(null)
                advancePrivate(tx)
            } catch (_: SQLiteException) {
                if (privateTx == null) completed("Private Nachricht konnte nicht gespeichert werden.")
                else finish("PM-Sendestatus konnte nicht gespeichert werden.", error = true)
            }
        }

        private fun advancePrivate(tx: PrivateTransmission) {
            if (privateTx !== tx) return
            val step = tx.retry.next()
            if (step == PrivateSendStep.DONE || commandChannelUncertain) {
                completePrivate(tx, if (tx.retry.acknowledged) PrivateSendStatus.ACKNOWLEDGED else PrivateSendStatus.UNKNOWN)
                return
            }
            if (commandBusy()) {
                schedulePrivate(tx, 500)
                return
            }
            if (privateContact(tx.key) == null) {
                completePrivate(tx, if (tx.retry.attempt == 0) PrivateSendStatus.FAILED else PrivateSendStatus.UNKNOWN)
                return
            }
            when {
                history.groupScopeDirty(checkNotNull(journalName)) -> {
                    if ((firmwareVersion ?: 0) < GroupSend.SCOPE_MIN_VERSION) {
                        completePrivate(tx, if (tx.retry.attempt == 0) PrivateSendStatus.FAILED else PrivateSendStatus.UNKNOWN)
                    } else {
                        writePrivate(tx, PrivateStage.RESTORE_SCOPE, GroupSend.restoreScope)
                    }
                }
                step == PrivateSendStep.RESET_PATH ->
                    writePrivate(tx, PrivateStage.RESET_PATH, PrivateSend.resetPath(tx.key))
                else -> writePrivate(tx, PrivateStage.SEND, PrivateSend.command(tx.key, tx.wireTime, tx.retry.attempt, tx.text))
            }
        }

        private fun writePrivate(tx: PrivateTransmission, stage: PrivateStage, bytes: ByteArray) {
            val connection = checkNotNull(gatt)
            val command = PrivateCommand(stage, bytes)
            privateCommand = command
            if (stage == PrivateStage.SEND && tx.status == PrivateSendStatus.WAITING) {
                // From the moment the BLE write is attempted a disconnect can leave acceptance uncertain.
                tx.status = PrivateSendStatus.UNKNOWN
            }
            if (!write(connection, command.reply.command)) {
                clearPrivateCommand()
                failPrivate(tx)
                return
            }
            privateCommandTimeout = Runnable {
                finish("Zeitüberschreitung bei der PM-Companion-Antwort.", error = true)
            }.also { handler.postDelayed(it, 5_000) }
            publishContacts(mutableStatus.value.contacts)
        }

        private fun receivePrivatePush(frame: RxFrame) {
            val tx = privateTx ?: return
            if (frame.code == PrivateSend.PATH_UPDATED && frame.bytes.size == 33 &&
                tx.wireKey.indices.all { tx.wireKey[it] == frame.bytes[it + 1] }
            ) tx.retry.pathUpdated()
            val token = PrivateSend.ack(frame.bytes) ?: return
            tx.retry.acknowledge(token)
            val command = privateCommand
            if (command == null && tx.retry.acknowledged) {
                completePrivate(tx, PrivateSendStatus.ACKNOWLEDGED)
            } else if (command?.stage == PrivateStage.SEND && command.earlyAcks.size < 32) {
                command.earlyAcks.add(token)
            }
        }

        private fun privateProgress(command: PrivateCommand) {
            val frame = command.reply.result ?: return
            val tx = checkNotNull(privateTx)
            val code = frame[0].toInt() and 0xff
            clearPrivateCommand()
            try {
                if ((code == CompanionProtocol.ERROR && frame.size in 1..2) ||
                    (code == CompanionProtocol.DISABLED && frame.size == 1)
                ) {
                    failPrivate(tx)
                    return
                }
                if (command.stage == PrivateStage.SEND) {
                    val receipt = PrivateSendReceipt.decode(frame)
                    if (receipt == null || receipt.estimateMs !in 1..600_000) {
                        finish("Ungültige PM-Sendeantwort vom Companion.", error = true)
                        return
                    }
                    tx.retry.receipt(receipt)
                    command.earlyAcks.forEach(tx.retry::acknowledge)
                    if (tx.retry.acknowledged) {
                        completePrivate(tx, PrivateSendStatus.ACKNOWLEDGED)
                        return
                    }
                    tx.status = PrivateSendStatus.ACCEPTED
                    history.updatePrivate(tx.id, tx.status)
                    schedulePrivate(tx, receipt.waitMs)
                } else {
                    if (code != CompanionProtocol.OK || frame.size != 1) {
                        finish("Ungültige PM-Konfigurationsantwort vom Companion.", error = true)
                        return
                    }
                    when (command.stage) {
                        PrivateStage.RESTORE_SCOPE -> history.setGroupScopeDirty(checkNotNull(journalName), false)
                        PrivateStage.RESET_PATH -> tx.retry.pathReset()
                        PrivateStage.SEND -> error("PM-Sendestufe bereits verarbeitet")
                    }
                    schedulePrivate(tx, 150)
                }
                publishContacts(mutableStatus.value.contacts)
                handler.post(resumeMessageSync)
            } catch (_: SQLiteException) {
                finish("PM-Sendestatus konnte nicht gespeichert werden.", error = true)
            }
        }

        private fun schedulePrivate(tx: PrivateTransmission, delayMs: Long) {
            tx.timer?.let(handler::removeCallbacks)
            tx.timer = Runnable {
                guarded {
                    try {
                        advancePrivate(tx)
                    } catch (_: SQLiteException) {
                        finish("PM-Sendestatus konnte nicht gespeichert werden.", error = true)
                    }
                }
            }.also { handler.postDelayed(it, delayMs) }
        }

        private fun clearPrivateCommand() {
            privateCommand = null
            privateCommandTimeout?.let(handler::removeCallbacks)
            privateCommandTimeout = null
        }

        private fun failPrivate(tx: PrivateTransmission) = completePrivate(tx, when {
            tx.retry.acknowledged -> PrivateSendStatus.ACKNOWLEDGED
            tx.retry.attempt == 0 -> PrivateSendStatus.FAILED
            else -> PrivateSendStatus.UNKNOWN
        })

        private fun completePrivate(tx: PrivateTransmission, status: PrivateSendStatus) {
            tx.timer?.let(handler::removeCallbacks)
            tx.status = status
            try {
                history.updatePrivate(tx.id, status)
            } catch (_: SQLiteException) {
                finish("PM-Sendestatus konnte nicht gespeichert werden.", error = true)
                return
            }
            privateTx = null
            publishContacts(mutableStatus.value.contacts)
            handler.post(resumeMessageSync)
        }

        fun sendGroup(session: String, companion: String, identity: String, text: String, scope: String,
                      completed: (String?) -> Unit) = guarded {
            val connection = gatt
            val sender = mutableGroupSender.value
            val slot = channelCatalog?.slots?.firstOrNull { it.channel.identity == identity }
            if (connection == null || sender == null || sender.session != session || sender.companion != companion ||
                slot == null || sender.busy || group != null
            ) {
                completed("Companion oder Kanal ist gerade nicht sendebereit.")
                return@guarded
            }
            val bytes = GroupSend.textBytes(text)
            if (scope !in sender.scopes || bytes == null || bytes.size > sender.maxBytes) {
                completed("Ungültiger Scope oder Nachrichtentext.")
                return@guarded
            }
            try {
                val oldOverride = history.groupScopeDirty(companion)
                if (oldOverride && (firmwareVersion ?: 0) < GroupSend.SCOPE_MIN_VERSION) {
                    completed("Der Scope-Override konnte mit dieser Firmware nicht zurückgesetzt werden.")
                    return@guarded
                }
                val now = System.currentTimeMillis()
                val channel = HistoryChannel(identity, slot.channel.name, slot.index)
                val (id, wireTime) = history.beginGroup(companion, channel, text, now, scope) { timestamp ->
                    selfName?.let { GroupSend.repeatDigest(slot.channel, timestamp, it, bytes) }
                }
                val tx = GroupTransaction(id, scope, GroupSend.command(slot.index, wireTime, bytes), completed)
                group = tx
                publishContacts(mutableStatus.value.contacts)
                val override = GroupSend.scopeOverride(scope)
                if (override != null || oldOverride) {
                    history.setGroupScopeDirty(companion, true)
                    writeGroup(connection, tx, GroupStage.PREPARE, override ?: GroupSend.restoreScope)
                } else {
                    writeGroup(connection, tx, GroupStage.SEND, tx.command)
                }
            } catch (_: SQLiteException) {
                val message = "Sendevorgang konnte nicht gespeichert werden."
                val tx = group
                if (tx == null) completed(message) else finishGroup(tx, message, uncertain = true)
            }
        }

        private fun writeGroup(connection: BluetoothGatt, tx: GroupTransaction, stage: GroupStage, bytes: ByteArray) {
            tx.stage = stage
            tx.reply = CompanionRead(bytes, CompanionProtocol.OK)
            if (stage == GroupStage.SEND) tx.status = GroupSendStatus.UNKNOWN
            if (!write(connection, tx.reply.command)) {
                if (stage == GroupStage.SEND) {
                    tx.status = GroupSendStatus.FAILED
                    restoreGroupScope(connection, tx, "BLE-Schreibvorgang konnte nicht gestartet werden.")
                } else {
                    finishGroup(tx, "Scope-Befehl konnte nicht gestartet werden.", uncertain = true)
                }
                return
            }
            timeout = Runnable {
                finishGroup(tx, "Zeitüberschreitung bei der Companion-Antwort.", uncertain = true)
            }.also { handler.postDelayed(it, 5_000) }
        }

        private fun nextGroupWrite(connection: BluetoothGatt, tx: GroupTransaction, stage: GroupStage, bytes: ByteArray) {
            handler.postDelayed({
                guarded { if (group === tx) writeGroup(connection, tx, stage, bytes) }
            }, 100)
        }

        private fun groupProgress(connection: BluetoothGatt, tx: GroupTransaction) {
            val reply = tx.reply.result ?: return
            if (timeout == null) return
            clearTimeout()
            val accepted = reply.contentEquals(byteArrayOf(CompanionProtocol.OK.toByte()))
            val rejected = reply.size == 2 && reply[0].toInt() == CompanionProtocol.ERROR
            if (!accepted && !rejected) {
                finishGroup(tx, "Ungültige Companion-Antwort.", uncertain = true)
                return
            }
            val rejection = if (rejected) "Companion-Code ${reply[1].toInt() and 0xff}." else null
            try {
                when (tx.stage) {
                    GroupStage.PREPARE -> if (accepted) {
                        if (tx.scope == GroupSend.DEFAULT) history.setGroupScopeDirty(checkNotNull(journalName), false)
                        nextGroupWrite(connection, tx, GroupStage.SEND, tx.command)
                    } else {
                        restoreGroupScope(connection, tx, "Scope abgelehnt. $rejection")
                    }
                    GroupStage.SEND -> {
                        tx.status = if (accepted) GroupSendStatus.ACCEPTED else GroupSendStatus.REJECTED
                        history.updateGroup(tx.id, tx.status)
                        restoreGroupScope(connection, tx, rejection)
                    }
                    GroupStage.RESTORE -> if (accepted) {
                        history.setGroupScopeDirty(checkNotNull(journalName), false)
                        finishGroup(tx, tx.error)
                    } else finishGroup(tx, "Scope konnte nicht zurückgesetzt werden. $rejection", uncertain = true)
                }
            } catch (_: SQLiteException) {
                finishGroup(tx, "Sendevorgang konnte nicht aktualisiert werden.", uncertain = true)
            }
        }

        private fun restoreGroupScope(connection: BluetoothGatt, tx: GroupTransaction, error: String?) {
            tx.error = error
            if (history.groupScopeDirty(checkNotNull(journalName))) {
                nextGroupWrite(connection, tx, GroupStage.RESTORE, GroupSend.restoreScope)
            } else finishGroup(tx, error)
        }

        private fun recordGroupResult(tx: GroupTransaction): Boolean = try {
            history.updateGroup(tx.id, tx.status)
            true
        } catch (_: SQLiteException) {
            false
        }

        private fun finishGroup(tx: GroupTransaction, error: String?, uncertain: Boolean = false) {
            group = null
            clearTimeout()
            val stored = recordGroupResult(tx)
            val message = if (stored) error else "Sendestatus konnte nicht gespeichert werden."
            tx.completed(message)
            if (uncertain) {
                finish("${message.orEmpty()} Companion erneut verbinden.", error = true)
            } else {
                publishContacts(mutableStatus.value.contacts)
                handler.post(resumeMessageSync)
            }
        }

        private fun discoverUnavailable(): String? = when {
            commandChannelUncertain -> "Companion-Befehlsstatus unklar. Für DISCOVER erneut verbinden."
            group != null -> "Kanalnachricht wird gesendet …"
            privateCommand != null -> "Private Nachricht wird an den Companion übergeben …"
            discoverDisabled != null -> discoverDisabled
            firmwareVersion == null -> "Companion-Protokollversion für DISCOVER fehlt."
            (firmwareVersion ?: 0) < CompanionProtocol.DISCOVER_MIN_VERSION ->
                "DISCOVER benötigt Companion-Protokollversion 8 oder höher (aktuell $firmwareVersion)."
            mtu < CompanionProtocol.DISCOVER_MIN_MTU ->
                "DISCOVER benötigt BLE-MTU ≥ ${CompanionProtocol.DISCOVER_MIN_MTU} (aktuell $mtu)."
            contactTransfer != null -> "Warte auf die Kontaktliste …"
            channelQuery != null -> "Warte auf Kanal- und Scope-Daten …"
            telemetryQuery != null -> "Warte auf Akku- und Funkwerte …"
            messageSync != null -> "Nachrichten werden abgeglichen …"
            else -> null
        }

        fun sendDiscover() = guarded {
            val connection = gatt ?: return@guarded
            val contacts = mutableStatus.value.contacts
            val journalName = journalName ?: return@guarded
            if (contacts.journalName == null || discoverUnavailable() != null) return@guarded
            val run = searches.start(
                discoverSession, System.currentTimeMillis(), SystemClock.elapsedRealtime(), contacts.resolverFor(journalName),
            ) ?: return@guarded
            handler.postDelayed(
                { searches.endCooldown(SystemClock.elapsedRealtime()) }, DiscoverSearches.COOLDOWN_MS,
            )
            discoverReply = DiscoverCommandReply()
            publishGroupSender()
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
                        publishGroupSender()
                        startMessageSync()
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
            handler.post(resumeMessageSync)
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
            handler.removeCallbacks(publishRepeaters)
            mutableLiveRepeaters.value = null
            mutableGroupSender.value = null
            mutablePrivateSender.value = null
            clearPrivateCommand()
            privateTx?.let { tx ->
                tx.timer?.let(handler::removeCallbacks)
                val status = when {
                    tx.retry.acknowledged -> PrivateSendStatus.ACKNOWLEDGED
                    tx.status == PrivateSendStatus.WAITING -> PrivateSendStatus.FAILED
                    else -> PrivateSendStatus.UNKNOWN
                }
                runCatching { history.updatePrivate(tx.id, status) }
            }
            privateTx = null
            group?.let { tx ->
                recordGroupResult(tx)
                tx.completed(message)
            }
            group = null
            handler.removeCallbacks(pollTelemetry)
            handler.removeCallbacks(resumeMessageSync)
            clearTimeout()
            searches.disconnect(discoverSession, message)
            writer.close()
            previousWriter = writer.completion
            channelCatalog = null
            gatt?.let {
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            gatt = null
            val state = mutableStatus.value
            val syncError = if (state.syncingMessages || (state.active && messagesWaiting)) {
                "Nachrichtenabgleich unterbrochen. Beim nächsten Verbinden wird die restliche Warteschlange abgerufen."
            } else state.messageSyncError
            mutableStatus.value = ConnectionStatus(message, error = error, messageSyncError = syncError)
        }
    }
}
