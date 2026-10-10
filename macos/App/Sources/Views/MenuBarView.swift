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
                .padding(.horizontal, 14)
                .padding(.top, 12)
                .padding(.bottom, 10)

            if !model.banners.isEmpty {
                VStack(spacing: 6) {
                    ForEach(model.banners) { BannerView(banner: $0) }
                }
                .padding(.horizontal, 10)
                .padding(.bottom, 10)
            }

            Divider()

            if model.phase == .ready {
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
        .frame(width: 340)
        .animation(.default, value: model.statusMessage)
        .onAppear { model.refreshPasteboardAccess() }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 3) {
                Text("Carom")
                    .font(.headline)
                HStack(spacing: 6) {
                    ConnectionDot(state: model.connection, paused: model.preferences.paused)
                    Text(statusLine)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }
            Spacer()
        }
    }

    private var statusLine: String {
        guard model.phase == .ready else { return model.phase == .loading ? "Starting…" : "Not set up" }
        let state = model.preferences.paused ? "Paused" : model.connection.label
        return model.hubHost.isEmpty ? state : "\(model.hubHost) · \(state)"
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
                MenuRow(title: model.preferences.paused ? "Resume" : "Pause",
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
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
            .background(hovering && isEnabled ? Color.primary.opacity(0.08) : .clear,
                        in: RoundedRectangle(cornerRadius: 5, style: .continuous))
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
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
                        ForEach(notifications) { NotificationRow(item: $0) }
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
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 8)
            .padding(.top, 6)
            .padding(.bottom, 2)
    }
}

private struct NotificationRow: View {
    @Environment(AppModel.self) private var model
    let item: RecentNotification
    @State private var hovering = false

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(item.appName)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    if model.phones.count > 1 {
                        Text("· \(item.phoneName)")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                    Spacer()
                    Text(item.date, style: .time)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
                if let title = item.title, !title.isEmpty {
                    Text(title)
                        .font(.callout.weight(.medium))
                        .lineLimit(1)
                }
                if let text = item.text, !text.isEmpty {
                    Text(text)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
            if let code = item.code, hovering {
                Button {
                    model.copy(code, sensitive: true)
                } label: {
                    Label(code, systemImage: "doc.on.doc")
                        .font(.caption.monospacedDigit())
                }
                .controlSize(.small)
                .help("Copy code")
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(hovering ? Color.primary.opacity(0.06) : .clear,
                    in: RoundedRectangle(cornerRadius: 6, style: .continuous))
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
}

private struct ClipRow: View {
    @Environment(AppModel.self) private var model
    let item: RecentClip
    @State private var hovering = false

    var body: some View {
        Button {
            model.copy(item.text, sensitive: item.sensitive)
        } label: {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: incoming ? "arrow.down.circle" : "arrow.up.circle")
                    .foregroundStyle(.secondary)
                    .padding(.top, 1)
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
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
            .background(hovering ? Color.primary.opacity(0.06) : .clear,
                        in: RoundedRectangle(cornerRadius: 6, style: .continuous))
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
