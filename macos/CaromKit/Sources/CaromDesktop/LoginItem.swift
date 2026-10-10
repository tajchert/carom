import ServiceManagement

/// Start at login for the main app, through `SMAppService`.
public enum LoginItem {
    @MainActor public static var isEnabled: Bool {
        SMAppService.mainApp.status == .enabled
    }

    @MainActor public static func setEnabled(_ enabled: Bool) throws {
        let service = SMAppService.mainApp
        if enabled {
            if service.status != .enabled { try service.register() }
        } else if service.status == .enabled || service.status == .requiresApproval {
            try service.unregister()
        }
    }
}
