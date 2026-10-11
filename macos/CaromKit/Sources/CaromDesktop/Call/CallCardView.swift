import CaromProtocol
import SwiftUI

/// An incoming call: caller, avatar, verification text, Answer and Decline. It rings while it is
/// on screen and stops when the phone removes the notification or the user acts. Audio never
/// reaches the Mac; the card says so.
public struct CallCardView: View {
    public let item: CardItem
    public let images: any ImageProvider
    public let status: ActionStatus
    public let perform: (CardAction) -> Void
    private let ringer: CallRinger

    @State private var acted = false

    public init(item: CardItem, images: any ImageProvider, status: ActionStatus,
                perform: @escaping (CardAction) -> Void) {
        self.init(item: item, images: images, status: status, ringer: .shared, perform: perform)
    }

    init(item: CardItem, images: any ImageProvider, status: ActionStatus, ringer: CallRinger,
         perform: @escaping (CardAction) -> Void) {
        self.item = item
        self.images = images
        self.status = status
        self.ringer = ringer
        self.perform = perform
    }

    private var posted: NotificationPosted { item.posted }
    private var caller: String {
        let name = posted.call?.person?.name ?? posted.title ?? ""
        return name.isEmpty ? "Unknown caller" : name
    }

    public var body: some View {
        VStack(spacing: 12) {
            HStack {
                Text(posted.call?.isVideo == true ? "Incoming video call" : "Incoming call")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                Text(posted.app.name).font(.caption).foregroundStyle(.tertiary).lineLimit(1)
            }
            BlobImageView(ref: posted.call?.person?.icon ?? posted.largeIcon, images: images, fallback: caller,
                          size: 64)
            Text(caller).font(.title2.weight(.semibold)).lineLimit(2).multilineTextAlignment(.center)
            if let verification = posted.call?.verification, !verification.isEmpty {
                Label(verification, systemImage: "checkmark.seal")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            buttons
            if let line = CardStatusText.text(for: status) {
                Text(line).font(.caption).foregroundStyle(.secondary)
            }
            Text(item.phoneAcceptsActions
                 ? "Audio stays on the phone."
                 : "Answer on the phone. Actions from Mac are off there.")
                .font(.caption)
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .contain)
        .onAppear { ringer.start(item.id) }
        .onDisappear { ringer.stop(item.id) }
    }

    @ViewBuilder private var buttons: some View {
        if item.phoneAcceptsActions {
            HStack(spacing: 12) {
                Button {
                    act(.decline)
                } label: {
                    Label("Decline", systemImage: "phone.down.fill").frame(maxWidth: .infinity)
                }
                .tint(.red)
                .accessibilityLabel("Decline call from \(caller)")
                Button {
                    act(.answer)
                } label: {
                    Label("Answer", systemImage: "phone.fill").frame(maxWidth: .infinity)
                }
                .tint(.green)
                .accessibilityLabel("Answer call from \(caller)")
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(Self.buttonsDisabled(acted: acted, status: status))
        }
    }

    /// Off while a press is on its way or done; on again after "Couldn't send" or "Phone blocked
    /// opening the app", so the user can retry (or unlock the phone and retry) while it still rings.
    static func buttonsDisabled(acted: Bool, status: ActionStatus) -> Bool {
        status == .sending || status == .sent || (acted && status != .failed && status != .blocked)
    }

    private func act(_ action: CardAction) {
        acted = true
        ringer.stop(item.id)
        perform(action)
    }
}
