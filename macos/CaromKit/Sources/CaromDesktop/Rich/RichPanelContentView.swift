import SwiftUI

/// The panel's content: up to four stacks (pinned first, then newest), then "+N more".
struct RichPanelContentView: View {
    let coordinator: NotificationCoordinator
    let onHeight: (CGFloat) -> Void

    var body: some View {
        let stacks = coordinator.visibleStacks
        let hidden = coordinator.hiddenStackCount
        VStack(alignment: .trailing, spacing: 8) {
            ForEach(stacks) { stack in
                CardStackView(stack: stack, coordinator: coordinator)
                    .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity),
                                            removal: .opacity))
            }
            if hidden > 0 {
                Text("+\(hidden) more")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(.regularMaterial, in: Capsule())
                    .accessibilityLabel("\(hidden) more notifications")
            }
        }
        .padding(8)
        .frame(width: RichPanelPresenter.width, alignment: .topTrailing)
        .fixedSize(horizontal: false, vertical: true)
        .animation(.easeOut(duration: 0.25), value: stacks.map(\.id))
        .onHover { coordinator.setHovering($0) }
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { onHeight($0) }
    }
}
