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

    /**
     * Void (fully reverse) an approved sale. No amount, no card presentation —
     * the request's [TransactionRequest.originalTransactionId] identifies the
     * sale to reverse. Success state is [TransactionState.REVERSED].
     * (Named voidTransaction because `void` is a Java keyword.)
     */
    suspend fun voidTransaction(request: TransactionRequest): TransactionResult

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

    /**
     * Set (or clear) branding to show on the terminal's customer-facing display
     * while it is idle — a merchant logo shown on connect and re-shown after
     * every transaction ("attract mode"). Pass null to turn it off.
     *
     * The adapter owns the choreography (it already owns the per-transaction
     * session): it pushes the content when idle and re-pushes it once each sale
     * / refund / void completes. Backends without a customer display treat this
     * as a no-op. Safe to call before connecting — the content is remembered and
     * shown on the next connect.
     */
    suspend fun setIdleBranding(content: CustomerDisplayContent?)

    /** Whether currently connected to a device */
    val isConnected: Boolean

    /**
     * Called by the adapter when the hardware initiates a disconnect.
     * PathTerminal wires this during connect() to emit a connectionStateChanged event.
     */
    var onHardwareDisconnect: (() -> Unit)?
}
