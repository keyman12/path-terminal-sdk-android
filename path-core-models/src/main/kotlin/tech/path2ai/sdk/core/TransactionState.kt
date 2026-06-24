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

    /**
     * Customer didn't respond to a customer-facing prompt (e.g. the tip
     * screen) within the allowed time. Distinct from [TIMED_OUT], which is
     * a hardware / network level timeout.
     */
    @SerialName("customer_timeout") CUSTOMER_TIMEOUT("customer_timeout"),

    @SerialName("failed") FAILED("failed"),
    @SerialName("reversal_pending") REVERSAL_PENDING("reversal_pending"),
    @SerialName("reversed") REVERSED("reversed"),
    @SerialName("refund_pending") REFUND_PENDING("refund_pending"),
    @SerialName("refunded") REFUNDED("refunded"),

    /**
     * Pre-authorization states. A pre-auth places a hold on the card (funds
     * reserved, not debited): [PREAUTH_PENDING] while the hold is being placed,
     * [PREAUTH_HELD] once it's active (also the success state of an adjust — the
     * hold is still open at its new total). Capturing the hold goes
     * [CAPTURE_PENDING] -> [CAPTURED]; releasing it reuses [REVERSAL_PENDING] ->
     * [REVERSED]. See the pre-auth methods on [PathTerminalAdapter].
     */
    @SerialName("preauth_pending") PREAUTH_PENDING("preauth_pending"),
    @SerialName("preauth_held") PREAUTH_HELD("preauth_held"),
    @SerialName("capture_pending") CAPTURE_PENDING("capture_pending"),
    @SerialName("captured") CAPTURED("captured"),

    @SerialName("settlement_pending") SETTLEMENT_PENDING("settlement_pending"),
    @SerialName("settled") SETTLED("settled");

    val rawValue: String get() = value
}
