import AppKit
import CaromDesktop
import SwiftUI

/// The menu-bar popover: status, notices, recent activity and the main actions.
struct MenuBarView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openSettings) private var openSettings

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header

            if !model.banners.isEmpty {
                VStack(spacing: 6) {
                    ForEach(model.banners) { BannerView(banner: $0) }
                }
                .padding(.horizontal, 10)
                .padding(.bottom, 10)
            }

            Divider()

            if model.phase == .ready {
                NowPlayingSection()
                ActivityList()
                    .frame(maxHeight: 360)
            } else if model.phase == .onboarding {
                notSetUp
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 80)
            }

            if let status = model.statusMessage {
                Divider()
                Label(status, systemImage: "checkmark.circle")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .transition(.opacity)
            }

            Divider()
            actions
                .padding(6)
        }
        .frame(width: 348)
        .animation(.default, value: model.statusMessage)
        .onAppear { model.refreshPasteboardAccess() }
    }

    private var header: some View {
        HStack(spacing: 10) {
            CaromTile(size: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text("Carom")
                    .font(.system(size: 13, weight: .semibold))
                HStack(spacing: 5) {
                    Image(systemName: status.symbol)
                        .font(.system(size: 11))
                        .foregroundStyle(status.tint)
                    Text(status.text)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }
            Spacer(minLength: 0)
            if model.phase == .ready, model.preferences.paused {
                Button("Resume") { model.setPaused(false) }
                    .controlSize(.small)
            } else if status.live {
                Image(systemName: "lock.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(Color.statusGreen)
                    .help("End-to-end encrypted")
                    .accessibilityLabel("End-to-end encrypted")
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .background(LinearGradient(colors: [Color("BrandGreen").opacity(0.11), Color("BrandGreen").opacity(0)],
                                   startPoint: .top, endPoint: .bottom))
    }

    private struct Status {
        var symbol: String
        var tint: Color
        var text: String
        var live = false
    }

    private var status: Status {
        guard model.phase == .ready else {
            return Status(symbol: "circle.dashed", tint: .secondary,
                          text: model.phase == .loading ? "Starting…" : "Not set up")
        }
        if model.preferences.paused {
            return Status(symbol: "pause.circle.fill", tint: Color(red: 0.91, green: 0.69, blue: 0.29),
                          text: "Paused · notifications stay on phone")
        }
        let host = model.hubHost.isEmpty ? "" : " · \(model.hubHost)"
        switch model.connection {
        case .connected:
            return Status(symbol: "checkmark.circle.fill", tint: .statusGreen, text: "Live" + host, live: true)
        case .connecting, .asleep:
            return Status(symbol: "arrow.triangle.2.circlepath.circle.fill", tint: .secondary,
                          text: model.connection.label + host)
        case .noNetwork:
            return Status(symbol: "wifi.slash", tint: .secondary, text: "Offline · No network")
        case .stopped:
            return Status(symbol: "exclamationmark.triangle.fill", tint: .red, text: "Offline · Stopped, pair again")
        case .offline:
            return Status(symbol: "icloud.slash.fill", tint: .secondary, text: "Offline" + host)
        }
    }

    private var notSetUp: some View {
        VStack(spacing: 10) {
            Image(systemName: "arrow.left.arrow.right.circle")
                .font(.system(size: 30, weight: .light))
                .foregroundStyle(.secondary)
            Text("Carom isn't set up yet.")
                .font(.callout)
            Button("Set Up…") { show(.onboarding) }
                .buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
    }

    private var actions: some View {
        VStack(spacing: 0) {
            if model.phase == .ready {
                MenuRow(title: "Send Clipboard", symbol: "doc.on.clipboard", shortcut: SendHotkey.display) {
                    Task { await model.sendClipboardNow() }
                }
                .disabled(model.connection == .stopped)
                MenuRow(title: model.preferences.paused ? "Resume Forwarding" : "Pause Forwarding",
                        symbol: model.preferences.paused ? "play.circle" : "pause.circle") {
                    model.setPaused(!model.preferences.paused)
                }
                MenuRow(title: "Add Device…", symbol: "plus.circle") { show(.addDevice) }
                    .disabled(model.connection == .stopped)
            }
            MenuRow(title: "Settings…", symbol: "gearshape", shortcut: "⌘,") {
                openSettings()
                AppEnvironment.activate()
            }
            .keyboardShortcut(",", modifiers: .command)
            MenuRow(title: "Quit Carom", symbol: "power", shortcut: "⌘Q") { NSApp.terminate(nil) }
                .keyboardShortcut("q", modifiers: .command)
        }
    }

    private func show(_ window: AppWindow) {
        openWindow(id: window.rawValue)
        AppEnvironment.activate()
    }
}

/// A full-width, menu-like button with hover highlight.
struct MenuRow: View {
    let title: String
    let symbol: String
    var shortcut: String?
    let action: () -> Void
    @State private var hovering = false
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: symbol)
                    .frame(width: 18)
                    .foregroundStyle(.secondary)
                Text(title)
                Spacer()
                if let shortcut {
                    Text(shortcut)
                        .font(.callout)
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
            .background(hovering && isEnabled ? Color.primary.opacity(0.08) : .clear,
                        in: RoundedRectangle(cornerRadius: 5, style: .continuous))
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
    }
}

/// The Now Playing cards of the phones' media sessions, above the activity list.
private struct NowPlayingSection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let sessions = model.nowPlaying
        if !sessions.isEmpty {
            VStack(spacing: 8) {
                ForEach(sessions) { session in
                    NowPlayingView(session: session, images: model.blobs,
                                   canControl: model.canControlMedia(session)) { control in
                        Task { await model.sendMedia(control, sessionID: session.id) }
                    }
                }
            }
            .padding(10)
            Divider()
        }
    }
}

/// Recent notifications and clipboard items (in memory only).
struct ActivityList: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let notifications = model.activity.notifications
        let clips = model.activity.clips
        if notifications.isEmpty && clips.isEmpty {
            VStack(spacing: 8) {
                Image(systemName: "tray")
                    .font(.system(size: 26, weight: .light))
                    .foregroundStyle(.secondary)
                Text("Nothing yet")
                    .font(.callout.weight(.medium))
                Text("Notifications from your phone and shared clipboard items show up here.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 24)
            .padding(.horizontal, 20)
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 2) {
                    if !notifications.isEmpty {
                        SectionTitle("Notifications")
                        ForEach(notifications) { NotificationRow(item: $0, newest: $0.id == notifications.first?.id) }
                    }
                    if !clips.isEmpty {
                        SectionTitle("Clipboard")
                        ForEach(clips) { ClipRow(item: $0) }
                    }
                }
                .padding(.horizontal, 6)
                .padding(.vertical, 6)
            }
        }
    }
}

private struct SectionTitle: View {
    let title: String
    init(_ title: String) { self.title = title }

    var body: some View {
        Text(title)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 10)
            .padding(.top, 8)
            .padding(.bottom, 4)
    }
}

private struct NotificationRow: View {
    @Environment(AppModel.self) private var model
    let item: RecentNotification
    var newest = false
    @State private var hovering = false

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            AppIconImage(ref: item.icon, appName: item.appName, images: model.blobs)
                .frame(width: 26, height: 26)
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(item.appName)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                    if model.phones.count > 1 {
                        Text("· \(item.phoneName)")
                            .font(.system(size: 11))
                            .foregroundStyle(.tertiary)
                    }
                    Spacer()
                    Text(item.date.formatted(date: .omitted, time: .shortened))
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
                if let title = item.title, !title.isEmpty {
                    Text(title)
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(1)
                }
                if let text = item.text, !text.isEmpty {
                    Text(text)
                        .font(.system(size: 13))
                        .lineLimit(2)
                }
                if let code = item.code, hovering {
                    HStack(spacing: 8) {
                        Text(code)
                            .font(.system(size: 13, design: .monospaced).weight(.medium))
                        Button("Copy code") { model.copy(code, sensitive: true) }
                            .controlSize(.small)
                    }
                    .padding(.top, 3)
                }
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(rowTint, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
        .onHover { hovering = $0 }
        .contextMenu {
            if let code = item.code {
                Button("Copy Code \(code)") { model.copy(code, sensitive: true) }
            }
            if let text = item.text, !text.isEmpty {
                Button("Copy Text") { model.copy(text, sensitive: false) }
            }
            Button("Dismiss on Phone") { Task { await model.dismissOnPhone(item) } }
            Divider()
            Button(model.preferences.isMuted(item.appId) ? "Unmute \(item.appName)" : "Mute \(item.appName)") {
                model.setMuted(item.appId, !model.preferences.isMuted(item.appId))
            }
        }
    }

    private var rowTint: Color {
        if hovering { return Color.primary.opacity(0.06) }
        return newest ? Color.statusGreen.opacity(0.09) : .clear
    }
}

private struct ClipRow: View {
    @Environment(AppModel.self) private var model
    let item: RecentClip
    @State private var hovering = false

    var body: some View {
        Button {
            model.copy(item.text, sensitive: item.sensitive)
        } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: incoming ? "arrow.down.left" : "arrow.up.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(incoming ? Color.statusGreen : Color.secondary)
                    .frame(width: 26, height: 20)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.preview)
                        .font(.callout)
                        .foregroundStyle(item.sensitive ? .secondary : .primary)
                        .lineLimit(1)
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
            .background(hovering ? Color.primary.opacity(0.06) : .clear,
                        in: RoundedRectangle(cornerRadius: 7, style: .continuous))
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .help("Copy again")
    }

    private var incoming: Bool {
        if case .received = item.direction { return true }
        return false
    }

    private var detail: String {
        let time = item.date.formatted(date: .omitted, time: .shortened)
        switch item.direction {
        case .received(let from): return "From \(from) · \(time)"
        case .sent(let to) where to.count == 1: return "To \(to[0]) · \(time)"
        case .sent(let to): return "To \(to.count) devices · \(time)"
        }
    }
}
