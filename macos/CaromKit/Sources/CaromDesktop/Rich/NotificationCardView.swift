import AppKit
import CaromProtocol
import SwiftUI

private func nonEmpty(_ s: String?) -> String? {
    guard let s, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
    return s
}

/// An Android-like notification card (spec §6.1): header, title and text with the large icon on
/// the right; expanded, the big text, inbox lines, the whole conversation and the big picture.
struct NotificationCardView: View {
    let item: CardItem
    let images: any ImageProvider
    let status: ActionStatus
    let perform: (CardAction) -> Void
    @State private var expanded: Bool

    init(item: CardItem, images: any ImageProvider, status: ActionStatus, perform: @escaping (CardAction) -> Void,
         initiallyExpanded: Bool = false) {
        self.item = item
        self.images = images
        self.status = status
        self.perform = perform
        _expanded = State(initialValue: initiallyExpanded)
    }

    private var posted: NotificationPosted { item.posted }
    private var messages: [NotificationPosted.ConversationMessage] { posted.messages ?? [] }
    private var headline: String? { nonEmpty(posted.conversation?.title) ?? nonEmpty(posted.title) }
    private var bodyText: String? {
        expanded ? (nonEmpty(posted.bigText) ?? nonEmpty(posted.text)) : nonEmpty(posted.text)
    }
    private var isExpandable: Bool {
        let longer = posted.bigText.map { $0 != posted.text } ?? false
        return longer || !(posted.lines ?? []).isEmpty || posted.picture != nil || messages.count > 2
    }

    var body: some View {
        let style = CardStyle.make(color: posted.color, colorized: posted.colorized)
        let ink = CardInk(style: style)
        VStack(alignment: .leading, spacing: 8) {
            CardHeader(item: item, images: images, ink: ink, showsChevron: isExpandable, expanded: $expanded)
            content(ink)
            CardActionsRow(item: item, ink: ink, status: status, perform: perform)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(ink.primary)
        .background { CardBackground(fill: style.fill) }
        .contentShape(RoundedRectangle(cornerRadius: CardMetrics.corner, style: .continuous))
        .onTapGesture { if item.canTap { perform(.tap) } }
        .contextMenu {
            if let code = item.code { Button("Copy code") { perform(.copyCode(code)) } }
            if item.canTap { Button("Open on phone") { perform(.tap) } }
            Button("Dismiss on phone") { perform(.dismiss) }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel([posted.app.name, headline].compactMap { $0 }.joined(separator: ", "))
        .accessibilityHint(item.canTap ? "Opens it on your phone" : "")
    }

    @ViewBuilder
    private func content(_ ink: CardInk) -> some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                if let headline {
                    Text(headline).font(.headline).lineLimit(expanded ? 3 : 1)
                }
                if messages.isEmpty, let text = bodyText {
                    Text(text).font(.callout).foregroundStyle(ink.secondary).lineLimit(expanded ? 12 : 2)
                }
            }
            Spacer(minLength: 0)
            if let ref = item.largeIconRef {
                ImageSlot(ref: ref, images: images) {
                    InitialBadge(text: headline ?? posted.app.name, tint: ink.accent)
                }
                .frame(width: 40, height: 40)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .accessibilityHidden(true)
            }
        }
        if expanded, let lines = posted.lines, !lines.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    Text(line).font(.callout).lineLimit(1)
                }
            }
        }
        if !messages.isEmpty {
            MessageBubbles(messages: expanded ? messages : Array(messages.suffix(2)), images: images, ink: ink)
        }
        if expanded, let picture = posted.picture {
            ImageSlot(ref: picture, images: images, contentMode: .fit) {
                RoundedRectangle(cornerRadius: 10, style: .continuous).fill(ink.bubble).frame(height: 120)
            }
            .frame(maxWidth: .infinity, maxHeight: 220)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .accessibilityLabel(posted.pictureDesc ?? "Picture")
        }
    }
}

/// Small icon tinted with the accent, app name in the accent, sub text (or the summary when
/// expanded), phone name, time and the expand chevron.
struct CardHeader: View {
    let item: CardItem
    let images: any ImageProvider
    let ink: CardInk
    let showsChevron: Bool
    @Binding var expanded: Bool

    var body: some View {
        HStack(spacing: 5) {
            icon.frame(width: 16, height: 16).accessibilityHidden(true)
            Text(item.posted.app.name).fontWeight(.semibold).foregroundStyle(ink.accent)
            ForEach(Array(details.enumerated()), id: \.offset) { _, detail in
                Text(verbatim: "· " + detail)
            }
            Spacer(minLength: 4)
            if showsChevron {
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { expanded.toggle() }
                } label: {
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.caption.weight(.semibold))
                        .frame(width: 20, height: 16)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(expanded ? "Collapse" : "Expand")
            }
        }
        .font(.caption)
        .foregroundStyle(ink.secondary)
        .lineLimit(1)
    }

    private var details: [String] {
        var out: [String] = []
        let sub = expanded ? (nonEmpty(item.posted.summaryText) ?? nonEmpty(item.posted.subText))
                           : nonEmpty(item.posted.subText)
        if let sub { out.append(sub) }
        if let phone = item.phoneName { out.append(phone) }
        if item.posted.showWhen != false { out.append(CardTime.short(item.displayDate, now: Date())) }
        return out
    }

    @ViewBuilder
    private var icon: some View {
        let initial = InitialBadge(text: item.posted.app.name, tint: ink.accent)
        if let small = item.posted.smallIcon {
            ImageSlot(ref: small, images: images, template: true) { initial }
                .foregroundStyle(ink.accent)
        } else if let appIcon = item.posted.app.icon {
            ImageSlot(ref: appIcon, images: images) { initial }
        } else {
            initial
        }
    }
}

/// Messaging style: one bubble per message with the sender's avatar. A message without a
/// sender is the phone's user.
struct MessageBubbles: View {
    let messages: [NotificationPosted.ConversationMessage]
    let images: any ImageProvider
    let ink: CardInk

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(messages.enumerated()), id: \.offset) { _, message in
                let name = message.sender?.name ?? "You"
                HStack(alignment: .top, spacing: 8) {
                    ImageSlot(ref: message.sender?.icon, images: images) {
                        InitialBadge(text: name, tint: ink.accent)
                    }
                    .frame(width: 24, height: 24)
                    .clipShape(Circle())
                    .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name).font(.caption.weight(.semibold))
                        if !message.text.isEmpty {
                            Text(message.text).font(.callout).lineLimit(4)
                        }
                        if let image = message.image {
                            ImageSlot(ref: image, images: images, contentMode: .fit) {
                                RoundedRectangle(cornerRadius: 8, style: .continuous).fill(ink.bubble).frame(height: 80)
                            }
                            .frame(maxWidth: .infinity, maxHeight: 140, alignment: .leading)
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                        }
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(ink.bubble, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .accessibilityElement(children: .combine)
            }
        }
    }
}
