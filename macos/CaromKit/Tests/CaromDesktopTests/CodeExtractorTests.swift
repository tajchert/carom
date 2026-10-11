import CaromProtocol
import Testing

@testable import CaromDesktop

@Suite struct CodeExtractorTests {
    @Test(arguments: [
        ("Your verification code is 482913", "482913"),
        ("482913 is your Google code", "482913"),
        ("G-482913 is your Google verification code", "482913"),
        ("Use 4829 as your login PIN", "4829"),
        ("Your code is 2024", "2024"),
        ("Code: 12345678. Do not share it.", "12345678"),
        ("OTP 123 456 valid for 10 min", "123456"),
        ("Your code: 123-456", "123456"),
        ("Your security code is 12345 and expires at 12:30", "12345"),
        ("Order 482913 shipped", "482913"),
        ("See you in 2024", nil),
        ("Call +48 123 456 789", nil),
        ("Call 555-123-4567 for the code", nil),
        ("Call (555) 123-4567", nil),
        ("Meeting at 12:34", nil),
        ("Total $1234.56", nil),
        ("Pay 1,234,567 today", nil),
        ("Delivery on 2024-05-01", nil),
        ("Your code is 123", nil),
        ("Your code is 123456789", "123456789"),
        ("Your code is 1234567890", "1234567890"),
        ("Your code is 12345678901", nil),
        ("Order 1234567890 shipped", nil),
        ("Battery at 1234%", nil),
        ("Ticket AB123456 confirmed", nil),
        ("Hello there", nil),
        ("", nil),
        ("Year 2024, your code is 998877", "998877"),
        // Letter-digit codes: a keyword must come first, within 40 characters.
        ("Your verification code is A1B2C3", "A1B2C3"),
        ("Use code X7K-92Q to log in", "X7K-92Q"),
        ("Your code is ABCD12", "ABCD12"),
        ("Your OTP: 7f3k9z", "7f3k9z"),
        ("Your Google code is G-482913", "482913"),
        ("AB123456 is your tracking number", nil),
        ("Your tracking number is AB123456 and your code is 482913", "482913"),
        ("Your code is A1", nil),
        ("Your code is ABC1", nil),
        ("Your code is AB12345678901", nil),
        ("Your code is A1-B2-C3", nil),
        ("Your code is iPhone", nil),
        ("Your code is price$A1B2C3", nil),
        ("Your code is A1B2C3@mail", nil),
    ] as [(String, String?)])
    func extracts(text: String, expected: String?) {
        #expect(CodeExtractor.code(in: text) == expected)
    }

    @Test func keywordTooFarBeforeLetterDigitCode() {
        let far = "Your code" + String(repeating: " ", count: 45) + "A1B2C3"
        let near = "Your code" + String(repeating: " ", count: 20) + "A1B2C3"
        #expect(CodeExtractor.code(in: far) == nil)
        #expect(CodeExtractor.code(in: near) == "A1B2C3")
    }

    @Test func keywordAfterLetterDigitCodeDoesNotCount() {
        #expect(CodeExtractor.code(in: "A1B2C3 is your code") == nil)
    }

    @Test func keywordedLetterDigitCodeBeatsLaterDigitRun() {
        #expect(CodeExtractor.code(in: "Your code is A1B2C3, ref 998877") == "A1B2C3")
    }

    // MARK: - Whole notification

    private func posted(_ extra: JSONObject = [:], text: String? = nil, redacted: Bool = false) -> NotificationPosted {
        var body: JSONObject = ["key": "k", "app": ["id": "a", "name": "A"], "posted_at": 1, "redacted": .bool(redacted)]
        if let text { body["text"] = .string(text) }
        for (k, v) in extra { body[k] = v }
        return NotificationPosted(json: body)!
    }

    @Test func scansTextBeforeBigText() {
        let n = posted(["big_text": "Your code is 111111"], text: "Your code is 222222")
        #expect(CodeExtractor.code(in: n) == "222222")
    }

    @Test func fallsBackToBigText() {
        let n = posted(["big_text": "Hi. Your code is 111111"], text: "Hi.")
        #expect(CodeExtractor.code(in: n) == "111111")
    }

    @Test func scansLinesOneByOne() {
        let n = posted(["lines": ["Total 1234567", "Your code is 654321"]], text: "2 new messages")
        #expect(CodeExtractor.code(in: n) == "654321")
        let split = posted(["lines": ["Your code is", "7654321"]], text: "2 new messages")
        #expect(CodeExtractor.code(in: split) == nil)
    }

    @Test func scansNewestMessageLast() {
        let msgs: JSON = [
            ["text": "Your code is 111111", "ts": 1],
            ["text": "Your code is 222222", "ts": 2],
        ]
        let n = posted(["messages": msgs], text: "Bank")
        #expect(CodeExtractor.code(in: n) == "222222")
    }

    @Test func redactedNotificationHasNoCode() {
        #expect(CodeExtractor.code(in: posted(text: "Your code is 482913", redacted: true)) == nil)
    }

    @Test func plainNotificationWithoutText() {
        #expect(CodeExtractor.code(in: posted()) == nil)
    }
}
