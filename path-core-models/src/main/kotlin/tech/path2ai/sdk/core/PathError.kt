package tech.path2ai.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PathErrorCode(val value: String) {
    @SerialName("validation") VALIDATION("validation"),
    @SerialName("connectivity") CONNECTIVITY("connectivity"),
    @SerialName("capability") CAPABILITY("capability"),
    @SerialName("terminal_busy") TERMINAL_BUSY("terminal_busy"),
    @SerialName("timeout") TIMEOUT("timeout"),
    @SerialName("user_cancelled") USER_CANCELLED("user_cancelled"),
    @SerialName("decline") DECLINE("decline"),
    @SerialName("terminal_fault") TERMINAL_FAULT("terminal_fault"),
    @SerialName("adapter_fault") ADAPTER_FAULT("adapter_fault"),
    @SerialName("protocol_mismatch") PROTOCOL_MISMATCH("protocol_mismatch"),
    @SerialName("recovery_required") RECOVERY_REQUIRED("recovery_required"),
    @SerialName("configuration_error") CONFIGURATION_ERROR("configuration_error"),
    @SerialName("unsupported_operation") UNSUPPORTED_OPERATION("unsupported_operation");
}

@Serializable
data class PathError(
    val code: PathErrorCode,
    override val message: String,
    @SerialName("adapter_error_code") val adapterErrorCode: String? = null,
    val recoverable: Boolean = false
) : Exception(message)
