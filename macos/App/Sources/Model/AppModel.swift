import AppKit
import CaromHub
import CaromProtocol
import CaromStore
import CaromSync
import Observation
import os
import CaromDesktop

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
    /// The Settings scene (opened with `openSettings`, not `openWindow`).
    case settings
    /// Debug builds: the popover content in a window (`-caromShowWindows menu`).
    case debugMenu = "debug-menu"
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
        /// Debug builds only (`-caromAutoApprove`): approve a verified pairing candidate.
        var autoApprove = false
        /// Debug builds only (`-caromEventLog`): receives event names and message types.
        var trace: (@MainActor (String) -> Void)?
        /// Card fade, "Phone is asleep" delay and visible stacks.
        var notifications = NotificationCoordinator.Options()
        /// Where decrypted images are cached. Every `Options()` gets a folder of its own, so
        /// tests do not share a cache.
        var blobDirectory = URL.temporaryDirectory.appending(path: "carom-blobs-\(UUID().uuidString)")
        /// How long an automatically copied code stays on the clipboard.
        var codeClearDelay: Duration = .seconds(60)
        /// Shared with the Hub clients; the model shows its drift as a banner and a notice.
        var clockSkew: ClockSkewTracker?
        /// A message sealed longer ago than this is late (the Mac or phone was offline): it is
        /// recorded in the history but does not banner, auto-copy or write the pasteboard.
        var lateThreshold: TimeInterval = 120
        /// Late notifications at least this many in one burst post a single summary.
        var lateSummaryMinimum = 4
        /// A burst of late notifications is over after this long without another.
        var lateSummaryDelay: Duration = .seconds(2)
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
    /// Why the login item toggle didn't take, if it didn't.
    private(set) var loginItemMessage: String?
    /// Battery reports (`device.status@1`) by device id.
    private(set) var deviceStatus: [String: DeviceStatus] = [:]
    /// Phone media sessions (`media.state@1`), newest first. A stopped session is removed.
    private(set) var mediaSessions: [MediaSession] = []
    var windowRequest: WindowRequest?

    // MARK: Dependencies

    @ObservationIgnored let sync: any SyncService
    @ObservationIgnored private let presenter: any NotificationPresenting
    /// Cards on the rich panel and native notifications (spec §6.1).
    @ObservationIgnored let notifications: NotificationCoordinator
    @ObservationIgnored private let focus: any FocusStatusProviding
    @ObservationIgnored private let clipboard: any ClipboardWatching
    @ObservationIgnored private let hotkey: any HotkeyRegistering
    @ObservationIgnored private let loginItem: any LoginItemControlling
    /// Decrypted images of notifications and media, shared by every card.
    @ObservationIgnored let blobs: BlobStore
    @ObservationIgnored private let liveChip: LiveChip
    @ObservationIgnored private let options: Options
    @ObservationIgnored private let log = Logger(subsystem: "com.mtajchert.carom.mac", category: "app")

    @ObservationIgnored private var socket: HubSocket?
    @ObservationIgnored private var monitor: SystemMonitor?
    @ObservationIgnored private var realtimeTask: Task<Void, Never>?
    @ObservationIgnored private var resyncRequested = false
    @ObservationIgnored private var terminationObserver: NSObjectProtocol?
    @ObservationIgnored private var outboxTask: Task<Void, Never>?
    @ObservationIgnored private var unreachableTask: Task<Void, Never>?
    @ObservationIgnored private var pairingTask: Task<Void, Never>?
    @ObservationIgnored private var statusTask: Task<Void, Never>?
    @ObservationIgnored private var suppressCopiesUntil = Date.distantPast
    @ObservationIgnored private var watcherRunning = false
    @ObservationIgnored private var sawNetworkPath = false
    @ObservationIgnored private var liveUpdates: [(id: String, posted: NotificationPosted)] = []
    @ObservationIgnored private var codeClearTask: Task<Void, Never>?
    @ObservationIgnored private var lateCount = 0
    @ObservationIgnored private var lateSummaryTask: Task<Void, Never>?
    @ObservationIgnored private var codeClearCount: Int?
    @ObservationIgnored private var lastAutoCopied: String?
    /// Hub time minus local time while the clock warning is up; nil otherwise.
    @ObservationIgnored private var clockDrift: Int?

    init(sync: any SyncService, desktop: DesktopServices, options: Options = Options()) {
        self.sync = sync
        presenter = desktop.presenter
        clipboard = desktop.clipboard
        hotkey = desktop.hotkey
        loginItem = desktop.loginItem
        focus = desktop.focus
        notifications = NotificationCoordinator(native: desktop.presenter, panel: desktop.panel, focus: desktop.focus,
                                                options: options.notifications)
        self.options = options
        blobs = BlobStore(directory: options.blobDirectory) { [sync] ref in await sync.fetchBlob(ref) }
        liveChip = LiveChip(host: desktop.liveChipHost)
        notifications.images = blobs
        options.clockSkew?.onChange = { [weak self] drift in
            Task { @MainActor in self?.clockDriftChanged(drift) }
        }
    }

    /// The production graph: Keychain secrets, a JSON state file in Application Support and the
    /// URLSession Hub client.
    static func live(launch: LaunchOptions = .current) -> AppModel {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
        var config = SyncConfiguration(deviceName: defaultDeviceName)
        config.appVersion = version
        let folder = launch.appFolder
        let state: any StateStore = (try? JSONFileStateStore.applicationSupport(appFolder: folder))
            ?? JSONFileStateStore(url: URL.applicationSupportDirectory.appending(path: "\(folder)/state.json"))
        let clockSkew = ClockSkewTracker()
        let engine = SyncEngine(configuration: config, secrets: SecretStoreFactory.make(service: launch.keychainService),
                                state: state, hub: HubClient.factory(clockSkew: clockSkew))
        var options = Options()
        options.clockSkew = clockSkew
        options.autoApprove = launch.autoApprove
        options.blobDirectory = URL.applicationSupportDirectory.appending(path: "\(folder)/blobs")
        if let path = launch.eventLog {
            let log = DebugEventLog(path: path)
            options.trace = { log.write($0) }
        }
        return AppModel(sync: engine, desktop: .live(), options: options)
    }

    /// Debug event trace: names and types only, never content.
    func trace(_ line: String) { options.trace?(line) }

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

    /// The Now Playing cards to show: none while paused.
    var nowPlaying: [MediaSession] { preferences.paused ? [] : mediaSessions }

    /// Whether the phone of a session advertises `media.command@1` (SPEC §3.6).
    func canControlMedia(_ session: MediaSession) -> Bool {
        directory.first { $0.record.id == session.phoneId }?.record.caps
            .contains(MessageType.mediaCommand.rawValue) ?? false
    }

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
        wireNotifications()
        observeRealtime()
        if options.systemHooks {
            terminationObserver = NotificationCenter.default.addObserver(
                forName: NSApplication.willTerminateNotification, object: nil, queue: .main
            ) { [weak self] _ in
                MainActor.assumeIsolated { self?.shutdown() }
            }
        }
        launchAtLogin = loginItem.isEnabled
        pasteboardAccess = clipboard.accessBehavior
        notificationsAllowed = await presenter.authorizationState()
        trace("notifications allowed=\(notificationsAllowed.map { String($0) } ?? "undecided")")
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
        trace("phase ready")
        await startRealtime()
        if options.systemHooks, monitor == nil {
            monitor = SystemMonitor(onSleep: { [weak self] in self?.systemWillSleep() },
                                    onWake: { [weak self] in self?.systemDidWake() },
                                    onNetwork: { [weak self] up in self?.networkChanged(up) })
        }
        registerHotkey()
        updateWatcher()
        startOutboxTimer()
        if preferences.showAs == .rich {
            let focus = self.focus
            Task { await focus.requestAuthorization() }
        }
    }

    /// Stops everything that talks to the Hub or the system (on revoke and reset).
    private func stopServices() async {
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
            notifications.setStyle(preferences.showAs)
            applyLiveChipSetting()
        }
        applyDirectory(await sync.directory)
    }

    /// Applies a new directory, withdraws notifications of removed phones and refreshes each
    /// phone's action cap on the cards it already sent.
    ///
    /// Receive lists and the "Send to" list are left alone: `"*"` (or no list) already includes
    /// devices paired later, and a list of ids is an explicit choice, so a new device is not
    /// added to it and shows as unchecked (docs/CLIENTS.md, receive lists and new devices).
    private func applyDirectory(_ newDirectory: [DirectoryEntry]) {
        let newIds = Set(newDirectory.map(\.record.id))
        for gone in phones where !newIds.contains(gone.record.id) {
            notifications.removedAll(phoneId: gone.record.id)
            activity.removeNotifications(phoneId: gone.record.id)
            forget(phoneId: gone.record.id)
        }
        directory = newDirectory
        deviceStatus = deviceStatus.filter { newIds.contains($0.key) }
        // A phone may have gained or lost `notification.action@1` (an update, or Actions from Mac
        // turned on or off): its existing cards show or hide their buttons to match.
        for entry in newDirectory {
            notifications.setPhoneAcceptsActions(Routing.advertises(entry.record, .notificationAction),
                                                 phoneId: entry.record.id)
        }
    }

    // MARK: Realtime connection

    private func startRealtime() async {
        socket = await sync.openRealtime()
        connection = socket == nil ? .offline : .connecting
        if socket != nil { armUnreachable() }
    }

    /// Follows the engine's realtime state for the lifetime of the model.
    private func observeRealtime() {
        guard realtimeTask == nil else { return }
        let states = sync.realtimeStates()
        realtimeTask = Task { [weak self] in
            for await state in states { self?.realtimeChanged(state) }
        }
    }

    /// The engine's socket state, combined with what the app knows (sleep, no network, 401).
    func realtimeChanged(_ state: RealtimeState) {
        trace("realtime \(state)")
        guard phase == .ready, connection != .stopped else { return }
        switch state {
        case .connected:
            connection = .connected
            requestResyncOnce()
            unreachableTask?.cancel()
            unreachableTask = nil
            dismissBanner(kind: .hubUnreachable)
        case .connecting:
            // Asleep and no network are reported by the system monitor; keep them.
            guard connection == .connected || connection == .connecting || connection == .offline else { return }
            connection = .connecting
            armUnreachable()
        case .offline:
            break  // stopRealtime, revoke and reset set the state themselves
        }
    }

    /// Asks the phones to re-send what they show, once per launch, after the first connection.
    private func requestResyncOnce() {
        guard !resyncRequested, !preferences.paused else { return }
        resyncRequested = true
        let sync = self.sync
        Task {
            do { _ = try await sync.send(NotificationResync(), replyTo: nil, only: nil) } catch {
                log.error("resync request failed")
            }
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
        // The path observer reports the current state once at start. A usable network then is
        // not news: forwarding it would make the socket drop the connection it just opened.
        let first = !sawNetworkPath
        sawNetworkPath = true
        if first, up { return }
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
        trace("message \(message.message.type.rawValue)")
        await dispatch(message.message, msgId: message.msgId, sender: message.sender,
                       replyTo: message.received.payload.replyTo,
                       sentAt: Date(timeIntervalSince1970: Double(message.received.payload.ts) / 1000))
    }

    /// Applies one decoded message from a trusted sender. `replyTo` is the payload's `reply_to`;
    /// `sentAt` is its `ts` (nil: not late).
    func dispatch(_ message: Message, msgId: String, sender: DeviceRecord, replyTo: String? = nil,
                  sentAt: Date? = nil) async {
        let isLate = sentAt.map { options.now().timeIntervalSince($0) > options.lateThreshold } ?? false
        switch message {
        case .notificationPosted(let n):
            if preferences.noteApp(id: n.app.id, name: n.app.name) { savePreferences() }
            guard !preferences.paused, receives(.notifications, from: sender.id), !preferences.isMuted(n.app.id) else {
                return
            }
            // A redacted notification without text says so instead of showing an empty card: Android
            // 15+ hid the content ("Sensitive notification content hidden"), or the app is set to
            // Hide content on the phone, which sends `redacted: true` and no text (SPEC §4.1).
            let text = n.redacted && (n.text ?? "").isEmpty ? UserMessages.contentHiddenOnPhone : n.text
            let code = CodeExtractor.code(in: n)
            let recent = RecentNotification(phoneId: sender.id, phoneName: sender.name, appId: n.app.id,
                                            appName: n.app.name, title: n.title, text: text, key: n.key,
                                            msgId: msgId, code: code, date: postedDate(n.postedAt), icon: n.app.icon)
            if n.resync == true {
                // A copy re-sent after a restart: menu list and Live Updates chip only, no alert.
                guard activity.addResynced(recent) else { return }
                await blobs.noteIcons([n.app.icon, n.smallIcon].compactMap { $0 })
                trackLive(phoneId: sender.id, posted: n)
                trace("notification resynced")
                return
            }
            activity.add(recent)
            await blobs.noteIcons([n.app.icon, n.smallIcon].compactMap { $0 })
            trackLive(phoneId: sender.id, posted: n)
            if !isLate { autoCopy(code, phoneId: sender.id, key: n.key) }
            var shown = n
            shown.text = text
            let card = CardItem(phoneId: sender.id, phoneName: phones.count > 1 ? sender.name : nil, msgId: msgId,
                                posted: shown, receivedAt: options.now(),
                                phoneAcceptsActions: Routing.advertises(sender, .notificationAction))
            await notifications.posted(card, quiet: isLate)
            if isLate {
                noteLate()
                trace("notification late")
            } else {
                trace("notification presented")
            }
        case .notificationRemoved(let r):
            notifications.removed(phoneId: sender.id, key: r.key)
            activity.removeNotification(phoneId: sender.id, key: r.key)
            untrackLive(phoneId: sender.id, key: r.key)
            trace("notification withdrawn")
        case .clipboardSet(let c):
            guard !preferences.paused, receives(.clipboard, from: sender.id) else { return }
            // A late item would overwrite what the user has copied since; it stays in the history.
            if isLate {
                trace("clipboard late")
            } else {
                clipboard.write(text: c.text, sensitive: c.sensitive)
                trace("clipboard written")
            }
            activity.add(RecentClip(direction: .received(from: sender.name), text: c.text, sensitive: c.sensitive,
                                    date: min(sentAt ?? options.now(), options.now())))
        case .deviceStatus(let s):
            deviceStatus[sender.id] = s
        case .notificationDismiss:
            break  // a desktop does not post notifications
        case .notificationActionResult(let result):
            guard let replyTo else { return }
            notifications.actionResult(replyTo: replyTo, result)
            trace("action result")
        case .mediaState(let m):
            applyMedia(m, msgId: msgId, sender: sender)
        case .notificationAction, .mediaCommand, .notificationResync:
            break  // a desktop does not receive these
        }
    }

    /// The phone's `posted_at` (ms) as a date: now when it is missing, never in the future.
    private func postedDate(_ postedAt: Int) -> Date {
        let now = options.now()
        guard postedAt > 0 else { return now }
        return min(Date(timeIntervalSince1970: Double(postedAt) / 1000), now)
    }

    /// Counts a late notification; once the burst settles, a big one gets a single summary.
    private func noteLate() {
        lateCount += 1
        lateSummaryTask?.cancel()
        let delay = options.lateSummaryDelay
        lateSummaryTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self else { return }
            let count = self.lateCount
            self.lateCount = 0
            self.lateSummaryTask = nil
            guard count >= self.options.lateSummaryMinimum else { return }
            await self.presenter.notify(title: "Carom", body: UserMessages.awayNotifications(count),
                                        identifier: "carom.late")
        }
    }

    // MARK: One-time codes

    /// "Copy codes automatically": copies a code that just arrived, marked as concealed, and
    /// clears it again after `codeClearDelay` unless something else was copied since.
    private func autoCopy(_ code: String?, phoneId: String, key: String) {
        guard let code, preferences.copiesCodes else { return }
        let marker = "\(phoneId)/\(key)/\(code)"
        guard marker != lastAutoCopied else { return }  // an update of the same notification
        lastAutoCopied = marker
        clipboard.write(text: code, sensitive: true)
        suppressCopiesUntil = options.now().addingTimeInterval(options.copySuppression)
        let count = clipboard.changeCount
        codeClearCount = count
        codeClearTask?.cancel()
        let delay = options.codeClearDelay
        codeClearTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self else { return }
            _ = self.clipboard.clear(ifChangeCount: count)
            self.codeClearCount = nil
            self.codeClearTask = nil
        }
    }

    // MARK: Media

    /// Keeps the latest `media.state` per phone and session. `stopped` removes the session.
    private func applyMedia(_ m: MediaState, msgId: String, sender: DeviceRecord) {
        let session = MediaSession(phoneId: sender.id, phoneName: sender.name, msgId: msgId, state: m,
                                   receivedAt: options.now())
        if m.state == "stopped" {
            mediaSessions.removeAll { $0.id == session.id }
            return
        }
        guard !preferences.paused, receives(.notifications, from: sender.id), !preferences.isMuted(m.app.id) else {
            // A state we will not show still makes the one we hold stale: drop it rather than keep
            // a card that says playing, extrapolates its position and replies to an old msg_id.
            mediaSessions.removeAll { $0.id == session.id }
            return
        }
        if let i = mediaSessions.firstIndex(where: { $0.id == session.id }) {
            mediaSessions[i] = session
        } else {
            mediaSessions.insert(session, at: 0)
        }
    }

    /// Sends a Now Playing button press to the phone. `reply_to` is the session's latest
    /// `media.state`, looked up now, because states keep arriving while the popover is open.
    func sendMedia(_ control: MediaControl, sessionID id: String) async {
        guard let session = mediaSessions.first(where: { $0.id == id }), canControlMedia(session),
              session.offers(control.command) else { return }
        let command = MediaCommand(session: session.state.session, command: control.command,
                                   positionMs: control.positionMs)
        do {
            if case .dropped(let code) = try await sync.send(command, replyTo: session.msgId, only: [session.phoneId]) {
                showStatus(UserMessages.sendDropped(code: code))
            }
        } catch {
            showStatus(UserMessages.describe(error))
        }
    }

    // MARK: Live Updates

    /// Keeps the menu-bar chip in step with the promoted Live Updates (newest first).
    private func trackLive(phoneId: String, posted n: NotificationPosted) {
        let id = "\(phoneId)/\(n.key)"
        liveUpdates.removeAll { $0.id == id }
        if n.live?.promoted == true { liveUpdates.insert((id: id, posted: n), at: 0) }
        updateLiveChip()
    }

    private func untrackLive(phoneId: String, key: String) {
        let id = "\(phoneId)/\(key)"
        liveUpdates.removeAll { $0.id == id }
        updateLiveChip()
    }

    private func updateLiveChip() { liveChip.update(liveUpdates.map(\.posted)) }

    private func applyLiveChipSetting() { liveChip.enabled = preferences.showsLiveChip && !preferences.paused }

    /// A phone left: what it showed goes with it.
    private func forget(phoneId: String) {
        mediaSessions.removeAll { $0.phoneId == phoneId }
        liveUpdates.removeAll { $0.id.hasPrefix(phoneId + "/") }
        updateLiveChip()
    }

    /// Engine events (SyncHandler).
    func handle(event: SyncEvent) async {
        trace("event \(Self.traceName(event))")
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

    func perform(_ action: NativeNotificationEvent) async {
        switch action {
        case .dismissOnPhone(_, let key, let msgId):
            _ = try? await sync.send(NotificationDismiss(key: key), replyTo: msgId, only: nil)
        case .copiedCode, .copiedText:
            noteOwnCopy()
        case .reply(let phoneId, let key, let msgId, let index, let title, let text):
            let action = CardAction.reply(index: index, title: title, text: text)
            if !notifications.perform(action, on: CardItem.id(phoneId: phoneId, key: key)) {
                // The card is gone from memory (Carom restarted): send without tracking a result.
                let body = CaromProtocol.NotificationAction(
                    key: key, action: .button(index: index, title: title, replyText: text))
                _ = try? await sync.send(body, replyTo: msgId, only: nil)
            }
        }
    }

    func dismissOnPhone(_ n: RecentNotification) async {
        await perform(.dismissOnPhone(phoneId: n.phoneId, key: n.key, msgId: n.msgId))
        notifications.removed(phoneId: n.phoneId, key: n.key)
    }

    /// Sends a card's `notification.action@1` as a reply to the card's latest posted message.
    func sendAction(_ ref: ActionRef, for item: CardItem) async -> ActionDelivery {
        let body = CaromProtocol.NotificationAction(key: item.posted.key, action: ref)
        let sent: (msgId: String?, outcome: SendOutcome)
        do { sent = try await sync.sendReturningMsgId(body, replyTo: item.msgId, only: nil) } catch {
            return .failed
        }
        trace("action send \(Self.traceName(sent.outcome))")
        switch sent.outcome {
        case .sent, .queued:
            guard let msgId = sent.msgId else { return .failed }
            return .accepted(msgId: msgId)
        case .superseded, .noRecipients, .suppressedEcho, .dropped:
            return .failed
        }
    }

    private func wireNotifications() {
        notifications.sendAction = { [weak self] item, ref in
            guard let self else { return .failed }
            return await self.sendAction(ref, for: item)
        }
        notifications.sendDismiss = { [weak self] item in
            await self?.perform(.dismissOnPhone(phoneId: item.phoneId, key: item.posted.key, msgId: item.msgId))
        }
        notifications.copy = { [weak self] code in
            guard let self else { return }
            // Written with the watcher's marker, and not sent straight back to the phone.
            self.clipboard.write(text: code, sensitive: false)
            self.noteOwnCopy()
        }
    }

    /// Our own copy of a notification's code or text: automatic sending pauses briefly, so it is
    /// not sent straight back to the phone.
    private func noteOwnCopy() {
        suppressCopiesUntil = options.now().addingTimeInterval(options.copySuppression)
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
        trace("clipboard send requested")
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
        trace("clipboard send \(Self.traceName(outcome))")
        let recipients = targets.filter { only?.contains($0.record.id) ?? true }
        let names = recipients.map(\.record.name)
        let onlyPhones = !recipients.isEmpty && recipients.allSatisfy { $0.record.kind == .phone }
        switch outcome {
        case .sent, .superseded:
            activity.add(RecentClip(direction: .sent(to: names), text: item.text, sensitive: item.sensitive,
                                    date: options.now()))
            showStatus(UserMessages.sent(to: names, onlyPhones: onlyPhones))
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
        applyLiveChipSetting()
    }

    func setLiveChip(_ on: Bool) {
        preferences.liveChip = on
        savePreferences()
        applyLiveChipSetting()
    }

    func setAutoCopyCodes(_ on: Bool) {
        preferences.autoCopyCodes = on
        savePreferences()
    }

    func setAutomaticClipboard(_ on: Bool) {
        preferences.automaticClipboard = on
        savePreferences()
        updateWatcher()
    }

    func setMuted(_ appId: String, _ muted: Bool) {
        preferences.setMuted(appId, muted)
        savePreferences()
        if muted {
            mediaSessions.removeAll { $0.state.app.id == appId }
            // Muted posts never reach trackLive, so the chip would keep its last text.
            liveUpdates.removeAll { $0.posted.app.id == appId }
            updateLiveChip()
        }
    }

    func setNotificationStyle(_ style: NotificationStyle) {
        preferences.notificationStyle = style
        savePreferences()
        notifications.setStyle(style)
        if style == .rich {
            let focus = self.focus
            Task { await focus.requestAuthorization() }
        }
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
            log.error("login item change failed")
            loginItemMessage = UserMessages.loginItemFailed
        }
        refreshLoginItem()
        if on, loginItem.requiresApproval { loginItemMessage = UserMessages.loginItemNeedsApproval }
    }

    /// Re-reads the login item (the user may change it in System Settings).
    func refreshLoginItem() {
        launchAtLogin = loginItem.isEnabled
        if launchAtLogin { loginItemMessage = nil }
    }

    /// Called when the app quits: releases the global hotkey and stops the watcher and socket.
    func shutdown() {
        codeClearTask?.cancel()
        if let count = codeClearCount { _ = clipboard.clear(ifChangeCount: count) }
        hotkey.unregister()
        trace("shutdown hotkey unregistered")
        hotkeyAvailable = false
        clipboard.stop()
        watcherRunning = false
        if let terminationObserver { NotificationCenter.default.removeObserver(terminationObserver) }
        terminationObserver = nil
    }

    /// After a 401: erase this Mac's keys and start onboarding again.
    func resetAndPairAgain() async {
        await stopServices()
        await sync.wipe()
        resetLocalState()
        banners = []
        if let clockDrift { showClockBanner(clockDrift) }
        phase = .onboarding
        request(.onboarding)
    }

    private func resetLocalState() {
        deviceId = nil
        hubURL = nil
        directory = []
        receiveRules = [:]
        preferences = Preferences()
        applyLiveChipSetting()
        activity.clear()
        notifications.reset()
        mediaSessions = []
        liveUpdates = []
        lastAutoCopied = nil
        updateLiveChip()
        let blobs = self.blobs
        Task { await blobs.clear() }
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

    /// Onboarding: one user-initiated read of the clipboard. On macOS 15.4+ this makes Carom
    /// appear in System Settings › Privacy & Security › Paste from Other Apps (and may show the
    /// system prompt), so the user can choose Always Allow. Nothing is sent or logged.
    func checkClipboardAccess() {
        _ = clipboard.readCurrent()
        refreshPasteboardAccess()
    }

    func finishOnboarding() async {
        preferences.onboardingComplete = true
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
        trace("pairing candidate")
        request(.addDevice)
        if options.autoApprove { Task { await approveCandidate() } }
    }

    func approveCandidate() async {
        guard case .confirming(_, let candidate) = pairing else { return }
        pairing = .approving(candidate)
        do {
            try await sync.approvePairing(pairId: candidate.pairId)
            pairing = .added(fingerprint: candidate.fingerprint)
            trace("pairing approved")
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

    // MARK: Clock check

    /// The Hub clients saw this Mac's clock drift from the Hub's (nil: it is fine again). The banner
    /// stays while it lasts; one system notice is posted per episode, not per response.
    func clockDriftChanged(_ driftMs: Int?) {
        let wasOff = clockDrift != nil
        clockDrift = driftMs
        guard let driftMs else {
            dismissBanner(kind: .clockSkew)
            return
        }
        showClockBanner(driftMs)
        guard !wasOff else { return }
        trace("clock drift")
        let message = UserMessages.clockOff(skewMs: driftMs)
        let presenter = self.presenter
        Task { await presenter.notify(title: "Check this Mac's date and time", body: message, identifier: "carom.clock") }
    }

    private func showClockBanner(_ driftMs: Int) {
        addBanner(Banner(kind: .clockSkew, message: UserMessages.clockOff(skewMs: driftMs), style: .warning))
    }

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

extension AppModel {
    static func traceName(_ outcome: SendOutcome) -> String {
        switch outcome {
        case .sent: "sent"
        case .superseded: "superseded"
        case .queued: "queued"
        case .noRecipients: "noRecipients"
        case .suppressedEcho: "suppressedEcho"
        case .dropped(let code): "dropped \(code)"
        }
    }

    /// The event's case name, without its values (they can carry device names).
    static func traceName(_ event: SyncEvent) -> String {
        switch event {
        case .directoryChanged: "directoryChanged"
        case .deviceRemoved: "deviceRemoved"
        case .keyRotated(let kv): "keyRotated kv=\(kv)"
        case .pairingCandidate: "pairingCandidate"
        case .pairingRejected(_, let reason): "pairingRejected \(reason.rawValue)"
        case .messageDropped(let e): "messageDropped \(e.rawValue)"
        case .sendDropped(let code): "sendDropped \(code)"
        case .revoked: "revoked"
        case .tokenRejected: "tokenRejected"
        }
    }
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
