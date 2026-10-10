import CaromProtocol
import CaromStore
import SwiftUI

struct DevicesSettings: View {
    @Environment(AppModel.self) private var model
    @State private var removing: DirectoryEntry?
    @State private var removingSelf = false
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        if model.phase != .ready {
            NotSetUpPlaceholder()
        } else {
            Form {
                Section {
                    ThisMacRow()
                    ForEach(model.directory, id: \.record.id) { entry in
                        DeviceRow(entry: entry) { removing = entry }
                    }
                    if model.directory.isEmpty {
                        Footnote("No other devices yet. Choose Add Device in the menu to pair your phone.")
                    }
                } header: {
                    Text("Devices")
                } footer: {
                    SettingsError(message: error)
                }

                if !model.directory.isEmpty {
                    Section {
                        ReceiveMatrix(error: $error)
                    } header: {
                        Text("Receive From")
                    } footer: {
                        Footnote("Turning a device off here tells it to stop sending that to this Mac.")
                    }
                }

                Section {
                    HStack {
                        Footnote("Removing this Mac erases its keys. You can pair it again later.")
                        Spacer()
                        Button("Remove This Mac…", role: .destructive) { removingSelf = true }
                    }
                }
            }
            .formStyle(.grouped)
            .disabled(busy)
            .confirmationDialog("Remove \(removing?.record.name ?? "")?", isPresented: Binding(
                get: { removing != nil }, set: { if !$0 { removing = nil } }), presenting: removing) { entry in
                Button("Remove", role: .destructive) { remove(entry.record.id) }
            } message: { entry in
                Text("\(entry.record.name) stops syncing right away and can't read anything sent after this. To use it again, pair it again.")
            }
            .confirmationDialog("Remove this Mac?", isPresented: $removingSelf) {
                Button("Remove This Mac", role: .destructive) { remove(model.deviceId ?? "self") }
            } message: {
                Text("This Mac stops syncing and its keys are erased. Your other devices keep working.")
            }
        }
    }

    private func remove(_ id: String) {
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do { try await model.remove(deviceId: id) } catch { self.error = UserMessages.describe(error) }
        }
    }
}

private struct ThisMacRow: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "laptopcomputer")
                .font(.title3)
                .foregroundStyle(.tint)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(model.deviceName)
                    Text("This Mac")
                        .font(.caption)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(.quaternary, in: Capsule())
                }
                Text("Rename it in General.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct DeviceRow: View {
    @Environment(AppModel.self) private var model
    let entry: DirectoryEntry
    let remove: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: deviceSymbol(entry.record.kind))
                .font(.title3)
                .foregroundStyle(.secondary)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.record.name)
                Text(details)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button(role: .destructive, action: remove) {
                Image(systemName: "minus.circle")
            }
            .buttonStyle(.borderless)
            .help("Remove \(entry.record.name)")
        }
    }

    private var details: String {
        var parts = [kindLabel(entry.record.kind), lastSeenText(entry.lastSeen)]
        if let battery = model.deviceStatus[entry.record.id]?.battery {
            parts.append("Battery \(battery)%")
        }
        return parts.joined(separator: " · ")
    }
}

/// Who may send what to this Mac (writes our record's `receive` rules).
private struct ReceiveMatrix: View {
    @Environment(AppModel.self) private var model
    @Binding var error: String?

    var body: some View {
        Grid(alignment: .leading, horizontalSpacing: 18, verticalSpacing: 10) {
            GridRow {
                Text("Device").foregroundStyle(.secondary)
                ForEach(ReceiveCategory.allCases) { category in
                    Text(category.title)
                        .foregroundStyle(.secondary)
                        .gridColumnAlignment(.center)
                }
            }
            .font(.caption)
            Divider()
            ForEach(model.directory, id: \.record.id) { entry in
                GridRow {
                    Text(entry.record.name)
                    ForEach(ReceiveCategory.allCases) { category in
                        if category == .notifications, entry.record.kind != .phone {
                            Text("—").foregroundStyle(.tertiary)
                        } else {
                            Toggle("", isOn: binding(category, entry.record.id))
                                .labelsHidden()
                                .toggleStyle(.checkbox)
                                .accessibilityLabel("\(category.title) from \(entry.record.name)")
                        }
                    }
                }
            }
        }
    }

    private func binding(_ category: ReceiveCategory, _ id: String) -> Binding<Bool> {
        Binding(get: { model.receives(category, from: id) }, set: { allowed in
            error = nil
            Task {
                do { try await model.setReceive(category, from: id, allowed) } catch {
                    self.error = UserMessages.describe(error)
                }
            }
        })
    }
}
