import AppKit
import CaromProtocol
import SwiftUI

/// An image from the blob store. Shows `fallback` (the initial of the app or caller, in a circle)
/// until the image arrives, and keeps showing it when the blob is missing or corrupt.
struct BlobImageView: View {
    let ref: BlobRef?
    let images: any ImageProvider
    let fallback: String
    var size: CGFloat = 40
    var circular = true

    @State private var image: NSImage?

    var body: some View {
        ZStack {
            if let image {
                Image(nsImage: image).resizable().scaledToFill()
            } else {
                Color.secondary.opacity(0.25)
                Text(String(fallback.prefix(1)).uppercased())
                    .font(.system(size: size * 0.45, weight: .semibold))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: circular ? size / 2 : size * 0.22, style: .continuous))
        .accessibilityHidden(true)
        .task(id: ref?.id) {
            image = nil
            guard let ref else { return }
            image = await images.image(for: ref)
        }
    }
}

enum CardColors {
    /// An ARGB int from the phone (`Notification.color`, segment and point colours).
    static func color(argb: Int) -> Color {
        let v = UInt32(truncatingIfNeeded: argb)
        return Color(.sRGB, red: Double(v >> 16 & 255) / 255, green: Double(v >> 8 & 255) / 255,
                     blue: Double(v & 255) / 255, opacity: Double(v >> 24 & 255) / 255)
    }
}

/// The result line under a button (SPEC §6.1).
enum CardStatusText {
    static func text(for status: ActionStatus) -> String? {
        switch status {
        case .idle: nil
        case .sending: "Sending…"
        case .sent: "Sent"
        case .asleep: "Phone is asleep — will send when it wakes"
        case .gone: "Notification is gone"
        case .changed: "App changed its buttons"
        case .failed: "Couldn't send"
        }
    }
}
