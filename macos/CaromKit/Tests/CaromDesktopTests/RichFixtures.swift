import AppKit
import CaromProtocol
import Foundation

@testable import CaromDesktop

/// Builders for V2 notification bodies and cards. The only test file in this target that calls
/// the initializers of the nested `NotificationPosted` types (M1s).
enum Fixture {
    static let phone = "dev_" + String(repeating: "A", count: 22)
    static let otherPhone = "dev_" + String(repeating: "B", count: 22)

    static func blob(_ n: Int) -> BlobRef {
        BlobRef(id: "blob\(n)", key: "a2V5", mime: "image/webp", size: 100, hash: "hash\(n)", w: 96, h: 96)
    }

    static func posted(_ key: String = "k1", app: String = "com.chat", appName: String = "Chat",
                       title: String? = "Ana", text: String? = "See you at 8") -> NotificationPosted {
        NotificationPosted(key: key, app: .init(id: app, name: appName), title: title, text: text,
                           postedAt: 1_800_000_000_000)
    }

    static func card(_ posted: NotificationPosted, phone: String = phone, phoneName: String? = nil,
                     msgId: String = "m1", actions: Bool = true,
                     at date: Date = Date(timeIntervalSince1970: 1_800_000_000)) -> CardItem {
        CardItem(phoneId: phone, phoneName: phoneName, msgId: msgId, posted: posted, receivedAt: date,
                 phoneAcceptsActions: actions)
    }

    static func id(_ key: String, phone: String = phone) -> String { CardItem.id(phoneId: phone, key: key) }

    /// "Mark as read" (index 0) and an inline "Reply" (index 1) with two suggested replies.
    static func withActions(_ posted: NotificationPosted) -> NotificationPosted {
        var p = posted
        p.actions = [
            .init(index: 0, title: "Mark as read", semantic: 2, reply: nil),
            .init(index: 1, title: "Reply", semantic: 1, reply: .init(label: "Message", choices: ["On my way", "Thanks!"])),
        ]
        p.canOpen = true
        return p
    }

    static func messaging(_ key: String = "chat1") -> NotificationPosted {
        var p = posted(key, title: "Ana", text: "Sure")
        p.template = "messaging"
        p.conversation = .init(title: "Weekend", isGroup: true, icon: blob(1))
        p.messages = [
            .init(sender: .init(name: "Ana", key: "ana", icon: blob(2)), text: "Dinner at 8?", ts: 1_800_000_000_000, image: nil),
            .init(sender: .init(name: "Ben", key: "ben", icon: nil), text: "Look", ts: 1_800_000_001_000, image: blob(3)),
            .init(sender: nil, text: "Sure", ts: 1_800_000_002_000, image: nil),
        ]
        return withActions(p)
    }

    static func bigText() -> NotificationPosted {
        var p = posted("news", app: "com.news", appName: "News", title: "Storm warning", text: "Heavy rain expected tonight")
        p.template = "big_text"
        p.bigText = "Heavy rain expected tonight across the region. Avoid travel after 9 pm and keep away from rivers."
        p.subText = "Weather"
        return p
    }

    static func inbox() -> NotificationPosted {
        var p = posted("mail", app: "com.mail", appName: "Mail", title: "4 new emails", text: "Ana, Ben, Cy, Dee")
        p.template = "inbox"
        p.lines = ["Ana  Lunch?", "Ben  Invoice", "Cy  Photos", "Dee  Re: plan"]
        p.summaryText = "me@example.com"
        return p
    }

    static func bigPicture() -> NotificationPosted {
        var p = posted("photo", app: "com.photos", appName: "Photos", title: "New memory", text: "Summer 2026")
        p.template = "big_picture"
        p.picture = blob(4)
        p.pictureDesc = "A beach at sunset"
        p.largeIcon = blob(5)
        return p
    }

    static func colorized(_ argb: Int) -> NotificationPosted {
        var p = posted("nav", app: "com.maps", appName: "Maps", title: "Turn left", text: "in 200 m")
        p.color = argb
        p.colorized = true
        return p
    }

    static func live(_ key: String = "ride", value: Int = 30) -> NotificationPosted {
        var p = posted(key, app: "com.ride", appName: "Ride", title: "Driver arriving", text: "Blue Corolla")
        p.template = "progress"
        p.ongoing = true
        p.progress = .init(value: value, max: 100, indeterminate: false, segments: nil, points: nil,
                           trackerIcon: nil, startIcon: nil, endIcon: nil)
        p.live = .init(promoted: true, shortCriticalText: "3 min")
        return p
    }

    static func incomingCall(_ key: String = "call") -> NotificationPosted {
        var p = posted(key, app: "com.phone", appName: "Phone", title: "Mom", text: "Incoming call")
        p.template = "call"
        p.call = .init(type: "incoming", person: .init(name: "Mom", key: nil, icon: blob(9)), isVideo: false,
                       verification: nil)
        return p
    }

    static func grouped(_ key: String, group: String = "g1", summary: Bool = false, sortKey: String? = nil,
                        when: Int? = nil, alert: String = "all") -> NotificationPosted {
        var p = posted(key, title: summary ? "3 new messages" : "Message \(key)")
        p.group = .init(key: group, summary: summary, sortKey: sortKey, alert: alert)
        p.when = when
        return p
    }

    /// Every image field set: with `NoImageProvider` none of them loads (index Review Focus 3).
    static func everyImage() -> NotificationPosted {
        var p = messaging("all")
        p.app.icon = blob(10)
        p.smallIcon = blob(11)
        p.largeIcon = blob(12)
        p.picture = blob(13)
        p.color = 0xFF1E88E5
        return p
    }
}
