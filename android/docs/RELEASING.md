# Release workflow

The starter verifies a minified release build but has no production signing configuration or publishing automation. `benchmarkRelease` and `nonMinifiedRelease` use the debug key for local tests; they are not distribution variants.

## Prepare a product release

1. Finish identity adoption, remove demo bindings/data, and review the [product checklist](TEMPLATE_CHECKLIST.md). Configure CI on the forge you use; the included workflow targets GitHub Actions, not GitLab.
2. Set `versionCode` and `versionName` in `app/build.gradle.kts`. Use an increasing code for successive product releases and define a user-visible naming policy.
3. Choose the upload/signing arrangement for your distribution channel. For Google Play, distinguish the upload key from the app signing key. Configure release signing in Gradle using protected local/CI inputs, or use Android Studio's **Generate Signed Bundle / APK** workflow. The repository defines no signing-secret variable names to populate.
4. Keep keystores and passwords outside tracked files. The existing ignore rules cover `.jks` and `.keystore`; they are not a substitute for controlling access and backups. CI signing material belongs in protected secrets and restricted release jobs.
5. Run [verification](TESTING.md#commands), relevant device tests, and screenshot checks. Regenerate reviewed Baseline Profiles when startup/common paths change.

See the official [app-signing guide](https://developer.android.com/studio/publish/app-signing) for key management and signed builds.

## Produce and retain artifacts

After configuring Gradle release signing:

```bash
./gradlew :app:bundleRelease
```

The bundle is under `app/build/outputs/bundle/release/`. Without release signing configuration, this task does not establish that the resulting bundle is ready for distribution. An AAB is not directly installable with `adb install`; use your channel's test delivery or generate device APKs with the official [bundletool](https://developer.android.com/tools/bundletool).

Retain the exact distributed artifact, version, source commit, and signing-certificate identity. Archive the release R8 mapping from `app/build/outputs/mapping/release/mapping.txt` with that version for crash retracing. The included CI verifies assembly; it does not publish or archive release mappings.

## Check the distributed build

Test the signed, optimized build rather than relying on debug behavior. Check startup, create/open/delete, persisted preferences, process restart, saved drafts, and deep links. Verify upgrades from the previous shipped version and database restore where supported. Check the minimum/target API, large fonts, RTL, accessibility, and adaptive windows for your product.

Publish App Link associations with the certificate that signs the installed distribution. Keep physical-device performance checks separate from emulator functional journeys. Use the distribution channel's internal test track before broader rollout, and record what was tested with the release.
