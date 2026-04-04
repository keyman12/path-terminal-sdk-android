package tech.path2ai.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TransactionState(val value: String) {
    @SerialName("created") CREATED("created"),
    @SerialName("pending_device") PENDING_DEVICE("pending_device"),
    @SerialName("card_presented") CARD_PRESENTED("card_presented"),
    @SerialName("card_read") CARD_READ("card_read"),
    @SerialName("authorizing") AUTHORIZING("authorizing"),
    @SerialName("approved") APPROVED("approved"),
    @SerialName("declined") DECLINED("declined"),
    @SerialName("cancelled") CANCELLED("cancelled"),
    @SerialName("timed_out") TIMED_OUT("timed_out"),
    @SerialName("failed") FAILED("failed"),
    @SerialName("reversal_pending") REVERSAL_PENDING("reversal_pending"),
    @SerialName("reversed") REVERSED("reversed"),
    @SerialName("refund_pending") REFUND_PENDING("refund_pending"),
    @SerialName("refunded") REFUNDED("refunded"),
    @SerialName("settlement_pending") SETTLEMENT_PENDING("settlement_pending"),
    @SerialName("settled") SETTLED("settled");

    val rawValue: String get() = value
}
