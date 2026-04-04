package tech.path2ai.sdk.core

import kotlinx.serialization.Serializable

@Serializable
data class DeviceCapabilities(
    val commands: List<String>,
    val nfc: Boolean = false,
    val display: Boolean = false,
    @kotlinx.serialization.SerialName("receipt_print") val receiptPrint: Boolean? = null
) {
    fun supports(command: String): Boolean = command in commands
}
