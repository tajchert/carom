# Carom Android guidance

Read [README](README.md) for implemented behavior and the module map, [`protocol/SPEC.md`](../protocol/SPEC.md) for the wire contract and [`docs/CLIENTS.md`](../docs/CLIENTS.md) for client behaviour. The spec wins over everything else. [Testing](docs/TESTING.md) has the commands.

- Do not edit `../protocol/`, `../hub/` or `../docs/` from Android work; report spec issues instead. Every `protocol/vectors/*.json` case must keep passing in `:core:protocol` (signatures by verification).
- Features may depend on another feature's API, never its implementation. Core modules do not depend on features. `core:protocol` and `core:model` stay pure Kotlin/JVM.
- New libraries go into `gradle/libs.versions.toml` and new modules into `settings.gradle.kts` only when there is a concrete requirement; the common ones are already registered.
- Secrets (identity keys, group keys, device token) live only in `core:security`. Nothing is backed up or transferred. Never log keys, tokens, setup/pair URIs, or notification and clipboard content; log error codes and ids.
- Only the launcher activity is exported. Every new exported component (listener, tile, share target) is a deliberate, reviewed decision.
- Room is the source of truth for local state. Never use destructive migrations; export every schema and add a tested migration for each change.
- ViewModels expose typed state/errors. Resolve user-facing text from resources in Compose (prefix string names with the module), preserve cancellation, and collect state with lifecycle awareness.
- Use [Android CLI and official skills](docs/AGENT_SETUP.md) for current guidance. Keep stable dependencies; screenshot testing is the documented alpha exception.
- Before completion run the applicable [verification commands](docs/TESTING.md#commands). Report any checks that could not run.
- For UI changes validate screenshots and inspect changed references: [screenshot workflow](docs/SCREENSHOT_TESTING.md). Never regenerate references merely to hide an unexplained failure.
- For database/lifecycle/Keystore/accessibility changes run the device tests in [Testing](docs/TESTING.md). Benchmark timing claims require a physical device.
- Update the README and relevant guides whenever implemented behavior, configuration, or developer workflows change.
