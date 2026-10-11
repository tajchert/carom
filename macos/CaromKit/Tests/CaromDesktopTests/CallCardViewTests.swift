import SwiftUI
import Testing

@testable import CaromDesktop

/// Never makes a sound: the cards under test must not ring the real system sound.
@MainActor
private final class Silent: RingPlaying {
    func start() {}
    func stop() {}
}

@MainActor
@Suite struct CallCardViewTests {
    private let ringer = CallRinger(player: Silent())

    @Test func laysOutWithBothButtons() {
        let call = cardItem(["call": ["type": "incoming", "person": ["name": "Ana"], "verification": "Verified caller"]])
        let size = fittingSize(CallCardView(item: call, images: NoImages(), status: .idle, ringer: ringer,
                                            perform: { _ in }))
        #expect(size.height > 100)
    }

    @Test func laysOutWhenThePhoneTakesNoActionsAndHasNoCallerName() {
        let call = cardItem(["call": ["type": "incoming", "is_video": true]], acts: false)
        let size = fittingSize(CallCardView(item: call, images: NoImages(), status: .asleep, ringer: ringer,
                                            perform: { _ in }))
        #expect(size.height > 60)
    }

    @Test func buttonsComeBackAfterAFailedSend() {
        #expect(!CallCardView.buttonsDisabled(acted: false, status: .idle))
        #expect(CallCardView.buttonsDisabled(acted: true, status: .idle))
        #expect(CallCardView.buttonsDisabled(acted: true, status: .sending))
        #expect(CallCardView.buttonsDisabled(acted: true, status: .sent))
        #expect(!CallCardView.buttonsDisabled(acted: true, status: .failed))
    }
}
