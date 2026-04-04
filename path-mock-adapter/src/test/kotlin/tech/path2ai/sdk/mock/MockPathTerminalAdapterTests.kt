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
}
