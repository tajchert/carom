import AppKit
import CaromHub
import CaromProtocol
import CaromStore
import CaromSync
import Observation
import os
#if canImport(CaromDesktop)
import CaromDesktop
#endif

enum AppPhase: Equatable, Sendable {
    case loading
    case onboarding
    case ready
}

enum ConnectionState: Equatable, Sendable {
    /// Not set up, or realtime not started.
    case offline
    case connecting
    case connected
    case noNetwork
    case asleep
    /// The Hub refused our token (401); syncing stopped until the user pairs again.
    case stopped

    var label: String {
        switch self {
        case .offline: "Offline"
        case .connecting: "Connecting…"
        case .connected: "Connected"
        case .noNetwork: "No network"
        case .asleep: "Asleep"
        case .stopped: "Stopped"
        }
    }
}

enum AppWindow: String, Sendable {
    case onboarding
    case addDevice = "add-device"
}

/// Asks the menu-bar label (which is always on screen) to open a window.
struct WindowRequest: Equatable {
    let window: AppWindow
    let nonce = UUID()
}

/// The "Add device" flow (docs/CLIENTS.md, pairing UI).
enum PairingState: Equatable, Sendable {
    case idle
    case creating
    case showing(Invite)
    case confirming(Invite, Candidate)
    case approving(Candidate)
    case added(fingerprint: String)
    case expired
    case failed(String)
}

/// The app's state and behaviour. Owns the sync engine's lifecycle and the realtime socket,
/// implements the `SyncHandler` side (through `SyncBridge`), and drives the CaromDesktop services.
@MainActor
@Observable
final class AppModel {
    struct Options {
        /// Sleep/wake and network observers (off in tests).
        var systemHooks = true
        var unreachableAfter: Duration = .seconds(45)
        var pairingPollInterval: Duration = .seconds(3)
        var outboxInterval: Duration = .seconds(30)
        var copySuppression: TimeInterval = 2
        var now: @MainActor () -> Date = { Date() }
    }

    // MARK: State read by the views

    private(set) var phase: AppPhase = .loading
    private(set) var deviceId: String?
    private(set) var deviceName = ""
    private(set) var hubURL: String?
    private(set) var directory: [DirectoryEntry] = []
    private(set) var receiveRules: [String: ReceiveRule] = [:]
    private(set) var preferences = Preferences()
    private(set) var connection: ConnectionState = .offline
    private(set) var banners: [Banner] = []
    private(set) var activity = RecentActivity()
    private(set) var pairing: PairingState = .idle
    private(set) var statusMessage: String?
    private(set) var pasteboardAccess: PasteboardAccess = .unknown
    private(set) var notificationsAllowed: Bool?
    private(set) var hotkeyAvailable = false
    private(set) var launchAtLogin = false
    /// Battery reports (`device.status@1`) by device id.
    private(set) var deviceStatus: [String: DeviceStatus] = [:]
    var windowRequest: WindowRequest?

    // MARK: Dependencies

    @ObservationIgnored let sync: any SyncService
    @ObservationIgnored private let presenter: any NotificationPresenting
    @ObservationIgnored private let clipboard: any ClipboardWatching
    @ObservationIgnored private let hotkey: any HotkeyRegistering
    @ObservationIgnored private let loginItem: any LoginItemControlling
    @ObservationIgnored private let options: Options
    @ObservationIgnored private let log = Logger(subsystem: "com.mtajchert.carom.mac", category: "app")

    @ObservationIgnored private var socket: HubSocket?
    @ObservationIgnored private var monitor: SystemMonitor?
    @ObservationIgnored private var transportTask: Task<Void, Never>?
    @ObservationIgnored private var outboxTask: Task<Void, Never>?
    @ObservationIgnored private var unreachableTask: Task<Void, Never>?
    @ObservationIgnored private var pairingTask: Task<Void, Never>?
    @ObservationIgnored private var statusTask: Task<Void, Never>?
    @ObservationIgnored private var latestConnection = 0
    @ObservationIgnored private var suppressCopiesUntil = Date.distantPast
    @ObservationIgnored private var watcherRunning = false

    init(sync: any SyncService, desktop: DesktopServices, options: Options = Options()) {
        self.sync = sync
        presenter = desktop.presenter
        clipboard = desktop.clipboard
        hotkey = desktop.hotkey
        loginItem = desktop.loginItem
        self.options = options
    }

    /// The production graph: Keychain secrets, a JSON state file in Application Support and the
    /// URLSession Hub client.
    static func live() -> AppModel {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
        var config = SyncConfiguration(deviceName: defaultDeviceName)
        config.appVersion = version
        let state: any StateStore = (try? JSONFileStateStore.applicationSupport(appFolder: "Carom"))
            ?? JSONFileStateStore(url: URL.applicationSupportDirectory.appending(path: "Carom/state.json"))
        let engine = SyncEngine(configuration: config, secrets: SecretStoreFactory.make(), state: state,
                                hub: HubClient.factory())
        return AppModel(sync: engine, desktop: .live())
    }

    static var defaultDeviceName: String { Host.current().localizedName ?? "Mac" }

    // MARK: Derived state

    var hubHost: String {
        guard let hubURL, let url = URL(string: hubURL) else { return "" }
        return url.host() ?? hubURL
    }

    var phones: [DirectoryEntry] { directory.filter { $0.record.kind == .phone } }

    var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
    }

    var automaticClipboard: Bool {
        ReceivePolicy.automaticClipboard(preferences.automaticClipboard, access: pasteboardAccess)
    }

    var clipboardTargets: [DirectoryEntry] { ReceivePolicy.clipboardTargets(directory, selfId: deviceId) }

    func isSendingClipboard(to id: String) -> Bool {
        preferences.clipboardSendTo.map { $0.contains(id) } ?? true
    }

    func receives(_ category: ReceiveCategory, from id: String) -> Bool {
        ReceivePolicy.allows(receiveRules, category, from: id)
    }

    func name(of id: String) -> String {
        if id == deviceId { return deviceName }
        return directory.first { $0.record.id == id }?.record.name ?? DeviceID.fingerprint(id)
    }

    // MARK: Lifecycle

    /// Loads keys and state, then shows onboarding or starts syncing.
    func start() async {
        guard phase == .loading else { return }
        do { _ = try await sync.load() } catch {
            log.error("load failed")
        }
        await sync.setHandler(SyncBridge(model: self))
        presenter.onAction = { [weak self] action in
            guard let self else { return }
            Task { await self.perform(action) }
        }
        clipboard.onCopy = { [weak self] item in self?.clipboardChanged(item) }
        launchAtLogin = loginItem.isEnabled
        pasteboardAccess = clipboard.accessBehavior
        await reloadState(loadPreferences: true)
        if await sync.isConfigured, preferences.onboardingComplete {
            await goReady()
        } else {
            phase = .onboarding
            request(.onboarding)
        }
    }

    private func goReady() async {
        phase = .ready
        await startRealtime()
        if options.systemHooks, monitor == nil {
            monitor = SystemMonitor(onSleep: { [weak self] in self?.systemWillSleep() },
                                    onWake: { [weak self] in self?.systemDidWake() },
                                    onNetwork: { [weak self] up in self?.networkChanged(up) })
        }
        registerHotkey()
        updateWatcher()
        startOutboxTimer()
    }

    /// Stops everything that talks to the Hub or the system (on revoke and reset).
    private func stopServices() async {
        transportTask?.cancel()
        transportTask = nil
        outboxTask?.cancel()
        outboxTask = nil
        unreachableTask?.cancel()
        unreachableTask = nil
        pairingTask?.cancel()
        pairingTask = nil
        monitor?.stop()
        monitor = nil
        hotkey.unregister()
        hotkeyAvailable = false
        clipboard.stop()
        watcherRunning = false
        socket = nil
        await sync.stopRealtime()
    }

    private func reloadState(loadPreferences: Bool = false) async {
        deviceId = await sync.deviceId
        deviceName = await sync.deviceName
        hubURL = await sync.hubURL
        receiveRules = await sync.receiveRules
        if loadPreferences {
            preferences = Preferences(json: await sync.settings[Preferences.settingsKey])
        }
        applyDirectory(await sync.directory)
    }

    /// Applies a new directory: withdraws notifications of removed phones and gives newly paired
    /// devices the default (allowed) settings when the user has narrowed a rule.
    private func applyDirectory(_ newDirectory: [DirectoryEntry]) {
        let newIds = Set(newDirectory.map(\.record.id))
        for gone in phones where !newIds.contains(gone.record.id) {
            presenter.withdrawAll(phoneId: gone.record.id)
            activity.removeNotifications(phoneId: gone.record.id)
        }
        directory = newDirectory
        deviceStatus = deviceStatus.filter { newIds.contains($0.key) }

        // Remember who was there, so devices paired later get the defaults (allowed).
        guard deviceId != nil else { return }
        let known = preferences.knownDevices.map { Set($0) }
        guard known != newIds else { return }
        let added = known.map { newIds.subtracting($0) } ?? []
        preferences.knownDevices = DeviceID.sorted(newIds)
        if !added.isEmpty, let sendTo = preferences.clipboardSendTo {
            preferences.clipboardSendTo = DeviceID.sorted(Set(sendTo).union(added))
        }
        savePreferences()
        guard !added.isEmpty else { return }
        let devices = Array(newIds)
        let rules = receiveRules
        Task { [weak self] in
            guard let self else { return }
            for category in ReceiveCategory.allCases {
                guard let rule = ReceivePolicy.rule(rules[category.primaryType.rawValue], adding: added,
                                                    devices: devices) else { continue }
                for type in category.types { try? await self.sync.setReceiveRule(rule, for: type) }
            }
            self.receiveRules = await self.sync.receiveRules
        }
    }

    // MARK: Realtime connection

    private func startRealtime() async {
        transportTask?.cancel()
        let (stream, continuation) = AsyncStream.makeStream(of: TransportEvent.self)
        transportTask = Task { [weak self] in
            for await event in stream { self?.transport(event) }
        }
        let connector = ObservedConnector(report: { continuation.yield($0) })
        socket = await sync.openRealtime(connector: connector)
        connection = socket == nil ? .offline : .connecting
        if socket != nil { armUnreachable() }
    }

    func transport(_ event: TransportEvent) {
        guard connection != .stopped else { return }
        switch event {
        case .opening(let id):
            latestConnection = id
            if connection != .connected { connection = .connecting }
            armUnreachable()
        case .up(let id):
            guard id == latestConnection else { return }
            connection = .connected
            unreachableTask?.cancel()
            unreachableTask = nil
            dismissBanner(kind: .hubUnreachable)
        case .down(let id):
            guard id == latestConnection, connection == .connected || connection == .connecting else { return }
            connection = .connecting
            armUnreachable()
        }
    }

    private func armUnreachable() {
        guard unreachableTask == nil else { return }
        let delay = options.unreachableAfter
        unreachableTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self else { return }
            self.unreachableTask = nil
            if self.connection == .connecting {
                self.addBanner(Banner(kind: .hubUnreachable, message: UserMessages.hubUnreachable, style: .warning))
            }
        }
    }

    func systemWillSleep() {
        guard phase == .ready, connection != .stopped else { return }
        connection = .asleep
        unreachableTask?.cancel()
        unreachableTask = nil
        let socket = self.socket
        Task { await socket?.systemWillSleep() }
    }

    func systemDidWake() {
        guard phase == .ready, connection != .stopped else { return }
        connection = .connecting
        armUnreachable()
        let socket = self.socket, sync = self.sync
        Task {
            await socket?.systemDidWake()
            await sync.flushOutbox()
        }
        refreshPasteboardAccess()
    }

    func networkChanged(_ up: Bool) {
        guard phase == .ready, connection != .stopped else { return }
        if up {
            if connection == .noNetwork { connection = .connecting }
            armUnreachable()
        } else {
            connection = .noNetwork
            unreachableTask?.cancel()
            unreachableTask = nil
            dismissBanner(kind: .hubUnreachable)
        }
        let socket = self.socket
        Task { await socket?.networkPathChanged(isSatisfied: up) }
    }

    private func startOutboxTimer() {
        outboxTask?.cancel()
        let interval = options.outboxInterval
        outboxTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: interval)
                guard let sync = self?.sync else { return }
                await sync.flushOutbox()
            }
        }
    }

    // MARK: Incoming messages (SyncHandler)

    func receive(_ message: IncomingMessage) async {
        await dispatch(message.message, msgId: message.msgId, sender: message.sender)
    }

    /// Applies one decoded message from a trusted sender.
    func dispatch(_ message: Message, msgId: String, sender: DeviceRecord) async {
        switch message {
        case .notificationPosted(let n):
            if preferences.noteApp(id: n.app.id, name: n.app.name) { savePreferences() }
            guard !preferences.paused, receives(.notifications, from: sender.id), !preferences.isMuted(n.app.id) else {
                return
            }
            activity.add(RecentNotification(phoneId: sender.id, phoneName: sender.name, appId: n.app.id,
                                            appName: n.app.name, title: n.title, text: n.text, key: n.key,
                                            msgId: msgId, code: n.text.flatMap(CodeExtractor.code(in:)),
                                            date: options.now()))
            let presented = PresentedNotification(phoneId: sender.id, phoneName: sender.name, appId: n.app.id,
                                                  appName: n.app.name, title: n.title, text: n.text, key: n.key,
                                                  msgId: msgId)
            await presenter.present(presented, showPhoneName: phones.count > 1)
        case .notificationRemoved(let r):
            presenter.withdraw(phoneId: sender.id, key: r.key)
        case .clipboardSet(let c):
            guard !preferences.paused, receives(.clipboard, from: sender.id) else { return }
            clipboard.write(text: c.text, sensitive: c.sensitive)
            activity.add(RecentClip(direction: .received(from: sender.name), text: c.text, sensitive: c.sensitive,
                                    date: options.now()))
        case .deviceStatus(let s):
            deviceStatus[sender.id] = s
        case .notificationDismiss:
            break  // a desktop does not post notifications
        }
    }

    /// Engine events (SyncHandler).
    func handle(event: SyncEvent) async {
        switch event {
        case .directoryChanged:
            receiveRules = await sync.receiveRules
            applyDirectory(await sync.directory)
        case .deviceRemoved(let name, let by):
            addBanner(Banner(kind: .deviceRemoved, message: UserMessages.removed(name: name, by: by),
                             style: by == nil ? .warning : .info, id: "removed-\(name)-\(UUID().uuidString)"))
        case .keyRotated:
            break
        case .pairingCandidate(let pairId, let candidate):
            candidateArrived(Candidate(pairId: pairId, deviceId: candidate.deviceId))
        case .pairingRejected:
            if case .showing = pairing { pairing = .failed(UserMessages.pairingRejected) }
            pairingTask?.cancel()
            addBanner(Banner(kind: .pairingRejected, message: UserMessages.pairingRejected, style: .error))
        case .messageDropped:
            break  // already logged by the engine (code only)
        case .sendDropped(let code):
            addBanner(Banner(kind: .sendFailed, message: UserMessages.sendDropped(code: code), style: .warning))
        case .revoked:
            await stopServices()
            resetLocalState()
            phase = .onboarding
            addBanner(Banner(kind: .revoked, message: UserMessages.revoked, style: .error))
            request(.onboarding)
        case .tokenRejected:
            guard connection != .stopped else { return }
            connection = .stopped
            unreachableTask?.cancel()
            unreachableTask = nil
            dismissBanner(kind: .hubUnreachable)
            addBanner(Banner(kind: .tokenRejected, message: UserMessages.tokenRejected, style: .error))
            let sync = self.sync
            socket = nil
            Task { await sync.stopRealtime() }
        }
    }

    // MARK: Notification actions

    func perform(_ action: NotificationAction) async {
        switch action {
        case .dismissOnPhone(_, let key, let msgId):
            _ = try? await sync.send(NotificationDismiss(key: key), replyTo: msgId, only: nil)
        case .copiedCode, .copiedText:
            // Our own copy: do not send it straight back to the phone.
            suppressCopiesUntil = options.now().addingTimeInterval(options.copySuppression)
        }
    }

    func dismissOnPhone(_ n: RecentNotification) async {
        await perform(.dismissOnPhone(phoneId: n.phoneId, key: n.key, msgId: n.msgId))
        presenter.withdraw(phoneId: n.phoneId, key: n.key)
    }

    /// Copies text from the menu. Written through the watcher, so it is not sent back.
    func copy(_ text: String, sensitive: Bool) {
        clipboard.write(text: text, sensitive: sensitive)
        showStatus("Copied.")
    }

    // MARK: Clipboard

    func clipboardChanged(_ item: ClipboardItem) {
        guard phase == .ready, automaticClipboard, !preferences.paused, connection != .stopped,
              options.now() >= suppressCopiesUntil else { return }
        Task { await sendClipboard(item) }
    }

    /// The menu button and ⌃⌥⌘C: send what is on the clipboard now.
    func sendClipboardNow() async {
        guard phase == .ready else { return }
        guard let item = clipboard.readCurrent() else {
            showStatus(UserMessages.nothingToSend)
            return
        }
        await sendClipboard(item)
    }

    func sendClipboard(_ item: ClipboardItem) async {
        let targets = clipboardTargets
        let only = ReceivePolicy.sendRestriction(preferences.clipboardSendTo, eligible: targets)
        guard !targets.isEmpty, only?.isEmpty != true else {
            showStatus(UserMessages.noClipboardTargets)
            return
        }
        let outcome: SendOutcome
        do {
            outcome = try await sync.send(ClipboardSet(text: item.text, sensitive: item.sensitive), replyTo: nil,
                                          only: only)
        } catch {
            showStatus(UserMessages.describe(error))
            return
        }
        let names = targets.filter { only?.contains($0.record.id) ?? true }.map(\.record.name)
        switch outcome {
        case .sent, .superseded:
            activity.add(RecentClip(direction: .sent(to: names), text: item.text, sensitive: item.sensitive,
                                    date: options.now()))
            showStatus(UserMessages.sent(to: names))
        case .queued:
            activity.add(RecentClip(direction: .sent(to: names), text: item.text, sensitive: item.sensitive,
                                    date: options.now()))
            showStatus(UserMessages.queued)
        case .noRecipients:
            showStatus(UserMessages.noClipboardTargets)
        case .suppressedEcho:
            break
        case .dropped(let code):
            showStatus(UserMessages.sendDropped(code: code))
        }
    }

    func refreshPasteboardAccess() {
        pasteboardAccess = clipboard.accessBehavior
        updateWatcher()
    }

    private func updateWatcher() {
        let shouldRun = phase == .ready && automaticClipboard && !preferences.paused
        guard shouldRun != watcherRunning else { return }
        watcherRunning = shouldRun
        if shouldRun { clipboard.start() } else { clipboard.stop() }
    }

    var isWatchingClipboard: Bool { watcherRunning }

    private func registerHotkey() {
        do {
            try hotkey.register(keyCode: SendHotkey.keyCode, modifiers: SendHotkey.modifiers) { [weak self] in
                guard let self else { return }
                Task { await self.sendClipboardNow() }
            }
            hotkeyAvailable = true
        } catch {
            hotkeyAvailable = false
        }
    }

    // MARK: Settings

    func setPaused(_ paused: Bool) {
        preferences.paused = paused
        savePreferences()
        updateWatcher()
    }

    func setAutomaticClipboard(_ on: Bool) {
        preferences.automaticClipboard = on
        savePreferences()
        updateWatcher()
    }

    func setMuted(_ appId: String, _ muted: Bool) {
        preferences.setMuted(appId, muted)
        savePreferences()
    }

    func setSendClipboard(to id: String, _ enabled: Bool) {
        preferences.clipboardSendTo = ReceivePolicy.sendTo(preferences.clipboardSendTo, setting: id, enabled: enabled,
                                                           eligible: clipboardTargets.map(\.record.id))
        savePreferences()
    }

    /// Writes this Mac's `receive` rule for a category (published in our record, so the
    /// sender stops sending).
    func setReceive(_ category: ReceiveCategory, from id: String, _ allowed: Bool) async throws {
        let rule = ReceivePolicy.rule(receiveRules[category.primaryType.rawValue], setting: id, allowed: allowed,
                                      devices: directory.map(\.record.id))
        for type in category.types { try await sync.setReceiveRule(rule, for: type) }
        receiveRules = await sync.receiveRules
    }

    func rename(_ name: String) async throws {
        let trimmed = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(64))
        guard !trimmed.isEmpty, trimmed != deviceName else { return }
        try await sync.rename(trimmed)
        deviceName = await sync.deviceName
    }

    /// Revokes a device, then rekeys (SPEC §5.4). Removing this Mac wipes it.
    func remove(deviceId id: String) async throws {
        try await sync.revoke(deviceId: id)
        if id != deviceId { await reloadState() }
    }

    func setLaunchAtLogin(_ on: Bool) {
        do { try loginItem.setEnabled(on) } catch {
            showStatus("Couldn't change the login item. Check System Settings › General › Login Items.")
        }
        launchAtLogin = loginItem.isEnabled
    }

    /// After a 401: erase this Mac's keys and start onboarding again.
    func resetAndPairAgain() async {
        await stopServices()
        await sync.wipe()
        resetLocalState()
        banners = []
        phase = .onboarding
        request(.onboarding)
    }

    private func resetLocalState() {
        deviceId = nil
        hubURL = nil
        directory = []
        receiveRules = [:]
        preferences = Preferences()
        activity.clear()
        deviceStatus = [:]
        pairing = .idle
        connection = .offline
    }

    private func savePreferences() {
        let json = preferences.json, sync = self.sync
        Task { await sync.setSetting(Preferences.settingsKey, json) }
    }

    // MARK: Onboarding

    /// "Set up a new hub" with a `carom://setup` URI.
    func setUp(uri: String) async throws {
        let trimmed = uri.trimmingCharacters(in: .whitespacesAndNewlines)
        if case .pair? = QR.parse(trimmed) { throw AppError(UserMessages.pairURIExpected) }
        guard case .setup? = QR.parse(trimmed) else { throw AppError(UserMessages.notURI) }
        try await sync.setup(uri: trimmed)
        await reloadState()
    }

    /// "Join" with a `carom://pair` URI: returns the fingerprint to compare on the other device.
    func beginJoin(uri: String) async throws -> String {
        let trimmed = uri.trimmingCharacters(in: .whitespacesAndNewlines)
        if case .setup? = QR.parse(trimmed) { throw AppError(UserMessages.setupURIExpected) }
        guard case .pair? = QR.parse(trimmed) else { throw AppError(UserMessages.notURI) }
        return try await sync.beginJoinReturningFingerprint(uri: trimmed)
    }

    /// Waits for the other device to approve (up to 5 minutes).
    func completeJoin() async throws {
        try await sync.completeJoin()
        await reloadState()
    }

    func requestNotificationPermission() async {
        notificationsAllowed = await presenter.requestAuthorization()
    }

    func finishOnboarding() async {
        preferences.onboardingComplete = true
        if preferences.knownDevices == nil { preferences.knownDevices = DeviceID.sorted(directory.map(\.record.id)) }
        savePreferences()
        refreshPasteboardAccess()
        dismissBanner(kind: .revoked)
        await goReady()
    }

    // MARK: Pairing (the device that invites)

    func startPairing() async {
        pairingTask?.cancel()
        pairing = .creating
        do {
            let invite = try await sync.makeInvite()
            pairing = .showing(invite)
            watchPairing(invite)
        } catch {
            pairing = .failed(UserMessages.describe(error))
        }
    }

    /// Expires the invite and polls `GET /v1/pair/:id` as a fallback for a missed `pair` frame.
    private func watchPairing(_ invite: Invite) {
        let interval = options.pairingPollInterval
        pairingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: interval)
                guard !Task.isCancelled, let self else { return }
                guard case .showing(let current) = self.pairing, current == invite else { return }
                if Int(self.options.now().timeIntervalSince1970 * 1000) >= invite.expiresAt {
                    self.pairing = .expired
                    await self.sync.cancelPairing(pairId: invite.pairId)
                    return
                }
                if let candidate = try? await self.sync.pollCandidate(pairId: invite.pairId) {
                    self.candidateArrived(candidate)
                }
            }
        }
    }

    func candidateArrived(_ candidate: Candidate) {
        guard case .showing(let invite) = pairing, invite.pairId == candidate.pairId else { return }
        pairingTask?.cancel()
        pairing = .confirming(invite, candidate)
        request(.addDevice)
    }

    func approveCandidate() async {
        guard case .confirming(_, let candidate) = pairing else { return }
        pairing = .approving(candidate)
        do {
            try await sync.approvePairing(pairId: candidate.pairId)
            pairing = .added(fingerprint: candidate.fingerprint)
        } catch {
            pairing = .failed(UserMessages.describe(error))
        }
    }

    func rejectCandidate() async {
        guard case .confirming(_, let candidate) = pairing else { return }
        try? await sync.rejectPairing(pairId: candidate.pairId)
        pairing = .failed("The device was turned away. Show a new code to try again.")
    }

    func cancelPairing() async {
        pairingTask?.cancel()
        pairingTask = nil
        switch pairing {
        case .showing(let invite): await sync.cancelPairing(pairId: invite.pairId)
        case .confirming(_, let candidate): try? await sync.rejectPairing(pairId: candidate.pairId)
        default: break
        }
        pairing = .idle
    }

    // MARK: Banners, status and windows

    func addBanner(_ banner: Banner) {
        banners.removeAll { $0.id == banner.id }
        banners.append(banner)
    }

    func dismissBanner(_ banner: Banner) { banners.removeAll { $0.id == banner.id } }

    func dismissBanner(kind: Banner.Kind) { banners.removeAll { $0.kind == kind } }

    func showStatus(_ message: String) {
        statusMessage = message
        statusTask?.cancel()
        statusTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            self?.statusMessage = nil
        }
    }

    func request(_ window: AppWindow) { windowRequest = WindowRequest(window: window) }
}

/// An error whose message is already user-facing.
struct AppError: LocalizedError, Equatable {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Bridges the engine's `SyncHandler` calls onto the main actor.
final class SyncBridge: SyncHandler {
    private weak let model: AppModel?

    init(model: AppModel) { self.model = model }

    func handle(_ message: IncomingMessage) async { await model?.receive(message) }
    func handle(_ event: SyncEvent) async { await model?.handle(event: event) }
}
