# Path Terminal SDK — Android

A Kotlin SDK for integrating Path payment terminals into Android EPOS apps. Provides a coroutine-based API for BLE device discovery, connection, transaction processing, and receipt retrieval against the **Path POS Emulator**.

Published on JitPack: `com.github.keyman12.path-terminal-sdk-android`

---

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `path-core-models` | `path-core-models` | Shared models, adapter interface, state definitions |
| `path-terminal-sdk` | `path-terminal-sdk` | `PathTerminal` — main SDK entry point |
| `path-emulator-adapter` | `path-emulator-adapter` | BLE adapter for the Path POS Emulator |
| `path-mock-adapter` | `path-mock-adapter` | In-process mock adapter for unit tests |
| `path-diagnostics` | `path-diagnostics` | Log ring buffer, support bundle generation |

---

## Quick Start

### 1. Add JitPack and SDK to Gradle

`settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`app/build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.github.keyman12.path-terminal-sdk-android:path-core-models:v1.4")
    implementation("com.github.keyman12.path-terminal-sdk-android:path-terminal-sdk:v1.4")
    implementation("com.github.keyman12.path-terminal-sdk-android:path-emulator-adapter:v1.4")
}
```

### 2. Add BLE permissions

`AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

Request permissions at runtime (Android 12+):
```kotlin
val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ... }
launcher.launch(arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT
))
```

### 3. Initialise

```kotlin
val bleAdapter = BLEPathTerminalAdapter(
    context = context,
    sdkVersion = "1.4.0",
    adapterVersion = "1.4.0"
)
val terminal = PathTerminal(bleAdapter)
```

### 4. Collect events

```kotlin
scope.launch {
    terminal.events.collect { event ->
        when (event) {
            is PathTerminalEvent.ConnectionStateChanged ->
                updateUI(event.state)
            is PathTerminalEvent.TransactionStateChanged ->
                showProgress(event.state)
            is PathTerminalEvent.ReceiptReady ->
                displayReceipt(event.receipt)
            is PathTerminalEvent.Error ->
                handleError(event.error)
            else -> {}
        }
    }
}
```

### 5. Discover and connect

```kotlin
// Scan for nearby terminals
val devices: List<DiscoveredDevice> = terminal.discoverDevices()

// Connect (coroutine — suspends until connected or throws)
terminal.connect(devices.first())
```

### 6. Process a sale

```kotlin
val envelope = RequestEnvelope.create(
    merchantReference = "ORDER-001",
    sdkVersion = "1.4.0",
    adapterVersion = "1.4.0"
)
val request = TransactionRequest.sale(
    amountMinor = 450,          // pence
    currency = "GBP",
    envelope = envelope
)

val result = terminal.sale(request)

if (result.isApproved) {
    println("Auth: ${result.transactionId}")
}
```

### 7. Fetch receipt data

```kotlin
if (result.isApproved && result.receiptAvailable) {
    val receipt = terminal.getReceiptData(result.transactionId!!)
    val cr = receipt.customerReceipt
    // cr.cardScheme, cr.maskedPan, cr.authCode, cr.aid, etc.
}
```

### 8. Process a refund

```kotlin
val refundEnvelope = RequestEnvelope.create(
    merchantReference = "REFUND-001",
    sdkVersion = "1.4.0",
    adapterVersion = "1.4.0"
)
val refundRequest = TransactionRequest.refund(
    amountMinor = 450,
    currency = "GBP",
    originalTransactionId = result.transactionId!!,
    envelope = refundEnvelope
)

val refundResult = terminal.refund(refundRequest)
```

---

## Key Types

### `ConnectionState`

```kotlin
sealed class ConnectionState {
    data object Idle        : ConnectionState()
    data object Scanning    : ConnectionState()
    data object Connecting  : ConnectionState()
    data object Connected   : ConnectionState()
    data object Disconnected: ConnectionState()
    data class  Error(val message: String) : ConnectionState()
}
```

### `PathTerminalEvent`

```kotlin
sealed class PathTerminalEvent {
    data class ConnectionStateChanged(val state: ConnectionState) : PathTerminalEvent()
    data class DeviceDiscovered(val device: DiscoveredDevice)    : PathTerminalEvent()
    data class TransactionStateChanged(val state: String)        : PathTerminalEvent()
    data class ReceiptReady(val receipt: Any)                    : PathTerminalEvent()
    data class Error(val error: PathError)                       : PathTerminalEvent()
    data class Prompt(val message: String)                       : PathTerminalEvent()
}
```

### `TransactionResult`

| Field | Type | Description |
|-------|------|-------------|
| `isApproved` | `Boolean` | Whether the transaction was authorised |
| `transactionId` | `String?` | Terminal transaction reference |
| `requestId` | `String` | SDK request identifier |
| `state` | `TransactionState` | Final state |
| `receiptAvailable` | `Boolean` | Whether receipt data can be fetched |
| `cardLastFour` | `String?` | Last 4 digits of PAN |
| `error` | `PathError?` | Set when not approved |

### `CustomerReceipt`

| Field | Description |
|-------|-------------|
| `status` | Transaction status string |
| `timestamp` | ISO 8601 timestamp |
| `txnRef` | Terminal transaction reference |
| `terminalId` | Terminal ID |
| `merchantId` | Merchant ID |
| `authCode` | 6-character authorisation code |
| `verification` | Cardholder verification method |
| `aid` | EMV Application Identifier |
| `entryMode` | e.g. `Contactless`, `Chip` |
| `maskedPan` | e.g. `****1234` |
| `cardScheme` | e.g. `Visa`, `Mastercard` |

---

## AI-assisted integration (recommended)

For a complete EPOS integration in one command, run from your Android Studio project directory:

```bash
node <(curl -s https://mcp.path2ai.tech/init.js) --agent claude
```

This configures Gradle dependencies and runs a Claude Code agent that wires the SDK into your project end-to-end — scan, connect, sale, refund, receipts. See the [MCP server README](https://github.com/keyman12/Path-mcp-server) for full details.

---

## Testing with the Path POS Emulator

1. Power on the Path Pico W emulator (broadcasts BLE automatically)
2. Call `terminal.discoverDevices()` — the emulator will appear in the list
3. Call `terminal.connect(device)` — first connection can take up to 15s on Android BLE
4. Submit a sale for `amountMinor = 100` (= £1.00 GBP)
5. Tap the NFC tag on the emulator when prompted
6. Verify `result.isApproved == true` and `result.transactionId` is present

---

## Running Tests

```bash
./gradlew test
```

Tests use `path-mock-adapter` — no hardware required.

---

## Structure

```
path-terminal-sdk-android/
├── path-core-models/        — Models, adapter interface, error types
├── path-terminal-sdk/       — PathTerminal, PathTerminalEvent, ConnectionState
├── path-emulator-adapter/   — BLEPathTerminalAdapter, BLE wire protocol
├── path-mock-adapter/       — MockPathTerminalAdapter for tests
└── path-diagnostics/        — PathDiagnostics, log ring buffer
```

---

## Related repos

| Repo | Purpose |
|------|---------|
| [path-terminal-sdk](https://github.com/keyman12/Path-terminal-sdk) | iOS SDK (Swift Package) |
| [Path-EPOS-TestHarness-Android](https://github.com/keyman12/Path-EPOS-TestHarness-Android) | Android EPOS test harness for agent integration testing |
| [Path-mcp-server](https://github.com/keyman12/Path-mcp-server) | MCP integration assistant (iOS + Android) |
| [PosEmulator](https://github.com/keyman12/PosEmulator) | Pico W firmware |
