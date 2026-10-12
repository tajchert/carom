# Carom

Phone-to-Mac notification and clipboard sync through a self-hosted Cloudflare Hub, end-to-end encrypted.

## Layout
- `hub/` — Cloudflare Worker + Durable Object (TypeScript). `npm test` from `hub/`.
- `protocol/` — spec and shared test vectors.
- `android/` — Android app (Kotlin, Compose, Hilt). Modules: `app`, `core/*` (`designsystem` holds the theme and shared components), `feature/<name>/{api,impl}`.
- `macos/CaromKit/` — Swift package (protocol, hub client, sync, desktop UI pieces).
- `macos/App/` — the menu-bar app (`Sources/Views` for SwiftUI, `Resources/Assets.xcassets`). Project generated from `project.yml` with `xcodegen generate` (commit both).
- `docs/images/` — README images, rendered by `macos/App/Tools/readme-images.sh` (the Android shots come from Compose screenshot-test references).

## Build and test (run narrowly)
- Android, from `android/`: `./gradlew :feature:home:impl:testDebugUnitTest`, `./gradlew :<module>:updateDebugScreenshotTest` / `validateDebugScreenshotTest`, `./gradlew :app:assembleDebug`. See `android/docs/SCREENSHOT_TESTING.md`.
- CaromKit, from `macos/CaromKit/`: `swift test --filter <Suite>`.
- macOS app, from the repo root: `xcodebuild -project macos/App/Carom.xcodeproj -scheme Carom -configuration Debug build` (or `test`). Pipe through `tail`/`grep`.

## Conventions
- Write code that matches the surrounding style; keep comments sparse.
- Commit messages: short imperative subject, wrapped body.
