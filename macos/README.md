# Carom for macOS

The macOS side of Carom: **CaromKit**, the reusable core as a Swift package, and **Carom**, the
menu-bar app built on it (see [The menu-bar app](#the-menu-bar-app)).

| Path | What |
|---|---|
| [`CaromKit/`](CaromKit/) | Swift package: protocol, Hub client, storage, sync engine, desktop services, tests |
| [`scripts/`](scripts/) | End-to-end test of the app against a local Hub with a simulated phone |
| [`App/`](App/) | The Carom menu-bar app: XcodeGen spec, generated Xcode project, sources, unit tests |

The contracts are [`protocol/SPEC.md`](../protocol/SPEC.md) (wire format) and
[`docs/CLIENTS.md`](../docs/CLIENTS.md) (client behaviour). When they disagree, the spec wins.

## Requirements

- Xcode 27 (Swift 6.4), macOS 27 to build. The package targets **macOS 14** and later.
- Swift 6 language mode with strict concurrency; every target treats warnings as errors.
- Apple frameworks only: CryptoKit, Foundation, Security, Network, and in `CaromDesktop` AppKit,
  SwiftUI, UserNotifications, Intents, Carbon and ServiceManagement. No third-party dependencies.

## Layout

```
CaromKit/
  Package.swift
  Sources/
    CaromProtocol/   SPEC §1–§5, §8: encodings, crypto, envelopes, records, pairing, rekey, QR
    CaromHub/        SPEC §6–§7: HTTP client, WebSocket client, network path observer
    CaromStore/      Keychain secrets, sync state persistence
    CaromSync/       docs/CLIENTS.md sync engine (an actor)
    CaromDesktop/    Desktop services and views: clipboard watcher, hotkey, login item, notification
                     presenter and coordinator, rich cards, Live Updates, media, calls, blob store
  Tests/
    CaromProtocolTests/  every file in protocol/vectors + unit tests
    CaromHubTests/       HubClient over a URLProtocol stub, HubSocket with a fake transport
    CaromStoreTests/     vault, state file, Keychain attributes
    CaromSyncTests/      two in-memory devices against a fake Hub; optional real-Hub test
    CaromDesktopTests/   coordinator routing, cards and stacks, Live maths, BlobStore, CodeExtractor
```

Dependency graph: `CaromProtocol` ← `CaromHub`, `CaromStore` ← `CaromSync`; `CaromDesktop` depends
on `CaromProtocol` only.

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

`Blobs.swift` (SPEC §11: seal and open, the convergent key) and `RichNotification.swift` /
`RichMessages.swift` (the V2 `notification.posted@1` fields, `notification.action@1`,
`notification.action_result@1`, `media.state@1`, `media.command@1`) hold the V2 types.

### CaromDesktop

| Path | Contents |
|---|---|
| `NotificationCoordinator.swift` | Routes every posted, removed, media and action-result event to the panel, native notifications, the Live chip and history. Owns the card timers ("Sending…", "Phone is asleep" after 30 s) |
| `NotificationPresenter.swift` | The native macOS notification (passive while the panel is on), its categories (Copy code, Reply, Dismiss on phone, Copy text) |
| `Rich/` | `RichPanel` (the non-activating `NSPanel`), `CardModel`, `CardStack` (grouping, order, "+N more"), card views and styles, `CardActionsRow` (buttons, reply field), `FocusStatus` (`INFocusStatusCenter`) |
| `Live/` | `LiveUpdateCardView`, `LiveMath` (segments, chronometer, countdown), `LiveChip` (menu-bar item) |
| `Media/` | `MediaSession` (state extrapolation), `NowPlayingView` |
| `Call/` | `CallCardView`, `CallRinger` (looped sound) |
| `BlobStore.swift` | Fetch, hash check, decrypt, disk cache (50 MB, LRU, icons last), 4 fetches at a time |
| `CodeExtractor.swift` | One-time codes: 4 to 10 digits, letter-digit with keyword |
| `ClipboardWatcher.swift`, `HotkeyManager.swift`, `LoginItem.swift` | Clipboard polling and writes, the global hotkey, the login item |

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
| Send | `send(_ body:, replyTo:, only:) -> SendOutcome`, `sendReturningMsgId(...)` (also the msg_id), `flushOutbox()`, `outbox` |
| Receive | `fetchMessages()`, `process(_:)`, `handle(_ event: HubSocket.Event)` |
| Realtime | `startRealtime(connector:configuration:) -> HubSocket?`, `stopRealtime()`, `realtimeState`, `realtimeStates()` (offline / connecting / connected; a fresh stream per call) |
| Revoke | `revoke(deviceId:)` (another device: delete, then rekey; this device: rekey without itself, then delete, SPEC §5.4), `rekey(excluding:)`, `handleRevoked()` |

Trust details beyond the basics: a device that joined through a welcome fetches the newest keyring
version's box list (`GET /v1/keyring?have=<kv − 1>`, verified with a trusted member's key) so
records still sealed under kv − 1 by boxed devices count (SPEC §5.3). Former members' `sign_pub`s
are cached with the newest kv they were members under, so a keyring version written by a device
that was revoked since still verifies (SPEC §5.5 step 1). A `notification.dismiss@1` must name the
key of the `notification.posted@1` it replies to (SPEC §3.3 rule 7).

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
- **HubClient**: every route's method, path, headers and body through a `URLProtocol` stub, the
  clock check (`Date` header, `hub_time` of a `clock_skew` 400), plus
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

# Setup + pair + messages + WebSocket state + revoke/rekey + self-leave + joiner transitional
# trust + dismiss key check against a real Hub (deployed, or `wrangler dev`), then revokes its devices.
# The Hub must be fresh: there is no reset route, change its SETUP_TOKEN secret to wipe it.
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

## The menu-bar app

`App/` holds **Carom**, a SwiftUI `MenuBarExtra` (window style) agent app (`LSUIElement`, no Dock
icon) for macOS 14 and later. It depends on the `CaromProtocol`, `CaromHub`, `CaromStore`,
`CaromSync` and `CaromDesktop` products of the local CaromKit package.

### Build and run

Requires [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`) when you
change `project.yml`. The generated `Carom.xcodeproj` is committed, so a plain checkout builds.

```sh
cd macos/App
xcodegen generate                      # after editing project.yml; commit both

# From the repository root:
xcodebuild -project macos/App/Carom.xcodeproj -scheme Carom -configuration Debug build
xcodebuild -project macos/App/Carom.xcodeproj -scheme Carom -configuration Debug test

# Run the built app (or press Run in Xcode):
open ~/Library/Developer/Xcode/DerivedData/Carom-*/Build/Products/Debug/Carom.app
```

On first launch the onboarding window opens; afterwards Carom lives in the menu bar. To start
from scratch, quit Carom, delete `~/Library/Application Support/Carom/state.json` and the
`com.mtajchert.carom.mac.secrets` Keychain item.

### Signing and the Keychain

| Setting | Value |
|---|---|
| Bundle id | `com.mtajchert.carom.mac` |
| Team | `A7WLJ7NW34` (automatic signing, Apple Development), set in `App/Config/Signing.xcconfig`. The team id is the certificate's OU, not the id in its name: `security find-certificate -c "Apple Development" -p \| openssl x509 -noout -subject` |
| Hardened runtime | on (`ENABLE_HARDENED_RUNTIME`) |
| App Sandbox | off for now (Developer ID distribution later); the code avoids sandbox-hostile APIs |

Override signing locally in `App/Config/Signing.local.xcconfig` (git-ignored): another team with
`DEVELOPMENT_TEAM = <id>`, or ad hoc signing on a Mac without a certificate:

```
DEVELOPMENT_TEAM =
CODE_SIGN_IDENTITY = -
```

or pass `DEVELOPMENT_TEAM=… CODE_SIGN_IDENTITY=…` to `xcodebuild`.

The data protection keychain needs a `keychain-access-groups` entitlement, which on macOS requires
a provisioning profile. The project does not declare it yet so that command-line builds work
without an Xcode account. `SecretStoreFactory` keeps using whichever keychain already holds this
Mac's keys; otherwise it uses the data protection keychain if a write/read/delete probe works, and
the login keychain if not (still `kSecAttrSynchronizable = false`). Team-signed Debug builds use the
login keychain without prompts across rebuilds; with an ad hoc signature macOS may ask for access
after each rebuild. For
release builds add `keychain-access-groups: [$(AppIdentifierPrefix)com.mtajchert.carom.mac]` to the
entitlements in `project.yml` and sign with a profile.

### Layout

```
App/
  project.yml            XcodeGen spec (targets Carom and CaromTests, scheme Carom)
  Carom.xcodeproj/       generated, committed
  Config/                Signing.xcconfig (+ optional Signing.local.xcconfig)
  Info.plist, Carom.entitlements   generated from project.yml
  Resources/             asset catalog (accent colour, app icon slot)
  Sources/
    App/                 CaromApp: MenuBarExtra, Onboarding / Add Device windows, Settings
    Model/               AppModel, Preferences, ReceivePolicy, activity, banners, user copy
    Services/            SyncService seam over SyncEngine, CaromDesktop seams, QR (CoreImage +
                         Vision), sleep/wake + network monitor, Keychain choice
    Views/               menu popover, onboarding, add device, Settings tabs
  Tests/                 Swift Testing: AppModel against fakes, policies, preferences, QR round
                         trip, Keychain choice
scripts/                 e2e.sh + fake-phone.mjs: the real app against a local Hub (see below)
```

### How it works

- **`AppModel`** (`@MainActor @Observable`) owns the `SyncEngine` (through the `SyncService`
  protocol so tests use a fake) and the realtime socket. It forwards `NSWorkspace` sleep/wake and
  `NetworkPathObserver` changes to `HubSocket`, flushes the outbox every 30 s and on wake, and
  implements the handler side through `SyncBridge` (`SyncHandler` → main actor).
- **Sending to phones** is pull-only (SPEC §9): a clipboard item for a phone waits at the Hub until the
  phone is opened. Settings › Clipboard and the Send Clipboard confirmation say so.
- **Connection state** comes from `SyncEngine.realtimeStates()` (connected after the first frame
  or pong; the engine pings once right away), combined with sleep and no-network from the system
  monitor. After 45 s without a connection a "Can't reach your Hub" banner appears.
- **Notifications.** `notification.posted` → `NotificationCoordinator` (native copy via
  `NotificationPresenter`) unless paused, the
  phone is turned off in our `receive` rules, or the app is muted locally. The phone name is shown
  only with more than one phone. `removed` withdraws it (and drops it from the menu). Redacted
  notifications with no text read "Content hidden on your phone." *Dismiss on phone* sends
  `notification.dismiss@1` with `reply_to`. Copying a code or text from a notification suppresses
  automatic sending for 2 s, so it is not sent straight back.
- **Rich notifications.** `NotificationCoordinator` (CaromDesktop) receives every posted, removed
  and action-result message. With *Show notifications as: Rich* (the default) a non-activating
  `NSPanel` at the top right of the screen under the pointer shows Android-like cards: at most 4
  stacks, then "+N more"; cards fade after 6 s unless the pointer is over them; conversations,
  calls and Live Updates stay until handled or removed; one app's group collapses into
  "App · N new". The native notification is posted at the passive level, so it lands in
  Notification Center without a second banner. The panel takes the keyboard only while a reply
  field is open. During a Focus only the native notification posts. The app's buttons and Reply
  (`notification.action@1`) appear only for phones whose caps include it; a card
  shows "Sending…", then the phone's result, or "Phone is asleep — will send when it wakes" after
  30 s. When the phone re-posts a conversation with the user's own reply appended (newest
  message without a sender), the card changes in place without a sound and still fades.
  *Native only* uses banners and no panel.
- **Clipboard.** `clipboard.set` is written with `ClipboardWatcher.write` (marker type, so the
  watcher skips it; the engine also suppresses echoes). Automatic sending defaults to on when
  pasteboard access is *Always allow* (`NSPasteboard.accessBehavior`, macOS 15.4+) and on macOS
  before 15.4 (no access control there); otherwise it is off and Settings explains how to allow
  it. Onboarding's *Check Access* button reads the clipboard once, so Carom appears in the system's
  pasteboard access list. The watcher only runs while automatic sending is on
  and Carom is not paused. ⌃⌥⌘C and *Send Clipboard* send the current item at any time.
- **Settings are persisted** in the sync state through `SyncEngine.setSetting("app.preferences",
  …)`: pause, muted and seen apps, automatic clipboard, the "Send to" list and onboarding
  completion. Per-phone notification on/off and "Receive from" are written as our record's
  `receive` rules (`setReceiveRule`), so senders stop sending. A rule where everyone is allowed
  collapses to `"*"`, which covers devices paired later. A list of ids (and a "Send to" list) is
  an explicit choice: devices paired later are not added and show unchecked (docs/CLIENTS.md).
- **Quit** unregisters the global hotkey and stops the pasteboard watcher. The login item toggle
  uses `SMAppService.mainApp` and says so when System Settings has to approve it.
- **Problems** surface as banners in the popover: 401 (`tokenRejected`: syncing stops, *Pair
  Again…* erases the keys after confirmation), revocation (back to onboarding), removal notices
  ("<by> removed <name>", "<name> left your devices", or "removed without a key change"), Hub
  unreachable, failed pairing
  checks and dropped sends. When this Mac's clock is more than 5 minutes off the Hub's (`Date` header
  of any answer, `hub_time` of a `clock_skew` 400; `ClockSkewTracker` in CaromHub, cleared below 4
  minutes) a banner offers *Open Date & Time Settings…* and one system notice is posted per episode.
- **Privacy.** Recent notifications and clips live in memory only; sensitive clips are never
  shown. Logs carry no content, keys, tokens or URIs.

### Scripted runs and the end-to-end test (Debug builds)

Debug builds accept launch arguments (Release builds ignore them; see
`Sources/App/DebugLaunch.swift`):

| Argument | Effect |
|---|---|
| `-caromProfile <name>` | separate state folder (`Application Support/Carom-<name>`) and Keychain service |
| `-caromSetupURI <uri>` | set up a Hub without the onboarding UI |
| `-caromInviteFile <path>` | create a pair invite and write its URI there (mode 0600; it holds the pair secret) |
| `-caromAutoApprove YES` | approve a verified pairing candidate |
| `-caromEventLog <path>` | append event names and message types, never content |
| `-caromShowWindows menu,settings` | open the popover content in a window, and Settings, for screenshots |
| `-caromAutoReply <text>` | load the images of the first card with a reply action, then reply on it |
| `-caromAutoMedia <command>` | send `play`, `pause`, `next` or `previous` to the first media session offering it |

`scripts/e2e.sh` uses them to run the real app against a local Hub with a simulated phone
(`scripts/fake-phone.mjs`, real crypto from `@carom/protocol`): setup, pairing, notification
posted and removed, clipboard, and the V2 set: an encrypted avatar blob (uploaded and read
back), a rich messaging card with a reply action that the app answers and the phone confirms
with `notification.action_result@1`, a progress Live Update with two updates, and a
`media.state@1` that the app answers with `media.command@1`. It checks the event log and the
pasteboard, then prints a short list of things to check on screen:

```sh
npm install && (cd protocol/ts && npm run build)      # once, at the repository root
printf 'SETUP_TOKEN=%s\n' "$TOKEN" > hub/.dev.vars      # TOKEN: 40+ random characters
(cd hub && npx wrangler dev --port 8790) &
printf '{"setupToken":"%s"}' "$TOKEN" > /tmp/carom-token.json
macos/scripts/e2e.sh http://localhost:8790 /tmp/carom-token.json \
  ~/Library/Developer/Xcode/DerivedData/Carom-*/Build/Products/Debug/Carom.app
```

The test Hub on Cloudflare works too: deploy it with `node hub/bin/carom-hub.mjs update --name
carom-hub-dev` and pass its URL and `~/.config/carom-hub/carom-hub-dev.json` (it holds
`setupToken`). Only use a throwaway Hub: the token ends up in the app's launch arguments.

It needs a fresh Hub (change its `SETUP_TOKEN` to wipe one), uses the `e2e` profile, and overwrites the clipboard with a test marker.
Notification banners only appear once Carom is allowed to post notifications.

### README images

The PNGs in [`docs/images/`](../docs/images/) are rendered by `App/Tests/ReadmeImages.swift`, a
test that is skipped unless `CAROM_README_IMAGES=1`. Run `App/Tools/readme-images.sh` to
regenerate them: it draws the real `MenuBarView` on an `AppModel` wired to the test fakes,
illustrates the macOS notification banners, and frames the Android screenshot-test references
(regenerate those first if the Android UI changed). Times follow the Mac's locale.
