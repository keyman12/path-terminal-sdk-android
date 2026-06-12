package tech.path2ai.sdk.psdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tech.path2ai.sdk.core.ReceiptData

/**
 * Receipt cache for the Verifone backend.
 *
 * PSDK receipts arrive once, WITH the completion event, and cannot be
 * re-fetched from the terminal — so the adapter caches the mapped
 * ReceiptData here and serves PathTerminal's getReceiptData() from it.
 * The last [maxEntries] receipts are also persisted (they're already
 * masked per scheme rules) so they survive an app restart.
 */
class ReceiptCache(
    private val store: KeyValueStore,
    private val maxEntries: Int = 20,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Serializable
    private data class StoredReceipt(val ts: Long, val receipt: ReceiptData)

    private val json = Json { ignoreUnknownKeys = true }
    private val memory = LinkedHashMap<String, ReceiptData>()

    fun put(receipt: ReceiptData) {
        synchronized(memory) {
            memory[receipt.transactionId] = receipt
            while (memory.size > maxEntries) memory.remove(memory.keys.first())
        }
        try {
            val wrapped = StoredReceipt(ts = clock(), receipt = receipt)
            store.put(receipt.transactionId, json.encodeToString(StoredReceipt.serializer(), wrapped))
            prune()
        } catch (_: Exception) {
            // persistence is best-effort; the in-memory copy still serves
        }
    }

    fun get(transactionId: String): ReceiptData? {
        synchronized(memory) { memory[transactionId] }?.let { return it }
        val raw = store.get(transactionId) ?: return null
        return try {
            json.decodeFromString(StoredReceipt.serializer(), raw).receipt
        } catch (_: Exception) {
            null
        }
    }

    private fun prune() {
        val keys = store.keys()
        if (keys.size <= maxEntries) return
        val byAge = keys.mapNotNull { k ->
            val ts = try {
                json.decodeFromString(StoredReceipt.serializer(), store.get(k) ?: return@mapNotNull null).ts
            } catch (_: Exception) { 0L }
            k to ts
        }.sortedBy { it.second }
        byAge.take(keys.size - maxEntries).forEach { store.remove(it.first) }
    }

    companion object {
        const val PREFS_NAME = "path_psdk_receipts"
    }
}
