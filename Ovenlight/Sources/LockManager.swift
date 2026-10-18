import LocalAuthentication
import SwiftUI

/// Gates the whole app behind Face ID (falling back to the device passcode).
@MainActor
final class LockManager: ObservableObject {
    @Published private(set) var isLocked: Bool
    @Published private(set) var isAuthenticating = false
    @Published var lastError: String?
    /// This iPhone has no passcode, so there is nothing to unlock with and Ovenlight stays
    /// open; Settings says so.
    @Published private(set) var noPasscode = false

    static let lockAfterKey = "lockAfterSeconds"
    static let defaultLockAfter = 60

    /// Seconds in the background before Ovenlight locks again. 0 locks every time.
    @AppStorage(LockManager.lockAfterKey) var lockAfterSeconds = LockManager.defaultLockAfter

    /// On a clock that keeps counting while the iPhone sleeps and ignores changes to the
    /// time of day, so setting the clock back can't skip the lock. It starts over when the
    /// iPhone restarts, so it's saved with the boot it was read in, and outlives Ovenlight
    /// being quit in the background.
    private var backgroundedAt: ContinuousClock.Instant?
    private let now: () -> ContinuousClock.Instant
    private let boot: () -> String?
    private let defaults: UserDefaults
    static let backgroundedKey = "backgroundedAt"
    /// Face ID starts by itself at launch and on each return from the background, never
    /// again after the person cancels it; the Face ID sheet itself makes Ovenlight briefly
    /// inactive, and coming back from that is no reason to ask again.
    private var promptsWhenActive = true

    /// Whether there is anything to protect yet. A new install stays unlocked until there is.
    private let hasSavedData: () -> Bool

    /// A launch locks unless Ovenlight was quit in the background within the lock delay,
    /// since this iPhone last started.
    init(hasSavedData: @escaping () -> Bool = { true }, now: @escaping () -> ContinuousClock.Instant = { .now },
         boot: @escaping () -> String? = LockManager.bootSession, defaults: UserDefaults = .standard) {
        self.hasSavedData = hasSavedData
        self.now = now
        self.boot = boot
        self.defaults = defaults
        isLocked = hasSavedData()
        if let saved = defaults.data(forKey: Self.backgroundedKey),
           let backgrounded = try? JSONDecoder().decode(Backgrounded.self, from: saved), let boot = boot(), backgrounded.boot == boot {
            backgroundedAt = backgrounded.at
        }
        if isLocked, backgroundedAt != nil, !lockIsDue { isLocked = false }
    }

    private struct Backgrounded: Codable {
        var boot: String
        var at: ContinuousClock.Instant
    }

    /// This boot of the iPhone: the UUID the kernel makes at each start, or, where the sandbox
    /// doesn't allow reading it, the boot time (which a clock change can move, locking sooner).
    /// Nil if neither can be read, and then a launch locks.
    nonisolated static func bootSession() -> String? {
        var size = 0
        if sysctlbyname("kern.bootsessionuuid", nil, &size, nil, 0) == 0, size > 1 {
            var buffer = [CChar](repeating: 0, count: size)
            if sysctlbyname("kern.bootsessionuuid", &buffer, &size, nil, 0) == 0 { return String(cString: buffer) }
        }
        var time = timeval()
        size = MemoryLayout<timeval>.size
        guard sysctlbyname("kern.boottime", &time, &size, nil, 0) == 0, time.tv_sec > 0 else { return nil }
        return "boottime-\(time.tv_sec).\(time.tv_usec)"
    }

    /// The first time since Ovenlight was last in front counts, so a launch in the background
    /// doesn't start the delay over.
    func didEnterBackground() {
        if !isLocked, backgroundedAt == nil {
            let at = now()
            backgroundedAt = at
            if let boot = boot() {
                defaults.set(try? JSONEncoder().encode(Backgrounded(boot: boot, at: at)), forKey: Self.backgroundedKey)
            }
        }
        promptsWhenActive = true
    }

    /// Whether the next return to the front locks. A link opened on a return arrives before
    /// `willBecomeActive`, so it asks this too.
    var lockIsDue: Bool {
        guard let since = backgroundedAt, hasSavedData() else { return false }
        let elapsed = since.duration(to: now())
        return elapsed < .zero || elapsed >= .seconds(lockAfterSeconds)
    }

    /// Whether an app a link asks for must wait for the unlock.
    var holdsLinks: Bool { isLocked || lockIsDue }

    func willBecomeActive() {
        if lockIsDue { isLocked = true }
        backgroundedAt = nil
        defaults.removeObject(forKey: Self.backgroundedKey)
    }

    func lock() {
        isLocked = true
    }

    func didBecomeActive() async {
        guard isLocked, promptsWhenActive else { return }
        promptsWhenActive = false
        await unlock()
    }

    func unlock() async {
        guard isLocked, !isAuthenticating else { return }
        isAuthenticating = true
        defer { isAuthenticating = false }
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            if let error, Self.isPasscodeNotSet(error) { return openWithoutPasscode() }
            lastError = error?.localizedDescription ?? "Ovenlight can't ask for your passcode right now."
            return
        }
        do {
            try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Unlock your apps")
            lastError = nil
            noPasscode = false
            isLocked = false
        } catch where Self.isPasscodeNotSet(error) {
            openWithoutPasscode()
        } catch let error as LAError where error.code == .userCancel || error.code == .appCancel || error.code == .systemCancel {
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    /// Without a passcode there is nothing to authenticate with, and staying locked would
    /// keep everything saved out of reach for good.
    private func openWithoutPasscode() {
        lastError = nil
        noPasscode = true
        isLocked = false
    }

    nonisolated static func isPasscodeNotSet(_ error: Error) -> Bool {
        let ns = error as NSError
        return ns.domain == LAErrorDomain && ns.code == LAError.passcodeNotSet.rawValue
    }

    /// What unlocks Ovenlight, read once per launch. Face ID that isn't set up, or that the
    /// person turned down for Ovenlight, leaves the passcode, though `biometryType` still
    /// names the sensor.
    let biometry: Biometry = {
        let context = LAContext()
        let usable = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        switch usable ? context.biometryType : .none {
        case .faceID: return Biometry(name: "Face ID", symbol: "faceid")
        case .touchID: return Biometry(name: "Touch ID", symbol: "touchid")
        case .opticID: return Biometry(name: "Optic ID", symbol: "opticid")
        default: return Biometry(name: "Passcode", symbol: "lock.fill")
        }
    }()

    struct Biometry {
        let name: String
        let symbol: String
    }
}
