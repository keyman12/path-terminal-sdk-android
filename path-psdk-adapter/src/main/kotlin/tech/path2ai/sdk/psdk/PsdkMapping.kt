package tech.path2ai.sdk.psdk

import com.verifone.payment_sdk.AuthorizationResult
import com.verifone.payment_sdk.Decimal
import com.verifone.payment_sdk.Payment
import com.verifone.payment_sdk.Receipt
import com.verifone.payment_sdk.ReceiptType
import tech.path2ai.sdk.core.*
import java.time.Instant

/**
 * Pure mapping between the Verifone PSDK's result vocabulary and Path's
 * canonical models. Money is integer minor units end-to-end:
 * Decimal(scale=2, unscaled=minor) — never doubles (PSDK requirement).
 */
internal object PsdkMapping {

    fun minorToDecimal(minor: Long): Decimal = Decimal(2, minor)

    /** Round-half-never: tip percentages round UP to the next minor unit (emulator parity). */
    fun tipForPercent(baseMinor: Long, percent: Int): Long =
        if (percent <= 0 || baseMinor <= 0) 0 else (baseMinor * percent + 99) / 100

    /**
     * Map a completed PSDK payment (or its absence) to a Path TransactionResult.
     *
     * The PSDK split: the completion event's status says whether the flow ran;
     * the Payment's authResult is the financial outcome. A decline is a normal
     * result (status SUCCESS + authResult *_DECLINED), never an exception.
     */
    fun mapCompletion(
        requestId: String,
        eventStatus: Int,
        payment: Payment?,
        baseMinor: Long,
        tipMinor: Long,
        currency: String,
        tipPercentX10: Int?,
        receiptsCached: Boolean,
        // The success state to use for an AUTHORIZED result. Defaults to APPROVED
        // (a sale); pre-auth uses PREAUTH_HELD (hold/adjust) or CAPTURED (complete)
        // — a pre-auth and its completion both come back AUTHORIZED from the PSDK.
        approvedState: TransactionState = TransactionState.APPROVED
    ): TransactionResult {
        val totalMinor = baseMinor + tipMinor
        val authResult = payment?.authResult
        val (state, error) = when (authResult) {
            AuthorizationResult.AUTHORIZED -> approvedState to null
            AuthorizationResult.REFUNDED -> TransactionState.REFUNDED to null
            AuthorizationResult.VOIDED -> TransactionState.REVERSED to null
            AuthorizationResult.DECLINED,
            AuthorizationResult.REFUND_DECLINED,
            AuthorizationResult.VOID_DECLINED -> TransactionState.DECLINED to PathError(
                code = PathErrorCode.DECLINE,
                message = "Transaction declined (${authResult.name.lowercase()})" +
                    (payment.authResponseText?.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: ""),
                adapterErrorCode = authResult.name,
                recoverable = false
            )
            else -> when (eventStatus) {
                // -11 CANCELLED (customer timeout / cancel), -12 ABORTED
                -11, -12 -> TransactionState.CANCELLED to PathError(
                    code = PathErrorCode.USER_CANCELLED,
                    message = "Transaction cancelled on the terminal (status $eventStatus)",
                    adapterErrorCode = eventStatus.toString(),
                    recoverable = true
                )
                // -24 TXN NOT SUPPORTED (AGPA status 807 / the "T807" the
                // terminal shows): the device recognises the transaction type
                // but isn't provisioned for it. The common case is a tip-bearing
                // sale ("Sale with Tip") on a terminal whose product config has
                // tipping disabled. Surface an actionable message — enabling it
                // is a terminal-side (VHQ) config change, not an SDK fix.
                -24 -> TransactionState.FAILED to PathError(
                    code = PathErrorCode.UNSUPPORTED_OPERATION,
                    message = if (tipMinor > 0) {
                        "Tipping isn't enabled on this terminal — the gratuity transaction was " +
                            "rejected as not supported (T807). Enable the tip transaction type in " +
                            "the terminal configuration (VHQ) to take gratuities."
                    } else {
                        "The terminal rejected this transaction as not supported (T807)."
                    },
                    adapterErrorCode = eventStatus.toString(),
                    recoverable = false
                )
                else -> TransactionState.FAILED to PathError(
                    code = PathErrorCode.TERMINAL_FAULT,
                    message = "Transaction did not complete (PSDK status $eventStatus, no auth result)",
                    adapterErrorCode = eventStatus.toString(),
                    recoverable = false
                )
            }
        }

        return TransactionResult(
            transactionId = payment?.transactionId,
            requestId = requestId,
            state = state,
            amountMinor = totalMinor.toInt(),
            currency = currency,
            tipMinor = if (tipMinor > 0) tipMinor.toInt() else null,
            baseAmountMinor = baseMinor.toInt(),
            tipAmountMinor = tipMinor.toInt(),
            totalAmountMinor = totalMinor.toInt(),
            tipPercentX10 = tipPercentX10,
            cardLastFour = cardLastFour(payment),
            receiptAvailable = receiptsCached,
            timestampUtc = Instant.now().toString(),
            error = error
        )
    }

    fun cardLastFour(payment: Payment?): String? {
        val masked = payment?.receipts?.values?.firstNotNullOfOrNull { it?.data?.maskedPAN }
            ?: return null
        val digits = masked.filter { it.isDigit() }
        return if (digits.length >= 4) digits.takeLast(4) else null
    }

    /**
     * Build Path ReceiptData from the receipts the PSDK delivered WITH the
     * completion event. These cannot be re-fetched from the terminal — the
     * adapter caches the mapped result keyed by transactionId.
     */
    fun mapReceipts(
        transactionId: String,
        requestId: String,
        payment: Payment,
        totalMinor: Long,
        tipMinor: Long,
        currency: String
    ): ReceiptData? {
        val receipts = payment.receipts ?: return null
        if (receipts.isEmpty()) return null
        val merchant = receipts[ReceiptType.MERCHANT]
        val customer = receipts[ReceiptType.CUSTOMER]
        val anyReceipt = merchant ?: customer ?: return null

        fun copy(rec: Receipt?, label: String, maskIds: Boolean): CardReceiptFields {
            val d = (rec ?: anyReceipt).data
            val mid = d?.merchantID.orEmpty()
            val tid = d?.terminalID.orEmpty()
            return CardReceiptFields(
                copyLabel = label,
                txnType = d?.transactionType.orEmpty(),
                amount = totalMinor.toInt(),
                currency = d?.currency?.takeIf { it.isNotEmpty() } ?: currency,
                cardScheme = d?.cardBrand.orEmpty(),
                maskedPan = d?.maskedPAN.orEmpty(),
                entryMode = d?.paymentInstrument.orEmpty(),
                aid = d?.aid.orEmpty(),
                verification = d?.cvmType.orEmpty(),
                authCode = d?.authCode.orEmpty(),
                merchantId = if (maskIds && mid.length >= 4) "**" + mid.takeLast(4) else mid,
                terminalId = if (maskIds && tid.length >= 4) "****" + tid.takeLast(4) else tid,
                txnRef = d?.refNumber?.takeIf { it.isNotEmpty() } ?: transactionId.take(13),
                timestamp = d?.transactionTimeStamp.orEmpty(),
                status = d?.transactionResult.orEmpty(),
                retainMessage = if (maskIds) "PLEASE RETAIN RECEIPT" else null
            )
        }

        return ReceiptData(
            transactionId = transactionId,
            requestId = requestId,
            merchantReceipt = copy(merchant, "MERCHANT COPY", maskIds = false),
            customerReceipt = copy(customer, "CARDHOLDER COPY", maskIds = true),
            timestampUtc = Instant.now().toString()
        )
    }
}
