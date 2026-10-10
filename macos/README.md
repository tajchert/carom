# Carom for macOS

The macOS side of Carom. Today this folder holds **CaromKit**, the reusable core of the Mac app
as a Swift package. The menu-bar app itself is the next step (see [What the app target
consumes](#what-the-app-target-consumes)).

| Path | What |
|---|---|
| [`CaromKit/`](CaromKit/) | Swift package: protocol, Hub client, storage, sync engine, tests |

The contracts are [`protocol/SPEC.md`](../protocol/SPEC.md) (wire format) and
[`docs/CLIENTS.md`](../docs/CLIENTS.md) (client behaviour). When they disagree, the spec wins.

## Requirements

- Xcode 27 (Swift 6.4), macOS 27 to build. The package targets **macOS 14** and later.
- Swift 6 language mode with strict concurrency; every target treats warnings as errors.
- Apple frameworks only: CryptoKit, Foundation, Security, Network. No third-party dependencies.

## Layout

```
CaromKit/
  Package.swift
  Sources/
    CaromProtocol/   SPEC §1–§5, §8: encodings, crypto, envelopes, records, pairing, rekey, QR
    CaromHub/        SPEC §6–§7: HTTP client, WebSocket client, network path observer
    CaromStore/      Keychain secrets, sync state persistence
    CaromSync/       docs/CLIENTS.md sync engine (an actor)
  Tests/
    CaromProtocolTests/  every file in protocol/vectors + unit tests
    CaromHubTests/       HubClient over a URLProtocol stub, HubSocket with a fake transport
    CaromStoreTests/     vault, state file, Keychain attributes
    CaromSyncTests/      two in-memory devices against a fake Hub; optional real-Hub test
```

Dependency graph: `CaromProtocol` ← `CaromHub`, `CaromStore` ← `CaromSync`.

### CaromProtocol

| File | Contents |
|---|---|
| `JSON.swift` | `JSON`/`JSONObject`: a strict RFC 8259 parser and a `JSON.stringify`-compatible serializer with ordered keys and JS number semantics. |
| `Bytes.swift`, `Base64URL.swift` | `Wire.lp`, `Wire.u64`, constant-time compare, `B64` strict base64url. |
| `Crypto.swift` | `Carom` constants, `Crypto` (CryptoKit wrappers), `KDF` (HKDF derivations). |
| `Identity.swift` | `DeviceIdentity`, `DeviceID` (derive, validate, fingerprint, sort). |
| `Seal.swift`, `Envelope.swift` | §2.3 seal; `Envelope`, `EnvelopeCodec.validateShape/seal/open`. |
| `Payload.swift`, `Messages.swift` | `Payload`, `Padding`, v1 bodies (`NotificationPosted`, `ClipboardSet`, …), `Message`, `Collapse`. |
| `Receive.swift` | `Receiver.receive`: the §3.3 rules 1–7 in order, with vector error codes. |
| `Record.swift` | `DeviceRecord`, `SealedRecord`, `RecordCodec.seal/open` (§5.3 checks). |
| `Box.swift`, `Pairing.swift`, `Rekey.swift` | §2.4 box; pairing mac/pop/candidate check/welcome; keyring build/verify/open. |
| `Token.swift`, `QR.swift`, `UUIDv7.swift`, `Routing.swift` | tokens, strict `carom://` URIs, monotonic msg_ids, §5.1/§5.2 routing. |

Failures from untrusted input are `Result` values whose error `rawValue`s are the vector codes
(`bad_signature`, `untrusted_sender`, …). Programmer errors trap.

### CaromHub

- `HubAPI`: one method per SPEC §6 route, `async throws(HubError)`. `HubClient` implements it on
  `URLSession`; `HubClient.factory()` builds a `HubFactory` for the engine.
- `HubError`: `.transport(URLError.Code)`, `.api(HubAPIError)` (status, code, message, and the
  `inactive` / `full` / `kv` extras), `.invalidResponse`, `.missingToken`; `isRetryable` covers
  network errors, 5xx and 429 without `full`.
- `HubSocket` (actor): the Mac connection policy. Protocol ping every 30 s, a 75 s watchdog, jittered
  backoff 1 → 60 s, close frame then hard cancel within 1 s (never waits for the close handshake),
  close code 4001 or a `revoked` frame stops it. Hooks: `systemWillSleep()`, `systemDidWake()`,
  `networkPathChanged(isSatisfied:)`, `sendAck(upTo:)`. Events: `AsyncStream<HubSocket.Event>`.
- `WebSocketConnection` / `WebSocketConnector`: the transport seam
  (`URLSessionWebSocketConnector` in production, fakes in tests).
- `NetworkPathObserver`: `NWPathMonitor` as a deduplicated `AsyncStream<Bool>`.

### CaromStore

- `SecretStore` protocol with `KeychainSecretStore` (generic passwords,
  `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, `kSecAttrSynchronizable = false`, data
  protection keychain by default) and `InMemorySecretStore` (tests, previews).
- `SecretVault` / `DeviceSecrets`: identity seeds, token and every group key, stored as **one**
  Keychain item so a rekey is atomic. Old keys are kept 7 days after they are retired.
- `StateStore` protocol with `JSONFileStateStore` (one JSON file in Application Support, written
  atomically, mode 0600, excluded from backup) and `InMemoryStateStore`. `SyncState` holds the
  Hub URL, device id and name, trusted directory, seen msg_ids, sent/received message references,
  outbox, last clipboard ts, the last msg_id, pending removal notices and free-form app settings.

### CaromSync

`SyncEngine` (actor) implements docs/CLIENTS.md:

| Area | API |
|---|---|
| Lifecycle | `load()`, `isConfigured`, `wipe()`, `setHandler(_:)` |
| First device | `setup(uri:)`, `setup(hubURL:setupToken:)` |
| Join | `beginJoin(uri:) -> JoinTicket` (show `fingerprint`), `completeJoin()`, `join(uri:)` |
| Invite | `createPairingInvite() -> PairingInvite` (QR `uri`), `pollPairing(pairId:)`, `approvePairing(pairId:)`, `rejectPairing(pairId:)`, `cancelPairing(pairId:)` |
| Directory | `refreshDirectory()`, `checkKeyring()`, `directory`, `rename(_:)`, `setReceiveRule(_:for:)` |
| Send | `send(_ body:, replyTo:, only:) -> SendOutcome`, `flushOutbox()`, `outbox` |
| Receive | `fetchMessages()`, `process(_:)`, `handle(_ event: HubSocket.Event)` |
| Realtime | `startRealtime(connector:configuration:) -> HubSocket?`, `stopRealtime()` |
| Revoke | `revoke(deviceId:)` (then rekey), `rekey(excluding:)`, `handleRevoked()` |

The app implements `SyncHandler`: `handle(_ message: IncomingMessage)` for decoded messages with
their trusted sender, and `handle(_ event: SyncEvent)` for `directoryChanged`,
`deviceRemoved(name:by:)`, `keyRotated`, `pairingCandidate`, `pairingRejected`, `messageDropped`,
`sendDropped`, `revoked` and `tokenRejected`. `EchoSuppressor` is used automatically for clipboard
sends and is public for the pasteboard watcher.

## How to test

```sh
cd macos/CaromKit
swift build
swift test
```

`swift test` runs, among others:

- **Vectors**: every JSON file in `protocol/vectors` (found relative to `#filePath`), including
  all negative cases. A test fails if a vector file is added that the suite does not consume.
  Signatures are checked by verification. Our own CryptoKit signatures (randomized) are verified
  against the vector public keys, and every deterministic output (AEAD ciphertexts, boxes, HKDF
  outputs, AADs, signature inputs, payload and record JSON, tokens, QR URIs) is compared byte
  for byte.
- **HubClient**: every route's method, path, headers and body through a `URLProtocol` stub, plus
  error mapping (409 `inactive`, 429 `full`, keyring 409 `kv`, 5xx, transport errors, 404 welcome).
- **HubSocket**: frame parsing, acks, pings, watchdog, reconnect backoff, 4001 / `revoked`,
  sleep/wake and network changes, using a fake transport and millisecond timings.
- **Sync engine**: a Mac and a phone (and a laptop) as in-memory devices against an in-memory
  `FakeHub`: setup, pairing with fingerprint check, phone → Mac notification, Mac → phone dismiss
  reply, clipboard both ways with echo suppression, routing, revoke + rekey with removal notices,
  late keyring fetch, `409 inactive` re-seal, outbox retry, bad pairing candidates, restart and
  reinstall, and socket-event handling.

Optional checks:

```sh
# Real login Keychain round trip (creates and deletes a temporary item).
CAROM_KEYCHAIN_TESTS=1 swift test --filter realKeychainRoundTrip

# Setup + pair + messages + WebSocket + revoke/rekey against a real Hub, which it then RESETS.
# The Hub must be fresh, or add CAROM_RESET_FIRST=1 to wipe it first.
CAROM_HUB_URL=https://carom-hub.<account>.workers.dev CAROM_SETUP_TOKEN=... \
  swift test --filter HubIntegrationTests
```

The integration test also runs against a local Hub: start `wrangler dev` for `hub/` with a
`SETUP_TOKEN` and point `CAROM_HUB_URL` at `http://127.0.0.1:<port>` (http is allowed for
loopback hosts).

## Design notes

- **Own JSON layer.** Payload and record plaintexts are signed and encrypted, and the vectors pin
  their exact bytes, so field order and escaping must match `JSON.stringify`. Validation must
  also mirror the reference (`Number.isInteger`, unknown fields rejected in envelopes, ignored in
  bodies). `JSONSerialization`/`Codable` guarantee neither, so the protocol layer uses a small
  strict `JSON` type. The models are still `Codable` for local persistence.
- **Randomized Ed25519.** CryptoKit signs with randomness, so signatures are never byte-compared;
  everything else is deterministic given the salt or ephemeral key, and is.
- **No plaintext at rest in the outbox.** Only the sealed envelope is stored. A re-seal after
  `409 inactive` or `429 full` decrypts our own envelope with the group key and seals it again
  under the same msg_id, kv and collapse value.
- **Transitional trust after a rekey.** Records under an older key are ignored (SPEC §5.3), but a
  member that has not yet republished after a rekey would otherwise drop out of the directory
  until it comes online. The engine keeps trusting a record sealed under the **previous** key
  only if the newest keyring version, verified and signed by a member, boxed the new key for
  that device. A revoked device is never in those boxes.
- **Rekey before judging.** A received batch with a kv newer than ours, or from an unknown
  sender, triggers one directory + keyring refresh before the §3.3 checks, so a device that
  missed a rekey or a pairing does not drop the first messages.
- **Removal notices.** A device that leaves the Hub's listing becomes a pending removal. A keyring
  version from R that excludes it yields "R removed X"; with no rekey within 2 minutes it is
  flagged as removed without a rekey (SPEC §5.4).
- **Acks.** Every handled or dropped message is acknowledged (cumulative `up_to`): over the socket
  when connected (each `msg` frame), otherwise `POST /v1/acks` after each `GET /v1/messages`
  page.
- **Privacy.** Logs (`os.Logger`, subsystem `app.carom.kit`) carry error codes only, never message
  content, keys, tokens or QR URIs.
- **Revocation.** A `revoked` frame, close code 4001, or revoking ourselves wipes the Keychain
  item and the state file. A 401 only raises `tokenRejected`; the app asks the user before
  wiping.

## What the app target consumes

The next step is a SwiftUI `MenuBarExtra` app generated with XcodeGen that depends on the
`CaromSync`, `CaromHub`, `CaromStore` and `CaromProtocol` products. It will:

1. Create the engine at launch with `KeychainSecretStore()` (signed app with a keychain access
   group, so the data protection keychain works), `JSONFileStateStore.applicationSupport()` and
   `HubClient.factory()`, call `load()`, and show onboarding when `isConfigured` is false.
2. Onboarding: paste a `carom://setup` or `carom://pair` URI, or read a QR code from a dropped or
   pasted image (Vision/CoreImage in the app), then `setup(uri:)` or `beginJoin` +
   `completeJoin`. Default device name: `Host.current().localizedName`.
3. Run `startRealtime()` and forward `NSWorkspace.willSleepNotification` /
   `didWakeNotification` to `systemWillSleep()` / `systemDidWake()` and `NetworkPathObserver`
   updates to `networkPathChanged(isSatisfied:)`; call `flushOutbox()` on a timer.
4. Implement `SyncHandler`: post one `UNNotificationRequest` per notification `key`
   (`threadIdentifier = <phone id>/<app id>`), with actions Dismiss on phone
   (`send(NotificationDismiss(key:), replyTo: msgId)`), Copy code and Copy text; withdraw on
   `notification.removed`; write `clipboard.set` to `NSPasteboard` with a marker type.
5. Watch `NSPasteboard.changeCount` every 500 ms (skipping concealed, transient, auto-generated
   and own items) and `send(ClipboardSet(text:sensitive:))`; global hotkey ⌃⌥⌘C.
6. Show pairing (`createPairingInvite`, QR from `uri` with CoreImage, 5-minute countdown,
   `pairingCandidate` → confirm with the fingerprint → `approvePairing` / `rejectPairing`), the
   device list (`directory`, `revoke`, `rename`, `setReceiveRule`) and the notices from
   `SyncEvent`.
