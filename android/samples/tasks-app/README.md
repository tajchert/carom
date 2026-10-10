# Tasks feature host

This module is a small, installable application that runs only the Tasks feature and the infrastructure it genuinely requires. It is a development tool—not a production variant, test APK, dynamic feature, or alternative implementation of the product.

## Run it

From Android Studio, select the `tasks-app` module. From the command line:

```bash
./gradlew :samples:tasks-app:installDebug
```

To compile without installing:

```bash
./gradlew :samples:tasks-app:assembleDebug
```

The installed application is named **Tasks Feature Host** and uses the distinct application ID `com.mtajchert.carom.app.samples.tasks`, so it can coexist with the complete sample app.

The host uses the real Room repository and DataStore preferences. Its toolbar offers the same preferences and confirmed demo import as the full app, and its theme follows the stored theme mode and dynamic-color choice. Tasks and preferences persist in the host's own application storage; they are separate from the complete app.

## Why this is faster

Gradle builds the host and its transitive dependency closure. Unrelated feature implementation modules are not compiled or packaged. The improvement becomes meaningful in a larger project where the main app aggregates many features, annotation processors, resources, generated code, and third-party SDKs.

This host also starts directly at `TaskListRoute`, eliminating unrelated login, onboarding, deep navigation, and fixture setup during manual iteration.

The current repository contains only one feature, so the raw build-time difference is intentionally modest. This module demonstrates a structure whose benefit grows with the application.

## What belongs here

- A minimal `Application` and activity.
- The real feature navigation contribution and design system.
- Only the concrete infrastructure needed to construct the feature's Hilt graph.
- Development-safe fakes or deterministic fixtures.
- Host-only scenario controls, if a feature later needs them.

## What must not belong here

- Copies or forks of production screens, ViewModels, repositories, or navigation.
- Product business logic that is absent from the real app.
- A second implementation of feature contracts.
- Production credentials, signing configuration, analytics, or publishing tasks.
- Dependencies on the main `:app` module.

If the host requires code from `:app`, that code is probably in the wrong architectural layer. Move reusable composition or contracts into an appropriate core or feature module instead of making the host depend on the complete application.

## Creating a host for another feature

Assume a feature has `:feature:checkout:api` and `:feature:checkout:impl` modules.

1. Copy this module to `samples/checkout-app` and include it in `settings.gradle.kts`.
2. Give it a unique namespace and application ID.
3. Rename the Application and Activity classes.
4. Depend on `checkout:api`, `checkout:impl`, and the design system.
5. Add only the concrete data/runtime modules needed to complete Hilt's graph.
6. Start navigation at the feature's public route and compose its exported navigation contribution.
7. Provide fake bindings at the host boundary when production services require authentication, remote state, hardware, or secrets.
8. Verify the host does not depend on `:app` or unrelated feature implementations.
9. Add `assembleDebug` to CI if the host is important to the team workflow.
10. Document any intentional differences from the complete application.

### Crucial dependency rules

The host owns an `@HiltAndroidApp` composition root. Hilt must see every module and constructor-injected type needed by the feature through the host's dependency graph. A missing database, repository, or network binding should fail compilation; do not bypass that signal with service locators or globals.

Use the feature's API/navigation contract as the entry point. The host depends on its implementation because it renders that feature, while production features should continue to depend only on other features' API modules.

Keep fake bindings explicit. When fake and production implementations share a Hilt binding, select them through appropriate variants or a host-specific DI replacement. Never make production behavior conditional on whether a feature host exists.

### Scenario selection

For a complex feature, add a host-only landing screen for scenarios such as empty, populated, loading, recoverable error, expired session, or long text. Inject scenarios through fake repositories or data sources. Do not add debug flags to production ViewModels solely for the host.

## Benefits

- Shorter compile, package, install, and startup cycles as the project grows.
- Immediate entry into the feature being changed.
- Deterministic manual states without modifying production behavior.
- Independent UI, accessibility, and performance exploration.
- A clear view of the feature's actual runtime dependencies.
- Continued feature work while unrelated application code is temporarily broken.

## Tradeoffs

- Every host is another application composition root to maintain.
- Manifest, theme, DI, and navigation wiring can drift from the complete app.
- Cross-feature interactions and application-level configuration are not exercised.
- Gradle still configures the included build; savings primarily come from avoiding compilation, processing, packaging, and deployment of unrelated dependency graphs.
- Adding hosts indiscriminately creates more maintenance than speed.

Create hosts for large, complex, independently owned, or hard-to-reach features. For a small screen, Compose previews and focused UI tests are usually cheaper and sufficient.

Always keep full-app CI and integration tests. A successful host proves the feature works in that host; it does not prove the complete product is correctly integrated.
