import Foundation

/// The plaintext inside an envelope's `ct` (SPEC §3.1).
public struct Payload: Sendable, Equatable {
    public var type: String
    /// Sender clock at send time, Unix ms.
    public var ts: Int
    /// msg_id of the message this replies to.
    public var replyTo: String?
    public var body: JSONObject

    public init(type: String, ts: Int, replyTo: String? = nil, body: JSONObject) {
        self.type = type
        self.ts = ts
        self.replyTo = replyTo
        self.body = body
    }

    public init(type: String, ts: Int, replyTo: String? = nil, body: some MessageBody) {
        self.init(type: type, ts: ts, replyTo: replyTo, body: body.json)
    }

    /// `{type, ts, reply_to?, body}` in this order.
    public var json: JSON {
        var o: JSONObject = ["type": .string(type), "ts": JSON(ts)]
        if let replyTo { o["reply_to"] = .string(replyTo) }
        o["body"] = .object(body)
        return .object(o)
    }

    /// Parses and checks the wrapper fields; nil on any malformed input.
    public init?(json: JSON) {
        guard case .object(let p) = json,
              let type = p["type"]?.stringValue, !type.isEmpty,
              let ts = p["ts"]?.safeInt, ts >= 0,
              case .object(let body)? = p["body"]
        else { return nil }
        if let r = p["reply_to"] {
            guard UUIDv7.isValid(r) else { return nil }
            replyTo = r.stringValue
        } else {
            replyTo = nil
        }
        self.type = type
        self.ts = ts
        self.body = body
    }

    /// Serializes and pads (unless `pad` is false). Throws `PayloadError.tooLarge` above 65 536 bytes.
    public func encode(pad: Bool = true) throws(PayloadError) -> Data {
        let data = json.serializedData()
        if pad { return try Padding.pad(data) }
        if data.count > Carom.Limits.maxPlaintext { throw .tooLarge }
        return data
    }

    public static func decode(_ plaintext: Data) -> Payload? {
        guard let json = try? JSON.parse(plaintext) else { return nil }
        return Payload(json: json)
    }
}

public enum PayloadError: Error, Sendable, Equatable {
    case tooLarge
}

/// Payload padding (SPEC §3.1).
public enum Padding {
    /// The next multiple of 256 up to 4096, the next multiple of 4096 above that.
    public static func paddedLength(_ n: Int) throws(PayloadError) -> Int {
        precondition(n >= 0)
        if n > Carom.Limits.maxPlaintext { throw .tooLarge }
        let bucket = n <= 4096 ? 256 : 4096
        return (n + bucket - 1) / bucket * bucket
    }

    /// Appends ASCII spaces up to `paddedLength`.
    public static func pad(_ data: Data) throws(PayloadError) -> Data {
        let target = try paddedLength(data.count)
        return data + Data(repeating: 0x20, count: target - data.count)
    }
}
