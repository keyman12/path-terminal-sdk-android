package tech.path2ai.sdk.psdk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tech.path2ai.sdk.core.CardReceiptFields
import tech.path2ai.sdk.core.ReceiptData

class ReceiptCacheTests {

    private fun receipt(txnId: String): ReceiptData {
        val copy = CardReceiptFields(
            copyLabel = "MERCHANT COPY", txnType = "SALE", amount = 100, currency = "GBP",
            cardScheme = "VISA", maskedPan = "**** 1234", entryMode = "Contactless",
            aid = "A0000000031010", verification = "None", authCode = "112233",
            merchantId = "M1", terminalId = "T1", txnRef = txnId, timestamp = "now",
            status = "APPROVED"
        )
        return ReceiptData(
            transactionId = txnId,
            merchantReceipt = copy,
            customerReceipt = copy.copy(copyLabel = "CARDHOLDER COPY", retainMessage = "PLEASE RETAIN RECEIPT"),
            timestampUtc = "2026-06-13T00:00:00Z"
        )
    }

    @Test
    fun `serves a cached receipt`() {
        val cache = ReceiptCache(InMemoryKeyValueStore())
        cache.put(receipt("txn-1"))
        assertEquals("txn-1", cache.get("txn-1")?.transactionId)
        assertNull(cache.get("missing"))
    }

    @Test
    fun `survives via the persisted copy`() {
        val backing = InMemoryKeyValueStore()
        ReceiptCache(backing).put(receipt("txn-2"))
        // New cache instance over the same backing store = app restart
        val revived = ReceiptCache(backing)
        assertEquals("txn-2", revived.get("txn-2")?.transactionId)
    }

    @Test
    fun `prunes oldest persisted receipts past the cap`() {
        var now = 1_000L
        val backing = InMemoryKeyValueStore()
        val cache = ReceiptCache(backing, maxEntries = 2, clock = { now++ })
        cache.put(receipt("a")); cache.put(receipt("b")); cache.put(receipt("c"))
        val revived = ReceiptCache(backing, maxEntries = 2)
        assertNull(revived.get("a"))
        assertEquals("c", revived.get("c")?.transactionId)
    }
}
