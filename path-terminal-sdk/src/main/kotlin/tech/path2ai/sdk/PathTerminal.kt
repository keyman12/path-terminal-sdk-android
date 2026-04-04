package tech.path2ai.sdk

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import tech.path2ai.sdk.core.*

/**
 * Main entry point for the Path Terminal SDK.
 * Wraps a [PathTerminalAdapter] and exposes typed APIs with an event flow.
 */
class PathTerminal(private var adapter: PathTerminalAdapter) {

    private val _events = MutableSharedFlow<PathTerminalEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    /** Typed event stream — collect in a coroutine to receive device, connection, and transaction updates. */
    val events: SharedFlow<PathTerminalEvent> = _events.asSharedFlow()

    private fun emit(event: PathTerminalEvent) {
        _events.tryEmit(event)
    }

    suspend fun discoverDevices(): List<DiscoveredDevice> {
        emit(PathTerminalEvent.ConnectionStateChanged(ConnectionState.Scanning))
        val devices = adapter.discoverDevices()
        for (d in devices) {
            emit(PathTerminalEvent.DeviceDiscovered(d))
        }
        emit(
            PathTerminalEvent.ConnectionStateChanged(
                if (adapter.isConnected) ConnectionState.Connected else ConnectionState.Idle
            )
        )
        return devices
    }

    suspend fun connect(device: DiscoveredDevice) {
        emit(PathTerminalEvent.ConnectionStateChanged(ConnectionState.Connecting))
        adapter.onHardwareDisconnect = {
            emit(PathTerminalEvent.ConnectionStateChanged(ConnectionState.Disconnected))
        }
        adapter.connect(device)
        emit(PathTerminalEvent.ConnectionStateChanged(ConnectionState.Connected))
    }

    suspend fun disconnect() {
        adapter.disconnect()
        emit(PathTerminalEvent.ConnectionStateChanged(ConnectionState.Disconnected))
    }

    suspend fun sale(request: TransactionRequest): TransactionResult {
        val sm = TransactionStateMachine { t ->
            emit(PathTerminalEvent.TransactionStateChanged(t.to))
        }
        sm.transition(TransactionState.PENDING_DEVICE)
        val result = adapter.sale(request)
        sm.transition(result.state)
        val txnId = result.transactionId
        if (result.receiptAvailable && txnId != null) {
            try {
                val receipt = adapter.getReceiptData(txnId)
                emit(PathTerminalEvent.ReceiptReady(receipt))
            } catch (_: Exception) {
                // Receipt fetch failure is non-fatal
            }
        }
        return result
    }

    suspend fun refund(request: TransactionRequest): TransactionResult {
        val sm = TransactionStateMachine { t ->
            emit(PathTerminalEvent.TransactionStateChanged(t.to))
        }
        sm.transition(TransactionState.PENDING_DEVICE)
        sm.transition(TransactionState.REFUND_PENDING)
        val result = adapter.refund(request)
        sm.transition(result.state)
        return result
    }

    suspend fun cancelActiveTransaction() {
        adapter.cancelActiveTransaction()
        emit(PathTerminalEvent.TransactionStateChanged(TransactionState.CANCELLED))
    }

    suspend fun getTransactionStatus(requestId: String): TransactionResult {
        return adapter.getTransactionStatus(requestId)
    }

    suspend fun getReceiptData(transactionId: String): ReceiptData {
        return adapter.getReceiptData(transactionId)
    }

    suspend fun getCapabilities(): DeviceCapabilities {
        return adapter.getCapabilities()
    }

    val isConnected: Boolean get() = adapter.isConnected
}
