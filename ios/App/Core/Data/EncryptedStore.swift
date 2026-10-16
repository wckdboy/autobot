import CryptoKit
import Foundation
import SQLite3
import Synchronization

struct StoreError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/**
 Encrypted persistence on the system SQLite (no third-party dependencies; the iOS counterpart
 of Room + SQLCipher).

 Rows only hold opaque ids in the clear. Every payload is a Codable value sealed with
 AES-256-GCM under the database key from [KeyManager] (random, Keychain-held, Secure Enclave
 wrapped), with `collection:id` as associated data so ciphertexts cannot be moved between rows.
 The file itself is protected with `NSFileProtectionComplete` and excluded from backups, and
 `secure_delete` overwrites deleted content.

 Two tables: `documents` (collection, id → payload) and `session_events` (agent session logs,
 append-only by sequence number). The database runs in WAL mode with two connections: writes go
 through one, reads through the other, so a read (a screen loading a log) never waits for a
 write (the agent appending events) and the other way round. Each keeps its prepared statements.
 */
final class EncryptedStore: Sendable {
    private let url: URL
    private let keys: KeyManager
    private let writeConnection = Mutex(Connection())
    private let readConnection = Mutex(Connection())
    /// Set by [close] (panic wipe): nothing may recreate the database afterwards.
    private let closed = Atomic<Bool>(false)

    init(url: URL = AppPaths.database, keys: KeyManager) {
        self.url = url
        self.keys = keys
    }

    // MARK: Documents

    func put<T: Encodable>(_ collection: String, id: String, _ value: T) throws {
        let payload = try seal(JSONEncoder.store.encode(value), aad: "\(collection):\(id)")
        try execute("INSERT OR REPLACE INTO documents (collection, id, payload) VALUES (?, ?, ?)", [.text(collection), .text(id), .blob(payload)])
    }

    func get<T: Decodable>(_ collection: String, id: String, as type: T.Type = T.self) throws -> T? {
        let rows = try query("SELECT payload FROM documents WHERE collection = ? AND id = ?", [.text(collection), .text(id)])
        guard let blob = rows.first?.first else { return nil }
        return try JSONDecoder.store.decode(T.self, from: open(blob, aad: "\(collection):\(id)"))
    }

    /// Every readable document of [collection]; undecryptable rows are skipped.
    func all<T: Decodable>(_ collection: String, as type: T.Type = T.self) throws -> [T] {
        try entries(collection, as: T.self).map(\.value)
    }

    /// Every readable document of [collection] with its id (ids starting with [idPrefix] only,
    /// when given), in id order; undecryptable rows are skipped.
    func entries<T: Decodable>(_ collection: String, idPrefix: String? = nil, as type: T.Type = T.self) throws -> [(id: String, value: T)] {
        let rows = if let idPrefix {
            try query("SELECT id, payload FROM documents WHERE collection = ? AND substr(id, 1, ?) = ? ORDER BY id", [.text(collection), .int(Int64(idPrefix.utf8.count)), .text(idPrefix)])
        } else {
            try query("SELECT id, payload FROM documents WHERE collection = ? ORDER BY id", [.text(collection)])
        }
        return rows.compactMap { row in
            guard row.count == 2, let id = String(data: row[0], encoding: .utf8),
                  let value = try? JSONDecoder.store.decode(T.self, from: open(row[1], aad: "\(collection):\(id)")) else { return nil }
            return (id, value)
        }
    }

    /// The ids of [collection] (nothing is decrypted).
    func ids(_ collection: String) throws -> [String] {
        try query("SELECT id FROM documents WHERE collection = ?", [.text(collection)]).compactMap { $0.first.flatMap { String(data: $0, encoding: .utf8) } }
    }

    func delete(_ collection: String, id: String) throws {
        try execute("DELETE FROM documents WHERE collection = ? AND id = ?", [.text(collection), .text(id)])
    }

    /// Deletes the documents of [collection] whose id starts with [idPrefix].
    func delete(_ collection: String, idPrefix: String) throws {
        try execute("DELETE FROM documents WHERE collection = ? AND substr(id, 1, ?) = ?", [.text(collection), .int(Int64(idPrefix.utf8.count)), .text(idPrefix)])
    }

    // MARK: Session logs

    /// Appends JSON log entries; an entry whose `seq` is already stored is skipped (a retried
    /// append after the device was locked repeats nothing).
    func appendEvents(sessionId: String, _ entries: [(seq: Int64, json: Data)]) throws {
        // Sealed before the transaction so the connection is held only for the inserts.
        let sealed = try entries.map { entry in (entry.seq, try seal(entry.json, aad: "event:\(sessionId):\(entry.seq)")) }
        try transaction { execute in
            for (seq, payload) in sealed {
                try execute("INSERT OR IGNORE INTO session_events (session_id, seq, payload) VALUES (?, ?, ?)", [.text(sessionId), .int(seq), .blob(payload)])
            }
        }
    }

    /// The session's log entries in order; undecryptable rows are skipped.
    func loadEvents(sessionId: String) throws -> [Data] {
        let rows = try query("SELECT seq, payload FROM session_events WHERE session_id = ? ORDER BY seq ASC", [.text(sessionId)])
        return rows.compactMap { row in
            guard row.count == 2, let seq = String(data: row[0], encoding: .utf8) else { return nil }
            return try? open(row[1], aad: "event:\(sessionId):\(seq)")
        }
    }

    /// Whether the session has a stored entry below [seq] (nothing is decrypted).
    func hasEvents(sessionId: String, before seq: Int64) throws -> Bool {
        try !query("SELECT 1 FROM session_events WHERE session_id = ? AND seq < ? LIMIT 1", [.text(sessionId), .int(seq)]).isEmpty
    }

    func deleteEvents(sessionId: String) throws {
        try execute("DELETE FROM session_events WHERE session_id = ?", [.text(sessionId)])
    }

    /// Closes the connections (before a panic wipe deletes the file).
    func close() {
        closed.store(true, ordering: .sequentiallyConsistent)
        writeConnection.withLock { $0.close() }
        readConnection.withLock { $0.close() }
    }

    // MARK: Crypto

    private func seal(_ data: Data, aad: String) throws -> Data {
        try AesGcmEnvelope.seal(keys.databaseKey(), data, aad: Data(aad.utf8))
    }

    private func open(_ data: Data, aad: String) throws -> Data {
        try AesGcmEnvelope.open(keys.databaseKey(), data, aad: Data(aad.utf8))
    }

    // MARK: SQLite

    private enum Value {
        case text(String), int(Int64), blob(Data)
    }

    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    /**
     One SQLite connection with its prepared statements (reused: preparing costs more than most
     of the statements it runs). Guarded by the [Mutex] that holds it.
     */
    private struct Connection {
        var handle: OpaquePointer?
        var statements: [String: OpaquePointer] = [:]

        mutating func statement(_ sql: String) throws -> OpaquePointer {
            if let s = statements[sql] { return s }
            guard let h = handle else { throw StoreError(message: "The database is not open") }
            var statement: OpaquePointer?
            guard sqlite3_prepare_v3(h, sql, -1, UInt32(SQLITE_PREPARE_PERSISTENT), &statement, nil) == SQLITE_OK, let s = statement else {
                throw StoreError(message: "SQL prepare failed: \(String(cString: sqlite3_errmsg(h)))")
            }
            statements[sql] = s
            return s
        }

        mutating func close() {
            statements.values.forEach { sqlite3_finalize($0) }
            statements.removeAll()
            if let h = handle { sqlite3_close_v2(h) }
            handle = nil
        }
    }

    /// The writer: opens (and creates) the database and the schema, in WAL mode.
    private func writer(_ c: inout Connection) throws {
        if c.handle != nil { return }
        guard !closed.load(ordering: .sequentiallyConsistent) else { throw StoreError(message: "The database was closed") }
        var h: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX | SQLITE_OPEN_FILEPROTECTION_COMPLETE
        guard sqlite3_open_v2(url.path, &h, flags, nil) == SQLITE_OK, let opened = h else {
            if let h { sqlite3_close_v2(h) }
            throw StoreError(message: "Cannot open the database")
        }
        // WAL: readers (the reader connection) never wait for a writer and a commit is one
        // append instead of a rollback journal; NORMAL syncs at checkpoints (a power cut can
        // lose the last commits, never corrupt). secure_delete stays on: deleted rows are
        // overwritten (in the WAL too, and in the database at checkpoint).
        let schema = """
        PRAGMA secure_delete = ON;
        PRAGMA journal_mode = WAL;
        PRAGMA synchronous = NORMAL;
        CREATE TABLE IF NOT EXISTS documents (collection TEXT NOT NULL, id TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY (collection, id));
        CREATE TABLE IF NOT EXISTS session_events (session_id TEXT NOT NULL, seq INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY (session_id, seq));
        """
        guard sqlite3_exec(opened, schema, nil, nil, nil) == SQLITE_OK else {
            sqlite3_close_v2(opened)
            throw StoreError(message: "Cannot create the schema")
        }
        sqlite3_busy_timeout(opened, 5_000)
        c.handle = opened
    }

    /// The reader: a second connection, so reads do not queue behind writes (WAL snapshots).
    private func reader(_ c: inout Connection) throws {
        if c.handle != nil { return }
        // The writer creates the file and the schema first.
        try writeConnection.withLock { try writer(&$0) }
        guard !closed.load(ordering: .sequentiallyConsistent) else { throw StoreError(message: "The database was closed") }
        var h: OpaquePointer?
        let flags = SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX | SQLITE_OPEN_FILEPROTECTION_COMPLETE
        guard sqlite3_open_v2(url.path, &h, flags, nil) == SQLITE_OK, let opened = h else {
            if let h { sqlite3_close_v2(h) }
            throw StoreError(message: "Cannot open the database")
        }
        sqlite3_busy_timeout(opened, 5_000)
        c.handle = opened
    }

    private func bind(_ statement: OpaquePointer, _ values: [Value]) {
        for (i, value) in values.enumerated() {
            let index = Int32(i + 1)
            switch value {
            case .text(let s): sqlite3_bind_text(statement, index, s, -1, Self.transient)
            case .int(let n): sqlite3_bind_int64(statement, index, n)
            case .blob(let d): d.withUnsafeBytes { _ = sqlite3_bind_blob(statement, index, $0.baseAddress, Int32(d.count), Self.transient) }
            }
        }
    }

    private func execute(_ sql: String, _ values: [Value] = []) throws {
        try writeConnection.withLock { c in
            try writer(&c)
            try run(&c, sql, values)
        }
    }

    /// One statement on [c] (the caller holds its lock and opened it).
    private func run(_ c: inout Connection, _ sql: String, _ values: [Value]) throws {
        let s = try c.statement(sql)
        defer {
            sqlite3_reset(s)
            sqlite3_clear_bindings(s)
        }
        bind(s, values)
        guard sqlite3_step(s) == SQLITE_DONE else { throw StoreError(message: "SQL failed: \(String(cString: sqlite3_errmsg(c.handle)))") }
    }

    /// Rows of column values as raw bytes (text columns as UTF-8), on the reader connection.
    private func query(_ sql: String, _ values: [Value]) throws -> [[Data]] {
        try readConnection.withLock { c in
            try reader(&c)
            let s = try c.statement(sql)
            defer {
                sqlite3_reset(s)
                sqlite3_clear_bindings(s)
            }
            bind(s, values)
            var rows: [[Data]] = []
            while true {
                let step = sqlite3_step(s)
                if step == SQLITE_DONE { break }
                guard step == SQLITE_ROW else { throw StoreError(message: "SQL failed: \(String(cString: sqlite3_errmsg(c.handle)))") }
                var row: [Data] = []
                for column in 0 ..< sqlite3_column_count(s) {
                    switch sqlite3_column_type(s, column) {
                    case SQLITE_INTEGER:
                        row.append(Data(String(sqlite3_column_int64(s, column)).utf8))
                    case SQLITE_NULL:
                        row.append(Data())
                    default:
                        let count = Int(sqlite3_column_bytes(s, column))
                        if let bytes = sqlite3_column_blob(s, column) { row.append(Data(bytes: bytes, count: count)) } else { row.append(Data()) }
                    }
                }
                rows.append(row)
            }
            return rows
        }
    }

    /**
     Runs [body] in one transaction with the writer held throughout, so no statement from
     another thread lands inside it (or starts a second BEGIN on the connection). [body] gets
     the statement runner to use instead of [execute].
     */
    private func transaction(_ body: (_ execute: (String, [Value]) throws -> Void) throws -> Void) throws {
        try writeConnection.withLock { c in
            try writer(&c)
            try run(&c, "BEGIN IMMEDIATE", [])
            do {
                try body { sql, values in try run(&c, sql, values) }
                try run(&c, "COMMIT", [])
            } catch {
                try? run(&c, "ROLLBACK", [])
                throw error
            }
        }
    }
}

extension JSONEncoder {
    static var store: JSONEncoder {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return e
    }
}

extension JSONDecoder {
    static var store: JSONDecoder { JSONDecoder() }
}
