# Path Terminal SDK — Android

You are working in the **Android Kotlin SDK** repo. Canonical remote: `https://github.com/keyman12/path-terminal-sdk-android`. Published to partners via **JitPack**.

## Ecosystem at a glance

One of **8 repos** in the Path semi-integrated terminal system:

| iOS | Android | Cross-platform |
|---|---|---|
| `Path-terminal-sdk-IOS` | `path-terminal-sdk-android` ← **you are here** | `Path-mcp-server` |
| `Path-epos-demo-sdk-IOS` | `Path-epos-demo-sdk-android` | `PosEmulator` (Pico firmware) |
| `Path-EPOS-TestHarness-IOS` | `Path-EPOS-TestHarness-Android` | |

See `Path-terminal-sdk-IOS/DEVELOPMENT.md` for the canonical map.

## Role

The Kotlin SDK partners consume via **JitPack** from their Android EPOS apps. Feature-parity mirror of the iOS SDK (`Path-terminal-sdk-IOS`).

## What lives here

- `path-core-models/` — `PathTerminal`, `TransactionRequest`, `TransactionResult`, `PathError`, `ReceiptData`, etc. (parallel to iOS `PathCoreModels`)
- `path-terminal-sdk/` — the main SDK entry point
- `path-emulator-adapter/` — adapters talking to the Pico emulator: `BLEPathTerminalAdapter` (Nordic UART) and `TcpPathTerminalAdapter` (emulator Wi-Fi mode, TCP :9700)
- `path-mock-adapter/` — mock adapter for tests
- `path-diagnostics/` — diagnostic helpers

## iOS parity — don't break it

Every model / factory method / error code here has an iOS counterpart. If you add, remove, or rename anything public:
- Mirror it in `Path-terminal-sdk-IOS/PathTerminalSDK/Sources/`
- Update shared `schemas/*.json` in the iOS SDK repo (single source of truth)
- Update MCP server examples (`Path-mcp-server/src/content/examples.ts` + `examples-android.ts`)
- Update both test harnesses' adapter layers

Silent drift between iOS and Android SDKs is the #1 way agentic installs start failing.

### Schema sync check

The iOS SDK repo has a sync check that compares `schemas/*.json` against both Swift and Kotlin data classes. Run it from that repo:

```bash
cd ../Path-terminal-sdk-IOS
node scripts/check-sdk-schema-sync.mjs
```

This repo being a sibling clone at `../path-terminal-sdk-android` is the expected layout. A CI workflow on the iOS SDK repo runs the same check automatically — any PR that introduces drift between Swift / Kotlin / schemas will fail there.

## Commands

```bash
# Build + test the whole project
./gradlew test

# Build a single module
./gradlew :path-terminal-sdk:build
./gradlew :path-core-models:test
```

## Versioning

Tags drive JitPack. When you cut a new version:
```bash
git tag v1.x
git push origin v1.x
```
JitPack builds on-demand from the tag. Version references in partner apps look like `com.github.keyman12.path-terminal-sdk-android:path-terminal-sdk:v1.x`.

Bump the pinned JitPack version in:
- `Path-EPOS-TestHarness-Android/CLAUDE.md` (the integration playbook)
- `Path-terminal-sdk-IOS/DEVELOPMENT.md` (the ecosystem doc)
