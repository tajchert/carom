import Foundation

/// A JSON value with JavaScript number semantics (every number is a `Double`) and ordered
/// object keys.
///
/// The protocol needs both properties: strict validation has to mirror the TypeScript reference
/// (`Number.isInteger`, `Number.isSafeInteger`, unknown-field checks), and the payload and record
/// plaintexts are serialized in a fixed field order so they match the vectors byte for byte.
/// `Foundation.JSONSerialization` and `JSONEncoder` give neither guarantee.
public enum JSON: Sendable, Hashable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSON])
    case object(JSONObject)
}

/// A JSON object that keeps its keys in insertion (or source) order. Duplicate keys in parsed
/// input keep their first position and the last value, like `JSON.parse`.
public struct JSONObject: Sendable, Hashable, Sequence, ExpressibleByDictionaryLiteral {
    public private(set) var keys: [String] = []
    private var storage: [String: JSON] = [:]

    public init() {}

    public init(dictionaryLiteral elements: (String, JSON)...) {
        for (k, v) in elements { self[k] = v }
    }

    public init(_ pairs: [(String, JSON)]) {
        for (k, v) in pairs { self[k] = v }
    }

    public subscript(key: String) -> JSON? {
        get { storage[key] }
        set {
            if let newValue {
                if storage.updateValue(newValue, forKey: key) == nil { keys.append(key) }
            } else if storage.removeValue(forKey: key) != nil {
                keys.removeAll { $0 == key }
            }
        }
    }

    public var count: Int { keys.count }
    public var isEmpty: Bool { keys.isEmpty }

    public func contains(_ key: String) -> Bool { storage[key] != nil }

    public func makeIterator() -> AnyIterator<(key: String, value: JSON)> {
        var i = 0
        return AnyIterator {
            guard i < keys.count else { return nil }
            defer { i += 1 }
            return (keys[i], storage[keys[i]]!)
        }
    }

    // Equality and hashing ignore key order, like comparing two parsed JS objects deeply.
    public static func == (a: JSONObject, b: JSONObject) -> Bool { a.storage == b.storage }
    public func hash(into hasher: inout Hasher) { hasher.combine(storage) }
}

// MARK: - Accessors

extension JSON {
    public subscript(key: String) -> JSON? {
        if case .object(let o) = self { return o[key] }
        return nil
    }

    public var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    public var boolValue: Bool? {
        if case .bool(let b) = self { return b }
        return nil
    }

    public var numberValue: Double? {
        if case .number(let n) = self { return n }
        return nil
    }

    public var arrayValue: [JSON]? {
        if case .array(let a) = self { return a }
        return nil
    }

    public var objectValue: JSONObject? {
        if case .object(let o) = self { return o }
        return nil
    }

    public var isNull: Bool { self == .null }

    /// `Number.isInteger`: a finite number without a fractional part.
    public var isInteger: Bool {
        guard case .number(let n) = self else { return false }
        return n.isFinite && n.rounded(.towardZero) == n
    }

    /// `Number.isSafeInteger(n) ? n : nil`.
    public var safeInt: Int? {
        guard case .number(let n) = self, n.isFinite, n.rounded(.towardZero) == n,
              abs(n) <= JSON.maxSafeInteger else { return nil }
        return Int(n)
    }

    /// An integer in `range`, also accepting a JS-integral double such as `5.0`.
    public func int(in range: ClosedRange<Int>) -> Int? {
        guard isInteger, case .number(let n) = self, n >= Double(range.lowerBound), n <= Double(range.upperBound)
        else { return nil }
        return Int(n)
    }

    public static let maxSafeInteger: Double = 9_007_199_254_740_991

    public init(_ value: Int) { self = .number(Double(value)) }
    public init(_ value: String) { self = .string(value) }
    public init(_ value: Bool) { self = .bool(value) }
    public init(_ values: [String]) { self = .array(values.map(JSON.string)) }
}

extension JSON: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByBooleanLiteral,
    ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral, ExpressibleByNilLiteral
{
    public init(stringLiteral value: String) { self = .string(value) }
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(arrayLiteral elements: JSON...) { self = .array(elements) }
    public init(dictionaryLiteral elements: (String, JSON)...) { self = .object(JSONObject(elements)) }
    public init(nilLiteral: ()) { self = .null }
}

// MARK: - Parsing

public struct JSONParseError: Error, Sendable, Equatable {
    public let offset: Int
    public let message: String
}

extension JSON {
    /// Strict RFC 8259 parse of UTF-8 bytes. Invalid UTF-8 is rejected. Surrounding JSON
    /// whitespace (space, tab, CR, LF) is allowed, so padded payloads parse.
    public static func parse(_ data: Data) throws(JSONParseError) -> JSON {
        guard String(data: data, encoding: .utf8) != nil else {
            throw JSONParseError(offset: 0, message: "invalid UTF-8")
        }
        var parser = Parser(bytes: [UInt8](data))
        return try parser.parseDocument()
    }

    public static func parse(_ text: String) throws(JSONParseError) -> JSON {
        var parser = Parser(bytes: Array(text.utf8))
        return try parser.parseDocument()
    }

    private struct Parser {
        let bytes: [UInt8]
        var i = 0
        var depth = 0

        init(bytes: [UInt8]) { self.bytes = bytes }

        func fail(_ message: String) -> JSONParseError { JSONParseError(offset: i, message: message) }

        mutating func parseDocument() throws(JSONParseError) -> JSON {
            skipWhitespace()
            let value = try parseValue()
            skipWhitespace()
            guard i == bytes.count else { throw fail("trailing characters") }
            return value
        }

        mutating func skipWhitespace() {
            while i < bytes.count, [0x20, 0x09, 0x0A, 0x0D].contains(bytes[i]) { i += 1 }
        }

        mutating func expect(_ literal: String, _ value: JSON) throws(JSONParseError) -> JSON {
            let lit = Array(literal.utf8)
            guard i + lit.count <= bytes.count, Array(bytes[i..<i + lit.count]) == lit else {
                throw fail("invalid literal")
            }
            i += lit.count
            return value
        }

        mutating func parseValue() throws(JSONParseError) -> JSON {
            guard i < bytes.count else { throw fail("unexpected end") }
            switch bytes[i] {
            case UInt8(ascii: "{"): return try parseObject()
            case UInt8(ascii: "["): return try parseArray()
            case UInt8(ascii: "\""): return .string(try parseString())
            case UInt8(ascii: "t"): return try expect("true", .bool(true))
            case UInt8(ascii: "f"): return try expect("false", .bool(false))
            case UInt8(ascii: "n"): return try expect("null", .null)
            default: return .number(try parseNumber())
            }
        }

        mutating func enter() throws(JSONParseError) {
            depth += 1
            if depth > 256 { throw fail("nesting too deep") }
        }

        mutating func parseObject() throws(JSONParseError) -> JSON {
            try enter()
            defer { depth -= 1 }
            i += 1
            var obj = JSONObject()
            skipWhitespace()
            if i < bytes.count, bytes[i] == UInt8(ascii: "}") {
                i += 1
                return .object(obj)
            }
            while true {
                skipWhitespace()
                guard i < bytes.count, bytes[i] == UInt8(ascii: "\"") else { throw fail("expected key") }
                let key = try parseString()
                skipWhitespace()
                guard i < bytes.count, bytes[i] == UInt8(ascii: ":") else { throw fail("expected ':'") }
                i += 1
                skipWhitespace()
                obj[key] = try parseValue()
                skipWhitespace()
                guard i < bytes.count else { throw fail("unterminated object") }
                if bytes[i] == UInt8(ascii: ",") { i += 1; continue }
                if bytes[i] == UInt8(ascii: "}") { i += 1; return .object(obj) }
                throw fail("expected ',' or '}'")
            }
        }

        mutating func parseArray() throws(JSONParseError) -> JSON {
            try enter()
            defer { depth -= 1 }
            i += 1
            var arr: [JSON] = []
            skipWhitespace()
            if i < bytes.count, bytes[i] == UInt8(ascii: "]") {
                i += 1
                return .array(arr)
            }
            while true {
                skipWhitespace()
                arr.append(try parseValue())
                skipWhitespace()
                guard i < bytes.count else { throw fail("unterminated array") }
                if bytes[i] == UInt8(ascii: ",") { i += 1; continue }
                if bytes[i] == UInt8(ascii: "]") { i += 1; return .array(arr) }
                throw fail("expected ',' or ']'")
            }
        }

        mutating func hex4() throws(JSONParseError) -> UInt16 {
            guard i + 4 <= bytes.count else { throw fail("truncated \\u escape") }
            var v: UInt16 = 0
            for _ in 0..<4 {
                let c = bytes[i]
                let d: UInt16
                switch c {
                case 0x30...0x39: d = UInt16(c - 0x30)
                case 0x41...0x46: d = UInt16(c - 0x41 + 10)
                case 0x61...0x66: d = UInt16(c - 0x61 + 10)
                default: throw fail("bad \\u escape")
                }
                v = v << 4 | d
                i += 1
            }
            return v
        }

        mutating func parseString() throws(JSONParseError) -> String {
            i += 1  // opening quote
            var out: [UInt8] = []
            while true {
                guard i < bytes.count else { throw fail("unterminated string") }
                let c = bytes[i]
                if c == UInt8(ascii: "\"") {
                    i += 1
                    return String(decoding: out, as: UTF8.self)
                }
                if c < 0x20 { throw fail("control character in string") }
                if c != UInt8(ascii: "\\") {
                    out.append(c)
                    i += 1
                    continue
                }
                i += 1
                guard i < bytes.count else { throw fail("unterminated escape") }
                let e = bytes[i]
                i += 1
                switch e {
                case UInt8(ascii: "\""): out.append(0x22)
                case UInt8(ascii: "\\"): out.append(0x5C)
                case UInt8(ascii: "/"): out.append(0x2F)
                case UInt8(ascii: "b"): out.append(0x08)
                case UInt8(ascii: "f"): out.append(0x0C)
                case UInt8(ascii: "n"): out.append(0x0A)
                case UInt8(ascii: "r"): out.append(0x0D)
                case UInt8(ascii: "t"): out.append(0x09)
                case UInt8(ascii: "u"):
                    let u = try hex4()
                    var scalar: Unicode.Scalar?
                    if (0xD800...0xDBFF).contains(u), i + 6 <= bytes.count, bytes[i] == UInt8(ascii: "\\"),
                       bytes[i + 1] == UInt8(ascii: "u")
                    {
                        let save = i
                        i += 2
                        let low = try hex4()
                        if (0xDC00...0xDFFF).contains(low) {
                            let v = 0x10000 + ((UInt32(u) - 0xD800) << 10) + (UInt32(low) - 0xDC00)
                            scalar = Unicode.Scalar(v)
                        } else {
                            i = save
                        }
                    } else {
                        scalar = Unicode.Scalar(u)
                    }
                    // A lone surrogate cannot be represented in a Swift String: use U+FFFD.
                    out.append(contentsOf: Array(String(Character(scalar ?? "\u{FFFD}")).utf8))
                default:
                    throw fail("bad escape")
                }
            }
        }

        mutating func parseNumber() throws(JSONParseError) -> Double {
            let start = i
            if i < bytes.count, bytes[i] == UInt8(ascii: "-") { i += 1 }
            guard i < bytes.count else { throw fail("bad number") }
            if bytes[i] == UInt8(ascii: "0") {
                i += 1
            } else if (0x31...0x39).contains(bytes[i]) {
                while i < bytes.count, (0x30...0x39).contains(bytes[i]) { i += 1 }
            } else {
                throw fail("unexpected character")
            }
            if i < bytes.count, bytes[i] == UInt8(ascii: ".") {
                i += 1
                let digits = i
                while i < bytes.count, (0x30...0x39).contains(bytes[i]) { i += 1 }
                if i == digits { throw fail("bad fraction") }
            }
            if i < bytes.count, bytes[i] == UInt8(ascii: "e") || bytes[i] == UInt8(ascii: "E") {
                i += 1
                if i < bytes.count, bytes[i] == UInt8(ascii: "+") || bytes[i] == UInt8(ascii: "-") { i += 1 }
                let digits = i
                while i < bytes.count, (0x30...0x39).contains(bytes[i]) { i += 1 }
                if i == digits { throw fail("bad exponent") }
            }
            let text = String(decoding: bytes[start..<i], as: UTF8.self)
            guard let value = Double(text) else { throw fail("bad number") }
            return value
        }
    }
}

// MARK: - Serialization

extension JSON {
    /// Compact serialization identical to `JSON.stringify` for the values the protocol uses:
    /// object keys in order, non-ASCII characters left as is, `/` not escaped.
    public func serialized() -> String {
        var out = ""
        write(to: &out)
        return out
    }

    public func serializedData() -> Data { Data(serialized().utf8) }

    private func write(to out: inout String) {
        switch self {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .number(let n): out += JSON.formatNumber(n)
        case .string(let s): JSON.writeString(s, to: &out)
        case .array(let a):
            out += "["
            for (idx, v) in a.enumerated() {
                if idx > 0 { out += "," }
                v.write(to: &out)
            }
            out += "]"
        case .object(let o):
            out += "{"
            var first = true
            for (k, v) in o {
                if !first { out += "," }
                first = false
                JSON.writeString(k, to: &out)
                out += ":"
                v.write(to: &out)
            }
            out += "}"
        }
    }

    static func formatNumber(_ n: Double) -> String {
        if !n.isFinite { return "null" }
        if n.rounded(.towardZero) == n, abs(n) < 1e21 {
            if abs(n) <= maxSafeInteger { return String(Int64(n)) }
            return String(format: "%.0f", n)
        }
        return "\(n)"
    }

    static func writeString(_ s: String, to out: inout String) {
        out += "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
    }
}

// MARK: - Codable bridge (used for local persistence, where key order does not matter)

extension JSON: Codable {
    public init(from decoder: any Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() {
            self = .null
        } else if let b = try? c.decode(Bool.self) {
            self = .bool(b)
        } else if let n = try? c.decode(Double.self) {
            self = .number(n)
        } else if let s = try? c.decode(String.self) {
            self = .string(s)
        } else if let a = try? c.decode([JSON].self) {
            self = .array(a)
        } else if let o = try? c.decode([String: JSON].self) {
            self = .object(JSONObject(o.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }))
        } else {
            throw DecodingError.dataCorruptedError(in: c, debugDescription: "not a JSON value")
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .number(let n): try c.encode(n)
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o):
            var dict: [String: JSON] = [:]
            for (k, v) in o { dict[k] = v }
            try c.encode(dict)
        }
    }
}
