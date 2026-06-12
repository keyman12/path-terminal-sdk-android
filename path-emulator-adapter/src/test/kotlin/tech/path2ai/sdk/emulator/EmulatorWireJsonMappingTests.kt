package tech.path2ai.sdk.emulator

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import tech.path2ai.sdk.core.PathErrorCode
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

    // ── Tipping (wire v1.1) ──────────────────────────────────────────────

    @Test
    fun `sale with tip breakdown`() {
        val json = """{"status":"approved","amount":2141,"base_amount":1946,"tip_amount":195,"total_amount":2141,"tip_percent_x10":100,"currency":"GBP","txn_id":"txn-tip-1"}"""
        val r = EmulatorWireJsonMapping.mapResponse(json, "req-tip-1")
        assertEquals(TransactionState.APPROVED, r.state)
        assertEquals(2141, r.amountMinor)
        assertEquals(1946, r.baseAmountMinor)
        assertEquals(195, r.tipAmountMinor)
        assertEquals(2141, r.totalAmountMinor)
        assertEquals(100, r.tipPercentX10)
        // Legacy tipMinor reflects the tip when present
        assertEquals(195, r.tipMinor)
    }

    @Test
    fun `sale no tip`() {
        val json = """{"status":"approved","amount":1946,"base_amount":1946,"tip_amount":0,"total_amount":1946,"currency":"GBP","txn_id":"txn-notip-1"}"""
        val r = EmulatorWireJsonMapping.mapResponse(json, "req-notip-1")
        assertEquals(TransactionState.APPROVED, r.state)
        assertEquals(1946, r.baseAmountMinor)
        assertEquals(0, r.tipAmountMinor)
        assertEquals(1946, r.totalAmountMinor)
        assertNull(r.tipPercentX10)
        // Legacy tipMinor stays null when no tip added
        assertNull(r.tipMinor)
    }

    @Test
    fun `sale customer timeout`() {
        val json = """{"status":"error","error":"customer_timeout","message":"Customer did not respond to tip prompt","base_amount":1946,"currency":"GBP"}"""
        val r = EmulatorWireJsonMapping.mapResponse(json, "req-timeout-1")
        assertEquals(TransactionState.CUSTOMER_TIMEOUT, r.state)
        assertNotNull(r.error)
        assertEquals(PathErrorCode.CUSTOMER_TIMEOUT, r.error?.code)
        assertEquals(true, r.error?.recoverable)
    }

    @Test
    fun `back-compat no breakdown fields`() {
        // Older emulator firmware — only `amount`, no base_amount / tip_amount.
        val json = """{"status":"approved","amount":1946,"currency":"GBP","txn_id":"txn-legacy"}"""
        val r = EmulatorWireJsonMapping.mapResponse(json, "req-legacy-1")
        assertEquals(TransactionState.APPROVED, r.state)
        assertEquals(1946, r.amountMinor)
        // No breakdown from the wire → base = total, tip = 0
        assertEquals(1946, r.baseAmountMinor)
        assertEquals(0, r.tipAmountMinor)
        assertEquals(1946, r.totalAmountMinor)
        assertNull(r.tipPercentX10)
    }

    @Test
    fun `back-compat legacy tip field`() {
        // Pre-v1.1 firmware — only the legacy `tip` field, no explicit base.
        val json = """{"status":"approved","amount":2141,"tip":195,"currency":"GBP","txn_id":"txn-legacy-tip"}"""
        val r = EmulatorWireJsonMapping.mapResponse(json, "req-legacy-tip")
        assertEquals(195, r.tipAmountMinor)
        // Without explicit base_amount we derive: base = total - tip
        assertEquals(1946, r.baseAmountMinor)
        assertEquals(2141, r.totalAmountMinor)
    }

    // ---- wire v1.2: void + declined-with-reason ----

    @Test
    fun `maps void reversed response`() {
        val json = """{"type":"result","cmd":"Void","status":"reversed","txn_id":"void-001","original_txn_id":"sale-001","amount":2141,"base_amount":1946,"tip_amount":195,"total_amount":2141,"currency":"GBP","card_last_four":"1234","auth_code":"112233","receipt_available":true}"""
        val r = EmulatorWireJsonMapping.mapResponse(json, "req-void-1")
        assertEquals(TransactionState.REVERSED, r.state)
        assertEquals("void-001", r.transactionId)
        assertEquals(2141, r.amountMinor)
        assertTrue(r.receiptAvailable)
        assertTrue(r.isApproved)
        assertNull(r.error)
    }

    @Test
    fun `maps declined sale with decline_reason and no auth code`() {
        val json = """{"type":"result","cmd":"Sale","status":"declined","decline_reason":"do_not_honour","txn_id":"sale-d1","amount":1946,"base_amount":1946,"tip_amount":0,"total_amount":1946,"currency":"GBP","card_last_four":"1234","receipt_available":true}"""
        val r = EmulatorWireJsonMapping.mapResponse(json, "req-decl-1")
        assertEquals(TransactionState.DECLINED, r.state)
        assertEquals(PathErrorCode.DECLINE, r.error?.code)
        assertEquals("do_not_honour", r.error?.adapterErrorCode)
        // Declines still produce receipts on v1.2 firmware
        assertTrue(r.receiptAvailable)
        assertFalse(r.isApproved)
    }

    @Test
    fun `maps declined refund`() {
        val json = """{"type":"result","cmd":"Refund","status":"declined","decline_reason":"do_not_honour","txn_id":"ref-d1","amount":500,"currency":"GBP","receipt_available":true}"""
        val r = EmulatorWireJsonMapping.mapResponse(json, "req-decl-2")
        // The Refund->REFUNDED special case must not swallow declines
        assertEquals(TransactionState.DECLINED, r.state)
        assertEquals(PathErrorCode.DECLINE, r.error?.code)
    }

    @Test
    fun `maps void declined already_voided`() {
        val json = """{"type":"result","cmd":"Void","status":"declined","decline_reason":"already_voided","original_txn_id":"sale-001","amount":2141,"currency":"GBP","receipt_available":false}"""
        val r = EmulatorWireJsonMapping.mapResponse(json, "req-void-2")
        assertEquals(TransactionState.DECLINED, r.state)
        assertEquals("already_voided", r.error?.adapterErrorCode)
    }

    @Test
    fun `maps void error transaction_not_found`() {
        val json = """{"type":"result","cmd":"Void","status":"error","error":"transaction_not_found","message":"Original transaction not found"}"""
        val r = EmulatorWireJsonMapping.mapResponse(json, "req-void-3")
        assertEquals(TransactionState.FAILED, r.state)
        assertEquals(PathErrorCode.TERMINAL_FAULT, r.error?.code)
        assertEquals("transaction_not_found", r.error?.adapterErrorCode)
    }
}
