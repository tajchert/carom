import Foundation

/// Monotonic UUIDv7 msg_ids (SPEC §3.1), RFC 9562 §6.2 method 1: a 12-bit counter in rand_a,
/// reseeded with its top bit clear on every new millisecond. On counter overflow, or when the
/// clock goes backwards, the timestamp is advanced past the last one used.
public enum UUIDv7 {
    private static let maxMs: UInt64 = (1 << 48) - 1

    /// Lowercase, hyphenated UUID with version 7 and the RFC 9562 variant.
    public static func isValid(_ s: String) -> Bool {
        let c = Array(s.utf8)
        guard c.count == 36 else { return false }
        for (i, ch) in c.enumerated() {
            switch i {
            case 8, 13, 18, 23:
                if ch != UInt8(ascii: "-") { return false }
            case 14:
                if ch != UInt8(ascii: "7") { return false }
            case 19:
                if ![UInt8(ascii: "8"), UInt8(ascii: "9"), UInt8(ascii: "a"), UInt8(ascii: "b")].contains(ch) {
                    return false
                }
            default:
                if !((0x30...0x39).contains(ch) || (0x61...0x66).contains(ch)) { return false }
            }
        }
        return true
    }

    public static func isValid(_ v: JSON?) -> Bool {
        guard let s = v?.stringValue else { return false }
        return isValid(s)
    }

    /// The 48-bit Unix millisecond timestamp of a valid UUIDv7.
    public static func timestamp(_ id: String) -> Int? {
        guard isValid(id) else { return nil }
        let hex = id.replacingOccurrences(of: "-", with: "").prefix(12)
        return Int(hex, radix: 16)
    }

    /// Formats a UUIDv7 from its parts; `randB` is 8 bytes of which the low 62 bits are used.
    public static func format(ms: UInt64, counter: UInt16, randB: Data) -> String {
        precondition(ms <= maxMs && counter <= 0xFFF && randB.count == 8)
        var bytes = [UInt8](repeating: 0, count: 16)
        var t = ms
        for i in stride(from: 5, through: 0, by: -1) {
            bytes[i] = UInt8(t & 0xFF)
            t >>= 8
        }
        bytes[6] = 0x70 | UInt8(counter >> 8)
        bytes[7] = UInt8(counter & 0xFF)
        for (i, b) in randB.enumerated() { bytes[8 + i] = b }
        bytes[8] = 0x80 | (bytes[8] & 0x3F)
        let h = Data(bytes).hex
        let c = Array(h)
        return String(c[0..<8]) + "-" + String(c[8..<12]) + "-" + String(c[12..<16]) + "-" + String(c[16..<20]) + "-"
            + String(c[20...])
    }
}

/// A generator whose ids are strictly increasing across calls. Not thread-safe: keep one per
/// sender inside an actor.
public struct UUIDv7Generator: Sendable {
    private let now: @Sendable () -> Int
    private let random: RandomSource
    private var lastMs: Int = -1
    private var counter: UInt16 = 0

    public init(now: @escaping @Sendable () -> Int = { Int(Date().timeIntervalSince1970 * 1000) },
                random: @escaping RandomSource = systemRandom) {
        self.now = now
        self.random = random
    }

    /// Continues after `last` (a previously issued id), so ids stay monotonic across restarts.
    public mutating func resume(after last: String) {
        guard let ms = UUIDv7.timestamp(last) else { return }
        let c = Array(last.utf8)
        let counterHex = String(decoding: c[15..<18], as: UTF8.self)
        lastMs = ms
        counter = UInt16(counterHex, radix: 16) ?? 0xFFF
    }

    public mutating func next() -> String {
        let r = [UInt8](random(10))
        let seed = (UInt16(r[0]) << 8 | UInt16(r[1])) & 0x7FF
        var ms = now()
        if ms > lastMs {
            counter = seed
        } else {
            ms = lastMs
            counter += 1
            if counter > 0xFFF {
                ms = lastMs + 1
                counter = seed
            }
        }
        lastMs = ms
        return UUIDv7.format(ms: UInt64(ms), counter: counter, randB: Data(r[2..<10]))
    }
}
