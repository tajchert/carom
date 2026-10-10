# First run and troubleshooting

Start with the unchanged sample before adopting its identity. The current toolchain is in the [README](../README.md) and `gradle/libs.versions.toml`.

## Configure your machine

1. Install Android Studio supporting the repository's AGP version. In SDK Manager install Android SDK Platform 37, Build Tools 36.0.0, and Platform Tools. Install an emulator system image if you need a virtual device.
2. Configure JDK 21 for Gradle, including Android Studio's Gradle JDK. `java -version` checks the shell Java; `./gradlew --version` checks the JVM actually used by Gradle. Production bytecode still targets Java 17.
3. Let Android Studio create `local.properties`, or create it yourself with an absolute SDK path:

   ```properties
   sdk.dir=/absolute/path/to/Android/sdk
   ```

   This file is ignored by Git. `ANDROID_HOME` is another way to identify the SDK; keep its value consistent with local configuration. Put the SDK's `platform-tools` directory on your PATH for `adb`.
4. Let Gradle download dependencies on the first build. Use the repository's `./gradlew`, rather than a separately installed Gradle.

See the official [environment-variable guide](https://developer.android.com/tools/variables) for SDK and Java configuration.

## Build and run

Run the [verification commands](TESTING.md#commands), then start an AVD in Android Studio's Device Manager or attach an authorized USB-debugging device:

```bash
adb devices
./gradlew :app:installDebug
adb shell am start -n com.mtajchert.carom.app.debug/com.mtajchert.carom.app.MainActivity
```

These examples use the original identity. After adoption, substitute your package; the debug application ID keeps the `.debug` suffix. With several devices attached, select the target explicitly, for example with `adb -s <serial>` and Android Studio's run-device selector.

The first database creation seeds two tasks. Create a task, relaunch, open its detail, and try preferences. Demo import replaces local tasks only after confirmation. For the separate development app, see [Tasks feature host](../samples/tasks-app/README.md).

## Common failures

| Symptom | Check |
|---|---|
| SDK location not found | Absolute `sdk.dir` in `local.properties`, or a correct `ANDROID_HOME` |
| Java/Gradle compatibility error | `./gradlew --version` and Android Studio's Gradle JDK both use JDK 21 |
| Missing platform or build tools | SDK Manager has the exact versions required by the version catalog |
| Dependency download fails | Repository access, proxy settings, and whether Gradle offline mode is enabled |
| Device is absent or unauthorized | `adb devices`, USB debugging authorization, or whether the AVD finished booting |
| Install fails after switching signing keys | Use a matching signing key or remove only the conflicting sample installation; uninstall deletes its local data |
| Screenshot comparison differs | Inspect the diff and rendering environment using [screenshot testing](SCREENSHOT_TESTING.md); do not blindly update references |

For a reproducible failure, capture the failing task and `./gradlew <task> --stacktrace` output. Fix the cause before clearing caches or changing toolchain versions.
