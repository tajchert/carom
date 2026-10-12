# Client behaviour (Android and macOS)

[`protocol/SPEC.md`](../protocol/SPEC.md) defines the wire format. This document defines what
the apps do with it, so both platforms behave the same way. When the two disagree, the spec wins.

## Principles

- **Simple:** pairing is one QR scan. Sensible defaults mean a new user never opens settings.
- **Battery first (phone):** no long-running service of our own by default. Work happens when the
  system wakes us (notification listener callback, user action), and then we stop. There is no push
  service and no periodic polling. The one exception is the *action window* (see
  [Action window](#action-window-phone)), which the user can turn off or widen.
- **Universal:** no Hub URL is baked into a build. It always comes from a setup or pair URI.
- **Private:** keys never leave the device, are never backed up, and are never logged. Notification
  and clipboard content are never logged either.

## Identity and storage

| Item | Android | macOS |
|---|---|---|
| `sign_sk`, `kx_sk`, group keys, device token | Encrypted with an Android Keystore AES-GCM key; stored in a no-backup file | Keychain, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, not synchronizable |
| Hub URL, device id, directory cache, seen `msg_id`s, outbox, settings | Room + DataStore. `allowBackup=false`, everything excluded from backup and device transfer | SQLite or JSON files in Application Support |

For local development on the Android emulator, use `adb reverse tcp:8787 tcp:8787` and a Hub URL
of `http://localhost:8787`. QR URIs allow plain http only for loopback hosts.

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
     "restricted settings" for sideloaded APKs), the `POST_NOTIFICATIONS` runtime permission, an
     optional battery-optimisation exemption, and on Android 15 and later a **Verification codes**
     step (see [One-time codes](#one-time-codes)).
   - macOS: notification permission, plus a pasteboard access explanation (*Always allow*). With
     *Show notifications as: Rich*, macOS also asks once for Focus status (see
     [Desktop presentation](#desktop-presentation)).

## Pairing UI (the device that invites)

"Add device" calls `POST /v1/pair/offer`, generates `pair_secret` and shows the QR code (plus a
copyable URI) with a 5-minute countdown. When the candidate arrives:

1. Verify `mac`, `pop` and `device_id`.
2. Ask the user: "Add device `<fingerprint>`?"
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
- Phone: no persistent socket, no push and no periodic polling (SPEC §9). It fetches with
  `GET /v1/messages` only when it is already awake:
  - when the app comes to the foreground (a WebSocket is open while the UI is visible),
  - after a successful send (piggyback, at most once every 15 s, and skipped while a socket is live),
  - after the Quick Settings tile or share actions, and
  - while the action window is open (below).

  Messages sent to the phone outside a window (clipboard from the desktop, dismiss requests)
  therefore arrive when the phone next opens Carom or sends something. Desktop UIs say this where
  it matters, for example "Arrives when Carom is opened on <phone>" next to "Send clipboard to".

**Revoking (any device can revoke any device)**
1. `DELETE /v1/devices/:id`.
2. Rekey (SPEC §5.5).
3. Republish our own record.

Other devices republish their records when they see the new key. A device that receives
`revoked` wipes its keys and returns to onboarding.

## Action window (phone)

Actions from the Mac (`notification.action@1`, `media.command@1`) need a live connection to the
phone. The phone opens a WebSocket for a short *action window*, only when it is already awake:

- **Opens** after the phone forwards a notification and the Hub's reply lists a desktop in
  `online` (SPEC §6). If no desktop is online, nothing opens.
- **Stays open** while an incoming or ongoing call, or a playing media session, is active.
- **Closes** when the window passes with no forward, received action or media change. Each of
  those restarts the timer. A failed connect is not retried once the window has closed.
- It reuses the normal ack and reconnect logic.

**Settings → Actions from Mac** (phone):

| Choice | What it does |
|---|---|
| **Off** | The phone drops `notification.action@1` and `media.command@1` from its `caps`. Macs show no action buttons or media controls for it. Calls say "Answer on the phone. Actions from Mac are off there." |
| **After a notification** (default) | The window above. **Stay ready for** 2, 5 (default) or 15 minutes. |
| **Always while a Mac is online** | A foreground service (`remoteMessaging`) keeps the socket open while the phone is paired. It shows a permanent notification, "Ready for your Mac", and uses more battery. While no Mac is online it reconnects with a backoff of up to 15 minutes. |

When the phone is outside its window, a Mac can still send an action. It waits at the Hub for
10 minutes. The card on the Mac shows "Sending…", and after 30 s without a result
"Phone is asleep — will send when it wakes". The phone runs the action when it next connects,
unless the notification is gone by then.

## Notifications (phone → desktop)

**Hide content:** an app set to *Hide content* sends only `key`, `app` (with its icon),
`posted_at` and `redacted: true` (SPEC §4.1). No text, other images or buttons leave
the phone. The Mac shows "Content hidden on your phone."

**Phone filters, applied before sending**
- Skip:
  - our own notifications,
  - system autogroup summaries (a summary with an override group key),
  - notifications with `FLAG_LOCAL_ONLY`,
  - `IMPORTANCE_MIN` channels,
  - media-style notifications as posts: the media card replaces them (see
    [Media](#media-phone--mac)).
- Silent (low-importance) notifications are forwarded.
- Ongoing and foreground-service notifications are skipped unless they are a promoted Live Update,
  have progress, are a call, or the app's **Ongoing: Forward** switch is on (default off).
- **Per-app setting:** *Forward* (default on, except for a small built-in deny list of system
  packages), *Hide content* (see above), or *Off*. The app list is built from apps seen posting
  notifications, without `QUERY_ALL_PACKAGES`.
- **Global settings:** a pause toggle, and "Only when screen is locked" (default off).

**Mapping (Android `StatusBarNotification` → `notification.posted@1`)**

| Body field | Source |
|---|---|
| `key` | `sbn.key` |
| `app.id` | package name |
| `app.name` | application label |
| `title` | `EXTRA_TITLE` (or `EXTRA_CONVERSATION_TITLE`) |
| `text` | `EXTRA_TEXT`; for `MessagingStyle`, the last message as "sender: text" |
| `big_text`, `lines`, `summary_text` | `EXTRA_BIG_TEXT`, the inbox lines (at most 7), `EXTRA_SUMMARY_TEXT` |
| `template`, `color`, `colorized` | the style class, `Notification.color`, `setColorized` |
| `messages`, `conversation` | `MessagingStyle` messages (at most 6, newest last) and the conversation title and icon |
| `actions` | the app's buttons (at most 6) with reply choices |
| `progress`, `live`, `when`, `chronometer` | progress extras, Android 16 Live Update fields, `when` and the chronometer flags |
| `call`, `group`, `channel` | `CallStyle`, the group key and sort key, the channel id and importance |
| icons and pictures | encoded and uploaded as blobs (see [Images](#images)) |
| `sub_text` | `EXTRA_SUB_TEXT` |
| `category` | `notification.category` |
| `posted_at` | `sbn.postTime` |
| `redacted` | true when Android 15+ replaced the content ("Sensitive notification content hidden") |

- Text fields are truncated at 4000 characters. The body stays within 65 000 bytes: the phone cuts
  messages and lines first, then text.
- A notification update re-sends `posted` with the same collapse key. Updates are throttled to
  one per 1.5 s per key (trailing edge, latest state wins). An update whose content is unchanged is
  not sent. A removal, a finished progress and a call type change go out at once.
- When the notification is removed, send `removed`.

**Desktop filters**
- Per phone: on or off. This is written into the record's `receive` rules, so the phone stops
  sending.
- Per app: mute. This is local only.
- Global: Do Not Disturb follows macOS Focus.

### Images

Icons, pictures and avatars are not part of the message. The phone encodes each one as WebP,
encrypts it and uploads it to the Hub as a blob (SPEC §11). The message carries a reference.

- **Sizes:** app icon 128 px, small icon 96 px (an alpha mask, tinted on the Mac), large icon and
  avatars 192 px, big picture 1080 px wide and at most 400 KB.
- **Phone:** loads the images as soon as the notification arrives, because Android revokes access
  when it is removed. Up to 3 uploads run at once. App and small icons are uploaded once per key
  version. If an upload fails, that image is left out and the notification is still sent.
- **Expiry:** a picture or avatar lives a little longer than its notification (2 days and an
  hour). App and small icons live 7 days, the Hub's maximum, and are checked again before the
  phone relies on them. The Hub deletes a picture earlier when an update replaces the notification.
- **Mac:** fetches at most 4 images at a time, checks the hash, decrypts, and keeps them in a
  disk cache in Application Support (50 MB, least recently used first, icons last). Cards show
  at once with a placeholder (the app's initial in a circle) and fill in when the image arrives.
  An image that is missing, expired or fails a check is skipped. It never blocks a notification.

### Desktop presentation

**Show notifications as** (Mac, **Settings → Notifications**):

| Choice | What the Mac does |
|---|---|
| **Rich** (default) | Carom's own panel shows a card that looks like the Android one. A quiet copy also goes to Notification Center, so there is history and Focus support but no second banner. |
| **Native only** | Plain macOS banners. No panel. |

The panel:
- A borderless panel at the top right of the screen under the pointer. It never takes focus,
  except while the user types in a reply field.
- At most 4 stacks are shown, then "+N more".
- Cards fade after 6 s unless the pointer is over them. Conversation, call and Live Update cards
  stay until the user handles them or the phone removes the notification.
- Same phone, app and `group.key` collapse into one card ("App · N new") that expands. Order is
  `sort_key`, then `when`.
- Card: small icon tinted with `color`, app name in the accent colour, `sub_text`, time. Then the
  title and text (expanded: `big_text`, big picture, inbox lines, message bubbles with avatars).
  `colorized` fills the card with `color`.
- New cards are announced to VoiceOver, and every control has a label.

**Focus.** When *Rich* is on, macOS asks once for access to Focus status. While a Focus is
on, the panel and its sound stay quiet and only the passive native notification posts. If the user
says no, Carom cannot see the Focus, so the panel still shows and only the native copy follows
Focus.

**Buttons on a card.** The native notification carries Copy code, Reply, Dismiss on phone and Copy text. The app's own buttons are on the card only.

- **Copy code**, when a code is found, then the app's own buttons, then **Reply**. Reply opens an
  inline field with the app's suggested replies as chips.
- **Dismiss on phone** sends `notification.dismiss@1` with `reply_to`.
- The Mac doesn't offer "Open on phone": Android blocks the background activity start, even when
  Carom opts in, and reports no error, so the protocol has no such action.
- App buttons and Reply appear only for a phone whose `caps` include `notification.action@1`.
- A button shows "Sending…", then "Sent", "Phone is asleep — will send when it wakes",
  "Notification is gone" or "App changed its buttons".
- **Copy text.**

Some apps build their reply button so that Android drops the typed text. The app then gets the
button press without text. Carom cannot detect this, so it shows "Sent". This is a known limit.

**Live Updates and progress.** A card with a progress bar (segments, points and tracker icon for
Android 16 Live Updates), a chronometer or countdown that the Mac ticks itself from `when`, and
the short critical text. It stays at the top of the stack and updates in place without sound.
For a promoted Live Update the Mac also shows a **menu-bar chip** next to the Carom icon: the
short critical text (up to 10 characters), or a progress ring. It disappears when the update
ends. Turn it off with **Show Live Updates in the menu bar** (on by default).

**Calls.** An incoming call is a large pinned card with the caller's name and avatar, the
verification text if any, and **Answer** and **Decline**. It rings (a looped system sound) until
the phone removes the notification or the user acts. Audio stays on the phone, and the card says
so.

**Other rules**
- One macOS notification per `key`, with `threadIdentifier = <phone id>/<app id>`. The title is
  "*App* · *title*". The subtitle is the phone name, shown only when more than one phone is paired.
- `removed` withdraws the card and the macOS notification.
- Plain text only. URLs are never opened automatically.

### Late messages

When a Mac was offline, the Hub delivers a backlog on reconnect. A message is late when the
decrypted payload's `ts` (not `posted_at`, which an ongoing notification keeps across updates)
is more than 2 minutes older than now. A late notification goes to the history only: no banner,
no panel card, no sound, no auto-copy of its code (the Copy button stays). When 4 or more arrive
in one burst, the Mac posts one notice, "N notifications while you were away". A late clipboard
item does not overwrite the pasteboard; it waits in the history to copy by hand. A later fresh
update of the same notification is shown as usual. The history and cards show `posted_at`
(never later than now) as the time.

### Media (phone → Mac)

The phone does not send media notifications. It sends `media.state@1` instead, from the media
session, at most once per second and only on metadata or playback-state changes. Album art goes
as a blob. When the session ends it sends `stopped`.

The Mac shows a **Now Playing** card in the menu-bar popover: art, title and artist, a progress bar
that the Mac moves by itself from `position_ms` and `position_at`, and play/pause, previous, next
and seek. Commands are `media.command@1`. There is no reply: the next `media.state` shows the
result. For a phone that does not advertise `media.command@1`, the card shows a hint to turn on
**Actions from Mac** on the phone.

### One-time codes

The Mac finds a code in a notification. It looks at `text`, `big_text`, `lines` and the newest
message, in that order, and uses the first hit.

- A **digit code** has 4 to 10 digits. Without a keyword such as "code", "OTP" or "verification",
  only 6-digit codes count. A 4-digit number that looks like a year needs a keyword. Phone numbers,
  dates, prices and times are not codes. `123 456` and `123-456` are read as one code.
- A **letter-digit code** has 4 to 10 letters and digits, with at least one letter and at least two
  digits, and optionally one `-`. It counts only with a keyword within 40 characters before it.
- A redacted notification has no code.

**Copy code** is the first button when a code is found. **Copy codes automatically** (**Settings → Notifications**,
off by default) copies the code when the notification arrives, and clears the clipboard 60 s later
if it still holds that code.

Android 15 and later hides codes from notification apps. The phone can ask for access through
**Enable code forwarding** (see [SETUP.md](SETUP.md#one-time-codes-on-android-15-and-later)).

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

**Receive lists and new devices:** `"*"` includes every device, including future ones. A list of ids
is an explicit choice: a newly paired device is **not** added to it, and settings show it as
unchecked.

**Settings (both platforms):**
- **Send clipboard to:** a device multi-select, which defaults to every device whose
  `receive` rule allows it.
- **Receive clipboard from:** writes the device's `receive["clipboard.set@1"]`, either `"*"`
  or a list of ids.
- Text whose padded payload would exceed 65 536 bytes (roughly 60 KB of text) is refused with
  a message.

## Clock check

A wrong device clock makes the Hub refuse messages (SPEC §6 rule 8), so both apps watch for it.

- **Skew** = Hub time − local time, in Unix epoch milliseconds. Hub time comes from the `Date`
  header of any Hub HTTP response, or `hub_time` in the `400` with `clock_skew: true`. Local time is
  `System.currentTimeMillis()` / `Date().timeIntervalSince1970`, read when the response arrives.
  Ignore responses without a parseable `Date`.
- **Timezones never matter.** Everything is epoch ms and GMT. Parse `Date` with a fixed GMT
  RFC 1123 parser (`en_US_POSIX` on macOS) and never use local-time or Calendar APIs. A device
  in any timezone with a correct clock has skew near 0, and a test proves it.
- **Warn** when |skew| > 5 minutes. **Clear** only when |skew| < 4 minutes on a later response, so
  the banner does not flicker.
- Wording: "<Phone|This Mac>'s clock is off by about N minutes. Carom can't deliver messages until
  it's fixed. Turn on automatic date and time." N is the rounded |skew| in minutes; skew < 0 means
  the local clock is ahead.
- Show a banner in the main UI, plus one system notification per drift episode (not per
  response), and a button that opens the OS date and time settings where possible.
- A `400` with `clock_skew` is not retried: the message is dropped like any 4xx.

## Device settings screen

The screen lists every device: name, kind and last seen (from the directory).
Actions:

- **Rename:** this device only.
- **Remove:** any device. A confirmation shows the consequences.
- **Receive from:** a matrix per type.

## UX copy rules

Short, plain sentences. Errors name the cause and the fix, for example: "Your Hub refused the
request because this device was removed. Pair again to continue."
