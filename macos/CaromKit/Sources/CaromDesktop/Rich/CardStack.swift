import Foundation

/// Cards from one phone, app and group, shown as one expandable card (spec §6.1, Stacks).
public struct CardStack: Identifiable, Sendable, Equatable {
    public var id: String
    /// The group summary. It supplies the header when present.
    public var summary: CardItem?
    /// Never empty. Ordered by `sort_key`, then newest `when` first.
    public var children: [CardItem]

    public var header: CardItem { summary ?? children[0] }
    public var count: Int { children.count }
    public var isPinned: Bool { children.contains { $0.isPinned } }
    /// "App · N new", the collapsed stack's title.
    public var title: String { "\(header.posted.app.name) · \(count) new" }
}

public enum CardStacks {
    /// Groups the panel's cards into stacks: pinned stacks first, then the most recently shown.
    public static func build(_ items: [CardItem], shownAt: [String: Date]) -> [CardStack] {
        var stacks: [(stack: CardStack, shown: Date)] = []
        for (key, members) in Dictionary(grouping: items, by: \.stackKey) {
            let summary = members.filter(\.isGroupSummary).max { $0.receivedAt < $1.receivedAt }
            var children = members.filter { !$0.isGroupSummary }.sorted(by: inStackOrder)
            var header = summary
            if children.isEmpty, let summary {
                children = [summary]
                header = nil
            }
            guard !children.isEmpty else { continue }
            let shown = members.compactMap { shownAt[$0.id] }.max() ?? .distantPast
            stacks.append((CardStack(id: key, summary: header, children: children), shown))
        }
        return stacks.sorted { a, b in
            if a.stack.isPinned != b.stack.isPinned { return a.stack.isPinned }
            if a.shown != b.shown { return a.shown > b.shown }
            return a.stack.id < b.stack.id
        }.map(\.stack)
    }

    /// `sort_key` ascending (cards without one after those with one), then newest `when` first.
    public static func inStackOrder(_ a: CardItem, _ b: CardItem) -> Bool {
        switch (a.posted.group?.sortKey, b.posted.group?.sortKey) {
        case let (x?, y?) where x != y: return x < y
        case (.some, nil): return true
        case (nil, .some): return false
        default: break
        }
        if a.displayDate != b.displayDate { return a.displayDate > b.displayDate }
        return a.id < b.id
    }
}
