# Changelog

All notable changes to Carom are listed here. Versions apply to the whole project: the Android app,
the Mac app and the Hub are released together under one `vX.Y.Z` tag.

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
  [setup guide](https://github.com/tajchert/carom/blob/main/docs/SETUP.md#building-from-source)).
- **Messages to the phone can wait.** Replies and commands arrive at once during the few minutes
  after the phone sends a notification. Outside that window they wait until you open Carom.
- **Free plan quota.** A flood of requests from many addresses can use up the Free plan's 100k daily
  requests, and the Hub then pauses until the next day. Your data stays safe.

[1.0.0]: https://github.com/tajchert/carom/releases/tag/v1.0.0
