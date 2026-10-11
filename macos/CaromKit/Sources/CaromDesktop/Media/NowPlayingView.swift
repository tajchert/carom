import CaromProtocol
import SwiftUI

/// The decisions behind the card: which buttons are drawn, whether they are enabled, whether the
/// position is a seek slider, and whether the hint shows. Kept apart from the view so they are testable.
struct NowPlayingLayout: Equatable {
    struct Button: Equatable {
        let symbol: String
        let label: String
        let control: MediaControl
        let large: Bool
    }

    let buttons: [Button]
    let buttonsEnabled: Bool
    let showsHint: Bool
    let showsProgress: Bool
    let usesSeekSlider: Bool

    init(session: MediaSession, canControl: Bool) {
        let state = session.state
        var buttons: [Button] = []
        if session.offers("previous") { buttons.append(Button(symbol: "backward.fill", label: "Previous", control: .previous, large: false)) }
        if session.isPlaying {
            if session.offers("pause") { buttons.append(Button(symbol: "pause.fill", label: "Pause", control: .pause, large: true)) }
        } else if session.offers("play") {
            buttons.append(Button(symbol: "play.fill", label: "Play", control: .play, large: true))
        }
        if session.offers("next") { buttons.append(Button(symbol: "forward.fill", label: "Next", control: .next, large: false)) }
        self.buttons = buttons
        buttonsEnabled = canControl
        showsHint = !canControl
        showsProgress = state.durationMs != nil && state.positionMs != nil
        usesSeekSlider = canControl && session.offers("seek") && (state.durationMs ?? 0) > 0
    }

    /// The command sent when the slider is released at `target` milliseconds.
    static func seekCommand(to target: Double) -> MediaControl { .seek(positionMs: Int(target)) }
}

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
    private var layout: NowPlayingLayout { NowPlayingLayout(session: session, canControl: canControl) }

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
            if layout.showsProgress { progress }
            controls
            if layout.showsHint {
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
                if layout.usesSeekSlider {
                    Slider(value: Binding(get: { shown }, set: { dragging = $0 }), in: 0...Double(duration)) { editing in
                        if !editing, let target = dragging {
                            send(NowPlayingLayout.seekCommand(to: target))
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
            ForEach(layout.buttons, id: \.label) { item in
                button(item.symbol, item.label, item.control, large: item.large)
            }
            Spacer()
        }
    }

    private func button(_ symbol: String, _ label: String, _ control: MediaControl, large: Bool = false) -> some View {
        Button { send(control) } label: {
            Image(systemName: symbol).font(large ? .title2 : .body).frame(width: 28, height: 28)
        }
        .buttonStyle(.plain)
        .disabled(!layout.buttonsEnabled)
        .accessibilityLabel(label)
    }
}
