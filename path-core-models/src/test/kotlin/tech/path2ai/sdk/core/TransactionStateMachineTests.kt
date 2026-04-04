package tech.path2ai.sdk.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class TransactionStateMachineTests {

    @Test
    fun `initial state is CREATED`() {
        val sm = TransactionStateMachine()
        assertEquals(TransactionState.CREATED, sm.currentState)
    }

    @Test
    fun `valid transition from CREATED to PENDING_DEVICE`() {
        val sm = TransactionStateMachine()
        assertTrue(sm.transition(TransactionState.PENDING_DEVICE))
        assertEquals(TransactionState.PENDING_DEVICE, sm.currentState)
    }

    @Test
    fun `invalid transition from CREATED to APPROVED`() {
        val sm = TransactionStateMachine()
        assertFalse(sm.transition(TransactionState.APPROVED))
        assertEquals(TransactionState.CREATED, sm.currentState)
    }

    @Test
    fun `full sale flow transitions`() {
        val sm = TransactionStateMachine()
        assertTrue(sm.transition(TransactionState.PENDING_DEVICE))
        assertTrue(sm.transition(TransactionState.CARD_PRESENTED))
        assertTrue(sm.transition(TransactionState.CARD_READ))
        assertTrue(sm.transition(TransactionState.AUTHORIZING))
        assertTrue(sm.transition(TransactionState.APPROVED))
        assertEquals(TransactionState.APPROVED, sm.currentState)
    }

    @Test
    fun `declined outcome`() {
        val sm = TransactionStateMachine()
        sm.transition(TransactionState.PENDING_DEVICE)
        sm.transition(TransactionState.CARD_PRESENTED)
        sm.transition(TransactionState.CARD_READ)
        sm.transition(TransactionState.AUTHORIZING)
        assertTrue(sm.transition(TransactionState.DECLINED))
        assertEquals(TransactionState.DECLINED, sm.currentState)
        assertTrue(sm.isInTerminalState)
        assertFalse(sm.isApproved)
    }

    @Test
    fun `timed out outcome`() {
        val sm = TransactionStateMachine()
        sm.transition(TransactionState.PENDING_DEVICE)
        sm.transition(TransactionState.CARD_PRESENTED)
        sm.transition(TransactionState.CARD_READ)
        sm.transition(TransactionState.AUTHORIZING)
        assertTrue(sm.transition(TransactionState.TIMED_OUT))
        assertTrue(sm.isInTerminalState)
    }

    @Test
    fun `refund flow from PENDING_DEVICE`() {
        val sm = TransactionStateMachine()
        sm.transition(TransactionState.PENDING_DEVICE)
        assertTrue(sm.transition(TransactionState.REFUND_PENDING))
        assertTrue(sm.transition(TransactionState.REFUNDED))
        assertTrue(sm.isInTerminalState)
        assertTrue(sm.isApproved)
    }

    @Test
    fun `refund flow from APPROVED`() {
        val sm = TransactionStateMachine()
        sm.transition(TransactionState.PENDING_DEVICE)
        sm.transition(TransactionState.CARD_PRESENTED)
        sm.transition(TransactionState.CARD_READ)
        sm.transition(TransactionState.AUTHORIZING)
        sm.transition(TransactionState.APPROVED)
        assertTrue(sm.transition(TransactionState.REFUND_PENDING))
        assertTrue(sm.transition(TransactionState.REFUNDED))
        assertTrue(sm.isApproved)
    }

    @Test
    fun `reversal flow`() {
        val sm = TransactionStateMachine()
        sm.transition(TransactionState.PENDING_DEVICE)
        sm.transition(TransactionState.CARD_PRESENTED)
        sm.transition(TransactionState.CARD_READ)
        sm.transition(TransactionState.AUTHORIZING)
        sm.transition(TransactionState.APPROVED)
        assertTrue(sm.transition(TransactionState.REVERSAL_PENDING))
        assertTrue(sm.transition(TransactionState.REVERSED))
        assertTrue(sm.isInTerminalState)
        assertTrue(sm.isApproved)
    }

    @Test
    fun `settlement flow`() {
        val sm = TransactionStateMachine()
        sm.transition(TransactionState.PENDING_DEVICE)
        sm.transition(TransactionState.CARD_PRESENTED)
        sm.transition(TransactionState.CARD_READ)
        sm.transition(TransactionState.AUTHORIZING)
        sm.transition(TransactionState.APPROVED)
        assertTrue(sm.transition(TransactionState.SETTLEMENT_PENDING))
        assertTrue(sm.transition(TransactionState.SETTLED))
        assertTrue(sm.isInTerminalState)
        assertTrue(sm.isApproved)
    }

    @Test
    fun `transitionOrThrow throws on invalid`() {
        val sm = TransactionStateMachine()
        val error = assertThrows<PathError> {
            sm.transitionOrThrow(TransactionState.APPROVED)
        }
        assertEquals(PathErrorCode.PROTOCOL_MISMATCH, error.code)
        assertTrue(error.message.contains("Invalid state transition"))
    }

    @Test
    fun `transition callback fires`() {
        val transitions = mutableListOf<TransactionStateTransition>()
        val sm = TransactionStateMachine { transitions.add(it) }
        sm.transition(TransactionState.PENDING_DEVICE)
        sm.transition(TransactionState.CARD_PRESENTED)

        assertEquals(2, transitions.size)
        assertEquals(TransactionState.CREATED, transitions[0].from)
        assertEquals(TransactionState.PENDING_DEVICE, transitions[0].to)
        assertEquals(TransactionState.PENDING_DEVICE, transitions[1].from)
        assertEquals(TransactionState.CARD_PRESENTED, transitions[1].to)
    }

    @Test
    fun `APPROVED is not terminal state`() {
        val sm = TransactionStateMachine(initialState = TransactionState.APPROVED)
        assertFalse(sm.isInTerminalState)
        assertTrue(sm.isApproved)
    }

    @Test
    fun `cannot transition from terminal states`() {
        for (state in listOf(
            TransactionState.DECLINED, TransactionState.CANCELLED,
            TransactionState.TIMED_OUT, TransactionState.FAILED,
            TransactionState.REVERSED, TransactionState.REFUNDED,
            TransactionState.SETTLED
        )) {
            val sm = TransactionStateMachine(initialState = state)
            assertFalse(sm.transition(TransactionState.APPROVED))
            assertTrue(sm.isInTerminalState)
        }
    }
}
