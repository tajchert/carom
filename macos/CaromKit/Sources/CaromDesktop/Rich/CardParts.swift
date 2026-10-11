import AppKit
import CaromProtocol
import SwiftUI

enum CardMetrics {
    static let corner: CGFloat = 14
}

/// The card's text and accent colours. Colorized cards use black or white for contrast.
struct CardInk {
    var primary: Color
    var secondary: Color
    var accent: Color
    var bubble: Color

    init(style: CardStyle) {
        if style.fill != nil {
            let ink: Color = style.darkText ? .black : .white
            primary = ink
            secondary = ink.opacity(0.75)
            accent = ink
            bubble = ink.opacity(0.12)
        } else {
            primary = .primary
            secondary = .secondary
            accent = style.accent?.color ?? .accentColor
            bubble = Color.primary.opacity(0.06)
        }
    }
}

/// The card's rounded background: the colorized fill, or a material.
struct CardBackground: View {
    let fill: ARGB?

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: CardMetrics.corner, style: .continuous)
        Group {
            if let fill { shape.fill(fill.color) } else { shape.fill(.regularMaterial) }
        }
        .overlay(shape.strokeBorder(Color.primary.opacity(0.08)))
        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
    }
}

/// The first letter of a name in a tinted circle: the placeholder for every missing image.
struct InitialBadge: View {
    let text: String
    let tint: Color

    var body: some View {
        GeometryReader { geo in
            Circle()
                .fill(tint.opacity(0.2))
                .overlay(
                    Text(verbatim: String(text.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased())
                        .font(.system(size: max(6, geo.size.height * 0.5), weight: .semibold))
                        .foregroundStyle(tint)
                )
        }
    }
}

/// An image from a blob reference. Shows the placeholder right away and keeps it when the
/// provider returns nil (missing, expired or corrupt blob; spec §7).
struct ImageSlot<Placeholder: View>: View {
    let ref: BlobRef?
    let images: any ImageProvider
    var template = false
    var contentMode: ContentMode = .fill
    @ViewBuilder let placeholder: () -> Placeholder
    @State private var image: NSImage?

    var body: some View {
        ZStack {
            if let image {
                Image(nsImage: image)
                    .renderingMode(template ? .template : .original)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else {
                placeholder()
            }
        }
        .task(id: ref?.id) {
            image = nil
            guard let ref else { return }
            image = await images.image(for: ref)
        }
    }
}
