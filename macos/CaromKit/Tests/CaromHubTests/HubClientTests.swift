import CaromProtocol
import Foundation
import Testing
import os

@testable import CaromHub

/// A URLProtocol stub: records every request and answers with the installed handler.
final class StubProtocol: URLProtocol {
    struct Recorded: Sendable {
        let method: String
        let url: String
        let headers: [String: String]
        let body: Data?

        var json: JSON? { body.flatMap { try? JSON.parse($0) } }
    }

    enum Reply: Sendable {
        case status(Int, String)
        /// An answer with extra response headers (for example `Date`).
        case statusWithHeaders(Int, String, [String: String])
        /// A binary answer (the blob routes).
        case bytes(Int, Data)
        case failure(URLError.Code)
    }

    typealias Handler = @Sendable (Recorded) -> Reply

    private static let state = OSAllocatedUnfairLock<(handler: Handler?, requests: [Recorded])>(
        initialState: (nil, []))

    static func install(_ handler: @escaping Handler) {
        state.withLock { $0 = (handler, []) }
    }

    static var requests: [Recorded] { state.withLock { $0.requests } }

    static func session() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return URLSession(configuration: config)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var body = request.httpBody
        if body == nil, let stream = request.httpBodyStream {
            stream.open()
            var data = Data()
            var buf = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(&buf, maxLength: buf.count)
                if n <= 0 { break }
                data.append(buf, count: n)
            }
            stream.close()
            body = data
        }
        let rec = Recorded(method: request.httpMethod ?? "GET", url: request.url?.absoluteString ?? "",
                           headers: request.allHTTPHeaderFields ?? [:], body: body)
        let handler = Self.state.withLock { s -> Handler? in
            s.requests.append(rec)
            return s.handler
        }
        let reply = handler?(rec) ?? .status(500, "")
        switch reply {
        case .status(let code, let text), .statusWithHeaders(let code, let text, _):
            var headers = ["Content-Type": "application/json"]
            if case .statusWithHeaders(_, _, let extra) = reply { headers.merge(extra) { _, new in new } }
            let resp = HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: "HTTP/1.1",
                                       headerFields: headers)!
            client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(text.utf8))
            client?.urlProtocolDidFinishLoading(self)
        case .bytes(let code, let data):
            let resp = HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/octet-stream"])!
            client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        case .failure(let code):
            client?.urlProtocol(self, didFailWithError: URLError(code))
        }
    }

    override func stopLoading() {}
}

/// A token whose device id is derived from a real key, as the Hub mints them.
func sampleCredentials() -> (id: String, token: String) {
    let identity = DeviceIdentity(signSk: Data(repeating: 1, count: 32), kxSk: Data(repeating: 2, count: 32))
    let token = Token.make(tokenKey: KDF.tokenKey(setupToken: String(repeating: "s", count: 32)),
                           deviceId: identity.deviceId, rand: Data(repeating: 3, count: 32))
    return (identity.deviceId, token)
}

@Suite("HubClient over a URLProtocol stub", .serialized)
struct HubClientTests {
    let base = URL(string: "https://hub.example.com/carom/")!

    func client(token: String? = "ct1.token") -> HubClient {
        HubClient(baseURL: base, token: token, session: StubProtocol.session())
    }

    @Test func infoIsUnauthenticatedAndParsed() async throws {
        StubProtocol.install { _ in
            .status(200, #"{"name":"carom-hub","protocol":1,"version":"0.1.0","setup_done":true,"jurisdiction":null}"#)
        }
        let info = try await client().info()
        #expect(info == HubInfo(name: "carom-hub", protocolVersion: 1, version: "0.1.0", setupDone: true,
                                jurisdiction: nil))
        let req = try #require(StubProtocol.requests.first)
        #expect(req.method == "GET")
        #expect(req.url == "https://hub.example.com/carom/v1/info")
        #expect(req.headers["Authorization"] == nil)
    }

    @Test func setupSendsKeysAndReturnsCredentials() async throws {
        let creds = sampleCredentials()
        StubProtocol.install { _ in .status(200, #"{"device_id":"\#(creds.id)","token":"\#(creds.token)"}"#) }
        let signPub = Data(repeating: 9, count: 32)
        let kxPub = Data(repeating: 8, count: 32)
        let got = try await client(token: nil).setup(setupToken: "setup-token", signPub: signPub, kxPub: kxPub)
        #expect(got == HubCredentials(deviceId: creds.id, token: creds.token))
        let req = try #require(StubProtocol.requests.first)
        #expect(req.method == "POST" && req.url.hasSuffix("/carom/v1/setup"))
        #expect(req.headers["Content-Type"] == "application/json")
        #expect(req.json == ["setup_token": "setup-token", "sign_pub": .string(B64.encode(signPub)),
                             "kx_pub": .string(B64.encode(kxPub))])
    }

    @Test func credentialsMustMatchTheToken() async throws {
        let creds = sampleCredentials()
        StubProtocol.install { _ in .status(200, #"{"device_id":"dev_AAAAAAAAAAAAAAAAAAAAAA","token":"\#(creds.token)"}"#) }
        await #expect(throws: HubError.invalidResponse("credentials")) {
            try await client(token: nil).pairAccept(PairAcceptBody(pairId: "AAECAwQFBgcICQoLDA0ODw", signPub: "",
                                                                   kxPub: "", mac: "", pop: ""))
        }
    }

    @Test func authenticatedRoutesSendTheBearerToken() async throws {
        StubProtocol.install { _ in .status(200, #"{"devices":[{"id":"dev_x","sign_pub":"a","kx_pub":"b","created_at":1,"last_seen":2}]}"#) }
        let devices = try await client(token: "ct1.abc").devices()
        #expect(devices == [DeviceListing(id: "dev_x", signPub: "a", kxPub: "b", createdAt: 1, lastSeen: 2,
                                          record: nil)])
        #expect(StubProtocol.requests.first?.headers["Authorization"] == "Bearer ct1.abc")
    }

    @Test func missingTokenFailsWithoutARequest() async throws {
        StubProtocol.install { _ in .status(200, "{}") }
        await #expect(throws: HubError.missingToken) { try await client(token: nil).devices() }
        #expect(StubProtocol.requests.isEmpty)
    }

    @Test("every SPEC §6 route uses the right method, path and body")
    func routeTable() async throws {
        StubProtocol.install { rec in
            let path = URL(string: rec.url)!.path
            switch (rec.method, path) {
            case ("GET", "/carom/v1/keyring"): return .status(200, #"{"versions":[]}"#)
            case ("POST", "/carom/v1/pair/offer"):
                return .status(200, #"{"pair_id":"AAECAwQFBgcICQoLDA0ODw","expires_at":5}"#)
            case ("GET", "/carom/v1/pair/AAECAwQFBgcICQoLDA0ODw"): return .status(200, #"{"status":"waiting"}"#)
            case ("GET", "/carom/v1/pair/AAECAwQFBgcICQoLDA0ODw/welcome"):
                return .status(200, #"{"box":{"epk":"e","ct":"c"}}"#)
            case ("POST", "/carom/v1/messages"): return .status(200, #"{"seq":7,"duplicate":false}"#)
            case ("GET", "/carom/v1/messages"):
                return .status(200, #"{"messages":[{"seq":3,"envelope":{"v":1}}],"more":true}"#)
            default: return .status(200, "{}")
            }
        }
        let c = client()
        let box = Box(epk: "e", ct: "c")
        let version = KeyringVersion(kv: 2, by: "dev_a", boxes: ["dev_a": box], sig: "s")
        let sealed = SealedRecord(id: "dev_a", updated: 1, kv: 1, salt: "x", ct: "y", sig: "z")
        let env = Envelope(msgId: "m", from: "dev_a", to: ["dev_b"], collapse: nil, ttlS: 60, kv: 1, salt: "s",
                           ct: "c", sig: "g")

        try await c.putKeyring(version)
        #expect(try await c.keyring(have: 3) == [])
        #expect(try await c.pairOffer() == PairOffer(pairId: "AAECAwQFBgcICQoLDA0ODw", expiresAt: 5))
        #expect(try await c.pairStatus(pairId: "AAECAwQFBgcICQoLDA0ODw") == PairStatus(status: .waiting, candidate: nil))
        try await c.pairWelcome(pairId: "AAECAwQFBgcICQoLDA0ODw", box: box)
        try await c.pairReject(pairId: "AAECAwQFBgcICQoLDA0ODw")
        #expect(try await c.pairWelcomeFetch(pairId: "AAECAwQFBgcICQoLDA0ODw") == box.json)
        try await c.putRecord(sealed)
        try await c.revoke(deviceId: "self")
        #expect(try await c.postMessage(env) == PostResult(seq: 7, duplicate: false, superseded: false))
        #expect(try await c.fetchMessages(limit: 500)
                == MessagePage(messages: [InboundMessage(seq: 3, envelope: ["v": 1])], more: true))
        try await c.ack(upTo: 3)

        let seen = StubProtocol.requests.map { "\($0.method) \(URL(string: $0.url)!.path)\(URL(string: $0.url)!.query.map { "?" + $0 } ?? "")" }
        #expect(seen == [
            "PUT /carom/v1/keyring",
            "GET /carom/v1/keyring?have=3",
            "POST /carom/v1/pair/offer",
            "GET /carom/v1/pair/AAECAwQFBgcICQoLDA0ODw",
            "POST /carom/v1/pair/AAECAwQFBgcICQoLDA0ODw/welcome",
            "POST /carom/v1/pair/AAECAwQFBgcICQoLDA0ODw/reject",
            "GET /carom/v1/pair/AAECAwQFBgcICQoLDA0ODw/welcome",
            "PUT /carom/v1/devices/self/record",
            "DELETE /carom/v1/devices/self",
            "POST /carom/v1/messages",
            "GET /carom/v1/messages?limit=200",
            "POST /carom/v1/acks",
        ])
        let bodies = StubProtocol.requests.map(\.json)
        #expect(bodies[0] == version.json)
        #expect(bodies[4] == ["box": box.json])
        #expect(bodies[7] == ["record": sealed.json])
        #expect(bodies[9] == ["envelope": env.json])
        #expect(bodies[11] == ["up_to": 3])
    }

    @Test("SPEC §6: POST /v1/messages reports the recipients that were online")
    func postResultCarriesOnline() async throws {
        let env = Envelope(msgId: "m", from: "dev_a", to: ["dev_b"], collapse: nil, ttlS: 60, kv: 1, salt: "s",
                           ct: "c", sig: "g")
        StubProtocol.install { _ in .status(200, #"{"seq":7,"duplicate":false,"online":["dev_b","dev_c"]}"#) }
        #expect(try await client().postMessage(env)
                == PostResult(seq: 7, duplicate: false, superseded: false, online: ["dev_b", "dev_c"]))
        // A Hub that predates V2 sends no `online`.
        StubProtocol.install { _ in .status(200, #"{"seq":8,"duplicate":false}"#) }
        #expect(try await client().postMessage(env).online == [])
    }

    @Test("SPEC §6: PUT /v1/blobs/:id sends raw bytes with the token and parses the answer")
    func putBlob() async throws {
        StubProtocol.install { _ in .status(201, #"{"id":"abc","size":4,"expires_at":99}"#) }
        let ct = Data([0, 1, 2, 255])
        let info = try await client().putBlob(id: "abc", ct: ct, ttlS: 3_600, msgId: "01a1268a-e800-7614-b735-9b848038782d")
        #expect(info == BlobInfo(id: "abc", size: 4, expiresAt: 99))
        let req = try #require(StubProtocol.requests.first)
        #expect(req.method == "PUT")
        #expect(req.url == "https://hub.example.com/carom/v1/blobs/abc?ttl_s=3600&msg_id=01a1268a-e800-7614-b735-9b848038782d")
        #expect(req.headers["Content-Type"] == "application/octet-stream")
        #expect(req.headers["Authorization"] == "Bearer ct1.token")
        #expect(req.body == ct)

        StubProtocol.install { _ in .status(200, #"{"id":"abc","size":4,"expires_at":99}"#) }
        _ = try await client().putBlob(id: "abc", ct: ct, ttlS: 60, msgId: nil)
        #expect(StubProtocol.requests.first?.url == "https://hub.example.com/carom/v1/blobs/abc?ttl_s=60")

        StubProtocol.install { _ in .status(200, "{}") }
        await #expect(throws: HubError.invalidResponse("blob")) { try await client().putBlob(id: "abc", ct: ct, ttlS: 60, msgId: nil) }
        await #expect(throws: HubError.invalidResponse("invalid path parameter")) {
            try await client().putBlob(id: "a/b", ct: ct, ttlS: 60, msgId: nil)
        }
        await #expect(throws: HubError.missingToken) { try await client(token: nil).putBlob(id: "abc", ct: ct, ttlS: 60, msgId: nil) }
    }

    @Test("a full blob store is 429 quota and is not retried; 413 is not retryable either")
    func blobQuota() async throws {
        StubProtocol.install { _ in .status(429, #"{"error":{"code":"rate_limited","message":"x"},"quota":true}"#) }
        do {
            _ = try await client().putBlob(id: "abc", ct: Data([1]), ttlS: 60, msgId: nil)
            Issue.record("expected an error")
        } catch {
            #expect(error.status == 429 && error.apiError?.quota == true)
            #expect(!error.isRetryable)
        }
        StubProtocol.install { _ in .status(413, #"{"error":{"code":"too_large","message":"x"}}"#) }
        do {
            _ = try await client().putBlob(id: "abc", ct: Data([1]), ttlS: 60, msgId: nil)
            Issue.record("expected an error")
        } catch {
            #expect(error.code == .tooLarge && !error.isRetryable)
        }
    }

    @Test("HEAD /v1/blobs/:id is true on 200 and false on 404; touch adds ttl_s")
    func headBlob() async throws {
        StubProtocol.install { _ in .status(200, "") }
        #expect(try await client().headBlob(id: "abc", touchTtlS: 600))
        #expect(StubProtocol.requests.first?.method == "HEAD")
        #expect(StubProtocol.requests.first?.url == "https://hub.example.com/carom/v1/blobs/abc?touch=1&ttl_s=600")
        StubProtocol.install { _ in .status(404, "") }
        #expect(try await client().headBlob(id: "abc", touchTtlS: nil) == false)
        #expect(StubProtocol.requests.first?.url == "https://hub.example.com/carom/v1/blobs/abc")
        StubProtocol.install { _ in .status(403, #"{"error":{"code":"forbidden","message":"x"}}"#) }
        await #expect(throws: HubError.self) { try await client().headBlob(id: "abc", touchTtlS: nil) }
    }

    @Test("GET /v1/blobs/:id returns the bytes unchanged, nil on 404, and throws on other errors")
    func getBlob() async throws {
        let bytes = Data((0...255).map(UInt8.init))
        StubProtocol.install { _ in .bytes(200, bytes) }
        #expect(try await client().getBlob(id: "abc") == bytes)
        #expect(StubProtocol.requests.first?.method == "GET")
        #expect(StubProtocol.requests.first?.headers["Accept"] == "application/octet-stream")
        StubProtocol.install { _ in .status(404, #"{"error":{"code":"not_found","message":"x"}}"#) }
        #expect(try await client().getBlob(id: "abc") == nil)
        StubProtocol.install { _ in .status(500, "") }
        await #expect(throws: HubError.self) { try await client().getBlob(id: "abc") }
        StubProtocol.install { _ in .failure(.notConnectedToInternet) }
        await #expect(throws: HubError.transport(.notConnectedToInternet)) { try await client().getBlob(id: "abc") }
    }

    @Test func conflictCarriesInactiveIds() async throws {
        StubProtocol.install { _ in
            .status(409, #"{"error":{"code":"conflict","message":"some recipients are not active devices"},"inactive":["dev_b"]}"#)
        }
        let env = Envelope(msgId: "m", from: "dev_a", to: ["dev_b"], collapse: nil, ttlS: 60, kv: 1, salt: "s",
                           ct: "c", sig: "g")
        do {
            _ = try await client().postMessage(env)
            Issue.record("expected an error")
        } catch {
            #expect(error.status == 409)
            #expect(error.code == .conflict)
            #expect(error.apiError?.inactive == ["dev_b"])
            #expect(!error.isRetryable)
        }
    }

    @Test func rateLimitAndServerErrors() async throws {
        StubProtocol.install { _ in .status(429, #"{"error":{"code":"rate_limited","message":"x"},"full":["dev_c"]}"#) }
        do {
            _ = try await client().devices()
            Issue.record("expected an error")
        } catch {
            #expect(error.apiError?.full == ["dev_c"])
            #expect(!error.isRetryable)
        }
        StubProtocol.install { _ in .status(503, #"{"error":{"code":"unavailable","message":"x"}}"#) }
        do {
            _ = try await client().devices()
            Issue.record("expected an error")
        } catch {
            #expect(error.code == .unavailable)
            #expect(error.isRetryable)
        }
        StubProtocol.install { _ in .status(502, "<html>bad gateway</html>") }
        do {
            _ = try await client().devices()
            Issue.record("expected an error")
        } catch {
            #expect(error.status == 502 && error.code == .internalError)
            #expect(error.isRetryable)
        }
    }

    @Test func keyringConflictCarriesTheHubKv() async throws {
        StubProtocol.install { _ in .status(409, #"{"error":{"code":"conflict","message":"x"},"kv":4}"#) }
        do {
            try await client().putKeyring(KeyringVersion(kv: 3, by: "dev_a", boxes: [:], sig: "s"))
            Issue.record("expected an error")
        } catch {
            #expect(error.apiError?.keyringKv == 4)
        }
    }

    @Test func welcomeNotReadyIsNil() async throws {
        StubProtocol.install { _ in .status(404, #"{"error":{"code":"not_found","message":"welcome not ready"}}"#) }
        #expect(try await client().pairWelcomeFetch(pairId: "AAECAwQFBgcICQoLDA0ODw") == nil)
        StubProtocol.install { _ in .status(401, #"{"error":{"code":"unauthorized","message":"x"}}"#) }
        await #expect(throws: HubError.api(HubAPIError(status: 401, code: .unauthorized, message: "x",
                                                       body: ["error": ["code": "unauthorized", "message": "x"]]))) {
            try await client().pairWelcomeFetch(pairId: "AAECAwQFBgcICQoLDA0ODw")
        }
    }

    @Test func transportErrorsAreRetryable() async throws {
        StubProtocol.install { _ in .failure(.notConnectedToInternet) }
        do {
            _ = try await client().info()
            Issue.record("expected an error")
        } catch {
            #expect(error == .transport(.notConnectedToInternet))
            #expect(error.isRetryable)
        }
    }

    @Test func pathParametersAreValidated() async throws {
        StubProtocol.install { _ in .status(200, "{}") }
        await #expect(throws: HubError.self) { try await client().revoke(deviceId: "../v1/reset") }
        #expect(StubProtocol.requests.isEmpty)
    }

    // MARK: Clock check

    /// 2026-10-11 22:34:07 GMT.
    static let hubNowMs = 1_791_758_047_000

    func clockClient(localMs: Int, tracker: inout ClockSkewTracker?) -> HubClient {
        let t = ClockSkewTracker(now: { localMs })
        tracker = t
        return HubClient(baseURL: base, token: "ct1.token", session: StubProtocol.session(), clockSkew: t)
    }

    @Test func dateHeaderFeedsTheClockTracker() async throws {
        StubProtocol.install { _ in
            .statusWithHeaders(200, "{}", ["Date": "Sun, 11 Oct 2026 22:34:07 GMT"])
        }
        var tracker: ClockSkewTracker?
        // The local clock is 10 minutes behind the Hub.
        let c = clockClient(localMs: Self.hubNowMs - 600_000, tracker: &tracker)
        try await c.ack(upTo: 1)
        #expect(tracker?.drift == 600_000)
    }

    @Test func missingOrBrokenDateHeaderIsIgnored() async throws {
        StubProtocol.install { _ in .statusWithHeaders(200, "{}", ["Date": "yesterday"]) }
        var tracker: ClockSkewTracker?
        let c = clockClient(localMs: 0, tracker: &tracker)
        try await c.ack(upTo: 1)
        StubProtocol.install { _ in .status(200, "{}") }
        try await c.ack(upTo: 2)
        #expect(tracker?.drift == nil)
    }

    @Test func clockSkewBadRequestFeedsHubTime() async throws {
        let body = #"{"error":{"code":"bad_request","message":"msg_id time is outside the allowed window; "#
            + #"check the device clock"},"clock_skew":true,"hub_time":1791758047000}"#
        StubProtocol.install { _ in .status(400, body) }
        var tracker: ClockSkewTracker?
        // The local clock is 20 minutes ahead.
        let c = clockClient(localMs: Self.hubNowMs + 1_200_000, tracker: &tracker)
        do {
            _ = try await c.ack(upTo: 1)
            Issue.record("expected an error")
        } catch {
            #expect(error.status == 400)
            #expect(error.apiError?.clockSkew == true)
            #expect(error.apiError?.hubTime == Self.hubNowMs)
            #expect(!error.isRetryable)
        }
        #expect(tracker?.drift == -1_200_000)
    }

    @Test func plainBadRequestDoesNotTouchTheTracker() async throws {
        StubProtocol.install { _ in
            .status(400, #"{"error":{"code":"bad_request","message":"x"},"hub_time":1}"#)
        }
        var tracker: ClockSkewTracker?
        let c = clockClient(localMs: Self.hubNowMs, tracker: &tracker)
        await #expect(throws: HubError.self) { try await c.ack(upTo: 1) }
        #expect(tracker?.drift == nil)
    }

    @Test func urls() {
        let c = HubClient(baseURL: URL(string: "https://hub.example.com/carom/")!)
        #expect(c.url("/v1/info").absoluteString == "https://hub.example.com/carom/v1/info")
        #expect(c.webSocketURL.absoluteString == "wss://hub.example.com/carom/v1/ws")
        let local = HubClient(baseURL: URL(string: "http://localhost:8787")!)
        #expect(local.webSocketURL.absoluteString == "ws://localhost:8787/v1/ws")
    }
}
