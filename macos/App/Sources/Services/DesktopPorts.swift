import AppKit
import Carbon.HIToolbox
import ServiceManagement
import CaromDesktop

// Narrow seams over the CaromDesktop services so AppModel can be unit-tested with fakes.

/// The native presenter. Posting and withdrawing (`NativeNotifying`) go through
/// `NotificationCoordinator`; the model itself asks for permission and receives the actions.
@MainActor
protocol NotificationPresenting: NativeNotifying {
    var onAction: ((NativeNotificationEvent) -> Void)? { get set }
    func requestAuthorization() async -> Bool
    func authorizationState() async -> Bool?
}

@MainActor
protocol ClipboardWatching: AnyObject {
    var onCopy: ((ClipboardItem) -> Void)? { get set }
    var accessBehavior: PasteboardAccess { get }
    func start()
    func stop()
    func write(text: String, sensitive: Bool)
    func readCurrent() -> ClipboardItem?
    /// The pasteboard change count. Read it right after `write` to remember that write.
    var changeCount: Int { get }
    /// Empties the pasteboard when nothing was copied since `count`. Returns whether it cleared.
    func clear(ifChangeCount count: Int) -> Bool
}

@MainActor
protocol HotkeyRegistering: AnyObject {
    func register(keyCode: UInt32, modifiers: UInt32, handler: @escaping @MainActor () -> Void) throws
    func unregister()
}

@MainActor
protocol LoginItemControlling {
    var isEnabled: Bool { get }
    /// Registered, but the user has to allow it in System Settings › General › Login Items.
    var requiresApproval: Bool { get }
    func setEnabled(_ enabled: Bool) throws
}

extension NotificationPresenter: NotificationPresenting {}
extension ClipboardWatcher: ClipboardWatching {}
extension HotkeyManager: HotkeyRegistering {}

struct SystemLoginItem: LoginItemControlling {
    var isEnabled: Bool { LoginItem.isEnabled }
    var requiresApproval: Bool { SMAppService.mainApp.status == .requiresApproval }
    func setEnabled(_ enabled: Bool) throws { try LoginItem.setEnabled(enabled) }
}

/// The global "send clipboard" shortcut: ⌃⌥⌘C.
enum SendHotkey {
    static let keyCode = UInt32(kVK_ANSI_C)
    static let modifiers = HotkeyModifiers.control | HotkeyModifiers.option | HotkeyModifiers.command
    static let display = "⌃⌥⌘C"
}

/// The concrete CaromDesktop services.
@MainActor
struct DesktopServices {
    var presenter: any NotificationPresenting
    var clipboard: any ClipboardWatching
    var hotkey: any HotkeyRegistering
    var loginItem: any LoginItemControlling
    var panel: any CardPanelHosting
    var focus: any FocusStatusProviding
    /// Where the Live Update chip is drawn.
    var liveChipHost: any LiveChipHost = NullLiveChipHost()

    static func live() -> DesktopServices {
        DesktopServices(presenter: NotificationPresenter(center: .current()),
                        clipboard: ClipboardWatcher(pasteboard: .general, interval: 0.5),
                        hotkey: HotkeyManager(), loginItem: SystemLoginItem(),
                        panel: RichPanelPresenter(), focus: SystemFocusStatus(), liveChipHost: StatusItemChipHost())
    }
}
