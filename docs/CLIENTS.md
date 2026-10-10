# Client behaviour (Android and macOS)

[`protocol/SPEC.md`](../protocol/SPEC.md) defines the wire format. This document defines what
the apps do with it, so both platforms behave the same way. When the two disagree, the spec wins.

## Principles

- **Simple:** pairing is one QR scan. Sensible defaults mean a new user never opens settings.
- **Battery first (phone):** no long-running service of our own. Work happens when the system
  wakes us (notification listener callback, push, user action), and then we stop.
- **Universal:** no Hub URL is baked into a build. It always comes from a setup or pair URI.
- **Private:** keys never leave the device, are never backed up, and are never logged. Notification
  and clipboard content are never logged either.

## Identity and storage

| Item | Android | macOS |
|---|---|---|
| `sign_sk`, `kx_sk`, group keys, device token | Encrypted with an Android Keystore AES-GCM key; stored in a no-backup file | Keychain, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, not synchronizable |
| Hub URL, device id, directory cache, seen `msg_id`s, outbox, settings | Room + DataStore. `allowBackup=false`, everything excluded from backup and device transfer | SQLite or JSON files in Application Support |

When the app finds it has no usable keys (reinstall or restore), it starts onboarding again.

## Onboarding

1. **Welcome:** explain what Carom does and that you need your own Hub (link to the docs).
2. **Connect:** either
   - **Set up a new hub:** scan or paste the `carom://setup` URI printed by `npx carom-hub deploy`,
     then `POST /v1/setup`, generate `K` (kv 1) and publish the record; or
   - **Join:** scan or paste a `carom://pair` URI shown by an already-paired device.
   Phones scan with the camera. Macs paste the URI, or read a QR code from a dropped or pasted
   image. A Mac can also *show* a pair QR code for a phone to scan.
3. **Name this device:** the default comes from the system device name.
4. **Permissions:**
   - Android: notification access (deep link to the listener settings, with a hint about
     "restricted settings" for sideloaded APKs), the `POST_NOTIFICATIONS` runtime permission, and
     an optional battery-optimisation exemption.
   - macOS: notification permission, plus a pasteboard access explanation (*Always allow*).

## Pairing UI (the device that invites)

"Add device" calls `POST /v1/pair/offer`, generates `pair_secret` and shows the QR code (plus a
copyable URI) with a 5-minute countdown. When the candidate arrives:

1. Verify `mac`, `pop` and `device_id`.
2. Ask the user: "Add *<kind>* device `<fingerprint>`?"
3. On yes, post the welcome. On no, reject.

The fingerprint is the first 8 characters of the candidate's `device_id` after `dev_`. It is
shown on both screens.

## Sync engine (shared logic)

**Directory**
- Refresh on startup, on a `dir` frame, after pairing, and after any `409 inactive`. Refresh means
  `GET /v1/devices`, opening every record (SPEC §5.3), and caching the trusted set.
- When a device disappears without a rekey, show a notice: "*<name>* was removed".
- After a directory change, also check `GET /v1/keyring?have=<kv>`.

**Sending**
- Build the payload, route with the receive rules (SPEC §5.2), seal it, store it in the outbox,
  and `POST /v1/messages`.
- On a network error or 5xx, retry with backoff. On 409, refresh the directory and re-seal
  under the same `msg_id`. On 429 `full`, drop those recipients. On other 4xx, drop the message
  and log the error code only.
- Android sends straight from the listener callback, and falls back to WorkManager (network
  constraint, exponential backoff).

**Receiving**
- Run the SPEC §3.3 checks, dispatch by type, and ack with `up_to` after handling.
- Seen `msg_id`s are kept for 7 days.

**Connection policy**
- Mac: always-on WebSocket. Protocol ping every 30 s. A watchdog drops the socket after 75 s
  with no frame or pong. Disconnect on sleep and reconnect on wake or network change, with
  jittered backoff of 1 to 60 s. Never wait for a close handshake (SPEC §7).
- Phone: no persistent socket. It fetches with `GET /v1/messages` when:
  - a push arrives,
  - the app comes to the foreground (a WebSocket is allowed while the UI is visible),
  - after each successful send (piggyback), and
  - after the Quick Settings tile or share actions.

**Push (phone)**
- Register with UnifiedPush using the VAPID key from `/v1/info`, then `PUT /v1/devices/self/push`.
  Re-register whenever the endpoint changes.
- If no distributor is available, show this in settings and rely on the other fetch triggers.

**Revoking (any device can revoke any device)**
1. `DELETE /v1/devices/:id`.
2. Rekey (SPEC §5.5).
3. Republish our own record.

Other devices republish their records when they see the new key. A device that receives
`revoked` wipes its keys and returns to onboarding.

## Notifications (phone → desktop)

**Phone filters, applied before sending**
- Skip:
  - our own notifications,
  - ongoing and foreground-service notifications,
  - group summaries,
  - notifications with `FLAG_LOCAL_ONLY`,
  - silent or `IMPORTANCE_MIN` channels,
  - media-style notifications.
- **Per-app setting:** *Forward* (default on, except for a small built-in deny list of system
  packages), *Hide content* (send only the app name and "New notification"), or *Off*. The app
  list is built from apps seen posting notifications, without `QUERY_ALL_PACKAGES`.
- **Global settings:** a pause toggle, and "Only when screen is locked" (default off).

**Mapping (Android `StatusBarNotification` → `notification.posted@1`)**

| Body field | Source |
|---|---|
| `key` | `sbn.key` |
| `app.id` | package name |
| `app.name` | application label |
| `title` | `EXTRA_TITLE` (or `EXTRA_CONVERSATION_TITLE`) |
| `text` | `EXTRA_BIG_TEXT` or `EXTRA_TEXT`; for `MessagingStyle`, the last message as "sender: text" |
| `sub_text` | `EXTRA_SUB_TEXT` |
| `category` | `notification.category` |
| `posted_at` | `sbn.postTime` |
| `redacted` | true when Android 15+ replaced the content ("Sensitive notification content hidden") |

- Text fields are truncated at 4000 characters.
- A notification update re-sends `posted` with the same collapse key, at most once per 2 s per key.
- When the notification is removed, send `removed`.

**Desktop presentation**
- One macOS notification per `key`, with `threadIdentifier = <phone id>/<app id>`. The title is
  "*App* · *title*". The subtitle is the phone name, shown only when more than one phone is paired.
- Actions:
  - **Dismiss on phone** sends `notification.dismiss@1` with `reply_to`.
  - **Copy code** appears when the text contains a 4–8 digit code.
  - **Copy text.**
- `removed` withdraws the macOS notification.
- Plain text only. URLs are never opened automatically.

**Desktop filters**
- Per phone: on or off. This is written into the record's `receive` rules, so the phone stops
  sending.
- Per app: mute. This is local only.
- Global: Do Not Disturb follows macOS Focus.

## Clipboard (both directions)

**Phone → others** (Android doesn't let background apps read the clipboard):
- Share sheet target ("Send to Carom").
- Quick Settings tile "Send clipboard".
- An action on the optional ongoing "Carom" notification (off by default).
- The in-app "Send clipboard" button.

Each of these opens a transparent activity that reads the clipboard once it has focus, sends it,
and finishes.

**Others → phone**
- `clipboard.set` is written with `ClipboardManager.setPrimaryClip`. When `sensitive` is set,
  `EXTRA_IS_SENSITIVE` is applied as well.
- If "Show a notification when clipboard arrives" is on (default on), the phone posts a quiet
  notification with the first line of the text. This keeps the user informed, and keeps FCM
  high-priority messages from being deprioritised.

**Mac → others**
- With automatic mode (default on once pasteboard access is *Always allow*), the Mac polls
  `changeCount` every 500 ms. It skips:
  - items marked concealed, transient or auto-generated (nspasteboard.org types),
  - non-text items,
  - items larger than 60 KB,
  - its own writes.
- A global hotkey (default ⌃⌥⌘C) and a menu item send the current clipboard on demand.
- `sensitive` is true when the item carries the concealed marker.

**Others → Mac:** `NSPasteboard.setString`, plus a marker type so our own watcher ignores it.

**Echo suppression (both platforms):** remember `SHA-256` of the last received text for
10 seconds, and don't send it back.

**Settings (both platforms):**
- **Send clipboard to:** a device multi-select, which defaults to every device whose
  `receive` rule allows it.
- **Receive clipboard from:** writes the device's `receive["clipboard.set@1"]`, either `"*"`
  or a list of ids.
- Text larger than 64 KB is refused with a message.

## Device settings screen

The screen lists every device: name, kind, last seen, and push yes/no (from the directory).
Actions:

- **Rename:** this device only.
- **Remove:** any device. A confirmation shows the consequences.
- **Receive from:** a matrix per type.

## UX copy rules

Short, plain sentences. Errors name the cause and the fix, for example: "Your Hub refused the
request because this device was removed. Pair again to continue."
