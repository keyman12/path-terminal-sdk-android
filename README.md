# Path Terminal SDK — Android

A Kotlin SDK for integrating Path payment terminals into Android apps. Provides a clean, coroutine-based API for BLE device discovery, connection, and transaction processing against the **Path POS Emulator**.

## Modules

| Module | Package | Purpose |
|--------|---------|---------|
| `path-core-models` | `tech.path2ai.sdk.core` | Shared data models, interfaces, state machines |
| `path-terminal-sdk` | `tech.path2ai.sdk` | `PathTerminal` — core SDK entry point |
| `path-emulator-adapter` | `tech.path2ai.sdk.emulator` | BLE adapter for Path POS Emulator hardware |
| `path-mock-adapter` | `tech.path2ai.sdk.mock` | In-process mock adapter for unit tests |
| `path-diagnostics` | `tech.path2ai.sdk.diagnostics` | Log ring buffer, support bundle generation |

## Quick Start

### 1. Add the SDK

**Gradle composite build** (recommended for development):

In your app's `settings.gradle.kts`:

```kotlin
includeBuild("../path-terminal-sdk-android") {
    dependencySubstitution {
        substitute(module("tech.path2ai.sdk:path-core-models")).using(project(":path-core-models"))
        substitute(module("tech.path2ai.sdk:path-terminal-sdk")).using(project(":path-terminal-sdk"))
        substitute(module("tech.path2ai.sdk:path-emulator-adapter")).using(project(":path-emulator-adapter"))
    }
}
```

In `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("tech.path2ai.sdk:path-terminal-sdk:0.1.0")
    implementation("tech.path2ai.sdk:path-emulator-adapter:0.1.0")
}
```

### 2. Add permissions

`AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

### 3. Initialise and connect

```kotlin
val bleAdapter = BLEPathTerminalAdapter(
    context = context,
    sdkVersion = "0.1.0",
    adapterVersion = "0.1.0"
)
val terminal = PathTerminal(bleAdapter)

// Collect events
scope.launch {
    terminal.events.collect { event ->
        when (event) {
            is PathTerminalEvent.ConnectionStateChanged -> { /* update UI */ }
            is PathTerminalEvent.TransactionStateChanged -> { /* show state */ }
            else -> {}
        }
    }
}

// Scan and connect
val devices = terminal.scanForDevices()
terminal.connectToDevice(devices.first().id)
```

### 4. Process a sale

```kotlin
val result = terminal.submitTransaction(
    TransactionRequest(
        type = TransactionType.Sale,
        amount = 450,           // pence
        currency = "GBP",
        reference = "ORDER-001",
        operatorId = "cashier1",
        lineItems = listOf(LineItem("Flat White", 1, 450))
    )
)

if (result.approved) {
    println("Auth: ${result.authorisationCode}")
    // result.receiptData contains EMV receipt fields
}
```

### 5. Process a refund

```kotlin
val refund = terminal.submitRefund(
    originalTransactionId = result.transactionId!!,
    amount = 450
)
```

## Key Types

### `ConnectionState`

```kotlin
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting   : ConnectionState()
    data object Connected    : ConnectionState()
    data class  Failed(val reason: String) : ConnectionState()
}
```

### `TransactionRequest`

| Field | Type | Description |
|-------|------|-------------|
| `type` | `TransactionType` | `Sale` or `Refund` |
| `amount` | `Int` | Amount in pence |
| `currency` | `String` | ISO 4217 code e.g. `"GBP"` |
| `reference` | `String` | Merchant order reference |
| `operatorId` | `String` | Cashier identifier |
| `lineItems` | `List<LineItem>` | Basket items for receipt |

### `TransactionResult`

| Field | Type | Description |
|-------|------|-------------|
| `approved` | `Boolean` | Whether the transaction was authorised |
| `transactionId` | `String?` | Terminal transaction reference |
| `authorisationCode` | `String?` | 6-digit auth code |
| `maskedPan` | `String?` | e.g. `****1234` |
| `cardScheme` | `String?` | e.g. `Visa`, `Mastercard` |
| `receiptData` | `ReceiptData?` | Full EMV receipt fields |
| `failureReason` | `String?` | Set when `approved = false` |

## Running Tests

```bash
./gradlew test
```

46 tests across all modules. Tests use `MockPathTerminalAdapter` — no hardware required.

## Structure

```
path-terminal-sdk-android/
├── path-core-models/        — Models, adapter interface, state machine
├── path-terminal-sdk/       — PathTerminal, PathTerminalEvent, ConnectionState
├── path-emulator-adapter/   — BLEPathTerminalAdapter, wire protocol
├── path-mock-adapter/       — MockPathTerminalAdapter for tests
└── path-diagnostics/        — PathDiagnostics, log ring buffer
```

## Reference App

See [Path-epos-demo-sdk-android](https://github.com/keyman12/Path-epos-demo-sdk-android) for a complete working integration — Jetpack Compose EPOS app with all SDK features in use.
