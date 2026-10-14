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
 append-only by sequence number).
 */
final class EncryptedStore: Sendable {
    private let url: URL
    private let keys: KeyManager
    private let db = Mutex<OpaquePointer?>(nil)

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
        let rows = try query("SELECT id, payload FROM documents WHERE collection = ?", [.text(collection)])
        return rows.compactMap { row in
            guard row.count == 2, let id = String(data: row[0], encoding: .utf8) else { return nil }
            return try? JSONDecoder.store.decode(T.self, from: open(row[1], aad: "\(collection):\(id)"))
        }
    }

    func delete(_ collection: String, id: String) throws {
        try execute("DELETE FROM documents WHERE collection = ? AND id = ?", [.text(collection), .text(id)])
    }

    // MARK: Session logs

    /// Appends JSON log entries; `seq` must be new for the session.
    func appendEvents(sessionId: String, _ entries: [(seq: Int64, json: Data)]) throws {
        try transaction {
            for entry in entries {
                let payload = try seal(entry.json, aad: "event:\(sessionId):\(entry.seq)")
                try execute("INSERT INTO session_events (session_id, seq, payload) VALUES (?, ?, ?)", [.text(sessionId), .int(entry.seq), .blob(payload)])
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

    func deleteEvents(sessionId: String) throws {
        try execute("DELETE FROM session_events WHERE session_id = ?", [.text(sessionId)])
    }

    /// Closes the connection (before a panic wipe deletes the file).
    func close() {
        db.withLock { handle in
            if let h = handle { sqlite3_close_v2(h) }
            handle = nil
        }
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

    private func connection(_ handle: inout OpaquePointer?) throws -> OpaquePointer {
        if let h = handle { return h }
        var h: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX | SQLITE_OPEN_FILEPROTECTION_COMPLETE
        guard sqlite3_open_v2(url.path, &h, flags, nil) == SQLITE_OK, let opened = h else {
            if let h { sqlite3_close_v2(h) }
            throw StoreError(message: "Cannot open the database")
        }
        let schema = """
        PRAGMA secure_delete = ON;
        PRAGMA journal_mode = DELETE;
        CREATE TABLE IF NOT EXISTS documents (collection TEXT NOT NULL, id TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY (collection, id));
        CREATE TABLE IF NOT EXISTS session_events (session_id TEXT NOT NULL, seq INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY (session_id, seq));
        """
        guard sqlite3_exec(opened, schema, nil, nil, nil) == SQLITE_OK else {
            sqlite3_close_v2(opened)
            throw StoreError(message: "Cannot create the schema")
        }
        handle = opened
        return opened
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
        try db.withLock { handle in
            let h = try connection(&handle)
            var statement: OpaquePointer?
            guard sqlite3_prepare_v2(h, sql, -1, &statement, nil) == SQLITE_OK, let s = statement else {
                throw StoreError(message: "SQL prepare failed: \(String(cString: sqlite3_errmsg(h)))")
            }
            defer { sqlite3_finalize(s) }
            bind(s, values)
            guard sqlite3_step(s) == SQLITE_DONE else { throw StoreError(message: "SQL failed: \(String(cString: sqlite3_errmsg(h)))") }
        }
    }

    /// Rows of column values as raw bytes (text columns as UTF-8).
    private func query(_ sql: String, _ values: [Value]) throws -> [[Data]] {
        try db.withLock { handle in
            let h = try connection(&handle)
            var statement: OpaquePointer?
            guard sqlite3_prepare_v2(h, sql, -1, &statement, nil) == SQLITE_OK, let s = statement else {
                throw StoreError(message: "SQL prepare failed: \(String(cString: sqlite3_errmsg(h)))")
            }
            defer { sqlite3_finalize(s) }
            bind(s, values)
            var rows: [[Data]] = []
            while sqlite3_step(s) == SQLITE_ROW {
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

    private func transaction(_ body: () throws -> Void) throws {
        try execute("BEGIN IMMEDIATE")
        do {
            try body()
            try execute("COMMIT")
        } catch {
            try? execute("ROLLBACK")
            throw error
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
