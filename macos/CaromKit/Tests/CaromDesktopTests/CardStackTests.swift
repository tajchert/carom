import Foundation
import Testing

@testable import CaromDesktop

@Suite struct CardStackTests {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func samePhoneAppAndGroupCollapseWithSummaryHeader() {
        let items = [
            Fixture.card(Fixture.grouped("a")),
            Fixture.card(Fixture.grouped("b")),
            Fixture.card(Fixture.grouped("s", summary: true)),
            Fixture.card(Fixture.posted("solo")),
        ]
        let shown = Dictionary(uniqueKeysWithValues: items.map { ($0.id, t0) })
        let stacks = CardStacks.build(items, shownAt: shown)
        #expect(stacks.count == 2)
        let group = stacks.first { $0.count == 2 }
        #expect(group?.summary?.posted.key == "s")
        #expect(group?.header.posted.key == "s")
        #expect(group?.title == "Chat · 2 new")
        #expect(Set(group?.children.map(\.posted.key) ?? []) == ["a", "b"])
    }

    @Test func summaryAloneIsItsOwnCard() {
        let summary = Fixture.card(Fixture.grouped("s", summary: true))
        let stacks = CardStacks.build([summary], shownAt: [summary.id: t0])
        #expect(stacks.count == 1)
        #expect(stacks[0].summary == nil)
        #expect(stacks[0].children.map(\.posted.key) == ["s"])
    }

    @Test func childrenOrderBySortKeyThenNewestWhen() {
        let items = [
            Fixture.card(Fixture.grouped("old", when: 1_800_000_000_000)),
            Fixture.card(Fixture.grouped("new", when: 1_800_000_090_000)),
            Fixture.card(Fixture.grouped("b", sortKey: "b")),
            Fixture.card(Fixture.grouped("a", sortKey: "a")),
        ]
        let stacks = CardStacks.build(items, shownAt: [:])
        #expect(stacks.first?.children.map(\.posted.key) == ["a", "b", "new", "old"])
    }

    @Test func pinnedFirstThenMostRecentlyShown() {
        let first = Fixture.card(Fixture.posted("first"))
        let second = Fixture.card(Fixture.posted("second"))
        let live = Fixture.card(Fixture.live())
        let shown = [first.id: t0, second.id: t0.addingTimeInterval(5), live.id: t0.addingTimeInterval(-60)]
        let stacks = CardStacks.build([first, second, live], shownAt: shown)
        #expect(stacks.map(\.header.posted.key) == ["ride", "second", "first"])
        #expect(stacks[0].isPinned)
    }
}
