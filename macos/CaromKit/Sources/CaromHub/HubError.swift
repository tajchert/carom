import CaromProtocol
import Foundation

/// Error codes from SPEC §6.
public enum HubErrorCode: Sendable, Equatable, Hashable {
    case badRequest, unauthorized, forbidden, notFound, conflict, gone, tooLarge, rateLimited, unavailable, internalError
    case other(String)

    public init(_ code: String) {
        switch code {
        case "bad_request": self = .badRequest
        case "unauthorized": self = .unauthorized
        case "forbidden": self = .forbidden
        case "not_found": self = .notFound
        case "conflict": self = .conflict
        case "gone": self = .gone
        case "too_large": self = .tooLarge
        case "rate_limited": self = .rateLimited
        case "unavailable": self = .unavailable
        case "internal": self = .internalError
        default: self = .other(code)
        }
    }

    public var rawValue: String {
        switch self {
        case .badRequest: "bad_request"
        case .unauthorized: "unauthorized"
        case .forbidden: "forbidden"
        case .notFound: "not_found"
        case .conflict: "conflict"
        case .gone: "gone"
        case .tooLarge: "too_large"
        case .rateLimited: "rate_limited"
        case .unavailable: "unavailable"
        case .internalError: "internal"
        case .other(let s): s
        }
    }
}

/// An error answer from the Hub: `{"error": {"code", "message"}, ...extra}`.
public struct HubAPIError: Sendable, Equatable {
    public var status: Int
    public var code: HubErrorCode
    public var message: String
    /// The whole response body, for extra fields.
    public var body: JSON

    public init(status: Int, code: HubErrorCode, message: String, body: JSON = .null) {
        self.status = status
        self.code = code
        self.message = message
        self.body = body
    }

    /// `409 {"inactive": [ids]}` on `POST /v1/messages`.
    public var inactive: [String] { body["inactive"]?.arrayValue?.compactMap(\.stringValue) ?? [] }
    /// `429 {"full": [ids]}` on `POST /v1/messages`.
    public var full: [String] { body["full"]?.arrayValue?.compactMap(\.stringValue) ?? [] }
    /// `429 {"quota": true}` on `PUT /v1/blobs/:id`: the Hub's blob storage is full.
    public var quota: Bool { body["quota"]?.boolValue ?? false }
    /// `409 {"kv": n}` on `PUT /v1/keyring`: the Hub's newest keyring version.
    public var keyringKv: Int? { body["kv"]?.safeInt }
}

public enum HubError: Error, Sendable, Equatable {
    /// The request never got an HTTP answer (offline, DNS, TLS, timeout, cancelled).
    case transport(URLError.Code)
    /// The Hub answered with an error status.
    case api(HubAPIError)
    /// The Hub answered 2xx with a body that does not match SPEC §6.
    case invalidResponse(String)
    /// An authenticated route was called without a device token.
    case missingToken

    public var apiError: HubAPIError? {
        if case .api(let e) = self { return e }
        return nil
    }

    public var status: Int? { apiError?.status }
    public var code: HubErrorCode? { apiError?.code }

    /// Worth retrying later with backoff: network errors, 5xx, and 429 without `full` or `quota`.
    public var isRetryable: Bool {
        switch self {
        case .transport: true
        case .api(let e): e.status >= 500 || (e.status == 429 && e.full.isEmpty && !e.quota)
        case .invalidResponse, .missingToken: false
        }
    }
}
