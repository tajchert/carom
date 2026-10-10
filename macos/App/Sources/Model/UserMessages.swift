import CaromHub
import CaromSync
import Foundation

/// User-facing sentences (docs/CLIENTS.md UX rules: short, plain, name the cause and the fix).
enum UserMessages {
    static let tokenRejected = "Your Hub refused the request because this device was removed. Pair again to continue."
    static let revoked = "This Mac was removed from your devices. Set it up again to continue."
    static let hubUnreachable = "Can't reach your Hub. Carom keeps trying in the background."
    static let pairingRejected = "A device tried to join but failed the security check. It was turned away."
    static let nothingToSend = "There's no text on the clipboard to send."
    static let noClipboardTargets = "No device takes clipboard items from this Mac. Check Settings › Clipboard."
    static let textTooLarge = "This text is too large to send. The limit is about 60 KB."
    static let queued = "You're offline. Carom will send it when the Hub is reachable."
    static let notURI = "That isn't a Carom code. Copy the whole carom:// link and try again."
    static let noQRInImage = "No Carom QR code was found in that image."
    static let setupURIExpected = "That's a pairing code. Choose Join instead."
    static let pairURIExpected = "That's a setup code. Choose Set up a new hub instead."

    static func removed(name: String, by: String?) -> String {
        if let by { return "\(by) removed \(name)." }
        return "\(name) was removed without a key change (no rekey)."
    }

    static func sendDropped(code: String) -> String {
        "Your Hub refused a message (\(code)). It was not delivered."
    }

    static func sent(to names: [String]) -> String {
        switch names.count {
        case 0: "Sent."
        case 1: "Sent to \(names[0])."
        default: "Sent to \(names.count) devices."
        }
    }

    /// Explains a failed onboarding, pairing or settings action.
    static func describe(_ error: any Error) -> String {
        if let e = error as? SyncError {
            switch e {
            case .invalidURI: return notURI
            case .alreadyConfigured: return "This Mac is already set up."
            case .notConfigured: return "This Mac isn't set up yet."
            case .credentialsMismatch, .welcomeInvalid:
                return "The pairing couldn't be verified. Show a new code on your other device and try again."
            case .unknownPairing, .pairingExpired: return "The pairing code expired. Show a new code and try again."
            case .pairingRejected: return "The other device declined. Show a new code and try again."
            case .payloadTooLarge: return textTooLarge
            case .rekeyFailed: return "Couldn't rotate the group key. Check your connection and try again."
            case .store: return "Carom couldn't save its keys in the Keychain."
            }
        }
        if let e = error as? HubError {
            switch e {
            case .transport: return "Can't reach the Hub. Check the address and your connection."
            case .invalidResponse: return "The Hub sent an answer Carom doesn't understand. Update the Hub and try again."
            case .missingToken: return "This Mac isn't set up yet."
            case .api(let api):
                switch api.status {
                case 401: return tokenRejected
                case 403: return "The Hub refused the setup token. Use the code printed by your latest deploy."
                case 404, 410: return "This code was already used or has expired. Show a new one."
                case 409: return "This Hub is already set up. Join from a device that's already paired."
                case 429: return "Too many attempts. Wait a while and try again."
                case 503: return "The Hub isn't ready. Check its SETUP_TOKEN secret and deploy again."
                default: return "The Hub couldn't complete the request (\(api.code.rawValue)). Try again."
                }
            }
        }
        return "Something went wrong. Try again."
    }
}
