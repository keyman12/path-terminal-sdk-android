package tech.path2ai.sdk.mock

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import tech.path2ai.sdk.core.*

class MockPathTerminalAdapterTests {

    @Test
    fun `default discover returns mock device`() = runTest {
        val adapter = MockPathTerminalAdapter()
        val devices = adapter.discoverDevices()
        assertEquals(1, devices.size)
        assertEquals("Mock Terminal", devices[0].name)
    }

    @Test
    fun `connect sets isConnected`() = runTest {
        val adapter = MockPathTerminalAdapter()
        assertFalse(adapter.isConnected)
        val device = adapter.discoverDevices().first()
        adapter.connect(device)
        assertTrue(adapter.isConnected)
    }

    @Test
    fun `connect throws when connectError set`() = runTest {
        val adapter = MockPathTerminalAdapter()
        adapter.connectError = PathError(
            code = PathErrorCode.CONNECTIVITY,
            message = "Test connection failure"
        )
        val device = adapter.discoverDevices().first()
        assertThrows<PathError> { adapter.connect(device) }
    }

    @Test
    fun `disconnect clears isConnected`() = runTest {
        val adapter = MockPathTerminalAdapter()
        val device = adapter.discoverDevices().first()
        adapter.connect(device)
        assertTrue(adapter.isConnected)
        adapter.disconnect()
        assertFalse(adapter.isConnected)
    }

    @Test
    fun `default sale returns approved`() = runTest {
        val adapter = MockPathTerminalAdapter()
        val envelope = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        val request = TransactionRequest.sale(amountMinor = 1000, currency = "GBP", envelope = envelope)
        val result = adapter.sale(request)
        assertEquals(TransactionState.APPROVED, result.state)
        assertEquals(1000, result.amountMinor)
        assertNotNull(result.transactionId)
    }

    @Test
    fun `default refund returns refunded`() = runTest {
        val adapter = MockPathTerminalAdapter()
        val envelope = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        val request = TransactionRequest.refund(amountMinor = 500, currency = "GBP", envelope = envelope)
        val result = adapter.refund(request)
        assertEquals(TransactionState.REFUNDED, result.state)
    }

    @Test
    fun `custom sale result`() = runTest {
        val adapter = MockPathTerminalAdapter()
        adapter.saleResult = Result.success(
            TransactionResult(
                transactionId = "custom-txn",
                requestId = "req",
                state = TransactionState.DECLINED,
                amountMinor = 1000,
                currency = "GBP",
                timestampUtc = "2025-01-01T00:00:00Z",
                error = PathError(code = PathErrorCode.DECLINE, message = "Insufficient funds")
            )
        )
        val envelope = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        val result = adapter.sale(TransactionRequest.sale(amountMinor = 1000, currency = "GBP", envelope = envelope))
        assertEquals(TransactionState.DECLINED, result.state)
        assertEquals("Insufficient funds", result.error?.message)
    }

    @Test
    fun `simulateHardwareDisconnect fires callback`() {
        val adapter = MockPathTerminalAdapter()
        var callbackFired = false
        adapter.onHardwareDisconnect = { callbackFired = true }
        adapter.simulateHardwareDisconnect()
        assertTrue(callbackFired)
        assertFalse(adapter.isConnected)
    }

    @Test
    fun `getReceiptData throws by default`() = runTest {
        val adapter = MockPathTerminalAdapter()
        assertThrows<PathError> { adapter.getReceiptData("txn-1") }
    }

    // ── Simulated tip responses ───────────────────────────────────────────

    @Test
    fun `sale with tip prompt no tip`() = runTest {
        val adapter = MockPathTerminalAdapter()
        adapter.simulatedTipResponse = MockPathTerminalAdapter.SimulatedTipResponse.NoTip
        val envelope = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        val request = TransactionRequest.sale(
            amountMinor = 1946, currency = "GBP", promptForTip = true, envelope = envelope
        )
        val r = adapter.sale(request)
        assertEquals(TransactionState.APPROVED, r.state)
        assertEquals(1946, r.baseAmountMinor)
        assertEquals(0, r.tipAmountMinor)
        assertEquals(1946, r.totalAmountMinor)
        assertNull(r.tipPercentX10)
    }

    @Test
    fun `sale with tip prompt picked preset`() = runTest {
        val adapter = MockPathTerminalAdapter()
        // Customer picked 15% on £19.46 — tip = ceil(1946 * 15 / 100) = 292
        adapter.simulatedTipResponse = MockPathTerminalAdapter.SimulatedTipResponse.PickedPreset(
            percentX10 = 150,
            amountMinor = 292
        )
        val envelope = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        val request = TransactionRequest.sale(
            amountMinor = 1946, currency = "GBP", promptForTip = true, envelope = envelope
        )
        val r = adapter.sale(request)
        assertEquals(TransactionState.APPROVED, r.state)
        assertEquals(1946, r.baseAmountMinor)
        assertEquals(292, r.tipAmountMinor)
        assertEquals(2238, r.totalAmountMinor)
        assertEquals(2238, r.amountMinor)  // legacy amount = card-charged total
        assertEquals(150, r.tipPercentX10)
    }

    @Test
    fun `sale with tip prompt customer timeout`() = runTest {
        val adapter = MockPathTerminalAdapter()
        adapter.simulatedTipResponse = MockPathTerminalAdapter.SimulatedTipResponse.CustomerTimeout
        val envelope = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        val request = TransactionRequest.sale(
            amountMinor = 1946, currency = "GBP", promptForTip = true, envelope = envelope
        )
        val r = adapter.sale(request)
        assertEquals(TransactionState.CUSTOMER_TIMEOUT, r.state)
        assertEquals(PathErrorCode.CUSTOMER_TIMEOUT, r.error?.code)
        assertEquals(true, r.error?.recoverable)
    }

    /** simulatedTipResponse must be ignored when promptForTip is false. */
    @Test
    fun `sale without tip prompt ignores simulation`() = runTest {
        val adapter = MockPathTerminalAdapter()
        adapter.simulatedTipResponse = MockPathTerminalAdapter.SimulatedTipResponse.PickedPreset(200, 999)
        val envelope = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        val request = TransactionRequest.sale(
            amountMinor = 1946, currency = "GBP", envelope = envelope
        )
        val r = adapter.sale(request)
        assertEquals(TransactionState.APPROVED, r.state)
        // Pass-through — no tip added.
        assertEquals(0, r.tipAmountMinor)
    }
}
