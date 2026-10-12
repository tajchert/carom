import AppKit
import CaromProtocol
import CaromStore
import SwiftUI
import UniformTypeIdentifiers

/// A notice at the top of the menu.
struct BannerView: View {
    @Environment(AppModel.self) private var model
    let banner: Banner
    @State private var confirmingReset = false

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: symbol)
                .foregroundStyle(tint)
                .font(.callout)
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 6) {
                Text(banner.message)
                    .font(.callout)
                    .fixedSize(horizontal: false, vertical: true)
                if banner.kind == .tokenRejected {
                    Button("Pair Again…") { confirmingReset = true }
                        .controlSize(.small)
                }
                if banner.kind == .clockSkew {
                    Button("Open Date & Time Settings…") {
                        if let url = URL(string: "x-apple.systempreferences:com.apple.Date-Time-Settings.extension") {
                            NSWorkspace.shared.open(url)
                        }
                    }
                    .controlSize(.small)
                }
                if banner.kind == .revoked, model.phase == .onboarding {
                    Button("Set Up…") { model.request(.onboarding) }
                        .controlSize(.small)
                }
            }
            Spacer(minLength: 0)
            if banner.isDismissible {
                Button {
                    model.dismissBanner(banner)
                } label: {
                    Image(systemName: "xmark")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Dismiss")
            }
        }
        .padding(10)
        .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .confirmationDialog("Pair this Mac again?", isPresented: $confirmingReset) {
            Button("Erase and Pair Again", role: .destructive) { Task { await model.resetAndPairAgain() } }
        } message: {
            Text("This erases this Mac's keys. Then you set it up again from a paired device.")
        }
    }

    private var symbol: String {
        switch banner.style {
        case .info: "info.circle.fill"
        case .warning: "exclamationmark.triangle.fill"
        case .error: "xmark.octagon.fill"
        }
    }

    private var tint: Color {
        switch banner.style {
        case .info: .accentColor
        case .warning: .orange
        case .error: .red
        }
    }
}

/// SF Symbol for a device kind.
func deviceSymbol(_ kind: DeviceKind) -> String {
    switch kind {
    case .phone: "iphone.gen3"
    case .desktop: "desktopcomputer"
    case .cli: "terminal"
    }
}

func kindLabel(_ kind: DeviceKind) -> String {
    switch kind {
    case .phone: "Phone"
    case .desktop: "Computer"
    case .cli: "Command line"
    }
}

/// "Last seen 5 min ago" from an epoch-millisecond value.
func lastSeenText(_ ms: Int?) -> String {
    guard let ms else { return "Never seen" }
    let date = Date(timeIntervalSince1970: Double(ms) / 1000)
    if Date().timeIntervalSince(date) < 60 { return "Seen just now" }
    return "Seen " + date.formatted(.relative(presentation: .named))
}

/// A QR code image drawn crisply (no interpolation).
struct QRImage: View {
    let text: String
    var size: CGFloat = 220

    var body: some View {
        Group {
            if let image = QRCode.image(for: text) {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
            } else {
                Image(systemName: "qrcode")
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
        .padding(12)
        .background(.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityLabel("Pairing QR code")
    }
}

/// Reads a `carom://` URI from pasted or dropped text, image data or image files.
enum CodeInput {
    static func uri(fromText text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.lowercased().hasPrefix("carom://") ? trimmed : nil
    }

    static func uri(fromImageData data: Data) -> String? {
        guard let image = NSImage(data: data) else { return nil }
        return QRCode.caromURI(in: image)
    }

    static func uri(fromFile url: URL) -> String? {
        guard let image = NSImage(contentsOf: url) else { return nil }
        return QRCode.caromURI(in: image)
    }

    enum Result: Sendable {
        case uri(String)
        case noCode
    }

    /// Text first, then images, then image files.
    @MainActor static func read(_ providers: [NSItemProvider]) async -> Result {
        for provider in providers {
            if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
               let text = await loadString(provider), let uri = uri(fromText: text) {
                return .uri(uri)
            }
            if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier),
               let url = await loadFileURL(provider), let uri = uri(fromFile: url) {
                return .uri(uri)
            }
            if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier),
               let data = await loadData(provider, type: .image), let uri = uri(fromImageData: data) {
                return .uri(uri)
            }
        }
        return .noCode
    }

    @MainActor private static func loadData(_ provider: NSItemProvider, type: UTType) async -> Data? {
        await withCheckedContinuation { continuation in
            _ = provider.loadDataRepresentation(forTypeIdentifier: type.identifier) { data, _ in
                continuation.resume(returning: data)
            }
        }
    }

    @MainActor private static func loadString(_ provider: NSItemProvider) async -> String? {
        guard let data = await loadData(provider, type: .plainText) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    @MainActor private static func loadFileURL(_ provider: NSItemProvider) async -> URL? {
        guard let data = await loadData(provider, type: .fileURL) else { return nil }
        return URL(dataRepresentation: data, relativeTo: nil)
    }

    /// Shows an Open panel for an image and decodes it.
    @MainActor static func openImage() -> Result? {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        panel.message = "Choose a screenshot or photo of the QR code."
        guard panel.runModal() == .OK, let url = panel.url else { return nil }
        return uri(fromFile: url).map(Result.uri) ?? .noCode
    }
}

/// A form row explaining something, in secondary text.
struct Footnote: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.callout)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

enum Links {
    static let project = URL(string: "https://github.com/tajchert/carom")!
    static let hubDocs = URL(string: "https://github.com/tajchert/carom#readme")!
    static let notificationSettings = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension")!
    static let privacySettings = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy")!
}

extension Color {
    /// Status green: #1F8A62 in light, #6CDBAC in dark.
    static let statusGreen = Color(nsColor: NSColor(name: nil) { appearance in
        appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            ? NSColor(srgbRed: 0x6C / 255, green: 0xDB / 255, blue: 0xAC / 255, alpha: 1)
            : NSColor(srgbRed: 0x1F / 255, green: 0x8A / 255, blue: 0x62 / 255, alpha: 1)
    })
}

extension Color {
    /// Paused / attention amber (#E8B04A).
    static let statusAmber = Color(red: 0xE8 / 255, green: 0xB0 / 255, blue: 0x4A / 255)
}

/// The Carom "two-rail C" mark, drawn in a 100-unit box (live variant: solid ball).
struct CaromMark: Shape {
    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 100
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: rect.minX + x * s, y: rect.minY + y * s)
        }
        var path = Path()
        path.move(to: p(80, 22))
        path.addLine(to: p(40, 22))
        path.addLine(to: p(22, 50))
        path.addLine(to: p(40, 78))
        path.addLine(to: p(62, 78))
        return path.strokedPath(StrokeStyle(lineWidth: 10 * s, lineCap: .round, lineJoin: .round))
            .union(Path(ellipseIn: CGRect(x: p(80, 78).x - 8.5 * s, y: p(80, 78).y - 8.5 * s,
                                          width: 17 * s, height: 17 * s)))
    }
}

/// The app tile: the mark on the brand gradient (dark: charcoal with a mint mark).
struct CaromTile: View {
    var size: CGFloat = 30
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        RoundedRectangle(cornerRadius: size * 0.233, style: .continuous)
            .fill(LinearGradient(
                colors: dark ? [Color(red: 0.141, green: 0.188, blue: 0.169), Color(red: 0.071, green: 0.094, blue: 0.082)]
                    : [Color(red: 0.165, green: 0.604, blue: 0.447), Color(red: 0.082, green: 0.376, blue: 0.282)],
                startPoint: .top, endPoint: .bottom))
            .frame(width: size, height: size)
            .overlay {
                CaromMark()
                    .fill(dark ? Color(red: 0.424, green: 0.859, blue: 0.675) : Color(red: 0.957, green: 0.984, blue: 0.969))
                    .frame(width: size * 0.64, height: size * 0.64)
            }
            .accessibilityHidden(true)
    }
}
