import AppKit
import CaromProtocol
import SwiftUI
import Testing

@testable import CaromDesktop

/// Render tests: every template draws in light and dark, collapsed and expanded, with no images.
/// Set CAROM_CARD_IMAGES=<dir> to also write the PNGs there for a visual check.
@MainActor
@Suite struct CardRenderTests {
    static let width: CGFloat = 364

    nonisolated static let templates: [(String, NotificationPosted)] = [
        ("standard", Fixture.posted()),
        ("big-text", Fixture.bigText()),
        ("inbox", Fixture.inbox()),
        ("messaging", Fixture.messaging()),
        ("big-picture", Fixture.bigPicture()),
        ("colorized", Fixture.colorized(0xFF2E7D32)),
        ("actions", Fixture.withActions(Fixture.posted(text: "Your code is 482913"))),
    ]

    func render(_ view: some View, scheme: ColorScheme = .light, name: String) -> NSImage? {
        let renderer = ImageRenderer(content: view.frame(width: Self.width).environment(\.colorScheme, scheme))
        renderer.scale = 2
        let image = renderer.nsImage
        if let dir = ProcessInfo.processInfo.environment["CAROM_CARD_IMAGES"], let image {
            write(image, to: dir, name: "\(name)-\(scheme == .dark ? "dark" : "light")")
        }
        return image
    }

    func card(_ item: CardItem, expanded: Bool = false, status: ActionStatus = .idle) -> NotificationCardView {
        NotificationCardView(item: item, images: NoImageProvider(), status: status, perform: { _ in },
                             initiallyExpanded: expanded)
    }

    func write(_ image: NSImage, to dir: String, name: String) {
        guard let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
              let png = rep.representation(using: .png, properties: [:]) else { return }
        try? png.write(to: URL(fileURLWithPath: dir).appending(path: "\(name).png"))
    }

    func sample(_ image: NSImage, x: CGFloat, y: CGFloat) -> NSColor? {
        guard let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return nil }
        let rep = NSBitmapImageRep(cgImage: cg)
        let scale = CGFloat(cg.width) / image.size.width
        return rep.colorAt(x: Int(x * scale), y: Int(y * scale))?.usingColorSpace(.sRGB)
    }

    @Test(arguments: CardRenderTests.templates)
    func rendersEveryTemplateInLightAndDark(name: String, posted: NotificationPosted) throws {
        for scheme in [ColorScheme.light, .dark] {
            for expanded in [false, true] {
                let image = try #require(render(card(Fixture.card(posted), expanded: expanded), scheme: scheme,
                                                name: "\(name)\(expanded ? "-expanded" : "")"))
                #expect(abs(image.size.width - Self.width) < 1)
                #expect(image.size.height > 40)
            }
        }
    }

    /// Index Review Focus 3: a card whose every image is missing still renders at once.
    @Test func rendersWithEveryImageMissing() throws {
        for expanded in [false, true] {
            let image = try #require(render(card(Fixture.card(Fixture.everyImage()), expanded: expanded),
                                            name: "missing-images\(expanded ? "-expanded" : "")"))
            #expect(image.size.height > 40)
        }
    }

    @Test func colorizedCardIsFilledWithItsColor() throws {
        let image = try #require(render(card(Fixture.card(Fixture.colorized(0xFFD32F2F))), name: "colorized-red"))
        let pixel = try #require(sample(image, x: 3, y: image.size.height / 2))
        #expect(pixel.redComponent > 0.7 && pixel.greenComponent < 0.35 && pixel.blueComponent < 0.35)
    }

    @Test func accentAloneDoesNotFillTheCard() throws {
        var p = Fixture.posted()
        p.color = 0xFFD32F2F
        let image = try #require(render(card(Fixture.card(p)), name: "accent-only"))
        let pixel = try #require(sample(image, x: 3, y: image.size.height / 2))
        #expect(!(pixel.redComponent > 0.7 && pixel.greenComponent < 0.35 && pixel.blueComponent < 0.35))
    }

    @Test func expandedIsTallerForBigText() throws {
        let item = Fixture.card(Fixture.bigText())
        let collapsed = try #require(render(card(item), name: "bt-collapsed"))
        let expanded = try #require(render(card(item, expanded: true), name: "bt-expanded"))
        #expect(expanded.size.height > collapsed.size.height)
    }

    @Test func rendersActionsWithEveryStatus() throws {
        let item = Fixture.card(Fixture.withActions(Fixture.posted(text: "Your code is 482913")))
        let statuses: [ActionStatus] = [.idle, .sending, .sent, .asleep, .gone, .changed, .failed]
        var heights: [CGFloat] = []
        for status in statuses {
            let image = try #require(render(card(item, status: status), name: "status-\(status)"))
            heights.append(image.size.height)
        }
        #expect(heights[3] > heights[0], "a status line adds height")
    }

    @Test func rendersStacksCollapsedAndSingle() async throws {
        let (c, _, _, _) = makeCoordinator()
        await c.posted(Fixture.card(Fixture.grouped("a")))
        await c.posted(Fixture.card(Fixture.grouped("b")))
        await c.posted(Fixture.card(Fixture.grouped("s", summary: true)))
        await c.posted(Fixture.card(Fixture.posted("solo")))
        let stacks = c.visibleStacks
        #expect(stacks.count == 2)
        for (i, stack) in stacks.enumerated() {
            let image = try #require(render(CardStackView(stack: stack, coordinator: c), name: "stack-\(i)"))
            #expect(image.size.height > 40)
        }
    }

    @Test func liveAndCallCardsRouteToTheirViews() async throws {
        let (c, _, _, _) = makeCoordinator()
        await c.posted(Fixture.card(Fixture.live()))
        await c.posted(Fixture.card(Fixture.incomingCall()))
        for stack in c.visibleStacks {
            #expect(render(CardStackView(stack: stack, coordinator: c), name: "pinned-\(stack.id)") != nil)
        }
        #expect(CardStackView.kind(for: Fixture.card(Fixture.live())) == .live)
        #expect(CardStackView.kind(for: Fixture.card(Fixture.incomingCall())) == .call)
        #expect(CardStackView.kind(for: Fixture.card(Fixture.messaging())) == .notification)
        #expect(CardStackView.kind(for: Fixture.card(Fixture.posted())) == .notification)
        var callWithProgress = Fixture.incomingCall()
        callWithProgress.progress = Fixture.live().progress
        #expect(CardStackView.kind(for: Fixture.card(callWithProgress)) == .call, "a call wins over live")
    }
}
