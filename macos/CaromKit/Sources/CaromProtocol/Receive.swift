import Foundation

/// Receiver rules (SPEC §3.3), applied in order. Every failure means: drop the message
/// silently, log locally (the code only, never content), and still acknowledge it.
public enum ReceiveError: String, Error, Sendable, CaseIterable {
    case badShape = "bad_shape"
    case notAddressed = "not_addressed"
    case untrustedSender = "untrusted_sender"
    case badSignature = "bad_signature"
    case unknownKv = "unknown_kv"
    case decryptFailed = "decrypt_failed"
    case badPayload = "bad_payload"
    case expired
    case fromFuture = "from_future"
    case replay
    case unknownType = "unknown_type"
    case staleClipboard = "stale_clipboard"
    case replyNotAllowed = "reply_not_allowed"
}

public struct ReceiveContext: Sendable {
    public var selfId: String
    /// Trusted directory (SPEC §5.3): member id → sign_pub.
    public var directory: [String: Data]
    /// Group keys by version.
    public var keys: [Int: Data]
    /// Receiver clock, Unix ms.
    public var now: Int
    /// msg_ids already seen (kept 7 days plus skew).
    public var seen: Set<String>
    /// Types this device handles (its `caps`).
    public var knownTypes: Set<String>
    /// `ts` of the last clipboard item applied locally.
    public var lastClipboardTs: Int?
    /// `to` of messages this device sent, by msg_id.
    public var originalRecipients: [String: [String]]

    public init(selfId: String, directory: [String: Data], keys: [Int: Data], now: Int, seen: Set<String> = [],
                knownTypes: Set<String> = Set(MessageType.allCases.map(\.rawValue)), lastClipboardTs: Int? = nil,
                originalRecipients: [String: [String]] = [:]) {
        self.selfId = selfId
        self.directory = directory
        self.keys = keys
        self.now = now
        self.seen = seen
        self.knownTypes = knownTypes
        self.lastClipboardTs = lastClipboardTs
        self.originalRecipients = originalRecipients
    }
}

public struct ReceivedMessage: Sendable, Equatable {
    public let envelope: Envelope
    public let payload: Payload
    /// The decoded body (every type that passes the receiver checks is a known v1 type).
    public let message: Message
}

public enum Receiver {
    /// Rule 1b: `from` is a trusted member and `device_id(sign_pub)` matches. Returns its key.
    public static func trustedSenderKey(_ directory: [String: Data], _ from: String) -> Data? {
        guard let pub = directory[from], DeviceID.matches(from, signPub: pub) else { return nil }
        return pub
    }

    /// Rule 3: `now - ts <= ttl_s*1000 + 300000` and `ts - now <= 300000`, both inclusive.
    public static func checkFreshness(ts: Int, ttlS: Int, now: Int) -> ReceiveError? {
        if now - ts > ttlS * 1000 + Carom.Limits.clockSkewMs { return .expired }
        if ts - now > Carom.Limits.clockSkewMs { return .fromFuture }
        return nil
    }

    /// Rule 6: a clipboard item older than the last one applied is ignored. Equal ts is accepted.
    public static func isStaleClipboard(ts: Int, last: Int?) -> Bool {
        if let last { return ts < last }
        return false
    }

    /// Rule 7: a reply is accepted only from a recipient of the original message.
    public static func isReplyAllowed(from: String, originalTo: [String]?) -> Bool {
        originalTo?.contains(from) ?? false
    }

    /// Applies SPEC §3.3 rules 1–7 in order and returns the first failure, or the message.
    public static func receive(_ value: JSON, context ctx: ReceiveContext) -> Result<ReceivedMessage, ReceiveError> {
        guard case .success(let env) = EnvelopeCodec.validateShape(value) else { return .failure(.badShape) }
        return receive(env, context: ctx)
    }

    public static func receive(_ env: Envelope, context ctx: ReceiveContext) -> Result<ReceivedMessage, ReceiveError> {
        guard env.to.contains(ctx.selfId) else { return .failure(.notAddressed) }
        guard let signPub = trustedSenderKey(ctx.directory, env.from) else { return .failure(.untrustedSender) }

        let opened: EnvelopeCodec.Opened
        switch EnvelopeCodec.open(env, signPub: signPub, keys: ctx.keys) {
        case .success(let o): opened = o
        case .failure(let e): return .failure(ReceiveError(rawValue: e.rawValue)!)
        }
        let payload = opened.payload

        if let err = checkFreshness(ts: payload.ts, ttlS: env.ttlS, now: ctx.now) { return .failure(err) }
        if ctx.seen.contains(env.msgId) { return .failure(.replay) }

        guard ctx.knownTypes.contains(payload.type), let type = MessageType(rawValue: payload.type) else {
            return .failure(.unknownType)
        }
        guard let message = Message(type: type, body: payload.body) else { return .failure(.badPayload) }
        if type == .notificationDismiss, payload.replyTo == nil { return .failure(.badPayload) }

        if type == .clipboardSet, isStaleClipboard(ts: payload.ts, last: ctx.lastClipboardTs) {
            return .failure(.staleClipboard)
        }
        if let replyTo = payload.replyTo,
           !isReplyAllowed(from: env.from, originalTo: ctx.originalRecipients[replyTo]) {
            return .failure(.replyNotAllowed)
        }
        return .success(ReceivedMessage(envelope: env, payload: payload, message: message))
    }
}
