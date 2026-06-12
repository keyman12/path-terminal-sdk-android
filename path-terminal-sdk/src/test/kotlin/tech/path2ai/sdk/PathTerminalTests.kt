package tech.path2ai.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import tech.path2ai.sdk.core.*
import tech.path2ai.sdk.mock.MockPathTerminalAdapter

@OptIn(ExperimentalCoroutinesApi::class)
class PathTerminalTests {

    @Test
    fun `discoverDevices emits scanning then idle`() = runTest {
        val mock = MockPathTerminalAdapter()
        val terminal = PathTerminal(mock)

        val events = mutableListOf<PathTerminalEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            terminal.events.collect { events.add(it) }
        }

        terminal.discoverDevices()

        assertTrue(events.size >= 3, "Expected at least 3 events, got ${events.size}")
        assertEquals(ConnectionState.Scanning, (events[0] as PathTerminalEvent.ConnectionStateChanged).state)
        assertTrue(events[1] is PathTerminalEvent.DeviceDiscovered)
        assertEquals(ConnectionState.Idle, (events[2] as PathTerminalEvent.ConnectionStateChanged).state)

        job.cancel()
    }

    @Test
    fun `connect emits connecting then connected`() = runTest {
        val mock = MockPathTerminalAdapter()
        val terminal = PathTerminal(mock)

        val events = mutableListOf<PathTerminalEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            terminal.events.collect { events.add(it) }
        }

        val device = mock.discoverDevices().first()
        terminal.connect(device)

        val connEvents = events.filterIsInstance<PathTerminalEvent.ConnectionStateChanged>()
        assertTrue(connEvents.any { it.state == ConnectionState.Connecting })
        assertTrue(connEvents.any { it.state == ConnectionState.Connected })
        assertTrue(terminal.isConnected)

        job.cancel()
    }

    @Test
    fun `disconnect emits disconnected`() = runTest {
        val mock = MockPathTerminalAdapter()
        val terminal = PathTerminal(mock)

        val device = mock.discoverDevices().first()
        terminal.connect(device)

        val events = mutableListOf<PathTerminalEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            terminal.events.collect { events.add(it) }
        }

        terminal.disconnect()
        assertFalse(terminal.isConnected)

        val connEvents = events.filterIsInstance<PathTerminalEvent.ConnectionStateChanged>()
        assertTrue(connEvents.any { it.state == ConnectionState.Disconnected })

        job.cancel()
    }

    @Test
    fun `sale emits transaction state changes`() = runTest {
        val mock = MockPathTerminalAdapter()
        val terminal = PathTerminal(mock)

        val events = mutableListOf<PathTerminalEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            terminal.events.collect { events.add(it) }
        }

        val envelope = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        val request = TransactionRequest.sale(amountMinor = 1000, currency = "GBP", envelope = envelope)
        val result = terminal.sale(request)

        assertEquals(TransactionState.APPROVED, result.state)
        assertTrue(result.isApproved)

        // State machine emits PENDING_DEVICE; the jump to APPROVED is rejected
        // by the state machine (no intermediate states from mock) but the result
        // is still APPROVED — this matches iOS behaviour.
        val txnEvents = events.filterIsInstance<PathTerminalEvent.TransactionStateChanged>()
        assertTrue(txnEvents.any { it.state == TransactionState.PENDING_DEVICE })

        job.cancel()
    }

    @Test
    fun `refund emits REFUNDED state`() = runTest {
        val mock = MockPathTerminalAdapter()
        val terminal = PathTerminal(mock)

        val events = mutableListOf<PathTerminalEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            terminal.events.collect { events.add(it) }
        }

        val envelope = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        val request = TransactionRequest.refund(amountMinor = 500, currency = "GBP", envelope = envelope)
        val result = terminal.refund(request)

        assertEquals(TransactionState.REFUNDED, result.state)
        assertTrue(result.isApproved)

        val txnEvents = events.filterIsInstance<PathTerminalEvent.TransactionStateChanged>()
        assertTrue(txnEvents.any { it.state == TransactionState.REFUNDED })

        job.cancel()
    }

    @Test
    fun `isConnected reflects adapter state`() = runTest {
        val mock = MockPathTerminalAdapter()
        val terminal = PathTerminal(mock)
        assertFalse(terminal.isConnected)

        val device = mock.discoverDevices().first()
        terminal.connect(device)
        assertTrue(terminal.isConnected)

        terminal.disconnect()
        assertFalse(terminal.isConnected)
    }

    @Test
    fun `voidTransaction returns reversed and emits transaction states`() = runTest {
        val mock = MockPathTerminalAdapter()
        val terminal = PathTerminal(mock)

        val events = mutableListOf<PathTerminalEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            terminal.events.collect { events.add(it) }
        }

        val envelope = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        val request = TransactionRequest.voidTransaction(
            originalTransactionId = "orig-txn-99",
            envelope = envelope
        )
        val result = terminal.voidTransaction(request)

        assertEquals(TransactionState.REVERSED, result.state)
        assertTrue(result.isApproved)
        val states = events.filterIsInstance<PathTerminalEvent.TransactionStateChanged>().map { it.state }
        assertEquals(
            listOf(TransactionState.PENDING_DEVICE, TransactionState.REVERSAL_PENDING, TransactionState.REVERSED),
            states
        )

        job.cancel()
    }

    @Test
    fun `voidTransaction without originalTransactionId throws validation error`() = runTest {
        val mock = MockPathTerminalAdapter()
        val terminal = PathTerminal(mock)

        val envelope = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        // A request built without a link (e.g. a plain sale request) must be rejected
        val request = TransactionRequest.sale(amountMinor = 1000, currency = "GBP", envelope = envelope)

        val error = org.junit.jupiter.api.assertThrows<PathError> {
            terminal.voidTransaction(request)
        }
        assertEquals(PathErrorCode.VALIDATION, error.code)
    }
}
