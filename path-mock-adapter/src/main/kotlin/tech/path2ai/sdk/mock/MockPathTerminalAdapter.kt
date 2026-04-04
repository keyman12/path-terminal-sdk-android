package tech.path2ai.sdk.mock

import kotlinx.coroutines.delay
import tech.path2ai.sdk.core.*

/**
 * Test mock for [PathTerminalAdapter] — no BLE dependency.
 * Configure result fields to control behaviour in unit tests.
 */
class MockPathTerminalAdapter : PathTerminalAdapter {

    var discoverResult: Result<List<DiscoveredDevice>> =
        Result.success(listOf(DiscoveredDevice(id = "mock-001", name = "Mock Terminal", rssi = -50)))

    var connectError: Exception? = null

    var saleResult: Result<TransactionResult>? = null

    var refundResult: Result<TransactionResult>? = null

    var capabilitiesResult: Result<DeviceCapabilities> =
        Result.success(DeviceCapabilities(commands = listOf("Sale", "Refund", "Cancel"), nfc = true, display = true))

    var deviceInfoResult: Result<DeviceInfo> =
        Result.success(DeviceInfo(model = "Mock Terminal", firmware = "1.0.0", protocolVersion = "0.1.0"))

    var transactionStatusResult: Result<TransactionResult>? = null

    var receiptDataResult: Result<ReceiptData>? = null

    var cancelError: Exception? = null

    /** Simulated delay in milliseconds before returning results. */
    var delayMs: Long = 0

    private var _isConnected = false
    override val isConnected: Boolean get() = _isConnected

    override var onHardwareDisconnect: (() -> Unit)? = null

    private suspend fun maybeDelay() {
        if (delayMs > 0) delay(delayMs)
    }

    override suspend fun discoverDevices(): List<DiscoveredDevice> {
        maybeDelay()
        return discoverResult.getOrThrow()
    }

    override suspend fun connect(device: DiscoveredDevice) {
        maybeDelay()
        connectError?.let { throw it }
        _isConnected = true
    }

    override suspend fun disconnect() {
        maybeDelay()
        _isConnected = false
    }

    override suspend fun sale(request: TransactionRequest): TransactionResult {
        maybeDelay()
        return saleResult?.getOrThrow() ?: TransactionResult(
            transactionId = "mock-txn-${System.currentTimeMillis()}",
            requestId = request.envelope.requestId,
            state = TransactionState.APPROVED,
            amountMinor = request.amountMinor,
            currency = request.currency,
            cardLastFour = "1234",
            receiptAvailable = true,
            timestampUtc = java.time.Instant.now().toString()
        )
    }

    override suspend fun refund(request: TransactionRequest): TransactionResult {
        maybeDelay()
        return refundResult?.getOrThrow() ?: TransactionResult(
            transactionId = "mock-ref-${System.currentTimeMillis()}",
            requestId = request.envelope.requestId,
            state = TransactionState.REFUNDED,
            amountMinor = request.amountMinor,
            currency = request.currency,
            receiptAvailable = false,
            timestampUtc = java.time.Instant.now().toString()
        )
    }

    override suspend fun getCapabilities(): DeviceCapabilities {
        maybeDelay()
        return capabilitiesResult.getOrThrow()
    }

    override suspend fun getDeviceInfo(): DeviceInfo {
        maybeDelay()
        return deviceInfoResult.getOrThrow()
    }

    override suspend fun getTransactionStatus(requestId: String): TransactionResult {
        maybeDelay()
        return transactionStatusResult?.getOrThrow() ?: TransactionResult(
            transactionId = null,
            requestId = requestId,
            state = TransactionState.APPROVED,
            amountMinor = 0,
            currency = "GBP",
            timestampUtc = java.time.Instant.now().toString()
        )
    }

    override suspend fun getReceiptData(transactionId: String): ReceiptData {
        maybeDelay()
        return receiptDataResult?.getOrThrow() ?: throw PathError(
            code = PathErrorCode.UNSUPPORTED_OPERATION,
            message = "Receipt not available in mock",
            recoverable = false
        )
    }

    override suspend fun cancelActiveTransaction() {
        maybeDelay()
        cancelError?.let { throw it }
    }

    /** Simulate a hardware-initiated disconnect. */
    fun simulateHardwareDisconnect() {
        _isConnected = false
        onHardwareDisconnect?.invoke()
    }
}
