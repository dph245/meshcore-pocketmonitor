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
import de.meshcore.pocketmonitor.protocol.PrivateRoute
import de.meshcore.pocketmonitor.protocol.PrivateSend
import de.meshcore.pocketmonitor.protocol.PrivateSendReceipt
import de.meshcore.pocketmonitor.protocol.PrivateSendStatus
import de.meshcore.pocketmonitor.protocol.PrivateSendStep
import de.meshcore.pocketmonitor.protocol.HeardRepeater
import de.meshcore.pocketmonitor.protocol.RepeaterAttribution
import de.meshcore.pocketmonitor.protocol.RepeaterWindow
import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.ChannelChange
import de.meshcore.pocketmonitor.protocol.ChannelQuery
import de.meshcore.pocketmonitor.protocol.ChannelReference
import de.meshcore.pocketmonitor.protocol.reference
import de.meshcore.pocketmonitor.protocol.CompanionAction
import de.meshcore.pocketmonitor.protocol.CompanionManagement
import de.meshcore.pocketmonitor.protocol.AdvertLocationSettings
import de.meshcore.pocketmonitor.protocol.CompanionGps
import de.meshcore.pocketmonitor.protocol.CompanionPosition
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.CompanionRead
import de.meshcore.pocketmonitor.protocol.CompanionProtocol.Command
import de.meshcore.pocketmonitor.protocol.ContactListResult
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
import de.meshcore.pocketmonitor.protocol.TraceCommand
import de.meshcore.pocketmonitor.protocol.TracePath
import de.meshcore.pocketmonitor.protocol.TracePhase
import de.meshcore.pocketmonitor.protocol.TraceResponse
import de.meshcore.pocketmonitor.protocol.TraceRun
import de.meshcore.pocketmonitor.rx.RxFrame
import de.meshcore.pocketmonitor.rx.CompanionHistory
import de.meshcore.pocketmonitor.rx.KnownCompanion
import de.meshcore.pocketmonitor.rx.HistoryChannel
import de.meshcore.pocketmonitor.rx.ReceptionWriter
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ConnectionStatus(
    val message: String = "Getrennt",
    val active: Boolean = false,
    val error: Boolean = false,
    val contacts: ContactStatus = ContactStatus(),
    val management: ManagementStatus? = null,
    val channelError: String? = null,
    val channelManagement: ChannelManagementStatus? = null,
    val syncingMessages: Boolean = false,
    val messageSyncError: String? = null,
    val batteryMv: Int? = null,
    val noiseFloorDbm: Int? = null,
    val uptimeSeconds: Long? = null,
    val discoverUnavailable: String? = "Zuerst einen Companion verbinden.",
    val traceSender: TraceSender? = null,
)

internal data class TraceSender(val session: String, val mtu: Int, val unavailable: String?)

internal data class ChannelManagementStatus(
    val session: String,
    val companion: String,
    val channels: List<ChannelReference>,
    val hasFreeSlot: Boolean,
    val busy: Boolean,
)

internal data class ManagementStatus(
    val session: String,
    val scopeName: String?,
    val cliTextLimit: Int?,
    val busy: Boolean,
    val available: Boolean,
    val position: CompanionPosition?,
    val advertLocationEnabled: Boolean?,
    val canSetAdvertLocation: Boolean,
    val gps: CompanionGps,
)

private class ManagementTransaction(
    val action: CompanionAction,
    val completed: (Result<String?>) -> Unit,
) {
    var reply: CompanionRead? = null
    var verifyingScope: ByteArray? = null
    var verifyingAdvertLocation = false
    var cliPrefix: String? = null
    var channelChange: ChannelChange? = null
    var verifyingChannel = false
}

internal data class GroupSender(
    val session: String,
    val companion: String,
    val channels: Set<String>,
    val scopes: List<String>,
    val maxBytes: Int,
    val busy: Boolean,
)

internal data class LiveRepeaterSnapshot(val companion: String, val rows: List<HeardRepeater>)

internal data class PrivatePathSnapshot(val session: String, val key: String, val contact: CompanionContact?)

private class PrivatePathQuery(val key: String) : CompanionRead(PrivateSend.readContact(key), CompanionProtocol.CONTACT)

internal data class PrivateSender(
    val session: String,
    val companion: String,
    val contacts: List<CompanionContact>,
    val repeaters: List<CompanionContact>,
    val firmwareVersion: Int,
    val pathHashWidth: Int,
    val textLimit: Int,
    val busy: Boolean,
)

private class PrivateTransmission(
    val id: Long, val key: String, val wireTime: Long, val text: ByteArray, val options: PrivateRetryOptions,
) {
    val retry = PrivateRetry(options)
    var status = PrivateSendStatus.WAITING
    var timer: Runnable? = null
    var pathRevision = 0
    var scopeApplied = false
}

private enum class PrivateStage { READ_PATH, SET_PATH, RESET_PATH, VERIFY_DIRECT, VERIFY_FLOOD, SET_SCOPE, RESTORE_SCOPE, SEND }
private class PrivateCommand(
    val stage: PrivateStage,
    bytes: ByteArray,
    val pathRevision: Int,
    val expectedFlood: Boolean?,
    val afterRestore: (() -> Unit)?,
) {
    val reply = CompanionRead(bytes, when (stage) {
        PrivateStage.SEND -> PrivateSend.SENT
        PrivateStage.READ_PATH, PrivateStage.VERIFY_DIRECT, PrivateStage.VERIFY_FLOOD -> CompanionProtocol.CONTACT
        else -> CompanionProtocol.OK
    })
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
    private val installedPrivateRoutes = context.getSharedPreferences("installed_private_routes", Context.MODE_PRIVATE)
    private val mutableStatus = MutableStateFlow(ConnectionStatus())
    val status = mutableStatus.asStateFlow()
    private val mutableGroupSender = MutableStateFlow<GroupSender?>(null)
    val groupSender = mutableGroupSender.asStateFlow()
    private val mutablePrivateSender = MutableStateFlow<PrivateSender?>(null)
    val privateSender = mutablePrivateSender.asStateFlow()
    private val mutablePrivatePath = MutableStateFlow<PrivatePathSnapshot?>(null)
    val privatePath = mutablePrivatePath.asStateFlow()
    private val mutableLiveRepeaters = MutableStateFlow<LiveRepeaterSnapshot?>(null)
    val liveRepeaters = mutableLiveRepeaters.asStateFlow()
    private val searches = DiscoverSearches()
    val discover = searches.history
    private val discoverCooldownTimer = object : Runnable {
        override fun run() {
            val remainingMs = searches.updateCooldown(SystemClock.elapsedRealtime())
            if (remainingMs > 0) handler.postDelayed(this, remainingMs)
        }
    }
    private val mutableTraces = MutableStateFlow<List<TraceRun>>(emptyList())
    val traces = mutableTraces.asStateFlow()

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
    private var requestedDevice: CompanionDevice? = null
    private var onStopped: (() -> Unit)? = null
    private var connectedOnce = false
    private var retryDelaySeconds = 2L
    private val reconnect = Runnable { requestedDevice?.let(::startSession) }

    fun connect(device: CompanionDevice, stopped: () -> Unit) {
        handler.post {
            if (requestedDevice == null) {
                requestedDevice = device
                onStopped = stopped
                connectedOnce = false
                retryDelaySeconds = 2
                startSession(device)
            }
        }
    }

    private fun startSession(device: CompanionDevice) {
        channelCatalog = null
        historyError = null
        val session = Session(device)
        current = session
        session.start()
    }

    fun disconnect() {
        handler.post {
            handler.removeCallbacks(reconnect)
            val session = current
            if (session != null) {
                session.finish("Getrennt")
            } else if (requestedDevice != null) {
                mutableStatus.value = ConnectionStatus()
                stopReceiving()
            }
        }
    }

    private fun stopReceiving() {
        requestedDevice = null
        val stopped = onStopped
        onStopped = null
        stopped?.invoke()
    }

    fun refreshContacts() {
        handler.post { current?.refreshContacts() }
    }

    fun manage(session: String, action: CompanionAction, completed: (Result<String?>) -> Unit) {
        handler.post {
            val connection = current
            if (connection == null) completed(Result.failure(IllegalStateException("Companion ist getrennt.")))
            else connection.manage(session, action, completed)
        }
    }

    fun watchPrivatePath(session: String, key: String, watching: Boolean) {
        handler.post { current?.watchPrivatePath(session, key, watching) }
    }

    fun refreshChannels() {
        handler.post { current?.refreshChannels() }
    }

    fun sendTrace(session: String, path: TracePath) {
        handler.post { current?.sendTrace(session, path) }
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

    // connectGatt delivers all callbacks on this same handler.
    private inner class Session(private val device: CompanionDevice) : BluetoothGattCallback() {
        private val discoverSession = UUID.randomUUID().toString()
        private var journalName: String? = null
        private var selfName: ByteArray? = null
        private var selfPosition: CompanionPosition? = null
        private var advertLocation: AdvertLocationSettings? = null
        private var gps = CompanionGps.UNKNOWN
        private var group: GroupTransaction? = null
        private var privateTx: PrivateTransmission? = null
        private var privateCommand: PrivateCommand? = null
        private var watchedPrivateKey: String? = null
        private var privatePathPending = false
        private var privatePathQuery: PrivatePathQuery? = null
        private val refreshPrivatePath = Runnable { guarded { refreshPrivatePathWhenIdle() } }
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
        private var contactCompletion: ((ContactListResult) -> Unit)? = null
        private var management: ManagementTransaction? = null
        private var cliSequence = 0
        private var firmwareVersion: Int? = null
        private var pathHashWidth = 1
        private var channelSlots = 0
        private var nextSlot = 0
        private var scopeQueried = false
        private var initialMetadataPending = true
        private var channelQuery: ChannelQuery? = null
        private val configuredSlots = mutableListOf<ChannelSlot>()
        private val emptyChannelSlots = sortedSetOf<Int>()
        private var channelReadFailed = false
        private var defaultScope: FloodScope? = null
        private var scopeReadable = false
        private var telemetryQuery: TelemetryQuery? = null
        private val unavailableMetrics = mutableSetOf<TelemetryMetric>()
        private val pollTelemetry = Runnable { pollTelemetryWhenIdle() }
        private var discoverReply: DiscoverCommandReply? = null
        private var traceCommand: TraceCommand? = null
        private var traceTimer: Runnable? = null
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
            if (gatt == null) finish("GATT-Verbindung konnte nicht gestartet werden.", error = true, retry = true)
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = guarded {
            when {
                status != BluetoothGatt.GATT_SUCCESS ->
                    finish("BLE-Verbindung beendet (GATT $status).", error = true, retry = true)
                newState == BluetoothProfile.STATE_DISCONNECTED ->
                    finish("Companion hat die Verbindung getrennt.", retry = true)
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
            management?.reply?.let { reply ->
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    reply.acknowledgeWrite()
                    managementProgress(gatt)
                } else {
                    finishManagement(error = "BLE-Schreibbestätigung fehlgeschlagen. Ausführung unbestätigt.", uncertain = true)
                }
                return@guarded
            }
            privateCommand?.let { command ->
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    command.reply.acknowledgeWrite()
                    privateProgress(command)
                } else {
                    finish("BLE-Schreibbestätigung für PM fehlgeschlagen.", error = true)
                }
                return@guarded
            }
            privatePathQuery?.let { query ->
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    query.acknowledgeWrite()
                    privatePathProgress(query)
                } else {
                    finishPrivatePathRead(query, null, uncertain = true)
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
            traceCommand?.let { command ->
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    command.acknowledgeWrite()
                    traceProgress(command)
                } else {
                    finishTraceCommand("BLE-Schreibbestätigung fehlgeschlagen (GATT $status).", uncertain = true)
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
                if (frame.code == TraceResponse.CODE) {
                    receiveTrace(frame.bytes)
                    return@guarded
                }
                searches.receive(discoverSession, frame, elapsedMs)
                PrivateSend.changedContact(frame.bytes)?.let { invalidatePrivateContact(it, frame.code) }
                val updatedPath = PrivateSend.pathUpdatedKey(frame.bytes)
                if (updatedPath != null) queuePrivatePathRefresh(updatedPath)
                receivePrivatePush(frame, updatedPath)
                if (frame.code == QueuedMessage.WAITING && frame.bytes.size == 1) {
                    messagesWaiting = true
                    handler.post(resumeMessageSync)
                }
                management?.reply?.let { reply ->
                    val prefix = management?.cliPrefix
                    if (frame.code != CompanionManagement.CLI_REPLY || prefix == null ||
                        CompanionManagement.cliReply(frame.bytes, prefix) != null
                    ) {
                        reply.receive(frame.bytes)
                        managementProgress(gatt)
                    }
                    return@guarded
                }
                privateCommand?.let { command ->
                    command.reply.receive(frame.bytes)
                    privateProgress(command)
                    return@guarded
                }
                privatePathQuery?.let { query ->
                    if (frame.code != CompanionProtocol.CONTACT ||
                        CompanionContact.decode(frame.bytes)?.publicKey == query.key
                    ) query.receive(frame.bytes)
                    privatePathProgress(query)
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
                traceCommand?.let { command ->
                    command.receive(frame.bytes)
                    traceProgress(command)
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
                    selfPosition = CompanionManagement.selfPosition(frame.bytes)
                    advertLocation = CompanionManagement.advertLocationSettings(frame.bytes)
                }
                if (transaction.command == Command.DEVICE_QUERY && frame.code == Command.DEVICE_QUERY.expectedReply) {
                    val version = frame.bytes.getOrNull(1)?.toInt()?.and(0xff) ?: 0
                    firmwareVersion = version
                    if (version >= PrivateRoute.MULTIBYTE_MIN_VERSION) {
                        // Device info v10 appends the zero-based hash width at byte 81.
                        pathHashWidth = frame.bytes.getOrNull(81)?.toInt()?.takeIf { it in 0..2 }?.plus(1) ?: 1
                    }
                    if (version >= 3 && mtu >= ChannelQuery.CHANNEL_MTU) {
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
                    connectedOnce = true
                    retryDelaySeconds = 2
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
                        traceSender = traceSender(),
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
                searches.history.value.busy || traceCommand != null || group != null || privateCommand != null ||
                management != null || privatePathQuery != null || commandChannelUncertain) {
                return@guarded
            }
            startContactTransfer(connection)
        }

        private fun startContactTransfer(connection: BluetoothGatt, completed: ((ContactListResult) -> Unit)? = null) {
            val contacts = mutableStatus.value.contacts
            val transfer = ContactListTransfer()
            contactTransfer = transfer
            contactCompletion = completed
            publishCommandState(contacts.copy(canRefresh = false, loading = true, error = null))
            if (write(connection, byteArrayOf(CompanionProtocol.GET_CONTACTS.toByte()))) {
                contactProgress(transfer)
            } else {
                contactTransfer = null
                val error = "Kontaktabfrage konnte nicht gestartet werden."
                publishCommandState(contacts.copy(error = error))
                completeContactTransfer(ContactListResult.Failed(error))
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
                publishCommandState(contacts)
                completeContactTransfer(result)
                startInitialMetadata()
                handler.post(resumeMessageSync)
            } else {
                // There is no request ID: after an idle timeout a late list could overlap a retry.
                timeout = Runnable {
                    stopContactTransfer("Zeitüberschreitung bei der Kontaktabfrage. Erneut verbinden.")
                }.also { handler.postDelayed(it, 10_000) }
            }
        }

        private fun completeContactTransfer(result: ContactListResult) {
            val completed = contactCompletion
            contactCompletion = null
            completed?.invoke(result)
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
            publishCommandState(mutableStatus.value.contacts.copy(canRefresh = false, loading = false, error = message))
            completeContactTransfer(ContactListResult.Failed(message))
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

        private fun startInitialMetadata() {
            val connection = gatt ?: return
            if (!initialMetadataPending || contactTransfer != null || commandChannelUncertain) return
            initialMetadataPending = false
            nextMetadataQuery(connection)
        }

        fun refreshChannels() = guarded {
            val connection = gatt ?: return@guarded
            if (channelSlots == 0 || commandChannelUncertain || commandBusy() || privateTx != null) return@guarded
            configuredSlots.clear()
            emptyChannelSlots.clear()
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
            publishCommandState()
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
            if (ChannelQuery.emptySlot(frame)) emptyChannelSlots.add(frame[1].toInt() and 0xff)
            if (frame.firstOrNull() == ChannelQuery.DEFAULT_FLOOD_SCOPE.toByte()) {
                defaultScope = ChannelQuery.defaultScope(frame)
                scopeReadable = frame.size == 1 || defaultScope != null
            }
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
            publishCommandState(if (uncertain) contacts.copy(
                canRefresh = false, error = "Companion-Befehlsstatus unklar. Erneut verbinden.",
            ) else contacts)
            if (!uncertain && initialTelemetryPending) handler.post(pollTelemetry)
            else startMessageSync()
        }

        private fun startMessageSync() {
            val connection = gatt ?: return
            if (initialTelemetryPending || !messagesWaiting || messageSync != null || !mutableStatus.value.active || initialMetadataPending ||
                channelQuery != null || contactTransfer != null || telemetryQuery != null ||
                searches.history.value.busy || traceCommand != null || group != null || privateCommand != null ||
                management != null || privatePathQuery != null || commandChannelUncertain
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
                publishCommandState()
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
            publishCommandState(if (uncertain) state.contacts.copy(canRefresh = false) else state.contacts)
        }

        private fun pollTelemetryWhenIdle(): Unit = guarded {
            val connection = gatt ?: return@guarded
            if (commandChannelUncertain) return@guarded
            if (contactTransfer != null || channelQuery != null || messageSync != null || telemetryQuery != null ||
                searches.history.value.busy || traceCommand != null || group != null || privateCommand != null ||
                management != null || privatePathQuery != null
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
                publishCommandState()
                if (metrics.isNotEmpty()) handler.postDelayed(pollTelemetry, 30_000)
                handler.postDelayed(resumeMessageSync, 100)
                return
            }

            val query = TelemetryQuery(metric)
            telemetryQuery = query
            publishCommandState()
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

        private fun publishTelemetry(metric: TelemetryMetric, value: Long?) {
            val state = mutableStatus.value
            mutableStatus.value = when (metric) {
                TelemetryMetric.BATTERY -> state.copy(batteryMv = value?.toInt())
                TelemetryMetric.NOISE_FLOOR -> state.copy(noiseFloorDbm = value?.toInt())
                TelemetryMetric.UPTIME -> state.copy(uptimeSeconds = value)
            }
        }

        private fun failTelemetry(query: TelemetryQuery) {
            clearTimeout()
            telemetryQuery = null
            commandChannelUncertain = true
            publishTelemetry(query.metric, null)
            publishCommandState(mutableStatus.value.contacts.copy(
                canRefresh = false, error = "Companion-Befehlsstatus unklar. Erneut verbinden.",
            ))
        }

        private fun publishCommandState(contacts: ContactStatus = mutableStatus.value.contacts) {
            val state = mutableStatus.value
            val catalog = channelCatalog
            val id = journalName
            val managementStatus = if (contacts.journalName != null) {
                ManagementStatus(
                    discoverSession,
                    if (scopeReadable) defaultScope?.name.orEmpty() else null,
                    CompanionManagement.cliTextLimit(mtu).takeIf { (firmwareVersion ?: 0) >= CompanionManagement.CLI_MIN_VERSION },
                    commandBusy() || privateTx != null,
                    !commandChannelUncertain,
                    selfPosition,
                    advertLocation?.enabled,
                    (firmwareVersion ?: 0) >= CompanionManagement.LOCATION_PARAMS_MIN_VERSION,
                    gps,
                )
            } else null
            mutableStatus.value = state.copy(
                contacts = contacts,
                management = managementStatus,
                channelManagement = if (id != null && catalog?.complete == true && state.active &&
                    channelSlots > 0 && !groupBlocked && !commandChannelUncertain
                ) {
                    ChannelManagementStatus(
                        discoverSession, id, catalog.slots.sortedBy { it.index }.map { it.reference() },
                        emptyChannelSlots.isNotEmpty(), commandBusy() || privateTx != null,
                    )
                } else null,
                discoverUnavailable = discoverUnavailable(),
                traceSender = traceSender(),
            )
            mutablePrivateSender.value = if (id != null && mutableStatus.value.active &&
                !commandChannelUncertain && sendableContacts.isNotEmpty()
            ) {
                PrivateSender(
                    discoverSession, id, sendableContacts,
                    contacts.book?.list?.contacts.orEmpty().filter { it.type == CompanionContact.TYPE_REPEATER },
                    firmwareVersion ?: 0, pathHashWidth, PrivateSend.textLimit(mtu), privateTx != null || commandBusy(),
                )
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
                publishCommandState()
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
            telemetryQuery != null || messageSync != null || searches.history.value.busy || management != null ||
            privatePathQuery != null || traceCommand != null

        fun watchPrivatePath(session: String, key: String, watching: Boolean) {
            if (session != discoverSession || (!watching && key != watchedPrivateKey)) return
            watchedPrivateKey = key.takeIf { watching }
            privatePathPending = false
            mutablePrivatePath.value = null
            handler.removeCallbacks(refreshPrivatePath)
            if (watching) queuePrivatePathRefresh(key)
        }

        private fun queuePrivatePathRefresh(key: String) {
            if (key != watchedPrivateKey) return
            privatePathPending = true
            handler.removeCallbacks(refreshPrivatePath)
            handler.postDelayed(refreshPrivatePath, 100)
        }

        private fun refreshPrivatePathWhenIdle() {
            val key = watchedPrivateKey ?: return
            val connection = gatt ?: return
            if (!privatePathPending) return
            if (commandChannelUncertain || privateContact(key) == null) {
                privatePathPending = false
                mutablePrivatePath.value = PrivatePathSnapshot(discoverSession, key, null)
                return
            }
            if (commandBusy()) {
                handler.postDelayed(refreshPrivatePath, 250)
                return
            }
            privatePathPending = false
            val query = PrivatePathQuery(key)
            privatePathQuery = query
            publishCommandState()
            if (write(connection, query.command)) {
                timeout = Runnable { finishPrivatePathRead(query, null, uncertain = true) }
                    .also { handler.postDelayed(it, 5_000) }
            } else {
                finishPrivatePathRead(query, null)
            }
        }

        private fun privatePathProgress(query: PrivatePathQuery) {
            val frame = query.result ?: return
            val contact = frame.takeIf { it.firstOrNull() == CompanionProtocol.CONTACT.toByte() }
                ?.let(CompanionContact::decode)
            finishPrivatePathRead(query, contact)
        }

        private fun finishPrivatePathRead(query: PrivatePathQuery, contact: CompanionContact?, uncertain: Boolean = false) {
            clearTimeout()
            privatePathQuery = null
            if (uncertain) commandChannelUncertain = true
            if (query.key == watchedPrivateKey && (!privatePathPending || uncertain)) {
                mutablePrivatePath.value = PrivatePathSnapshot(discoverSession, query.key, contact)
            }
            publishCommandState()
            handler.post(resumeMessageSync)
        }

        fun manage(session: String, action: CompanionAction, completed: (Result<String?>) -> Unit) = guarded {
            val connection = gatt
            val state = mutableStatus.value
            val unavailable = when {
                connection == null || state.management?.session != session -> "Companion ist nicht mehr verbunden."
                commandChannelUncertain -> "Companion erneut verbinden."
                commandBusy() || privateTx != null -> "Bitte den laufenden Companion-Vorgang abwarten."
                action is CompanionAction.ResetPrivatePath && privateContact(action.key) == null ->
                    "Kontakt ist auf diesem Companion nicht verfügbar."
                action is CompanionAction.SetScope && !scopeReadable -> "Standard-Scope konnte nicht gelesen werden."
                action is CompanionAction.SetAdvertLocation &&
                    (firmwareVersion ?: 0) < CompanionManagement.LOCATION_PARAMS_MIN_VERSION ->
                    "Die Standortfreigabe ist auf dieser Firmware nicht einstellbar."
                action is CompanionAction.SetGpsEnabled && gps != CompanionGps.ENABLED && gps != CompanionGps.DISABLED ->
                    "Der Companion meldet kein steuerbares GPS-Modul."
                action is CompanionAction.RunCli && (firmwareVersion ?: 0) < CompanionManagement.CLI_MIN_VERSION ->
                    "Companion-CLI benötigt Protokollversion 14 oder höher."
                (action is CompanionAction.AddContact || action is CompanionAction.RemoveContact) &&
                    !state.contacts.canRefresh -> "Kontaktliste ist nicht verfügbar."
                else -> null
            }
            if (unavailable != null || connection == null) {
                completed(Result.failure(IllegalStateException(unavailable ?: "Companion ist getrennt.")))
                return@guarded
            }

            val tx = ManagementTransaction(action, completed)
            management = tx
            val prefix = if (action is CompanionAction.RunCli) {
                val sequence = cliSequence++ % (36 * 36)
                sequence.toString(36).padStart(2, '0') + "|"
            } else null
            tx.cliPrefix = prefix
            val command = when (action) {
                is CompanionAction.SetScope -> CompanionManagement.scopeCommand(action.name)
                is CompanionAction.SendAdvert -> CompanionManagement.advertCommand(action.flood)
                is CompanionAction.SetPosition -> CompanionManagement.positionCommand(action.position)
                CompanionAction.ReadLocationSettings, is CompanionAction.SetAdvertLocation -> Command.APP_START.encode()
                is CompanionAction.SetGpsEnabled -> CompanionManagement.gpsCommand(action.enabled)
                is CompanionAction.AddContact -> CompanionManagement.addContact(action)
                is CompanionAction.RemoveContact -> CompanionManagement.removeContact(action.key)
                is CompanionAction.ResetPrivatePath -> PrivateSend.resetPath(action.key)
                is CompanionAction.RunCli -> CompanionManagement.cliCommand(
                    action.text, checkNotNull(prefix), CompanionManagement.cliTextLimit(mtu),
                )
                is CompanionAction.EditChannel -> {
                    prepareChannelChange(connection, action, tx)
                    return@guarded
                }
            }
            if (command == null) {
                finishManagement(error = when (action) {
                    is CompanionAction.SetScope -> CompanionManagement.SCOPE_INPUT_ERROR
                    is CompanionAction.RunCli -> "Ungültiges Kommando oder BLE-Längenlimit überschritten."
                    else -> CompanionManagement.CONTACT_INPUT_ERROR
                })
                return@guarded
            }
            when (action) {
                is CompanionAction.AddContact, is CompanionAction.RemoveContact ->
                    prepareContactChange(connection, action, command)
                CompanionAction.ReadLocationSettings, is CompanionAction.SetAdvertLocation -> {
                    advertLocation = null
                    if (action == CompanionAction.ReadLocationSettings) gps = CompanionGps.UNKNOWN
                    writeManagement(connection, CompanionRead(command, Command.APP_START.expectedReply))
                }
                is CompanionAction.RunCli ->
                    writeManagement(connection, CompanionRead(command, CompanionManagement.CLI_REPLY))
                else -> writeManagement(connection, CompanionRead(command, CompanionProtocol.OK))
            }
        }

        private fun prepareChannelChange(
            connection: BluetoothGatt, action: CompanionAction.EditChannel, tx: ManagementTransaction,
        ) {
            val catalog = mutableStatus.value.channelManagement
            if (catalog == null) {
                finishManagement(error = "Die Kanalliste ist nicht verfügbar.")
                return
            }
            val change = when (action) {
                is CompanionAction.EditChannel.Add -> {
                    if (catalog.channels.any { it.identity == action.channel.identity }) {
                        finishManagement(error = "Ein Kanal mit diesem Schlüssel ist bereits eingerichtet.")
                        return
                    }
                    val slot = emptyChannelSlots.firstOrNull()
                    if (slot == null) {
                        finishManagement(error = "Auf dem Companion ist kein Kanal-Slot frei.")
                        return
                    }
                    action.channel.change(slot)
                }
                is CompanionAction.EditChannel.Remove -> {
                    if (action.channel !in catalog.channels) {
                        finishManagement(error = "Dieser Kanal ist auf dem Companion nicht mehr verfügbar.")
                        return
                    }
                    ChannelChange.remove(action.channel)
                }
            }
            tx.channelChange = change
            writeManagement(connection, ChannelQuery(change.slot))
        }

        private fun prepareContactChange(connection: BluetoothGatt, action: CompanionAction, command: ByteArray) {
            startContactTransfer(connection) { result ->
                if (result is ContactListResult.Failed) {
                    finishManagement(error = result.message)
                    return@startContactTransfer
                }
                val contacts = (result as ContactListResult.Complete).contacts
                val key = command.toHexString(1, 33, HexFormat.UpperCase)
                val exists = contacts.any { it.publicKey == key }
                when {
                    action is CompanionAction.AddContact && exists ->
                        finishManagement(error = "Dieser öffentliche Schlüssel ist bereits im Kontaktbuch gespeichert.")
                    action is CompanionAction.RemoveContact && !exists ->
                        finishManagement(error = "Dieser Kontakt ist nicht mehr im Kontaktbuch gespeichert.")
                    else -> writeManagement(connection, CompanionRead(command, CompanionProtocol.OK))
                }
            }
        }

        private fun writeManagement(connection: BluetoothGatt, reply: CompanionRead) {
            val tx = checkNotNull(management)
            tx.reply = reply
            publishCommandState()
            if (write(connection, reply.command)) {
                timeout = Runnable {
                    finishManagement(error = "Keine passende Companion-Antwort. Ausführung unbestätigt.", uncertain = true)
                }.also { handler.postDelayed(it, 5_000) }
            } else if (tx.verifyingChannel) {
                channelChangeUnconfirmed("Kanaländerung angenommen. Slot konnte nicht zurückgelesen werden.")
            } else {
                val message = if (tx.verifyingScope != null) {
                    updateDefaultScope(null)
                    "Scope wurde angenommen, konnte aber nicht zurückgelesen werden."
                } else "BLE-Schreibvorgang konnte nicht gestartet werden."
                finishManagement(error = message)
            }
        }

        private fun managementProgress(connection: BluetoothGatt) {
            val tx = management ?: return
            val reply = tx.reply ?: return
            val frame = reply.result ?: return
            clearTimeout()
            tx.reply = null
            val code = frame.firstOrNull()?.toInt()?.and(0xff)
            val channelChange = tx.channelChange
            if (channelChange != null && reply is ChannelQuery) {
                channelChangeProgress(connection, tx, channelChange, frame)
                return
            }
            if (tx.verifyingScope == null && (code == CompanionProtocol.ERROR || code == CompanionProtocol.DISABLED)) {
                val number = frame.getOrNull(1)?.toInt()?.and(0xff)
                finishManagement(error = "Companion hat den Befehl abgelehnt${number?.let { " (Code $it)" }.orEmpty()}.")
                return
            }
            when (val action = tx.action) {
                CompanionAction.ReadLocationSettings -> {
                    if (reply.command[0].toInt() == Command.APP_START.code) {
                        updateLocationFromSelfInfo(frame)
                        readCompanionGps(connection)
                    } else {
                        gps = CompanionManagement.gpsSetting(frame)
                        finishManagement(error = when {
                            gps == CompanionGps.UNKNOWN -> "GPS-Status konnte nicht gelesen werden."
                            advertLocation == null -> "Standortfreigabe konnte nicht gelesen werden."
                            else -> null
                        })
                    }
                }
                is CompanionAction.SetAdvertLocation -> {
                    if (reply.command[0].toInt() == Command.APP_START.code) {
                        updateLocationFromSelfInfo(frame)
                        val current = advertLocation
                        when {
                            tx.verifyingAdvertLocation -> finishManagement(
                                error = if (current?.enabled == action.enabled) null
                                    else "Der Companion hat die Standortfreigabe nicht bestätigt.",
                            )
                            current == null -> finishManagement(error = "Standortfreigabe konnte nicht gelesen werden.")
                            current.enabled == action.enabled -> finishManagement()
                            else -> writeManagement(connection, CompanionRead(
                                CompanionManagement.advertLocationCommand(current, action.enabled), CompanionProtocol.OK,
                            ))
                        }
                    } else if (code == CompanionProtocol.OK && frame.size == 1) {
                        advertLocation = null
                        tx.verifyingAdvertLocation = true
                        writeManagement(connection, CompanionRead(Command.APP_START.encode(), Command.APP_START.expectedReply))
                    } else {
                        advertLocation = null
                        finishManagement(error = "Ungültige Bestätigung der Standortfreigabe.")
                    }
                }
                is CompanionAction.SetGpsEnabled -> {
                    if (reply.command[0] == CompanionManagement.GET_CUSTOM_VARS) {
                        gps = CompanionManagement.gpsSetting(frame)
                        val expected = if (action.enabled) CompanionGps.ENABLED else CompanionGps.DISABLED
                        finishManagement(error = if (gps == expected) null else "Der Companion hat den GPS-Status nicht bestätigt.")
                    } else if (code == CompanionProtocol.OK && frame.size == 1) {
                        gps = CompanionGps.UNKNOWN
                        readCompanionGps(connection)
                    } else {
                        gps = CompanionGps.UNKNOWN
                        finishManagement(error = "Ungültige Bestätigung des GPS-Status.")
                    }
                }
                is CompanionAction.SetScope -> {
                    val requested = tx.verifyingScope
                    if (requested == null) {
                        tx.verifyingScope = reply.command
                        writeManagement(connection, ChannelQuery())
                    } else {
                        updateDefaultScope(frame)
                        val error = if (CompanionManagement.scopeMatches(requested, frame)) null
                            else "Der Companion hat den gewünschten Standard-Scope nicht bestätigt."
                        finishManagement(error = error)
                    }
                }
                is CompanionAction.AddContact, is CompanionAction.RemoveContact ->
                    verifyContactChange(connection, action, reply.command)
                is CompanionAction.SendAdvert, is CompanionAction.SetPosition -> {
                    if (code != CompanionProtocol.OK || frame.size != 1) {
                        finishManagement(error = "Ungültige Bestätigung vom Companion.")
                        return
                    }
                    if (action is CompanionAction.SetPosition) selfPosition = action.position
                    finishManagement()
                }
                is CompanionAction.ResetPrivatePath -> {
                    if (code != CompanionProtocol.OK || frame.size != 1) {
                        finishManagement(error = "Ungültige Bestätigung für den Pfad-Reset.")
                        return
                    }
                    installedPrivateRoutes.edit().remove(privateRouteKey(action.key)).apply()
                    queuePrivatePathRefresh(action.key)
                    finishManagement()
                }
                is CompanionAction.RunCli ->
                    finishManagement(output = CompanionManagement.cliReply(frame, checkNotNull(tx.cliPrefix)))
                is CompanionAction.EditChannel -> {
                    if (code != CompanionProtocol.OK || frame.size != 1) {
                        channelChangeUnconfirmed("Ungültige Bestätigung der Kanaländerung.")
                        return
                    }
                    tx.verifyingChannel = true
                    writeManagement(connection, ChannelQuery(checkNotNull(tx.channelChange).slot))
                }
            }
        }

        private fun channelChangeProgress(
            connection: BluetoothGatt, tx: ManagementTransaction, change: ChannelChange, frame: ByteArray,
        ) {
            if (!tx.verifyingChannel) {
                if (change.matchesBefore(frame)) {
                    writeManagement(connection, CompanionRead(change.command, CompanionProtocol.OK))
                } else {
                    channelChangeUnconfirmed("Der Companion meldet einen anderen Slot-Inhalt. Aktion bitte erneut auswählen.")
                }
                return
            }
            if (!change.matchesAfter(frame)) {
                channelChangeUnconfirmed("Der Companion hat die Kanaländerung nicht bestätigt.")
                return
            }
            configuredSlots.removeAll { it.index == change.slot }
            emptyChannelSlots.remove(change.slot)
            val slot = ChannelQuery.configuredSlot(frame)
            if (slot == null) emptyChannelSlots.add(change.slot)
            else {
                configuredSlots.add(slot)
                configuredSlots.sortBy { it.index }
            }
            finishMetadata()
            finishManagement()
        }

        private fun channelChangeUnconfirmed(message: String) {
            groupBlocked = true
            finishManagement(error = message)
            refreshChannels()
        }

        private fun updateLocationFromSelfInfo(frame: ByteArray) {
            if (CompanionProtocol.selfIdentity(frame) != journalName) return
            advertLocation = CompanionManagement.advertLocationSettings(frame)
            CompanionManagement.selfPosition(frame)?.let { selfPosition = it }
        }

        private fun readCompanionGps(connection: BluetoothGatt) {
            writeManagement(connection, CompanionRead(
                byteArrayOf(CompanionManagement.GET_CUSTOM_VARS), CompanionManagement.CUSTOM_VARS,
            ))
        }

        private fun updateDefaultScope(frame: ByteArray?) {
            defaultScope = frame?.let { ChannelQuery.defaultScope(it) }
            scopeReadable = defaultScope != null ||
                frame?.contentEquals(byteArrayOf(ChannelQuery.DEFAULT_FLOOD_SCOPE.toByte())) == true
            channelCatalog = channelCatalog?.let { ChannelCatalog(it.journalName, it.slots, defaultScope, it.complete) }
        }

        private fun verifyContactChange(connection: BluetoothGatt, action: CompanionAction, command: ByteArray) {
            val key = command.toHexString(1, 33, HexFormat.UpperCase)
            startContactTransfer(connection) { result ->
                val complete = result as? ContactListResult.Complete
                val actual = complete?.contacts?.firstOrNull { it.publicKey == key }
                val verified = complete != null && when (action) {
                    is CompanionAction.AddContact -> actual?.type == action.category.type && actual.name == action.name.trim()
                    is CompanionAction.RemoveContact -> actual == null
                    else -> false
                }
                val error = if (verified) null else "Kontaktänderung vom Companion unbestätigt." +
                    ((result as? ContactListResult.Failed)?.let { " ${it.message}" } ?: "")
                finishManagement(error = error)
            }
        }

        private fun finishManagement(output: String? = null, error: String? = null, uncertain: Boolean = false) {
            val tx = management ?: return
            management = null
            clearTimeout()
            if (uncertain) {
                commandChannelUncertain = true
                if (tx.action is CompanionAction.SetScope) updateDefaultScope(null)
                if (tx.action is CompanionAction.SetAdvertLocation) advertLocation = null
                if (tx.action is CompanionAction.SetGpsEnabled) gps = CompanionGps.UNKNOWN
            }
            val contacts = mutableStatus.value.contacts
            publishCommandState(if (uncertain) contacts.copy(canRefresh = false) else contacts)
            tx.completed(if (error == null) Result.success(output) else Result.failure(
                IllegalStateException(if (uncertain) "$error Companion erneut verbinden." else error),
            ))
            handler.post(resumeMessageSync)
        }

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
            val version = firmwareVersion ?: 0
            val unsupportedRoute = (options.activeRoute?.width ?: 1) > 1 && version < PrivateRoute.MULTIBYTE_MIN_VERSION
            val scope = options.floodScope
            val unsupportedScope = when (scope) {
                GroupSend.DEFAULT -> false
                GroupSend.UNSCOPED -> version < GroupSend.UNSCOPED_MIN_VERSION
                else -> version < GroupSend.SCOPE_MIN_VERSION || CompanionManagement.namedScope(scope) != scope
            }
            if (unsupportedRoute || unsupportedScope) {
                completed("Pfadeinstellung oder Flood-Scope wird von diesem Companion nicht unterstützt.")
                return@guarded
            }
            try {
                val (id, timestamp) = history.beginPrivate(companion, contact, text, System.currentTimeMillis())
                val tx = PrivateTransmission(id, key, timestamp, bytes, options)
                privateTx = tx
                publishCommandState()
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
            val companion = checkNotNull(journalName)
            when {
                history.groupScopeDirty(companion) -> {
                    if ((firmwareVersion ?: 0) < GroupSend.SCOPE_MIN_VERSION) failPrivate(tx)
                    else restorePrivateScope(tx) { advancePrivate(tx) }
                }
                step == PrivateSendStep.RESET_PATH ->
                    writePrivate(tx, PrivateStage.RESET_PATH, PrivateSend.resetPath(tx.key))
                tx.options.activeRoute != null || tx.options.floodScope != GroupSend.DEFAULT || tx.retry.floodPhase ||
                    installedPrivateRoutes.contains(privateRouteKey(tx.key)) ->
                    writePrivate(tx, PrivateStage.READ_PATH, PrivateSend.readContact(tx.key))
                else -> sendPrivateAttempt(tx)
            }
        }

        private fun privateRouteKey(key: String) = "${checkNotNull(journalName)}/$key"

        @SuppressLint("UseKtx") // The KTX helper discards the commit result.
        private fun recordPrivateRoute(key: String, signatures: Set<String> = emptySet()): Boolean {
            val editor = installedPrivateRoutes.edit()
            if (signatures.isEmpty()) editor.remove(key) else editor.putStringSet(key, signatures)
            // The change must survive a restart before the Companion path can be written.
            return editor.commit()
        }

        private fun processPrivatePath(tx: PrivateTransmission, command: PrivateCommand, frame: ByteArray) {
            val contact = CompanionContact.decode(frame)
            if (contact?.publicKey != tx.key || command.pathRevision != tx.pathRevision || tx.retry.acknowledged) {
                failPrivate(tx)
                return
            }
            if (tx.key == watchedPrivateKey) {
                mutablePrivatePath.value = PrivatePathSnapshot(discoverSession, tx.key, contact)
            }
            val flood = contact.outPathLength == PrivateSend.UNKNOWN_PATH
            val signature = PrivateSend.pathSignature(contact)
            if (!flood && signature == null) {
                failPrivate(tx)
                return
            }
            val route = tx.options.activeRoute.takeUnless { tx.retry.floodPhase }
            val routeKey = privateRouteKey(tx.key)
            when (command.stage) {
                PrivateStage.VERIFY_DIRECT -> {
                    if (route?.signature == signature && !flood) sendPrivateAttempt(tx, flood = false)
                    else failPrivate(tx)
                }
                PrivateStage.VERIFY_FLOOD -> {
                    if (!flood || !recordPrivateRoute(routeKey)) {
                        failPrivate(tx)
                    } else {
                        tx.retry.pathReset()
                        preparePrivateAttempt(tx, flood = true)
                    }
                }
                else -> {
                    val previous = installedPrivateRoutes.getStringSet(routeKey, null).orEmpty()
                    val currentIsManual = signature != null && signature in previous
                    when {
                        route != null && signature != route.signature -> {
                            // Record the change before writing it so AUTO can recover after a disconnect.
                            val pending = setOfNotNull(signature.takeIf { currentIsManual }, route.signature)
                            if (!recordPrivateRoute(routeKey, pending)) failPrivate(tx)
                            else writePrivate(tx, PrivateStage.SET_PATH, PrivateSend.setPath(frame, route))
                        }
                        route == null && currentIsManual ->
                            writePrivate(tx, PrivateStage.RESET_PATH, PrivateSend.resetPath(tx.key))
                        route == null && previous.isNotEmpty() && !recordPrivateRoute(routeKey) ->
                            failPrivate(tx)
                        tx.retry.floodPhase && !flood -> failPrivate(tx)
                        else -> preparePrivateAttempt(tx, flood)
                    }
                }
            }
        }

        private fun preparePrivateAttempt(tx: PrivateTransmission, flood: Boolean) {
            val override = if (flood) GroupSend.scopeOverride(tx.options.floodScope) else null
            if (override == null) {
                sendPrivateAttempt(tx, flood)
            } else {
                history.setGroupScopeDirty(checkNotNull(journalName), true)
                tx.scopeApplied = true
                writePrivate(tx, PrivateStage.SET_SCOPE, override)
            }
        }

        private fun sendPrivateAttempt(tx: PrivateTransmission, flood: Boolean? = null) {
            if (tx.retry.acknowledged) {
                failPrivate(tx)
                return
            }
            writePrivate(tx, PrivateStage.SEND,
                PrivateSend.command(tx.key, tx.wireTime, tx.retry.attempt, tx.text), expectedFlood = flood)
        }

        private fun restorePrivateScope(tx: PrivateTransmission, after: () -> Unit) {
            writePrivate(tx, PrivateStage.RESTORE_SCOPE, GroupSend.restoreScope, afterRestore = after)
        }

        private fun writePrivate(
            tx: PrivateTransmission, stage: PrivateStage, bytes: ByteArray,
            expectedFlood: Boolean? = null, afterRestore: (() -> Unit)? = null,
        ) {
            val connection = checkNotNull(gatt)
            val command = PrivateCommand(stage, bytes, tx.pathRevision, expectedFlood, afterRestore)
            privateCommand = command
            if (stage == PrivateStage.SEND && tx.status == PrivateSendStatus.WAITING) {
                // From the moment the BLE write is attempted a disconnect can leave acceptance uncertain.
                tx.status = PrivateSendStatus.UNKNOWN
            }
            if (!write(connection, command.reply.command)) {
                clearPrivateCommand()
                if (stage == PrivateStage.RESTORE_SCOPE) finish("Flood-Scope konnte nicht zurückgesetzt werden.", error = true)
                else failPrivate(tx)
                return
            }
            privateCommandTimeout = Runnable {
                finish("Zeitüberschreitung bei der PM-Companion-Antwort.", error = true)
            }.also { handler.postDelayed(it, 5_000) }
            publishCommandState()
        }

        private fun receivePrivatePush(frame: RxFrame, updatedPath: String?) {
            val tx = privateTx ?: return
            if (updatedPath == tx.key) {
                tx.pathRevision++
                tx.retry.pathUpdated()
            }
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
                    if (command.stage == PrivateStage.RESTORE_SCOPE) {
                        finish("Flood-Scope konnte nicht zurückgesetzt werden.", error = true)
                    } else failPrivate(tx)
                    return
                }
                when (command.stage) {
                    PrivateStage.SEND -> acceptPrivateAttempt(tx, command, frame)
                    PrivateStage.READ_PATH, PrivateStage.VERIFY_DIRECT, PrivateStage.VERIFY_FLOOD ->
                        processPrivatePath(tx, command, frame)
                    else -> {
                        if (code != CompanionProtocol.OK || frame.size != 1) {
                            finish("Ungültige PM-Konfigurationsantwort vom Companion.", error = true)
                            return
                        }
                        when (command.stage) {
                            PrivateStage.RESTORE_SCOPE -> {
                                history.setGroupScopeDirty(checkNotNull(journalName), false)
                                tx.scopeApplied = false
                                checkNotNull(command.afterRestore).invoke()
                            }
                            PrivateStage.SET_PATH ->
                                writePrivate(tx, PrivateStage.VERIFY_DIRECT, PrivateSend.readContact(tx.key))
                            PrivateStage.RESET_PATH ->
                                writePrivate(tx, PrivateStage.VERIFY_FLOOD, PrivateSend.readContact(tx.key))
                            PrivateStage.SET_SCOPE -> {
                                if (command.pathRevision == tx.pathRevision) sendPrivateAttempt(tx, flood = true)
                                else failPrivate(tx)
                            }
                        }
                    }
                }
                publishCommandState()
                handler.post(resumeMessageSync)
            } catch (_: SQLiteException) {
                finish("PM-Sendestatus konnte nicht gespeichert werden.", error = true)
            }
        }

        private fun acceptPrivateAttempt(tx: PrivateTransmission, command: PrivateCommand, frame: ByteArray) {
            val receipt = PrivateSendReceipt.decode(frame)
            if (receipt == null || receipt.estimateMs !in 1..600_000) {
                finish("Ungültige PM-Sendeantwort vom Companion.", error = true)
                return
            }
            tx.retry.receipt(receipt)
            command.earlyAcks.forEach(tx.retry::acknowledge)
            tx.status = PrivateSendStatus.ACCEPTED
            history.recordPrivateAttempt(tx.id, tx.retry.attempt, receipt.route)
            val correctRoute = command.expectedFlood == null || command.expectedFlood == receipt.flood
            val continueAfterSend = {
                when {
                    tx.retry.acknowledged -> completePrivate(tx, PrivateSendStatus.ACKNOWLEDGED)
                    !correctRoute -> completePrivate(tx, PrivateSendStatus.UNKNOWN)
                    else -> schedulePrivate(tx, receipt.waitMs)
                }
            }
            if (tx.scopeApplied) restorePrivateScope(tx, continueAfterSend)
            else continueAfterSend()
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

        private fun failPrivate(tx: PrivateTransmission) {
            val complete = {
                completePrivate(tx, when {
                    tx.retry.acknowledged -> PrivateSendStatus.ACKNOWLEDGED
                    tx.retry.attempt == 0 -> PrivateSendStatus.FAILED
                    else -> PrivateSendStatus.UNKNOWN
                })
            }
            if (tx.scopeApplied) restorePrivateScope(tx, complete) else complete()
        }

        private fun completePrivate(tx: PrivateTransmission, status: PrivateSendStatus) {
            tx.timer?.let(handler::removeCallbacks)
            tx.status = status
            try {
                history.updatePrivate(tx.id, status)
            } catch (_: SQLiteException) {
                finish("PM-Sendestatus konnte nicht gespeichert werden.", error = true)
                return
            }
            if (status == PrivateSendStatus.ACKNOWLEDGED) queuePrivatePathRefresh(tx.key)
            privateTx = null
            publishCommandState()
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
                publishCommandState()
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
                publishCommandState()
                handler.post(resumeMessageSync)
            }
        }

        private fun traceSender() = TraceSender(discoverSession, mtu, when {
            commandChannelUncertain -> "Companion-Befehlsstatus unklar. Erneut verbinden."
            mtu < 18 -> "BLE-MTU für TRACE zu klein."
            commandBusy() || privateTx != null -> "Bitte den laufenden Companion-Vorgang abwarten."
            else -> null
        })

        private fun activeTrace(): TraceRun? =
            mutableTraces.value.lastOrNull()?.takeIf { it.session == discoverSession && it.busy }

        private fun updateTrace(run: TraceRun) {
            mutableTraces.value = mutableTraces.value.map {
                if (it.tag == run.tag && it.session == run.session) run else it
            }
        }

        fun sendTrace(session: String, path: TracePath) = guarded {
            val connection = gatt ?: return@guarded
            val identity = journalName ?: return@guarded
            if (!mutableStatus.value.active || session != discoverSession || traceSender().unavailable != null ||
                activeTrace() != null || path.hashes.size > TracePath.maxHops(path.hashBytes, mtu)
            ) return@guarded

            val runs = mutableTraces.value
            var tag = Random.nextInt().toUInt()
            while (runs.any { it.tag == tag }) tag = Random.nextInt().toUInt()
            val auth = Random.nextInt().toUInt()
            val run = TraceRun(
                discoverSession, tag, auth, path, System.currentTimeMillis(),
                mutableStatus.value.contacts.resolverFor(identity),
            )
            val command = TraceCommand(path, tag, auth)
            traceCommand = command
            mutableTraces.value = (runs + run).takeLast(20)
            publishCommandState()
            timeout = Runnable {
                finishTraceCommand("Zeitüberschreitung bei der Companion-Bestätigung. Sendestatus unklar.", uncertain = true)
            }.also { handler.postDelayed(it, DiscoverSearches.REPLY_TIMEOUT_MS) }
            if (!write(connection, command.command)) {
                finishTraceCommand("BLE-Schreibvorgang konnte nicht gestartet werden.")
            }
        }

        private fun traceProgress(command: TraceCommand) {
            val frame = command.result ?: return
            val run = activeTrace() ?: return
            val estimate = TraceCommand.estimateMs(frame)
            if (estimate == null) {
                val error = frame.getOrNull(1)?.toInt()?.and(0xff)
                when {
                    frame[0].toInt() == CompanionProtocol.ERROR && frame.size == 2 ->
                        finishTraceCommand("Companion hat TRACE abgelehnt (Code $error).")
                    frame[0].toInt() == CompanionProtocol.DISABLED && frame.size == 1 ->
                        finishTraceCommand("TRACE ist auf dem Companion deaktiviert.")
                    else -> finishTraceCommand("Ungültige TRACE-Sendebestätigung.", uncertain = true)
                }
                return
            }

            clearTimeout()
            traceCommand = null
            if (run.response != null) {
                updateTrace(run.copy(phase = TracePhase.ANSWERED))
            } else {
                updateTrace(run.copy(phase = TracePhase.LISTENING))
                traceTimer = Runnable {
                    traceTimer = null
                    activeTrace()?.takeIf { it.tag == run.tag }?.let {
                        updateTrace(it.copy(phase = TracePhase.TIMED_OUT))
                    }
                }.also { handler.postDelayed(it, (estimate + 2_000).coerceIn(5_000, 10 * 60_000)) }
            }
            publishCommandState()
            handler.post(resumeMessageSync)
        }

        private fun finishTraceCommand(message: String, uncertain: Boolean = false) {
            clearTimeout()
            traceCommand = null
            activeTrace()?.let { run ->
                updateTrace(run.copy(
                    phase = if (run.response == null) TracePhase.FAILED else TracePhase.ANSWERED,
                    message = message,
                ))
            }
            if (uncertain) commandChannelUncertain = true
            val contacts = mutableStatus.value.contacts
            publishCommandState(if (uncertain) contacts.copy(
                canRefresh = false, error = "Companion-Befehlsstatus unklar. Erneut verbinden.",
            ) else contacts)
            handler.post(resumeMessageSync)
        }

        private fun receiveTrace(frame: ByteArray) {
            val response = TraceResponse.decode(frame) ?: return
            val run = mutableTraces.value.lastOrNull {
                it.session == discoverSession && it.tag == response.tag
            } ?: return
            if (run.response != null || !run.matches(response)) return
            when (run.phase) {
                TracePhase.CONFIRMING -> updateTrace(run.copy(response = response))
                TracePhase.LISTENING -> {
                    traceTimer?.let(handler::removeCallbacks)
                    traceTimer = null
                    updateTrace(run.copy(phase = TracePhase.ANSWERED, response = response))
                }
                TracePhase.TIMED_OUT -> updateTrace(run.copy(phase = TracePhase.ANSWERED, response = response, late = true))
                else -> Unit
            }
        }

        private fun discoverUnavailable(): String? = when {
            commandChannelUncertain -> "Companion-Befehlsstatus unklar. Für DISCOVER erneut verbinden."
            management != null -> "Ein Companion-Befehl läuft …"
            group != null -> "Kanalnachricht wird gesendet …"
            privateCommand != null -> "Private Nachricht wird an den Companion übergeben …"
            privatePathQuery != null -> "Warte auf den Kontaktpfad …"
            traceCommand != null -> "Warte auf die TRACE-Sendebestätigung …"
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
            handler.removeCallbacks(discoverCooldownTimer)
            discoverCooldownTimer.run()
            discoverReply = DiscoverCommandReply()
            publishCommandState()
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
                        publishCommandState()
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
            searches.fail(message)
            val contacts = mutableStatus.value.contacts
            publishCommandState(if (uncertain) contacts.copy(
                canRefresh = false, error = "Companion-Befehlsstatus unklar. Erneut verbinden.",
            ) else contacts)
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
            timeout = Runnable { finish("Zeitüberschreitung bei „$message“", error = true, retry = true) }
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
                finish("Bluetooth-Verbindung ist nicht mehr verfügbar.", error = true, retry = true)
            }
        }

        fun finish(message: String, error: Boolean = false, retry: Boolean = false) {
            if (current !== this) return
            current = null
            handler.removeCallbacks(publishRepeaters)
            mutableLiveRepeaters.value = null
            mutableGroupSender.value = null
            mutablePrivateSender.value = null
            mutablePrivatePath.value = null
            handler.removeCallbacks(refreshPrivatePath)
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
            management?.completed?.invoke(Result.failure(IllegalStateException(
                "Verbindung getrennt. Ausführung des Companion-Befehls unbestätigt.",
            )))
            management = null
            contactCompletion = null
            handler.removeCallbacks(pollTelemetry)
            handler.removeCallbacks(resumeMessageSync)
            clearTimeout()
            searches.disconnect(discoverSession, message)
            traceTimer?.let(handler::removeCallbacks)
            activeTrace()?.let { run ->
                updateTrace(run.copy(
                    phase = if (run.response == null) TracePhase.DISCONNECTED else TracePhase.ANSWERED,
                    message = message,
                ))
            }
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
            if (retry && connectedOnce) {
                val delaySeconds = retryDelaySeconds
                retryDelaySeconds = (delaySeconds * 2).coerceAtMost(30)
                mutableStatus.value = ConnectionStatus(
                    "Verbindung zu ${device.name} unterbrochen · neuer Versuch in ${delaySeconds} s",
                    active = true,
                    messageSyncError = syncError,
                )
                handler.postDelayed(reconnect, delaySeconds * 1_000)
            } else {
                mutableStatus.value = ConnectionStatus(message, error = error, messageSyncError = syncError)
                stopReceiving()
            }
        }
    }
}
