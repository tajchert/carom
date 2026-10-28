# Changelog

All notable changes to Carom are listed here. Versions apply to the whole project: the Android app,
the Mac app and the Hub are released together under one `vX.Y.Z` tag.

## [1.0.5] - 2026-10-28

One-time codes on Android 15 and later without adb: the Mac poses as a watch for a moment.

### 📱 Android app

- **Code forwarding without adb.** Enable code forwarding now pairs the phone with your Mac as a
  companion watch, the only kind of companion that Android 15 and later trusts with verification
  codes. It survives reboots, so you no longer need to run the adb command again after each
  restart. The adb command remains as a fallback.

### 💻 Mac app

- **Make Discoverable** in Settings → Notifications advertises the Mac over Bluetooth LE for 3
  minutes so the phone can pair with it. Nothing stays connected afterwards.

## [1.0.4] - 2026-10-15

Change many apps at once, and the forwarding switches now live in Settings.

### 📱 Android app

- **Select several apps.** On the Apps screen, long-press an app to start selecting. Tap more
  apps, or use **Select all**, then choose Forward, Hide content or Off for all of them at once.
- **Forwarding switches moved to Settings.** "Only when screen is locked or off" is now in
  Settings, next to **Pause notification forwarding**. The duplicate pause switch on the Apps
  screen is gone.

## [1.0.3] - 2026-10-15

A quieter one-time code reminder and a more consistent Android app.

### 📱 Android app

- **Fewer "One-time codes" reminders.** The reminder to turn on code forwarding now really shows
  at most once a day. Before, it came back whenever Android restarted Carom in the background.
- **Don't show again.** The reminder has a button that turns it off for good. You can still turn
  on code forwarding from the app.
- **Consistent screens.** Every screen now uses the same cards, buttons and status chips.

## [1.0.2] - 2026-10-12

The Mac app now has a download and a Homebrew cask.

### 💻 Mac app

- **Install with Homebrew:** `brew install --cask tajchert/tap/carom`.
- **Download:** `Carom-1.0.2-macos.zip` on the release page runs on Apple Silicon and Intel Macs
  with macOS 14 or newer.
- **Not notarized yet.** The app is ad-hoc signed, so on first launch open **System Settings →
  Privacy & Security** and choose **Open Anyway**. After an update, macOS may ask once to let Carom
  use its keychain items.

## [1.0.1] - 2026-10-12

A small update that makes the Android app start faster.

### 📱 Android app

- **Faster startup.** The app now ships Baseline and Startup Profiles, so Android compiles the code
  Carom needs at launch when it installs the app, instead of over the first few runs.

## [1.0.0] - 2026-10-12

The first release of Carom. Your Android phone's notifications show up on your Mac, end-to-end
encrypted, through a small Hub that runs on your own Cloudflare account on the free plan.

### Highlights

- 🔔 **Rich notifications on your Mac.** Icons, colours, pictures, conversations with avatars,
  progress bars and grouped stacks, close to how they look on the phone.
- 💬 **Act without picking up the phone.** Reply inline, tap the app's own buttons, dismiss on the
  phone or copy the text.
- 🔑 **One-time codes, one click.** A **Copy code** button, with optional auto-copy that clears
  itself after 60 seconds.
- 📋 **Clipboard both ways.** Phone → Mac with one tap, Mac → phone automatically or by hotkey.
- 🔒 **Private by design.** Everything is encrypted on the sending device. The Hub only ever sees
  ciphertext and never holds a key.

### 📱 Android app

- **Forwarding:** the full notification, including big pictures, conversations, inbox and progress
  styles. Removing it on the phone removes it on the Mac.
- **Per-app control:** Forward, Hide content or Off for each app, plus **Only when locked** and a
  global pause.
- **Live Updates:** Android 16 Live Updates, progress bars and timers update in place on the Mac.
- **Media and calls:** a Now Playing card with play/pause, skip and seek, and an incoming-call card
  with **Answer** and **Decline**. Audio stays on the phone.
- **One-time codes:** 4 to 10 digit and letter-digit codes. On Android 15 and later, a one-time
  **Enable code forwarding** step gets past Android's code hiding.
- **Clipboard:** send from the home screen, a Quick Settings tile, the share sheet or an optional
  ongoing notification.
- **Battery friendly:** no polling and no push connection. After a notification, Carom listens for
  your Mac for a few minutes so replies and buttons are quick. You can shorten or turn this off.
- **Native and modern:** Kotlin, Jetpack Compose and Material 3, with dynamic colour, light and dark
  themes, and layouts for phones, foldables and tablets.

### 💻 Mac app

- **Menu-bar app** with a notification history and real app icons.
- **Notification cards** next to the menu bar, plus a quiet copy in Notification Center. Focus is
  respected.
- **Actions:** inline **Reply** with suggested replies, the app's own buttons, **Dismiss on phone**
  and **Copy text**, with clear status when the phone is asleep.
- **Live Update chip** in the menu bar for promoted Live Updates.
- **Clipboard:** sends to your phone automatically, from the menu or with ⌃⌥⌘C.
- **Mute apps** on the Mac without changing the phone.

### ☁️ Hub

- **One command to deploy:** `carom-hub deploy` logs in to Cloudflare, deploys one Worker and one
  SQLite Durable Object, and prints a setup QR code.
- **Zero maintenance:** nothing to patch, back up or keep online. Messages are kept only until
  they're delivered, one or two days at most.
- **Built-in protection:** rate limits stop strangers before they cost anything, and a reset by
  secret rotation wipes the Hub if you need a fresh start.
- **EU option:** `--jurisdiction eu` keeps data in the EU.

### 🔒 Security and privacy

- **End-to-end encryption:** Ed25519 signatures, X25519 key agreement and ChaCha20-Poly1305.
- **Pairing by QR code** with a short confirmation code on both screens, so the Hub can't intercept
  it.
- **Any number of devices.** Remove a lost one from any other device and the rest switch to a new
  key.
- **No open ports:** every device connects outward to your Hub, so it works on any network.
- **One protocol, four implementations:** the TypeScript, Kotlin and Swift code and the Hub all pass
  the same shared test vectors.

### Requirements

- An Android 10+ phone
- A Mac with macOS 14+
- A free Cloudflare account, and Node.js 22+ to deploy the Hub

### Known limitations

- **No prebuilt Mac download yet.** Build the Mac app from source (see the
  [setup guide](https://github.com/tajchert/carom/blob/v1.0.0/docs/SETUP.md#building-from-source)).
- **Messages to the phone can wait.** Replies and commands arrive at once during the few minutes
  after the phone sends a notification. Outside that window they wait until you open Carom.
- **Free plan quota.** A flood of requests from many addresses can use up the Free plan's 100k daily
  requests, and the Hub then pauses until the next day. Your data stays safe.

[1.0.3]: https://github.com/tajchert/carom/releases/tag/v1.0.3
[1.0.2]: https://github.com/tajchert/carom/releases/tag/v1.0.2
[1.0.1]: https://github.com/tajchert/carom/releases/tag/v1.0.1
[1.0.0]: https://github.com/tajchert/carom/releases/tag/v1.0.0
