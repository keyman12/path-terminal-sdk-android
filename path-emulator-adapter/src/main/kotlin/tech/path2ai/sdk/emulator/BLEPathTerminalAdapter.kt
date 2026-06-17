package tech.path2ai.sdk.emulator

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.path2ai.sdk.core.*
import java.util.UUID

/**
 * BLE adapter for the Path POS Emulator using Nordic UART Service.
 * Communicates via newline-delimited JSON over BLE characteristics.
 */
@SuppressLint("MissingPermission")
class BLEPathTerminalAdapter(
    private val context: Context,
    private val sdkVersion: String = "1.5.0",
    private val adapterVersion: String = "1.5.0",
    private val deviceNameFilter: ((String) -> Boolean)? = null,
    // Login credentials sent on connect() — the emulator now performs the same
    // connect-time login handshake as a real terminal (protocol v1.3).
    private val username: String = "user",
    private val password: String = "",
    private val shift: String = "",
    private val onLog: ((String) -> Unit)? = null
) : PathTerminalAdapter {

    companion object {
        private const val TAG = "BLEPathTerminal"

        // Nordic UART Service UUIDs
        private val UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val UART_RX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val UART_TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val CHUNK_SIZE = 20
        private const val CHUNK_DELAY_MS = 20L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val mutex = Mutex()
    private var receiveBuffer = StringBuilder()
    @Volatile
    private var pendingResponse: CompletableDeferred<String>? = null

    private var _isConnected = false
    override val isConnected: Boolean get() = _isConnected

    override var onHardwareDisconnect: (() -> Unit)? = null

    val isBluetoothPoweredOn: Boolean get() = bluetoothAdapter?.isEnabled == true

    // Persistent GATT callback — lives for the lifetime of the connection
    private var activeGattCallback: BluetoothGattCallback? = null

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke("[BLE] $msg")
    }

    // ── Discovery ────────────────────────────────────────────────────────────

    override suspend fun discoverDevices(): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter ?: throw PathError(
            code = PathErrorCode.CONNECTIVITY,
            message = "Bluetooth is not available on this device",
            recoverable = false
        )

        if (!adapter.isEnabled) throw PathError(
            code = PathErrorCode.CONNECTIVITY,
            message = "Bluetooth is turned off",
            recoverable = true
        )

        if (_isConnected) return@withContext emptyList()

        val scanner = adapter.bluetoothLeScanner ?: throw PathError(
            code = PathErrorCode.CONNECTIVITY,
            message = "BLE scanner not available",
            recoverable = false
        )

        val devices = mutableListOf<DiscoveredDevice>()
        val scanComplete = CompletableDeferred<Unit>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (!matchesFilter(name)) return
                val id = result.device.address
                if (devices.none { it.id == id }) {
                    log("Discovered: $name ($id) RSSI=${result.rssi}")
                    devices.add(DiscoveredDevice(id = id, name = name, rssi = result.rssi))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                log("Scan failed: $errorCode")
                scanComplete.completeExceptionally(
                    PathError(
                        code = PathErrorCode.CONNECTIVITY,
                        message = "BLE scan failed (error $errorCode)",
                        recoverable = true
                    )
                )
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        log("Starting BLE scan...")
        scanner.startScan(null, settings, callback)

        try {
            withTimeout(SCAN_TIMEOUT_MS) { scanComplete.await() }
        } catch (_: TimeoutCancellationException) {
            // Expected — scan window elapsed
        } finally {
            scanner.stopScan(callback)
            log("Scan complete, found ${devices.size} device(s)")
        }

        devices
    }

    private fun matchesFilter(name: String): Boolean {
        val customFilter = deviceNameFilter
        if (customFilter != null) return customFilter(name)
        return name == "Path POS Emulator" || name.contains("Path")
    }

    // ── Connection ───────────────────────────────────────────────────────────

    override suspend fun connect(device: DiscoveredDevice): Unit = withContext(Dispatchers.IO) {
        // Clean up any existing connection first
        val hadExistingGatt = gatt != null
        gatt?.let {
            log("Closing previous GATT connection before reconnect")
            it.close()
            gatt = null
        }
        _isConnected = false
        rxCharacteristic = null
        txCharacteristic = null
        receiveBuffer.clear()

        // Give the BLE stack time to fully release resources — especially important on first
        // connect after a previous session, or if GATT 133 occurred on the last attempt.
        if (hadExistingGatt) {
            log("Waiting 500ms for BLE stack to settle after close...")
            delay(500)
        }

        val adapter = bluetoothAdapter ?: throw PathError(
            code = PathErrorCode.CONNECTIVITY,
            message = "Bluetooth not available",
            recoverable = false
        )

        val btDevice = adapter.getRemoteDevice(device.id)
        val connectionReady = CompletableDeferred<Unit>()
        var retryCount = 0
        val maxRetries = 3

        log("Connecting to ${device.name} (${device.id})...")

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                log("onConnectionStateChange: status=$status newState=$newState retry=$retryCount")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        log("GATT connected, discovering services...")
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        log("GATT disconnected (status=$status)")
                        if (!connectionReady.isCompleted) {
                            // GATT status 133 = GATT_ERROR — classic Android BLE reliability bug.
                            // Happens on first connection attempt after a fresh app start or
                            // after a previous disconnection. Auto-retry resolves it.
                            if (status == 133 && retryCount < maxRetries) {
                                retryCount++
                                log("GATT 133 error — auto-retry $retryCount/$maxRetries in 600ms...")
                                g.close()
                                gatt = null
                                CoroutineScope(Dispatchers.IO).launch {
                                    delay(600)
                                    log("Retrying connectGatt (attempt $retryCount)...")
                                    val cb = activeGattCallback
                                    if (cb != null && !connectionReady.isCompleted) {
                                        gatt = btDevice.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
                                    }
                                }
                            } else {
                                handleDisconnect()
                                connectionReady.completeExceptionally(
                                    PathError(
                                        code = PathErrorCode.CONNECTIVITY,
                                        message = "Connection lost (status=$status)",
                                        recoverable = true
                                    )
                                )
                            }
                        } else if (g === gatt) {
                            // Only handle disconnect for the active GATT — stale callbacks from
                            // a previous connection attempt must be ignored to prevent spurious
                            // disconnects when connectToDevice() is called concurrently.
                            log("Active GATT disconnected — handling disconnect")
                            handleDisconnect()
                        } else {
                            log("Ignoring disconnect from stale GATT (not the active connection)")
                            g.close()
                        }
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                log("onServicesDiscovered: status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectionReady.completeExceptionally(
                        PathError(
                            code = PathErrorCode.CONNECTIVITY,
                            message = "Service discovery failed (status=$status)",
                            recoverable = true
                        )
                    )
                    return
                }

                val service = g.getService(UART_SERVICE_UUID)
                if (service == null) {
                    log("Nordic UART service NOT found. Available services: ${g.services.map { it.uuid }}")
                    connectionReady.completeExceptionally(
                        PathError(
                            code = PathErrorCode.CONNECTIVITY,
                            message = "Nordic UART service not found on device",
                            recoverable = false
                        )
                    )
                    return
                }

                rxCharacteristic = service.getCharacteristic(UART_RX_UUID)
                txCharacteristic = service.getCharacteristic(UART_TX_UUID)
                log("RX characteristic: ${rxCharacteristic?.uuid}, TX characteristic: ${txCharacteristic?.uuid}")

                if (rxCharacteristic == null || txCharacteristic == null) {
                    connectionReady.completeExceptionally(
                        PathError(
                            code = PathErrorCode.CONNECTIVITY,
                            message = "UART characteristics not found",
                            recoverable = false
                        )
                    )
                    return
                }

                // Enable notifications on TX
                log("Enabling TX notifications...")
                val notifyResult = g.setCharacteristicNotification(txCharacteristic, true)
                log("setCharacteristicNotification result: $notifyResult")

                val descriptor = txCharacteristic!!.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    log("Writing CCCD descriptor to enable notifications...")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // API 33+ — use new writeDescriptor with byte array
                        val writeResult = g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        log("writeDescriptor (API 33+) result: $writeResult")
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        val writeResult = g.writeDescriptor(descriptor)
                        log("writeDescriptor (legacy) result: $writeResult")
                    }
                } else {
                    log("CCCD descriptor not found — notifications may not work!")
                    // Try proceeding anyway — some devices work without CCCD
                    _isConnected = true
                    gatt = g
                    connectionReady.complete(Unit)
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                log("onDescriptorWrite: uuid=${descriptor.uuid} status=$status")
                if (descriptor.uuid == CCCD_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        log("TX notifications enabled — connection ready")
                        _isConnected = true
                        gatt = g
                        connectionReady.complete(Unit)
                    } else {
                        log("CCCD write failed with status $status")
                        connectionReady.completeExceptionally(
                            PathError(
                                code = PathErrorCode.CONNECTIVITY,
                                message = "Failed to enable notifications (status=$status)",
                                recoverable = true
                            )
                        )
                    }
                }
            }

            // API 33+ callback — this is what fires on Android 13+
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                log("onCharacteristicChanged (API33+): uuid=${characteristic.uuid} bytes=${value.size}")
                if (characteristic.uuid == UART_TX_UUID) {
                    val chunk = String(value, Charsets.UTF_8)
                    log("RX chunk: '$chunk'")
                    handleReceivedData(chunk)
                }
            }

            // Pre-API 33 fallback — on API 33+ the new three-arg callback handles this,
            // so we must skip here to avoid double-processing every BLE notification.
            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                log("onCharacteristicChanged (legacy): uuid=${characteristic.uuid}")
                if (characteristic.uuid == UART_TX_UUID) {
                    @Suppress("DEPRECATION")
                    val chunk = characteristic.value?.let { String(it, Charsets.UTF_8) } ?: return
                    log("RX chunk: '$chunk'")
                    handleReceivedData(chunk)
                }
            }
        }

        activeGattCallback = gattCallback
        gatt = btDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        try {
            withTimeout(CONNECT_TIMEOUT_MS) { connectionReady.await() }
            log("Connection complete — logging in")
        } catch (e: TimeoutCancellationException) {
            gatt?.close()
            gatt = null
            throw PathError(
                code = PathErrorCode.TIMEOUT,
                message = "Connection timed out after ${CONNECT_TIMEOUT_MS / 1000}s",
                recoverable = true
            )
        }

        // Log in before declaring the connection ready — mirrors a real terminal
        // (and the Verifone backend), which require a login handshake on connect.
        // The notification pipe is up at this point.
        try {
            login()
        } catch (e: Exception) {
            gatt?.close()
            gatt = null
            _isConnected = false
            throw e
        }
        log("Logged in — ready for commands")
    }

    override suspend fun disconnect() {
        log("Disconnecting...")
        gatt?.disconnect()
        // Small delay to let disconnect complete before closing
        delay(200)
        gatt?.close()
        gatt = null
        _isConnected = false
        rxCharacteristic = null
        txCharacteristic = null
        receiveBuffer.clear()
        activeGattCallback = null
        log("Disconnected")
    }

    private fun handleDisconnect() {
        _isConnected = false
        rxCharacteristic = null
        txCharacteristic = null
        receiveBuffer.clear()
        pendingResponse?.completeExceptionally(
            PathError(code = PathErrorCode.CONNECTIVITY, message = "Device disconnected", recoverable = true)
        )
        pendingResponse = null
        onHardwareDisconnect?.invoke()
    }

    private fun handleReceivedData(chunk: String) {
        receiveBuffer.append(chunk)
        val content = receiveBuffer.toString()

        // Process all complete lines in the buffer (there may be multiple)
        var remaining = content
        while (true) {
            val newlineIndex = remaining.indexOf('\n')
            if (newlineIndex < 0) break

            val line = remaining.substring(0, newlineIndex).trim()
            remaining = remaining.substring(newlineIndex + 1)

            if (line.isEmpty()) continue

            // Strip "OK " prefix if present
            val jsonStr = if (line.startsWith("OK ")) line.removePrefix("OK ") else line

            if (!jsonStr.startsWith("{")) {
                log("Ignoring non-JSON line: '$line'")
                continue
            }

            // Determine message type — only complete pendingResponse for "result" messages
            // ACK ("type": "ack") and events ("type": "card_read") are intermediate
            val isResult = jsonStr.contains("\"type\": \"result\"") ||
                           jsonStr.contains("\"type\":\"result\"")
            val type = when {
                isResult -> "result"
                jsonStr.contains("\"type\": \"ack\"") || jsonStr.contains("\"type\":\"ack\"") -> "ack"
                jsonStr.contains("\"type\": \"card_read\"") || jsonStr.contains("\"type\":\"card_read\"") -> "card_read"
                else -> "unknown"
            }

            log("RX [$type]: ${jsonStr.take(120)}...")

            if (isResult) {
                log("Completing pending response with result")
                pendingResponse?.complete(jsonStr)
            } else {
                log("Intermediate message ($type) — not completing response")
            }
        }
        receiveBuffer = StringBuilder(remaining)
    }

    // ── Command Send/Receive ─────────────────────────────────────────────────

    private suspend fun sendCommand(
        command: String,
        timeoutMs: Long = RESPONSE_TIMEOUT_MS
    ): String = mutex.withLock {
        val g = gatt ?: throw PathError(code = PathErrorCode.CONNECTIVITY, message = "Not connected", recoverable = true)
        val rx = rxCharacteristic ?: throw PathError(code = PathErrorCode.CONNECTIVITY, message = "RX characteristic not available", recoverable = false)

        val deferred = CompletableDeferred<String>()
        pendingResponse = deferred

        val payload = (command + "\n").toByteArray(Charsets.UTF_8)
        log("TX (${payload.size} bytes): $command")

        // Chunk and send
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + CHUNK_SIZE, payload.size)
            val chunk = payload.copyOfRange(offset, end)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ writeCharacteristic
                val result = g.writeCharacteristic(rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                log("writeCharacteristic (API33+) chunk ${offset}..${end}: result=$result")
            } else {
                @Suppress("DEPRECATION")
                rx.value = chunk
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                val result = g.writeCharacteristic(rx)
                log("writeCharacteristic (legacy) chunk ${offset}..${end}: result=$result")
            }

            offset = end
            if (offset < payload.size) delay(CHUNK_DELAY_MS)
        }

        log("All chunks sent, waiting for response (timeout=${timeoutMs / 1000}s)...")

        // Wait for response
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingResponse = null
            log("RESPONSE TIMEOUT — no data received from terminal")
            throw PathError(
                code = PathErrorCode.TIMEOUT,
                message = "No response from terminal after ${timeoutMs / 1000}s",
                recoverable = true
            )
        }
    }

    private fun buildCommandJson(reqId: String, cmd: String, args: Map<String, Any?>): String =
        EmulatorWireJsonMapping.buildCommandJson(reqId, cmd, args)

    // ── Transactions ─────────────────────────────────────────────────────────

    // ── Login (connect-time handshake, protocol v1.3) ────────────────────────

    private suspend fun login() {
        val cmd = buildCommandJson(
            reqId = UUID.randomUUID().toString(),
            cmd = "Login",
            args = mapOf(
                "user_id" to username,   // legacy field — pre-v1.3 firmware still acks this
                "username" to username,
                "password" to password,
                "shift" to shift
            )
        )
        log("Login as '$username' (shift '$shift')...")
        val raw = sendCommand(cmd, timeoutMs = 10_000L)  // login is a quick handshake
        if (!raw.contains("\"status\": \"success\"") && !raw.contains("\"status\":\"success\"")) {
            throw PathError(
                code = PathErrorCode.CONNECTIVITY,
                message = "Emulator login was not accepted: ${raw.take(160)}",
                recoverable = true
            )
        }
        log("Logged in")
    }

    /**
     * Session ceremony: the emulator processes transactions directly, but each
     * is bracketed with a logged session open/close so the lifecycle mirrors the
     * Verifone backend (which opens/closes a PSDK session per payment).
     */
    private suspend fun <T> withSession(op: String, block: suspend () -> T): T {
        log("Session opened ($op)")
        try {
            return block()
        } finally {
            log("Session ended ($op)")
        }
    }

    override suspend fun sale(request: TransactionRequest): TransactionResult = withSession("SALE") {
        val args = mutableMapOf<String, Any?>(
            "amount" to request.amountMinor,
            "currency" to request.currency,
            "tip" to request.tipMinor
        )
        // Wire v1.1: when set, the terminal shows a customer-facing tip
        // selection screen before the card tap. The chosen tip lands in
        // the result's tipAmountMinor / tipPercentX10.
        if (request.promptForTip) args["prompt_for_tip"] = true

        val cmd = buildCommandJson(
            reqId = request.envelope.requestId,
            cmd = "Sale",
            args = args
        )
        // A sale that prompts for a tip can legitimately take up to ~56s on
        // the emulator (30s tip window + 26s tap window). Bump the timeout
        // from the 30s default with a bit of headroom.
        val timeoutMs = if (request.promptForTip) 75_000L else RESPONSE_TIMEOUT_MS
        val raw = sendCommand(cmd, timeoutMs = timeoutMs)
        EmulatorWireJsonMapping.mapResponse(raw, request.envelope.requestId)
    }

    override suspend fun refund(request: TransactionRequest): TransactionResult = withSession("REFUND") {
        val cmd = buildCommandJson(
            reqId = request.envelope.requestId,
            cmd = "Refund",
            args = mapOf(
                "amount" to request.amountMinor,
                "currency" to request.currency,
                // Wire v1.2 link field. The legacy original_req_id is kept for
                // pre-1.2 firmware (which ignored it anyway — it carried the
                // txn_id under the wrong name).
                "original_txn_id" to request.originalTransactionId,
                "original_req_id" to request.originalRequestId
            )
        )
        val raw = sendCommand(cmd)
        EmulatorWireJsonMapping.mapResponse(raw, request.envelope.requestId)
    }

    override suspend fun voidTransaction(request: TransactionRequest): TransactionResult = withSession("VOID") {
        // Void needs no card tap — it completes immediately on the terminal,
        // so the default 30s timeout is generous.
        val cmd = buildCommandJson(
            reqId = request.envelope.requestId,
            cmd = "Void",
            args = mapOf("txn_id" to request.originalTransactionId)
        )
        val raw = sendCommand(cmd)
        EmulatorWireJsonMapping.mapResponse(raw, request.envelope.requestId)
    }

    override suspend fun getTransactionStatus(requestId: String): TransactionResult {
        val cmd = buildCommandJson(
            reqId = java.util.UUID.randomUUID().toString(),
            cmd = "GetTransactionStatus",
            args = mapOf("req_id" to requestId)
        )
        val raw = sendCommand(cmd)
        return EmulatorWireJsonMapping.mapResponse(raw, requestId)
    }

    override suspend fun getReceiptData(transactionId: String): ReceiptData {
        val cmd = buildCommandJson(
            reqId = java.util.UUID.randomUUID().toString(),
            cmd = "GetReceipt",
            args = mapOf("txn_id" to transactionId)
        )
        val raw = sendCommand(cmd)
        return EmulatorWireJsonMapping.mapReceiptResponse(raw, transactionId)
    }

    override suspend fun cancelActiveTransaction() {
        val cmd = buildCommandJson(
            reqId = java.util.UUID.randomUUID().toString(),
            cmd = "Cancel",
            args = emptyMap()
        )
        sendCommand(cmd)
    }

    override suspend fun getCapabilities(): DeviceCapabilities {
        throw PathError(
            code = PathErrorCode.UNSUPPORTED_OPERATION,
            message = "GetCapabilities not supported by emulator",
            recoverable = false
        )
    }

    override suspend fun getDeviceInfo(): DeviceInfo {
        throw PathError(
            code = PathErrorCode.UNSUPPORTED_OPERATION,
            message = "GetDeviceInfo not supported by emulator",
            recoverable = false
        )
    }
}
