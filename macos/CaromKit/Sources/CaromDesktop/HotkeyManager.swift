import Carbon.HIToolbox
import Foundation

public enum HotkeyError: Error, Equatable, Sendable {
    case installFailed(Int32)
    case registerFailed(Int32)
}

/// Carbon modifier masks for `HotkeyManager.register`.
public enum HotkeyModifiers {
    public static let command = UInt32(cmdKey)
    public static let option = UInt32(optionKey)
    public static let control = UInt32(controlKey)
    public static let shift = UInt32(shiftKey)
}

/// A single global hotkey through Carbon `RegisterEventHotKey`. It needs no accessibility permission.
@MainActor
public final class HotkeyManager {
    /// Default clipboard hotkey: control + option + command + C.
    public static let defaultKeyCode = UInt32(kVK_ANSI_C)
    public static let defaultModifiers = HotkeyModifiers.control | HotkeyModifiers.option | HotkeyModifiers.command

    private var hotKeyRef: EventHotKeyRef?
    private var handlerRef: EventHandlerRef?
    private var handler: (@MainActor () -> Void)?

    public init() {}

    /// Registers the hotkey, replacing a previous one. `modifiers` are Carbon masks.
    public func register(keyCode: UInt32, modifiers: UInt32, handler: @escaping @MainActor () -> Void) throws {
        unregister()

        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        var ref: EventHandlerRef?
        let status = InstallEventHandler(
            GetApplicationEventTarget(),
            { _, _, userData in
                guard let userData else { return OSStatus(eventNotHandledErr) }
                let manager = Unmanaged<HotkeyManager>.fromOpaque(userData).takeUnretainedValue()
                MainActor.assumeIsolated { manager.handler?() }
                return noErr
            },
            1, &spec, Unmanaged.passUnretained(self).toOpaque(), &ref
        )
        guard status == noErr else { throw HotkeyError.installFailed(status) }

        var hk: EventHotKeyRef?
        let id = EventHotKeyID(signature: OSType(0x4352_4D48), id: 1)  // 'CRMH'
        let rs = RegisterEventHotKey(keyCode, modifiers, id, GetApplicationEventTarget(), 0, &hk)
        guard rs == noErr else {
            if let ref { RemoveEventHandler(ref) }
            throw HotkeyError.registerFailed(rs)
        }
        handlerRef = ref
        hotKeyRef = hk
        self.handler = handler
    }

    public func unregister() {
        if let hotKeyRef { UnregisterEventHotKey(hotKeyRef) }
        if let handlerRef { RemoveEventHandler(handlerRef) }
        hotKeyRef = nil
        handlerRef = nil
        handler = nil
    }
}
