# Setting up Carom

About 10 minutes, in three parts: **deploy your Hub → set up the Mac → add your phone**.

## What you need

| | Requirement |
|---|---|
| Hub | A free [Cloudflare](https://dash.cloudflare.com/sign-up) account, and Node.js 22 or newer on your computer |
| Mac | macOS 14 or newer |
| Phone | Android 10 or newer |

Your Hub fits in the Cloudflare Workers Free plan. Its daily quotas are ample for personal use, so
it costs nothing. All message content (notifications, clipboard) is end-to-end encrypted. Cloudflare
and the Hub see only ciphertext, plus routing metadata such as which device sent to which, and when.

> **No prebuilt downloads yet.** Until the first release, build the apps from source
> ([Building from source](#building-from-source)) and run the Hub CLI from a checkout. Once
> `carom-hub` is on npm, `npx carom-hub …` replaces `node hub/bin/carom-hub.mjs …` below.

## 1. Deploy your Hub

1. On your computer:
   ```sh
   git clone https://github.com/tajchert/carom.git && cd carom
   npm install
   node hub/bin/carom-hub.mjs deploy
   ```
   - A browser window opens so you can log in to Cloudflare (first time only).
   - If you have several Cloudflare accounts, wrangler asks which one to use.
   - If your account has no `workers.dev` subdomain yet, accept wrangler's offer to register one.
     It becomes part of your Hub's address.
   - **EU only:** add `--jurisdiction eu` to keep data in the EU. **This can't be changed later.**
     Switching means deploying a new Hub under another `--name` and pairing every device again.
2. When it finishes you see:
   - **Hub URL**, for example `https://carom-hub.<your-subdomain>.workers.dev`
   - **Setup URI** `carom://setup?v=1&h=…&t=…`, also shown as a **QR code**

Keep the terminal open for the next step. The setup URI works until the first device sets up the
Hub. The token inside it is also your Hub's admin secret (it authorizes `reset`), so don't share it.

The CLI saves the Hub URL and token in `~/.config/carom-hub/carom-hub.json`. **Keep this file:**
`update` and `reset` need it. Lost the terminal output? `node hub/bin/carom-hub.mjs qr` prints the
setup QR again until a device has used it.

## 2. Set up the Mac (first device)

1. Open **Carom**. The onboarding window opens. Carom has no Dock icon; later you'll find it in the
   menu bar.
2. Click **Set Up a New Hub**.
3. Paste the `carom://setup…` link from the terminal, or drop a screenshot of the QR code onto the
   window (or **Open Image…**). Click **Set Up**.
4. **Name This Mac**. Your other devices show this name. Click **Continue**.
5. **Permissions**, then click **Continue**:
   - **Notifications → Allow**, so phone notifications can appear on the Mac. It shows *Allowed* if
     macOS already permits it, or **Open Settings** if it was denied before.
   - **Clipboard → Check Access** (macOS 15.4 and newer; older macOS shows *Allowed*, so there's
     nothing to do). macOS asks before apps read the clipboard. To send what you copy
     automatically, choose **Always Allow** for Carom in **System Settings → Privacy & Security →
     Paste from Other Apps**. Without it you can still send on demand with **⌃⌥⌘C** or
     **Send Clipboard** in the menu.
   - **Focus status.** macOS asks once, when Carom starts with its own notification panel turned on (the default),
     whether Carom may see if a Focus is on. Choose **Allow**. While a Focus is on, Carom then
     shows only the quiet macOS notification. If you refuse, Carom can't see your Focus, so the
     panel still appears. You can change this later in **System Settings → Privacy & Security →
     Focus**.
6. **You're All Set → Add a Phone.** A pairing QR code appears. It's valid for 5 minutes and for one
   device. Continue on the phone.

## 3. Add your phone

1. Install and open **Carom** on the phone, tap **Get started**, then choose **Join with a code**.
   Don't choose *Set up a new hub*: that's only for the very first device.
2. Tap **Allow camera** and scan the QR code on the Mac. To paste instead, click **Copy Link** in the
   Mac's *Add a Device* window and paste into the phone's **Code** field (or tap **Paste from
   clipboard**).
3. The phone shows **This device** with a short code. On the Mac, check that **Add device
   `<code>`?** shows the same code, then click **Add Device**.
4. **Name this device**, then **Continue**.
5. **Allow access**:
   - **Notification access → Allow.** In the system list, turn on **Carom notification
     forwarding**. If the switch is greyed out, Android is blocking it for apps installed outside an app
     store: tap **Open App info → ⋮ → Allow restricted settings**, then try again.
   - **Notifications from Carom → Allow.** Carom uses this to tell you when clipboard text arrives.
   - **Verification codes → Enable** (Android 15 and later). See
     [One-time codes on Android 15 and later](#one-time-codes-on-android-15-and-later). You can skip it.
   - **Unrestricted battery use** (optional). Carom uses little battery without it, but on some
     phones (Xiaomi, Samsung, Huawei and others) it prevents notifications going out late. Choose
     **Don't optimise** for Carom.
6. **You're all set → Open Carom.** Post any notification on the phone. It shows up on the Mac.

## Daily use

On the phone, **Settings** and **Devices** are the icons at the top of the home screen. On the Mac,
open **Settings…** (⌘,) from the menu-bar menu.

| You want to… | How |
|---|---|
| Act on a phone notification on the Mac | The card shows the app's own buttons, **Reply** (type a reply or pick a suggestion), **Copy code** (when Carom detects a one-time code), **Dismiss on phone**, and **Copy text**. Click the card to open the notification on the phone. Right-clicking an item in the menu-bar list offers some of the same actions, plus muting the app. See [Actions from Mac](#actions-from-mac) |
| Control music from the Mac | The **Now Playing** card in the menu shows what the phone plays, with play/pause, previous, next and a seek bar. It needs **Actions from Mac** on the phone |
| Answer or decline a call | Incoming calls show a card with **Answer** and **Decline**. The call audio stays on the phone |
| Choose how notifications look on the Mac | Mac: **Settings → Notifications → Show notifications as**: **Rich** (Carom's panel plus a quiet copy in Notification Center) or **Native only** (plain macOS banners) |
| Send the Mac clipboard to the phone | Copy (automatic with *Always Allow*, or turn on **Send what I copy automatically** in **Settings → Clipboard**), press **⌃⌥⌘C**, or choose **Send Clipboard** in the menu. **It arrives when you next open Carom on the phone**, or when the phone next sends something. There are no push wake-ups, to save battery |
| Send the phone clipboard to the Mac | **Send clipboard** on Carom's home screen, the **Send clipboard** Quick Settings tile (add it via Quick Settings → edit ✎), **Share → Send to Carom** (plain text), or the ongoing notification (turn on **Keep a Carom notification with a Send clipboard button** in **Settings → Clipboard**). Android doesn't let apps read the clipboard in the background, so it always takes one tap |
| Choose which apps forward | Phone: **Settings → Apps**, then **Forward / Hide content / Off** per app. Apps appear after their first notification. *Hide content* sends only the app name, and the Mac shows "Content hidden on your phone". **Ongoing: Forward** lets that app's ongoing notifications through (see below). **Forward only when the screen is locked** is on the same screen. Mac: **Settings → Notifications** turns phones on or off and mutes apps on that Mac only |
| Pause | Phone: **Settings → Pause notification forwarding**. Mac: **Pause** (then **Resume**) in the menu, or **Pause syncing** in **Settings → General**. While paused, **Send Clipboard** still works |
| Choose who gets your clipboard | Phone: **Settings → Clipboard → Send clipboard to / Receive clipboard from** (or **Any device**). Mac: **Settings → Clipboard → Send To / Receive From**. Devices paired later are only included where **Any device** is on |

Some system apps (Android system, System UI, Settings, Play Store, Google Play services and similar)
start as **Off**. Carom forwards silent notifications too, but not those from channels Android marks
as minimum importance. Ongoing notifications (music players, navigation, downloads) are skipped
unless they are a Live Update, show progress or are a call, or you turn on **Ongoing: Forward** for
that app. Media players are not forwarded as notifications: they appear as the Now Playing card.
You don't need to grant anything more for this. It all runs under notification access.

## Actions from Mac

On the phone, **Settings → Actions from Mac** decides whether your Mac can tap, reply to or
control things on the phone.

| Choice | What it means |
|---|---|
| **Off** | Your Mac gets no buttons, reply field or media controls for this phone |
| **After a notification** (default) | The phone listens for your Mac for a few minutes after it sends a notification. **Stay ready for** 2, 5 or 15 minutes. Costs very little battery |
| **Always while a Mac is online** | The phone is always ready. It shows a permanent notification ("Ready for your Mac") and uses more battery |

Actions work only while the phone is listening. If you click a button when the phone isn't, the
Mac shows "Phone is asleep — will send when it wakes". The action waits for up to 10 minutes. It
runs when the phone next listens, or is dropped if the notification is gone by then.

Two things to know:
- **Open on phone** starts the app on the phone. Android 14 and later can block that when the
  phone is locked or the screen is off. The Mac then says the action failed or was blocked.
- Some apps drop the text of a reply sent this way. Carom can't tell, so the Mac still shows "Sent".

Android 16 shows **Live Updates** from apps that support them (a ride, a delivery, a timer). Carom
shows these as a pinned card on the Mac, and in the menu bar if the app marks them as promoted.
Whether an app shows its Live Update is a per-app choice in Android's own notification settings,
not in Carom.

## Adding more devices

On any paired device:
- **Phone:** **Add a device** on the home screen.
- **Mac:** **Add Device…** in the menu.

Then:
- **New phone:** choose **Join with a code** and scan the QR code.
- **New Mac:** choose **Join My Devices** and paste the `carom://pair…` link. On a phone, use
  **Copy code instead** under the QR code and get the link to the Mac, for example with a note.
- Approve **Add device `<code>`?** on the device showing the QR code.

You can pair any number of phones and Macs.

## Removing a device

- **Lost or old device:** remove it from any other device.
  - **Mac:** **Settings → Devices**, click the remove icon on its row, then confirm **Remove**.
  - **Phone:** **Devices**, tap **Remove** on its row, then confirm.

  Your other devices switch to a new key, so the removed device can't read anything new.
- **This device:** phone **Settings → Leave hub**, Mac **Settings → Remove This Mac…**. It hands
  over a new key first, then erases its own keys. It needs a connection to the Hub.
- **A device the Hub refused** was removed, or the Hub was reset. To pair it again from a device that still works:
  - **Phone:** the home screen says *"Your Hub refused this phone…"*. Tap **Pair again → Delete and
    pair again**, then choose **Join with a code**.
  - **Mac:** the menu says *"Your Hub refused the request because this device was removed."* Click
    **Pair Again… → Erase and Pair Again**, then choose **Join My Devices**.

## Hub maintenance

```sh
node hub/bin/carom-hub.mjs info                 # status and version
node hub/bin/carom-hub.mjs update               # deploy the latest Hub code (same token and data)
node hub/bin/carom-hub.mjs reset                # erase all Hub data (type the hub name to confirm, or --yes)
node hub/bin/carom-hub.mjs reset --new-token    # erase and also rotate the setup token
```

`reset` prints a fresh setup QR; continue from step 2. If you deployed under another name, add
`--name <name>` to every command (its state file is then `~/.config/carom-hub/<name>.json`).

There is nothing to back up. The Hub deletes message content once every recipient has it, and in any
case after 1–2 days. The keys live only on your devices. If you lose every device, run `reset`.
Without the state file, use `deploy --new-token` instead. Then set up again.

## One-time codes on Android 15 and later

Android 15 hides verification codes from notification apps. Without help, a code reaches the Mac as "Sensitive notification content hidden". Carom can ask Android to trust it with those notifications:

1. During setup, the permissions step offers **Verification codes → Enable**. Later, when Carom sees a hidden code, it shows a quiet notification: "Android hides verification codes from notification apps. Tap to enable."
2. Tap it, choose **Enable code forwarding** and accept Android's prompt. Carom creates a *companion device* association, which makes Android treat it as trusted for these notifications.
3. Send yourself a test code. If it still arrives hidden, Carom shows the command below.

Some phones and Android versions don't let the association unlock codes. Then the screen reads
"Turn it on from a computer" and shows the command below. Use it.

### If Android still hides codes

Connect the phone with USB debugging turned on and run this on a computer:

```bash
adb shell cmd appops set --user 0 com.mtajchert.carom.app RECEIVE_SENSITIVE_NOTIFICATIONS allow
```

Use `com.mtajchert.carom.app.debug` for a debug build. Android may reset this after a reboot or an app update, so run it again if codes are hidden again. Carom asks for no SMS permission.

## Troubleshooting

| Problem | Fix |
|---|---|
| `deploy` stops with a workers.dev subdomain message | Register one under **Workers & Pages → Account details → Subdomain → Change**, then run `deploy` again |
| `deploy` refuses because a Hub already exists | This machine has no `~/.config/carom-hub/<name>.json`. Copy it from the machine that deployed, or start over on purpose with `deploy --new-token` (signs out every device) |
| "This Hub is already set up" | A device already used the setup code. Use **Join** from a paired device instead |
| "The code expired" | Pairing codes last 5 minutes and work once. Choose **Show a new code** (phone) or **Show a New Code** (Mac) |
| Mac shows no notifications | **System Settings → Notifications → Carom**: allow, and turn on banners. Also check the phone is on in **Settings → Notifications** |
| Phone forwards nothing | The home screen shows a banner if notification access is off. Check **Settings → Apps**: the app may be **Off**, **Pause forwarding** may be on, or **Forward only when the screen is locked** is on while the screen is unlocked |
| Notifications arrive late or stop | Your phone's battery saver is restricting Carom. Allow **Unrestricted battery use**, and see [dontkillmyapp.com](https://dontkillmyapp.com) for your phone brand |
| Mac clipboard never reaches the phone | Expected until you open Carom on the phone, or until it next sends something |
| One-time codes show as "Sensitive notification content hidden" | Android 15+ hides OTPs from notification apps. Enable code forwarding as described in [One-time codes on Android 15 and later](#one-time-codes-on-android-15-and-later). Turning off **Enhanced notifications** in system settings also works |
| Reply, Answer or the app's buttons don't show on the Mac | The phone must list the action types: set **Settings → Actions from Mac** on the phone to something other than **Off**, then wait a moment for the Mac to see the change |
| A button says "Phone is asleep — will send when it wakes" | The phone isn't listening. Open Carom on the phone, or use **After a notification** or **Always while a Mac is online** |
| Live Update, music or call is missing on the Mac | Check **Settings → Apps** on the phone (the app can't be **Off**), and for Live Updates the app's own Live Updates switch in Android |
| Automatic clipboard sending is off on the Mac | Choose **Always Allow** under **Privacy & Security → Paste from Other Apps**, then turn on **Send what I copy automatically** in **Settings → Clipboard** |

## Building from source

**Android** needs:
- JDK 21,
- Android SDK Platform 37 and Build Tools 36.0.0 (with `sdk.dir` in `android/local.properties`, or
  `ANDROID_HOME` set), and
- platform-tools, for `adb`.

Connect your phone with USB debugging on, then:

```sh
cd android
./gradlew :app:installDebug
```

This installs the debug build (`com.mtajchert.carom.app.debug`). Release builds come out unsigned
until release signing is set up.

**Mac** needs Xcode 27 to build. The app runs on macOS 14 or newer. The project signs with the
maintainer's team by default, so the build fails without that certificate. First create
`macos/App/Config/Signing.local.xcconfig` with your team:

```
DEVELOPMENT_TEAM = <your team id>
```

To sign ad hoc instead, set `DEVELOPMENT_TEAM =` and `CODE_SIGN_IDENTITY = -`. With ad hoc signing,
macOS may ask for Keychain access after each rebuild. Then:

```sh
xcodebuild -project macos/App/Carom.xcodeproj -scheme Carom -configuration Release build
open ~/Library/Developer/Xcode/DerivedData/Carom-*/Build/Products/Release/Carom.app
```
