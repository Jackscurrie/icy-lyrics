import UIKit
import UniformTypeIdentifiers
import SpotifyiOS
import IcyShared

/// UIKit owns OS presentations only. Compose owns every application screen.
@MainActor
final class NativeHost: NSObject, IosHost, UIDocumentPickerDelegate, SPTAppRemoteDelegate {
    private enum PlaybackSource { case appRemote, webAPI }

    // Spotify 5.0.1 declares both weak delegate properties non-null. A retained
    // sink detaches the host without assigning nil or weakening the SDK contract.
    private static let detachedRemoteDelegate = DetachedAppRemoteDelegate()
    private weak var window: UIWindow?
    private let authorization: SpotifyAuthorization
    private let nowPlayingClient = SpotifyNowPlayingClient()
    private let artworkClient = SpotifyArtworkClient()
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
    private var playbackMonitorTask: Task<Void, Never>?
    private var playbackMonitorGeneration = UUID()
    private var lastAppRemoteSampleUptime: TimeInterval?
    private var playbackSource: PlaybackSource?
    private var webArtworkTask: Task<Void, Never>?
    private var webArtworkGeneration = UUID()
    private var reportedAppRemoteError: String?
    private var reportedWebAPIError: String?
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
    private var playbackAuthorized = false
    private var accountGeneration = UUID()
    private var reconnectSuppressed = false
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
        if let index = ProcessInfo.processInfo.arguments.firstIndex(of: "--icy-kawarp-probe"),
           ProcessInfo.processInfo.arguments.indices.contains(index + 1),
           let runID = ProcessInfo.processInfo.environment["ICY_KAWARP_RUN_ID"] {
            return KawarpGpuProbeViewController(caseID: ProcessInfo.processInfo.arguments[index + 1], runID: runID)
        }
        if let index = ProcessInfo.processInfo.arguments.firstIndex(of: "--icy-fixture"),
           ProcessInfo.processInfo.arguments.indices.contains(index + 1) {
            return ParityFixtureViewController(scenario: ProcessInfo.processInfo.arguments[index + 1])
        }
        #endif
        return controller.makeViewController()
    }

    private var isFixture: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("--icy-fixture")
            || ProcessInfo.processInfo.arguments.contains("--icy-kawarp-probe")
        #else
        return false
        #endif
    }
    func activate() {
        guard !closed, !isFixture, !active else { return }
        active = true
        UIApplication.shared.isIdleTimerDisabled = keepAwake
        publishAuthorizationStatus()
        reconnect()
    }
    func deactivate() {
        active = false
        stopPlaybackMonitor()
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
            appRemote.playerAPI?.delegate = Self.detachedRemoteDelegate
            appRemote.delegate = Self.detachedRemoteDelegate
            appRemote.disconnect()
        }
        playerObserver = nil
        playerState = nil
        artworkURI = nil
        retainedController?.playbackDisconnected(message: nil)
        UIApplication.shared.isIdleTimerDisabled = false
    }
    func open(_ url: URL) {
        guard !closed else { return }
        // This host obtains the persisted App Remote token through its own PKCE
        // browser. Spotify SDK authorizeAndPlayURI callbacks use a different
        // access-token/error shape and must not invalidate that pending request.
        authorization.handleSceneCallback(url)
    }

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
        guard active, !closed, !reconnectSuppressed, !authorization.clientID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        connectionTask?.cancel()
        let generation = UUID()
        connectionGeneration = generation
        artworkURI = nil
        connectionTask = Task { [weak self] in
            guard let self else { return }
            do {
                let token = try await self.authorization.token(.playback)
                guard !Task.isCancelled, self.active, !self.closed, !self.reconnectSuppressed,
                      self.connectionGeneration == generation else { return }
                self.publishAuthorizationStatus()
                guard let token else { return }
                self.startPlaybackMonitor()
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
                        self.appRemote.playerAPI?.delegate = Self.detachedRemoteDelegate
                        self.appRemote.delegate = Self.detachedRemoteDelegate
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
        if !forLyrics {
            accountGeneration = UUID()
            stopPlaybackMonitor()
        }
        authorizationStatusGeneration = UUID()
        authorizationStatusTask?.cancel()
        authorizationStatusTask = nil
        let generation = UUID()
        authorizationFlowGeneration = generation
        controller.authorizationChanged(inProgress: true, connected: playbackAuthorized, message: nil)
        authorization.begin(forLyrics ? .lyrics : .playback) { [weak self] result in
            guard let self, !self.closed, self.authorizationFlowGeneration == generation else { return }
            switch result {
            case .success(let purpose):
                self.publishAuthorizationStatus()
                if purpose == .lyrics { self.controller.lyricsAuthorizationReady() }
                if purpose == .playback {
                    self.reconnectSuppressed = false
                    // A user action may switch apps. Foreground reconnection never does.
                    UIApplication.shared.open(URL(string: "spotify://")!, options: [:]) { [weak self] opened in
                        guard let self, !self.closed, self.authorizationFlowGeneration == generation else { return }
                        if !opened { self.controller.showError(message: "Install Spotify, start a song, then return to Icy Lyrics.") }
                    }
                    self.reconnect()
                }
            case .failure(let error):
                self.publishAuthorizationStatus(message: error.localizedDescription)
            }
        }
    }
    private func publishAuthorizationStatus(message: String? = nil) {
        // A failed read must not hide a previously available Disconnect control.
        if let value = try? authorization.hasStoredAuthorization(.playback) { playbackAuthorized = value }
        if let value = try? authorization.hasStoredAuthorization(.lyrics) { lyricsConnected = value }
        // The shared connected/readiness flag controls playback onboarding. A
        // separate lyrics grant must never hide Connect when playback needs a
        // fresh consent grant.
        controller.authorizationChanged(inProgress: authorization.isAuthorizing,
            connected: playbackAuthorized, message: message)
    }
    /// Invoked only by explicit provider/consent controls, never foregrounding.
    func ensureLyricsAuthorization() {
        guard !closed, authorizationStatusTask == nil, !authorization.isAuthorizing else { return }
        let generation = UUID()
        authorizationStatusGeneration = generation
        authorizationStatusTask = Task { [weak self] in
            guard let self else { return }
            defer { if self.authorizationStatusGeneration == generation { self.authorizationStatusTask = nil } }
            do {
                let token = try await self.authorization.token(.lyrics)
                guard !Task.isCancelled, !self.closed, self.authorizationStatusGeneration == generation else { return }
                self.publishAuthorizationStatus()
                if token != nil { self.controller.lyricsAuthorizationReady() }
                else { self.connectSpotify(forLyrics: true) }
            } catch {
                guard !Task.isCancelled, !self.closed, self.authorizationStatusGeneration == generation else { return }
                self.publishAuthorizationStatus(message: error.localizedDescription)
            }
        }
    }
    func cancelLyricsAuthorization() {
        authorizationStatusGeneration = UUID()
        authorizationStatusTask?.cancel()
        authorizationStatusTask = nil
        if authorization.authorizingPurpose == .lyrics { authorization.cancel() }
    }
    func cancelSpotifyAuthorization() {
        cancelLyricsAuthorization()
        authorization.cancel()
        publishAuthorizationStatus()
    }
    func disconnectSpotify() {
        guard !closed else { return }
        accountGeneration = UUID()
        stopPlaybackMonitor()
        authorizationFlowGeneration = UUID()
        authorizationStatusGeneration = UUID()
        authorizationStatusTask?.cancel()
        authorizationStatusTask = nil
        reconnectSuppressed = true
        connectionGeneration = UUID()
        connectionTask?.cancel()
        connectionTask = nil
        refreshTimer?.invalidate()
        refreshTimer = nil
        needsFreshTransport = true
        if hasCreatedAppRemote {
            appRemote.playerAPI?.delegate = Self.detachedRemoteDelegate
            appRemote.delegate = Self.detachedRemoteDelegate
            appRemote.disconnect()
        }
        playerObserver = nil
        playerState = nil
        artworkURI = nil
        invalidateImport()
        documentPicker?.delegate = nil
        documentPicker?.dismiss(animated: true)
        documentPicker = nil
        controller.accountDisconnected()
        do {
            try authorization.disconnect()
            publishAuthorizationStatus(message: "Spotify disconnected.")
        } catch { publishAuthorizationStatus(message: error.localizedDescription) }
    }
    func lyricsAccessToken(rejectedToken: String?, completion: @escaping (String?) -> Void) {
        let generation = accountGeneration
        Task { [weak self] in
            guard let self, !self.closed else { completion(nil); return }
            do {
                let token = try await self.authorization.token(.lyrics, rejected: rejectedToken)
                completion(self.closed || self.accountGeneration != generation ? nil : token)
            }
            catch { completion(nil) }
        }
    }

    /// App Remote remains the preferred low-latency source. This independent
    /// monitor fills the same shared snapshot when a sideloaded bundle cannot
    /// establish Spotify's local App Remote channel.
    private func startPlaybackMonitor() {
        guard active, !closed, !reconnectSuppressed, playbackMonitorTask == nil else { return }
        let generation = UUID()
        let account = accountGeneration
        playbackMonitorGeneration = generation
        playbackMonitorTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.playbackMonitorGeneration == generation {
                    self.playbackMonitorTask = nil
                }
            }
            while self.isPlaybackMonitorCurrent(generation, account: account) {
                guard let delay = await self.pollCurrentlyPlaying(generation: generation, account: account)
                else { return }
                do {
                    try await Task.sleep(nanoseconds: UInt64(max(1, delay) * 1_000_000_000))
                } catch {
                    return
                }
            }
        }
    }

    private func stopPlaybackMonitor() {
        playbackMonitorGeneration = UUID()
        playbackMonitorTask?.cancel()
        playbackMonitorTask = nil
        webArtworkGeneration = UUID()
        webArtworkTask?.cancel()
        webArtworkTask = nil
        lastAppRemoteSampleUptime = nil
        playbackSource = nil
        reportedAppRemoteError = nil
        reportedWebAPIError = nil
    }

    private func isPlaybackMonitorCurrent(_ generation: UUID, account: UUID) -> Bool {
        !Task.isCancelled && active && !closed && !reconnectSuppressed
            && playbackMonitorGeneration == generation && accountGeneration == account
    }

    private func pollCurrentlyPlaying(generation: UUID, account: UUID) async -> TimeInterval? {
        let revision = playerUpdateRevision
        do {
            guard let accessToken = try await authorization.token(.playback) else {
                guard isPlaybackMonitorCurrent(generation, account: account) else { return nil }
                publishAuthorizationStatus(message: "Reconnect Spotify to allow current-song access.")
                return nil
            }

            let item: SpotifyNowPlayingItem?
            do {
                item = try await nowPlayingClient.fetch(accessToken: accessToken)
            } catch SpotifyNowPlayingError.unauthorized {
                guard isPlaybackMonitorCurrent(generation, account: account) else { return nil }
                guard let refreshed = try await authorization.token(.playback, rejected: accessToken) else {
                    publishAuthorizationStatus(message: "Reconnect Spotify to allow current-song access.")
                    return nil
                }
                do {
                    item = try await nowPlayingClient.fetch(accessToken: refreshed)
                } catch SpotifyNowPlayingError.unauthorized {
                    // One forced refresh is enough for a single poll. A second
                    // rejection means this grant cannot be used safely again.
                    try? authorization.disconnect(.playback)
                    publishAuthorizationStatus(message: "Reconnect Spotify to allow current-song access.")
                    return nil
                }
            }

            guard isPlaybackMonitorCurrent(generation, account: account) else { return nil }
            guard playerUpdateRevision == revision else { return 2 }
            if let sampled = lastAppRemoteSampleUptime,
               ProcessInfo.processInfo.systemUptime - sampled < 12 {
                reportedWebAPIError = nil
                return 5
            }
            guard let item else {
                playbackSource = nil
                artworkURI = nil
                webArtworkGeneration = UUID()
                webArtworkTask?.cancel()
                webArtworkTask = nil
                controller.clearPlayback()
                reportedWebAPIError = nil
                return 5
            }

            playerUpdateRevision &+= 1
            playbackSource = .webAPI
            controller.updatePlayback(uri: item.uri, title: item.title, artist: item.artist, album: item.album,
                durationMs: item.durationMs, positionMs: item.positionMs,
                speed: item.isPlaying ? 1 : 0, playing: item.isPlaying, actions: 0)
            fetchWebArtwork(for: item, generation: generation, account: account)
            reportedWebAPIError = nil
            return 5
        } catch is CancellationError {
            return nil
        } catch SpotifyNowPlayingError.rateLimited(let retryAfterSeconds) {
            guard isPlaybackMonitorCurrent(generation, account: account) else { return nil }
            return TimeInterval(min(max(retryAfterSeconds ?? 15, 5), 300))
        } catch {
            guard isPlaybackMonitorCurrent(generation, account: account) else { return nil }
            if (try? authorization.hasStoredAuthorization(.playback)) != true {
                publishAuthorizationStatus(message: "Reconnect Spotify to allow current-song access.")
                return nil
            }
            reportPlaybackIssue("Spotify current-song lookup failed", error: error, source: .webAPI)
            return 15
        }
    }

    private func fetchWebArtwork(for item: SpotifyNowPlayingItem, generation: UUID, account: UUID) {
        guard let url = item.artworkURL, artworkURI != item.uri else { return }
        webArtworkGeneration = UUID()
        webArtworkTask?.cancel()
        let artworkGeneration = webArtworkGeneration
        let uri = item.uri
        artworkURI = uri
        webArtworkTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.webArtworkGeneration == artworkGeneration {
                    self.webArtworkTask = nil
                }
            }
            do {
                let encoded = try await self.artworkClient.fetch(url)
                guard self.isPlaybackMonitorCurrent(generation, account: account),
                      self.webArtworkGeneration == artworkGeneration,
                      self.artworkURI == uri,
                      let data = UIImage(data: encoded)?.pngData() else { return }
                self.controller.updateArtwork(data: data, forUri: uri)
            } catch is CancellationError {
                return
            } catch {
                guard self.isPlaybackMonitorCurrent(generation, account: account),
                      self.webArtworkGeneration == artworkGeneration else { return }
                // The track and lyrics remain usable when optional artwork fails.
                self.artworkURI = nil
            }
        }
    }

    private func reportPlaybackIssue(_ context: String, error: Error?, source: PlaybackSource) {
        let detail: String
        if let error {
            let value = error as NSError
            detail = "\(context) (\(value.domain) \(value.code))."
        } else {
            detail = "\(context)."
        }
        switch source {
        case .appRemote:
            guard reportedAppRemoteError != detail else { return }
            reportedAppRemoteError = detail
        case .webAPI:
            guard reportedWebAPIError != detail else { return }
            reportedWebAPIError = detail
        }
        controller.showError(message: detail)
    }

    func appRemoteDidEstablishConnection(_ appRemote: SPTAppRemote) {
        guard hasCreatedAppRemote, appRemote === self.appRemote else { appRemote.disconnect(); return }
        guard active, !closed, !reconnectSuppressed else { appRemote.disconnect(); return }
        let generation = connectionGeneration
        startPlaybackMonitor()
        installPlayerObserver(generation: generation)
        appRemote.playerAPI?.subscribe(toPlayerState: { [weak self] _, error in
            guard let self, !self.closed, self.active, self.connectionGeneration == generation else { return }
            if let error {
                self.reportPlaybackIssue("Spotify App Remote could not subscribe to playback updates",
                                         error: error, source: .appRemote)
            }
        })
        requestFreshState()
        refreshTimer?.invalidate()
        refreshTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.requestFreshState() }
        }
    }
    func appRemote(_ appRemote: SPTAppRemote, didFailConnectionAttemptWithError error: Error?) {
        guard !closed, !reconnectSuppressed, hasCreatedAppRemote, appRemote === self.appRemote else { return }
        needsFreshTransport = true
        lastAppRemoteSampleUptime = nil
        if active {
            if playbackSource != .webAPI { controller.playbackDisconnected(message: nil) }
            reportPlaybackIssue("Spotify App Remote could not connect; current-song detection will keep trying",
                                error: error, source: .appRemote)
        }
    }
    func appRemote(_ appRemote: SPTAppRemote, didDisconnectWithError error: Error?) {
        guard !closed, !reconnectSuppressed, hasCreatedAppRemote, appRemote === self.appRemote else { return }
        connectionGeneration = UUID()
        needsFreshTransport = true
        refreshTimer?.invalidate()
        refreshTimer = nil
        playerState = nil
        playerObserver = nil
        lastAppRemoteSampleUptime = nil
        if active {
            if playbackSource != .webAPI {
                artworkURI = nil
                controller.playbackDisconnected(message: nil)
            }
            reportPlaybackIssue("Spotify App Remote disconnected; current-song detection will keep trying",
                                error: error, source: .appRemote)
        }
    }
    private func requestFreshState() {
        guard active, !closed, hasCreatedAppRemote, appRemote.isConnected else { return }
        let generation = connectionGeneration
        let revision = playerUpdateRevision
        let requestID = UUID()
        stateRequestID = requestID
        appRemote.playerAPI?.getPlayerState { [weak self] result, error in
            guard let self, !self.closed, self.active, self.connectionGeneration == generation,
                  self.playerUpdateRevision == revision, self.stateRequestID == requestID else { return }
            if let error {
                self.reportPlaybackIssue("Spotify App Remote could not read playback state",
                                         error: error, source: .appRemote)
                return
            }
            guard let state = result as? SPTAppRemotePlayerState else { return }
            self.receivePlayerState(state, generation: generation)
        }
    }
    private func installPlayerObserver(generation: UUID) {
        let observer = PlayerStateObserver(host: self, generation: generation)
        playerObserver = observer
        appRemote.playerAPI?.delegate = observer
    }
    fileprivate func receivePlayerState(_ playerState: SPTAppRemotePlayerState, generation: UUID) {
        guard active, !closed, connectionGeneration == generation, appRemote.isConnected else { return }
        // Any accepted App Remote sample invalidates an older Web API response.
        playerUpdateRevision &+= 1
        self.playerState = playerState
        lastAppRemoteSampleUptime = ProcessInfo.processInfo.systemUptime
        playbackSource = .appRemote
        reportedAppRemoteError = nil
        let track = playerState.track
        var actions: Int64 = 2 | 4 | 512 // Android's neutral pause/play/toggle capability bits.
        if playerState.playbackRestrictions.canSkipPrevious { actions |= 16 }
        if playerState.playbackRestrictions.canSkipNext { actions |= 32 }
        if playerState.playbackRestrictions.canSeek { actions |= 256 }
        controller.updatePlayback(uri: track.uri, title: track.name, artist: track.artist.name, album: track.album.name,
            durationMs: Int64(clamping: track.duration), positionMs: Int64(playerState.playbackPosition),
            speed: playerState.playbackSpeed, playing: !playerState.isPaused, actions: actions)
        if artworkURI != track.uri {
            webArtworkGeneration = UUID()
            webArtworkTask?.cancel()
            webArtworkTask = nil
            artworkURI = track.uri
            let uri = track.uri
            let generation = connectionGeneration
            appRemote.imageAPI?.fetchImage(forItem: track, with: CGSize(width: 1024, height: 1024)) { [weak self] image, error in
                guard let self, !self.closed, self.active, self.appRemote.isConnected,
                      self.connectionGeneration == generation, self.artworkURI == uri else { return }
                guard let data = (image as? UIImage)?.pngData() else { self.artworkURI = nil; return }
                self.controller.updateArtwork(data: data, forUri: uri)
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
private final class DetachedAppRemoteDelegate: NSObject, SPTAppRemoteDelegate, SPTAppRemotePlayerStateDelegate {
    func appRemoteDidEstablishConnection(_ appRemote: SPTAppRemote) {
        // A connection attempt may finish after its owner has deactivated.
        appRemote.disconnect()
    }
    func appRemote(_ appRemote: SPTAppRemote, didFailConnectionAttemptWithError error: Error?) {}
    func appRemote(_ appRemote: SPTAppRemote, didDisconnectWithError error: Error?) {}
    func playerStateDidChange(_ playerState: SPTAppRemotePlayerState) {}
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
    func ensureLyricsAuthorization() { host?.ensureLyricsAuthorization() }
    func cancelLyricsAuthorization() { host?.cancelLyricsAuthorization() }
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
