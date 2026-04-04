package tech.path2ai.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TransactionResult(
    @SerialName("transaction_id") val transactionId: String? = null,
    @SerialName("request_id") val requestId: String,
    val state: TransactionState,
    @SerialName("amount_minor") val amountMinor: Int,
    val currency: String,
    @SerialName("tip_minor") val tipMinor: Int? = null,
    @SerialName("card_last_four") val cardLastFour: String? = null,
    @SerialName("receipt_available") val receiptAvailable: Boolean = false,
    @SerialName("timestamp_utc") val timestampUtc: String,
    val error: PathError? = null
) {
    val isApproved: Boolean
        get() = state == TransactionState.APPROVED ||
                state == TransactionState.REFUNDED ||
                state == TransactionState.REVERSED ||
                state == TransactionState.SETTLED

    val isFinal: Boolean
        get() = state in setOf(
            TransactionState.APPROVED,
            TransactionState.DECLINED,
            TransactionState.CANCELLED,
            TransactionState.TIMED_OUT,
            TransactionState.FAILED,
            TransactionState.REVERSED,
            TransactionState.REFUNDED,
            TransactionState.SETTLED
        )
}
