package de.meshcore.pocketmonitor.protocol

internal sealed interface CompanionEvent {
    data class RadioRx(
        val snrDb: Double?,
        val rssiDbm: Int?,
        val radioBytes: ByteArray?,
        val issue: String? = null,
    ) : CompanionEvent

    data class Other(val code: Int?) : CompanionEvent
}

internal object CompanionEventDecoder {
    fun decode(notification: ByteArray): CompanionEvent {
        val code = notification.firstOrNull()?.toInt()?.and(0xff)
        if (code != CompanionProtocol.RF_LOG) return CompanionEvent.Other(code)

        // Companion MyMesh::logRxRaw: code, int8(SNR * 4), int8(RSSI), RF bytes.
        return CompanionEvent.RadioRx(
            snrDb = notification.getOrNull(1)?.toInt()?.div(4.0),
            rssiDbm = notification.getOrNull(2)?.toInt(),
            radioBytes = if (notification.size >= 3) notification.copyOfRange(3, notification.size) else null,
            issue = when {
                notification.size < 3 -> "Companion-RX-Metadaten unvollständig."
                notification.size == 3 -> "Funkpaket fehlt."
                else -> null
            },
        )
    }
}
