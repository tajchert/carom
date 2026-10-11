#!/bin/bash
# End-to-end test of the macOS app (a Debug build) against a local Hub, with a simulated phone.
#
#   macos/scripts/e2e.sh <hub url> <setup token file> <path to Debug Carom.app> [work dir]
#
# Start the Hub first, for example `cd hub && npx wrangler dev --port 8790`, with the token from
# the JSON file ({"setupToken": "..."}) in hub/.dev.vars. The Hub must be fresh (there is no
# reset route: change its SETUP_TOKEN to wipe it). The script launches the
# app with Debug-only launch arguments in a separate profile ("e2e": its own Application Support
# folder and Keychain service), lets it set up the Hub and show a pair invite, pairs
# macos/scripts/fake-phone.mjs through it, sends a notification and a clipboard item, then the V2
# set (an encrypted avatar blob, a rich messaging notification with a reply action, a progress
# Live Update and its updates, media.state), and checks the app's event log (event types only,
# never content) and the pasteboard. Debug launch arguments make the app reply on the chat card
# and press pause on the media session; the fake phone answers the action with an ok result. It overwrites the
# clipboard with a test marker. The setup URI is passed as a launch argument, so only use it with
# a throwaway local Hub token. Set E2E_SHOW_WINDOWS=menu to also open the popover content in a
# window (Debug builds), for screenshots with `screencapture -l <window id>`.
set -euo pipefail

HUB=${1:?hub url}
TOKEN_FILE=${2:?setup token file}
APP=${3:?Carom.app}
WORK=${4:-$(mktemp -d)}
HERE=$(cd "$(dirname "$0")" && pwd)
PROFILE=e2e
SERVICE="com.mtajchert.carom.mac.secrets.$PROFILE"
mkdir -p "$WORK"
chmod 700 "$WORK"
EVENTS="$WORK/events.log"
INVITE="$WORK/invite.txt"
rm -f "$EVENTS" "$INVITE"

TOKEN=$(python3 -c 'import json,sys; t=open(sys.argv[1]).read().strip()
try: t=json.loads(t)["setupToken"]
except Exception: pass
print(t)' "$TOKEN_FILE")
SETUP_URI=$(python3 -c 'import sys, urllib.parse as u; print("carom://setup?v=1&h=" + u.quote(sys.argv[1], safe="") + "&t=" + u.quote(sys.argv[2], safe=""))' "$HUB" "$TOKEN")

echo "== reset the e2e profile (the Hub must be fresh)"
rm -rf "$HOME/Library/Application Support/Carom-$PROFILE"
while security delete-generic-password -s "$SERVICE" >/dev/null 2>&1; do :; done
osascript -e 'tell application id "com.mtajchert.carom.mac" to quit' >/dev/null 2>&1 || true
sleep 1

echo "== launch $APP"
open -n "$APP" --args -caromProfile "$PROFILE" -caromSetupURI "$SETUP_URI" -caromInviteFile "$INVITE" \
  -caromAutoApprove YES -caromEventLog "$EVENTS" -caromAutoReply "Yes, see you at noon" -caromAutoMedia pause \
  ${E2E_SHOW_WINDOWS:+-caromShowWindows "$E2E_SHOW_WINDOWS"}

MARKER="carom-e2e-$(date +%s)"
echo "== simulated phone"
phone_status=0
node "$HERE/fake-phone.mjs" --invite-file "$INVITE" --clipboard-text "$MARKER" --expect-action --expect-media \
  || phone_status=$?
rm -f "$INVITE"
sleep 4

echo "== app event log (types only)"
cat "$EVENTS"

status=0
expect() {
  if grep -q "$1" "$EVENTS"; then echo "PASS  $2"; else echo "FAIL  $2"; status=1; fi
}
expect "phase ready" "app finished setup and went ready"
expect "realtime connected" "WebSocket connected"
expect "pairing candidate" "pairing candidate arrived"
expect "pairing approved" "pairing approved"
expect "message notification.posted@1" "received notification.posted@1"
expect "notification presented" "notification handed to Notification Center"
expect "message notification.removed@1" "received notification.removed@1"
expect "notification withdrawn" "notification withdrawn"
expect "message clipboard.set@1" "received clipboard.set@1"
expect "clipboard written" "clipboard written"
if [ "$(pbpaste)" = "$MARKER" ]; then echo "PASS  pasteboard holds the phone's text"; else echo "FAIL  pasteboard"; status=1; fi
# V2 (the fake phone itself checks the blob round trip and the action's key and reply_to).
expect "debug images loaded 1/1" "Mac fetched and decrypted the avatar blob"
expect "debug reply performed" "Mac replied on the chat card"
expect "action send sent" "Mac sent notification.action@1"
expect "message notification.action_result@1" "Mac received notification.action_result@1"
expect "debug action status sent" "chat card shows the action as sent"
expect "debug progress card shown" "progress Live Update shown"
expect "debug progress card updated" "progress Live Update updated in place"
expect "message media.state@1" "received media.state@1"
expect "debug media command pause" "Mac sent media.command@1 (pause)"
if [ "$phone_status" -eq 0 ]; then echo "PASS  fake phone: blob, action result and media command"; else
  echo "FAIL  fake phone exited with $phone_status"; status=1; fi

cat <<'CHECKS'

Manual checks (look at the screen now; the e2e profile keeps running):
  [ ] chat card: orange avatar, two messages from Anna, Reply and Mark as read buttons
  [ ] chat card showed the reply as sent, then faded (handled cards fade after 6 s)
  [ ] Rides card: progress bar at 80% with segments and a point, countdown, "2 min" chip
  [ ] menu bar Live Update chip shows the ride
  [ ] menu popover: Now Playing "E2E Song" with art and controls (it stays playing: the
      fake phone does not apply the pause)
  [ ] Notification Center: the posted notifications appear (needs permission)
CHECKS
exit $status
