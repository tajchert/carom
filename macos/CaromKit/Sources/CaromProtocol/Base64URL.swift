import Foundation

/// base64url without padding (RFC 4648 §5), SPEC §1. Decoding is strict: no padding, nothing
/// outside the alphabet, no whitespace, and canonical (zero) trailing bits.
public enum B64 {
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".utf8)
    private static let lookup: [Int8] = {
        var t = [Int8](repeating: -1, count: 256)
        for (i, c) in alphabet.enumerated() { t[Int(c)] = Int8(i) }
        return t
    }()

    public static func encode(_ data: Data) -> String {
        let bytes = [UInt8](data)
        var out = [UInt8]()
        out.reserveCapacity((bytes.count * 4 + 2) / 3)
        var i = 0
        while i + 3 <= bytes.count {
            let n = UInt32(bytes[i]) << 16 | UInt32(bytes[i + 1]) << 8 | UInt32(bytes[i + 2])
            out += [alphabet[Int(n >> 18 & 63)], alphabet[Int(n >> 12 & 63)], alphabet[Int(n >> 6 & 63)], alphabet[Int(n & 63)]]
            i += 3
        }
        switch bytes.count - i {
        case 1:
            let n = UInt32(bytes[i]) << 16
            out += [alphabet[Int(n >> 18 & 63)], alphabet[Int(n >> 12 & 63)]]
        case 2:
            let n = UInt32(bytes[i]) << 16 | UInt32(bytes[i + 1]) << 8
            out += [alphabet[Int(n >> 18 & 63)], alphabet[Int(n >> 12 & 63)], alphabet[Int(n >> 6 & 63)]]
        default:
            break
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Strict decode; nil on any non-canonical input.
    public static func decode(_ s: String) -> Data? {
        let chars = Array(s.utf8)
        if chars.count % 4 == 1 { return nil }
        var vals = [UInt8]()
        vals.reserveCapacity(chars.count)
        for c in chars {
            let v = lookup[Int(c)]
            if v < 0 { return nil }
            vals.append(UInt8(v))
        }
        var out = Data(capacity: chars.count * 3 / 4)
        var i = 0
        while i + 4 <= vals.count {
            let n = UInt32(vals[i]) << 18 | UInt32(vals[i + 1]) << 12 | UInt32(vals[i + 2]) << 6 | UInt32(vals[i + 3])
            out.append(UInt8(n >> 16 & 255))
            out.append(UInt8(n >> 8 & 255))
            out.append(UInt8(n & 255))
            i += 4
        }
        switch vals.count - i {
        case 2:
            let (a, b) = (vals[i], vals[i + 1])
            if b & 0x0F != 0 { return nil }
            out.append(a << 2 | b >> 4)
        case 3:
            let (a, b, c) = (vals[i], vals[i + 1], vals[i + 2])
            if c & 0x03 != 0 { return nil }
            out.append(a << 2 | b >> 4)
            out.append((b & 0x0F) << 4 | c >> 2)
        default:
            break
        }
        return out
    }

    /// Strict decode of exactly `length` bytes.
    public static func decode(_ s: String, length: Int) -> Data? {
        guard let d = decode(s), d.count == length else { return nil }
        return d
    }

    /// Strict decode of a JSON string value of exactly `length` bytes.
    public static func decode(_ v: JSON?, length: Int) -> Data? {
        guard let s = v?.stringValue else { return nil }
        return decode(s, length: length)
    }
}
