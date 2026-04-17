package tech.path2ai.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TransactionRequest(
    @SerialName("amount_minor") val amountMinor: Int,
    val currency: String,
    @SerialName("tip_minor") val tipMinor: Int? = null,
    /**
     * When true, the terminal asks the customer to pick a tip before the
     * card tap (10% / 15% / 20% / No tip on the current emulator firmware).
     * The result's [TransactionResult.tipAmountMinor] and
     * [TransactionResult.tipPercentX10] reflect what they chose.
     *
     * If both [tipMinor] (pre-entered) and [promptForTip] are set, the
     * customer's choice replaces the pre-entered value — the customer-
     * facing flow is the authoritative one.
     */
    @SerialName("prompt_for_tip") val promptForTip: Boolean = false,
    @SerialName("original_transaction_id") val originalTransactionId: String? = null,
    @SerialName("original_request_id") val originalRequestId: String? = null,
    val envelope: RequestEnvelope
) {
    companion object {
        fun sale(
            amountMinor: Int,
            currency: String,
            tipMinor: Int? = null,
            promptForTip: Boolean = false,
            envelope: RequestEnvelope
        ): TransactionRequest = TransactionRequest(
            amountMinor = amountMinor,
            currency = currency,
            tipMinor = tipMinor,
            promptForTip = promptForTip,
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
