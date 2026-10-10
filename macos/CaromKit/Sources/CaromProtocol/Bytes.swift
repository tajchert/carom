import Foundation
import Security

/// SPEC §1 encodings and small byte helpers.
public enum Wire {
    /// `u32be(n)`.
    public static func u32be(_ n: UInt32) -> Data {
        withUnsafeBytes(of: n.bigEndian) { Data($0) }
    }

    /// `u64(n)`: 8-byte big-endian unsigned integer.
    public static func u64(_ n: UInt64) -> Data {
        withUnsafeBytes(of: n.bigEndian) { Data($0) }
    }

    /// `u64(n)` for a non-negative `Int`. Programmer error (trap) when negative.
    public static func u64(_ n: Int) -> Data {
        precondition(n >= 0, "u64 out of range")
        return u64(UInt64(n))
    }

    /// `lp(x) = u32be(len(x)) || x`.
    public static func lp(_ x: Data) -> Data {
        var out = u32be(UInt32(x.count))
        out.append(x)
        return out
    }

    /// `lp(x)` of a string's UTF-8 bytes.
    public static func lp(_ s: String) -> Data { lp(Data(s.utf8)) }

    public static func concat(_ parts: Data...) -> Data { concat(parts) }

    public static func concat(_ parts: [Data]) -> Data {
        var out = Data(capacity: parts.reduce(0) { $0 + $1.count })
        for p in parts { out.append(p) }
        return out
    }

    /// Constant-time comparison for equal-length inputs; false on a length mismatch.
    public static func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for (x, y) in zip(a, b) { diff |= x ^ y }
        return diff == 0
    }

    public static func isAllZero(_ d: Data) -> Bool {
        d.reduce(0) { $0 | $1 } == 0
    }

    /// Cryptographically secure random bytes.
    public static func randomBytes(_ count: Int) -> Data {
        var data = Data(count: count)
        let status = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        return data
    }
}

/// A source of random bytes. Injectable so tests and vectors can be deterministic.
public typealias RandomSource = @Sendable (Int) -> Data

public let systemRandom: RandomSource = { Wire.randomBytes($0) }

extension Data {
    /// Lowercase hex.
    public var hex: String {
        var s = ""
        s.reserveCapacity(count * 2)
        for b in self { s += String(format: "%02x", b) }
        return s
    }

    /// Parses lowercase or uppercase hex; nil when malformed.
    public init?(hex: String) {
        let chars = Array(hex.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var out = Data(capacity: chars.count / 2)
        func nibble(_ c: UInt8) -> UInt8? {
            switch c {
            case 0x30...0x39: c - 0x30
            case 0x41...0x46: c - 0x41 + 10
            case 0x61...0x66: c - 0x61 + 10
            default: nil
            }
        }
        var i = 0
        while i < chars.count {
            guard let hi = nibble(chars[i]), let lo = nibble(chars[i + 1]) else { return nil }
            out.append(hi << 4 | lo)
            i += 2
        }
        self = out
    }

    /// The first `n` bytes, as a new zero-based `Data`.
    func prefixData(_ n: Int) -> Data { Data(self.prefix(n)) }
}
