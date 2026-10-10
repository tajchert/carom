import CaromHub
import CaromProtocol
import CaromStore
import Foundation
import os

/// The sync engine from docs/CLIENTS.md: onboarding (setup, join), pairing as inviter,
/// directory refresh and trust, the send pipeline with an outbox, the receive pipeline, rekey on
/// revoke, and revoked handling.
///
/// Usage from the app:
///
///     let engine = SyncEngine(configuration: .init(deviceName: Host.current().localizedName ?? "Mac"),
///                             secrets: KeychainSecretStore(), state: try .applicationSupport(),
///                             hub: HubClient.factory())
///     try await engine.load()
///     await engine.setHandler(myHandler)
///     if await engine.isConfigured { _ = await engine.startRealtime() }
///
/// Errors are `SyncError` or `HubError`.
public actor SyncEngine {
    private let config: SyncConfiguration
    private let vault: SecretVault
    private let stateStore: any StateStore
    private let hubFactory: HubFactory
    private let clock: @Sendable () -> Int
    private let random: RandomSource
    private let log = Logger(subsystem: "app.carom.kit", category: "sync")

    private var handler: (any SyncHandler)?
    private var secrets: DeviceSecrets?
    private var state = SyncState()
    private var generator: UUIDv7Generator
    private var echo = EchoSuppressor()
    private var offers: [String: Offer] = [:]
    private var pendingJoin: PendingJoin?
    private var refreshTask: Task<Void, any Error>?
    private var socket: HubSocket?
    private var socketTask: Task<Void, Never>?
    private let realtime = RealtimeStateBroadcaster()

    private struct Offer {
        let secret: Data
        let expiresAt: Int
        var verified: VerifiedCandidate?
    }

    private struct PendingJoin {
        let hubURL: String
        let pairId: String
        let secret: Data
        let identity: DeviceIdentity
        let token: String
    }

    public init(configuration: SyncConfiguration, secrets: any SecretStore, state: any StateStore,
                hub: @escaping HubFactory, clock: @escaping @Sendable () -> Int = SyncEngine.systemClock,
                random: @escaping RandomSource = systemRandom) {
        config = configuration
        vault = SecretVault(store: secrets)
        stateStore = state
        hubFactory = hub
        self.clock = clock
        self.random = random
        generator = UUIDv7Generator(now: clock, random: random)
    }

    public static let systemClock: @Sendable () -> Int = { Int(Date().timeIntervalSince1970 * 1000) }

    public func setHandler(_ handler: (any SyncHandler)?) { self.handler = handler }

    /// Loads keys and state. Returns whether the device is set up. When state exists but the
    /// keys are gone (reinstall, restore), the state is discarded and onboarding starts again.
    @discardableResult
    public func load() throws -> Bool {
        let loadedSecrets: DeviceSecrets?
        do { loadedSecrets = try vault.load() } catch { throw SyncError.store("\(error)") }
        let loadedState = try stateStore.load()
        if let loadedSecrets, var loadedState, loadedState.deviceId == loadedSecrets.identity.deviceId {
            secrets = loadedSecrets
            loadedState.prune(now: clock())
            state = loadedState
            if let last = state.lastMsgId { generator.resume(after: last) }
            secrets?.pruneKeys(now: clock())
        } else {
            secrets = nil
            state = SyncState()
            if loadedState != nil { try stateStore.wipe() }
        }
        return isConfigured
    }

    // MARK: - Read-only state

    public var isConfigured: Bool { secrets != nil && state.deviceId != nil && state.hubURL != nil }
    public var deviceId: String? { state.deviceId }
    public var deviceName: String { state.deviceName }
    public var hubURL: String? { state.hubURL }
    public var fingerprint: String? { state.deviceId.map(DeviceID.fingerprint) }
    public var currentKv: Int? { secrets?.current?.kv }
    /// Trusted members (excluding this device), sorted by name.
    public var directory: [DirectoryEntry] {
        state.directory.values.sorted { ($0.record.name, $0.record.id) < ($1.record.name, $1.record.id) }
    }
    public var outbox: [OutboxItem] { state.outbox }

    /// State of the realtime WebSocket started by `startRealtime`.
    public nonisolated var realtimeState: RealtimeState { realtime.current }

    /// Yields the current realtime state, then every change. Each call returns an independent
    /// stream; cancel the consuming task to stop observing.
    public nonisolated func realtimeStates() -> AsyncStream<RealtimeState> { realtime.stream() }
    /// App settings persisted with the sync state (the kit does not interpret them).
    public var settings: [String: JSON] { state.settings }

    public func setSetting(_ key: String, _ value: JSON?) {
        state.settings[key] = value
        persist()
    }

    // MARK: - Onboarding: first device

    /// Sets up a new Hub from a `carom://setup` URI (SPEC §8, first device).
    public func setup(uri: String) async throws {
        guard case .setup(let hub, let token)? = QR.parse(uri) else { throw SyncError.invalidURI }
        try await setup(hubURL: hub, setupToken: token)
    }

    public func setup(hubURL: String, setupToken: String) async throws {
        guard !isConfigured else { throw SyncError.alreadyConfigured }
        guard QR.isValidHubURL(hubURL), let url = URL(string: hubURL) else { throw SyncError.invalidURI }
        let identity = DeviceIdentity.generate(random: random)
        let creds = try await hubFactory(url, nil).setup(setupToken: setupToken, signPub: identity.signPub,
                                                          kxPub: identity.kxPub)
        guard creds.deviceId == identity.deviceId else { throw SyncError.credentialsMismatch }
        try install(identity: identity, token: creds.token, hubURL: hubURL,
                    keys: [GroupKey(kv: 1, key: random(Carom.keyLength))])
        try await publishRecord()
        try await refreshDirectory()
    }

    private func install(identity: DeviceIdentity, token: String, hubURL: String, keys: [GroupKey]) throws {
        let newSecrets = DeviceSecrets(identity: identity, token: token, groupKeys: keys)
        var newState = SyncState()
        newState.hubURL = hubURL
        newState.deviceId = identity.deviceId
        newState.deviceName = config.deviceName
        do { try vault.save(newSecrets) } catch { throw SyncError.store("\(error)") }
        secrets = newSecrets
        state = newState
        persist()
    }

    // MARK: - Onboarding: joining through an existing member

    /// Scans a `carom://pair` URI and posts `POST /v1/pair/accept` (SPEC §8 step 2). Show the
    /// ticket's fingerprint, then call `completeJoin()`.
    public func beginJoin(uri: String) async throws -> JoinTicket {
        guard !isConfigured else { throw SyncError.alreadyConfigured }
        guard case .pair(let hub, let pairId, let secret)? = QR.parse(uri), let url = URL(string: hub) else {
            throw SyncError.invalidURI
        }
        let identity = DeviceIdentity.generate(random: random)
        let body = Pairing.buildAccept(pairId: pairId, pairSecret: secret, identity: identity)
        let creds = try await hubFactory(url, nil).pairAccept(body)
        guard creds.deviceId == identity.deviceId else { throw SyncError.credentialsMismatch }
        pendingJoin = PendingJoin(hubURL: hub, pairId: pairId, secret: secret, identity: identity, token: creds.token)
        return JoinTicket(pairId: pairId, deviceId: identity.deviceId)
    }

    /// Polls for the welcome every 2 s, opens it, stores the keys, publishes our record and
    /// fetches the directory (SPEC §8 step 5). Cancel the calling task to give up.
    public func completeJoin() async throws {
        guard let join = pendingJoin, let url = URL(string: join.hubURL) else { throw SyncError.unknownPairing }
        let hub = hubFactory(url, join.token)
        let deadline = ContinuousClock.now + config.pairingTimeout
        var box: JSON?
        while box == nil {
            do {
                box = try await hub.pairWelcomeFetch(pairId: join.pairId)
            } catch {
                if error.status == 401 || error.status == 403 {
                    pendingJoin = nil
                    throw SyncError.pairingRejected
                }
                if !error.isRetryable { throw error }
            }
            if box != nil { break }
            guard ContinuousClock.now < deadline else {
                pendingJoin = nil
                throw SyncError.pairingExpired
            }
            try await Task.sleep(for: config.welcomePollInterval)
        }
        let welcome: Welcome
        switch Pairing.openWelcome(box!, pairId: join.pairId, selfId: join.identity.deviceId,
                                   kxSk: join.identity.kxSk, pairSecret: join.secret) {
        case .success(let w): welcome = w
        case .failure(let e):
            pendingJoin = nil
            throw SyncError.welcomeInvalid(e)
        }
        let now = clock()
        let newest = welcome.keys.map(\.kv).max() ?? 1
        let keys = welcome.groupKeys.map { GroupKey(kv: $0.key, key: $0.value, retiredAt: $0.key == newest ? nil : now) }
            .sorted { $0.kv < $1.kv }
        try install(identity: join.identity, token: join.token, hubURL: join.hubURL, keys: keys)
        pendingJoin = nil
        try await publishRecord()
        try await refreshDirectory()
    }

    /// `beginJoin` then `completeJoin`.
    public func join(uri: String) async throws {
        _ = try await beginJoin(uri: uri)
        try await completeJoin()
    }

    // MARK: - Pairing: the inviting device

    /// `POST /v1/pair/offer` and a fresh pair secret; show `uri` as a QR code for 5 minutes.
    public func createPairingInvite() async throws -> PairingInvite {
        let hub = try requireHub()
        guard let hubURL = state.hubURL else { throw SyncError.notConfigured }
        pruneOffers()
        let offer = try await hub.pairOffer()
        let secret = random(32)
        let uri: String
        do { uri = try QR.buildPairURI(hub: hubURL, pairId: offer.pairId, pairSecret: secret) } catch {
            throw SyncError.invalidURI
        }
        offers[offer.pairId] = Offer(secret: secret, expiresAt: offer.expiresAt)
        // Wipe the pair secret when the pairing expires even if nobody asks again (SPEC §8 step 3).
        let lifetime = max(0, offer.expiresAt - clock())
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(lifetime + 1))
            await self?.expireOffers()
        }
        return PairingInvite(pairId: offer.pairId, uri: uri, expiresAt: offer.expiresAt)
    }

    /// Polls `GET /v1/pair/:id` (the fallback when the WebSocket `pair` frame is missed).
    /// Returns the verified candidate once one has arrived.
    public func pollPairing(pairId: String) async throws -> VerifiedCandidate? {
        pruneOffers()
        guard let offer = offers[pairId] else { throw SyncError.unknownPairing }
        if let verified = offer.verified { return verified }
        let status = try await requireHub().pairStatus(pairId: pairId)
        guard let candidate = status.candidate else { return nil }
        return await candidateArrived(pairId: pairId, candidate: candidate)
    }

    /// Checks a candidate (SPEC §8 step 3). A mismatch rejects the pairing and warns the user.
    @discardableResult
    func candidateArrived(pairId: String, candidate: JSON) async -> VerifiedCandidate? {
        pruneOffers()
        guard var offer = offers[pairId] else { return nil }
        if let verified = offer.verified { return verified }
        switch Pairing.verifyCandidate(pairId: pairId, pairSecret: offer.secret, candidate: candidate) {
        case .success(let c):
            offer.verified = c
            offers[pairId] = offer
            await emit(.pairingCandidate(pairId: pairId, candidate: c))
            return c
        case .failure(let e):
            offers[pairId] = nil
            log.error("pairing candidate rejected: \(e.rawValue, privacy: .public)")
            try? await requireHub().pairReject(pairId: pairId)
            await emit(.pairingRejected(pairId: pairId, reason: e))
            return nil
        }
    }

    /// The user said yes: post the welcome box with every group key we hold (SPEC §8 step 4).
    public func approvePairing(pairId: String) async throws {
        pruneOffers()
        guard let offer = offers[pairId], let candidate = offer.verified else { throw SyncError.unknownPairing }
        guard let secrets, let selfId = state.deviceId else { throw SyncError.notConfigured }
        let welcome = Welcome(keys: secrets.groupKeys.map { WelcomeKey(kv: $0.kv, k: B64.encode($0.key)) },
                              inviter: selfId)
        let box = Pairing.sealWelcome(pairId: pairId, candidateId: candidate.deviceId, candidateKxPub: candidate.kxPub,
                                      pairSecret: offer.secret, welcome: welcome, random: random)
        offers[pairId] = nil  // wipe the pair secret on use
        try await requireHub().pairWelcome(pairId: pairId, box: box)
    }

    /// The user said no.
    public func rejectPairing(pairId: String) async throws {
        offers[pairId] = nil
        try await requireHub().pairReject(pairId: pairId)
    }

    /// Forgets an invite locally (the Hub expires it after 5 minutes).
    public func cancelPairing(pairId: String) { offers[pairId] = nil }

    private func expireOffers() { pruneOffers() }

    private func pruneOffers() {
        let now = clock()
        offers = offers.filter { $0.value.expiresAt > now }
    }

    // MARK: - Directory and trust

    /// `GET /v1/devices`, apply newer keyring versions, open every record (SPEC §5.3) and cache
    /// the trusted set. Concurrent calls share one refresh.
    public func refreshDirectory() async throws {
        if let running = refreshTask { return try await running.value }
        let task = Task { try await self.performRefresh() }
        refreshTask = task
        defer { refreshTask = nil }
        try await task.value
    }

    private func performRefresh() async throws {
        let hub = try requireHub()
        let listing: [DeviceListing]
        do { listing = try await hub.devices() } catch {
            await noteAuthFailure(error)
            throw error
        }
        let applied = try await applyKeyring()
        await learnCurrentKeyringMembers(listing: listing)
        await rebuildDirectory(listing: listing, applied: applied)
    }

    /// SPEC §5.3 transitional trust for a device that got the newest key through a welcome
    /// rather than the keyring: it calls `GET /v1/keyring?have=<newest kv − 1>` so it also holds
    /// the newest version's box list, and verifies that version's signature with the `sign_pub`
    /// of a trusted member. Records still sealed under kv − 1 by devices boxed in it then count.
    private func learnCurrentKeyringMembers(listing: [DeviceListing]) async {
        guard let s = secrets, let current = s.current, current.kv > 1, state.rekeyMembers[current.kv] == nil,
              let selfId = state.deviceId
        else { return }
        let versions: [JSON]
        do { versions = try await requireHub().keyring(have: current.kv - 1) } catch {
            log.info("keyring fetch for transitional trust failed")
            return
        }
        guard let json = versions.first(where: { $0["kv"]?.safeInt == current.kv }),
              let version = KeyringVersion(json: json)
        else { return }
        // Members under the current key: records that open with it (plus this device).
        var trusted: [String: Data] = [selfId: s.identity.signPub]
        for l in listing where l.id != selfId {
            guard let recordJSON = l.record,
                  case .success(let record) = RecordCodec.open(recordJSON, groupKey: current.key, kv: current.kv),
                  record.signPub == l.signPub, let pub = record.signPubBytes
            else { continue }
            trusted[l.id] = pub
        }
        guard let signer = Receiver.trustedSenderKey(trusted, version.by),
              Rekey.verifySignature(version, bySignPub: signer)
        else {
            log.error("keyring version for transitional trust rejected")
            return
        }
        state.rekeyMembers[current.kv] = DeviceID.sorted(version.boxes.keys)
        state.rekeyBy[current.kv] = version.by
        persist()
    }

    /// `GET /v1/keyring?have=kv` and install every newer version we can verify (SPEC §5.5).
    @discardableResult
    public func checkKeyring() async throws -> Int? {
        _ = try await applyKeyring()
        return currentKv
    }

    private func applyKeyring() async throws -> [Rekey.Opened] {
        guard let have = secrets?.current?.kv else { throw SyncError.notConfigured }
        let versions = try await requireHub().keyring(have: have)
        guard var s = secrets, let selfId = state.deviceId else { throw SyncError.notConfigured }
        let sorted = versions.sorted { ($0["kv"]?.safeInt ?? 0) < ($1["kv"]?.safeInt ?? 0) }
        // The writer of version current + 1 must be a member under the current key: a trusted
        // directory member, or a device boxed in the current version. The cached sign_pubs cover
        // writers that have since been revoked (SPEC §5.5 step 1).
        var signers = trustedSignPubs(includingSelf: true)
            .merging(knownSignPubs(state.rekeyMembers[have] ?? [])) { current, _ in current }
            .merging(formerMembers(trustedUnder: have)) { current, _ in current }
        var applied: [Rekey.Opened] = []
        for v in sorted {
            guard let kv = v["kv"]?.safeInt, let current = s.current?.kv, kv == current + 1 else { continue }
            switch Rekey.open(v, selfId: selfId, kxSk: s.kxSk, directory: signers) {
            case .success(let opened):
                s.install(kv: opened.kv, key: opened.groupKey, now: clock())
                state.rekeyMembers[opened.kv] = DeviceID.sorted(opened.members)
                state.rekeyBy[opened.kv] = opened.by
                // The signer of the next version must be a member under this one.
                signers = knownSignPubs(opened.members)
                applied.append(opened)
            case .failure(let e):
                log.error("keyring version rejected: \(e.rawValue, privacy: .public)")
            }
            if applied.last?.kv != kv { break }
        }
        guard !applied.isEmpty else { return [] }
        s.pruneKeys(now: clock())
        do { try vault.save(s) } catch { throw SyncError.store("\(error)") }
        secrets = s
        persist()
        // SPEC §5.5 step 4: republish our record under K'.
        try await publishRecord()
        for o in applied { await emit(.keyRotated(kv: o.kv)) }
        return applied
    }

    private func rebuildDirectory(listing: [DeviceListing], applied: [Rekey.Opened]) async {
        guard let s = secrets, let current = s.current, let selfId = state.deviceId else { return }
        let now = clock()
        let previous = s.groupKeys.first { $0.kv == current.kv - 1 }
        let carriedOver = Set(state.rekeyMembers[current.kv] ?? [])
        var newDirectory: [String: DirectoryEntry] = [:]

        for l in listing where l.id != selfId {
            guard let recordJSON = l.record, let sealed = SealedRecord(json: recordJSON) else { continue }
            let result: Result<DeviceRecord, RecordOpenError>
            if sealed.kv == current.kv {
                result = RecordCodec.open(recordJSON, groupKey: current.key, kv: current.kv,
                                          lastUpdated: state.lastUpdated[l.id])
            } else if sealed.kv == current.kv - 1, let previous, carriedOver.contains(l.id) {
                // Transitional trust: the rekey signed by a member boxed K' for this device,
                // which has not republished yet. Its record under the previous key still counts.
                result = RecordCodec.open(recordJSON, groupKey: previous.key, kv: previous.kv,
                                          lastUpdated: state.lastUpdated[l.id])
            } else {
                continue
            }
            switch result {
            case .success(let record):
                guard record.signPub == l.signPub, record.kxPub == l.kxPub else { continue }
                state.lastUpdated[l.id] = max(state.lastUpdated[l.id] ?? 0, record.updated)
                // Trusted now means a member under the current key (transitional records belong to
                // devices boxed in the current version).
                state.signPubCache[l.id] = CachedSigner(signPub: record.signPub, kv: current.kv)
                newDirectory[l.id] = DirectoryEntry(record: record, kv: sealed.kv, createdAt: l.createdAt,
                                                    lastSeen: l.lastSeen, hasPush: l.hasPush)
            case .failure(.rollback):
                if var existing = state.directory[l.id] {
                    existing.lastSeen = l.lastSeen
                    existing.hasPush = l.hasPush
                    newDirectory[l.id] = existing
                }
            case .failure(let e):
                log.info("record ignored: \(e.rawValue, privacy: .public)")
            }
        }

        // Removal notices (SPEC §5.4).
        let listed = Set(listing.map(\.id))
        for (id, entry) in state.directory where !listed.contains(id) && state.pendingRemovals[id] == nil {
            state.pendingRemovals[id] = PendingRemoval(name: entry.record.name, since: now)
        }
        for id in state.pendingRemovals.keys where listed.contains(id) { state.pendingRemovals[id] = nil }
        var notices: [SyncEvent] = []
        for o in applied {
            let by = o.by == selfId ? state.deviceName : (newDirectory[o.by] ?? state.directory[o.by])?.record.name
            for (id, p) in state.pendingRemovals where !o.members.contains(id) {
                notices.append(.deviceRemoved(name: p.name, by: by ?? DeviceID.fingerprint(o.by)))
                state.pendingRemovals[id] = nil
            }
        }
        // A device that left on its own rekeyed without itself before deleting itself (SPEC §5.4),
        // so the version that dropped it may already be installed when it leaves the listing.
        if let writer = state.rekeyBy[current.kv], let members = state.rekeyMembers[current.kv],
           !members.contains(writer), let p = state.pendingRemovals[writer] {
            notices.append(.deviceRemoved(name: p.name, by: p.name))
            state.pendingRemovals[writer] = nil
        }
        for (id, p) in state.pendingRemovals where now - p.since > config.removalGraceMs {
            notices.append(.deviceRemoved(name: p.name, by: nil))
            state.pendingRemovals[id] = nil
        }

        let changed = newDirectory != state.directory
        state.directory = newDirectory
        state.listedIds = DeviceID.sorted(listed)
        persist()
        for n in notices { await emit(n) }
        if changed { await emit(.directoryChanged) }
    }

    private func trustedSignPubs(includingSelf: Bool = false) -> [String: Data] {
        var out: [String: Data] = [:]
        for (id, e) in state.directory { out[id] = e.record.signPubBytes }
        if includingSelf, let s = secrets { out[s.identity.deviceId] = s.identity.signPub }
        return out
    }

    /// sign_pubs of `ids` from this device, the directory, or the cache of former members.
    private func knownSignPubs(_ ids: some Sequence<String>) -> [String: Data] {
        var out: [String: Data] = [:]
        for id in ids {
            if id == secrets?.identity.deviceId {
                out[id] = secrets?.identity.signPub
            } else if let pub = state.directory[id]?.record.signPubBytes {
                out[id] = pub
            } else if let cached = state.signPubCache[id] {
                out[id] = B64.decode(cached.signPub, length: 32)
            }
        }
        return out
    }

    /// Cached devices whose record was trusted under key version `kv` or later: they were
    /// members under that key even if they have been revoked since.
    private func formerMembers(trustedUnder kv: Int) -> [String: Data] {
        var out: [String: Data] = [:]
        for (id, c) in state.signPubCache where c.kv >= kv { out[id] = B64.decode(c.signPub, length: 32) }
        return out
    }

    // MARK: - Own record

    private func ownRecord(updated: Int) throws -> DeviceRecord {
        guard let s = secrets else { throw SyncError.notConfigured }
        return DeviceRecord(identity: s.identity, name: state.deviceName, kind: config.kind, platform: config.platform,
                            appVersion: config.appVersion, caps: config.caps,
                            receive: state.receive ?? Routing.defaultReceive(config.kind), updated: updated)
    }

    /// Seals and publishes our record under the current key.
    public func publishRecord() async throws {
        guard let s = secrets, let key = s.current else { throw SyncError.notConfigured }
        let updated = max(clock(), state.recordUpdated + 1)
        let record = try ownRecord(updated: updated)
        let sealed = RecordCodec.seal(record, groupKey: key.key, kv: key.kv, signSk: s.signSk, random: random)
        try await requireHub().putRecord(sealed)
        state.recordUpdated = max(state.recordUpdated, updated)
        persist()
    }

    /// Renames this device (the only device a user can rename).
    public func rename(_ name: String) async throws {
        state.deviceName = name
        persist()
        try await publishRecord()
    }

    /// Sets who may send us `type` (`nil` removes the type: "do not send me this").
    public func setReceiveRule(_ rule: ReceiveRule?, for type: MessageType) async throws {
        var receive = state.receive ?? Routing.defaultReceive(config.kind)
        receive[type.rawValue] = rule
        state.receive = receive
        persist()
        try await publishRecord()
    }

    public var receiveRules: [String: ReceiveRule] { state.receive ?? Routing.defaultReceive(config.kind) }

    // MARK: - Sending

    /// Builds the payload, routes it (SPEC §5.2), seals it, stores it in the outbox and posts it.
    ///
    /// - Parameters:
    ///   - replyTo: msg_id of a received message; the reply goes only to its sender.
    ///   - only: restrict recipients to these ids (for example the clipboard "Send to" setting).
    public func send<B: MessageBody>(_ body: B, replyTo: String? = nil, only: Set<String>? = nil) async throws
        -> SendOutcome
    {
        guard let s = secrets, let key = s.current, let selfId = state.deviceId else { throw SyncError.notConfigured }
        let now = clock()
        if let clip = body as? ClipboardSet, echo.shouldSuppress(clip.text, now: now) { return .suppressedEcho }

        var to: [String]
        if let replyTo {
            guard let original = state.received[replyTo]?.peers.first, state.directory[original] != nil else {
                return .noRecipients
            }
            to = Routing.recipients(type: B.type.rawValue, sender: selfId, directory: [], replyToSender: original)
        } else {
            to = Routing.recipients(type: B.type.rawValue, sender: selfId,
                                    directory: state.directory.values.map(\.record))
            if let only { to = to.filter(only.contains) }
        }
        guard !to.isEmpty else { return .noRecipients }

        let plaintext: Data
        do { plaintext = try Payload(type: B.type.rawValue, ts: now, replyTo: replyTo, body: body).encode() } catch {
            throw SyncError.payloadTooLarge
        }
        let msgId = generator.next()
        state.lastMsgId = msgId
        let collapse = body.collapseRealKey.map { Collapse.value(groupKey: key.key, from: selfId, realKey: $0) }
        let envelope = EnvelopeCodec.seal(msgId: msgId, from: selfId, to: to, collapse: collapse,
                                          ttlS: B.type.defaultTtlS, plaintext: plaintext, groupKey: key.key,
                                          kv: key.kv, signSk: s.signSk, random: random)
        state.outbox.append(OutboxItem(envelope: envelope, createdAt: now, nextAttemptAt: now,
                                       notificationKey: (body as? NotificationPosted)?.key))
        persist()
        return await deliver(msgId: msgId)
    }

    /// Retries queued messages that are due. Call on reconnect, on a timer, and after wake.
    @discardableResult
    public func flushOutbox() async -> [SendOutcome] {
        let now = clock()
        state.prune(now: now)
        let due = state.outbox.filter { $0.nextAttemptAt <= now }.sorted { $0.createdAt < $1.createdAt }
        var outcomes: [SendOutcome] = []
        for item in due {
            let outcome = await deliver(msgId: item.envelope.msgId)
            outcomes.append(outcome)
            if outcome == .queued { break }  // still offline: keep the order, try later
        }
        return outcomes
    }

    private func deliver(msgId: String) async -> SendOutcome {
        for _ in 0..<4 {
            guard let item = state.outbox.first(where: { $0.envelope.msgId == msgId }) else {
                return .dropped(code: "gone")
            }
            let hub: any HubAPI
            do { hub = try requireHub() } catch { return .queued }
            do {
                let result = try await hub.postMessage(item.envelope)
                removeFromOutbox(msgId)
                state.sent[msgId] = MessageRef(peers: item.envelope.to,
                                               expiresAt: item.expiresAt + Carom.Limits.clockSkewMs,
                                               key: item.notificationKey)
                persist()
                if result.superseded { return .superseded }
                return .sent(seq: result.seq ?? 0, duplicate: result.duplicate)
            } catch {
                if let api = error.apiError, api.status == 409, !api.inactive.isEmpty {
                    // Refetch the directory, drop those ids, re-seal under the same msg_id.
                    try? await refreshDirectory()
                    if !reseal(msgId: msgId, dropping: Set(api.inactive)) { return .noRecipients }
                    continue
                }
                if let api = error.apiError, api.status == 429, !api.full.isEmpty {
                    if !reseal(msgId: msgId, dropping: Set(api.full)) { return .noRecipients }
                    continue
                }
                if error.isRetryable {
                    reschedule(msgId)
                    return .queued
                }
                let code = error.code?.rawValue ?? "invalid_response"
                log.error("message dropped by the Hub: \(code, privacy: .public)")
                removeFromOutbox(msgId)
                persist()
                await noteAuthFailure(error)
                await emit(.sendDropped(code: code))
                return .dropped(code: code)
            }
        }
        removeFromOutbox(msgId)
        persist()
        return .dropped(code: "too_many_retries")
    }

    private func removeFromOutbox(_ msgId: String) {
        state.outbox.removeAll { $0.envelope.msgId == msgId }
    }

    private func reschedule(_ msgId: String) {
        guard let i = state.outbox.firstIndex(where: { $0.envelope.msgId == msgId }) else { return }
        state.outbox[i].attempts += 1
        let exp = min(state.outbox[i].attempts - 1, 16)
        let delay = min(config.retryMaxMs, config.retryBaseMs << exp)
        state.outbox[i].nextAttemptAt = clock() + Int(Double(delay) * (0.5 + 0.5 * Double.random(in: 0...1)))
        persist()
    }

    /// Re-seals our own queued envelope without `dropping`, under the same msg_id, kv and
    /// collapse value. The plaintext comes from decrypting our own envelope, so the outbox never
    /// stores plaintext. Returns false (and drops the message) when nobody is left.
    private func reseal(msgId: String, dropping: Set<String>) -> Bool {
        guard let i = state.outbox.firstIndex(where: { $0.envelope.msgId == msgId }), let s = secrets else {
            return false
        }
        let env = state.outbox[i].envelope
        let to = env.to.filter { !dropping.contains($0) }
        guard !to.isEmpty, let key = s.keysByVersion[env.kv],
              case .success(let opened) = EnvelopeCodec.open(env, signPub: s.identity.signPub, keys: [env.kv: key])
        else {
            removeFromOutbox(msgId)
            persist()
            return false
        }
        state.outbox[i].envelope = EnvelopeCodec.seal(
            msgId: env.msgId, from: env.from, to: to, collapse: env.collapse, ttlS: env.ttlS,
            plaintext: opened.plaintext, groupKey: key, kv: env.kv, signSk: s.signSk, random: random)
        persist()
        return true
    }

    // MARK: - Receiving

    /// `GET /v1/messages` until empty, handling and acknowledging each page (SPEC §9 wake-up
    /// flow, and the fallback for the WebSocket). Returns the number of messages handled.
    @discardableResult
    public func fetchMessages() async throws -> Int {
        let hub = try requireHub()
        var total = 0
        while true {
            let page: MessagePage
            do { page = try await hub.fetchMessages(limit: config.fetchLimit) } catch {
                await noteAuthFailure(error)
                throw error
            }
            guard let last = page.messages.map(\.seq).max() else { break }
            await process(page.messages)
            try await hub.ack(upTo: last)
            total += page.messages.count
            if !page.more { break }
        }
        return total
    }

    /// Runs the SPEC §3.3 checks on each message, dispatches the good ones, and drops the rest.
    /// The caller acknowledges the highest seq afterwards in every case.
    public func process(_ messages: [InboundMessage]) async {
        guard let current = secrets?.current?.kv else { return }
        // A newer kv than ours means a rekey we have not seen; an unknown sender may be a device
        // that just paired. Refresh once before judging the batch.
        let needsRefresh = messages.contains { m in
            (m.envelope["kv"]?.safeInt ?? 0) > current
                || (m.envelope["from"]?.stringValue).map { state.directory[$0] == nil } ?? false
        }
        if needsRefresh { try? await refreshDirectory() }
        for m in messages { await receive(m.envelope) }
    }

    private func receive(_ envelope: JSON) async {
        guard let s = secrets, let selfId = state.deviceId else { return }
        let now = clock()
        let context = ReceiveContext(
            selfId: selfId, directory: trustedSignPubs(), keys: s.keysByVersion, now: now, seen: Set(state.seen.keys),
            knownTypes: Set(config.caps), lastClipboardTs: state.lastClipboardTs,
            originalRecipients: state.sent.mapValues(\.peers), originalKeys: state.sent.compactMapValues(\.key))
        switch Receiver.receive(envelope, context: context) {
        case .success(let r):
            state.seen[r.envelope.msgId] = now + Carom.Limits.seenRetentionMs
            state.received[r.envelope.msgId] = MessageRef(
                peers: [r.envelope.from], expiresAt: now + r.envelope.ttlS * 1000 + Carom.Limits.clockSkewMs)
            if case .clipboardSet(let clip) = r.message {
                state.lastClipboardTs = r.payload.ts
                echo.noteReceived(clip.text, now: now)
            }
            persist()
            guard let sender = state.directory[r.envelope.from]?.record else { return }
            await handler?.handle(IncomingMessage(received: r, sender: sender))
        case .failure(let e):
            log.info("message dropped: \(e.rawValue, privacy: .public)")
            await emit(.messageDropped(e))
        }
    }

    // MARK: - Realtime (WebSocket)

    /// Creates the Hub WebSocket, starts it, and feeds its events into the engine. The app
    /// forwards sleep/wake and network changes to the returned socket.
    public func startRealtime(connector: any WebSocketConnector = URLSessionWebSocketConnector(),
                              configuration: HubSocket.Configuration = HubSocket.Configuration()) async -> HubSocket? {
        guard let token = secrets?.token, let hubURL = state.hubURL, let url = URL(string: hubURL) else { return nil }
        await stopRealtime()
        let realtime = self.realtime
        let observed = LivenessConnector(inner: connector, report: { realtime.report($0) })
        let ws = HubSocket(url: HubClient(baseURL: url).webSocketURL, token: token, connector: observed,
                           configuration: configuration)
        socket = ws
        realtime.started()
        socketTask = Task { [weak self] in
            for await event in ws.events { await self?.handle(event) }
        }
        await ws.start()
        return ws
    }

    public func stopRealtime() async {
        realtime.stopped()
        socketTask?.cancel()
        socketTask = nil
        if let socket { await socket.stop() }
        socket = nil
    }

    /// Handles one socket event. `startRealtime` calls this; it is public for apps that run the
    /// socket themselves.
    public func handle(_ event: HubSocket.Event) async {
        switch event {
        case .connected:
            try? await refreshDirectory()
            await flushOutbox()
        case .frame(.msg(let seq, let envelope)):
            await process([InboundMessage(seq: seq, envelope: envelope)])
            if let socket, await socket.sendAck(upTo: seq) { return }
            try? await requireHub().ack(upTo: seq)
        case .frame(.dir):
            try? await refreshDirectory()
        case .frame(.pair(let pairId, let candidate)):
            await candidateArrived(pairId: pairId, candidate: candidate)
        case .frame(.revoked), .revoked:
            await handleRevoked()
        case .frame(.pong), .frame(.unknown), .disconnected:
            break
        }
    }

    // MARK: - Revocation

    /// Revokes any device (SPEC §5.4), then rekeys so it cannot read future messages (§5.5) and
    /// republishes our record. Revoking this device wipes it.
    public func revoke(deviceId id: String) async throws {
        guard let selfId = state.deviceId else { throw SyncError.notConfigured }
        if id == selfId || id == "self" {
            // SPEC §5.4: a device leaving on its own first rekeys without itself, so it cannot
            // read what the others send next, and only then deletes itself.
            try await rekey(excluding: [selfId])
            try await requireHub().revoke(deviceId: "self")
            await handleRevoked()
            return
        }
        try await requireHub().revoke(deviceId: id)
        let name = state.directory[id]?.record.name ?? state.pendingRemovals[id]?.name ?? DeviceID.fingerprint(id)
        state.directory[id] = nil
        state.pendingRemovals[id] = nil
        persist()
        try await rekey(excluding: [id])
        await emit(.deviceRemoved(name: name, by: state.deviceName))
        try? await refreshDirectory()
    }

    /// Generates K' (kv + 1), boxes it for every remaining member and `PUT /v1/keyring`. On 409
    /// (a concurrent rekey) it installs the newer version and tries again.
    public func rekey(excluding: Set<String> = []) async throws {
        for _ in 0..<3 {
            let hub = try requireHub()
            let listed = Set(try await hub.devices().map(\.id))
            guard let s = secrets, let current = s.current, let selfId = state.deviceId else {
                throw SyncError.notConfigured
            }
            let leaving = excluding.contains(selfId)
            var members = leaving ? [] : [RekeyMember(id: selfId, kxPub: s.identity.kxPub)]
            for (id, e) in state.directory where listed.contains(id) && !excluding.contains(id) {
                guard let kx = e.record.kxPubBytes else { continue }
                members.append(RekeyMember(id: id, kxPub: kx))
            }
            // The last device leaving: nobody is left to protect.
            if members.isEmpty { return }
            let newKv = current.kv + 1
            let newKey = random(Carom.keyLength)
            let version = Rekey.build(by: s.identity, kv: newKv, groupKey: newKey, members: members, leaving: leaving,
                                      random: random)
            do {
                try await hub.putKeyring(version)
            } catch {
                if error.status == 409 {
                    _ = try await applyKeyring()
                    continue
                }
                throw error
            }
            // A leaving device does not keep K'; it deletes itself next.
            if leaving { return }
            guard var updated = secrets else { throw SyncError.notConfigured }
            updated.install(kv: newKv, key: newKey, now: clock())
            do { try vault.save(updated) } catch { throw SyncError.store("\(error)") }
            secrets = updated
            state.rekeyMembers[newKv] = DeviceID.sorted(members.map(\.id))
            state.rekeyBy[newKv] = selfId
            persist()
            try await publishRecord()
            await emit(.keyRotated(kv: newKv))
            return
        }
        throw SyncError.rekeyFailed
    }

    /// This device was revoked (a `revoked` frame, close code 4001, or our own revoke): wipe
    /// keys and state and return to onboarding.
    public func handleRevoked() async {
        guard secrets != nil || state.deviceId != nil else { return }
        await wipe()
        await emit(.revoked)
    }

    /// Erases keys and local state (used on revoke and for "reset this Mac").
    public func wipe() async {
        realtime.stopped()
        let ws = socket
        socket = nil
        socketTask?.cancel()
        socketTask = nil
        if let ws { await ws.stop() }
        try? vault.wipe()
        try? stateStore.wipe()
        secrets = nil
        state = SyncState()
        offers = [:]
        pendingJoin = nil
        echo = EchoSuppressor()
    }

    // MARK: - Helpers

    private func requireHub() throws -> any HubAPI {
        guard let token = secrets?.token, let hubURL = state.hubURL, let url = URL(string: hubURL) else {
            throw SyncError.notConfigured
        }
        return hubFactory(url, token)
    }

    private func persist() {
        do { try stateStore.save(state) } catch { log.error("state save failed") }
    }

    private func noteAuthFailure(_ error: HubError) async {
        if error.status == 401 { await emit(.tokenRejected) }
    }

    private func emit(_ event: SyncEvent) async {
        await handler?.handle(event)
    }
}
