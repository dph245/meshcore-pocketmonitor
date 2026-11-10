package de.meshcore.pocketmonitor.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal sealed interface ContactListResult {
    data class Complete(val contacts: List<CompanionContact>, val newestModifiedSeconds: Long) : ContactListResult
    data class Failed(val message: String) : ContactListResult
}

/** One unfiltered GET_CONTACTS exchange. Notifications may precede the GATT write callback. */
internal class ContactListTransfer {
    private var writeAcknowledged = false
    private var expectedCount: Long? = null
    private val contacts = linkedMapOf<String, CompanionContact>()
    private var invalid = false
    private var terminal: ContactListResult? = null
    val result: ContactListResult? get() = terminal.takeIf { writeAcknowledged }

    fun acknowledgeWrite() {
        writeAcknowledged = true
    }

    /** Returns true only for frames belonging to this exchange, for the inactivity timeout. */
    fun receive(frame: ByteArray): Boolean {
        if (terminal != null) return false
        when (frame.firstOrNull()?.toInt()?.and(0xff)) {
            CompanionProtocol.CONTACTS_START -> {
                if (expectedCount != null || frame.size != 5) invalid = true
                else expectedCount = frame.readUnsignedInt()
            }
            CompanionProtocol.CONTACT -> {
                if (!invalid) {
                    val contact = CompanionContact.decode(frame)
                    val count = expectedCount
                    if (contact == null || count == null || contacts.size >= count ||
                        contacts.putIfAbsent(contact.publicKey, contact) != null
                    ) invalid = true
                }
            }
            CompanionProtocol.CONTACTS_END -> {
                terminal = if (!invalid && frame.size == 5 && expectedCount == contacts.size.toLong()) {
                    ContactListResult.Complete(contacts.values.toList(), frame.readUnsignedInt())
                } else {
                    ContactListResult.Failed("Kontaktliste unvollständig oder fehlerhaft.")
                }
            }
            CompanionProtocol.ERROR, CompanionProtocol.DISABLED -> {
                val code = frame.getOrNull(1)?.toInt()?.and(0xff)
                terminal = ContactListResult.Failed("Companion hat die Kontaktabfrage abgelehnt" +
                    (code?.let { " (Code $it)" } ?: "") + ".")
            }
            else -> return false
        }
        return true
    }

    private fun ByteArray.readUnsignedInt(): Long =
        ByteBuffer.wrap(this, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int.toUInt().toLong()
}
