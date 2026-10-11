import AppKit
import CaromDesktop
import CaromProtocol
import Foundation
import ImageIO
import SwiftUI
import Testing
import UniformTypeIdentifiers
@testable import Carom

/// Renders the PNGs in `docs/images/` used by the root README. Skipped unless
/// `CAROM_README_IMAGES=1` reaches the test runner; run `macos/App/Tools/readme-images.sh`.
///
/// The menu is the real `MenuBarView` on an `AppModel` wired to the test fakes. The macOS
/// notification banners are illustrations (real banners cannot be captured headlessly). The
/// Android screens come from the Android screenshot-test references.
@MainActor
@Suite("README images")
struct ReadmeImages {
    nonisolated static let enabled = ProcessInfo.processInfo.environment["CAROM_README_IMAGES"] == "1"

    static let repo = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent()
    static let out = repo.appending(path: "docs/images")

    static let androidHome = "android/feature/home/impl/src/screenshotTestDebug/reference/com/mtajchert/carom/app/feature/home/impl/HomeScreenScreenshotTestsKt/HomePairedScreenshotTest_Phone_eae32a36_0.png"
    static let androidApps = "android/feature/notifications/impl/src/screenshotTestDebug/reference/com/mtajchert/carom/app/feature/notifications/impl/apps/AppsScreenScreenshotTestsKt/AppsPopulatedScreenshotTest_Phone_eae32a36_0.png"
    static let androidPairing = "android/feature/pairing/impl/src/screenshotTestDebug/reference/com/mtajchert/carom/app/feature/pairing/impl/PairingScreenScreenshotTestsKt/PairingCodeScreenshotTest_Light_78856152_0.png"

    @Test(.enabled(if: enabled)) func render() async throws {
        try FileManager.default.createDirectory(at: Self.out, withIntermediateDirectories: true)

        let menu = try await Self.menuImage()
        try Self.write(MenuShot(menu: menu), "mac-menu.png")
        try Self.write(NotificationShot(), "mac-notification.png")

        let home = try Self.android(Self.androidHome)
        try Self.write(HeroShot(phone: home, menu: menu), "hero.png")

        // Crop the empty space under the content (heights in source pixels, 1082 px wide).
        try Self.write(PhoneShot(image: home.cropped(height: 1300)), "android-home.png")
        try Self.write(PhoneShot(image: try Self.android(Self.androidApps).cropped(height: 2160)), "android-apps.png")
        try Self.write(PhoneShot(image: try Self.android(Self.androidPairing).cropped(height: 1760)),
                       "android-pairing.png")
    }

    // MARK: The real menu

    /// `MenuBarView` with one paired phone, three notifications and two clipboard items.
    static func menuImage() async throws -> NSImage {
        let pixel = record(phoneA, "Michał's Pixel", kind: .phone)
        let h = Harness(directory: [entry(pixel)])
        var day = DateComponents(year: 2026, month: 10, day: 11, hour: 9, minute: 12)
        h.clock.now = try #require(Calendar.current.date(from: day))
        await h.start()
        h.model.realtimeChanged(.connected)

        await h.model.sendClipboard(ClipboardItem(text: "Meeting notes for Friday: agenda, budget, next steps",
                                                  sensitive: false))
        func at(_ minute: Int) throws {
            day.minute = minute
            h.clock.now = try #require(Calendar.current.date(from: day))
        }
        func post(_ key: String, _ appId: String, _ app: String, _ title: String, _ text: String) async {
            let n = NotificationPosted(key: key, app: .init(id: appId, name: app), title: title, text: text,
                                       postedAt: 1)
            await h.model.dispatch(.notificationPosted(n), msgId: "m-\(key)", sender: pixel)
        }
        try at(24)
        await h.model.dispatch(.clipboardSet(ClipboardSet(text: "https://maps.app.goo.gl/x7Lk2Qe9")),
                               msgId: "m-clip", sender: pixel)
        try at(30)
        await post("cal", "com.google.android.calendar", "Calendar", "Design review", "10:00 – 10:30 · Room 4")
        try at(38)
        await post("sig", "org.thoughtcrime.securesms", "Signal", "Anna", "Are we still on for 7?")
        try at(41)
        await post("otp", "com.google.android.gm", "Gmail", "Sign-in attempt", "Your login code is 482913")

        // "Sent to …" disappears after 5 s; wait so the menu shows its resting state.
        try await Task.sleep(for: .seconds(5.3))
        #expect(h.model.statusMessage == nil)

        let view = MenuBarView()
            .environment(h.model)
            .background(Palette.popover)
            .clipShape(RoundedRectangle(cornerRadius: Palette.popoverRadius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Palette.popoverRadius, style: .continuous)
                .strokeBorder(Color.black.opacity(0.12), lineWidth: 0.5))
            .environment(\.colorScheme, .light)
        return try await snapshot(view)
    }

    /// Draws a SwiftUI view through AppKit (so controls render like on screen) at 2x.
    static func snapshot(_ view: some View) async throws -> NSImage {
        let host = NSHostingView(rootView: view)
        host.appearance = NSAppearance(named: .aqua)
        let size = host.fittingSize
        let window = NSWindow(contentRect: CGRect(origin: CGPoint(x: -10_000, y: -10_000), size: size),
                              styleMask: [.borderless], backing: .buffered, defer: false)
        window.appearance = NSAppearance(named: .aqua)
        window.isOpaque = false
        window.backgroundColor = .clear
        window.contentView = host
        host.frame = CGRect(origin: .zero, size: size)
        host.layoutSubtreeIfNeeded()
        try await Task.sleep(for: .milliseconds(300))
        host.layoutSubtreeIfNeeded()
        host.display()

        let rep = try #require(NSBitmapImageRep(
            bitmapDataPlanes: nil, pixelsWide: Int(size.width * 2), pixelsHigh: Int(size.height * 2),
            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
            bytesPerRow: 0, bitsPerPixel: 0))
        rep.size = size
        host.cacheDisplay(in: host.bounds, to: rep)
        window.contentView = nil
        let image = NSImage(size: size)
        image.addRepresentation(rep)
        return image
    }

    // MARK: Files

    static func android(_ path: String) throws -> NSImage {
        try #require(NSImage(contentsOf: repo.appending(path: path)), "missing \(path)")
    }

    /// Renders `view` at 2x and writes a PNG.
    static func write(_ view: some View, _ name: String) throws {
        let renderer = ImageRenderer(content: view.environment(\.colorScheme, .light))
        renderer.scale = 2
        renderer.isOpaque = false
        let cgImage = try #require(renderer.cgImage, "could not render \(name)")
        let url = out.appending(path: name)
        let dest = try #require(CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(dest, cgImage, nil)
        #expect(CGImageDestinationFinalize(dest))
    }
}

// MARK: - Style

enum Palette {
    /// The app accent (Resources/Assets.xcassets/AccentColor).
    static let accent = Color(red: 0.204, green: 0.478, blue: 0.780)
    static let popover = Color(red: 0.965, green: 0.965, blue: 0.97)
    static let popoverRadius: CGFloat = 14
    static let card = Color(red: 0.957, green: 0.961, blue: 0.973)
    static let ink = Color(red: 0.12, green: 0.13, blue: 0.16)

    /// A soft blue-violet-pink wallpaper. Drawn by upscaling a 2x2 bitmap: a smooth gradient
    /// without dithering noise keeps the PNGs small.
    @MainActor static var wallpaper: some View {
        Image(nsImage: wallpaperPixels).resizable().interpolation(.high)
    }

    @MainActor static let wallpaperPixels: NSImage = {
        let pixels: [UInt8] = [158, 191, 237, 255, 196, 186, 235, 255,
                               190, 184, 234, 255, 242, 204, 214, 255]
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 2, pixelsHigh: 2, bitsPerSample: 8,
                                   samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                   colorSpaceName: .deviceRGB, bytesPerRow: 8, bitsPerPixel: 32)!
        rep.bitmapData!.update(from: pixels, count: pixels.count)
        let image = NSImage(size: NSSize(width: 2, height: 2))
        image.addRepresentation(rep)
        return image
    }()
}

extension NSImage {
    /// The top `height` pixels of a bitmap image.
    func cropped(height: Int) -> NSImage {
        guard let cg = cgImage(forProposedRect: nil, context: nil, hints: nil),
              let top = cg.cropping(to: CGRect(x: 0, y: 0, width: cg.width, height: min(height, cg.height)))
        else { return self }
        return NSImage(cgImage: top, size: NSSize(width: top.width, height: top.height))
    }

    var aspect: CGFloat { size.width / max(size.height, 1) }

    /// The colour of the top-left pixel (the screen background of a screenshot).
    var cornerColor: Color {
        guard let cg = cgImage(forProposedRect: nil, context: nil, hints: nil),
              let rep = Optional(NSBitmapImageRep(cgImage: cg)),
              let color = rep.colorAt(x: 4, y: 4) else { return .white }
        return Color(nsColor: color)
    }
}

/// A stand-in app icon for the banners: the menu-bar symbol on the accent colour.
struct CaromIcon: View {
    var size: CGFloat = 36

    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.235, style: .continuous)
            .fill(LinearGradient(colors: [Palette.accent.mix(.white, 0.25), Palette.accent],
                                 startPoint: .top, endPoint: .bottom))
            .overlay(Image(systemName: "arrow.left.arrow.right")
                .font(.system(size: size * 0.42, weight: .bold))
                .foregroundStyle(.white))
            .frame(width: size, height: size)
            .shadow(color: .black.opacity(0.15), radius: 1, y: 0.5)
    }
}

extension Color {
    func mix(_ other: NSColor, _ amount: CGFloat) -> Color {
        Color(nsColor: NSColor(self).blended(withFraction: amount, of: other) ?? NSColor(self))
    }
}

// MARK: - mac-notification.png

/// A macOS 26-style notification banner (illustration).
struct BannerCard: View {
    var title: String
    var subtitle: String
    var message: String
    var time = "now"
    var action: String?

    var body: some View {
        HStack(alignment: .center, spacing: 11) {
            CaromIcon(size: 36)
            VStack(alignment: .leading, spacing: 1) {
                HStack(alignment: .firstTextBaseline) {
                    Text(title).font(.system(size: 13, weight: .semibold))
                    Spacer(minLength: 6)
                    if action == nil {  // hovering shows the action and hides the time
                        Text(time).font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                }
                Text(subtitle).font(.system(size: 13))
                Text(message).font(.system(size: 13)).foregroundStyle(Palette.ink.opacity(0.85))
            }
            .lineLimit(1)
            if let action {
                Text(action)
                    .font(.system(size: 12, weight: .medium))
                    .padding(.horizontal, 11)
                    .padding(.vertical, 6)
                    .background(Color.black.opacity(0.07), in: Capsule())
            }
        }
        .foregroundStyle(Palette.ink)
        .padding(.horizontal, 13)
        .padding(.vertical, 11)
        .frame(width: 384)
        .background {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(Color.white.opacity(0.78))
                .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .fill(LinearGradient(colors: [.white.opacity(0.35), .clear], startPoint: .top, endPoint: .bottom)))
                .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.9), lineWidth: 0.75))
                .shadow(color: .black.opacity(0.14), radius: 14, y: 6)
                .shadow(color: .black.opacity(0.06), radius: 1, y: 0.5)
        }
    }
}

struct NotificationShot: View {
    var body: some View {
        VStack(alignment: .trailing, spacing: 10) {
            BannerCard(title: "Signal · Anna", subtitle: "Michał's Pixel", message: "Are we still on for 7?")
            BannerCard(title: "Gmail · Sign-in attempt", subtitle: "Michał's Pixel",
                       message: "Your login code is 482913", action: "Copy code")
        }
        .padding(.top, 22)
        .padding(.trailing, 20)
        .padding(.leading, 70)
        .padding(.bottom, 30)
        .background(Palette.wallpaper)
        .background(.white)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(2)
    }
}

// MARK: - mac-menu.png

struct MenuShot: View {
    let menu: NSImage

    var body: some View {
        Image(nsImage: menu)
            .shadow(color: .black.opacity(0.22), radius: 18, y: 8)
            .shadow(color: .black.opacity(0.08), radius: 2, y: 1)
            .padding(.horizontal, 36)
            .padding(.top, 24)
            .padding(.bottom, 48)
    }
}

// MARK: - android-*.png

struct PhoneShot: View {
    let image: NSImage
    var width: CGFloat = 400

    var body: some View {
        Image(nsImage: image)
            .resizable()
            .interpolation(.high)
            .aspectRatio(image.aspect, contentMode: .fit)
            .frame(width: width)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous)
                .strokeBorder(Color.black.opacity(0.14), lineWidth: 1))
            .padding(1)
    }
}

// MARK: - hero.png

/// A simple Android phone: dark frame, status bar, screenshot.
struct PhoneFrame: View {
    let screen: NSImage
    var height: CGFloat = 470

    var body: some View {
        let inner = height - 20
        let width = (inner * 0.47).rounded()
        let statusBar: CGFloat = 28
        let navBar: CGFloat = 18
        VStack(spacing: 0) {
            HStack(spacing: 5) {
                Text("9:41").font(.system(size: 11, weight: .semibold))
                Spacer()
                Image(systemName: "wifi").font(.system(size: 10, weight: .semibold))
                Image(systemName: "cellularbars").font(.system(size: 10, weight: .semibold))
                Image(systemName: "battery.75percent").font(.system(size: 12))
            }
            .foregroundStyle(Palette.ink)
            .padding(.horizontal, 18)
            .frame(width: width, height: statusBar)
            .overlay(Circle().fill(Color.black).frame(width: 10, height: 10))
            Image(nsImage: screen)
                .resizable()
                .interpolation(.high)
                .aspectRatio(contentMode: .fill)
                .frame(width: width, height: inner - statusBar - navBar, alignment: .top)
                .clipped()
            Capsule().fill(Color.black.opacity(0.7)).frame(width: 72, height: 4)
                .frame(width: width, height: navBar)
        }
        .background(screen.cornerColor)
        .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 40, style: .continuous).fill(Color(white: 0.11)))
        .overlay(RoundedRectangle(cornerRadius: 40, style: .continuous)
            .strokeBorder(Color(white: 0.32), lineWidth: 1.5))
        .shadow(color: .black.opacity(0.18), radius: 20, y: 10)
    }
}

/// A dashed arrow pointing right.
struct DashedArrow: View {
    var width: CGFloat

    var body: some View {
        HStack(spacing: 0) {
            Line()
                .stroke(Palette.accent.opacity(0.7), style: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [5, 6]))
                .frame(width: width - 8, height: 2)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(Palette.accent.opacity(0.85))
                .offset(x: -3)
        }
        .frame(width: width)
    }

    struct Line: Shape {
        func path(in rect: CGRect) -> Path {
            Path { p in
                p.move(to: CGPoint(x: 0, y: rect.midY))
                p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
            }
        }
    }
}

struct HubPill: View {
    var body: some View {
        HStack(spacing: 10) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
                    .background(Palette.accent, in: Circle())
                VStack(alignment: .leading, spacing: 1) {
                    Text("Your Hub").font(.system(size: 15, weight: .semibold))
                    Text("Cloudflare Worker").font(.system(size: 12)).foregroundStyle(.secondary)
                }
            }
            .foregroundStyle(Palette.ink)
            .padding(.leading, 8)
            .padding(.trailing, 18)
            .padding(.vertical, 8)
            .background(.white, in: Capsule())
            .overlay(Capsule().strokeBorder(Color.black.opacity(0.08), lineWidth: 1))
            .shadow(color: .black.opacity(0.08), radius: 10, y: 4)
    }
}

/// A Mac desktop: wallpaper, menu bar and the Carom menu open under its status item.
struct MacDesktop: View {
    let menu: NSImage
    let size = CGSize(width: 470, height: 440)
    let menuScale: CGFloat = 0.74

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Palette.wallpaper
            VStack(spacing: 0) {
                menuBar
                Spacer(minLength: 0)
            }
        }
        .frame(width: size.width, height: size.height)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
            .strokeBorder(Color.black.opacity(0.1), lineWidth: 1))
        .shadow(color: .black.opacity(0.14), radius: 20, y: 10)
    }

    private var menuBar: some View {
        HStack(spacing: 15) {
            Image(systemName: "apple.logo").font(.system(size: 13))
            Text("Finder").font(.system(size: 12.5, weight: .bold)).fixedSize()
            ForEach(["File", "Edit", "View"], id: \.self) {
                Text($0).font(.system(size: 12.5)).fixedSize()
            }
            Spacer()
            HStack(spacing: 13) {
                // The Carom status item, highlighted while its menu is open.
                Image(systemName: "arrow.left.arrow.right.circle")
                    .font(.system(size: 13, weight: .medium))
                    .frame(width: 26, height: 20)
                    .background(Color.black.opacity(0.1), in: RoundedRectangle(cornerRadius: 5, style: .continuous))
                    .overlay(alignment: .top) {
                        Image(nsImage: menu)
                            .resizable()
                            .frame(width: menu.size.width * menuScale, height: menu.size.height * menuScale)
                            .shadow(color: .black.opacity(0.22), radius: 14, y: 6)
                            .fixedSize()
                            .offset(y: 28)
                    }
                Image(systemName: "battery.75percent").font(.system(size: 14))
                Image(systemName: "wifi").font(.system(size: 12.5, weight: .medium))
                Image(systemName: "switch.2").font(.system(size: 12))
                Text("Sun 9:41").fixedSize().font(.system(size: 12.5))
            }
        }
        .foregroundStyle(Palette.ink)
        .padding(.horizontal, 14)
        .frame(height: 26)
        .background(Color.white.opacity(0.45))
    }
}

struct HeroShot: View {
    let phone: NSImage
    let menu: NSImage

    var body: some View {
        HStack(spacing: 0) {
            VStack(spacing: 12) {
                PhoneFrame(screen: phone, height: 440)
                caption("Android", "forwards notifications")
            }
            Spacer(minLength: 0)
            VStack(spacing: 14) {
                HStack(spacing: 8) {
                    DashedArrow(width: 52)
                    HubPill()
                    DashedArrow(width: 52)
                }
                Label("End-to-end encrypted", systemImage: "lock.shield")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Palette.accent)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Palette.accent.opacity(0.1), in: Capsule())
                Text("The Hub relays ciphertext only")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
            .offset(y: -18)
            Spacer(minLength: 0)
            VStack(spacing: 12) {
                MacDesktop(menu: menu)
                caption("macOS", "shows them, syncs the clipboard")
            }
        }
        .padding(.horizontal, 48)
        .padding(.vertical, 24)
        .frame(width: 1200, height: 550)
        .background(Palette.card)
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
    }

    private func caption(_ title: String, _ detail: String) -> some View {
        HStack(spacing: 5) {
            Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.ink)
            Text(detail).font(.system(size: 13)).foregroundStyle(.secondary)
        }
    }
}
