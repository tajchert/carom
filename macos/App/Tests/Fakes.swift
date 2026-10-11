import CaromHub
import CaromProtocol
import CaromStore
import CaromSync
import Foundation
@testable import Carom
import CaromDesktop

func devId(_ c: Character) -> String { "dev_" + String(repeating: c, count: 22) }

let macId = devId("M")
let phoneA = devId("A")
let phoneB = devId("B")
let laptopC = devId("C")

func record(_ id: String, _ name: String, kind: DeviceKind, receive: [String: ReceiveRule]? = nil) -> DeviceRecord {
    let caps: [String] = switch kind {
    case .phone: [MessageType.clipboardSet.rawValue, MessageType.notificationDismiss.rawValue]
    case .desktop: Routing.desktopCaps
    case .cli: [MessageType.clipboardSet.rawValue]
    }
    return DeviceRecord(id: id, name: name, kind: kind, platform: kind == .phone ? "android" : "macos",
                        appVersion: "1.0.0", signPub: "", kxPub: "", caps: caps,
                        receive: receive ?? Routing.defaultReceive(kind), updated: 1)
}

func entry(_ record: DeviceRecord) -> DirectoryEntry { DirectoryEntry(record: record, kv: 1) }

/// In-memory `SyncService` that records what the app asks for.
actor FakeSync: SyncService {
    struct Sent: Equatable {
        var type: MessageType
        var body: JSONObject
        var replyTo: String?
        var only: Set<String>?
    }

    var isConfigured = true
    var deviceId: String? = macId
    var deviceName = "Test Mac"
    var hubURL: String? = "https://carom-hub.example.workers.dev"
    var directory: [DirectoryEntry] = []
    var settings: [String: JSON] = [:]
    var receiveRules: [String: ReceiveRule] = Routing.defaultReceive(.desktop)

    var sent: [Sent] = []
    var sendOutcome: SendOutcome = .sent(seq: 1, duplicate: false)
    var stopRealtimeCalls = 0
    var wiped = false
    var revoked: [String] = []
    var invite = Invite(pairId: "pair-1", uri: "carom://pair?v=1", expiresAt: Int.max)
    var polledCandidate: Candidate?
    var approved: [String] = []
    var rejected: [String] = []
    var cancelled: [String] = []
    var setupURIs: [String] = []
    /// Blob plaintexts by id, and the ids that were asked for.
    var blobs: [String: Data] = [:]
    var fetchedBlobs: [String] = []

    init(directory: [DirectoryEntry] = [], settings: [String: JSON] = [:], configured: Bool = true) {
        self.directory = directory
        self.settings = settings
        isConfigured = configured
        if !configured {
            deviceId = nil
            hubURL = nil
        }
    }

    func set(directory: [DirectoryEntry]) { self.directory = directory }
    func set(outcome: SendOutcome) { sendOutcome = outcome }
    func set(candidate: Candidate?) { polledCandidate = candidate }
    func set(configured: Bool) { isConfigured = configured }

    func load() async throws -> Bool { isConfigured }
    func setSetting(_ key: String, _ value: JSON?) async { settings[key] = value }
    func setHandler(_ handler: (any SyncHandler)?) async {}

    func setup(uri: String) async throws { setupURIs.append(uri) }
    func beginJoinReturningFingerprint(uri: String) async throws -> String { "ABCDEFGH" }
    func completeJoin() async throws {}

    func makeInvite() async throws -> Invite { invite }
    func pollCandidate(pairId: String) async throws -> Candidate? { polledCandidate }
    func approvePairing(pairId: String) async throws { approved.append(pairId) }
    func rejectPairing(pairId: String) async throws { rejected.append(pairId) }
    func cancelPairing(pairId: String) async { cancelled.append(pairId) }

    func refreshDirectory() async throws {}
    func rename(_ name: String) async throws { deviceName = name }
    func setReceiveRule(_ rule: ReceiveRule?, for type: MessageType) async throws { receiveRules[type.rawValue] = rule }

    func send<B: MessageBody>(_ body: B, replyTo: String?, only: Set<String>?) async throws -> SendOutcome {
        sent.append(Sent(type: B.type, body: body.json, replyTo: replyTo, only: only))
        return sendOutcome
    }

    func flushOutbox() async -> [SendOutcome] { [] }
    func openRealtime() async -> HubSocket? { nil }
    nonisolated let realtime = AsyncStream.makeStream(of: RealtimeState.self)
    nonisolated func realtimeStates() -> AsyncStream<RealtimeState> { realtime.stream }
    func stopRealtime() async { stopRealtimeCalls += 1 }
    func fetchBlob(_ ref: BlobRef) async -> Data? {
        fetchedBlobs.append(ref.id)
        return blobs[ref.id]
    }
    func revoke(deviceId: String) async throws { revoked.append(deviceId) }
    func wipe() async {
        wiped = true
        isConfigured = false
        deviceId = nil
        settings = [:]
    }
}

@MainActor
final class FakePresenter: NotificationPresenting {
    var onAction: ((NativeNotificationEvent) -> Void)?
    var presented: [(PresentedNotification, Bool)] = []
    var withdrawn: [(String, String)] = []
    var withdrawnAll: [String] = []
    var authorization = true

    var state: Bool?
    func requestAuthorization() async -> Bool {
        state = authorization
        return authorization
    }
    func authorizationState() async -> Bool? { state }
    func present(_ notification: PresentedNotification, showPhoneName: Bool) async {
        presented.append((notification, showPhoneName))
    }
    func withdraw(phoneId: String, key: String) { withdrawn.append((phoneId, key)) }
    func withdrawAll(phoneId: String) { withdrawnAll.append(phoneId) }
}

@MainActor
final class FakeClipboard: ClipboardWatching {
    var onCopy: ((ClipboardItem) -> Void)?
    var accessBehavior: PasteboardAccess = .alwaysAllow
    var running = false
    var written: [ClipboardItem] = []
    var current: ClipboardItem?
    var reads = 0

    func start() { running = true }
    func stop() { running = false }
    func write(text: String, sensitive: Bool) { written.append(ClipboardItem(text: text, sensitive: sensitive)) }
    func readCurrent() -> ClipboardItem? {
        reads += 1
        return current
    }
}

@MainActor
final class FakeHotkey: HotkeyRegistering {
    var handler: (@MainActor () -> Void)?
    var unregisterCalls = 0
    var fail = false

    struct Taken: Error {}

    func register(keyCode: UInt32, modifiers: UInt32, handler: @escaping @MainActor () -> Void) throws {
        if fail { throw Taken() }
        self.handler = handler
    }
    func unregister() {
        unregisterCalls += 1
        handler = nil
    }
}

@MainActor
final class FakeChipHost: LiveChipHost {
    /// "show text:5 min", "show progress:0.5", "hide".
    var calls: [String] = []
    func show(_ content: LiveChipContent) {
        switch content {
        case .text(let t): calls.append("show text:\(t)")
        case .progress(let f): calls.append("show progress:\(f)")
        }
    }
    func hide() { calls.append("hide") }
}

@MainActor
final class FakeLoginItem: LoginItemControlling {
    var isEnabled = false
    var requiresApproval = false
    /// Simulates SMAppService registering but waiting for the user's approval.
    var needsApproval = false
    func setEnabled(_ enabled: Bool) throws {
        if enabled, needsApproval {
            requiresApproval = true
            return
        }
        isEnabled = enabled
    }
}

/// A model wired to fakes, plus handles to inspect them.
@MainActor
struct Harness {
    let model: AppModel
    let sync: FakeSync
    let presenter = FakePresenter()
    let clipboard = FakeClipboard()
    let hotkey = FakeHotkey()
    let loginItem = FakeLoginItem()
    let chipHost = FakeChipHost()
    let clock: TestClock

    init(directory: [DirectoryEntry] = [entry(record(phoneA, "Pixel", kind: .phone))],
         access: PasteboardAccess = .alwaysAllow, onboarded: Bool = true, configured: Bool = true) {
        var prefs = Preferences()
        prefs.onboardingComplete = onboarded
        var settings: [String: JSON] = [:]
        if let json = prefs.json { settings[Preferences.settingsKey] = json }
        sync = FakeSync(directory: configured ? directory : [], settings: settings, configured: configured)
        clipboard.accessBehavior = access
        let clock = TestClock()
        self.clock = clock
        var options = AppModel.Options()
        options.systemHooks = false
        options.unreachableAfter = .milliseconds(50)
        options.pairingPollInterval = .milliseconds(20)
        options.outboxInterval = .seconds(3600)
        options.now = { clock.now }
        model = AppModel(sync: sync,
                         desktop: DesktopServices(presenter: presenter, clipboard: clipboard, hotkey: hotkey,
                                                  loginItem: loginItem, liveChipHost: chipHost),
                         options: options)
    }

    func start() async {
        await model.start()
        await settle()
    }
}

@MainActor
final class TestClock {
    var now = Date(timeIntervalSince1970: 1_800_000_000)
    func advance(_ seconds: TimeInterval) { now = now.addingTimeInterval(seconds) }
}

/// Lets spawned tasks (preference saves, sends) run.
@MainActor
func settle() async {
    for _ in 0..<20 { await Task.yield() }
}
