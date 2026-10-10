import Foundation

/// Device bearer tokens `ct1.<device_id>.<b64 rand32>.<b64 mac16>` (SPEC §6). Clients only need
/// `parse` (to read their own device id); `make` and `verify` mirror the Hub for tests.
public enum Token {
    public struct Parsed: Sendable, Equatable {
        public let deviceId: String
        public let rand: Data
        public let mac: Data
    }

    /// `mac16 = HMAC-SHA256(token_key, lp(device_id) || lp(rand32))[0..16]`
    public static func mac(tokenKey: Data, deviceId: String, rand: Data) -> Data {
        Crypto.hmacSHA256(key: tokenKey, Wire.lp(deviceId) + Wire.lp(rand)).prefixData(16)
    }

    public static func make(tokenKey: Data, deviceId: String, rand: Data) -> String {
        precondition(DeviceID.isValid(deviceId) && rand.count == 32)
        return ["ct1", deviceId, B64.encode(rand), B64.encode(mac(tokenKey: tokenKey, deviceId: deviceId, rand: rand))]
            .joined(separator: ".")
    }

    /// Strict syntactic parse; nil when malformed. Does not check the MAC.
    public static func parse(_ token: String) -> Parsed? {
        let parts = token.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4, parts[0] == "ct1", DeviceID.isValid(parts[1]),
              let rand = B64.decode(parts[2], length: 32), let mac = B64.decode(parts[3], length: 16)
        else { return nil }
        return Parsed(deviceId: parts[1], rand: rand, mac: mac)
    }

    /// Parses and checks the MAC in constant time. Returns the device id, or nil.
    public static func verify(_ token: String, tokenKey: Data) -> String? {
        guard let p = parse(token),
              Wire.constantTimeEqual(p.mac, mac(tokenKey: tokenKey, deviceId: p.deviceId, rand: p.rand))
        else { return nil }
        return p.deviceId
    }

    /// `SHA-256(token)` over the token's UTF-8 bytes, as stored by the Hub.
    public static func hash(_ token: String) -> Data { Crypto.sha256(Data(token.utf8)) }
}
