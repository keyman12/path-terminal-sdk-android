package tech.path2ai.sdk.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ModelSerializationTests {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `TransactionState serializes to snake_case`() {
        assertEquals("\"pending_device\"", json.encodeToString(TransactionState.PENDING_DEVICE))
        assertEquals("\"timed_out\"", json.encodeToString(TransactionState.TIMED_OUT))
        assertEquals("\"card_presented\"", json.encodeToString(TransactionState.CARD_PRESENTED))
        assertEquals("\"approved\"", json.encodeToString(TransactionState.APPROVED))
        assertEquals("\"refunded\"", json.encodeToString(TransactionState.REFUNDED))
    }

    @Test
    fun `TransactionState deserializes from snake_case`() {
        assertEquals(TransactionState.PENDING_DEVICE, json.decodeFromString<TransactionState>("\"pending_device\""))
        assertEquals(TransactionState.TIMED_OUT, json.decodeFromString<TransactionState>("\"timed_out\""))
        assertEquals(TransactionState.REFUND_PENDING, json.decodeFromString<TransactionState>("\"refund_pending\""))
    }

    @Test
    fun `PathErrorCode round-trip`() {
        for (code in PathErrorCode.entries) {
            val encoded = json.encodeToString(code)
            val decoded = json.decodeFromString<PathErrorCode>(encoded)
            assertEquals(code, decoded)
        }
    }

    @Test
    fun `PathError round-trip`() {
        val error = PathError(
            code = PathErrorCode.CONNECTIVITY,
            message = "Device unreachable",
            adapterErrorCode = "BLE_TIMEOUT",
            recoverable = true
        )
        val encoded = json.encodeToString(error)
        val decoded = json.decodeFromString<PathError>(encoded)
        assertEquals(error.code, decoded.code)
        assertEquals(error.message, decoded.message)
        assertEquals(error.adapterErrorCode, decoded.adapterErrorCode)
        assertEquals(error.recoverable, decoded.recoverable)
    }

    @Test
    fun `DiscoveredDevice round-trip`() {
        val device = DiscoveredDevice(id = "AA:BB:CC:DD:EE:FF", name = "Path POS Emulator", rssi = -42)
        val encoded = json.encodeToString(device)
        val decoded = json.decodeFromString<DiscoveredDevice>(encoded)
        assertEquals(device, decoded)
    }

    @Test
    fun `TransactionRequest sale factory`() {
        val envelope = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        val request = TransactionRequest.sale(amountMinor = 1250, currency = "GBP", envelope = envelope)
        assertEquals(1250, request.amountMinor)
        assertEquals("GBP", request.currency)
        assertNull(request.originalTransactionId)
        assertNull(request.tipMinor)
    }

    @Test
    fun `TransactionRequest refund factory`() {
        val envelope = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        val request = TransactionRequest.refund(
            amountMinor = 500,
            currency = "GBP",
            originalTransactionId = "txn-123",
            envelope = envelope
        )
        assertEquals(500, request.amountMinor)
        assertEquals("txn-123", request.originalTransactionId)
    }

    @Test
    fun `TransactionRequest round-trip`() {
        val envelope = RequestEnvelope.create(
            merchantReference = "ORDER-42",
            sdkVersion = "0.1.0",
            adapterVersion = "0.1.0"
        )
        val request = TransactionRequest.sale(amountMinor = 1000, currency = "GBP", envelope = envelope)
        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<TransactionRequest>(encoded)
        assertEquals(request.amountMinor, decoded.amountMinor)
        assertEquals(request.currency, decoded.currency)
        assertEquals(request.envelope.requestId, decoded.envelope.requestId)
        assertEquals(request.envelope.merchantReference, decoded.envelope.merchantReference)
    }

    @Test
    fun `TransactionResult computed properties`() {
        val approved = TransactionResult(
            transactionId = "txn-1",
            requestId = "req-1",
            state = TransactionState.APPROVED,
            amountMinor = 1000,
            currency = "GBP",
            timestampUtc = "2025-01-01T00:00:00Z"
        )
        assertTrue(approved.isApproved)
        assertTrue(approved.isFinal)

        val declined = approved.copy(state = TransactionState.DECLINED)
        assertFalse(declined.isApproved)
        assertTrue(declined.isFinal)

        val refunded = approved.copy(state = TransactionState.REFUNDED)
        assertTrue(refunded.isApproved)
        assertTrue(refunded.isFinal)

        val pending = approved.copy(state = TransactionState.PENDING_DEVICE)
        assertFalse(pending.isApproved)
        assertFalse(pending.isFinal)
    }

    @Test
    fun `TransactionResult round-trip`() {
        val result = TransactionResult(
            transactionId = "txn-42",
            requestId = "req-42",
            state = TransactionState.APPROVED,
            amountMinor = 2500,
            currency = "GBP",
            cardLastFour = "1234",
            receiptAvailable = true,
            timestampUtc = "2025-06-15T12:00:00Z"
        )
        val encoded = json.encodeToString(result)
        val decoded = json.decodeFromString<TransactionResult>(encoded)
        assertEquals(result, decoded)
    }

    @Test
    fun `RequestEnvelope create generates unique IDs`() {
        val e1 = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        val e2 = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        assertNotEquals(e1.requestId, e2.requestId)
        assertNotEquals(e1.idempotencyKey, e2.idempotencyKey)
    }

    @Test
    fun `RequestEnvelope create uses requestId as defaults`() {
        val e = RequestEnvelope.create(sdkVersion = "0.1.0", adapterVersion = "0.1.0")
        assertEquals(e.requestId, e.idempotencyKey)
        assertEquals(e.requestId, e.correlationId)
        assertEquals("0.1.0", e.sdkVersion)
    }

    @Test
    fun `RequestEnvelope create with custom idempotencyKey`() {
        val e = RequestEnvelope.create(
            idempotencyKey = "custom-key",
            sdkVersion = "0.1.0",
            adapterVersion = "0.1.0"
        )
        assertEquals("custom-key", e.idempotencyKey)
        assertNotEquals("custom-key", e.requestId)
    }

    @Test
    fun `CardReceiptFields round-trip`() {
        val fields = CardReceiptFields(
            copyLabel = "CUSTOMER COPY",
            txnType = "SALE",
            amount = 1500,
            currency = "GBP",
            cardScheme = "VISA",
            maskedPan = "****1234",
            entryMode = "CONTACTLESS",
            aid = "A0000000031010",
            verification = "NONE",
            authCode = "123456",
            merchantId = "MERCH001",
            terminalId = "TERM001",
            txnRef = "REF001",
            timestamp = "2025-01-01T12:00:00Z",
            status = "APPROVED",
            retainMessage = "Please retain for your records"
        )
        val encoded = json.encodeToString(fields)
        val decoded = json.decodeFromString<CardReceiptFields>(encoded)
        assertEquals(fields, decoded)
        // Verify snake_case keys in JSON
        assertTrue(encoded.contains("\"copy_label\""))
        assertTrue(encoded.contains("\"card_scheme\""))
        assertTrue(encoded.contains("\"masked_pan\""))
        assertTrue(encoded.contains("\"auth_code\""))
    }

    @Test
    fun `ReceiptData round-trip`() {
        val receipt = CardReceiptFields(
            copyLabel = "MERCHANT COPY", txnType = "SALE", amount = 1000,
            currency = "GBP", cardScheme = "MASTERCARD", maskedPan = "****5678",
            entryMode = "CHIP", aid = "A0000000041010", verification = "PIN",
            authCode = "654321", merchantId = "M001", terminalId = "T001",
            txnRef = "R001", timestamp = "2025-01-01T00:00:00Z", status = "APPROVED"
        )
        val data = ReceiptData(
            transactionId = "txn-99",
            requestId = "req-99",
            merchantReceipt = receipt,
            customerReceipt = receipt.copy(copyLabel = "CUSTOMER COPY"),
            timestampUtc = "2025-01-01T00:00:00Z"
        )
        val encoded = json.encodeToString(data)
        val decoded = json.decodeFromString<ReceiptData>(encoded)
        assertEquals(data, decoded)
    }

    @Test
    fun `DeviceCapabilities supports check`() {
        val caps = DeviceCapabilities(commands = listOf("Sale", "Refund", "Cancel"), nfc = true, display = true)
        assertTrue(caps.supports("Sale"))
        assertTrue(caps.supports("Refund"))
        assertFalse(caps.supports("GetDeviceInfo"))
    }

    @Test
    fun `SupportBundleSnapshotV1 JSON encoding`() {
        val snapshot = SupportBundleSnapshotV1(
            generatedAtUtc = "2025-01-01T00:00:00Z",
            integration = "path_sdk",
            sdkVersion = "0.1.0",
            connectionState = "connected",
            isReady = true,
            isBluetoothPoweredOn = true,
            logLineCount = 5,
            recentLogLines = listOf("line1", "line2"),
            transactionLogCount = 3
        )
        val jsonStr = SupportBundleSnapshotV1.encodeJson(snapshot)
        assertTrue(jsonStr.contains("\"bundle_version\""))
        assertTrue(jsonStr.contains("\"path_sdk\""))

        val pretty = SupportBundleSnapshotV1.encodePrettyString(snapshot)
        assertTrue(pretty.contains("\n"))
    }
}
