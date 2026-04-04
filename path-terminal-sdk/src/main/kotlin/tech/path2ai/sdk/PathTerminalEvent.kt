package tech.path2ai.sdk

import tech.path2ai.sdk.core.DiscoveredDevice
import tech.path2ai.sdk.core.PathError
import tech.path2ai.sdk.core.ReceiptData
import tech.path2ai.sdk.core.TransactionState

sealed class PathTerminalEvent {
    data class DeviceDiscovered(val device: DiscoveredDevice) : PathTerminalEvent()
    data class ConnectionStateChanged(val state: ConnectionState) : PathTerminalEvent()
    data class TransactionStateChanged(val state: TransactionState) : PathTerminalEvent()
    data class Prompt(val message: String) : PathTerminalEvent()
    data class Error(val error: PathError) : PathTerminalEvent()
    data class ReceiptReady(val data: ReceiptData) : PathTerminalEvent()
}
