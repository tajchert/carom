# Swipe to hide macOS notification banners

## Intent
Let users swipe Carom's rich notification banners away with a trackpad. As confirmed by the user, this only hides the Mac banner; it does not dismiss the phone notification or remove its native Notification Center entry.

## Behavior
- A horizontal swipe to the right moves the banner with the gesture. Passing a distance threshold slides it out and calls the existing coordinator close operation.
- Short or cancelled gestures return the banner to its original position.
- Vertical scrolling and ordinary clicks keep their existing behavior.
- Individual cards and collapsed notification stacks support the gesture. Swiping a collapsed stack hides its displayed children.
- Closing a replying card ends reply mode through the existing coordinator behavior.
- Respect Reduce Motion when animating.

## Approaches
Use AppKit trackpad scroll events routed to the hovered card, with SwiftUI rendering the offset and animation. This handles two-finger trackpad gestures directly and preserves the existing card controls. This is the recommended approach.

A SwiftUI drag gesture alone would support pointer dragging but would not provide the requested two-finger trackpad interaction. Replacing rich banners with native notifications would discard the existing rich presentation and is outside this change.

## Implementation and verification
Keep gesture recognition isolated from notification delivery. Reuse NotificationCoordinator.close; do not call the dismiss action. Cover direction recognition, threshold, cancellation, and momentum handling with focused tests, and run the macOS package suite. A physical trackpad check should confirm feel and interaction with buttons and reply fields.

Thresholds and animation timing are implementation choices and can be tuned during manual testing.
