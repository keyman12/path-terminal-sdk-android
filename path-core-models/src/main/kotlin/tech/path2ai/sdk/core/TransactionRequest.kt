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

        /**
         * Void (full reversal) of an approved sale. No amount is sent — the
         * terminal reverses the original transaction in full, mirroring how
         * semi-integrated terminals behave (e.g. Verifone processVoid).
         * [amountMinor] is fixed at 0 and ignored by adapters.
         */
        fun voidTransaction(
            originalTransactionId: String,
            currency: String = "GBP",
            envelope: RequestEnvelope
        ): TransactionRequest = TransactionRequest(
            amountMinor = 0,
            currency = currency,
            tipMinor = null,
            originalTransactionId = originalTransactionId,
            originalRequestId = null,
            envelope = envelope
        )

        /**
         * Pre-authorize (place a hold on the card for [amountMinor]). The card is
         * presented once. The result's [TransactionResult.transactionId] is the
         * handle the EPOS stores against the order to adjust / complete / void the
         * hold later. Success state is [TransactionState.PREAUTH_HELD].
         */
        fun preAuth(
            amountMinor: Int,
            currency: String,
            envelope: RequestEnvelope
        ): TransactionRequest = TransactionRequest(
            amountMinor = amountMinor,
            currency = currency,
            tipMinor = null,
            originalTransactionId = null,
            originalRequestId = null,
            envelope = envelope
        )

        /**
         * Adjust an existing hold to a NEW TOTAL (not a delta): [newTotalMinor]
         * greater than the current hold increases it, less decreases it. No card.
         * [originalTransactionId] is the pre-auth's transaction id. A new total
         * equal to the current hold is rejected by the terminal. Success state is
         * [TransactionState.PREAUTH_HELD] (the hold stays open at the new total).
         */
        fun adjustPreAuth(
            newTotalMinor: Int,
            originalTransactionId: String,
            currency: String = "GBP",
            envelope: RequestEnvelope
        ): TransactionRequest = TransactionRequest(
            amountMinor = newTotalMinor,
            currency = currency,
            tipMinor = null,
            originalTransactionId = originalTransactionId,
            originalRequestId = null,
            envelope = envelope
        )

        /**
         * Complete (capture/settle) a held pre-auth, debiting [amountMinor] (which
         * may be <= the current hold) and closing it. No card.
         * [originalTransactionId] is the pre-auth's transaction id. Success state
         * is [TransactionState.CAPTURED].
         */
        fun completePreAuth(
            amountMinor: Int,
            originalTransactionId: String,
            currency: String = "GBP",
            envelope: RequestEnvelope
        ): TransactionRequest = TransactionRequest(
            amountMinor = amountMinor,
            currency = currency,
            tipMinor = null,
            originalTransactionId = originalTransactionId,
            originalRequestId = null,
            envelope = envelope
        )

        /**
         * Void (release) a held pre-auth without debiting. No amount, no card.
         * [originalTransactionId] is the pre-auth's transaction id. Success state
         * is [TransactionState.REVERSED].
         */
        fun voidPreAuth(
            originalTransactionId: String,
            currency: String = "GBP",
            envelope: RequestEnvelope
        ): TransactionRequest = TransactionRequest(
            amountMinor = 0,
            currency = currency,
            tipMinor = null,
            originalTransactionId = originalTransactionId,
            originalRequestId = null,
            envelope = envelope
        )
    }
}
