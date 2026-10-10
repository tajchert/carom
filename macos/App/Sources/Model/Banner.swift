import Foundation

/// A problem or notice shown at the top of the menu.
struct Banner: Identifiable, Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        /// 401 from the Hub: offer to pair again.
        case tokenRejected
        /// This Mac was revoked and wiped.
        case revoked
        case deviceRemoved
        case hubUnreachable
        case pairingRejected
        case sendFailed
    }

    enum Style: Sendable { case info, warning, error }

    var kind: Kind
    var message: String
    var style: Style
    var id: String

    init(kind: Kind, message: String, style: Style, id: String? = nil) {
        self.kind = kind
        self.message = message
        self.style = style
        self.id = id ?? "\(kind)"
    }

    /// Whether the user can close it (the 401 banner stays until resolved).
    var isDismissible: Bool { kind != .tokenRejected }
}
