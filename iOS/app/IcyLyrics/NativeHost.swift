import UIKit
import UniformTypeIdentifiers
import SpotifyiOS
import IcyShared

/// UIKit owns OS presentations only. Compose owns every application screen.
@MainActor
final class NativeHost: NSObject, IosHost, UIDocumentPickerDelegate, SPTAppRemoteDelegate {
    private weak var window: UIWindow?
    private let authorization: SpotifyAuthorization
    private var hasCreatedAppRemote = false
    private lazy var remoteConfiguration = SPTConfiguration(clientID: authorization.clientID,
                                                            redirectURL: authorization.callbackURL)
    private lazy var appRemote: SPTAppRemote = {
        hasCreatedAppRemote = true
        let remote = SPTAppRemote(configuration: remoteConfiguration, logLevel: .none)
        remote.delegate = self
        return remote
    }()
    private var closed = false
    private var active = false
    private var connectionGeneration = UUID()
    private var needsFreshTransport = true
    private var playerUpdateRevision: UInt64 = 0
    private var stateRequestID = UUID()
    private var connectionTask: Task<Void, Never>?
    private var refreshTimer: Timer?
    private var playerState: SPTAppRemotePlayerState?
    private var playerObserver: PlayerStateObserver?
    private var artworkURI: String?
    private var importTask: Task<Void, Never>?
    private var importWorker: Task<ImportedTtml, Error>?
    private var importGeneration = UUID()
    private weak var documentPicker: UIDocumentPickerViewController?
    private var authorizationStatusTask: Task<Void, Never>?
    private var authorizationStatusGeneration = UUID()
    private var authorizationFlowGeneration = UUID()
    private var keepAwake = true
    private var lyricsConnected = false
    // Kotlin retains its IosHost. The forwarding object must retain this owner
    // weakly, otherwise NativeHost and IosAppController can never be released.
    private lazy var hostForwarder = WeakIosHost(self)
    private var retainedController: IosAppController?
    private var controller: IosAppController {
        if let retainedController { return retainedController }
        let value = IosAppController(host: hostForwarder,
            versionName: Bundle.main.object(forInfoDictionaryKey: "IcyDisplayVersion") as? String
                ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0",
            authAvailable: !authorization.clientID.isEmpty)
        retainedController = value
        return value
    }

    init(window: UIWindow) {
        self.window = window
        let client = Bundle.main.object(forInfoDictionaryKey: "SpotifyClientID") as? String ?? ""
        let configuredCallback = URL(string: Bundle.main.object(forInfoDictionaryKey: "SpotifyRedirectURI") as? String ?? "")
        let callback = configuredCallback.flatMap { $0.scheme == nil ? nil : $0 }
            ?? URL(string: "com.icy.lyrics.ios://spotify-callback")!
        authorization = SpotifyAuthorization(clientID: client, callbackURL: callback)
        super.init()
        authorization.window = window
    }

    func makeViewController() -> UIViewController {
        #if DEBUG
        if let index = ProcessInfo.processInfo.arguments.firstIndex(of: "--icy-fixture"),
           ProcessInfo.processInfo.arguments.indices.contains(index + 1) {
            return IosParityKt.createIcyParityViewController(scenarioId: ProcessInfo.processInfo.arguments[index + 1])
        }
        #endif
        return controller.makeViewController()
    }

    private var isFixture: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("--icy-fixture")
        #else
        return false
        #endif
    }
    func activate() {
        guard !closed, !isFixture, !active else { return }
        active = true
        UIApplication.shared.isIdleTimerDisabled = keepAwake
        reconnect()
        guard !authorization.isAuthorizing else { return }
        authorizationStatusTask?.cancel()
        let generation = UUID()
        authorizationStatusGeneration = generation
        authorizationStatusTask = Task { [weak self] in
            guard let self else { return }
            let connected: Bool
            do { connected = try await self.authorization.token(.lyrics) != nil }
            catch { connected = false }
            guard !Task.isCancelled, !self.closed, self.active,
                  self.authorizationStatusGeneration == generation, !self.authorization.isAuthorizing else { return }
            self.lyricsConnected = connected
            self.controller.authorizationChanged(inProgress: false, connected: self.lyricsConnected, message: nil)
        }
    }
    func deactivate() {
        active = false
        connectionGeneration = UUID()
        needsFreshTransport = true
        connectionTask?.cancel()
        connectionTask = nil
        authorizationStatusGeneration = UUID()
        authorizationStatusTask?.cancel()
        authorizationStatusTask = nil
        refreshTimer?.invalidate()
        refreshTimer = nil
        if hasCreatedAppRemote {
            appRemote.playerAPI?.delegate = nil
            appRemote.delegate = nil
            appRemote.disconnect()
        }
        playerObserver = nil
        playerState = nil
        artworkURI = nil
        retainedController?.playbackDisconnected(message: nil)
        UIApplication.shared.isIdleTimerDisabled = false
    }
    func open(_ url: URL) { if !closed { authorization.handle(url) } }

    func close() {
        guard !closed else { return }
        closed = true
        deactivate()
        authorizationFlowGeneration = UUID()
        authorization.cancel()
        invalidateImport()
        documentPicker?.delegate = nil
        documentPicker = nil
        retainedController?.cancelImport()
        retainedController?.close()
        retainedController = nil
    }

    private func reconnect() {
        guard active, !closed, !authorization.clientID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        connectionTask?.cancel()
        let generation = UUID()
        connectionGeneration = generation
        artworkURI = nil
        connectionTask = Task { [weak self] in
            guard let self else { return }
            do {
                guard let token = try await self.authorization.token(.playback), !Task.isCancelled,
                      self.active, !self.closed, self.connectionGeneration == generation else { return }
                if !self.needsFreshTransport, self.hasCreatedAppRemote, self.appRemote.isConnected,
                   self.appRemote.connectionParameters.accessToken == token {
                    self.installPlayerObserver(generation: generation)
                    self.requestFreshState()
                } else {
                    // A fresh transport identity makes late delegate events from
                    // an earlier connection attempt distinguishable and harmless.
                    self.refreshTimer?.invalidate()
                    self.refreshTimer = nil
                    if self.hasCreatedAppRemote {
                        self.appRemote.playerAPI?.delegate = nil
                        self.appRemote.delegate = nil
                        self.appRemote.disconnect()
                    }
                    self.playerObserver = nil
                    self.playerState = nil
                    self.artworkURI = nil
                    self.appRemote = SPTAppRemote(configuration: self.remoteConfiguration, logLevel: .none)
                    self.hasCreatedAppRemote = true
                    self.needsFreshTransport = false
                    self.appRemote.delegate = self
                    self.appRemote.connectionParameters.accessToken = token
                    self.controller.playbackConnecting()
                    self.appRemote.connect()
                }
            } catch {
                guard !Task.isCancelled, !self.closed, self.active, self.connectionGeneration == generation else { return }
                self.controller.showError(message: error.localizedDescription)
            }
        }
    }

    func connectSpotify(forLyrics: Bool) {
        guard !closed else { return }
        authorizationStatusGeneration = UUID()
        authorizationStatusTask?.cancel()
        let generation = UUID()
        authorizationFlowGeneration = generation
        controller.authorizationChanged(inProgress: true, connected: lyricsConnected, message: nil)
        authorization.begin(forLyrics ? .lyrics : .playback) { [weak self] result in
            guard let self, !self.closed, self.authorizationFlowGeneration == generation else { return }
            switch result {
            case .success(let purpose):
                if purpose == .lyrics { self.lyricsConnected = true }
                self.controller.authorizationChanged(inProgress: false, connected: self.lyricsConnected, message: nil)
                if purpose == .playback {
                    // A user action may switch apps. Foreground reconnection never does.
                    UIApplication.shared.open(URL(string: "spotify://")!, options: [:]) { [weak self] opened in
                        guard let self, !self.closed, self.authorizationFlowGeneration == generation else { return }
                        if !opened { self.controller.showError(message: "Install Spotify, start a song, then return to Icy Lyrics.") }
                    }
                    self.reconnect()
                }
            case .failure(let error):
                self.controller.authorizationChanged(inProgress: false, connected: self.lyricsConnected,
                                                       message: error.localizedDescription)
            }
        }
    }
    func cancelSpotifyAuthorization() { authorization.cancel() }
    func disconnectSpotify() {
        guard !closed else { return }
        authorizationStatusGeneration = UUID()
        authorizationStatusTask?.cancel()
        // The existing settings action disconnects the narrowly scoped lyrics provider.
        // Playback credentials remain separate and never reach that provider.
        do {
            try authorization.disconnect(.lyrics)
            lyricsConnected = false
            controller.authorizationChanged(inProgress: false, connected: false, message: "Disconnected Spotify lyrics authorization.")
        } catch { controller.showError(message: error.localizedDescription) }
    }
    func lyricsAccessToken(rejectedToken: String?, completion: @escaping (String?) -> Void) {
        Task { [weak self] in
            guard let self, !self.closed else { completion(nil); return }
            do {
                let token = try await self.authorization.token(.lyrics, rejected: rejectedToken)
                completion(self.closed ? nil : token)
            }
            catch { completion(nil) }
        }
    }

    func appRemoteDidEstablishConnection(_ appRemote: SPTAppRemote) {
        guard hasCreatedAppRemote, appRemote === self.appRemote else { appRemote.disconnect(); return }
        guard active, !closed else { appRemote.disconnect(); return }
        let generation = connectionGeneration
        installPlayerObserver(generation: generation)
        appRemote.playerAPI?.subscribe(toPlayerState: { [weak self] _, error in
            guard let self, !self.closed, self.active, self.connectionGeneration == generation else { return }
            if error != nil { self.controller.showError(message: "Spotify playback updates could not be subscribed. Tap reconnect and try again.") }
        })
        requestFreshState()
        refreshTimer?.invalidate()
        refreshTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.requestFreshState() }
        }
    }
    func appRemote(_ appRemote: SPTAppRemote, didFailConnectionAttemptWithError error: Error?) {
        guard !closed, hasCreatedAppRemote, appRemote === self.appRemote else { return }
        needsFreshTransport = true
        if active { controller.playbackDisconnected(message: "Open Spotify and start a song, then return to Icy Lyrics to reconnect.") }
    }
    func appRemote(_ appRemote: SPTAppRemote, didDisconnectWithError error: Error?) {
        guard !closed, hasCreatedAppRemote, appRemote === self.appRemote else { return }
        connectionGeneration = UUID()
        needsFreshTransport = true
        refreshTimer?.invalidate()
        refreshTimer = nil
        playerState = nil
        playerObserver = nil
        artworkURI = nil
        if active { controller.playbackDisconnected(message: "Spotify disconnected. Open Spotify, then return to Icy Lyrics.") }
    }
    private func requestFreshState() {
        guard active, !closed, hasCreatedAppRemote, appRemote.isConnected else { return }
        let generation = connectionGeneration
        let revision = playerUpdateRevision
        let requestID = UUID()
        stateRequestID = requestID
        appRemote.playerAPI?.getPlayerState { [weak self] result, error in
            guard let self, !self.closed, self.active, self.connectionGeneration == generation,
                  self.playerUpdateRevision == revision, self.stateRequestID == requestID,
                  let state = result as? SPTAppRemotePlayerState else { return }
            self.receivePlayerState(state, generation: generation, isSubscription: false)
        }
    }
    private func installPlayerObserver(generation: UUID) {
        let observer = PlayerStateObserver(host: self, generation: generation)
        playerObserver = observer
        appRemote.playerAPI?.delegate = observer
    }
    fileprivate func receivePlayerState(_ playerState: SPTAppRemotePlayerState, generation: UUID, isSubscription: Bool = true) {
        guard active, !closed, connectionGeneration == generation, appRemote.isConnected else { return }
        // A polling response must not rewind a newer push update or track change.
        if isSubscription { playerUpdateRevision &+= 1 }
        self.playerState = playerState
        let track = playerState.track
        var actions: Int64 = 2 | 4 | 512 // Android's neutral pause/play/toggle capability bits.
        if playerState.playbackRestrictions.canSkipPrevious { actions |= 16 }
        if playerState.playbackRestrictions.canSkipNext { actions |= 32 }
        if playerState.playbackRestrictions.canSeek { actions |= 256 }
        controller.updatePlayback(uri: track.uri, title: track.name, artist: track.artist.name, album: track.album.name,
            durationMs: Int64(clamping: track.duration), positionMs: Int64(playerState.playbackPosition),
            speed: playerState.playbackSpeed, playing: !playerState.isPaused, actions: actions)
        if artworkURI != track.uri {
            artworkURI = track.uri
            let uri = track.uri
            let generation = connectionGeneration
            appRemote.imageAPI?.fetchImage(forItem: track, with: CGSize(width: 1024, height: 1024)) { [weak self] image, error in
                guard let self, !self.closed, self.active, self.appRemote.isConnected,
                      self.connectionGeneration == generation, self.artworkURI == uri else { return }
                guard let data = (image as? UIImage)?.pngData() else { self.artworkURI = nil; return }
                self.controller.updateArtwork(data: data as NSData, forUri: uri)
            }
        }
    }
    private var commandCallback: SPTAppRemoteCallback {
        let generation = connectionGeneration
        return { [weak self] _, error in
            guard let self, !self.closed, self.active, self.connectionGeneration == generation else { return }
            if error != nil { self.controller.showError(message: "Spotify could not complete that playback action.") }
            self.requestFreshState()
        }
    }
    func playPause() {
        guard active, !closed else { return }
        guard let state = playerState, appRemote.isConnected else { reconnect(); return }
        if state.isPaused { appRemote.playerAPI?.resume(commandCallback) }
        else { appRemote.playerAPI?.pause(commandCallback) }
    }
    func previous() {
        guard active, !closed, hasCreatedAppRemote, appRemote.isConnected, playerState?.playbackRestrictions.canSkipPrevious == true else { return }
        appRemote.playerAPI?.skip(toPrevious: commandCallback)
    }
    func next() {
        guard active, !closed, hasCreatedAppRemote, appRemote.isConnected, playerState?.playbackRestrictions.canSkipNext == true else { return }
        appRemote.playerAPI?.skip(toNext: commandCallback)
    }
    func seekTo(positionMs: Int64) {
        guard active, !closed, hasCreatedAppRemote, appRemote.isConnected, playerState?.playbackRestrictions.canSeek == true else { return }
        let duration = playerState.map { Int64(clamping: $0.track.duration) } ?? Int64.max
        appRemote.playerAPI?.seek(toPosition: Int(clamping: min(max(0, positionMs), duration)), callback: commandCallback)
    }

    func pickTtml() {
        guard !closed else { return }
        invalidateImport()
        guard let presenter = window?.rootViewController, presenter.presentedViewController == nil else {
            controller.cancelImport()
            return
        }
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.xml, .plainText, .data], asCopy: true)
        picker.allowsMultipleSelection = false
        picker.delegate = self
        documentPicker = picker
        presenter.present(picker, animated: true)
    }
    func documentPickerWasCancelled(_ picker: UIDocumentPickerViewController) {
        guard !closed, picker === documentPicker else { return }
        documentPicker = nil
        invalidateImport()
        controller.cancelImport()
    }
    func documentPicker(_ picker: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard !closed, picker === documentPicker else { return }
        documentPicker = nil
        guard let source = urls.first else { controller.cancelImport(); return }
        let generation = importGeneration
        let worker = Task.detached(priority: .userInitiated) { try ImportedTtml.copy(source) }
        importWorker = worker
        importTask = Task { [weak self] in
            do {
                let imported = try await withTaskCancellationHandler {
                    try await worker.value
                } onCancel: {
                    worker.cancel()
                }
                guard let self, !Task.isCancelled, !self.closed, self.importGeneration == generation else {
                    imported.discard()
                    return
                }
                self.importWorker = nil
                self.importTask = nil
                self.controller.completeImport(text: imported.text, sourceUri: imported.url.absoluteString)
            } catch {
                guard let self, !Task.isCancelled, !self.closed, self.importGeneration == generation else { return }
                self.importWorker = nil
                self.importTask = nil
                self.controller.cancelImport()
                self.controller.showError(message: error.localizedDescription)
            }
        }
    }
    private func invalidateImport() {
        importGeneration = UUID()
        importTask?.cancel()
        importWorker?.cancel()
        importTask = nil
        importWorker = nil
    }
    func shareDiagnostics(text: String) {
        guard !closed else { return }
        let share = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        share.popoverPresentationController?.sourceView = window?.rootViewController?.view
        window?.rootViewController?.present(share, animated: true)
    }
    func setKeepAwake(enabled: Bool) {
        keepAwake = enabled
        UIApplication.shared.isIdleTimerDisabled = !closed && active && enabled
    }
}

@MainActor
private final class PlayerStateObserver: NSObject, SPTAppRemotePlayerStateDelegate {
    weak var host: NativeHost?
    let generation: UUID
    init(host: NativeHost, generation: UUID) {
        self.host = host
        self.generation = generation
        super.init()
    }
    func playerStateDidChange(_ playerState: SPTAppRemotePlayerState) {
        host?.receivePlayerState(playerState, generation: generation)
    }
}

@MainActor
private final class WeakIosHost: NSObject, IosHost {
    private weak var host: NativeHost?
    init(_ host: NativeHost) { self.host = host; super.init() }
    func playPause() { host?.playPause() }
    func previous() { host?.previous() }
    func next() { host?.next() }
    func seekTo(positionMs: Int64) { host?.seekTo(positionMs: positionMs) }
    func connectSpotify(forLyrics: Bool) { host?.connectSpotify(forLyrics: forLyrics) }
    func cancelSpotifyAuthorization() { host?.cancelSpotifyAuthorization() }
    func disconnectSpotify() { host?.disconnectSpotify() }
    func pickTtml() { host?.pickTtml() }
    func shareDiagnostics(text: String) { host?.shareDiagnostics(text: text) }
    func setKeepAwake(enabled: Bool) { host?.setKeepAwake(enabled: enabled) }
    func lyricsAccessToken(rejectedToken: String?, completion: @escaping (String?) -> Void) {
        guard let host else { completion(nil); return }
        host.lyricsAccessToken(rejectedToken: rejectedToken, completion: completion)
    }
}

struct ImportedTtml: Sendable {
    let text: String
    let url: URL
    private init(text: String, url: URL) { self.text = text; self.url = url }
    /// Only an import-created UUID destination can be discarded through this type.
    func discard() { try? FileManager.default.removeItem(at: url) }

    static func copy(_ source: URL, into directory: URL? = nil) throws -> ImportedTtml {
        try Task.checkCancellation()
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let folder: URL
        if let directory { folder = directory }
        else {
            folder = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                appropriateFor: nil, create: true).appendingPathComponent("IcyLyrics/Imports")
        }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let destination = folder.appendingPathComponent(UUID().uuidString + ".ttml")
        var completed = false
        defer { if !completed { try? FileManager.default.removeItem(at: destination) } }
        var readError: Error?
        var coordinationError: NSError?
        var result: ImportedTtml?
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: source, options: [], error: &coordinationError) { readable in
            do {
                try Task.checkCancellation()
                let attributes = try FileManager.default.attributesOfItem(atPath: readable.path)
                guard let size = attributes[.size] as? NSNumber, (Int64(0)...Int64(8_000_000)).contains(size.int64Value),
                      attributes[.type] as? FileAttributeType == .typeRegular else { throw CocoaError(.fileReadTooLarge) }
                // Bound the read even if the provider changes its reported file size.
                let input = try FileHandle(forReadingFrom: readable)
                defer { try? input.close() }
                let bytes = try readBounded { try input.read(upToCount: $0) }
                guard let text = String(data: bytes, encoding: .utf8),
                      text.utf16.count <= 2_000_000, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                else { throw CocoaError(.fileReadCorruptFile) }
                try Task.checkCancellation()
                try bytes.write(to: destination, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                try Task.checkCancellation()
                result = ImportedTtml(text: text, url: destination)
            } catch { readError = error }
        }
        if let coordinationError { throw coordinationError }
        if let readError { throw readError }
        try Task.checkCancellation()
        guard let result else { throw CocoaError(.fileReadCorruptFile) }
        completed = true
        return result
    }

    /// FileHandle may return a short read before EOF. Loop until EOF and read at
    /// most one byte beyond the limit to detect oversized or growing files.
    static func readBounded(maximumBytes: Int = 8_000_000,
                            read: (Int) throws -> Data?) throws -> Data {
        precondition(maximumBytes >= 0 && maximumBytes < Int.max)
        var result = Data()
        while true {
            try Task.checkCancellation()
            let requested = min(65_536, maximumBytes - result.count + 1)
            guard let chunk = try read(requested), !chunk.isEmpty else { return result }
            guard chunk.count <= requested, chunk.count <= maximumBytes - result.count else {
                throw CocoaError(.fileReadTooLarge)
            }
            result.append(chunk)
        }
    }
}
