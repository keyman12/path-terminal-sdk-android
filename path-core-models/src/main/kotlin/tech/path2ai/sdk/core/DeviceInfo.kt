package tech.path2ai.sdk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val model: String,
    val firmware: String,
    val serial: String? = null,
    @SerialName("protocol_version") val protocolVersion: String
)
