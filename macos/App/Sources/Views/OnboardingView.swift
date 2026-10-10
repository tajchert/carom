import AppKit
import SwiftUI

/// First run: welcome → set up a new hub or join → name → permissions → done (docs/CLIENTS.md).
struct OnboardingView: View {
    enum Mode { case setup, join }

    enum Step: Equatable {
        case welcome
        case connect(Mode)
        case waitingForApproval(fingerprint: String)
        case name
        case permissions
        case done
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
                case .connect(let mode): ConnectStep(mode: mode, step: $step)
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
        }
    }
}

// MARK: - Steps

private struct StepHeader: View {
    let symbol: String
    let title: String
    let subtitle: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(.tint)
                .symbolRenderingMode(.hierarchical)
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
            StepHeader(symbol: "arrow.left.arrow.right.circle",
                       title: "Welcome to Carom",
                       subtitle: "See your phone's notifications on this Mac and share the clipboard between your devices. Everything is end-to-end encrypted and goes through your own Hub on Cloudflare.")
            Link("How to deploy your own Hub", destination: Links.hubDocs)
                .font(.callout)
            Spacer()
            VStack(spacing: 10) {
                Button {
                    step = .connect(.join)
                } label: {
                    Text("Join My Devices").frame(maxWidth: 260)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .keyboardShortcut(.defaultAction)
                Button {
                    step = .connect(.setup)
                } label: {
                    Text("Set Up a New Hub").frame(maxWidth: 260)
                }
                .controlSize(.large)
                Text("Choose Join if another device already uses Carom.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct ConnectStep: View {
    let mode: OnboardingView.Mode
    @Binding var step: OnboardingView.Step
    @Environment(AppModel.self) private var model
    @State private var uri = ""
    @State private var error: String?
    @State private var working = false
    @State private var dropTargeted = false

    var body: some View {
        VStack(spacing: 18) {
            StepHeader(symbol: mode == .setup ? "server.rack" : "qrcode.viewfinder",
                       title: mode == .setup ? "Set Up a New Hub" : "Join My Devices",
                       subtitle: mode == .setup
                           ? "Paste the carom://setup link printed by npx carom-hub deploy, or drop a screenshot of its QR code."
                           : "On a paired device, choose Add Device. Then paste the carom://pair link here, or drop a screenshot of the QR code.")

            dropZone

            TextField(mode == .setup ? "carom://setup?…" : "carom://pair?…", text: $uri, axis: .vertical)
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
                Spacer()
                if working { ProgressView().controlSize(.small) }
                Button(mode == .setup ? "Set Up" : "Continue", action: connect)
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
                switch mode {
                case .setup:
                    try await model.setUp(uri: value)
                    step = .name
                case .join:
                    let fingerprint = try await model.beginJoin(uri: value)
                    step = .waitingForApproval(fingerprint: fingerprint)
                }
                uri = ""
            } catch let e as AppError {
                error = e.message
            } catch {
                self.error = UserMessages.describe(error)
            }
        }
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
                    step = .connect(.join)
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
                            .foregroundStyle(.green)
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
                              detail: "macOS asks before apps read the clipboard. To send what you copy automatically, choose Always Allow for Carom when asked, or later in System Settings › Privacy & Security. You can always send it with \(SendHotkey.display).") {
                    EmptyView()
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
            StepHeader(symbol: "checkmark.circle",
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
