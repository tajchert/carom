import CaromProtocol
import Foundation

/// Finds a one-time code in notification text: 4-10 digits, or 4-10 letters and digits.
///
/// Heuristics:
/// - A digit run touching letters, currency signs, `+`, `%`, decimals or times is not a code.
/// - A run joined to other digits by `-`, `.`, `/` or a space (phone numbers, dates) is not a code,
///   except a `123 456` / `123-456` pair, which is read as one 6-digit code.
/// - Without a keyword such as "code" or "OTP" in the text, only 6-digit codes count.
/// - 4-digit values that look like a year (1900-2099) count only when a keyword is present.
/// - A letter-digit code (`A1B2C3`, `X7K-92Q`) has 4-10 characters, at least one letter and at
///   least two digits, and at most one `-`. It counts only with a keyword within 40 characters
///   before it. A single letter, `-` and digits (`G-482913`) is read as the digits.
/// - When several candidates remain, one with a keyword just before it wins, else the first.
public enum CodeExtractor {
    /// The code in a notification: scans `text`, `big_text`, each of `lines`, then the newest
    /// message, and returns the first hit. A redacted notification has none.
    public static func code(in posted: NotificationPosted) -> String? {
        guard !posted.redacted else { return nil }
        var sources: [String?] = [posted.text, posted.bigText]
        sources += (posted.lines ?? []).map { Optional($0) }
        sources.append(posted.messages?.last?.text)
        for source in sources {
            if let source, let found = code(in: source) { return found }
        }
        return nil
    }

    public static func code(in text: String) -> String? {
        let chars = Array(text.unicodeScalars)
        let runs = digitRuns(chars)
        let tokens = mixedTokens(chars)
        guard !runs.isEmpty || !tokens.isEmpty else { return nil }
        let hasKeyword = containsKeyword(text.lowercased())

        var best: (code: String, near: Bool, start: Int)?
        func consider(_ code: String, near: Bool, start: Int) {
            guard let current = best else { best = (code, near, start); return }
            if (near && !current.near) || (near == current.near && start < current.start) {
                best = (code, near, start)
            }
        }
        for (i, run) in runs.enumerated() {
            guard let c = candidate(index: i, runs: runs, chars: chars) else { continue }
            let n = c.count
            guard (4...10).contains(n) else { continue }
            if !hasKeyword && n != 6 { continue }
            if n == 4 && isYear(c) && !hasKeyword { continue }
            consider(c, near: keywordPrecedes(chars, at: run.start), start: run.start)
        }
        for token in tokens where keywordPrecedes(chars, at: token.start) {
            consider(string(chars, token.start, token.end), near: true, start: token.start)
        }
        return best?.code
    }

    // MARK: - Internals

    private static let keywords = [
        "code", "otp", "pin", "passcode", "password", "verif", "kod", "token", "one-time",
        "security", "log in", "login", "sign in", "authenticat", "confirm",
    ]

    private struct Run { var start: Int; var end: Int; var length: Int { end - start } }

    private static func containsKeyword(_ lower: String) -> Bool {
        keywords.contains { lower.contains($0) }
    }

    private static func isDigit(_ s: Unicode.Scalar) -> Bool { s.value >= 0x30 && s.value <= 0x39 }

    private static func digitRuns(_ chars: [Unicode.Scalar]) -> [Run] {
        var runs: [Run] = []
        var i = 0
        while i < chars.count {
            guard isDigit(chars[i]) else { i += 1; continue }
            var j = i
            while j < chars.count, isDigit(chars[j]) { j += 1 }
            runs.append(Run(start: i, end: j))
            i = j
        }
        return runs
    }

    private static func isLetter(_ s: Unicode.Scalar) -> Bool { CharacterSet.letters.contains(s) }

    private static func isAsciiLetter(_ s: Unicode.Scalar) -> Bool {
        (s.value >= 0x41 && s.value <= 0x5A) || (s.value >= 0x61 && s.value <= 0x7A)
    }

    /// Letter-digit tokens: a maximal run of ASCII letters, digits and `-` that, without the `-`,
    /// has 4-10 characters, at least one letter and at least two digits, with at most one `-`.
    /// Tokens next to other letters or to the signs that mark prices, mentions and addresses are
    /// skipped. The keyword rule is applied by the caller.
    private static func mixedTokens(_ chars: [Unicode.Scalar]) -> [Run] {
        var tokens: [Run] = []
        var i = 0
        while i < chars.count {
            guard isAsciiLetter(chars[i]) || isDigit(chars[i]) else { i += 1; continue }
            var j = i
            while j < chars.count, isAsciiLetter(chars[j]) || isDigit(chars[j]) || chars[j] == "-" { j += 1 }
            var end = j
            while end > i, chars[end - 1] == "-" { end -= 1 }
            let run = Run(start: i, end: end)
            i = j
            let body = chars[run.start..<run.end]
            let hyphens = body.filter { $0 == "-" }.count
            let letters = body.filter(isAsciiLetter).count
            let digits = body.filter(isDigit).count
            guard hyphens <= 1, (4...10).contains(letters + digits), letters >= 1, digits >= 2 else { continue }
            // "G-482913": one letter, a dash and digits is a label plus a digit code.
            if hyphens == 1, letters == 1, isAsciiLetter(chars[run.start]), chars[run.start + 1] == "-" { continue }
            if run.start > 0 {
                let p = chars[run.start - 1]
                if isLetter(p) || "+$€£¥#@_".unicodeScalars.contains(p) { continue }
            }
            if j < chars.count {
                let n = chars[j]
                if isLetter(n) || "%@_".unicodeScalars.contains(n) { continue }
            }
            tokens.append(run)
        }
        return tokens
    }

    /// Digits for the run at `index`, or nil when it is not code-like. For the first half of a
    /// `123 456` pair this returns both halves; the second half returns nil.
    private static func candidate(index: Int, runs: [Run], chars: [Unicode.Scalar]) -> String? {
        let run = runs[index]
        if isClean(run, chars) == false { return nil }

        let leftJoin = index > 0 && joined(runs[index - 1], run, chars)
        let rightJoin = index + 1 < runs.count && joined(run, runs[index + 1], chars)

        if rightJoin && !leftJoin && run.length == 3 {
            let other = runs[index + 1]
            let sep = chars[run.end]
            let chained = index + 2 < runs.count && joined(other, runs[index + 2], chars)
            if other.length == 3, (sep == " " || sep == "-"), !chained, isClean(other, chars) {
                return (string(chars, run.start, run.end) + string(chars, other.start, other.end))
            }
            return nil
        }
        if leftJoin || rightJoin { return nil }
        return (string(chars, run.start, run.end))
    }

    /// False when the characters around a run mark it as something other than a code.
    private static func isClean(_ run: Run, _ chars: [Unicode.Scalar]) -> Bool {
        let prev: Unicode.Scalar? = run.start > 0 ? chars[run.start - 1] : nil
        let next: Unicode.Scalar? = run.end < chars.count ? chars[run.end] : nil
        let before: Unicode.Scalar? = run.start > 1 ? chars[run.start - 2] : nil
        let after: Unicode.Scalar? = run.end + 1 < chars.count ? chars[run.end + 1] : nil

        if let p = prev, isLetter(p) || "+$€£¥#@_".unicodeScalars.contains(p) { return false }
        if let n = next, isLetter(n) || "%@_".unicodeScalars.contains(n) { return false }
        for sep: Unicode.Scalar in [".", ",", ":"] {
            if prev == sep, let b = before, isDigit(b) { return false }
            if next == sep, let a = after, isDigit(a) { return false }
        }
        return true
    }

    /// True when two adjacent runs are separated like a phone number or a date.
    private static func joined(_ l: Run, _ r: Run, _ chars: [Unicode.Scalar]) -> Bool {
        let gap = r.start - l.end
        if gap == 1 {
            let sep = chars[l.end]
            if sep == "-" || sep == "." || sep == "/" { return true }
            if sep == " " { return l.length >= 3 && r.length >= 3 }
        }
        if gap == 2, chars[l.end] == ")", chars[l.end + 1] == " " { return true }
        return false
    }

    private static func isYear(_ d: String) -> Bool {
        guard let v = Int(d) else { return false }
        return (1900...2099).contains(v)
    }

    private static func string(_ chars: [Unicode.Scalar], _ s: Int, _ e: Int) -> String {
        var out = String.UnicodeScalarView()
        out.append(contentsOf: chars[s..<e])
        return String(out)
    }

    private static func keywordPrecedes(_ chars: [Unicode.Scalar], at start: Int) -> Bool {
        containsKeyword(string(chars, max(0, start - 40), start).lowercased())
    }
}
