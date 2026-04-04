package tech.path2ai.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CardReceiptFields(
    @SerialName("copy_label") val copyLabel: String,
    @SerialName("txn_type") val txnType: String,
    val amount: Int,
    val currency: String,
    @SerialName("card_scheme") val cardScheme: String,
    @SerialName("masked_pan") val maskedPan: String,
    @SerialName("entry_mode") val entryMode: String,
    val aid: String,
    val verification: String,
    @SerialName("auth_code") val authCode: String,
    @SerialName("merchant_id") val merchantId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("txn_ref") val txnRef: String,
    val timestamp: String,
    val status: String,
    @SerialName("retain_message") val retainMessage: String? = null
)

@Serializable
data class ReceiptData(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("merchant_receipt") val merchantReceipt: CardReceiptFields,
    @SerialName("customer_receipt") val customerReceipt: CardReceiptFields,
    @SerialName("timestamp_utc") val timestampUtc: String
)
