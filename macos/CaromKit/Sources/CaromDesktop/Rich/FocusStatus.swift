import Intents

/// Reads whether a Focus is on (`INFocusStatusCenter`). Needs the user's permission
/// (`NSFocusStatusUsageDescription`); without it every read is "not focused", so the panel
/// still shows and the native notification follows the Focus as usual.
@MainActor
public final class SystemFocusStatus: FocusStatusProviding {
    public init() {}

    public var isFocused: Bool {
        let center = INFocusStatusCenter.default
        guard center.authorizationStatus == .authorized else { return false }
        return center.focusStatus.isFocused ?? false
    }

    /// Asks once; does nothing after the user decided.
    public func requestAuthorization() async {
        guard INFocusStatusCenter.default.authorizationStatus == .notDetermined else { return }
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            INFocusStatusCenter.default.requestAuthorization { _ in done.resume() }
        }
    }
}
