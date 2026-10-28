import CoreBluetooth
import Foundation
import Observation

/// Advertises the Carom service UUID over Bluetooth LE for a few minutes, so an Android phone can
/// create its companion "watch" association with this Mac. That association is the only way a
/// third-party app gets to see verification codes on Android 15+.
///
/// Advertising only: there is no GATT service, nothing connects, and it stops on its own, when
/// asked, or when the owner goes away. The phone keeps its association without the Mac.
@MainActor
@Observable
public final class CompanionBeacon: NSObject {
    public enum State: Equatable, Sendable {
        case idle
        case starting
        case advertising(until: Date)
        case bluetoothOff
        case unauthorized
        case unsupported
        case failed
    }

    /// Matched by the Android app's `AndroidAssociationApi.BEACON_SERVICE`; keep the two in sync.
    nonisolated public static let serviceUUID = "A1C39594-89E2-4224-94E2-9E0186526ADF"
    nonisolated public static let duration: Duration = .seconds(180)

    public private(set) var state: State = .idle

    @ObservationIgnored private var manager: CBPeripheralManager?
    @ObservationIgnored private var timeout: Task<Void, Never>?

    override public init() {
        super.init()
    }

    public func start() {
        guard manager == nil else { return }
        state = .starting
        manager = CBPeripheralManager(delegate: self, queue: .main)
    }

    public func stop() {
        finish(.idle)
    }

    private func finish(_ next: State) {
        timeout?.cancel()
        timeout = nil
        manager?.stopAdvertising()
        manager?.delegate = nil
        manager = nil
        state = next
    }

    private func stateChanged(_ bluetooth: CBManagerState) {
        guard let manager else { return }
        switch bluetooth {
        case .poweredOn:
            guard state == .starting else { return }
            manager.startAdvertising([
                CBAdvertisementDataServiceUUIDsKey: [CBUUID(string: Self.serviceUUID)],
                CBAdvertisementDataLocalNameKey: "Carom",
            ])
        case .poweredOff: finish(.bluetoothOff)
        case .unauthorized: finish(.unauthorized)
        case .unsupported: finish(.unsupported)
        case .unknown, .resetting: break
        @unknown default: break
        }
    }

    private func advertisingStarted(failed: Bool) {
        guard manager != nil, state == .starting else { return }
        if failed {
            finish(.failed)
            return
        }
        let seconds = Double(Self.duration.components.seconds)
        state = .advertising(until: Date().addingTimeInterval(seconds))
        timeout = Task { [weak self] in
            try? await Task.sleep(for: Self.duration)
            guard !Task.isCancelled else { return }
            self?.stop()
        }
    }
}

extension CompanionBeacon: CBPeripheralManagerDelegate {
    // The manager delivers on the main queue (see start()).
    nonisolated public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        let bluetooth = peripheral.state
        MainActor.assumeIsolated { stateChanged(bluetooth) }
    }

    nonisolated public func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: (any Error)?) {
        let failed = error != nil
        MainActor.assumeIsolated { advertisingStarted(failed: failed) }
    }
}
