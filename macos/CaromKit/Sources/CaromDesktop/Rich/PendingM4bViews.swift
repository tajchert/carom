import SwiftUI

// M4b owns Live/LiveUpdateCardView.swift and Call/CallCardView.swift. Until it is merged these
// stand-ins keep the stack compiling. Delete this file when merging M4b: both names are then
// declared twice in CaromDesktop and the build fails until it is gone.

struct LiveUpdateCardView: View {
    init(item: CardItem, images: any ImageProvider, status: ActionStatus, perform: @escaping (CardAction) -> Void) {}
    var body: some View { Color.clear.frame(height: 1) }
}

struct CallCardView: View {
    init(item: CardItem, images: any ImageProvider, status: ActionStatus, perform: @escaping (CardAction) -> Void) {}
    var body: some View { Color.clear.frame(height: 1) }
}
