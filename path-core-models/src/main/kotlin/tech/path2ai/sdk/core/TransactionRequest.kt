package tech.path2ai.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TransactionRequest(
    @SerialName("amount_minor") val amountMinor: Int,
    val currency: String,
    @SerialName("tip_minor") val tipMinor: Int? = null,
    @SerialName("original_transaction_id") val originalTransactionId: String? = null,
    @SerialName("original_request_id") val originalRequestId: String? = null,
    val envelope: RequestEnvelope
) {
    companion object {
        fun sale(
            amountMinor: Int,
            currency: String,
            tipMinor: Int? = null,
            envelope: RequestEnvelope
        ): TransactionRequest = TransactionRequest(
            amountMinor = amountMinor,
            currency = currency,
            tipMinor = tipMinor,
            originalTransactionId = null,
            originalRequestId = null,
            envelope = envelope
        )

        fun refund(
            amountMinor: Int,
            currency: String,
            originalTransactionId: String? = null,
            originalRequestId: String? = null,
            envelope: RequestEnvelope
        ): TransactionRequest = TransactionRequest(
            amountMinor = amountMinor,
            currency = currency,
            tipMinor = null,
            originalTransactionId = originalTransactionId,
            originalRequestId = originalRequestId,
            envelope = envelope
        )
    }
}
