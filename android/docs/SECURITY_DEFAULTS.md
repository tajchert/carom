# Security and backup defaults

Carom's rules come from [`protocol/SPEC.md`](../../protocol/SPEC.md) §5.3 and
[`docs/CLIENTS.md`](../../docs/CLIENTS.md) ("Identity and storage", "Private"). This page
records how the Android app implements them. Review it whenever storage, networking or an
exported component changes.

## Current choices

| Configuration | Behaviour |
|---|---|
| App manifest | `allowBackup="false"`; the launcher activity is the only exported component |
| Permissions | `INTERNET` only. Later work adds notification access, `POST_NOTIFICATIONS`, camera, and the listener/tile/share components, each deliberately |
| Network security (release) | `app/src/main/res/xml/network_security_config.xml`: cleartext denied, system trust anchors only |
| Network security (debug) | `app/src/debug/res/xml/network_security_config.xml`: cleartext also for `10.0.2.2`, `localhost`, `127.0.0.1` (a local `wrangler dev` Hub) |
| Legacy backup XML | `backup_rules.xml` excludes every domain (root, file, database, sharedpref, external) |
| Android 12+ extraction XML | `data_extraction_rules.xml` excludes every domain from both `cloud-backup` and `device-transfer` |
| Secrets | `core:security`: identity keys, group keys and the device token in `noBackupFilesDir/carom/vault.bin`, AES-256-GCM with a non-exportable Android Keystore key (`carom.vault.v1`), no user-auth requirement so background work runs while locked |
| Other local state | Room `carom-database` (outbox, seen ids, directory, app settings, recent items) and DataStore `user_preferences` (Hub URL, device id/name, toggles) |
| Logging | No HTTP logging interceptor. Sync logs carry event codes and ids only, never message content, keys, tokens or URIs. `toString()` of every secret-bearing type is redacted |

Hub URLs are never baked into a build; they come from a setup or pair URI, which already
requires `https:` except for loopback hosts (SPEC §8). With `adb reverse tcp:8787 tcp:8787`
a debug build can use `http://localhost:8787` from a QR code or pasted URI.

## Restore and transfer

Nothing is backed up or transferred. If the vault file ever reappears without its Keystore key
(restore, transfer, a cleared Keystore), `FileSecretVault` cannot decrypt it, deletes it and
reports empty contents, so the app onboards again with a new identity. This is the behaviour
SPEC §5.3 requires ("A reinstall means a new identity and a new pairing").

## Still to decide when the features land

- The notification listener, Quick Settings tile and share target must
  each be exported only as far as the platform requires, with permission guards where possible.
- Notification and clipboard content must never reach logs or crash reports.
- Configure real signing and artifact retention through [Release](RELEASING.md); keys do not
  belong in the repository.
