# Carom for Android

The Android client of Carom: it forwards phone notifications to your desktops and syncs the
clipboard between your own devices, end-to-end encrypted, through a Hub you run yourself
(Cloudflare Worker, [`hub/`](../hub/)). The wire contract is [`protocol/SPEC.md`](../protocol/SPEC.md);
what the apps do with it is [`docs/CLIENTS.md`](../docs/CLIENTS.md). When this README and the spec
disagree, the spec wins.

The project was adopted from the team's Android template (Compose, Navigation 3, Hilt, Room 3,
DataStore, Kover, screenshot tests, CI) and its Tasks sample was removed.

## Status

Done (foundation):

- `core:protocol`: the complete protocol v1 in pure Kotlin/JVM, passing every case of every
  file in `protocol/vectors/`.
- `core:hub`: an HTTP client for every SPEC §6 route and an OkHttp WebSocket client (§7).
- `core:security`: Android Keystore-wrapped storage for identity keys, group keys and the token.
- `core:database`, `core:datastore`: Room schema v4 (v1 to v2 adds `forwarded_notifications`, v2 to
  v3 drops `directory.has_push`, v3 to v4 adds `notification_apps.forward_ongoing` and
  `icon_uploads`; `MigrationTest` checks every migration against the exported schemas) and
  preferences.
- `core:data`: the `CaromSync` engine (directory, outbox send pipeline, SPEC §3.3 receive
  pipeline with refresh-before-drop, §5.3 transitional trust, §5.5 chained keyring verification,
  rekey/revocation, §5.4 removal notices and the `401` "pair again" latch, "Leave hub" rekeying
  without itself before it deletes itself, the §3.3 rule 7 dismiss key check against the original
  message in the outbox, and the §5.3 joiner keyring fetch) and `Enrollment`
  (setup, join, invite), tested against an in-memory Hub; WorkManager + Hilt worker factory;
  `OutboxWorker`, `FetchWorker`; the sync runtime (`runtime/`).
- Home (status, last sync, quick actions, problem banners, recent items), Devices
  (list, rename, remove with rekey, receive-from matrix) and Settings (device name, Apps and
  Clipboard sub-screens, pause, about, leave hub).
- Onboarding (`feature:onboarding`): shown at start when there are no keys or Hub (and again after
  a revocation). Welcome, set up a new Hub or join, CameraX + zxing QR scanner (camera permission
  asked on that step only) or paste, progress and cause-and-fix errors, the joining device's
  fingerprint while it waits for approval, "Name this device", permissions (notification access
  deep link with the Android 13+ restricted-settings hint, `POST_NOTIFICATIONS`, optional battery
  exemption), done.
- Pairing (`feature:pairing`, `PairingRoute`): "Add device" shows the QR code and a copyable URI
  with a 5-minute countdown, polls `GET /v1/pair/:id` every 2 s while visible, and asks
  "Add device <fingerprint>?". `:app` binds `PairCandidateFeed` to
  `LiveHubConnection.pairCandidates`, so a `pair` frame on the foreground WebSocket shows the
  prompt at once.
- `feature:notifications`: phone -> desktop mirroring. `CaromListenerService` (declared in the
  module's own manifest, merged into the app, asking the system for every notification type)
  hands each notification to `NotificationForwarder`. `RichExtractor` reads the whole
  notification (V2 spec §3.3: template, colours, big text, inbox lines, messaging with avatars,
  big picture, actions with reply choices, progress and Android 16 Live Updates through string
  extras keys, calls, groups, channel) into a snapshot; the filters drop our own, system
  autogroup summaries, local-only, minimum-importance and media notifications, and let ongoing
  ones through only for Live Updates, progress, calls or an app set to "Ongoing: Forward".
  `ImageEncoder` loads the images right away and encodes them as WebP within the caps (app icon
  128 px, small icon 96 px alpha mask, large icon and avatars 192 px, big picture 1080 px and 400
  KB); `DefaultBlobUploader` uploads them through `CaromSync.uploadBlob` (3 at a time, convergent
  icons remembered in the `icon_uploads` table). Each notification key has one worker: the 1.5 s
  trailing-edge throttle, the content-hash dedupe and the "one send in flight" rule keep an older
  state from overtaking a newer one while images upload. `NotificationMapper` builds the body,
  capped at 6 messages, 7 lines and 6 actions and trimmed to 65 000 bytes; *Hide content* sends
  only the app (with its icon), the time and `redacted: true`. Forwarded keys are
  kept in Room, so removals are sent across listener reconnects and process death; on connect the
  listener sends `removed` for forwarded keys that are no longer showing.
  `notification.dismiss@1` cancels the notification if it is still showing (the engine has
  already checked that the key matches the posted message it replies to). The "Apps" screen
  (`NotificationAppsRoute`) lists apps seen posting notifications with their Forward / Hide
  content / Off mode and the "Ongoing: Forward / Off" switch.
- Actions, media and code forwarding (`feature:notifications:impl`, packages `actions/`, `media/`
  and `codes/`). `ActionExecutor` runs `notification.action@1` (answer, decline, the app's
  buttons, inline reply) through `PendingIntent.send` with background activity start allowed, and
  answers with `notification.action_result@1` (`gone`, `changed`, `blocked`, `failed`).
  `MediaBridge` sends `media.state@1` (debounced to 1 s, art as a blob) and
  `MediaCommandHandler` runs `media.command@1` through the session's transport controls.
  `ActiveCallTracker` holds the action window open for calls and playing media.
  `ActionsForegroundService` is the "Always" mode. The code-forwarding screen creates a
  `CompanionDeviceManager` association (self-managed profile first) and falls back to the adb
  command (`AdbFallback`). `RedactionPrompt` posts the quiet "Tap to enable" notification.
  Which association profile unlocks codes on Android 15 and 16 still needs checking on a device.
- Settings → "Actions from Mac" (Off / After a notification with 2, 5 or 15 minutes / Always). Off
  removes `notification.action@1` and `media.command@1` from the published `caps`
  (`SetActionsFromMacUseCase`).


### Fetching (docs/CLIENTS.md "Connection policy (phone)")

| Trigger | What runs |
|---|---|
| App visible (`ProcessLifecycleOwner` start) | `ForegroundSync`: flush outbox, refresh directory, then a WebSocket (`ForegroundSocketSession`) that processes and acks frames, refreshes on `dir`, wipes on `revoked`, reconnects with jittered backoff. Closed when the app goes to the background. |
| After a successful send | `PiggybackFetch` schedules a `FetchWorker`, unless the socket is live. |
| A forward whose send response lists a desktop in `online` | `ActionWindow` opens a `ForegroundSocketSession` for the configured minutes. Each forward, received action or media change restarts the timer. A call or playing media holds it open. "Always" keeps it open while paired (`ActionsForegroundService`). |

`LiveHubConnection.pairCandidates` exposes `pair` frames from the live socket for the pairing screen.
There is no push and no periodic background polling (SPEC §9): messages from a desktop to this
phone (`clipboard.set`, `notification.dismiss`) are fetched only while the app is awake or an action
window is open, which is
why the Clipboard settings tell the user that a clipboard from a Mac arrives when Carom is opened.

## Baseline

| Area | Choice |
|---|---|
| Language | Kotlin 2.4.20, Java 17 bytecode |
| UI | Jetpack Compose, Material 3 |
| Navigation | Navigation 3 1.2.0 with typed, serializable keys |
| DI | Hilt (+ `androidx.hilt:hilt-work`) |
| Persistence | Room 3.0.3, Preferences DataStore, Android Keystore |
| Network | OkHttp 5.5.0, kotlinx-serialization |
| Crypto | Tink 1.23.0 (`tink-android`) + JCA |
| Background | WorkManager 2.12.0 |
| Build | AGP 9.4.1, Gradle 9.8.0, version catalog, configuration cache |
| Android | compileSdk 37, targetSdk 37, **minSdk 29** |
| App id | `com.mtajchert.carom.app` (debug: `com.mtajchert.carom.app.debug`) |

## Module map

```text
app ── feature/*/impl ── feature/*/api
          │
          ├─ core:domain ── core:data ─┬─ core:hub ──────┐
          │                            ├─ core:security ─┼─ core:protocol (pure JVM)
          │                            ├─ core:database  │
          │                            └─ core:datastore │
          └─ core:ui ── core:designsystem               core:model (pure JVM)
```

| Module | What belongs there |
|---|---|
| `:app` | `MainActivity`, `CaromNavHost` (Home is the start), `CaromApplication` (Hilt root, WorkManager `Configuration.Provider`), manifest, backup/network XML |
| `:core:protocol` | SPEC as code: `B64`, `lp`/`u64`, `Crypto` (Ed25519, X25519, HKDF, ChaCha20-Poly1305), `Envelopes`, `Payload`, `MessageTypes`/`MessageBodies` (V2 rich bodies, actions, media), `Blobs` (encrypted image blobs), `Receiver` (§3.3), `Records`, `Routing`, `Boxes`, `Pairing`, `Rekey`, `Tokens`, `QrUri`, `UuidV7Generator`, `Collapse` |
| `:core:hub` | `HubClient` (one suspend function per route, including `putBlob`/`headBlob`/`getBlob`, `HubException` errors), `HubSocket`, `Backoff`, `HubClientFactory` |
| `:core:security` | `SecretVault` / `FileSecretVault` / `AndroidKeystoreCipher` |
| `:core:database` | `CaromDatabase` (outbox, seen_messages, directory, notification_apps, recent_items, forwarded_notifications, icon_uploads) |
| `:core:datastore` | `UserPreferencesDataSource` (Hub URL, device id/name, toggles, theme, last clipboard ts) |
| `:core:data` | `CaromSync`/`DefaultCaromSync` (incl. `uploadBlob` for encrypted images), `Enrollment`, stores, repositories, `OutboxWorker`, `FetchWorker`, `IncomingMessageHandler`/`WipeHook`/`SyncStartHook` multibindings, `runtime/` (foreground socket, `SyncRuntime`, `ActionWindow.kt` with `ActivitySignal`, an optional Hilt binding declared in `di/ActivitySignalModule.kt`) |
| `:core:domain` | `ObserveHomeStatusUseCase`, `SendClipboardUseCase`, rename/remove, receive matrix |
| `:core:model` | App models (`SessionState`, `ConnectionStatus`, `DeviceSummary`, `UserPreferences`, …) |
| `:core:ui`, `:core:designsystem` | Shared composables and the Carom theme |
| `:core:common` | Dispatcher qualifiers, `Result` |
| `:core:testing` | `FakeHub` (in-memory Hub with a blob store and `online` recipients), `InMemorySecretVault`, in-memory stores, `FakeCaromSync`, fake repositories, `MainDispatcherRule` |
| `:feature:{home,onboarding,pairing,devices,settings,notifications}:{api,impl}` | api: one `NavKey` route each. impl: screen, ViewModel, `xxxEntries(...)` navigation contribution |
| `:feature:notifications:impl` | `CaromListenerService`, `RichExtractor`, `NotificationFilter`, `ImageEncoder`, `BlobUploader`, `UpdateThrottle`, `NotificationMapper`; `actions/` (`ActionExecutor`, `NotificationActionHandler`, `ActiveCallTracker`, `ActionsForegroundService`); `media/` (`MediaBridge`, `MediaCommandHandler`, art encoding and upload); `codes/` (code-forwarding screen, `CompanionDeviceManager` association, `AdbFallback`, `RedactionPrompt`) |
| `:feature:clipboard:{api,impl}` | Two-way clipboard: `ClipboardSend.intent`, transparent `ClipboardSendActivity`, exported share target `ShareTargetActivity` and Quick Settings tile, `clipboard.set@1` handler (sensitive flag, 10 s echo guard, optional quiet notification with "Copy again"), clipboard settings screen (`ClipboardSettingsRoute`; "Send clipboard to" is stored locally, "Receive clipboard from" goes through `SetReceiveRuleUseCase`), the optional ongoing "Carom" notification with a Send clipboard action |
| `:benchmark` | Startup Macrobenchmark, Baseline Profile generator, `StartupSmokeTest` |

Rules: features depend on other features' **api** modules only and navigate through callbacks
wired in `CaromNavHost`; core modules never depend on features; `core:protocol` and
`core:model` stay free of Android.

### Extension points for the next features

- **Receiving a message type**: implement `IncomingMessageHandler` and bind it with
  `@Binds @IntoSet`. The set's types become this device's `caps` and known types.
- **Sending**: `CaromSync.send(type, body, …)` routes, seals, stores in the outbox and posts;
  on `SendResult.Queued` call `SyncWorkScheduler.scheduleOutboxFlush()` (as
  `SendClipboardUseCase` does).
- **Fetching**: `SyncWorkScheduler.scheduleFetch()` after tile/share actions; the foreground
  and piggyback triggers are already wired (see "Fetching" above).
- **Receive rules**: `SetReceiveRuleUseCase` is the only writer: `invoke` changes one matrix cell,
  `setRule` sets a whole rule ("Receive clipboard from"). Rules persist in `ReceiveRulesStore`,
  which the published record and both screens read; the record is republished once per change.
- **Leaving / wiping**: anything that must happen before the keys go away implements `WipeHook`.
- **Onboarding/pairing UI**: `Enrollment.setUpNewHub`, `join`, `createInvite`,
  `checkCandidate`/`verify`, `approve`, `reject`; parse scanned/pasted text with `QrUri.parse`.

## Running

Prerequisites: JDK 21 for Gradle, Android SDK 37 and Build Tools 36.0.0, an API 29+ device or
emulator. See [First run and troubleshooting](docs/GETTING_STARTED.md).

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug :app:lintRelease :app:assembleRelease
./gradlew :app:installDebug
```

[Testing](docs/TESTING.md) lists every command (screenshots, coverage, device tests).

## Protocol vectors

`:core:protocol` is a plain Kotlin/JVM module. Its tests read the shared vectors straight from
`../protocol/vectors/` (relative to `android/`): Gradle passes the directory as the
`carom.vectors.dir` system property and declares it as a test input, so editing a vector re-runs
the tests. `ProtocolVectorsTest` consumes every case of every file, compares deterministic
outputs byte for byte and checks signatures by verification; `everyVectorFileIsConsumed` fails
when a new vector file appears without a test. CI runs on changes to `android/**` and
`protocol/vectors/**`.

```bash
./gradlew :core:protocol:test
```

Crypto: Tink's `subtle` Ed25519/X25519/HKDF and `aead.internal.InsecureNonceChaCha20Poly1305`
(the spec's single-use key with a zero nonce needs an explicit-nonce AEAD), JCA for SHA-256 and
HMAC. Tink has pure-Java fallbacks, so the same code runs on the JVM test runner and on devices,
without depending on the platform's stripped BouncyCastle.

## Documentation

- [First run and troubleshooting](docs/GETTING_STARTED.md)
- [Testing](docs/TESTING.md) and [Compose screenshot testing](docs/SCREENSHOT_TESTING.md)
- [Security and backup defaults](docs/SECURITY_DEFAULTS.md)
- [Release workflow](docs/RELEASING.md), [Localization](docs/LOCALIZATION.md)
- [Performance testing](docs/PERFORMANCE_TESTING.md)
- [Agent setup](docs/AGENT_SETUP.md) and the repository rules in [AGENTS.md](AGENTS.md)

## Quality contract

`.github/workflows/android.yml` (repository root) runs unit tests (including all protocol
vectors), lint, debug and minified release builds, Keystore device tests and a cold-start
journey on an emulator, and validates screenshot references on macOS. Do not use destructive
Room migrations, do not swallow coroutine cancellation, and never log keys, tokens, setup/pair
URIs, or notification and clipboard content.
