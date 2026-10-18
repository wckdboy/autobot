import Foundation

/**
 A recipe (ROADMAP Phase 2.5 item 7) as the iOS app holds it: a Codable mirror of the shared
 `Recipe` JSON (`RecipeCodec`). Every rule (next runs, validation, wording) is the shared
 `RecipeBridge`'s; Swift only edits fields and round-trips the JSON through it.
 */
struct RecipeDoc: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var name: String
    var prompt: String
    var schedule: RecipeScheduleDoc
    /// The on-device model id ("" = This phone's default).
    var model = ""
    var tools: [String] = []
    var includeRecentChats = false
    var requiresCharging = false
    var requiresBatteryNotLow = true
    var enabled = false
    var template: String?
    var createdAt: Int64 = 0
    var state = RecipeStateDoc()

    init(id: String, name: String, prompt: String, schedule: RecipeScheduleDoc) {
        (self.id, self.name, self.prompt, self.schedule) = (id, name, prompt, schedule)
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        prompt = try c.decode(String.self, forKey: .prompt)
        schedule = try c.decode(RecipeScheduleDoc.self, forKey: .schedule)
        model = try c.decodeIfPresent(String.self, forKey: .model) ?? ""
        tools = try c.decodeIfPresent([String].self, forKey: .tools) ?? []
        includeRecentChats = try c.decodeIfPresent(Bool.self, forKey: .includeRecentChats) ?? false
        requiresCharging = try c.decodeIfPresent(Bool.self, forKey: .requiresCharging) ?? false
        requiresBatteryNotLow = try c.decodeIfPresent(Bool.self, forKey: .requiresBatteryNotLow) ?? true
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? false
        template = try c.decodeIfPresent(String.self, forKey: .template)
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        state = try c.decodeIfPresent(RecipeStateDoc.self, forKey: .state) ?? RecipeStateDoc()
    }

    /// The shared JSON.
    var json: String { (try? JSONEncoder().encode(self)).map { String(decoding: $0, as: UTF8.self) } ?? "{}" }

    init?(json: String) {
        guard let value = try? JSONDecoder().decode(RecipeDoc.self, from: Data(json.utf8)) else { return nil }
        self = value
    }

    /// Whether an allowed tool reaches the network.
    var usesNetwork: Bool { RecipeRules.usesNetwork(json) }
}

/// The shared `RecipeSchedule`: `{"type":"daily"|"weekdays"|"weekly"|"hours", …}`.
enum RecipeScheduleDoc: Codable, Equatable, Hashable, Sendable {
    case daily(minuteOfDay: Int)
    case weekdays(minuteOfDay: Int)
    /// [dayOfWeek]: ISO, 1 = Monday … 7 = Sunday.
    case weekly(dayOfWeek: Int, minuteOfDay: Int)
    case hours(Int)

    private enum Keys: String, CodingKey { case type, minuteOfDay, dayOfWeek, hours }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Keys.self)
        switch try c.decode(String.self, forKey: .type) {
        case "weekdays": self = .weekdays(minuteOfDay: try c.decode(Int.self, forKey: .minuteOfDay))
        case "weekly": self = .weekly(dayOfWeek: try c.decode(Int.self, forKey: .dayOfWeek), minuteOfDay: try c.decode(Int.self, forKey: .minuteOfDay))
        case "hours": self = .hours(try c.decode(Int.self, forKey: .hours))
        default: self = .daily(minuteOfDay: try c.decode(Int.self, forKey: .minuteOfDay))
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Keys.self)
        switch self {
        case .daily(let m):
            try c.encode("daily", forKey: .type)
            try c.encode(m, forKey: .minuteOfDay)
        case .weekdays(let m):
            try c.encode("weekdays", forKey: .type)
            try c.encode(m, forKey: .minuteOfDay)
        case .weekly(let d, let m):
            try c.encode("weekly", forKey: .type)
            try c.encode(d, forKey: .dayOfWeek)
            try c.encode(m, forKey: .minuteOfDay)
        case .hours(let h):
            try c.encode("hours", forKey: .type)
            try c.encode(h, forKey: .hours)
        }
    }

    enum Kind: String, CaseIterable, Identifiable, Sendable {
        case daily, weekdays, weekly, hours
        var id: String { rawValue }

        /// As the shared `RecipeSchedule.Kind` labels.
        var label: String {
            switch self {
            case .daily: "Daily"
            case .weekdays: "Weekdays"
            case .weekly: "Weekly"
            case .hours: "Every N hours"
            }
        }
    }

    var kind: Kind {
        switch self {
        case .daily: .daily
        case .weekdays: .weekdays
        case .weekly: .weekly
        case .hours: .hours
        }
    }

    /// The time of day (nil for an interval).
    var minute: Int? {
        switch self {
        case .daily(let m), .weekdays(let m), .weekly(_, let m): m
        case .hours: nil
        }
    }

    /// A schedule of [kind] keeping this one's time (shared `RecipeSchedule.of`).
    func with(kind: Kind) -> RecipeScheduleDoc {
        let m = minute ?? 7 * 60
        switch kind {
        case .daily: return .daily(minuteOfDay: m)
        case .weekdays: return .weekdays(minuteOfDay: m)
        case .weekly: if case .weekly = self { return self } else { return .weekly(dayOfWeek: 1, minuteOfDay: m) }
        case .hours: if case .hours = self { return self } else { return .hours(6) }
        }
    }

    func with(minute m: Int) -> RecipeScheduleDoc {
        switch self {
        case .daily: .daily(minuteOfDay: m)
        case .weekdays: .weekdays(minuteOfDay: m)
        case .weekly(let d, _): .weekly(dayOfWeek: d, minuteOfDay: m)
        case .hours: self
        }
    }
}

struct RecipeStateDoc: Codable, Equatable, Sendable {
    var nextRunAt: Int64?
    var waitingForUnlock = false
    var lastRun: RecipeRunDoc?

    init(nextRunAt: Int64? = nil, waitingForUnlock: Bool = false, lastRun: RecipeRunDoc? = nil) {
        (self.nextRunAt, self.waitingForUnlock, self.lastRun) = (nextRunAt, waitingForUnlock, lastRun)
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        nextRunAt = try c.decodeIfPresent(Int64.self, forKey: .nextRunAt)
        waitingForUnlock = try c.decodeIfPresent(Bool.self, forKey: .waitingForUnlock) ?? false
        lastRun = try c.decodeIfPresent(RecipeRunDoc.self, forKey: .lastRun)
    }
}

/// The shared `RecipeRunRecord`.
struct RecipeRunDoc: Codable, Equatable, Sendable {
    var startedAt: Int64
    var finishedAt: Int64
    /// DONE, FAILED or TIMED_OUT.
    var status: String
    /// SCHEDULE or MANUAL.
    var trigger = "SCHEDULE"
    var sessionId: String?
    var summary = ""
    var notes: [String] = []
    var hosts = 0
    var bytesSent: Int64 = 0

    init(startedAt: Int64, finishedAt: Int64, status: String, sessionId: String? = nil, summary: String = "") {
        (self.startedAt, self.finishedAt, self.status, self.sessionId, self.summary) = (startedAt, finishedAt, status, sessionId, summary)
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        startedAt = try c.decode(Int64.self, forKey: .startedAt)
        finishedAt = try c.decode(Int64.self, forKey: .finishedAt)
        status = try c.decode(String.self, forKey: .status)
        trigger = try c.decodeIfPresent(String.self, forKey: .trigger) ?? "SCHEDULE"
        sessionId = try c.decodeIfPresent(String.self, forKey: .sessionId)
        summary = try c.decodeIfPresent(String.self, forKey: .summary) ?? ""
        notes = try c.decodeIfPresent([String].self, forKey: .notes) ?? []
        hosts = try c.decodeIfPresent(Int.self, forKey: .hosts) ?? 0
        bytesSent = try c.decodeIfPresent(Int64.self, forKey: .bytesSent) ?? 0
    }

    init?(json: String) {
        guard let value = try? JSONDecoder().decode(RecipeRunDoc.self, from: Data(json.utf8)) else { return nil }
        self = value
    }

    var json: String { (try? JSONEncoder().encode(self)).map { String(decoding: $0, as: UTF8.self) } ?? "{}" }
    var done: Bool { status == "DONE" }
}
