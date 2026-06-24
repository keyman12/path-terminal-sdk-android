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

    var voidResult: Result<TransactionResult>? = null

    var preAuthResult: Result<TransactionResult>? = null

    var adjustPreAuthResult: Result<TransactionResult>? = null

    var completePreAuthResult: Result<TransactionResult>? = null

    var voidPreAuthResult: Result<TransactionResult>? = null

    /**
     * How the fake "customer" should respond to a Sale whose request has
     * [TransactionRequest.promptForTip] == true. Lets tests exercise the
     * tipping happy path and the customer-timeout path without needing
     * real hardware.
     *
     * Ignored when [saleResult] is set (that takes precedence), or when
     * the Sale request doesn't ask for a tip prompt.
     */
    sealed class SimulatedTipResponse {
        /** Customer picked "No tip". Result has tipAmountMinor == 0. */
        object NoTip : SimulatedTipResponse()

        /**
         * Customer picked a preset. [percentX10] is 100 / 125 / 150 / 200
         * (i.e. 10 / 12.5 / 15 / 20 percent) and [amountMinor] is the
         * rounded-up tip amount in minor units.
         */
        data class PickedPreset(val percentX10: Int, val amountMinor: Int) : SimulatedTipResponse()

        /** Customer walked away — emitted as CUSTOMER_TIMEOUT state + error. */
        object CustomerTimeout : SimulatedTipResponse()
    }

    /**
     * Controls what the mock "customer" does when a Sale request asks for a
     * tip prompt. Defaults to [SimulatedTipResponse.NoTip] so existing tests
     * that don't care about tipping stay green.
     */
    var simulatedTipResponse: SimulatedTipResponse = SimulatedTipResponse.NoTip

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
        // Explicit override from the test wins.
        saleResult?.let { return it.getOrThrow() }

        val now = java.time.Instant.now().toString()

        // If the request asks for a tip prompt, act out whichever response
        // was configured on the mock. Lets tests exercise each tipping branch
        // (happy-path tip, no-tip, customer walks away) deterministically.
        if (request.promptForTip) {
            return when (val r = simulatedTipResponse) {
                SimulatedTipResponse.CustomerTimeout -> TransactionResult(
                    transactionId = null,
                    requestId = request.envelope.requestId,
                    state = TransactionState.CUSTOMER_TIMEOUT,
                    amountMinor = request.amountMinor,
                    currency = request.currency,
                    tipMinor = null,
                    baseAmountMinor = request.amountMinor,
                    tipAmountMinor = 0,
                    totalAmountMinor = request.amountMinor,
                    tipPercentX10 = null,
                    cardLastFour = null,
                    receiptAvailable = false,
                    timestampUtc = now,
                    error = PathError(
                        code = PathErrorCode.CUSTOMER_TIMEOUT,
                        message = "Customer did not respond to tip prompt",
                        recoverable = true
                    )
                )
                SimulatedTipResponse.NoTip -> TransactionResult(
                    transactionId = "mock-txn-${System.currentTimeMillis()}",
                    requestId = request.envelope.requestId,
                    state = TransactionState.APPROVED,
                    amountMinor = request.amountMinor,
                    currency = request.currency,
                    tipMinor = null,
                    baseAmountMinor = request.amountMinor,
                    tipAmountMinor = 0,
                    totalAmountMinor = request.amountMinor,
                    tipPercentX10 = null,
                    cardLastFour = "1234",
                    receiptAvailable = true,
                    timestampUtc = now
                )
                is SimulatedTipResponse.PickedPreset -> {
                    val total = request.amountMinor + r.amountMinor
                    TransactionResult(
                        transactionId = "mock-txn-${System.currentTimeMillis()}",
                        requestId = request.envelope.requestId,
                        state = TransactionState.APPROVED,
                        amountMinor = total,
                        currency = request.currency,
                        tipMinor = r.amountMinor,
                        baseAmountMinor = request.amountMinor,
                        tipAmountMinor = r.amountMinor,
                        totalAmountMinor = total,
                        tipPercentX10 = r.percentX10,
                        cardLastFour = "1234",
                        receiptAvailable = true,
                        timestampUtc = now
                    )
                }
            }
        }

        // No tip prompt — default pass-through (preserves prior behaviour).
        return TransactionResult(
            transactionId = "mock-txn-${System.currentTimeMillis()}",
            requestId = request.envelope.requestId,
            state = TransactionState.APPROVED,
            amountMinor = request.amountMinor,
            currency = request.currency,
            cardLastFour = "1234",
            receiptAvailable = true,
            timestampUtc = now
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

    override suspend fun voidTransaction(request: TransactionRequest): TransactionResult {
        maybeDelay()
        return voidResult?.getOrThrow() ?: TransactionResult(
            transactionId = "mock-void-${System.currentTimeMillis()}",
            requestId = request.envelope.requestId,
            state = TransactionState.REVERSED,
            amountMinor = request.amountMinor,
            currency = request.currency,
            receiptAvailable = false,
            timestampUtc = java.time.Instant.now().toString()
        )
    }

    override suspend fun preAuth(request: TransactionRequest): TransactionResult {
        maybeDelay()
        return preAuthResult?.getOrThrow() ?: TransactionResult(
            transactionId = "mock-preauth-${System.currentTimeMillis()}",
            requestId = request.envelope.requestId,
            state = TransactionState.PREAUTH_HELD,
            amountMinor = request.amountMinor,
            currency = request.currency,
            cardLastFour = "1234",
            receiptAvailable = true,
            timestampUtc = java.time.Instant.now().toString()
        )
    }

    override suspend fun adjustPreAuth(request: TransactionRequest): TransactionResult {
        maybeDelay()
        return adjustPreAuthResult?.getOrThrow() ?: TransactionResult(
            transactionId = "mock-preauth-adj-${System.currentTimeMillis()}",
            requestId = request.envelope.requestId,
            state = TransactionState.PREAUTH_HELD,
            amountMinor = request.amountMinor,   // the new total hold
            currency = request.currency,
            receiptAvailable = false,
            timestampUtc = java.time.Instant.now().toString()
        )
    }

    override suspend fun completePreAuth(request: TransactionRequest): TransactionResult {
        maybeDelay()
        return completePreAuthResult?.getOrThrow() ?: TransactionResult(
            transactionId = "mock-preauth-cap-${System.currentTimeMillis()}",
            requestId = request.envelope.requestId,
            state = TransactionState.CAPTURED,
            amountMinor = request.amountMinor,
            currency = request.currency,
            receiptAvailable = false,
            timestampUtc = java.time.Instant.now().toString()
        )
    }

    override suspend fun voidPreAuth(request: TransactionRequest): TransactionResult {
        maybeDelay()
        return voidPreAuthResult?.getOrThrow() ?: TransactionResult(
            transactionId = "mock-preauth-void-${System.currentTimeMillis()}",
            requestId = request.envelope.requestId,
            state = TransactionState.REVERSED,
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

    /** Records the last branding set, for assertions; no display in the mock. */
    var lastIdleBranding: CustomerDisplayContent? = null
        private set

    override suspend fun setIdleBranding(content: CustomerDisplayContent?) {
        maybeDelay()
        lastIdleBranding = content
    }

    /** Simulate a hardware-initiated disconnect. */
    fun simulateHardwareDisconnect() {
        _isConnected = false
        onHardwareDisconnect?.invoke()
    }
}
