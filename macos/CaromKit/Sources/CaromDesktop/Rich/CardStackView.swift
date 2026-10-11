import SwiftUI

/// One stack on the panel: a single card, or "App · N new" that expands into its cards.
struct CardStackView: View {
    let stack: CardStack
    let coordinator: NotificationCoordinator
    @State private var expanded = false

    var body: some View {
        VStack(spacing: 6) {
            if stack.count > 1 && !expanded {
                collapsed
            } else {
                if stack.count > 1 { collapseBar }
                ForEach(stack.children) { item in card(item) }
            }
        }
    }

    @ViewBuilder
    private func card(_ item: CardItem) -> some View {
        let status = coordinator.status(for: item.id)
        let perform: (CardAction) -> Void = { [coordinator] action in _ = coordinator.perform(action, on: item.id) }
        Group {
            if item.isIncomingCall {
                CallCardView(item: item, images: coordinator.images, status: status, perform: perform)
            } else if item.isLive {
                LiveUpdateCardView(item: item, images: coordinator.images, status: status, perform: perform)
            } else {
                NotificationCardView(item: item, images: coordinator.images, status: status, perform: perform)
            }
        }
        .environment(\.cardReplyMode, { [coordinator] on in
            if on { coordinator.beginReply(item.id) } else { coordinator.endReply() }
        })
        .modifier(CloseOnHover { [coordinator] in coordinator.close(item.id) })
    }

    private var collapsed: some View {
        let header = stack.header
        let style = CardStyle.make(color: header.posted.color, colorized: header.posted.colorized)
        let ink = CardInk(style: style)
        let latest = stack.children.first.flatMap { $0.posted.title ?? $0.posted.text }
        return Button {
            withAnimation(.easeOut(duration: 0.2)) { expanded = true }
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                CardHeader(item: header, images: coordinator.images, ink: ink, showsChevron: false,
                           expanded: .constant(false))
                Text(stack.title).font(.headline)
                if let latest {
                    Text(latest).font(.callout).foregroundStyle(ink.secondary).lineLimit(1)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .foregroundStyle(ink.primary)
            .background { CardBackground(fill: style.fill) }
            .background(alignment: .bottom) {
                CardBackground(fill: style.fill).padding(.horizontal, 8).offset(y: 5).opacity(0.6)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(stack.title)
        .accessibilityHint("Shows each notification")
    }

    private var collapseBar: some View {
        HStack {
            Text(stack.title).font(.caption.weight(.semibold))
            Spacer()
            Button("Show less") { withAnimation(.easeOut(duration: 0.2)) { expanded = false } }
                .buttonStyle(.plain)
                .font(.caption)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(.regularMaterial, in: Capsule())
    }
}

/// A close button on hover (and a "Close" accessibility action): the card leaves the panel,
/// nothing is sent to the phone.
struct CloseOnHover: ViewModifier {
    let close: () -> Void
    @State private var hovering = false

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .topLeading) {
                if hovering {
                    Button(action: close) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 15))
                            .symbolRenderingMode(.hierarchical)
                    }
                    .buttonStyle(.plain)
                    .offset(x: -6, y: -6)
                    .accessibilityLabel("Close")
                }
            }
            .onHover { hovering = $0 }
            .accessibilityAction(named: "Close", close)
    }
}
