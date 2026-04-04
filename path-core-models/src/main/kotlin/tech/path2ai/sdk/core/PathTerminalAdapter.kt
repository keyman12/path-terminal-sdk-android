package tech.path2ai.sdk.core

/**
 * Adapter interface for terminal communication.
 * Implementations map device-specific protocols to Path canonical models.
 */
interface PathTerminalAdapter {

    /** Discover available devices (e.g. BLE scan) */
    suspend fun discoverDevices(): List<DiscoveredDevice>

    /** Connect to a discovered device */
    suspend fun connect(device: DiscoveredDevice)

    /** Disconnect from current device */
    suspend fun disconnect()

    /** Execute sale transaction */
    suspend fun sale(request: TransactionRequest): TransactionResult

    /** Execute refund transaction */
    suspend fun refund(request: TransactionRequest): TransactionResult

    /** Get device capabilities */
    suspend fun getCapabilities(): DeviceCapabilities

    /** Get device info */
    suspend fun getDeviceInfo(): DeviceInfo

    /** Get transaction status by request ID */
    suspend fun getTransactionStatus(requestId: String): TransactionResult

    /** Get receipt data for transaction */
    suspend fun getReceiptData(transactionId: String): ReceiptData

    /** Cancel the in-flight transaction */
    suspend fun cancelActiveTransaction()

    /** Whether currently connected to a device */
    val isConnected: Boolean

    /**
     * Called by the adapter when the hardware initiates a disconnect.
     * PathTerminal wires this during connect() to emit a connectionStateChanged event.
     */
    var onHardwareDisconnect: (() -> Unit)?
}
