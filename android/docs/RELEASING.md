# Release workflow

Release builds are minified and signed with the Carom release key. `benchmarkRelease` and `nonMinifiedRelease` use the debug key for local tests; they are not distribution variants.

## Signing setup

Release signing is wired in `app/build.gradle.kts`. Local builds read the gitignored file `android/keystore.properties`:

```properties
storeFile=/Users/<user>/keystores/carom-release.jks
storePassword=<password>
keyAlias=carom
keyPassword=<password>
```

CI reads the same values from `CAROM_KEYSTORE_PATH`, `CAROM_KEYSTORE_PASSWORD`, `CAROM_KEY_ALIAS` and `CAROM_KEY_PASSWORD`, populated by `.github/workflows/android-release.yml` from these repository secrets:

- `RELEASE_KEYSTORE_BASE64` (`base64 -i carom-release.jks`)
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

`keystore.properties`, `*.jks` and `*.keystore` must stay uncommitted. Keep a backup of the keystore and its password outside this machine: a lost key means users cannot upgrade in place. Without a release keystore, Gradle falls back to the debug key so contributor and CI release builds stay installable; a public release must always be verified as release-signed.

Expected release certificate:

```text
CN=Carom Release, O=Tajchert, C=PL
SHA-256: da44b2efba8cc35ee2f6e165ca3619d6b6fba1b3cd20a66da09fd58099cae279
```

## Prepare a release

1. Review [security and backup defaults](SECURITY_DEFAULTS.md).
2. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
3. Run [verification](TESTING.md#commands), relevant device tests, and screenshot checks. Regenerate reviewed Baseline Profiles when startup/common paths change.
4. Build and verify the signed APK locally:

   ```bash
   ./gradlew :app:assembleRelease
   APKSIGNER=$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -n1)
   "$APKSIGNER" verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```

   The certificate DN and SHA-256 must match the values above.
5. Commit, then tag and push: `git tag android-v<versionName> && git push origin android-v<versionName>`.

The tag runs `.github/workflows/android-release.yml`: unit tests, a tag/`versionName` check, the signed build, certificate verification, a checksum, and a GitHub Release with `carom-<version>.apk` and its `.sha256`. Pushing the same tag again replaces the assets. **Run workflow** on the Actions tab performs a dry run that only uploads a workflow artifact.

## Retain artifacts

Retain the exact distributed artifact, version, source commit, and signing-certificate identity. The release workflow uploads the R8 mapping (`carom-<version>-mapping.txt`) as a workflow artifact for 90 days; archive it with the version for crash retracing.

## Check the distributed build

Test the signed, optimized build rather than relying on debug behavior. Check startup, setup and pairing against a real Hub, sending and receiving, revocation, persisted settings and process restart. Verify upgrades from the previous shipped version and database restore where supported. Check the minimum/target API, large fonts, RTL, accessibility, and adaptive windows for your product.

Publish App Link associations with the certificate that signs the installed distribution. Keep physical-device performance checks separate from emulator functional journeys. Use the distribution channel's internal test track before broader rollout, and record what was tested with the release.
