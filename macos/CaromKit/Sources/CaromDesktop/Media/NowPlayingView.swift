import CaromProtocol
import SwiftUI

/// The Now Playing card of the menu popover: art, title, artist, a progress bar extrapolated from
/// the last `media.state`, and the controls the phone offers. Commands go out through `send`.
public struct NowPlayingView: View {
    public let session: MediaSession
    public let images: any ImageProvider
    /// False when the phone does not advertise `media.command@1`: the card only shows.
    public let canControl: Bool
    public let send: (MediaControl) -> Void

    @State private var dragging: Double?

    public init(session: MediaSession, images: any ImageProvider, canControl: Bool,
                send: @escaping (MediaControl) -> Void) {
        self.session = session
        self.images = images
        self.canControl = canControl
        self.send = send
    }

    private var state: MediaState { session.state }

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                BlobImageView(ref: state.art, images: images, fallback: state.app.name, size: 52, circular: false)
                VStack(alignment: .leading, spacing: 2) {
                    Text(state.title ?? "Nothing playing").font(.callout.weight(.semibold)).lineLimit(1)
                    if let artist = state.artist, !artist.isEmpty {
                        Text(artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Text(session.phoneName.map { "\(state.app.name) · \($0)" } ?? state.app.name)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            if state.durationMs != nil, state.positionMs != nil { progress }
            controls
            if !canControl {
                Text("Turn on Actions from Mac in Carom on the phone to control playback here.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(10)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Now playing")
    }

    private var progress: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let duration = state.durationMs ?? 0
            let position = MediaMath.positionMs(of: session, now: context.date) ?? 0
            let shown = dragging ?? Double(position)
            VStack(spacing: 2) {
                if canControl, session.offers("seek"), duration > 0 {
                    Slider(value: Binding(get: { shown }, set: { dragging = $0 }), in: 0...Double(duration)) { editing in
                        if !editing, let target = dragging {
                            send(.seek(positionMs: Int(target)))
                            dragging = nil
                        }
                    }
                    .controlSize(.small)
                    .accessibilityLabel("Position")
                } else {
                    ProgressView(value: MediaMath.fraction(positionMs: position, durationMs: duration) ?? 0)
                        .progressViewStyle(.linear)
                        .accessibilityLabel("Position")
                }
                HStack {
                    Text(MediaMath.clock(ms: Int(shown)))
                    Spacer()
                    Text(MediaMath.clock(ms: duration))
                }
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
            }
        }
    }

    private var controls: some View {
        HStack(spacing: 18) {
            Spacer()
            if session.offers("previous") { button("backward.fill", "Previous", .previous) }
            if session.offers(session.isPlaying ? "pause" : "play") {
                button(session.isPlaying ? "pause.fill" : "play.fill", session.isPlaying ? "Pause" : "Play",
                       session.isPlaying ? .pause : .play, large: true)
            }
            if session.offers("next") { button("forward.fill", "Next", .next) }
            Spacer()
        }
    }

    private func button(_ symbol: String, _ label: String, _ control: MediaControl, large: Bool = false) -> some View {
        Button { send(control) } label: {
            Image(systemName: symbol).font(large ? .title2 : .body).frame(width: 28, height: 28)
        }
        .buttonStyle(.plain)
        .disabled(!canControl)
        .accessibilityLabel(label)
    }
}
