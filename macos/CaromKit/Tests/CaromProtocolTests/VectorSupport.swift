import CaromProtocol
import Foundation
import Testing

/// Locates `protocol/vectors` relative to this file: macos/CaromKit/Tests/CaromProtocolTests/ → repo root.
enum Vectors {
    static let directory: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()  // CaromProtocolTests
        .deletingLastPathComponent()  // Tests
        .deletingLastPathComponent()  // CaromKit
        .deletingLastPathComponent()  // macos
        .deletingLastPathComponent()  // repo root
        .appendingPathComponent("protocol/vectors")

    static func allFileNames() throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: directory.path).filter { $0.hasSuffix(".json") }.sorted()
    }

    static func load(_ name: String) throws -> JSON {
        let data = try Data(contentsOf: directory.appendingPathComponent(name))
        return try JSON.parse(data)
    }
}

/// Non-throwing accessors for vector files: a missing or malformed field records a test issue
/// and returns an empty value, so assertions stay one-liners.
extension JSON {
    func str(_ key: String, sourceLocation: SourceLocation = #_sourceLocation) -> String {
        guard let s = self[key]?.stringValue else {
            Issue.record("missing string \(key)", sourceLocation: sourceLocation)
            return ""
        }
        return s
    }

    func hex(_ key: String, sourceLocation: SourceLocation = #_sourceLocation) -> Data {
        guard let d = Data(hex: str(key, sourceLocation: sourceLocation)) else {
            Issue.record("bad hex \(key)", sourceLocation: sourceLocation)
            return Data()
        }
        return d
    }

    func int(_ key: String, sourceLocation: SourceLocation = #_sourceLocation) -> Int {
        guard let n = self[key]?.safeInt else {
            Issue.record("missing int \(key)", sourceLocation: sourceLocation)
            return 0
        }
        return n
    }

    func list(_ key: String, sourceLocation: SourceLocation = #_sourceLocation) -> [JSON] {
        guard let a = self[key]?.arrayValue else {
            Issue.record("missing array \(key)", sourceLocation: sourceLocation)
            return []
        }
        return a
    }

    func obj(_ key: String, sourceLocation: SourceLocation = #_sourceLocation) -> JSON {
        guard let v = self[key], v.objectValue != nil else {
            Issue.record("missing object \(key)", sourceLocation: sourceLocation)
            return .object(JSONObject())
        }
        return v
    }

    /// `{id: b64 sign_pub}` → `[id: bytes]`.
    func pubDirectory(_ key: String) -> [String: Data] {
        var out: [String: Data] = [:]
        for (id, pub) in obj(key).objectValue ?? JSONObject() {
            out[id] = pub.stringValue.flatMap { B64.decode($0) } ?? Data()
        }
        return out
    }
}
