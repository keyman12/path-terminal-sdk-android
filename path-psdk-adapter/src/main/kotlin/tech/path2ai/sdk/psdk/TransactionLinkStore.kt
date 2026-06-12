package tech.path2ai.sdk.psdk

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Tiny string key/value abstraction so the stores can be unit-tested without
 * Android (SharedPreferences in production, a map in tests).
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

class InMemoryKeyValueStore : KeyValueStore {
    private val map = LinkedHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}

class SharedPrefsKeyValueStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
    override fun keys(): Set<String> = prefs.all.keys.toSet()
}

/**
 * Persistent Path-transactionId → PSDK appSpecificData link store.
 *
 * The PSDK links refunds/voids to their original sale via an opaque
 * appSpecificData token (≤255 chars, captured from the approved sale). The
 * Path surface links by originalTransactionId, and refunds legitimately
 * happen hours or days later — across app restarts — so the mapping must
 * survive the process. The tokens are Verifone link references, not
 * cardholder data.
 *
 * Entries are pruned oldest-first past [maxEntries].
 */
class TransactionLinkStore(
    private val store: KeyValueStore,
    private val maxEntries: Int = 1000,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Serializable
    private data class LinkRecord(val asd: String, val ts: Long)

    private val json = Json { ignoreUnknownKeys = true }

    fun saveLink(transactionId: String, appSpecificData: String) {
        val record = LinkRecord(asd = appSpecificData, ts = clock())
        store.put(transactionId, json.encodeToString(LinkRecord.serializer(), record))
        prune()
    }

    fun linkFor(transactionId: String): String? {
        val raw = store.get(transactionId) ?: return null
        return try {
            json.decodeFromString(LinkRecord.serializer(), raw).asd.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun prune() {
        val keys = store.keys()
        if (keys.size <= maxEntries) return
        val byAge = keys.mapNotNull { k ->
            val ts = try {
                json.decodeFromString(LinkRecord.serializer(), store.get(k) ?: return@mapNotNull null).ts
            } catch (_: Exception) { 0L }
            k to ts
        }.sortedBy { it.second }
        val excess = keys.size - maxEntries
        byAge.take(excess).forEach { store.remove(it.first) }
    }

    companion object {
        const val PREFS_NAME = "path_psdk_links"
    }
}
