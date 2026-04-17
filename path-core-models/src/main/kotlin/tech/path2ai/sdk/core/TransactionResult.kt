package tech.path2ai.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TransactionResult(
    @SerialName("transaction_id") val transactionId: String? = null,
    @SerialName("request_id") val requestId: String,
    val state: TransactionState,
    /**
     * Total amount charged to the card (same as [totalAmountMinor]).
     * Kept for source compatibility with earlier SDK versions.
     */
    @SerialName("amount_minor") val amountMinor: Int,
    val currency: String,
    /**
     * Legacy pre-tipping field. Equal to [tipAmountMinor] when that's > 0,
     * `null` otherwise. Prefer [tipAmountMinor] for new code.
     */
    @SerialName("tip_minor") val tipMinor: Int? = null,

    /**
     * The sale amount before tip (what the EPOS originally asked for).
     * Defaults to [totalAmountMinor] when no tip was added.
     */
    @SerialName("base_amount_minor") val baseAmountMinor: Int = 0,
    /**
     * Tip added (by the customer on-terminal, or pre-entered via [tipMinor]
     * on the request). 0 when no tip.
     */
    @SerialName("tip_amount_minor") val tipAmountMinor: Int = 0,
    /**
     * [baseAmountMinor] + [tipAmountMinor] — the amount charged to the card.
     */
    @SerialName("total_amount_minor") val totalAmountMinor: Int = 0,
    /**
     * Which tip preset the customer chose, multiplied by 10 so 12.5% = 125.
     * `null` when no tip was added, or when a non-preset tip amount was used.
     */
    @SerialName("tip_percent_x10") val tipPercentX10: Int? = null,

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
            TransactionState.CUSTOMER_TIMEOUT,
            TransactionState.FAILED,
            TransactionState.REVERSED,
            TransactionState.REFUNDED,
            TransactionState.SETTLED
        )

    /**
     * Resolves back-compat defaults: when a caller (or an older emulator)
     * didn't supply the breakdown fields, infer sensible values from
     * [amountMinor] / [tipMinor] so consumer code can rely on the getters
     * below returning meaningful numbers.
     */
    val resolvedTotalAmountMinor: Int
        get() = if (totalAmountMinor > 0) totalAmountMinor else amountMinor

    val resolvedTipAmountMinor: Int
        get() = when {
            tipAmountMinor > 0 -> tipAmountMinor
            tipMinor != null && tipMinor > 0 -> tipMinor
            else -> 0
        }

    val resolvedBaseAmountMinor: Int
        get() = if (baseAmountMinor > 0) baseAmountMinor
        else resolvedTotalAmountMinor - resolvedTipAmountMinor
}
