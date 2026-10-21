import Foundation

/// The basic catalog's icon names mapped to SF Symbols (the Material names the catalog uses, in camelCase).
enum A2uiIcons {
    static let symbols: [String: String] = [
        "accountCircle": "person.crop.circle",
        "add": "plus",
        "arrowBack": "arrow.left",
        "arrowForward": "arrow.right",
        "attachFile": "paperclip",
        "calendarToday": "calendar",
        "call": "phone.fill",
        "camera": "camera",
        "check": "checkmark",
        "close": "xmark",
        "delete": "trash",
        "download": "arrow.down.circle",
        "edit": "pencil",
        "event": "calendar.badge.clock",
        "error": "exclamationmark.circle",
        "fastForward": "forward.fill",
        "favorite": "heart.fill",
        "favoriteOff": "heart",
        "folder": "folder",
        "help": "questionmark.circle",
        "home": "house",
        "info": "info.circle",
        "locationOn": "mappin.and.ellipse",
        "lock": "lock",
        "lockOpen": "lock.open",
        "mail": "envelope",
        "menu": "line.3.horizontal",
        "moreVert": "ellipsis", // drawn turned 90° (A2uiIconView)
        "moreHoriz": "ellipsis",
        "notificationsOff": "bell.slash",
        "notifications": "bell",
        "pause": "pause.fill",
        "payment": "creditcard",
        "person": "person",
        "phone": "phone",
        "photo": "photo",
        "play": "play.fill",
        "print": "printer",
        "refresh": "arrow.clockwise",
        "rewind": "backward.fill",
        "search": "magnifyingglass",
        "send": "paperplane",
        "settings": "gearshape",
        "share": "square.and.arrow.up",
        "shoppingCart": "cart",
        "skipNext": "forward.end.fill",
        "skipPrevious": "backward.end.fill",
        "star": "star.fill",
        "starHalf": "star.leadinghalf.filled",
        "starOff": "star",
        "stop": "stop.fill",
        "upload": "arrow.up.circle",
        "visibility": "eye",
        "visibilityOff": "eye.slash",
        "volumeDown": "speaker.wave.1",
        "volumeMute": "speaker",
        "volumeOff": "speaker.slash",
        "volumeUp": "speaker.wave.3",
        "warning": "exclamationmark.triangle",
    ]

    /// The SF Symbol for a catalog name (also accepts snake_case), or a neutral fallback.
    static func symbol(_ name: String) -> String {
        if let s = symbols[name] { return s }
        let camel = name.split(separator: "_").enumerated()
            .map { $0.offset == 0 ? String($0.element) : $0.element.prefix(1).uppercased() + $0.element.dropFirst() }
            .joined()
        return symbols[camel] ?? "questionmark.square.dashed"
    }

    /// A spoken name for the icon ("arrowBack" → "arrow back").
    static func label(_ name: String) -> String {
        var out = ""
        for ch in name.replacingOccurrences(of: "_", with: " ") {
            if ch.isUppercase { out += " " + ch.lowercased() } else { out.append(ch) }
        }
        return out
    }
}
