import AppKit
import CaromProtocol
import Observation

/// The native notification side (`NotificationPresenter`; a fake in tests).
@MainActor
public protocol NativeNotifying: AnyObject {
    func present(_ notification: PresentedNotification, showPhoneName: Bool) async
    func withdraw(phoneId: String, key: String)
    func withdrawAll(phoneId: String)
}

/// The rich panel window (`RichPanelPresenter`; a fake in tests).
@MainActor
public protocol CardPanelHosting: AnyObject {
    /// Gives the panel its content source. The coordinator calls it once, from its init.
    func bind(_ coordinator: NotificationCoordinator)
    /// Orders the panel front at the top right of the screen under the pointer. Never makes it key.
    func show()
    func hide()
    /// True while the user types a reply: only then may the panel become key.
    func setReplyMode(_ on: Bool)
    /// A new card: plays the sound (when `sound`) and posts a VoiceOver announcement.
    func alert(announcement: String, sound: Bool)
}

/// Whether a Focus (Do Not Disturb and the like) is on.
@MainActor
public protocol FocusStatusProviding: AnyObject {
    var isFocused: Bool { get }
    func requestAuthorization() async
}

extension NotificationPresenter: NativeNotifying {}

extension PresentedNotification {
    /// The native copy of a card.
    public init(card: CardItem, passive: Bool) {
        self.init(phoneId: card.phoneId, phoneName: card.phoneName ?? "", appId: card.posted.app.id,
                  appName: card.posted.app.name, title: card.posted.title, text: card.posted.text,
                  key: card.posted.key, msgId: card.msgId, passive: passive,
                  reply: card.replyAction.map { ReplyTarget(index: $0.index, title: $0.title) })
    }
}

/// Routes phone notifications to the rich panel and to native notifications (spec §6.1).
///
/// Keeps every card until the phone removes it, which cards are on the panel now, and the
/// status of the last action sent from each card. Never logs card content.
@MainActor
@Observable
public final class NotificationCoordinator {
    public struct Options: Sendable {
        /// A card leaves the panel this long after it was shown, unless the pointer is over the panel.
        public var fadeAfter: Duration = .seconds(6)
        /// "Phone is asleep" after this long without a result.
        public var asleepAfter: Duration = .seconds(30)
        /// Stacks on the panel; the rest are summed up as "+N more".
        public var maxVisible = 4
        public init() {}
    }

    public private(set) var style: NotificationStyle = .rich
    /// Every card the phones still have, by card id.
    public private(set) var cards: [String: CardItem] = [:]
    /// Card ids on the panel now.
    public private(set) var onPanel: Set<String> = []
    /// When each card on the panel was last shown (orders the stacks).
    public private(set) var shownAt: [String: Date] = [:]
    public private(set) var statuses: [String: ActionStatus] = [:]
    /// The card whose reply field is open. The panel may be key only then.
    public private(set) var replyingTo: String?
    public private(set) var isHovering = false

    /// Where card images come from (M4b's BlobStore; placeholders until then).
    @ObservationIgnored public var images: any ImageProvider = NoImageProvider()

    @ObservationIgnored private let native: any NativeNotifying
    @ObservationIgnored private let panel: any CardPanelHosting
    @ObservationIgnored private let focus: any FocusStatusProviding
    @ObservationIgnored private let options: Options
    @ObservationIgnored private let now: @MainActor () -> Date
    /// Waits for the fade and asleep timers. Returns early when the task is cancelled.
    @ObservationIgnored private let sleep: @Sendable (Duration) async -> Void
    @ObservationIgnored private var fadeTasks: [String: Task<Void, Never>] = [:]
    /// Cards the user acted on: they fade even if they would otherwise stay.
    @ObservationIgnored private var handled: Set<String> = []
    /// Sends a `notification.action@1` for a card. Set by the app.
    @ObservationIgnored public var sendAction: (@MainActor (CardItem, ActionRef) async -> ActionDelivery)?
    /// Sends a `notification.dismiss@1` for a card. Set by the app.
    @ObservationIgnored public var sendDismiss: (@MainActor (CardItem) async -> Void)?
    /// Writes a code to the clipboard without syncing it back. Set by the app.
    @ObservationIgnored public var copy: (@MainActor (String) -> Void)?
    @ObservationIgnored private var asleepTasks: [String: Task<Void, Never>] = [:]
    /// Action msg_id → card id, until the phone's result arrives.
    @ObservationIgnored private var pendingActions: [String: String] = [:]
    /// Results that arrived before their send returned its msg_id (newest last, at most 16).
    @ObservationIgnored private var earlyResults: [(msgId: String, result: NotificationActionResult)] = []

    public init(native: any NativeNotifying, panel: any CardPanelHosting, focus: any FocusStatusProviding,
                options: Options = Options(), now: @escaping @MainActor () -> Date = { Date() },
                sleep: @escaping @Sendable (Duration) async -> Void = { try? await Task.sleep(for: $0) }) {
        self.native = native
        self.panel = panel
        self.focus = focus
        self.options = options
        self.now = now
        self.sleep = sleep
        panel.bind(self)
    }

    // MARK: Derived state for the panel

    public var stacks: [CardStack] { CardStacks.build(onPanel.compactMap { cards[$0] }, shownAt: shownAt) }
    public var visibleStacks: [CardStack] { Array(stacks.prefix(options.maxVisible)) }
    public var hiddenStackCount: Int { max(0, stacks.count - options.maxVisible) }
    public func status(for cardId: String) -> ActionStatus { statuses[cardId] ?? .idle }

    // MARK: Phone events

    /// A `notification.posted` (new, or an update of the same key).
    public func posted(_ item: CardItem) async {
        let isUpdate = cards[item.id] != nil
        cards[item.id] = item
        // Messaging apps re-post a conversation with the user's own reply appended (no sender).
        // That changes the card in place, silently, and keeps it handled.
        let ownReply = isUpdate && Self.endsWithOwnMessage(item)
        if !ownReply {
            handled.remove(item.id)
            if isUpdate { clearFinishedStatus(item.id) }
            if style == .rich { place(item, isUpdate: isUpdate) }
        }
        await native.present(PresentedNotification(card: item, passive: style == .rich || ownReply),
                             showPhoneName: item.phoneName != nil)
    }

    /// The newest message is the phone user's own (it has no sender).
    static func endsWithOwnMessage(_ item: CardItem) -> Bool {
        guard let last = item.posted.messages?.last else { return false }
        return last.sender == nil
    }

    /// A new message replaces the last action's result; an action still in flight keeps its status.
    private func clearFinishedStatus(_ cardId: String) {
        switch statuses[cardId] {
        case .sending?, .asleep?, nil: break
        default: statuses[cardId] = nil
        }
    }

    /// A `notification.removed`: the card and the native notification go.
    public func removed(phoneId: String, key: String) {
        native.withdraw(phoneId: phoneId, key: key)
        forget(CardItem.id(phoneId: phoneId, key: key))
    }

    /// A phone left the directory.
    public func removedAll(phoneId: String) {
        native.withdrawAll(phoneId: phoneId)
        for id in cards.values.filter({ $0.phoneId == phoneId }).map(\.id) { forget(id) }
    }

    /// The card's close button: off the panel, nothing sent to the phone.
    public func close(_ cardId: String) { takeOffPanel(cardId) }

    public func setStyle(_ newStyle: NotificationStyle) {
        style = newStyle
        if newStyle == .nativeOnly {
            for id in onPanel { takeOffPanel(id) }
        }
    }

    /// Forgets every card (this Mac was reset or removed).
    public func reset() {
        for id in Array(cards.keys) { forget(id) }
    }

    public func setHovering(_ hovering: Bool) {
        guard hovering != isHovering else { return }
        isHovering = hovering
        if hovering {
            for task in fadeTasks.values { task.cancel() }
            fadeTasks = [:]
        } else {
            for id in onPanel { scheduleFade(id) }
        }
    }

    // MARK: Reply mode

    /// The user opened a card's reply field: the panel may take the keyboard now.
    public func beginReply(_ cardId: String) {
        guard cards[cardId]?.replyAction != nil, onPanel.contains(cardId) else { return }
        replyingTo = cardId
        fadeTasks.removeValue(forKey: cardId)?.cancel()
        panel.setReplyMode(true)
    }

    public func endReply() {
        guard let id = replyingTo else { return }
        replyingTo = nil
        panel.setReplyMode(false)
        scheduleFade(id)
    }

    // MARK: Actions

    /// Runs a card's action. Phone actions need the phone's `notification.action@1` cap. Returns
    /// false when the card is unknown (for example after a restart).
    @discardableResult
    public func perform(_ action: CardAction, on cardId: String) -> Bool {
        guard let item = cards[cardId] else { return false }
        switch action {
        case .copyCode(let code):
            copy?(code)
            markHandled(cardId)
        case .dismiss:
            let send = sendDismiss
            Task { await send?(item) }
            removed(phoneId: item.phoneId, key: item.posted.key)
        case .tap:
            deliver(.tap, for: item)
        case .button(let index, let title):
            deliver(.button(index: index, title: title, replyText: nil), for: item)
        case .reply(let index, let title, let text):
            if replyingTo == cardId { endReply() }
            deliver(.button(index: index, title: title, replyText: text), for: item)
        case .answer:
            deliver(.answer, for: item)
        case .decline:
            deliver(.decline, for: item)
        }
        return true
    }

    /// The phone's `notification.action_result@1`; `replyTo` is the action's msg_id.
    public func actionResult(replyTo: String, _ result: NotificationActionResult) {
        guard let cardId = pendingActions.removeValue(forKey: replyTo) else {
            earlyResults.append((replyTo, result))
            if earlyResults.count > 16 { earlyResults.removeFirst(earlyResults.count - 16) }
            return
        }
        apply(result, to: cardId)
    }

    private func deliver(_ ref: ActionRef, for item: CardItem) {
        guard item.phoneAcceptsActions, let send = sendAction else { return }
        statuses[item.id] = .sending
        armAsleep(item.id)
        Task { [weak self] in
            let delivery = await send(item, ref)
            self?.delivered(delivery, cardId: item.id)
        }
    }

    private func delivered(_ delivery: ActionDelivery, cardId: String) {
        guard cards[cardId] != nil else { return }
        switch delivery {
        case .accepted(let msgId):
            if let i = earlyResults.firstIndex(where: { $0.msgId == msgId }) {
                let early = earlyResults.remove(at: i)
                apply(early.result, to: cardId)
            } else {
                pendingActions[msgId] = cardId
            }
        case .failed:
            asleepTasks.removeValue(forKey: cardId)?.cancel()
            statuses[cardId] = .failed
            markHandled(cardId)
        }
    }

    private func apply(_ result: NotificationActionResult, to cardId: String) {
        asleepTasks.removeValue(forKey: cardId)?.cancel()
        guard cards[cardId] != nil else { return }
        statuses[cardId] = ActionStatus(result: result)
        markHandled(cardId)
    }

    /// "Phone is asleep — will send when it wakes" when no result came in time. The action stays
    /// queued at the Hub (TTL 10 min), so a later result still updates the status.
    private func armAsleep(_ cardId: String) {
        asleepTasks.removeValue(forKey: cardId)?.cancel()
        let delay = options.asleepAfter, sleep = sleep
        asleepTasks[cardId] = Task { [weak self] in
            await sleep(delay)
            guard !Task.isCancelled, let self else { return }
            self.asleepTasks[cardId] = nil
            if self.statuses[cardId] == .sending { self.statuses[cardId] = .asleep }
        }
    }

    private func markHandled(_ cardId: String) {
        handled.insert(cardId)
        scheduleFade(cardId)
    }

    // MARK: Panel membership

    private func place(_ item: CardItem, isUpdate: Bool) {
        // Live Updates and calls already on the panel change in place, silently.
        if isUpdate, item.isPinned, onPanel.contains(item.id) { return }
        // With a Focus on, only the native notification posts (it follows the Focus).
        if focus.isFocused { return }
        onPanel.insert(item.id)
        shownAt[item.id] = now()
        scheduleFade(item.id)
        panel.show()
        if item.alertsByGroupRule {
            panel.alert(announcement: Self.announcement(for: item), sound: true)
        }
    }

    /// App name and title only; the body text is not read out.
    static func announcement(for item: CardItem) -> String {
        let title = item.posted.conversation?.title ?? item.posted.title
        return [item.posted.app.name, title].compactMap { $0 }.joined(separator: ": ")
    }

    private func scheduleFade(_ id: String) {
        fadeTasks.removeValue(forKey: id)?.cancel()
        guard let item = cards[id], onPanel.contains(id), !isHovering, replyingTo != id else { return }
        if item.staysUntilHandled, !handled.contains(id) { return }
        let delay = options.fadeAfter, sleep = sleep
        fadeTasks[id] = Task { [weak self] in
            await sleep(delay)
            guard !Task.isCancelled else { return }
            self?.takeOffPanel(id)
        }
    }

    private func takeOffPanel(_ id: String) {
        fadeTasks.removeValue(forKey: id)?.cancel()
        guard onPanel.contains(id) else { return }
        onPanel.remove(id)
        shownAt[id] = nil
        if replyingTo == id { endReply() }
        if onPanel.isEmpty {
            // An ordered-out window gets no hover exit, so the hold would otherwise stay on.
            isHovering = false
            panel.hide()
        }
    }

    private func forget(_ id: String) {
        takeOffPanel(id)
        cards[id] = nil
        handled.remove(id)
        statuses[id] = nil
        asleepTasks.removeValue(forKey: id)?.cancel()
        pendingActions = pendingActions.filter { $0.value != id }
    }
}
