package tech.path2ai.sdk.core

import java.time.Instant

data class TransactionStateTransition(
    val from: TransactionState,
    val to: TransactionState,
    val timestampUtc: String = Instant.now().toString()
)

class TransactionStateMachine(
    initialState: TransactionState = TransactionState.CREATED,
    private val onTransition: ((TransactionStateTransition) -> Unit)? = null
) {
    var currentState: TransactionState = initialState
        private set

    companion object {
        private val validTransitions: Map<TransactionState, Set<TransactionState>> = mapOf(
            TransactionState.CREATED to setOf(TransactionState.PENDING_DEVICE),
            TransactionState.PENDING_DEVICE to setOf(
                TransactionState.CARD_PRESENTED,
                TransactionState.CARD_READ,
                TransactionState.AUTHORIZING,
                TransactionState.REFUND_PENDING
            ),
            TransactionState.CARD_PRESENTED to setOf(TransactionState.CARD_READ),
            TransactionState.CARD_READ to setOf(TransactionState.AUTHORIZING),
            TransactionState.AUTHORIZING to setOf(
                TransactionState.APPROVED,
                TransactionState.DECLINED,
                TransactionState.CANCELLED,
                TransactionState.TIMED_OUT,
                TransactionState.FAILED
            ),
            TransactionState.APPROVED to setOf(
                TransactionState.REVERSAL_PENDING,
                TransactionState.REFUND_PENDING,
                TransactionState.SETTLEMENT_PENDING
            ),
            TransactionState.REVERSAL_PENDING to setOf(TransactionState.REVERSED),
            TransactionState.REFUND_PENDING to setOf(TransactionState.REFUNDED),
            TransactionState.SETTLEMENT_PENDING to setOf(TransactionState.SETTLED)
        )
    }

    /**
     * Attempt transition. Returns true if valid, false if rejected.
     */
    fun transition(newState: TransactionState): Boolean {
        val allowed = validTransitions[currentState] ?: emptySet()
        if (newState !in allowed) return false
        val t = TransactionStateTransition(from = currentState, to = newState)
        currentState = newState
        onTransition?.invoke(t)
        return true
    }

    /**
     * Attempt transition; throws PathError if invalid.
     */
    fun transitionOrThrow(newState: TransactionState) {
        if (!transition(newState)) {
            throw PathError(
                code = PathErrorCode.PROTOCOL_MISMATCH,
                message = "Invalid state transition from ${currentState.value} to ${newState.value}",
                recoverable = false
            )
        }
    }

    /** Whether the current state is terminal (no further transitions expected) */
    val isInTerminalState: Boolean
        get() = currentState in setOf(
            TransactionState.DECLINED,
            TransactionState.CANCELLED,
            TransactionState.TIMED_OUT,
            TransactionState.FAILED,
            TransactionState.REVERSED,
            TransactionState.REFUNDED,
            TransactionState.SETTLED
        )

    /** Whether the transaction has reached a successful outcome */
    val isApproved: Boolean
        get() = currentState in setOf(
            TransactionState.APPROVED,
            TransactionState.REFUNDED,
            TransactionState.REVERSED,
            TransactionState.SETTLED
        )
}
