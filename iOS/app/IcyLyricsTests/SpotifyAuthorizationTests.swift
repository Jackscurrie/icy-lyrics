import AuthenticationServices
import XCTest
@testable import IcyLyrics

@MainActor
final class SpotifyAuthorizationTests: XCTestCase {
    private let callback = URL(string: "com.icy.lyrics.ios://spotify-callback")!

    func testPKCEMatchesRFC7636VectorAndUsesURLSafeEncoding() {
        XCTAssertEqual(SpotifyAuthorization.codeChallenge(for: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
                       "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        XCTAssertEqual(SpotifyAuthorization.base64url(Data([0xfb, 0xff])), "-_8")
        XCTAssertTrue(SpotifyAuthorization.constantTimeEqual("same", "same"))
        XCTAssertFalse(SpotifyAuthorization.constantTimeEqual("same", "sane"))
        XCTAssertFalse(SpotifyAuthorization.constantTimeEqual("same", "same-longer"))
    }

    func testLyricsNeverReceivesAControlCapableStoredToken() async throws {
        let store = MemoryCredentials()
        store.values[.lyrics] = credentials(scopes: ["user-read-currently-playing", "app-remote-control"])
        let network = TokenProbe()
        let auth = makeAuth(store, network)
        let token = try await auth.token(.lyrics)
        XCTAssertNil(token)
        XCTAssertTrue(network.requests.isEmpty)
    }

    func testLyricsDisconnectPreservesPlaybackAuthorization() async throws {
        let store = MemoryCredentials()
        store.values[.playback] = credentials(expiresAt: Date().addingTimeInterval(3600), scopes: ["app-remote-control"])
        store.values[.lyrics] = credentials()
        let network = TokenProbe()
        let auth = makeAuth(store, network)
        try auth.disconnect(.lyrics)
        XCTAssertNil(store.values[.lyrics])
        let playbackToken = try await auth.token(.playback)
        XCTAssertEqual(playbackToken, "old-access")
        XCTAssertTrue(network.requests.isEmpty)
    }

    func testLyricsDisconnectDoesNotCancelPendingPlaybackLogin() throws {
        let store = MemoryCredentials()
        let network = TokenProbe()
        let browsers = BrowserProbe()
        let auth = makeAuth(store, network, browsers)
        var completions = 0
        auth.begin(.playback) { _ in completions += 1 }
        try auth.disconnect(.lyrics)
        XCTAssertTrue(auth.isAuthorizing)
        XCTAssertEqual(completions, 0)
        auth.cancel()
        XCTAssertEqual(completions, 1)
    }

    func testPlaybackOnlyStoredAuthorizationRemainsAvailableWithoutNetworkOrBrowser() throws {
        let store = MemoryCredentials()
        store.values[.playback] = credentials(scopes: ["app-remote-control"])
        let network = TokenProbe()
        let browsers = BrowserProbe()
        let auth = makeAuth(store, network, browsers)
        XCTAssertTrue(try auth.hasStoredAuthorization(.playback))
        XCTAssertFalse(try auth.hasStoredAuthorization(.lyrics))
        XCTAssertTrue(network.requests.isEmpty)
        XCTAssertTrue(browsers.urls.isEmpty)
    }

    func testFullDisconnectCancelsBrowserAndRejectsLateCallbackForBothStoredPurposes() async throws {
        let store = MemoryCredentials()
        store.values[.playback] = credentials(scopes: ["app-remote-control"])
        store.values[.lyrics] = credentials()
        let network = TokenProbe()
        let browsers = BrowserProbe()
        let auth = makeAuth(store, network, browsers)
        var results = [Result<SpotifyCredentialPurpose, Error>]()
        auth.begin(.playback) { results.append($0) }
        let callback = browsers.callbackURL(self.callback)
        try auth.disconnect()
        auth.handle(callback)
        browsers.sessions[0].completion(callback, nil)
        await yieldToMainActor()
        XCTAssertFalse(auth.isAuthorizing)
        XCTAssertTrue(store.values.isEmpty)
        XCTAssertEqual(Set(store.clearAttempts), [.playback, .lyrics])
        XCTAssertTrue(network.requests.isEmpty)
        XCTAssertEqual(results.count, 1)
        XCTAssertThrowsError(try results[0].get())
    }

    func testFullDisconnectCancelsBothRefreshesAndTheirLateResultsCannotRestoreEitherPurpose() async throws {
        let store = MemoryCredentials()
        store.values[.playback] = credentials(scopes: ["app-remote-control"])
        store.values[.lyrics] = credentials()
        let network = TokenProbe()
        let playbackStarted = expectation(description: "playback refresh")
        let lyricsStarted = expectation(description: "lyrics refresh")
        network.onRequest = { if $0 == 1 { playbackStarted.fulfill() } else { lyricsStarted.fulfill() } }
        let auth = makeAuth(store, network)
        let playback = Task { try await auth.token(.playback) }
        await fulfillment(of: [playbackStarted], timeout: 2)
        let lyrics = Task { try await auth.token(.lyrics) }
        await fulfillment(of: [lyricsStarted], timeout: 2)
        try auth.disconnect()
        network.respond(0, token: "late-playback", scope: "app-remote-control")
        network.respond(1, token: "late-lyrics", scope: "user-read-currently-playing")
        for task in [playback, lyrics] {
            do { _ = try await task.value; XCTFail("Revoked refresh returned a token") }
            catch { XCTAssertTrue(error is CancellationError) }
        }
        XCTAssertTrue(store.values.isEmpty)
        XCTAssertFalse(try auth.hasStoredAuthorization(.playback))
        XCTAssertFalse(try auth.hasStoredAuthorization(.lyrics))
    }

    func testFullDisconnectAttemptsBothDeletionsAndFailedPurposeCannotSupplyTokenBeforeRetry() async throws {
        let store = MemoryCredentials()
        store.values[.playback] = credentials(expiresAt: Date().addingTimeInterval(3600), scopes: ["app-remote-control"])
        store.values[.lyrics] = credentials()
        store.clearFailures = [.playback]
        let network = TokenProbe()
        let auth = makeAuth(store, network)
        XCTAssertThrowsError(try auth.disconnect())
        XCTAssertEqual(Set(store.clearAttempts), [.playback, .lyrics])
        XCTAssertTrue(try auth.hasStoredAuthorization(.playback)) // Keep Disconnect reachable for retry.
        XCTAssertFalse(try auth.hasStoredAuthorization(.lyrics))
        let token = try await auth.token(.playback)
        XCTAssertNil(token)
        XCTAssertTrue(network.requests.isEmpty)
        store.clearFailures = []
        try auth.disconnect()
        XCTAssertFalse(try auth.hasStoredAuthorization(.playback))
    }

    func testRefreshIsSingleFlightAndRetainsAnOmittedRefreshToken() async throws {
        let store = MemoryCredentials()
        store.values[.lyrics] = credentials()
        let network = TokenProbe()
        let started = expectation(description: "refresh started")
        network.onRequest = { if $0 == 1 { started.fulfill() } }
        let auth = makeAuth(store, network)
        let first = Task { try await auth.token(.lyrics) }
        let second = Task { try await auth.token(.lyrics) }
        await fulfillment(of: [started], timeout: 2)
        await yieldToMainActor()
        XCTAssertEqual(network.requests.count, 1)
        network.respond(0, token: "fresh", scope: "user-read-currently-playing")
        let firstValue = try await first.value
        let secondValue = try await second.value
        XCTAssertEqual(firstValue, "fresh")
        XCTAssertEqual(secondValue, "fresh")
        XCTAssertEqual(store.values[.lyrics]?.refreshToken, "original-refresh")
    }

    func testForcedRefreshIsJoinedInsteadOfReturningRejectedCachedToken() async throws {
        let store = MemoryCredentials()
        store.values[.lyrics] = credentials(expiresAt: Date().addingTimeInterval(3600))
        let network = TokenProbe()
        let started = expectation(description: "forced refresh started")
        network.onRequest = { if $0 == 1 { started.fulfill() } }
        let auth = makeAuth(store, network)
        let first = Task { try await auth.token(.lyrics, rejected: "old-access") }
        await fulfillment(of: [started], timeout: 2)
        var secondCompleted = false
        let second = Task {
            let value = try await auth.token(.lyrics)
            secondCompleted = true
            return value
        }
        await yieldToMainActor()
        XCTAssertFalse(secondCompleted)
        XCTAssertEqual(network.requests.count, 1)
        network.respond(0, token: "replacement", scope: "user-read-currently-playing")
        let firstValue = try await first.value
        let secondValue = try await second.value
        XCTAssertEqual(firstValue, "replacement")
        XCTAssertEqual(secondValue, "replacement")
    }

    func testDisconnectPreventsAnUncooperativeRefreshFromRestoringCredentials() async throws {
        let store = MemoryCredentials()
        store.values[.lyrics] = credentials()
        let network = TokenProbe()
        let started = expectation(description: "refresh started")
        network.onRequest = { if $0 == 1 { started.fulfill() } }
        let auth = makeAuth(store, network)
        let refresh = Task { try await auth.token(.lyrics, rejected: "old-access") }
        await fulfillment(of: [started], timeout: 2)
        try auth.disconnect()
        // This transport deliberately completes even though its task was cancelled.
        network.respond(0, token: "must-not-return", scope: "user-read-currently-playing")
        do { _ = try await refresh.value; XCTFail("Disconnected refresh returned a token") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertTrue(store.values.isEmpty)
    }

    func testOldRefreshCleanupCannotEraseANewerFlight() async throws {
        let store = MemoryCredentials()
        store.values[.lyrics] = credentials()
        let network = TokenProbe()
        let firstStarted = expectation(description: "first refresh")
        let secondStarted = expectation(description: "replacement refresh")
        network.onRequest = { count in
            if count == 1 { firstStarted.fulfill() }
            if count == 2 { secondStarted.fulfill() }
        }
        let auth = makeAuth(store, network)
        let old = Task { try await auth.token(.lyrics) }
        await fulfillment(of: [firstStarted], timeout: 2)
        try auth.disconnect()
        store.values[.lyrics] = credentials()
        let replacement = Task { try await auth.token(.lyrics) }
        await fulfillment(of: [secondStarted], timeout: 2)
        network.respond(0, token: "stale", scope: "user-read-currently-playing")
        do { _ = try await old.value; XCTFail("Old flight returned after disconnect") } catch {}
        let joiner = Task { try await auth.token(.lyrics) }
        await yieldToMainActor()
        XCTAssertEqual(network.requests.count, 2)
        network.respond(1, token: "new-account", scope: "user-read-currently-playing")
        let replacementValue = try await replacement.value
        let joinedValue = try await joiner.value
        XCTAssertEqual(replacementValue, "new-account")
        XCTAssertEqual(joinedValue, "new-account")
    }

    func testCancelledCodeExchangeCannotCompleteOrPersistOverANewerLogin() async throws {
        let store = MemoryCredentials()
        let network = TokenProbe()
        let browsers = BrowserProbe()
        let oldStarted = expectation(description: "old code exchange")
        let newStarted = expectation(description: "new code exchange")
        let newFinished = expectation(description: "new login finished")
        network.onRequest = { count in
            if count == 1 { oldStarted.fulfill() }
            if count == 2 { newStarted.fulfill() }
        }
        let auth = makeAuth(store, network, browsers)
        var oldResults = [Result<SpotifyCredentialPurpose, Error>]()
        var newResults = [Result<SpotifyCredentialPurpose, Error>]()
        auth.begin(.playback) { oldResults.append($0) }
        let oldCallback = browsers.callbackURL(callback)
        auth.handle(oldCallback)
        auth.handle(oldCallback) // Callback replay must not exchange twice.
        await fulfillment(of: [oldStarted], timeout: 2)
        auth.cancel()
        auth.begin(.lyrics) { result in newResults.append(result); newFinished.fulfill() }
        auth.handle(browsers.callbackURL(callback))
        await fulfillment(of: [newStarted], timeout: 2)
        XCTAssertEqual(network.requests.count, 2)
        network.respond(0, token: "stale-playback", scope: "app-remote-control")
        network.respond(1, token: "new-lyrics", scope: "user-read-currently-playing")
        await fulfillment(of: [newFinished], timeout: 2)
        await yieldToMainActor()
        XCTAssertEqual(oldResults.count, 1)
        XCTAssertThrowsError(try oldResults[0].get())
        XCTAssertEqual(newResults.count, 1)
        XCTAssertEqual(try newResults[0].get(), .lyrics)
        XCTAssertNil(store.values[.playback])
        XCTAssertEqual(store.values[.lyrics]?.accessToken, "new-lyrics")
    }

    func testReturnedScopeEscalationIsRejectedBeforePersistence() async throws {
        let store = MemoryCredentials()
        store.values[.lyrics] = credentials()
        let network = TokenProbe()
        let started = expectation(description: "refresh")
        network.onRequest = { if $0 == 1 { started.fulfill() } }
        let auth = makeAuth(store, network)
        let result = Task { try await auth.token(.lyrics) }
        await fulfillment(of: [started], timeout: 2)
        network.respond(0, token: "control-capable", scope: "user-read-currently-playing app-remote-control")
        do { _ = try await result.value; XCTFail("Escalated scope accepted") } catch {}
        XCTAssertEqual(store.values[.lyrics]?.accessToken, "old-access")
    }

    private func credentials(expiresAt: Date = .distantPast,
                             scopes: Set<String> = ["user-read-currently-playing"]) -> SpotifyCredentials {
        SpotifyCredentials(accessToken: "old-access", refreshToken: "original-refresh",
                           expiresAt: expiresAt, scopes: scopes)
    }
    private func makeAuth(_ store: MemoryCredentials, _ network: TokenProbe,
                          _ browserProbe: BrowserProbe? = nil) -> SpotifyAuthorization {
        let browsers = browserProbe ?? BrowserProbe()
        return SpotifyAuthorization(clientID: "test-client", callbackURL: callback, credentialStore: store,
            transport: { try await network.request($0) },
            browserFactory: { url, _, completion in browsers.make(url, completion) })
    }
    private func yieldToMainActor() async {
        for _ in 0..<20 { await Task.yield() }
    }
}

@MainActor
private final class MemoryCredentials: SpotifyCredentialStore {
    var values: [SpotifyCredentialPurpose: SpotifyCredentials] = [:]
    var clearFailures = Set<SpotifyCredentialPurpose>()
    var clearAttempts = [SpotifyCredentialPurpose]()
    func read(_ purpose: SpotifyCredentialPurpose) throws -> SpotifyCredentials? { values[purpose] }
    func write(_ value: SpotifyCredentials, purpose: SpotifyCredentialPurpose) throws { values[purpose] = value }
    func clear(_ purpose: SpotifyCredentialPurpose) throws {
        clearAttempts.append(purpose)
        if clearFailures.contains(purpose) { throw SpotifyAuthError.keychain(-1) }
        values[purpose] = nil
    }
}

@MainActor
private final class TokenProbe {
    var requests = [URLRequest]()
    var onRequest: ((Int) -> Void)?
    private var continuations = [Int: CheckedContinuation<(Data, URLResponse), Error>]()
    func request(_ request: URLRequest) async throws -> (Data, URLResponse) {
        try await withCheckedThrowingContinuation { continuation in
            let index = requests.count
            requests.append(request)
            continuations[index] = continuation
            onRequest?(requests.count)
        }
    }
    func respond(_ index: Int, token: String, scope: String) {
        let data = try! JSONSerialization.data(withJSONObject: [
            "access_token": token, "token_type": "Bearer", "expires_in": 3600, "scope": scope,
        ])
        let response = HTTPURLResponse(url: URL(string: "https://accounts.spotify.com/api/token")!,
                                       statusCode: 200, httpVersion: nil, headerFields: nil)!
        continuations.removeValue(forKey: index)?.resume(returning: (data, response))
    }
}

@MainActor
private final class BrowserProbe {
    var urls = [URL]()
    var sessions = [TestBrowser]()
    func make(_ url: URL, _ completion: @escaping (URL?, Error?) -> Void) -> SpotifyAuthorizationBrowser {
        urls.append(url)
        let browser = TestBrowser(completion)
        sessions.append(browser)
        return browser
    }
    func callbackURL(_ base: URL) -> URL {
        let state = URLComponents(url: urls.last!, resolvingAgainstBaseURL: false)!
            .queryItems!.first { $0.name == "state" }!.value!
        var result = URLComponents(url: base, resolvingAgainstBaseURL: false)!
        result.queryItems = [URLQueryItem(name: "state", value: state), URLQueryItem(name: "code", value: "code")]
        return result.url!
    }
}

@MainActor
private final class TestBrowser: SpotifyAuthorizationBrowser {
    let completion: (URL?, Error?) -> Void
    init(_ completion: @escaping (URL?, Error?) -> Void) { self.completion = completion }
    func start(presentationProvider: ASWebAuthenticationPresentationContextProviding) -> Bool { true }
    func cancel() {}
}
