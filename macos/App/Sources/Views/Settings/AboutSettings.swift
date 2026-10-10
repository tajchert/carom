import CaromProtocol
import SwiftUI

struct AboutSettings: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Form {
            Section {
                HStack(spacing: 14) {
                    Image(systemName: "arrow.left.arrow.right.circle.fill")
                        .font(.system(size: 44))
                        .foregroundStyle(.tint)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Carom").font(.title2.weight(.semibold))
                        Text("Version \(model.appVersion)")
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
            }

            Section("This Mac") {
                LabeledContent("Hub") {
                    Text(model.hubURL ?? "Not set up")
                        .textSelection(.enabled)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                LabeledContent("Device ID") {
                    Text(model.deviceId ?? "—")
                        .font(.body.monospaced())
                        .textSelection(.enabled)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                if let id = model.deviceId {
                    LabeledContent("Fingerprint") {
                        Text(DeviceID.fingerprint(id))
                            .font(.body.monospaced())
                            .textSelection(.enabled)
                    }
                }
            }

            Section {
                LabeledContent("License", value: "GPL-3.0")
                LabeledContent("Source code") {
                    Link("github.com/tajchert/carom", destination: Links.project)
                }
            } footer: {
                Footnote("End-to-end encrypted. Your Hub only sees ciphertext.")
            }
        }
        .formStyle(.grouped)
    }
}
