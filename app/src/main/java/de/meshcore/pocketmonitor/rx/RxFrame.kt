package de.meshcore.pocketmonitor.rx

internal data class RxFrame(val timeMs: Long, val bytes: ByteArray) {
    val code: Int? get() = bytes.firstOrNull()?.toInt()?.and(0xff)
}

internal data class RxRow(val sequence: Long, val frame: RxFrame) {
    val timeMs: Long get() = frame.timeMs
    val code: Int? get() = frame.code
}

internal data class JournalSnapshot(
    val fileName: String,
    val count: Long,
    val newestFirst: List<RxRow>,
    val newestRadioFirst: List<RxRow>,
    val error: String?,
    val hasOlderRaw: Boolean = false,
    val hasOlderRadio: Boolean = false,
    val revision: Long = -1,
    val before: Long? = null,
)
