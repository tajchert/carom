# Localization and RTL checks

The sample currently ships English default resources only. Resource-backed labels and typed errors make translation possible; they do not mean translations have been supplied. Demo task text and test fixtures are English sample data.

## Add translations

- Keep default strings complete in each owning module: `app`, `feature/tasks/impl`, `core/ui`, and `core/designsystem`. Add language-specific `res/values-<language>/strings.xml` files alongside them and use locale qualifiers for regional variants when needed.
- Resolve UI labels/errors with resources rather than exception text or hardcoded strings. Use positional formatting arguments for task-specific accessibility labels; do not assemble sentences by concatenating translated fragments.
- Use quantity resources when adding count-dependent messages. Mark identifiers and other intentionally untranslated constants appropriately.
- Keep dates and times locale-aware. Task details already format with the configured UI locale and device time zone. Format any new numbers, currencies, and units for their intended locale too.

See Android's official [localization guide](https://developer.android.com/guide/topics/resources/localization).

## Verify layout and semantics

The manifest declares RTL support, but that declaration alone does not verify the screens. Check a real RTL locale and expanded translations on both app hosts. Use start/end positioning, preserve directional icon behavior, and inspect mixed-direction user-entered titles.

For a development-only pseudolocale check, enable `isPseudoLocalesEnabled = true` in the debug build type; it is not enabled in the starter. Select English (XA), `en-XA`, for expanded/accented text and Arabic (XB), `ar-XB`, for RTL testing using the device's developer/language settings. See the official [pseudolocale guide](https://developer.android.com/guide/topics/resources/pseudolocales).

Check task cards, create-form validation, detail dates, preferences, confirmation/error dialogs, touch targets, and spoken labels with long text and large fonts. Test at phone and adaptive window sizes. Keep the existing English screenshot references deterministic; introduce separate locale cases deliberately rather than changing their locale globally. Existing text-based functional journeys also assume English and need localized selectors or a fixed test locale when translations are added.

Run Android lint, review translated strings, and perform TalkBack checks in supported languages. See [Testing](TESTING.md) and [Screenshots](SCREENSHOT_TESTING.md).
