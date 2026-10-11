import AppKit
import CaromProtocol
import SwiftUI

@testable import CaromDesktop

/// Every blob is missing: cards must still lay out and show their placeholders.
struct NoImages: ImageProvider {
    func image(for ref: BlobRef) async -> NSImage? { nil }
}

/// A card for a notification built from the wire JSON, with `extra` fields on top of a minimal body.
func cardItem(_ extra: JSONObject = [:], acts: Bool = true) -> CardItem {
    var body: JSONObject = ["key": "k", "app": ["id": "a", "name": "Rides"], "title": "Your driver is coming",
                            "posted_at": 1, "redacted": false]
    for (k, v) in extra { body[k] = v }
    return CardItem(id: "p/k", phoneId: "p", phoneName: "Pixel", msgId: "m1", posted: NotificationPosted(json: body)!,
                    receivedAt: Date(), phoneAcceptsActions: acts)
}

/// The size a view takes at 320 pt wide. Laying it out proves that the view builds and that
/// nothing in it needs an image.
@MainActor
func fittingSize<V: View>(_ view: V) -> CGSize {
    let host = NSHostingView(rootView: view.frame(width: 320))
    host.layoutSubtreeIfNeeded()
    return host.fittingSize
}
