package de.meshcore.pocketmonitor.ble

import de.meshcore.pocketmonitor.protocol.ContactListResult
import de.meshcore.pocketmonitor.protocol.HopResolver

internal class ContactBook(val list: ContactListResult.Complete) {
    val resolver = HopResolver(list.contacts)
}

internal data class ContactStatus(
    val journalName: String? = null,
    val book: ContactBook? = null,
    val canRefresh: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
) {
    fun resolverFor(fileName: String?): HopResolver =
        book?.takeIf { journalName == fileName }?.resolver ?: HopResolver.EMPTY

    fun completed(result: ContactListResult): ContactStatus = when (result) {
        is ContactListResult.Complete -> copy(book = ContactBook(result), canRefresh = true, loading = false, error = null)
        is ContactListResult.Failed -> copy(canRefresh = true, loading = false, error = result.message)
    }
}
