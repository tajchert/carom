import SwiftUI
import Testing

@testable import CaromDesktop

@MainActor
@Suite struct CallCardViewTests {
    @Test func laysOutWithBothButtons() {
        let call = cardItem(["call": ["type": "incoming", "person": ["name": "Ana"], "verification": "Verified caller"]])
        let size = fittingSize(CallCardView(item: call, images: NoImages(), status: .idle, perform: { _ in }))
        #expect(size.height > 100)
    }

    @Test func laysOutWhenThePhoneTakesNoActionsAndHasNoCallerName() {
        let call = cardItem(["call": ["type": "incoming", "is_video": true]], acts: false)
        let size = fittingSize(CallCardView(item: call, images: NoImages(), status: .asleep, perform: { _ in }))
        #expect(size.height > 60)
    }
}
