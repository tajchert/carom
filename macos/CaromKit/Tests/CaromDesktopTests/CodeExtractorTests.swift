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
        ("Your code is 123456789", nil),
        ("Battery at 1234%", nil),
        ("Ticket AB123456 confirmed", nil),
        ("Hello there", nil),
        ("", nil),
        ("Year 2024, your code is 998877", "998877"),
    ] as [(String, String?)])
    func extracts(text: String, expected: String?) {
        #expect(CodeExtractor.code(in: text) == expected)
    }
}
