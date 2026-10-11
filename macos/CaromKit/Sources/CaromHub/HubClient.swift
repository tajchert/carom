import CaromProtocol
import Foundation

/// async/await client for the Hub HTTP API (SPEC §6) on `URLSession`.
///
/// The client is a value: create a new one (`withToken`) once a token is known. It never logs
/// request or response bodies.
public struct HubClient: HubAPI {
    public let baseURL: URL
    public let token: String?
    public let session: URLSession
    public let timeout: TimeInterval
    /// Told the Hub's time from every HTTP answer (`Date` header, `hub_time` of a `clock_skew` 400).
    public let clockSkew: ClockSkewTracker?

    public init(baseURL: URL, token: String? = nil, session: URLSession = .shared, timeout: TimeInterval = 30,
                clockSkew: ClockSkewTracker? = nil) {
        self.baseURL = baseURL
        self.token = token
        self.session = session
        self.timeout = timeout
        self.clockSkew = clockSkew
    }

    public func withToken(_ token: String?) -> HubClient {
        HubClient(baseURL: baseURL, token: token, session: session, timeout: timeout, clockSkew: clockSkew)
    }

    /// A factory for `SyncEngine` that shares one session (and one clock tracker).
    public static func factory(session: URLSession = .shared, clockSkew: ClockSkewTracker? = nil) -> HubFactory {
        { url, token in HubClient(baseURL: url, token: token, session: session, clockSkew: clockSkew) }
    }

    // MARK: - Routes

    public func info() async throws(HubError) -> HubInfo {
        let j = try await call("GET", "/v1/info", auth: false)
        guard let name = j["name"]?.stringValue, let proto = j["protocol"]?.safeInt,
              let setupDone = j["setup_done"]?.boolValue
        else { throw .invalidResponse("info") }
        return HubInfo(name: name, protocolVersion: proto, version: j["version"]?.stringValue ?? "",
                       setupDone: setupDone, jurisdiction: j["jurisdiction"]?.stringValue)
    }

    public func setup(setupToken: String, signPub: Data, kxPub: Data) async throws(HubError) -> HubCredentials {
        let body: JSON = ["setup_token": .string(setupToken), "sign_pub": .string(B64.encode(signPub)),
                          "kx_pub": .string(B64.encode(kxPub))]
        return try credentials(try await call("POST", "/v1/setup", body: body, auth: false))
    }

    public func putKeyring(_ version: KeyringVersion) async throws(HubError) {
        _ = try await call("PUT", "/v1/keyring", body: version.json)
    }

    public func keyring(have: Int) async throws(HubError) -> [JSON] {
        let j = try await call("GET", "/v1/keyring?have=\(have)")
        guard let versions = j["versions"]?.arrayValue else { throw .invalidResponse("keyring") }
        return versions
    }

    public func pairOffer() async throws(HubError) -> PairOffer {
        let j = try await call("POST", "/v1/pair/offer", body: .object(JSONObject()))
        guard let id = j["pair_id"]?.stringValue, QR.isValidPairId(id), let exp = j["expires_at"]?.safeInt else {
            throw .invalidResponse("pair offer")
        }
        return PairOffer(pairId: id, expiresAt: exp)
    }

    public func pairStatus(pairId: String) async throws(HubError) -> PairStatus {
        let j = try await call("GET", "/v1/pair/\(try pathParam(pairId))")
        guard let s = j["status"]?.stringValue, let state = PairStatus.State(rawValue: s) else {
            throw .invalidResponse("pair status")
        }
        return PairStatus(status: state, candidate: j["candidate"])
    }

    public func pairAccept(_ body: PairAcceptBody) async throws(HubError) -> HubCredentials {
        try credentials(try await call("POST", "/v1/pair/accept", body: body.json, auth: false))
    }

    public func pairWelcome(pairId: String, box: Box) async throws(HubError) {
        _ = try await call("POST", "/v1/pair/\(try pathParam(pairId))/welcome", body: ["box": box.json])
    }

    public func pairReject(pairId: String) async throws(HubError) {
        _ = try await call("POST", "/v1/pair/\(try pathParam(pairId))/reject", body: .object(JSONObject()))
    }

    public func pairWelcomeFetch(pairId: String) async throws(HubError) -> JSON? {
        let path = "/v1/pair/\(try pathParam(pairId))/welcome"
        let j: JSON
        do throws(HubError) {
            j = try await call("GET", path)
        } catch {
            if error.status == 404 { return nil }
            throw error
        }
        guard let box = j["box"] else { throw .invalidResponse("welcome") }
        return box
    }

    public func devices() async throws(HubError) -> [DeviceListing] {
        let j = try await call("GET", "/v1/devices")
        guard let arr = j["devices"]?.arrayValue else { throw .invalidResponse("devices") }
        var out: [DeviceListing] = []
        for d in arr {
            guard let listing = DeviceListing(json: d) else { throw .invalidResponse("device listing") }
            out.append(listing)
        }
        return out
    }

    public func putRecord(_ record: SealedRecord) async throws(HubError) {
        _ = try await call("PUT", "/v1/devices/self/record", body: ["record": record.json])
    }

    public func revoke(deviceId: String) async throws(HubError) {
        _ = try await call("DELETE", "/v1/devices/\(try pathParam(deviceId))")
    }

    public func postMessage(_ envelope: Envelope) async throws(HubError) -> PostResult {
        let j = try await call("POST", "/v1/messages", body: ["envelope": envelope.json])
        let seq = j["seq"]?.safeInt
        guard seq != nil || j["seq"] == .null else { throw .invalidResponse("post") }
        return PostResult(seq: seq, duplicate: j["duplicate"]?.boolValue ?? false,
                          superseded: j["superseded"]?.boolValue ?? false,
                          online: j["online"]?.arrayValue?.compactMap(\.stringValue) ?? [])
    }

    public func fetchMessages(limit: Int = 100) async throws(HubError) -> MessagePage {
        let j = try await call("GET", "/v1/messages?limit=\(max(1, min(200, limit)))")
        guard let arr = j["messages"]?.arrayValue else { throw .invalidResponse("messages") }
        var out: [InboundMessage] = []
        for m in arr {
            guard let seq = m["seq"]?.safeInt, let env = m["envelope"] else { throw .invalidResponse("message") }
            out.append(InboundMessage(seq: seq, envelope: env))
        }
        return MessagePage(messages: out, more: j["more"]?.boolValue ?? false)
    }

    public func ack(upTo: Int) async throws(HubError) {
        _ = try await call("POST", "/v1/acks", body: ["up_to": JSON(upTo)])
    }

    public func putBlob(id: String, ct: Data, ttlS: Int, msgId: String? = nil) async throws(HubError) -> BlobInfo {
        var path = "/v1/blobs/\(try pathParam(id))?ttl_s=\(ttlS)"
        if let msgId { path += "&msg_id=\(try pathParam(msgId))" }
        let data = try await raw("PUT", path, body: ct)
        guard let j = try? JSON.parse(data), let rid = j["id"]?.stringValue, let size = j["size"]?.safeInt,
              let exp = j["expires_at"]?.safeInt
        else { throw .invalidResponse("blob") }
        return BlobInfo(id: rid, size: size, expiresAt: exp)
    }

    public func headBlob(id: String, touchTtlS: Int? = nil) async throws(HubError) -> Bool {
        var path = "/v1/blobs/\(try pathParam(id))"
        if let touchTtlS { path += "?touch=1&ttl_s=\(touchTtlS)" }
        do throws(HubError) {
            _ = try await raw("HEAD", path)
            return true
        } catch {
            if error.status == 404 { return false }
            throw error
        }
    }

    public func getBlob(id: String) async throws(HubError) -> Data? {
        do throws(HubError) {
            return try await raw("GET", "/v1/blobs/\(try pathParam(id))", accept: "application/octet-stream")
        } catch {
            if error.status == 404 { return nil }
            throw error
        }
    }

    // MARK: - Plumbing

    /// `https://host/base` + `/v1/...`, keeping any base path.
    public func url(_ path: String) -> URL {
        var base = baseURL.absoluteString
        while base.hasSuffix("/") { base.removeLast() }
        return URL(string: base + path)!
    }

    /// The WebSocket URL (`wss:` for `https:`, `ws:` for `http:`).
    public var webSocketURL: URL {
        var comps = URLComponents(url: url("/v1/ws"), resolvingAgainstBaseURL: false)!
        comps.scheme = comps.scheme == "http" ? "ws" : "wss"
        return comps.url!
    }

    private func pathParam(_ s: String) throws(HubError) -> String {
        let ok = !s.isEmpty && s.utf8.count <= 64 && s.utf8.allSatisfy {
            (0x30...0x39).contains($0) || (0x41...0x5A).contains($0) || (0x61...0x7A).contains($0) || $0 == 0x2D
                || $0 == 0x5F
        }
        guard ok else { throw .invalidResponse("invalid path parameter") }
        return s
    }

    private func credentials(_ j: JSON) throws(HubError) -> HubCredentials {
        guard let id = j["device_id"]?.stringValue, DeviceID.isValid(id), let token = j["token"]?.stringValue,
              Token.parse(token)?.deviceId == id
        else { throw .invalidResponse("credentials") }
        return HubCredentials(deviceId: id, token: token)
    }

    func call(_ method: String, _ path: String, body: JSON? = nil, auth: Bool = true) async throws(HubError) -> JSON {
        var req = try request(method, path, auth: auth, accept: "application/json")
        if let body {
            req.httpBody = body.serializedData()
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, http) = try await perform(req)
        let json: JSON? = data.isEmpty ? .object(JSONObject()) : try? JSON.parse(data)
        guard (200..<300).contains(http.statusCode) else { throw failure(http, json) }
        guard let json, json.objectValue != nil else { throw .invalidResponse("body is not a JSON object") }
        return json
    }

    /// An authenticated request with a raw-bytes body or answer (the blob routes). Returns the
    /// response body of a 2xx answer; an error answer is parsed like `call` does.
    func raw(_ method: String, _ path: String, body: Data? = nil, accept: String = "application/json")
        async throws(HubError) -> Data
    {
        var req = try request(method, path, auth: true, accept: accept)
        if let body {
            req.httpBody = body
            req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        }
        let (data, http) = try await perform(req)
        guard (200..<300).contains(http.statusCode) else {
            throw failure(http, data.isEmpty ? .object(JSONObject()) : try? JSON.parse(data))
        }
        return data
    }

    private func request(_ method: String, _ path: String, auth: Bool, accept: String) throws(HubError) -> URLRequest {
        var req = URLRequest(url: url(path), cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        req.httpMethod = method
        req.setValue(accept, forHTTPHeaderField: "Accept")
        if auth {
            guard let token else { throw .missingToken }
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return req
    }

    private func perform(_ req: URLRequest) async throws(HubError) -> (Data, HTTPURLResponse) {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: req)
        } catch let e as URLError {
            throw .transport(e.code)
        } catch {
            throw .transport(.unknown)
        }
        guard let http = response as? HTTPURLResponse else { throw .invalidResponse("not HTTP") }
        clockSkew?.observe(dateHeader: http.value(forHTTPHeaderField: "Date"))
        return (data, http)
    }

    private func failure(_ http: HTTPURLResponse, _ json: JSON?) -> HubError {
        let err = json?["error"]
        if http.statusCode == 400, json?["clock_skew"]?.boolValue == true, let hubTime = json?["hub_time"]?.safeInt {
            clockSkew?.observe(hubTimeMs: hubTime)
        }
        return .api(HubAPIError(
            status: http.statusCode,
            code: HubErrorCode(err?["code"]?.stringValue ?? Self.defaultCode(http.statusCode)),
            message: err?["message"]?.stringValue ?? "", body: json ?? .null))
    }

    static func defaultCode(_ status: Int) -> String {
        switch status {
        case 400: "bad_request"
        case 401: "unauthorized"
        case 403: "forbidden"
        case 404: "not_found"
        case 409: "conflict"
        case 410: "gone"
        case 413: "too_large"
        case 429: "rate_limited"
        case 503: "unavailable"
        default: status >= 500 ? "internal" : "http_\(status)"
        }
    }
}
