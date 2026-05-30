# Security policy

We take security in the Path Terminal SDK seriously. The SDK is embedded into partner Android EPOS applications that handle live card-present transactions, so vulnerability handling matters.

## Reporting a vulnerability

**Please do not report security issues via public GitHub issues, discussions, or pull requests.**

Email: **security@path2ai.tech**

Include:
- The affected component (e.g. `path-terminal-sdk`, `path-core-models`, `path-emulator-adapter`, `path-diagnostics`)
- The version(s) affected (JitPack tag, e.g. `v1.4`, or commit SHA preferred)
- A clear description of the vulnerability and its potential impact
- Reproduction steps or a proof-of-concept where possible
- Any suggested remediation

If you would prefer to encrypt your report, request our PGP public key in your initial mail and we will respond with the fingerprint.

## What to expect

| Step | Target |
|---|---|
| Acknowledgement of receipt | **2 business days** |
| Initial assessment + severity classification | **5 business days** |
| Status update cadence while triaging | **at least weekly** |
| Coordinated disclosure timeline | Agreed with the reporter; typically **90 days** from confirmation, sooner for actively-exploited issues |

We will credit you in the release notes when the issue is fixed, unless you ask us not to.

## Supported versions

The canonical matrix of which SDK, protocol and firmware versions receive security
updates is maintained once, in the iOS SDK repository, at
[`schemas/supported-versions.json`](https://github.com/keyman12/Path-terminal-sdk-IOS/blob/main/schemas/supported-versions.json).
The Android wire protocol is the shared contract; Android SDK version numbering is
independent of iOS.

In summary:
- Latest release line (currently **1.4.x**): **full support** (functional + security fixes)
- Previous release line: **security-only**
- Older lines: **end-of-life** — please upgrade

## In scope

- The Kotlin SDK modules (`path-terminal-sdk`, `path-core-models`, `path-emulator-adapter`, `path-diagnostics`, `path-mock-adapter`)
- The JitPack-published artifacts under `com.github.keyman12.path-terminal-sdk-android`
- The canonical JSON wire format and BLE transport shared with the Path POS Emulator

Specifically, we are interested in:
- Information disclosure (PAN, full transaction IDs, merchant secrets) in logs, support bundles, error messages, or wire traces
- Tampering with the BLE wire protocol or the canonical JSON wire format
- Issues in the redaction layer that allow sensitive data into a support bundle

## Out of scope

- The Pico **emulator firmware** (separate repo: `PosEmulator`) and any DoS / resource-exhaustion attacks against it — it is a test target, not a production device
- Social engineering of partners, their staff, or our team
- Physical attacks against terminals, EPOS devices, or developer machines
- Issues that require an already-compromised host operating system or a rooted device
- Findings limited to old, unsupported release lines (please test against a supported version first)

## Safe harbour

Good-faith security research, responsibly reported, will not result in legal action from us. Please do not exfiltrate real cardholder data; use the emulator and synthetic test PANs.
