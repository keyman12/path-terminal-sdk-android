package tech.path2ai.sdk.emulator

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import tech.path2ai.sdk.core.TransactionState

class EmulatorWireJsonMappingTests {

    @Test
    fun `maps approved sale response`() {
        val json = """{"status":"success","txn_status":"approved","txn_id":"emul-001","amount":1500,"currency":"GBP","card_last_four":"1234","receipt_available":true}"""
        val result = EmulatorWireJsonMapping.mapResponse(json, "req-1")
        assertEquals(TransactionState.APPROVED, result.state)
        assertEquals("emul-001", result.transactionId)
        assertEquals(1500, result.amountMinor)
        assertEquals("GBP", result.currency)
        assertEquals("1234", result.cardLastFour)
        assertTrue(result.receiptAvailable)
        assertNull(result.error)
    }

    @Test
    fun `maps declined response`() {
        val json = """{"status":"success","txn_status":"declined","txn_id":"emul-002","amount":500,"currency":"GBP","error":"Insufficient funds"}"""
        val result = EmulatorWireJsonMapping.mapResponse(json, "req-2")
        assertEquals(TransactionState.DECLINED, result.state)
        assertNotNull(result.error)
        assertEquals("Insufficient funds", result.error?.message)
    }

    @Test
    fun `maps timed out response`() {
        val json = """{"status":"success","txn_status":"timed_out","amount":1000,"currency":"GBP"}"""
        val result = EmulatorWireJsonMapping.mapResponse(json, "req-3")
        assertEquals(TransactionState.TIMED_OUT, result.state)
    }

    @Test
    fun `maps cancelled response`() {
        val json = """{"status":"success","txn_status":"cancelled","amount":750,"currency":"GBP"}"""
        val result = EmulatorWireJsonMapping.mapResponse(json, "req-4")
        assertEquals(TransactionState.CANCELLED, result.state)
    }

    @Test
    fun `maps refunded response`() {
        val json = """{"status":"success","txn_status":"refunded","txn_id":"emul-ref-001","amount":500,"currency":"GBP"}"""
        val result = EmulatorWireJsonMapping.mapResponse(json, "req-5")
        assertEquals(TransactionState.REFUNDED, result.state)
        assertEquals("emul-ref-001", result.transactionId)
        assertTrue(result.isApproved)
    }

    @Test
    fun `maps error status response`() {
        val json = """{"status":"error","txn_status":"failed","error":"Terminal fault"}"""
        val result = EmulatorWireJsonMapping.mapResponse(json, "req-6")
        assertEquals(TransactionState.FAILED, result.state)
        assertNotNull(result.error)
        assertEquals("Terminal fault", result.error?.message)
    }

    @Test
    fun `handles transaction_status field alias`() {
        val json = """{"status":"success","transaction_status":"approved","txn_id":"emul-003","amount":2000,"currency":"GBP"}"""
        val result = EmulatorWireJsonMapping.mapResponse(json, "req-7")
        assertEquals(TransactionState.APPROVED, result.state)
    }

    @Test
    fun `maps unknown status to FAILED`() {
        val json = """{"status":"success","txn_status":"some_unknown_state","amount":100,"currency":"GBP"}"""
        val result = EmulatorWireJsonMapping.mapResponse(json, "req-8")
        assertEquals(TransactionState.FAILED, result.state)
    }

    @Test
    fun `missing fields use defaults`() {
        val json = """{"status":"success","txn_status":"approved"}"""
        val result = EmulatorWireJsonMapping.mapResponse(json, "req-9")
        assertEquals(TransactionState.APPROVED, result.state)
        assertEquals(0, result.amountMinor)
        assertEquals("GBP", result.currency)
        assertNull(result.cardLastFour)
        assertFalse(result.receiptAvailable)
    }
}
