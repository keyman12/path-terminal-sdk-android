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
            // PENDING_DEVICE may resolve straight to a final state: adapters
            // return one result per operation (they don't emit the
            // intermediate CARD_*/AUTHORIZING steps), so the facade's
            // PENDING_DEVICE → result.state transition must be valid or the
            // final TransactionStateChanged event is silently dropped.
            TransactionState.PENDING_DEVICE to setOf(
                TransactionState.CARD_PRESENTED,
                TransactionState.CARD_READ,
                TransactionState.AUTHORIZING,
                TransactionState.REFUND_PENDING,
                TransactionState.REVERSAL_PENDING,
                TransactionState.PREAUTH_PENDING,
                TransactionState.CAPTURE_PENDING,
                TransactionState.APPROVED,
                TransactionState.DECLINED,
                TransactionState.CANCELLED,
                TransactionState.TIMED_OUT,
                TransactionState.CUSTOMER_TIMEOUT,
                TransactionState.FAILED
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
            TransactionState.REVERSAL_PENDING to setOf(
                TransactionState.REVERSED,
                TransactionState.DECLINED,
                TransactionState.TIMED_OUT,
                TransactionState.FAILED
            ),
            TransactionState.REFUND_PENDING to setOf(
                TransactionState.REFUNDED,
                TransactionState.DECLINED,
                TransactionState.CANCELLED,
                TransactionState.TIMED_OUT,
                TransactionState.FAILED
            ),
            // Pre-auth: place a hold (PREAUTH_PENDING -> PREAUTH_HELD) and capture
            // it (CAPTURE_PENDING -> CAPTURED). Adjust reuses PREAUTH_PENDING ->
            // PREAUTH_HELD; release reuses REVERSAL_PENDING -> REVERSED.
            TransactionState.PREAUTH_PENDING to setOf(
                TransactionState.PREAUTH_HELD,
                TransactionState.DECLINED,
                TransactionState.CANCELLED,
                TransactionState.TIMED_OUT,
                TransactionState.CUSTOMER_TIMEOUT,
                TransactionState.FAILED
            ),
            TransactionState.PREAUTH_HELD to setOf(
                TransactionState.CAPTURE_PENDING,
                TransactionState.REVERSAL_PENDING,
                TransactionState.PREAUTH_PENDING
            ),
            TransactionState.CAPTURE_PENDING to setOf(
                TransactionState.CAPTURED,
                TransactionState.DECLINED,
                TransactionState.TIMED_OUT,
                TransactionState.FAILED
            ),
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
            TransactionState.CAPTURED,
            TransactionState.SETTLED
        )

    /** Whether the transaction has reached a successful outcome */
    val isApproved: Boolean
        get() = currentState in setOf(
            TransactionState.APPROVED,
            TransactionState.REFUNDED,
            TransactionState.REVERSED,
            TransactionState.PREAUTH_HELD,
            TransactionState.CAPTURED,
            TransactionState.SETTLED
        )
}
