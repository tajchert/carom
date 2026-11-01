import AppKit
import SwiftUI

/// First run: welcome → connect (setup or pairing code) → name → permissions → done (docs/CLIENTS.md).
struct OnboardingView: View {
    enum Step: Equatable {
        case welcome
        case connect
        case hubHelp
        case confirm(IncomingLink)
        case waitingForApproval(fingerprint: String)
        case name
        case permissions
        case done

        /// Steps where an incoming `carom://` link may still start enrollment.
        var acceptsLink: Bool {
            switch self {
            case .welcome, .connect, .hubHelp, .confirm: true
            default: false
            }
        }
    }

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openWindow) private var openWindow
    @State private var step: Step = .welcome
    @State private var started = false

    var body: some View {
        VStack(spacing: 0) {
            Group {
                switch step {
                case .welcome: WelcomeStep(step: $step)
                case .connect: ConnectStep(step: $step)
                case .hubHelp: HubHelpStep(step: $step)
                case .confirm(let link): ConfirmStep(link: link, step: $step)
                case .waitingForApproval(let fingerprint): WaitingStep(fingerprint: fingerprint, step: $step)
                case .name: NameStep(step: $step)
                case .permissions: PermissionsStep(step: $step)
                case .done: DoneStep(close: { dismiss() }, addPhone: {
                    dismiss()
                    openWindow(id: AppWindow.addDevice.rawValue)
                    AppEnvironment.activate()
                })
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .transition(.opacity)
        }
        .padding(32)
        .frame(width: 520, height: 480)
        .animation(.easeInOut(duration: 0.2), value: step)
        .onAppear {
            guard !started else { return }
            started = true
            // Resume after connecting (for example the app quit before naming the device).
            if model.phase == .onboarding, model.deviceId != nil { step = .name }
            if model.phase == .ready { step = .done }
            takePendingLink()
        }
        .onChange(of: model.pendingLink) { _, _ in takePendingLink() }
        .onChange(of: model.phase) { _, phase in
            // Set up elsewhere while this window was open (for example a scripted Debug launch):
            // don't leave it on Welcome.
            if phase == .ready, step == .welcome || step == .connect || step == .hubHelp {
                step = .done
            }
        }
    }

    /// A `carom://` link opened the app: ask before using it, and only while still connecting.
    private func takePendingLink() {
        guard let link = model.consumePendingLink() else { return }
        if step.acceptsLink {
            step = .confirm(link)
        } else {
            AppEnvironment.showAlreadyConnected()
        }
    }
}

// MARK: - Steps

private struct StepHeader: View {
    let symbol: String
    var brand = false
    var statusSymbol = false
    let title: String
    let subtitle: String

    var body: some View {
        VStack(spacing: 10) {
            if brand {
                CaromTile(size: 72)
            } else {
                Image(systemName: symbol)
                    .font(.system(size: 40, weight: .light))
                    .foregroundStyle(statusSymbol ? AnyShapeStyle(Color.statusGreen) : AnyShapeStyle(.tint))
                    .symbolRenderingMode(.hierarchical)
            }
            Text(title)
                .font(.title2.weight(.semibold))
            Text(subtitle)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: 420)
    }
}

private struct WelcomeStep: View {
    @Binding var step: OnboardingView.Step

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            StepHeader(symbol: "", brand: true,
                       title: "Welcome to Carom",
                       subtitle: "See your phone's notifications on this Mac and share the clipboard between your devices. Everything is end-to-end encrypted and goes through your own Hub on Cloudflare.")
            Spacer()
            VStack(spacing: 12) {
                Button {
                    step = .connect
                } label: {
                    Text("Get Started").frame(maxWidth: 260)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .keyboardShortcut(.defaultAction)
                Button("Don't have a Hub yet?") { step = .hubHelp }
                    .buttonStyle(.link)
                    .font(.callout)
            }
        }
    }
}

private struct ConnectStep: View {
    @Binding var step: OnboardingView.Step
    @Environment(AppModel.self) private var model
    @State private var uri = ""
    @State private var error: String?
    @State private var working = false
    @State private var dropTargeted = false

    var body: some View {
        VStack(spacing: 18) {
            StepHeader(symbol: "qrcode.viewfinder",
                       title: "Connect to Your Hub",
                       subtitle: "Use the setup code from carom-hub deploy if this is your first device, or the code from Add Device on a device that already uses Carom.")

            dropZone

            TextField("carom://…", text: $uri, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .font(.system(.callout, design: .monospaced))
                .lineLimit(2...3)
                .disabled(working)
                .onSubmit(connect)

            if let error {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.callout)
                    .foregroundStyle(.red)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 0)

            HStack {
                Button("Back") { step = .welcome }
                    .disabled(working)
                Button("Don't have a Hub yet?") { step = .hubHelp }
                    .buttonStyle(.link)
                    .disabled(working)
                Spacer()
                if working { ProgressView().controlSize(.small) }
                Button("Continue", action: connect)
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                    .disabled(uri.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || working)
            }
        }
    }

    private var dropZone: some View {
        HStack(spacing: 14) {
            Image(systemName: "qrcode")
                .font(.system(size: 28, weight: .light))
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 4) {
                Text("Drop a QR code image here")
                    .font(.callout.weight(.medium))
                HStack(spacing: 8) {
                    Button("Open Image…") { handle(CodeInput.openImage()) }
                    PasteButton(supportedContentTypes: [.plainText, .image, .fileURL]) { providers in
                        Task { handle(await CodeInput.read(providers)) }
                    }
                    .labelStyle(.titleOnly)
                }
                .controlSize(.small)
            }
            Spacer()
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(style: StrokeStyle(lineWidth: 1.5, dash: [5, 4]))
                .foregroundStyle(dropTargeted ? Color.accentColor : Color.secondary.opacity(0.4))
        )
        .onDrop(of: [.fileURL, .image, .plainText], isTargeted: $dropTargeted) { providers in
            Task { handle(await CodeInput.read(providers)) }
            return true
        }
        .disabled(working)
    }

    private func handle(_ result: CodeInput.Result?) {
        switch result {
        case .uri(let value)?:
            uri = value
            error = nil
            connect()
        case .noCode?:
            error = UserMessages.noQRInImage
        case nil:
            break
        }
    }

    private func connect() {
        let value = uri.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, !working else { return }
        working = true
        error = nil
        Task {
            defer { working = false }
            do {
                step = try await model.connect(uri: value).step
                uri = ""
            } catch let e as AppError {
                error = e.message
            } catch {
                self.error = UserMessages.describe(error)
            }
        }
    }
}

private extension AppModel.ConnectResult {
    var step: OnboardingView.Step {
        switch self {
        case .setUp: .name
        case .waiting(let fingerprint): .waitingForApproval(fingerprint: fingerprint)
        }
    }
}

private struct ConfirmStep: View {
    let link: IncomingLink
    @Binding var step: OnboardingView.Step
    @Environment(AppModel.self) private var model
    @State private var error: String?
    @State private var working = false

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            StepHeader(symbol: link.kind == .setup ? "server.rack" : "link",
                       title: link.kind == .setup ? "Set Up Your Hub?" : "Join Your Devices?",
                       subtitle: "Carom was opened with a link to \(link.host). Continue only if you started this yourself.")
            if let error {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.callout)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            }
            Spacer()
            HStack {
                Button("Cancel") { step = .connect }
                    .disabled(working)
                Spacer()
                if working { ProgressView().controlSize(.small) }
                Button("Continue", action: proceed)
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                    .disabled(working)
            }
        }
    }

    private func proceed() {
        working = true
        error = nil
        Task {
            defer { working = false }
            do {
                step = try await model.connect(uri: link.uri).step
            } catch let e as AppError {
                error = e.message
            } catch {
                self.error = UserMessages.describe(error)
            }
        }
    }
}

private struct HubHelpStep: View {
    static let command = """
    git clone https://github.com/tajchert/carom.git && cd carom
    npm install
    node hub/bin/carom-hub.mjs deploy
    """

    @Binding var step: OnboardingView.Step
    @State private var copied = false

    var body: some View {
        VStack(spacing: 14) {
            Text("Set Up Your Hub")
                .font(.title2.weight(.semibold))
            Text("Your Hub is a free Cloudflare Worker that passes encrypted messages between your devices. You set it up once, from a computer with Node.js 22 or newer and a free Cloudflare account:")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Text(Self.command)
                .font(.system(size: 11, design: .monospaced))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            HStack(spacing: 10) {
                Button(copied ? "Copied" : "Copy") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(Self.command, forType: .string)
                    copied = true
                }
                Button("Open Terminal", action: Self.openTerminal)
                Link("Setup Guide", destination: Links.setupGuide)
            }
            .controlSize(.small)
            Text("It logs you in to Cloudflare, then opens Carom on this Mac with your setup code.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            HStack {
                Spacer()
                Button("I Have a Code") { step = .connect }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
            }
        }
        .frame(maxWidth: 440)
    }

    private static func openTerminal() {
        guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: "com.apple.Terminal") else { return }
        NSWorkspace.shared.openApplication(at: url, configuration: NSWorkspace.OpenConfiguration())
    }
}

private struct WaitingStep: View {
    let fingerprint: String
    @Binding var step: OnboardingView.Step
    @Environment(AppModel.self) private var model
    @State private var error: String?
    @State private var task: Task<Void, Never>?

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            StepHeader(symbol: "checkmark.shield",
                       title: "Approve on Your Other Device",
                       subtitle: "Check that your other device shows the same code, then approve it there.")
            Text(fingerprint)
                .font(.system(size: 28, weight: .semibold, design: .monospaced))
                .textSelection(.enabled)
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            if let error {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.callout)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            } else {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Waiting for approval…")
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            HStack {
                Button(error == nil ? "Cancel" : "Back") {
                    task?.cancel()
                    step = .connect
                }
                Spacer()
            }
        }
        .onAppear(perform: wait)
        .onDisappear { task?.cancel() }
    }

    private func wait() {
        task = Task {
            do {
                try await model.completeJoin()
                step = .name
            } catch is CancellationError {
            } catch {
                guard !Task.isCancelled else { return }
                self.error = UserMessages.describe(error)
            }
        }
    }
}

private struct NameStep: View {
    @Binding var step: OnboardingView.Step
    @Environment(AppModel.self) private var model
    @State private var name = ""

    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            StepHeader(symbol: "laptopcomputer",
                       title: "Name This Mac",
                       subtitle: "Your other devices show this name.")
            TextField("Name", text: $name)
                .textFieldStyle(.roundedBorder)
                .font(.title3)
                .frame(maxWidth: 300)
                .onSubmit(next)
            Spacer()
            HStack {
                Spacer()
                Button("Continue", action: next)
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .onAppear {
            name = model.deviceName.isEmpty ? AppModel.defaultDeviceName : model.deviceName
        }
    }

    private func next() {
        let chosen = name
        Task {
            try? await model.rename(chosen)
            step = .permissions
        }
    }
}

private struct PermissionsStep: View {
    @Binding var step: OnboardingView.Step
    @Environment(AppModel.self) private var model
    @State private var asking = false
    @State private var checkedClipboard = false

    private var clipboardDetail: String {
        if model.pasteboardAccess == .unknown {
            return "Carom sends what you copy to your other devices. You can also send it with \(SendHotkey.display)."
        }
        return "macOS asks before apps read the clipboard. To send what you copy automatically, choose Always Allow for Carom under Privacy & Security › Paste from Other Apps. You can always send it with \(SendHotkey.display)."
    }

    var body: some View {
        VStack(spacing: 22) {
            StepHeader(symbol: "bell.badge",
                       title: "Permissions",
                       subtitle: "Carom needs two things to work well.")

            VStack(alignment: .leading, spacing: 18) {
                PermissionRow(symbol: "bell", title: "Notifications",
                              detail: "So Carom can show your phone's notifications.") {
                    switch model.notificationsAllowed {
                    case true?:
                        Label("Allowed", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(Color.statusGreen)
                    case false?:
                        Link("Open Settings", destination: Links.notificationSettings)
                    case nil:
                        Button("Allow") {
                            asking = true
                            Task {
                                await model.requestNotificationPermission()
                                asking = false
                            }
                        }
                        .disabled(asking)
                    }
                }
                PermissionRow(symbol: "doc.on.clipboard", title: "Clipboard",
                              detail: clipboardDetail) {
                    switch model.pasteboardAccess {
                    case .alwaysAllow:
                        Label("Always Allowed", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(Color.statusGreen)
                    case .unknown:
                        Label("Allowed", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(Color.statusGreen)
                    case .standard, .ask, .alwaysDeny:
                        if checkedClipboard {
                            Link("Open Settings", destination: Links.privacySettings)
                        } else {
                            // One user-initiated read so Carom shows up in the system's
                            // pasteboard access list (and macOS can ask right away).
                            Button("Check Access") {
                                checkedClipboard = true
                                model.checkClipboardAccess()
                            }
                        }
                    }
                }
            }
            .frame(maxWidth: 440)

            Spacer()
            HStack {
                Spacer()
                Button("Continue") { step = .done }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
            }
        }
    }
}

private struct PermissionRow<Accessory: View>: View {
    let symbol: String
    let title: String
    let detail: String
    @ViewBuilder let accessory: () -> Accessory

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(.tint)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.headline)
                Text(detail)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            accessory()
        }
    }
}

private struct DoneStep: View {
    let close: () -> Void
    let addPhone: () -> Void
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            StepHeader(symbol: "checkmark.circle.fill", statusSymbol: true,
                       title: "You're All Set",
                       subtitle: model.phones.isEmpty
                           ? "Carom lives in the menu bar. Add your phone to start syncing."
                           : "Carom lives in the menu bar. Notifications and clipboard items now sync.")
            Spacer()
            HStack {
                Spacer()
                if model.phones.isEmpty {
                    Button("Done", action: close)
                    Button("Add a Phone", action: addPhone)
                        .buttonStyle(.borderedProminent)
                        .keyboardShortcut(.defaultAction)
                } else {
                    Button("Done", action: close)
                        .buttonStyle(.borderedProminent)
                        .keyboardShortcut(.defaultAction)
                }
            }
        }
        .task {
            if model.phase != .ready { await model.finishOnboarding() }
        }
    }
}
