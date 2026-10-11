import CaromDesktop
import CaromProtocol
import Foundation

/// Launch arguments for driving the app from scripts in Debug builds. Release builds ignore
/// every argument (`current` is always empty there).
///
///     Carom.app/Contents/MacOS/Carom \
///         -caromProfile e2e                      # separate state folder and Keychain service
///         -caromSetupURI 'carom://setup?…'        # set up a Hub without the onboarding UI
///         -caromInviteFile /path/invite.txt       # create a pair invite, write its URI (0600)
///         -caromAutoApprove YES                   # approve a verified pairing candidate
///         -caromEventLog /path/events.log         # append event types (never content)
///         -caromShowWindows menu,settings         # open windows for screenshots
///         -caromAutoReply 'text'                  # reply on the first card with a reply action
///         -caromAutoMedia pause                   # send this command to the first media session
///
/// The invite file holds a pairing secret: use a private temporary folder and delete it after.
struct LaunchOptions: Equatable {
    var profile: String?
    var setupURI: String?
    var inviteFile: String?
    var autoApprove = false
    var eventLog: String?
    var showWindows: [String] = []
    var autoReply: String?
    var autoMedia: String?

    static var current: LaunchOptions {
        #if DEBUG
        let d = UserDefaults.standard
        let profile = d.string(forKey: "caromProfile").map { $0.filter { $0.isLetter || $0.isNumber || $0 == "-" } }
        return LaunchOptions(profile: profile?.isEmpty == false ? profile : nil,
                             setupURI: d.string(forKey: "caromSetupURI"),
                             inviteFile: d.string(forKey: "caromInviteFile"),
                             autoApprove: d.bool(forKey: "caromAutoApprove"),
                             eventLog: d.string(forKey: "caromEventLog"),
                             showWindows: (d.string(forKey: "caromShowWindows") ?? "")
                                 .split(separator: ",").map { String($0) },
                             autoReply: d.string(forKey: "caromAutoReply"),
                             autoMedia: d.string(forKey: "caromAutoMedia"))
        #else
        return LaunchOptions()
        #endif
    }

    /// Application Support folder name.
    var appFolder: String { profile.map { "Carom-\($0)" } ?? "Carom" }

    /// Keychain service for the secrets.
    var keychainService: String {
        profile.map { "\(SecretStoreFactory.service).\($0)" } ?? SecretStoreFactory.service
    }
}

/// Appends one line per app event to a file. Only event names and message types are written,
/// never notification or clipboard content, keys or tokens.
final class DebugEventLog: Sendable {
    private let url: URL

    init(path: String) {
        url = URL(fileURLWithPath: path)
        FileManager.default.createFile(atPath: path, contents: nil, attributes: [.posixPermissions: 0o600])
    }

    func write(_ line: String) {
        let stamp = ISO8601DateFormatter.string(from: Date(), timeZone: .current,
                                                formatOptions: [.withInternetDateTime, .withFractionalSeconds])
        guard let data = "\(stamp) \(line)\n".data(using: .utf8),
              let handle = try? FileHandle(forWritingTo: url) else { return }
        defer { try? handle.close() }
        _ = try? handle.seekToEnd()
        try? handle.write(contentsOf: data)
    }
}

@MainActor
enum DebugLaunch {
    /// Runs the scripted steps after `AppModel.start()`.
    static func run(_ model: AppModel, _ options: LaunchOptions) async {
        #if DEBUG
        if let uri = options.setupURI, model.phase == .onboarding, model.deviceId == nil {
            do {
                try await model.setUp(uri: uri)
                await model.finishOnboarding()
                model.trace("debug setup done")
            } catch {
                model.trace("debug setup failed")
            }
        }
        if let path = options.inviteFile, model.phase == .ready {
            await model.startPairing()
            if case .showing(let invite) = model.pairing {
                FileManager.default.createFile(atPath: path, contents: Data(invite.uri.utf8),
                                               attributes: [.posixPermissions: 0o600])
                model.trace("debug invite written")
            } else {
                model.trace("debug invite failed")
            }
        }
        for name in options.showWindows {
            switch name {
            case "menu": model.request(.debugMenu)
            case "settings": model.request(.settings)
            case "onboarding": model.request(.onboarding)
            case "add-device": model.request(.addDevice)
            default: continue
            }
            // Let the menu-bar label open one window before the next request replaces it.
            try? await Task.sleep(for: .milliseconds(400))
        }
        if options.autoReply != nil || options.autoMedia != nil {
            Task { await watch(model, options) }
        }
        #endif
    }

    #if DEBUG
    /// Polls the cards and media sessions for three minutes and drives them like a user
    /// would: loads every image of the first card that offers a reply (the BlobStore path the
    /// views use), replies on it, and sends `autoMedia` to the first session that offers it. Also
    /// notes progress cards and their updates. Traces carry no content.
    private static func watch(_ model: AppModel, _ options: LaunchOptions) async {
        var replied = options.autoReply == nil
        var mediaSent = options.autoMedia == nil
        var repliedCard: String?
        var statusTraced = false
        var progressMsgIds: [String: String] = [:]
        let deadline = Date().addingTimeInterval(180)
        while Date() < deadline {
            for item in model.notifications.cards.values where item.posted.progress != nil {
                switch progressMsgIds[item.id] {
                case nil: model.trace("debug progress card shown")
                case let seen? where seen != item.msgId: model.trace("debug progress card updated")
                default: break
                }
                progressMsgIds[item.id] = item.msgId
            }
            if !replied, let text = options.autoReply,
               let item = model.notifications.cards.values.first(where: { card in
                   card.phoneAcceptsActions && card.posted.actions?.contains { $0.reply != nil } == true
               }),
               let action = item.posted.actions?.first(where: { $0.reply != nil }) {
                replied = true
                let refs = imageRefs(item.posted)
                var loaded = 0
                for ref in refs where await model.blobs.image(for: ref) != nil { loaded += 1 }
                model.trace("debug images loaded \(loaded)/\(refs.count)")
                model.notifications.perform(.reply(index: action.index, title: action.title, text: text), on: item.id)
                model.trace("debug reply performed")
                repliedCard = item.id
            }
            if let card = repliedCard, !statusTraced, model.notifications.status(for: card) != .sending,
               model.notifications.status(for: card) != .idle {
                statusTraced = true
                model.trace("debug action status \(model.notifications.status(for: card))")
            }
            if !mediaSent, let command = options.autoMedia,
               let session = model.mediaSessions.first(where: { $0.offers(command) && model.canControlMedia($0) }),
               let control = mediaControl(command) {
                mediaSent = true
                await model.sendMedia(control, sessionID: session.id)
                model.trace("debug media command \(command)")
            }
            try? await Task.sleep(for: .milliseconds(250))
        }
        if !replied || !mediaSent || (repliedCard != nil && !statusTraced) { model.trace("debug watch timed out") }
    }

    private static func imageRefs(_ n: NotificationPosted) -> [BlobRef] {
        var refs = [n.app.icon, n.smallIcon, n.largeIcon, n.picture, n.conversation?.icon].compactMap { $0 }
        for m in n.messages ?? [] { refs += [m.sender?.icon, m.image].compactMap { $0 } }
        var seen = Set<String>()
        return refs.filter { seen.insert($0.id).inserted }
    }

    private static func mediaControl(_ command: String) -> MediaControl? {
        switch command {
        case "play": .play
        case "pause": .pause
        case "next": .next
        case "previous": .previous
        default: nil
        }
    }
    #endif
}
