# Android agent setup

Install Android CLI using the [official installation instructions](https://developer.android.com/tools/agents/android-cli), then check `android --version` and `android info`. Gradle remains the build entry point for this repository.

Install selected skills for your agent in this project:

```bash
android skills add android-cli --project=. --agent=codex
android skills add testing-setup --project=. --agent=codex
android skills add navigation-3 --project=. --agent=codex
android skills add edge-to-edge --project=. --agent=codex
android skills add android-intent-security --project=. --agent=codex
android skills add android-permissions-security --project=. --agent=codex
```

Check `android skills --help` for your installed CLI. Update installed skills with `android skills update --all`; install other skills only when the product needs their capabilities.

Useful workflows:

```bash
android docs search "Navigation 3 predictive back"
android emulator list
android run --device=<serial>
android layout --device=<serial> --pretty
android screen capture --device=<serial>
```

Before interacting with devices or running agent journeys, read the installed Android CLI skill's `references/interact.md` and `references/journeys.md`. Use the official docs/knowledge base when changing Android APIs; skill recipes can require preview dependencies unsuitable for this starter.

The root AGENTS.md links repository behavior and verification. Installed skill directories are developer tooling; they do not belong in the application's dependencies.
