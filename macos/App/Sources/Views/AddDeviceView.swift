import AppKit
import SwiftUI

/// "Add device": a pair QR code with a 5-minute countdown, then a fingerprint check.
struct AddDeviceView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 18) {
            content
        }
        .padding(28)
        .frame(width: 420, height: 520)
        .animation(.easeInOut(duration: 0.2), value: model.pairing)
        .task {
            switch model.pairing {
            case .idle, .added, .expired, .failed: await model.startPairing()
            default: break
            }
        }
        .onDisappear {
            if case .added = model.pairing { return }
            Task { await model.cancelPairing() }
        }
    }

    @ViewBuilder private var content: some View {
        switch model.pairing {
        case .idle, .creating:
            Spacer()
            ProgressView("Creating a pairing code…")
            Spacer()
        case .showing(let invite):
            ShowingInvite(invite: invite, cancel: { dismiss() })
        case .confirming(_, let candidate):
            ConfirmCandidate(candidate: candidate)
        case .approving:
            Spacer()
            ProgressView("Adding the device…")
            Spacer()
        case .added(let fingerprint):
            Outcome(symbol: "checkmark.circle.fill", tint: .green, title: "Device Added",
                    message: "Device \(fingerprint) can now sync. Its name appears in your devices once it finishes setting up.") {
                Button("Done") { dismiss() }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
            }
        case .expired:
            Outcome(symbol: "clock.badge.exclamationmark", tint: .orange, title: "The Code Expired",
                    message: "Pairing codes work for 5 minutes. Show a new one to try again.") {
                retryButtons
            }
        case .failed(let message):
            Outcome(symbol: "exclamationmark.triangle.fill", tint: .orange, title: "Pairing Didn't Finish",
                    message: message) {
                retryButtons
            }
        }
    }

    private var retryButtons: some View {
        HStack {
            Button("Close") { dismiss() }
            Button("Show a New Code") { Task { await model.startPairing() } }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
        }
    }
}

private struct ShowingInvite: View {
    let invite: Invite
    let cancel: () -> Void
    @State private var copied = false

    var body: some View {
        VStack(spacing: 6) {
            Text("Add a Device")
                .font(.title2.weight(.semibold))
            Text("Scan this code with Carom on your phone. On another Mac, paste the link instead.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        QRImage(text: invite.uri)
        TimelineView(.periodic(from: .now, by: 1)) { context in
            Text(countdown(at: context.date))
                .font(.callout.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        HStack(spacing: 6) {
            Image(systemName: "lock.fill")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text("Only show this code to devices you own.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        Spacer(minLength: 0)
        HStack {
            Button("Cancel", action: cancel)
                .keyboardShortcut(.cancelAction)
            Spacer()
            Button {
                let pb = NSPasteboard.general
                pb.clearContents()
                pb.setString(invite.uri, forType: .string)
                // Keep the link out of clipboard managers and out of Carom's own sync.
                pb.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
                copied = true
            } label: {
                Label(copied ? "Copied" : "Copy Link", systemImage: copied ? "checkmark" : "link")
            }
        }
    }

    private func countdown(at date: Date) -> String {
        let remaining = max(0, Int((Double(invite.expiresAt) / 1000 - date.timeIntervalSince1970).rounded(.down)))
        return remaining == 0 ? "Expired" : String(format: "Expires in %d:%02d", remaining / 60, remaining % 60)
    }
}

private struct ConfirmCandidate: View {
    @Environment(AppModel.self) private var model
    let candidate: Candidate

    var body: some View {
        Spacer()
        Image(systemName: "person.badge.shield.checkmark")
            .font(.system(size: 44, weight: .light))
            .foregroundStyle(.tint)
            .symbolRenderingMode(.hierarchical)
        Text("Add This Device?")
            .font(.title2.weight(.semibold))
        Text("A device scanned your code. Add it only if it shows the same code:")
            .font(.callout)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
        Text(candidate.fingerprint)
            .font(.system(size: 28, weight: .semibold, design: .monospaced))
            .textSelection(.enabled)
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .background(.quaternary, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        Spacer()
        HStack {
            Button("Reject", role: .destructive) { Task { await model.rejectCandidate() } }
            Spacer()
            Button("Add Device") { Task { await model.approveCandidate() } }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
        }
    }
}

private struct Outcome<Actions: View>: View {
    let symbol: String
    let tint: Color
    let title: String
    let message: String
    @ViewBuilder let actions: () -> Actions

    var body: some View {
        Spacer()
        Image(systemName: symbol)
            .font(.system(size: 44))
            .foregroundStyle(tint)
        Text(title)
            .font(.title2.weight(.semibold))
        Text(message)
            .font(.callout)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
        Spacer()
        actions()
    }
}
