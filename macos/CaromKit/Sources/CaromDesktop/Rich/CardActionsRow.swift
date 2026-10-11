import CaromProtocol
import SwiftUI

private struct CardReplyModeKey: EnvironmentKey {
    static let defaultValue: @MainActor @Sendable (Bool) -> Void = { _ in }
}

extension EnvironmentValues {
    /// Called with true when the user opens a card's reply field and false when it closes, so
    /// the panel takes keyboard focus only for typing (spec §6.1).
    var cardReplyMode: @MainActor @Sendable (Bool) -> Void {
        get { self[CardReplyModeKey.self] }
        set { self[CardReplyModeKey.self] = newValue }
    }
}

/// Copy code first, then the app's buttons, then Reply; the reply field with the app's
/// suggested replies as chips; and the status of the last action.
struct CardActionsRow: View {
    let item: CardItem
    let ink: CardInk
    let status: ActionStatus
    let perform: (CardAction) -> Void
    @Environment(\.cardReplyMode) private var replyMode
    @State private var replying = false
    @State private var draft = ""
    @FocusState private var fieldFocused: Bool

    private var hasButtons: Bool { item.code != nil || !item.phoneButtons.isEmpty || item.replyAction != nil }

    var body: some View {
        if hasButtons || status.label != nil {
            VStack(alignment: .leading, spacing: 6) {
                if hasButtons {
                    HStack(spacing: 14) {
                        if let code = item.code {
                            action("Copy code") { perform(.copyCode(code)) }
                        }
                        ForEach(item.phoneButtons, id: \.index) { button in
                            action(button.title) { perform(.button(index: button.index, title: button.title)) }
                        }
                        if let reply = item.replyAction, !replying {
                            action(reply.title.isEmpty ? "Reply" : reply.title) { startReply() }
                        }
                    }
                    .disabled(status == .sending)
                }
                if replying, let reply = item.replyAction {
                    replyField(reply)
                }
                if let label = status.label {
                    Text(label).font(.caption).foregroundStyle(ink.secondary)
                }
            }
        }
    }

    private func action(_ title: String, run: @escaping () -> Void) -> some View {
        Button(title, action: run)
            .buttonStyle(.plain)
            .font(.callout.weight(.semibold))
            .foregroundStyle(ink.accent)
            .lineLimit(1)
    }

    @ViewBuilder
    private func replyField(_ reply: NotificationPosted.Action) -> some View {
        HStack(spacing: 6) {
            TextField(reply.reply?.label ?? "Reply", text: $draft)
                .textFieldStyle(.roundedBorder)
                .focused($fieldFocused)
                .onSubmit { send(draft, reply) }
            Button("Send") { send(draft, reply) }
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Button {
                cancelReply()
            } label: {
                Image(systemName: "xmark.circle.fill")
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Cancel reply")
        }
        if let choices = reply.reply?.choices, !choices.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(Array(choices.enumerated()), id: \.offset) { _, choice in
                        Button(choice) { send(choice, reply) }
                            .buttonStyle(.plain)
                            .font(.callout)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .overlay(Capsule().strokeBorder(ink.accent.opacity(0.6)))
                            .foregroundStyle(ink.accent)
                            .accessibilityLabel("Reply \(choice)")
                    }
                }
            }
        }
    }

    private func startReply() {
        replying = true
        replyMode(true)
        Task { @MainActor in fieldFocused = true }
    }

    private func cancelReply() {
        replying = false
        draft = ""
        replyMode(false)
    }

    private func send(_ text: String, _ reply: NotificationPosted.Action) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        perform(.reply(index: reply.index, title: reply.title, text: trimmed))
        cancelReply()
    }
}
