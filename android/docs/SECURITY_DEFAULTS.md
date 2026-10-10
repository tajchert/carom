# Security and backup defaults

This describes the sample's configuration, not a complete product security policy. Review it when replacing the feature, adding networking, or storing sensitive data.

## Current choices

| Configuration | Current behavior |
|---|---|
| App manifest | `allowBackup="true"`, RTL support, and one exported launcher/deep-link activity |
| Permissions | The app declares no INTERNET or ACCESS_NETWORK_STATE permission; its backend is an in-memory fake |
| Network security XML | Cleartext denied by default; exceptions for `10.0.2.2`, `localhost`, and `127.0.0.1`, including subdomains |
| Legacy backup XML | Excludes the entire SharedPreferences domain |
| Android 12+ extraction XML | Excludes SharedPreferences from cloud backup; no explicit device-transfer section |
| Local storage | Room database `carom-database` and DataStore file `files/datastore/user_preferences.preferences_pb` |

The cleartext exceptions are in the main configuration, so they also apply to release variants. The fake makes no HTTP requests. When adding a backend, add INTERNET only when required and move development exceptions to an appropriate debug configuration or remove them.

## Backup implications

The SharedPreferences exclusion does not exclude Room databases or Preferences DataStore: they use different storage domains. The current rules do not explicitly exclude the task database or DataStore file. The missing device-transfer section also means the cloud exclusions are not an explicit device-to-device policy.

Android backs up databases and internal files by default, subject to its transport and device conditions. Use separate cloud and device-transfer rules where required. See the official [Auto Backup guide](https://developer.android.com/identity/data/autobackup), including the platform caveat that disabling cloud backup does not universally disable device transfers.

For a product, decide which user data should restore, which settings are portable, and which credentials/device identifiers must be excluded. Update both `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml`, then test backup/restore, database migration after restore, and transfer behavior on supported Android versions. Do not treat uninstall/reinstall as proof of an empty database because restoration may occur.

## Other product decisions

- Validate incoming links and authorize the destination against the signed-in user when authentication exists. The sample does not implement authentication; see [Deep links](DEEP_LINKS.md).
- Choose credential storage and retention deliberately. Room and Preferences DataStore in this starter are not configured as encrypted credential stores.
- Keep server credentials out of the APK. Review logging, exported components, permission requests, and any new SDK's data collection.
- Configure real signing and artifact retention through [Release](RELEASING.md); keys do not belong in the repository.

Use the [product checklist](TEMPLATE_CHECKLIST.md) as the handoff list, and document the policy chosen for the actual product.
