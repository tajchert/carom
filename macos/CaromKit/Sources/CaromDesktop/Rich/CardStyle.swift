import Foundation
import SwiftUI

/// An Android colour int (`Notification.color`). Android ints are signed 32-bit, so
/// `0xFF2196F3` arrives as `-14575885`; only the low 32 bits count.
struct ARGB: Equatable, Sendable {
    var alpha: Double
    var red: Double
    var green: Double
    var blue: Double

    init(_ value: Int) {
        let v = UInt32(truncatingIfNeeded: value)
        alpha = Double((v >> 24) & 0xFF) / 255
        red = Double((v >> 16) & 0xFF) / 255
        green = Double((v >> 8) & 0xFF) / 255
        blue = Double(v & 0xFF) / 255
    }

    /// WCAG relative luminance.
    var luminance: Double {
        func linear(_ c: Double) -> Double { c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }

    /// Black text contrasts more than white above this luminance (equal contrast at ~0.179).
    var prefersDarkText: Bool { luminance > 0.179 }

    var color: Color { Color(.sRGB, red: red, green: green, blue: blue, opacity: alpha) }
}

/// The card's colours: the accent (app name, buttons) and, when colorized, the fill.
struct CardStyle: Equatable, Sendable {
    var accent: ARGB?
    var fill: ARGB?

    /// Text on the fill is black when the fill is light.
    var darkText: Bool { fill?.prefersDarkText ?? false }

    /// `color == 0` (transparent) is Android's "default" and means no colour.
    static func make(color: Int?, colorized: Bool?) -> CardStyle {
        guard let color else { return CardStyle(accent: nil, fill: nil) }
        let argb = ARGB(color)
        guard argb.alpha > 0 else { return CardStyle(accent: nil, fill: nil) }
        return CardStyle(accent: argb, fill: colorized == true ? argb : nil)
    }
}

/// Android-style header times: "now", "5m", "2h", then the date.
enum CardTime {
    static func short(_ date: Date, now: Date) -> String {
        let seconds = now.timeIntervalSince(date)
        if seconds < 60 { return "now" }
        if seconds < 3_600 { return "\(Int(seconds / 60))m" }
        if seconds < 86_400 { return "\(Int(seconds / 3_600))h" }
        return date.formatted(.dateTime.month(.abbreviated).day())
    }
}
