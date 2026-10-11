import AppKit
import CaromProtocol
import SwiftUI

/// A Live Update or progress notification: header, title, the segmented bar with its points and
/// tracker icon, a local chronometer, and the short critical text. Updates arrive as new
/// `CardItem`s with the same `id`, so the card changes in place and makes no sound.
public struct LiveUpdateCardView: View {
    public let item: CardItem
    public let images: any ImageProvider
    public let status: ActionStatus
    public let perform: (CardAction) -> Void

    public init(item: CardItem, images: any ImageProvider, status: ActionStatus,
                perform: @escaping (CardAction) -> Void) {
        self.item = item
        self.images = images
        self.status = status
        self.perform = perform
    }

    private var posted: NotificationPosted { item.posted }
    private var accent: Color { posted.color.map(CardColors.color(argb:)) ?? .accentColor }

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            if let title = posted.title, !title.isEmpty {
                Text(title).font(.headline).lineLimit(2)
            }
            if let text = posted.text, !text.isEmpty {
                Text(text).font(.callout).foregroundStyle(.secondary).lineLimit(3)
            }
            if let progress = posted.progress {
                LiveProgressBar(bar: LiveMath.bar(for: progress), accent: accent,
                                tracker: progress.trackerIcon, start: progress.startIcon, end: progress.endIcon,
                                images: images)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Progress")
                    .accessibilityValue(progress.indeterminate ? "In progress" : percent(progress))
            }
            if let timer = LiveMath.chronometer(of: posted) {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    Text(LiveMath.chronometerText(whenMs: timer.whenMs, countDown: timer.countDown, now: context.date))
                        .font(.title3.monospacedDigit().weight(.medium))
                        .accessibilityLabel(timer.countDown ? "Time left" : "Time elapsed")
                }
            }
            actions
            if let line = CardStatusText.text(for: status) {
                Text(line).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture {
            if posted.canOpen == true, item.phoneAcceptsActions { perform(.tap) }
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            BlobImageView(ref: posted.app.icon, images: images, fallback: posted.app.name, size: 20, circular: false)
            Text(posted.app.name).font(.caption.weight(.semibold)).foregroundStyle(accent).lineLimit(1)
            Spacer(minLength: 8)
            if let critical = posted.live?.shortCriticalText, !critical.isEmpty {
                Text(critical)
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(accent.opacity(0.2), in: Capsule())
                    .foregroundStyle(accent)
                    .lineLimit(1)
            }
        }
    }

    /// The plain buttons this card draws: none unless the phone takes actions, at most three.
    /// Reply actions are left out: pressing one without text would fire the app's reply intent
    /// empty, and inline reply belongs to the panel's actions row.
    static func visibleButtons(of item: CardItem) -> [NotificationPosted.Action] {
        guard item.phoneAcceptsActions else { return [] }
        return Array((item.posted.actions ?? []).filter { $0.reply == nil }.prefix(3))
    }

    @ViewBuilder private var actions: some View {
        let buttons = Self.visibleButtons(of: item)
        if !buttons.isEmpty {
            HStack {
                ForEach(buttons, id: \.index) { button in
                    Button(button.title) { perform(.button(index: button.index, title: button.title)) }
                        .controlSize(.small)
                        .disabled(status == .sending)
                }
            }
        }
    }

    private func percent(_ progress: NotificationPosted.Progress) -> String {
        "\(Int((LiveMath.bar(for: progress).fraction * 100).rounded())) percent"
    }
}

/// Draws a `LiveMath.Bar`: segments with small gaps, points as dots, and the tracker icon at the
/// fill position (a dot when the phone sent none).
struct LiveProgressBar: View {
    let bar: LiveMath.Bar
    let accent: Color
    let tracker: BlobRef?
    let start: BlobRef?
    let end: BlobRef?
    let images: any ImageProvider

    private let height: CGFloat = 8
    private let gap: CGFloat = 3
    private let icon: CGFloat = 20

    var body: some View {
        HStack(spacing: 6) {
            if let start { BlobImageView(ref: start, images: images, fallback: "", size: 16, circular: false) }
            track
            if let end { BlobImageView(ref: end, images: images, fallback: "", size: 16, circular: false) }
        }
        .frame(height: icon)
    }

    private var track: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let gaps = gap * CGFloat(max(0, bar.segments.count - 1))
            ZStack(alignment: .leading) {
                if bar.indeterminate || bar.segments.isEmpty {
                    Capsule().fill(accent.opacity(0.25)).frame(height: height)
                    if bar.indeterminate { ProgressView().controlSize(.small).frame(maxWidth: .infinity) }
                } else {
                    HStack(spacing: gap) {
                        ForEach(Array(bar.segments.enumerated()), id: \.offset) { _, segment in
                            let w = (width - gaps) * segment.width
                            let color = segment.color.map(CardColors.color(argb:)) ?? accent
                            ZStack(alignment: .leading) {
                                Capsule().fill(color.opacity(0.25))
                                Capsule().fill(color).frame(width: w * segment.fill)
                            }
                            .frame(width: w, height: height)
                        }
                    }
                    ForEach(Array(bar.points.enumerated()), id: \.offset) { _, point in
                        Circle()
                            .fill(point.color.map(CardColors.color(argb:)) ?? accent)
                            .overlay(Circle().stroke(.background, lineWidth: 1.5))
                            .frame(width: height + 2, height: height + 2)
                            .offset(x: x(point.at, width) - (height + 2) / 2)
                    }
                    trackerView.offset(x: x(bar.fraction, width) - icon / 2)
                }
            }
            .frame(height: icon)
        }
    }

    @ViewBuilder private var trackerView: some View {
        if tracker != nil {
            BlobImageView(ref: tracker, images: images, fallback: "", size: icon, circular: false)
        } else {
            Circle().fill(accent).overlay(Circle().stroke(.background, lineWidth: 2)).frame(width: 12, height: 12)
                .frame(width: icon, height: icon)
        }
    }

    /// Fraction to x. The gaps are small, so the fraction of the full width is close enough for
    /// the tracker and the points.
    private func x(_ fraction: Double, _ width: CGFloat) -> CGFloat { width * CGFloat(fraction) }
}
