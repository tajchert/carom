import Foundation

/// QR / pasted-text URIs (SPEC §8):
///
///     carom://setup?v=1&h=<urlenc hub url>&t=<setup token>
///     carom://pair?v=1&h=<urlenc hub url>&c=<pair_id>&s=<b64 pair_secret>
///
/// Builders percent-encode every byte outside the RFC 3986 unreserved set (uppercase hex).
/// Parsers are strict: exact parameter set, no duplicates, valid percent-escapes, and `+` must be
/// percent-encoded inside values.
public enum QRPayload: Sendable, Equatable {
    case setup(hub: String, setupToken: String)
    case pair(hub: String, pairId: String, pairSecret: Data)

    public var hub: String {
        switch self {
        case .setup(let hub, _), .pair(let hub, _, _): hub
        }
    }
}

public enum QR {
    private static let minSetupToken = 32
    private static let loopbackHosts: Set<String> = ["localhost", "127.0.0.1", "[::1]", "::1"]

    private static func isUnreserved(_ b: UInt8) -> Bool {
        (0x30...0x39).contains(b) || (0x41...0x5A).contains(b) || (0x61...0x7A).contains(b)
            || b == 0x2D || b == 0x2E || b == 0x5F || b == 0x7E
    }

    /// Raw characters allowed in a value besides `%XX`: `A-Za-z0-9-._~!$'()*,;:@/?=`.
    private static func isAllowedRaw(_ b: UInt8) -> Bool {
        isUnreserved(b) || Array("!$'()*,;:@/?=".utf8).contains(b)
    }

    /// Percent-encodes all bytes except `A-Z a-z 0-9 - . _ ~`.
    public static func percentEncode(_ s: String) -> String {
        var out = ""
        for b in s.utf8 {
            if isUnreserved(b) {
                out.unicodeScalars.append(Unicode.Scalar(b))
            } else {
                out += String(format: "%%%02X", b)
            }
        }
        return out
    }

    private static func hexValue(_ c: UInt8) -> UInt8? {
        switch c {
        case 0x30...0x39: c - 0x30
        case 0x41...0x46: c - 0x41 + 10
        case 0x61...0x66: c - 0x61 + 10
        default: nil
        }
    }

    private static func percentDecode(_ raw: Substring) -> String? {
        let chars = Array(raw.utf8)
        var bytes: [UInt8] = []
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if c == UInt8(ascii: "%") {
                guard i + 2 < chars.count, let hi = hexValue(chars[i + 1]), let lo = hexValue(chars[i + 2]) else { return nil }
                bytes.append(hi << 4 | lo)
                i += 3
            } else {
                guard isAllowedRaw(c) else { return nil }
                bytes.append(c)
                i += 1
            }
        }
        return String(bytes: bytes, encoding: .utf8)
    }

    private static func hasControl(_ s: String) -> Bool {
        s.unicodeScalars.contains { $0.value < 0x20 || $0.value == 0x7F }
    }

    /// Hub base URL: https (http only for loopback hosts), no credentials, query or fragment.
    public static func isValidHubURL(_ s: String) -> Bool {
        if hasControl(s) || s.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) }) {
            return false
        }
        if s.contains("?") || s.contains("#") { return false }
        guard let comps = URLComponents(string: s), let scheme = comps.scheme?.lowercased(),
              let host = comps.host?.lowercased(), !host.isEmpty
        else { return false }
        guard comps.user == nil, comps.password == nil else { return false }
        // Reject "https:host" and other forms without an authority.
        guard s.lowercased().hasPrefix(scheme + "://") else { return false }
        if scheme == "https" { return true }
        return scheme == "http" && loopbackHosts.contains(host)
    }

    public static func isValidSetupToken(_ s: String) -> Bool {
        s.utf16.count >= minSetupToken && !hasControl(s)
    }

    /// Exactly 22 characters of `[A-Za-z0-9_-]` (b64 of 16 bytes).
    public static func isValidPairId(_ s: String) -> Bool {
        let c = Array(s.utf8)
        return c.count == 22 && c.allSatisfy {
            (0x30...0x39).contains($0) || (0x41...0x5A).contains($0) || (0x61...0x7A).contains($0) || $0 == 0x2D
                || $0 == 0x5F
        }
    }

    public enum BuildError: Error, Sendable { case invalidHubURL, invalidSetupToken, invalidPairId, invalidPairSecret }

    public static func buildSetupURI(hub: String, setupToken: String) throws(BuildError) -> String {
        guard isValidHubURL(hub) else { throw .invalidHubURL }
        guard isValidSetupToken(setupToken) else { throw .invalidSetupToken }
        return "carom://setup?v=1&h=\(percentEncode(hub))&t=\(percentEncode(setupToken))"
    }

    public static func buildPairURI(hub: String, pairId: String, pairSecret: Data) throws(BuildError) -> String {
        guard isValidHubURL(hub) else { throw .invalidHubURL }
        guard isValidPairId(pairId) else { throw .invalidPairId }
        guard pairSecret.count == 32 else { throw .invalidPairSecret }
        return "carom://pair?v=1&h=\(percentEncode(hub))&c=\(pairId)&s=\(B64.encode(pairSecret))"
    }

    private static func parseQuery(_ query: Substring, allowed: [String]) -> [String: String]? {
        var params: [String: String] = [:]
        for part in query.split(separator: "&", omittingEmptySubsequences: false) {
            guard let eq = part.firstIndex(of: "="), eq != part.startIndex else { return nil }
            let key = String(part[part.startIndex..<eq])
            guard allowed.contains(key), params[key] == nil,
                  let value = percentDecode(part[part.index(after: eq)...])
            else { return nil }
            params[key] = value
        }
        return params.count == allowed.count ? params : nil
    }

    /// Parses pasted or scanned text (surrounding whitespace is ignored). nil when invalid.
    public static func parse(_ text: String) -> QRPayload? {
        let s = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let kind: String
        let query: Substring
        if s.hasPrefix("carom://setup?") {
            kind = "setup"
            query = s.dropFirst("carom://setup?".count)
        } else if s.hasPrefix("carom://pair?") {
            kind = "pair"
            query = s.dropFirst("carom://pair?".count)
        } else {
            return nil
        }
        guard let params = parseQuery(query, allowed: kind == "setup" ? ["v", "h", "t"] : ["v", "h", "c", "s"]),
              params["v"] == "1", let hub = params["h"], isValidHubURL(hub)
        else { return nil }
        if kind == "setup" {
            guard let t = params["t"], isValidSetupToken(t) else { return nil }
            return .setup(hub: hub, setupToken: t)
        }
        guard let c = params["c"], isValidPairId(c), let sStr = params["s"], let secret = B64.decode(sStr, length: 32)
        else { return nil }
        return .pair(hub: hub, pairId: c, pairSecret: secret)
    }
}
