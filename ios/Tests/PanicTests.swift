import AutobotShared
import Foundation
import Synchronization
import Testing
@testable import Autobot

/**
 Panic protection on iOS (the counterpart of Android's `PanicAndroidTest`): the PIN lock and its
 counter, the lock screen's PIN path, and whole wipes (and interrupted ones) on a temporary folder
 that stands in for Application Support. Nothing here touches the app's real folders, the Keychain
 or the live `WipeSteps`.
 */
@MainActor
@Suite(.serialized)
struct PanicTests {
    private let base: URL
    private let paths: PanicPaths

    init() throws {
        base = FileManager.default.temporaryDirectory.appendingPathComponent("panic-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        paths = PanicPaths(base: base, appRoot: base.appendingPathComponent("Autobot", isDirectory: true))
    }

    private func lock(_ key: MemoryLockKey = MemoryLockKey()) -> PinLock { PinLock(paths: paths, keys: key) }

    // MARK: PIN lock

    @Test func pinSettingsFollowTheSharedRules() {
        let pin = lock()
        #expect(pin.setPin("12", confirm: "12") == "Use 4 to 12 digits")
        #expect(pin.setPin("2468", confirm: "2469") == "The PINs do not match")
        #expect(pin.setPin("2468", confirm: "2468") == nil)
        #expect(pin.record.hasPin)
        #expect(pin.setPin("1357", confirm: "1357") == "A PIN is already set")
        #expect(pin.setWipeAfter(10, current: "0000") == PinLock.wrongPin)
        #expect(pin.setWipeAfter(7, current: "2468") == "Unsupported wipe threshold")
        #expect(pin.setWipeAfter(10, current: "2468") == nil)
        #expect(pin.setDuress(current: "2468", duress: "2468", confirm: "2468") == "The duress PIN must differ from your PIN")
        #expect(pin.setDuress(current: "2468", duress: "9753", confirm: "9753") == nil)
        #expect(pin.changePin(current: "2468", pin: "9753", confirm: "9753") == "Your PIN must differ from the duress PIN")
        #expect(pin.changePin(current: "2468", pin: "1357", confirm: "1357") == nil)
        #expect(pin.matchesPin("1357"))
        // The file holds verifiers only, never a PIN.
        let text = String(decoding: (try? Data(contentsOf: paths.lockRecord)) ?? Data(), as: UTF8.self)
        #expect(!text.contains("1357") && !text.contains("9753"))
        // Persisted: a new lock on the same files sees the same settings.
        let again = PinLock(paths: paths, keys: MemoryLockKey(pin.keys.read()))
        #expect(again.record.wipeAfter == 10)
        #expect(again.record.duress != nil)
        #expect(again.removePin(current: "2468") == PinLock.wrongPin)
        #expect(again.removePin(current: "1357") == nil)
        #expect(!again.record.hasPin && again.record.duress == nil && again.record.wipeAfter == 0)
    }

    @Test func aNewDeviceKeyOpensNothing() {
        let pin = lock()
        #expect(pin.setPin("2468", confirm: "2468") == nil)
        let copied = PinLock(paths: paths, keys: MemoryLockKey())
        #expect(!copied.matchesPin("2468"))
    }

    @Test func attemptsAreCountedBeforeTheCheckAndBackOff() {
        let pin = lock()
        _ = pin.setPin("2468", confirm: "2468")
        var now: Int64 = 1_000_000
        for i in 1 ... 4 {
            #expect(pin.attempt("0000", now: now) is UnlockOutcomeFailed)
            #expect(pin.record.failures == Int32(i))
        }
        #expect(!pin.record.biometricsAllowed)
        // The fourth failure starts a 30 s wait: refused and not counted.
        #expect(pin.attempt("2468", now: now) == nil)
        #expect(pin.record.failures == 4)
        now += 30_000
        #expect(pin.attempt("2468", now: now) is UnlockOutcomeUnlocked)
        #expect(pin.record.failures == 0)
        #expect(pin.record.biometricsAllowed)
    }

    @Test func aKilledAttemptStaysCounted() throws {
        let pin = lock()
        _ = pin.setPin("2468", confirm: "2468")
        // A kill between begin and resolve leaves the begun record on disk.
        let begun = try #require(UnlockPolicy.shared.begin(record: pin.record, now: 1_000_000))
        try pin.save(begun)
        #expect(PinLock(paths: paths, keys: pin.keys).record.failures == 1)
    }

    @Test func theDuressPinAndTheLastFailureAskForAWipe() {
        let pin = lock()
        _ = pin.setPin("2468", confirm: "2468")
        _ = pin.setWipeAfter(5, current: "2468")
        _ = pin.setDuress(current: "2468", duress: "9753", confirm: "9753")
        #expect(pin.attempt("9753", now: 1_000_000) is UnlockOutcomeDuress)
        var last: (any UnlockOutcome)?
        var now: Int64 = 1_000_000
        for _ in 1 ... 5 {
            now += 31 * 60_000
            last = pin.attempt("0000", now: now)
        }
        #expect(last is UnlockOutcomeWipe)
    }

    // MARK: Lock screen

    @Test func theLockScreenHandsTheDuressPinAndTheLimitToTheWipe() async {
        let pin = lock()
        _ = pin.setPin("2468", confirm: "2468")
        _ = pin.setDuress(current: "2468", duress: "9753", confirm: "9753")
        let manager = AppLockManager(pin: pin)
        manager.configure(enabled: false, timeoutSeconds: 60)
        // A PIN always locks, even with the device lock off.
        #expect(manager.isLockActive())
        #expect(manager.state == .locked)
        #expect(manager.usesPin)
        var panics: [(WipeLanding, PinVerifier?)] = []
        manager.onPanic = { landing, carried in panics.append((landing, carried)) }

        guard case .wrong(_, let refused) = await manager.submitPin("0000") else { Issue.record("a wrong PIN unlocked"); return }
        #expect(!refused)
        #expect(manager.state == .locked)
        #expect(await manager.submitPin("9753") == .wiping)
        #expect(panics.count == 1)
        #expect(panics.first?.0 == .quiet)
        #expect(panics.first?.1 == pin.record.duress)
        #expect(manager.state == .locked)
        #expect(await manager.submitPin("2468") == .unlocked)
        #expect(manager.state == .unlocked)

        _ = pin.setWipeAfter(5, current: "2468")
        let begun = pin.record
        try? pin.save(begun.doCopy(pin: begun.pin, duress: begun.duress, wipeAfter: 5, wipeModels: false, biometrics: true, failures: 4, lastFailureAt: 0, quietStart: false))
        #expect(await manager.submitPin("0000") == .wiping)
        #expect(panics.last?.0 == .firstRun)
    }

    @Test func aQuietStartPrintsAwayAsAnUnlock() {
        let pin = lock()
        _ = pin.setPin("2468", confirm: "2468")
        let manager = AppLockManager(pin: pin)
        manager.beginQuietStart()
        manager.configure(enabled: true, timeoutSeconds: 60)
        #expect(manager.state == .locked)
        manager.finishQuietStart()
        #expect(manager.state == .unlocked)
        #expect(!manager.quietStart)
    }

    // MARK: Wipes

    private final class Calls: @unchecked Sendable {
        let keys = Mutex(0)
        let other = Mutex(0)
    }

    private func steps(_ pin: PinLock, _ calls: Calls, sweep: [URL] = []) -> WipeSteps {
        WipeSteps(
            paths: paths,
            lock: pin,
            destroyKeys: { calls.keys.withLock { $0 += 1 } },
            sweep: sweep,
            clearOther: { calls.other.withLock { $0 += 1 } },
        )
    }

    private func seed() throws {
        let fm = FileManager.default
        for (path, text) in [
            ("autobot.sqlite", "db"), ("preferences.json", "{}"), ("gallery/a.img", "img"), ("workspace/notes.md", "notes"),
            ("models/qwen/model.gguf", "weights"),
        ] {
            let url = paths.appRoot.appendingPathComponent(path)
            try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data(text.utf8).write(to: url)
        }
    }

    private func exists(_ path: String) -> Bool { FileManager.default.fileExists(atPath: paths.appRoot.appendingPathComponent(path).path) }

    @Test func aFirstRunWipeDeletesEverythingWithTheModels() async throws {
        try seed()
        let cache = base.appendingPathComponent("cache", isDirectory: true)
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        try Data("c".utf8).write(to: cache.appendingPathComponent("c.bin"))
        let outside = base.appendingPathComponent("elsewhere.txt")
        try Data("mine".utf8).write(to: outside)
        let key = MemoryLockKey()
        let pin = lock(key)
        _ = pin.setPin("2468", confirm: "2468")
        let calls = Calls()
        var stopped: [String] = []
        let wipe = PanicWipe(steps: steps(pin, calls, sweep: [cache]), participants: [{ stopped.append("agent") }, { stopped.append("store") }])
        let report = await wipe.wipe(landing: .firstRun, keepModels: false)
        #expect(report.steps == WipePlan.shared.Steps.map(\.name))
        #expect(stopped == ["agent", "store"])
        #expect(calls.keys.withLock { $0 } == 1)
        #expect(calls.other.withLock { $0 } == 1)
        #expect(!FileManager.default.fileExists(atPath: paths.appRoot.path))
        #expect(paths.tombs().isEmpty)
        #expect((try? FileManager.default.contentsOfDirectory(atPath: cache.path))?.isEmpty == true)
        #expect(!FileManager.default.fileExists(atPath: paths.journal.path))
        #expect(!FileManager.default.fileExists(atPath: paths.lockRecord.path))
        #expect(key.read() == nil, "first run forgets the PIN key")
        #expect(String(decoding: try Data(contentsOf: outside), as: UTF8.self) == "mine")
    }

    @Test func aQuietWipeKeepsModelsAndLandsOnTheDuressPin() async throws {
        try seed()
        let key = MemoryLockKey()
        let pin = lock(key)
        _ = pin.setPin("2468", confirm: "2468")
        _ = pin.setDuress(current: "2468", duress: "9753", confirm: "9753")
        let duress = pin.record.duress
        let wipe = PanicWipe(steps: steps(pin, Calls()), keptModels: { #"[{"id":"qwen"}]"# }, participants: [])
        _ = await wipe.wipe(landing: .quiet, keepModels: true, carriedPin: duress, biometrics: false)
        #expect(!exists("autobot.sqlite") && !exists("gallery/a.img") && !exists("workspace/notes.md") && !exists("preferences.json"))
        #expect(String(decoding: try Data(contentsOf: paths.appRoot.appendingPathComponent("models/qwen/model.gguf")), as: UTF8.self) == "weights")
        #expect(String(decoding: try Data(contentsOf: paths.keptRegistry), as: UTF8.self) == #"[{"id":"qwen"}]"#)
        #expect(key.read() != nil, "the duress PIN keeps its key")
        let landed = PinLock(paths: paths, keys: key)
        #expect(landed.record.pin == duress)
        #expect(landed.record.duress == nil)
        #expect(landed.record.quietStart)
        #expect(!landed.record.biometrics)
        #expect(landed.matchesPin("9753"))
        #expect(!landed.matchesPin("2468"))
    }

    @Test func aWipeCutOffAfterTheKeysFinishesOnTheNextStart() throws {
        try seed()
        let pin = lock()
        let calls = Calls()
        let s = steps(pin, calls)
        // What a kill right after the keys leaves behind: the journal says DESTROY_KEYS.
        let journal = WipePlan.shared.journal(landing: .firstRun, keepModels: true, keptModels: "[]", carriedPin: nil, biometrics: true)
        try s.commit(journal.after(step: .destroyKeys))
        #expect(s.pending()?.done == .destroyKeys)

        #expect(s.resumeIfNeeded())
        #expect(calls.keys.withLock { $0 } == 1, "the keys are destroyed again")
        #expect(s.pending() == nil)
        #expect(!exists("autobot.sqlite") && !exists("gallery/a.img"))
        #expect(exists("models/qwen/model.gguf"))
        #expect(!s.resumeIfNeeded(), "nothing left to finish")
    }

    @Test func aGarbledJournalStillFinishesTheWipeKeepingNothing() throws {
        try seed()
        try Data("garbage".utf8).write(to: paths.journal)
        let s = steps(lock(), Calls())
        #expect(s.pending()?.keepModels == false)
        #expect(s.resumeIfNeeded())
        #expect(!FileManager.default.fileExists(atPath: paths.appRoot.path))
    }

    @Test func aTombLeftByAKillIsDeletedOnTheNextStart() throws {
        let tomb = paths.tomb()
        try FileManager.default.createDirectory(at: tomb, withIntermediateDirectories: true)
        try Data("x".utf8).write(to: tomb.appendingPathComponent("autobot.sqlite"))
        #expect(!steps(lock(), Calls()).resumeIfNeeded())
        #expect(paths.tombs().isEmpty)
    }

    // MARK: Incognito images

    @Test func unsavedImagesGoWithTheirChatAndAreBounded() {
        let images = UnsavedImages()
        func put(_ owner: String) -> String {
            let id = images.newId()
            images.put(GalleryItem(id: id, createdAt: 1, mode: "TXT2IMG", prompt: "p", negativePrompt: "", width: 8, height: 8, seed: 1, steps: 1, cfgScale: 1,
                                   sampler: "", engine: "t", model: nil, durationMs: 1, paramsJson: "{}"), image: Data([1]), thumbnail: Data([2]), owner: owner)
            return id
        }
        let mine = put("incognito-a")
        let sub = put("incognito-a::task")
        let other = put("incognito-b")
        #expect(UnsavedImages.isUnsaved(mine))
        images.drop(owner: "incognito-a")
        #expect(images.get(mine) == nil && images.get(sub) == nil)
        #expect(images.get(other) != nil)
        let first = put(UnsavedImages.imageScreen)
        for _ in 0 ..< UnsavedImages.max { _ = put(UnsavedImages.imageScreen) }
        #expect(images.ids.count == UnsavedImages.max)
        #expect(images.get(first) == nil)
        images.clear()
        #expect(images.ids.isEmpty)
    }

    @Test func keptModelsAreExportedAsCatalogRowsOnly() throws {
        let store = EncryptedStore(url: base.appendingPathComponent("db.sqlite"), keys: KeyManager())
        let library = ModelLibrary(store: store)
        #expect(library.exportReady() == nil)
        // A registry for a model whose folder is gone is not adopted, and the file is consumed.
        let file = base.appendingPathComponent("kept.json")
        try Data("[]".utf8).write(to: file)
        #expect(library.adoptKept(from: file) == 0)
        #expect(!FileManager.default.fileExists(atPath: file.path))
    }

    @Test func settingsShowThePinRowsUnderAppLock() {
        #expect(SettingsSection.privacy.rows.prefix(3) == [.privacyCenter, .appLock, .appPin])
        #expect(SettingsRow.appPin.title() == PanicCopy.shared.PIN_TITLE)
    }
}
