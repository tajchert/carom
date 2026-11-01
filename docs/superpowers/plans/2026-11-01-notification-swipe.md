# Notification Swipe Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Swipe right on rich Mac banners to hide them locally, then merge and release the macOS app.
**Architecture:** An isolated gesture state machine consumes phased AppKit scroll events. A transparent view monitors events only for its card and drives a SwiftUI modifier; successful swipes invoke the existing close operation.
**Tech Stack:** Swift 6, AppKit, SwiftUI, Swift Testing.
**Spec:** docs/superpowers/specs/2026-11-01-notification-swipe-design.md

## Global Constraints
- No notification dismissal is sent to the phone; Notification Center history is retained.
- Preserve vertical scrolling, buttons, reply fields, and macOS 14 support.
- Respect Reduce Motion.

## Review Focus
- Momentum must not dismiss another card after a swipe.
- Vertical gestures must not turn into horizontal dismissals.
- Cancelled and short gestures must return to zero.
- Removing the view must remove its event monitor.
- A collapsed stack must close only the children it represents.

### Task 1: Gesture and card integration
- [x] Add tests for direction locking, threshold, reverse movement, cancellation, fresh gesture reset, imprecise input, momentum, and phased input requirements in `macos/CaromKit/Tests/CaromDesktopTests/NotificationSwipeTests.swift`.
- [x] Run `swift test --filter NotificationSwipeTests` and confirm the missing implementation fails.
- [x] Implement `NotificationSwipe` and `SwipeToHide` in `macos/CaromKit/Sources/CaromDesktop/Rich/SwipeToHide.swift`. Use 8 points to lock direction and 100 points to dismiss on release.
- [x] Apply the modifier to individual cards and collapsed stacks in `CardStackView.swift`, using coordinator.close for local hiding.
- [x] Test local close after a completed swipe while replying, and closing a collapsed stack without phone or native withdrawal.
- [x] Run the complete CaromKit and app test suites and build a universal Release app.
- [x] Review and commit the feature.

### Task 2: Merge and macOS release
- [x] Check existing version and release state; use a distinct macOS tag to avoid triggering the combined Android/Hub release workflow.
- [ ] Bump the macOS patch version in both project.yml and the generated project; document the macOS-only release in CHANGELOG.md and macos/README.md.
- [ ] Verify universal architectures, signature, bundle version, zip, and SHA-256.
- [ ] Merge the feature branch into main and push it, with no unrelated files staged.
- [ ] Tag the merged commit, publish the macOS zip and checksum, and verify the published release.

## Verification and review
- Baseline: 297 package tests passed before implementation.
- New gesture and coordinator tests initially failed because their implementation was absent, then passed.
- Independent review found an animation race when new group members arrive during dismissal. A hosted AppKit rendering test reproduced invisible surviving cards; resetting collapsed-card identity on membership changes made it pass.
- Package suite: 307 tests passed after the review fix. App suite: 117 tests passed with ad-hoc signing; the initial development-certificate signing attempt failed with errSecInternalComponent before tests ran.
- Universal Release app uses the existing ad-hoc signing configuration. No signing or notarization policy changes.
- Physical trackpad feel and reversed scrolling preferences have not been manually checked. The tests cover the gesture state machine and a real hosted SwiftUI view's animation lifecycle.
- Scope decision: macOS-only 1.1.1 release with macos-v1.1.1 tag; Android and Hub remain at 1.1.0. User authorized branch implementation, merge, and publication.
