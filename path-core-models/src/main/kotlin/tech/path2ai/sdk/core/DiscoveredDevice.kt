package tech.path2ai.sdk.core

import kotlinx.serialization.Serializable

@Serializable
data class DiscoveredDevice(
    val id: String,
    val name: String,
    val rssi: Int
)
