package tech.path2ai.sdk.emulator

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.path2ai.sdk.core.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP adapter for the Path POS Emulator in Wi-Fi mode (wire protocol v1.2).
 *
 * The emulator serves the exact same newline-delimited JSON protocol over a
 * TCP socket (default port 9700) as it does over BLE — this adapter exists so
 * an EPOS integration destined for a Wi-Fi-connected production terminal
 * (e.g. a Verifone reached over TCP/IP) can be developed against the emulator
 * over the same connectivity. The emulator shows its IP:port on the welcome
 * screen when in Wi-Fi mode (Config → Connection).
 *
 * There is no scanning on TCP: [discoverDevices] returns a single synthetic
 * device for the configured host. The consuming app needs the INTERNET
 * permission (no Bluetooth permissions).
 */
class TcpPathTerminalAdapter(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val onLog: ((String) -> Unit)? = null
) : PathTerminalAdapter {

    companion object {
        private const val TAG = "TcpPathTerminal"

        /** The emulator's Wi-Fi mode port (deliberately not Verifone's 9600/9601). */
        const val DEFAULT_PORT = 9700

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val TIP_PROMPT_RESPONSE_TIMEOUT_MS = 75_000L
    }

    private val mutex = Mutex()
    private var socket: Socket? = null
    private var readerJob: Job? = null
    private var readerScope: CoroutineScope? = null

    @Volatile
    private var pendingResponse: CompletableDeferred<String>? = null

    private var _isConnected = false
    override val isConnected: Boolean get() = _isConnected

    override var onHardwareDisconnect: (() -> Unit)? = null

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke("[TCP] $msg")
    }

    // ── Discovery ────────────────────────────────────────────────────────────

    override suspend fun discoverDevices(): List<DiscoveredDevice> {
        // No scan on TCP — one synthetic device for the configured endpoint
        // (same UX shape as a host-addressed real terminal).
        return listOf(
            DiscoveredDevice(
                id = "$host:$port",
                name = "Path POS Emulator (Wi-Fi $host)",
                rssi = 0
            )
        )
    }

    // ── Connection ───────────────────────────────────────────────────────────

    override suspend fun connect(device: DiscoveredDevice): Unit = withContext(Dispatchers.IO) {
        disconnectInternal(notify = false)

        log("Connecting to $host:$port...")
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw PathError(
                code = PathErrorCode.CONNECTIVITY,
                message = "Could not connect to emulator at $host:$port (${e.message}). " +
                    "Is the emulator in Wi-Fi mode and on the same network?",
                recoverable = true
            )
        }
        socket = s
        _isConnected = true
        log("Connected — starting reader")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        readerScope = scope
        readerJob = scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (isActive) {
                    val line = reader.readLine() ?: break  // null = orderly close
                    handleLine(line)
                }
            } catch (e: Exception) {
                if (isActive) log("Reader error: ${e.message}")
            }
            if (isActive && _isConnected) {
                log("Connection lost")
                handleDisconnect()
            }
        }
    }

    override suspend fun disconnect() {
        log("Disconnecting...")
        disconnectInternal(notify = false)
        log("Disconnected")
    }

    private fun disconnectInternal(notify: Boolean) {
        _isConnected = false
        readerJob?.cancel()
        readerJob = null
        readerScope?.cancel()
        readerScope = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        pendingResponse?.completeExceptionally(
            PathError(code = PathErrorCode.CONNECTIVITY, message = "Disconnected", recoverable = true)
        )
        pendingResponse = null
        if (notify) onHardwareDisconnect?.invoke()
    }

    private fun handleDisconnect() {
        disconnectInternal(notify = true)
    }

    private fun handleLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
        val jsonStr = if (line.startsWith("OK ")) line.removePrefix("OK ") else line
        if (!jsonStr.startsWith("{")) {
            log("Ignoring non-JSON line: '$line'")
            return
        }

        // Same type discrimination as the BLE adapter: only "result" messages
        // complete the in-flight command; ack / card_read are intermediate.
        val isResult = jsonStr.contains("\"type\": \"result\"") ||
            jsonStr.contains("\"type\":\"result\"")
        log("RX [${if (isResult) "result" else "intermediate"}]: ${jsonStr.take(120)}")
        if (isResult) {
            pendingResponse?.complete(jsonStr)
        }
    }

    // ── Command Send/Receive ─────────────────────────────────────────────────

    private suspend fun sendCommand(
        command: String,
        timeoutMs: Long = RESPONSE_TIMEOUT_MS
    ): String = mutex.withLock {
        val s = socket ?: throw PathError(
            code = PathErrorCode.CONNECTIVITY, message = "Not connected", recoverable = true
        )

        val deferred = CompletableDeferred<String>()
        pendingResponse = deferred

        withContext(Dispatchers.IO) {
            try {
                val out = s.getOutputStream()
                out.write((command + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                log("TX: $command")
            } catch (e: Exception) {
                pendingResponse = null
                handleDisconnect()
                throw PathError(
                    code = PathErrorCode.CONNECTIVITY,
                    message = "Send failed: ${e.message}",
                    recoverable = true
                )
            }
        }

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

    // ── Transactions (same wire JSON as the BLE adapter) ─────────────────────

    override suspend fun sale(request: TransactionRequest): TransactionResult {
        val args = mutableMapOf<String, Any?>(
            "amount" to request.amountMinor,
            "currency" to request.currency,
            "tip" to request.tipMinor
        )
        if (request.promptForTip) args["prompt_for_tip"] = true

        val cmd = EmulatorWireJsonMapping.buildCommandJson(
            reqId = request.envelope.requestId,
            cmd = "Sale",
            args = args
        )
        val timeoutMs = if (request.promptForTip) TIP_PROMPT_RESPONSE_TIMEOUT_MS else RESPONSE_TIMEOUT_MS
        val raw = sendCommand(cmd, timeoutMs = timeoutMs)
        return EmulatorWireJsonMapping.mapResponse(raw, request.envelope.requestId)
    }

    override suspend fun refund(request: TransactionRequest): TransactionResult {
        val cmd = EmulatorWireJsonMapping.buildCommandJson(
            reqId = request.envelope.requestId,
            cmd = "Refund",
            args = mapOf(
                "amount" to request.amountMinor,
                "currency" to request.currency,
                "original_txn_id" to request.originalTransactionId,
                "original_req_id" to request.originalRequestId
            )
        )
        val raw = sendCommand(cmd)
        return EmulatorWireJsonMapping.mapResponse(raw, request.envelope.requestId)
    }

    override suspend fun voidTransaction(request: TransactionRequest): TransactionResult {
        val cmd = EmulatorWireJsonMapping.buildCommandJson(
            reqId = request.envelope.requestId,
            cmd = "Void",
            args = mapOf("txn_id" to request.originalTransactionId)
        )
        val raw = sendCommand(cmd)
        return EmulatorWireJsonMapping.mapResponse(raw, request.envelope.requestId)
    }

    override suspend fun getTransactionStatus(requestId: String): TransactionResult {
        val cmd = EmulatorWireJsonMapping.buildCommandJson(
            reqId = java.util.UUID.randomUUID().toString(),
            cmd = "GetTransactionStatus",
            args = mapOf("req_id" to requestId)
        )
        val raw = sendCommand(cmd)
        return EmulatorWireJsonMapping.mapResponse(raw, requestId)
    }

    override suspend fun getReceiptData(transactionId: String): ReceiptData {
        val cmd = EmulatorWireJsonMapping.buildCommandJson(
            reqId = java.util.UUID.randomUUID().toString(),
            cmd = "GetReceipt",
            args = mapOf("txn_id" to transactionId)
        )
        val raw = sendCommand(cmd)
        return EmulatorWireJsonMapping.mapReceiptResponse(raw, transactionId)
    }

    override suspend fun cancelActiveTransaction() {
        val cmd = EmulatorWireJsonMapping.buildCommandJson(
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
