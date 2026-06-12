package tech.path2ai.sdk.psdk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TransactionLinkStoreTests {

    @Test
    fun `stores and resolves a link token`() {
        val store = TransactionLinkStore(InMemoryKeyValueStore())
        store.saveLink("txn-1", "ASD-TOKEN-1")
        assertEquals("ASD-TOKEN-1", store.linkFor("txn-1"))
    }

    @Test
    fun `unknown transaction resolves to null`() {
        val store = TransactionLinkStore(InMemoryKeyValueStore())
        assertNull(store.linkFor("nope"))
    }

    @Test
    fun `prunes oldest entries past the cap`() {
        var now = 1_000L
        val store = TransactionLinkStore(InMemoryKeyValueStore(), maxEntries = 3, clock = { now++ })
        for (i in 1..5) store.saveLink("txn-$i", "tok-$i")
        assertNull(store.linkFor("txn-1"))
        assertNull(store.linkFor("txn-2"))
        assertEquals("tok-3", store.linkFor("txn-3"))
        assertEquals("tok-5", store.linkFor("txn-5"))
    }
}
