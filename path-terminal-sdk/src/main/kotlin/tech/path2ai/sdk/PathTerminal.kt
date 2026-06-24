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
        try {
            adapter.connect(device)
            emit(PathTerminalEvent.ConnectionStateChanged(ConnectionState.Connected))
        } catch (e: Exception) {
            // Emit disconnected so the UI doesn't stay frozen at Connecting
            emit(PathTerminalEvent.ConnectionStateChanged(ConnectionState.Disconnected))
            val pathError = e as? tech.path2ai.sdk.core.PathError
                ?: tech.path2ai.sdk.core.PathError(
                    code = tech.path2ai.sdk.core.PathErrorCode.CONNECTIVITY,
                    message = e.message ?: "Connection failed",
                    recoverable = true
                )
            emit(PathTerminalEvent.Error(pathError))
            throw e
        }
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

    /**
     * Void (fully reverse) an approved sale. The request must carry
     * [TransactionRequest.originalTransactionId] — build it with
     * [TransactionRequest.Companion.voidTransaction]. No amount, no card
     * presentation; success state is [TransactionState.REVERSED]. Like
     * [refund], the receipt is not auto-fetched — call [getReceiptData]
     * with the result's transactionId when needed.
     */
    suspend fun voidTransaction(request: TransactionRequest): TransactionResult {
        if (request.originalTransactionId == null) {
            throw PathError(
                code = PathErrorCode.VALIDATION,
                message = "voidTransaction requires originalTransactionId (use TransactionRequest.voidTransaction)",
                recoverable = false
            )
        }
        val sm = TransactionStateMachine { t ->
            emit(PathTerminalEvent.TransactionStateChanged(t.to))
        }
        sm.transition(TransactionState.PENDING_DEVICE)
        sm.transition(TransactionState.REVERSAL_PENDING)
        val result = adapter.voidTransaction(request)
        sm.transition(result.state)
        return result
    }

    /**
     * Pre-authorize: place a hold on the card. Build the request with
     * [TransactionRequest.Companion.preAuth]. The card is presented once; success
     * state is [TransactionState.PREAUTH_HELD] and the result's transactionId is
     * the handle for [adjustPreAuth] / [completePreAuth] / [voidPreAuth]. Like a
     * sale, the receipt is auto-fetched when available.
     */
    suspend fun preAuth(request: TransactionRequest): TransactionResult {
        val sm = TransactionStateMachine { t ->
            emit(PathTerminalEvent.TransactionStateChanged(t.to))
        }
        sm.transition(TransactionState.PENDING_DEVICE)
        sm.transition(TransactionState.PREAUTH_PENDING)
        val result = adapter.preAuth(request)
        sm.transition(result.state)
        val txnId = result.transactionId
        if (result.receiptAvailable && txnId != null) {
            try {
                emit(PathTerminalEvent.ReceiptReady(adapter.getReceiptData(txnId)))
            } catch (_: Exception) {
            }
        }
        return result
    }

    /**
     * Adjust a held pre-auth to a new total — build the request with
     * [TransactionRequest.Companion.adjustPreAuth]. No card; success state is
     * [TransactionState.PREAUTH_HELD] (the hold stays open at the new total).
     */
    suspend fun adjustPreAuth(request: TransactionRequest): TransactionResult {
        requireOriginalId(request, "adjustPreAuth")
        val sm = TransactionStateMachine { t ->
            emit(PathTerminalEvent.TransactionStateChanged(t.to))
        }
        sm.transition(TransactionState.PENDING_DEVICE)
        sm.transition(TransactionState.PREAUTH_PENDING)
        val result = adapter.adjustPreAuth(request)
        sm.transition(result.state)
        return result
    }

    /**
     * Complete (capture) a held pre-auth — build the request with
     * [TransactionRequest.Companion.completePreAuth]. No card; success state is
     * [TransactionState.CAPTURED].
     */
    suspend fun completePreAuth(request: TransactionRequest): TransactionResult {
        requireOriginalId(request, "completePreAuth")
        val sm = TransactionStateMachine { t ->
            emit(PathTerminalEvent.TransactionStateChanged(t.to))
        }
        sm.transition(TransactionState.PENDING_DEVICE)
        sm.transition(TransactionState.CAPTURE_PENDING)
        val result = adapter.completePreAuth(request)
        sm.transition(result.state)
        return result
    }

    /**
     * Void (release) a held pre-auth — build the request with
     * [TransactionRequest.Companion.voidPreAuth]. No amount, no card; success
     * state is [TransactionState.REVERSED].
     */
    suspend fun voidPreAuth(request: TransactionRequest): TransactionResult {
        requireOriginalId(request, "voidPreAuth")
        val sm = TransactionStateMachine { t ->
            emit(PathTerminalEvent.TransactionStateChanged(t.to))
        }
        sm.transition(TransactionState.PENDING_DEVICE)
        sm.transition(TransactionState.REVERSAL_PENDING)
        val result = adapter.voidPreAuth(request)
        sm.transition(result.state)
        return result
    }

    private fun requireOriginalId(request: TransactionRequest, op: String) {
        if (request.originalTransactionId == null) {
            throw PathError(
                code = PathErrorCode.VALIDATION,
                message = "$op requires originalTransactionId (the pre-auth's transaction id)",
                recoverable = false
            )
        }
    }

    suspend fun cancelActiveTransaction() {
        adapter.cancelActiveTransaction()
        emit(PathTerminalEvent.TransactionStateChanged(TransactionState.CANCELLED))
    }

    /**
     * Set (or clear) the idle customer-display branding (a merchant logo shown
     * on connect and re-shown between transactions). Pass null to turn it off.
     * No-op on backends without a customer screen. See
     * [PathTerminalAdapter.setIdleBranding].
     */
    suspend fun setIdleBranding(content: CustomerDisplayContent?) {
        adapter.setIdleBranding(content)
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
