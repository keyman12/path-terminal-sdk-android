package tech.path2ai.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class RequestEnvelope(
    @SerialName("request_id") val requestId: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("merchant_reference") val merchantReference: String? = null,
    @SerialName("terminal_session_id") val terminalSessionId: String? = null,
    @SerialName("correlation_id") val correlationId: String,
    @SerialName("sdk_version") val sdkVersion: String,
    @SerialName("adapter_version") val adapterVersion: String,
    @SerialName("timestamp_utc") val timestampUtc: String
) {
    companion object {
        fun create(
            idempotencyKey: String? = null,
            merchantReference: String? = null,
            terminalSessionId: String? = null,
            correlationId: String? = null,
            sdkVersion: String,
            adapterVersion: String
        ): RequestEnvelope {
            val requestId = UUID.randomUUID().toString()
            val idemKey = idempotencyKey ?: requestId
            val corrId = correlationId ?: requestId
            val now = Instant.now().toString()
            return RequestEnvelope(
                requestId = requestId,
                idempotencyKey = idemKey,
                merchantReference = merchantReference,
                terminalSessionId = terminalSessionId,
                correlationId = corrId,
                sdkVersion = sdkVersion,
                adapterVersion = adapterVersion,
                timestampUtc = now
            )
        }
    }
}
