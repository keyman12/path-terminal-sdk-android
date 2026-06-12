package tech.path2ai.sdk.emulator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.path2ai.sdk.core.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Drives [TcpPathTerminalAdapter] against a fake single-client emulator
 * server on localhost speaking wire protocol v1.2 — the same ack→result
 * message shapes the Pico's wifi_service serves on port 9700.
 */
class TcpPathTerminalAdapterTests {

    private lateinit var server: ServerSocket
    private var serverThread: Thread? = null

    /** cmd → result JSON builder (req_id substituted in) */
    private var responder: ((cmd: String, reqId: String, args: String) -> String)? = null

    @BeforeEach
    fun startServer() {
        server = ServerSocket(0) // ephemeral port
        serverThread = thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val client: Socket = server.accept()
                    thread(isDaemon = true) { serveClient(client) }
                }
            } catch (_: Exception) {
                // socket closed — test over
            }
        }
    }

    @AfterEach
    fun stopServer() {
        try { server.close() } catch (_: Exception) {}
    }

    private fun serveClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val out = client.getOutputStream()
            while (true) {
                val line = reader.readLine() ?: break
                val cmd = Regex("\"cmd\":\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: continue
                val reqId = Regex("\"req_id\":\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: ""
                // ACK first (intermediate — must not complete the command)
                out.write(("""{"type": "ack", "cmd": "$cmd", "req_id": "$reqId", "status": "processing"}""" + "\n").toByteArray())
                out.flush()
                val result = responder?.invoke(cmd, reqId, line)
                if (result != null) {
                    out.write((result + "\n").toByteArray())
                    out.flush()
                }
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun newAdapter() = TcpPathTerminalAdapter(host = "127.0.0.1", port = server.localPort)

    private fun envelope() = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")

    @Test
    fun `discover returns single synthetic device`() = runBlocking {
        val adapter = newAdapter()
        val devices = adapter.discoverDevices()
        assertEquals(1, devices.size)
        assertEquals("127.0.0.1:${server.localPort}", devices[0].id)
        assertTrue(devices[0].name.contains("Wi-Fi"))
    }

    @Test
    fun `connect sale approved end to end`() = runBlocking {
        responder = { cmd, reqId, _ ->
            assertEquals("Sale", cmd)
            """{"type": "result", "cmd": "Sale", "req_id": "$reqId", "status": "approved", "txn_id": "txn-tcp-1", "amount": 1500, "base_amount": 1500, "tip_amount": 0, "total_amount": 1500, "currency": "GBP", "card_last_four": "4321", "auth_code": "654321", "receipt_available": true}"""
        }
        val adapter = newAdapter()
        adapter.connect(adapter.discoverDevices().first())
        assertTrue(adapter.isConnected)

        val result = adapter.sale(TransactionRequest.sale(amountMinor = 1500, currency = "GBP", envelope = envelope()))
        assertEquals(TransactionState.APPROVED, result.state)
        assertEquals("txn-tcp-1", result.transactionId)
        assertTrue(result.receiptAvailable)
        adapter.disconnect()
        assertFalse(adapter.isConnected)
    }

    @Test
    fun `void over tcp maps to reversed`() = runBlocking {
        responder = { cmd, reqId, line ->
            assertEquals("Void", cmd)
            assertTrue(line.contains("\"txn_id\":\"orig-1\""), "void must address original by txn_id: $line")
            """{"type": "result", "cmd": "Void", "req_id": "$reqId", "status": "reversed", "txn_id": "void-1", "original_txn_id": "orig-1", "amount": 1500, "currency": "GBP", "receipt_available": true}"""
        }
        val adapter = newAdapter()
        adapter.connect(adapter.discoverDevices().first())

        val result = adapter.voidTransaction(
            TransactionRequest.voidTransaction(originalTransactionId = "orig-1", envelope = envelope())
        )
        assertEquals(TransactionState.REVERSED, result.state)
        assertTrue(result.isApproved)
        adapter.disconnect()
    }

    @Test
    fun `refund sends original_txn_id link`() = runBlocking {
        responder = { cmd, reqId, line ->
            assertEquals("Refund", cmd)
            assertTrue(line.contains("\"original_txn_id\":\"sale-9\""), "refund must carry v1.2 link: $line")
            """{"type": "result", "cmd": "Refund", "req_id": "$reqId", "status": "approved", "txn_id": "ref-1", "amount": 500, "currency": "GBP", "receipt_available": true}"""
        }
        val adapter = newAdapter()
        adapter.connect(adapter.discoverDevices().first())

        val result = adapter.refund(
            TransactionRequest.refund(amountMinor = 500, currency = "GBP", originalTransactionId = "sale-9", envelope = envelope())
        )
        assertEquals(TransactionState.REFUNDED, result.state)
        adapter.disconnect()
    }

    @Test
    fun `connect to dead port throws connectivity error`(): Unit = runBlocking {
        val deadPort = server.localPort
        server.close() // nothing listening any more
        // Give the OS a moment to release the listener
        withContext(Dispatchers.IO) { Thread.sleep(50) }
        val adapter = TcpPathTerminalAdapter(host = "127.0.0.1", port = deadPort)
        val error = assertThrows<PathError> {
            adapter.connect(adapter.discoverDevices().first())
        }
        assertEquals(PathErrorCode.CONNECTIVITY, error.code)
    }
}
