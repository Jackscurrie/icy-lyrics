import AuthenticationServices
import CryptoKit
import Security
import UIKit

enum SpotifyCredentialPurpose: String, Sendable {
    case playback, lyrics
    var scopes: Set<String> {
        self == .playback ? ["app-remote-control"] : ["user-read-currently-playing"]
    }
}

struct SpotifyCredentials: Codable, Sendable {
    var accessToken: String
    var refreshToken: String?
    var expiresAt: Date
    var scopes: Set<String>
}

enum SpotifyAuthError: LocalizedError {
    case unavailable, cancelled, invalidCallback, expired, invalidResponse, http(Int), keychain(OSStatus)
    var errorDescription: String? {
        switch self {
        case .unavailable: return "Spotify client ID is not configured."
        case .cancelled: return "Spotify connection was cancelled."
        case .invalidCallback: return "Spotify returned an invalid authorization callback."
        case .expired: return "Spotify authorization expired. Please connect again."
        case .invalidResponse: return "Spotify returned an invalid authorization response."
        case .http(let code): return "Spotify authorization returned HTTP \(code)."
        case .keychain: return "Spotify credentials could not be accessed securely."
        }
    }
}

@MainActor
protocol SpotifyCredentialStore: AnyObject {
    func read(_ purpose: SpotifyCredentialPurpose) throws -> SpotifyCredentials?
    func write(_ value: SpotifyCredentials, purpose: SpotifyCredentialPurpose) throws
    func clear(_ purpose: SpotifyCredentialPurpose) throws
}

@MainActor
final class SpotifyKeychain: SpotifyCredentialStore {
    private var service: String { (Bundle.main.bundleIdentifier ?? "com.icy.lyrics.ios") + ".spotify" }
    private func query(_ purpose: SpotifyCredentialPurpose) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: purpose.rawValue, kSecAttrSynchronizable as String: false]
    }
    func read(_ purpose: SpotifyCredentialPurpose) throws -> SpotifyCredentials? {
        var search = query(purpose)
        search[kSecReturnData as String] = true
        search[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(search as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw SpotifyAuthError.keychain(status) }
        return try JSONDecoder().decode(SpotifyCredentials.self, from: data)
    }
    func write(_ value: SpotifyCredentials, purpose: SpotifyCredentialPurpose) throws {
        let data = try JSONEncoder().encode(value)
        let values: [String: Any] = [kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        var status = SecItemUpdate(query(purpose) as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound {
            var insert = query(purpose)
            values.forEach { insert[$0.key] = $0.value }
            status = SecItemAdd(insert as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw SpotifyAuthError.keychain(status) }
    }
    func clear(_ purpose: SpotifyCredentialPurpose) throws {
        let status = SecItemDelete(query(purpose) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw SpotifyAuthError.keychain(status) }
    }
}

private final class RejectRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

@MainActor
protocol SpotifyAuthorizationBrowser: AnyObject {
    func start(presentationProvider: ASWebAuthenticationPresentationContextProviding) -> Bool
    func cancel()
}

@MainActor
private final class SystemSpotifyAuthorizationBrowser: SpotifyAuthorizationBrowser {
    private let session: ASWebAuthenticationSession
    init(url: URL, scheme: String?, completion: @escaping (URL?, Error?) -> Void) {
        session = ASWebAuthenticationSession(url: url, callbackURLScheme: scheme, completionHandler: completion)
    }
    func start(presentationProvider: ASWebAuthenticationPresentationContextProviding) -> Bool {
        session.presentationContextProvider = presentationProvider
        return session.start()
    }
    func cancel() { session.cancel() }
}

/// Main-actor ownership makes each check-and-write atomic across suspension points.
/// Login and credential revision identities prevent cancelled requests from
/// completing newer logins or restoring credentials after disconnect.
@MainActor
final class SpotifyAuthorization: NSObject, ASWebAuthenticationPresentationContextProviding {
    typealias TokenTransport = @MainActor (URLRequest) async throws -> (Data, URLResponse)
    typealias BrowserFactory = @MainActor (URL, String?, @escaping (URL?, Error?) -> Void) -> SpotifyAuthorizationBrowser

    private struct Pending: Sendable {
        let state: String
        let verifier: String
        let purpose: SpotifyCredentialPurpose
        let startedAt: Date
        let generation: UUID
    }
    private struct TokenResponse: Decodable {
        let access_token: String
        let token_type: String
        let expires_in: Int
        let refresh_token: String?
        let scope: String?
    }
    private struct RefreshFlight {
        let id: UUID
        let generation: UUID
        let task: Task<SpotifyCredentials?, Error>
    }
    let clientID: String
    let callbackURL: URL
    private let keychain: SpotifyCredentialStore
    private let transport: TokenTransport
    private let makeBrowser: BrowserFactory
    private let now: () -> Date
    private var pending: Pending?
    private var authorizationGeneration: UUID?
    private var authorizationPurpose: SpotifyCredentialPurpose?
    private var exchangeTask: Task<Void, Never>?
    private var browser: SpotifyAuthorizationBrowser?
    private var completion: ((Result<SpotifyCredentialPurpose, Error>) -> Void)?
    private var credentialGenerations: [SpotifyCredentialPurpose: UUID] = [:]
    private var refreshTasks: [SpotifyCredentialPurpose: RefreshFlight] = [:]
    private var revokedPurposes = Set<SpotifyCredentialPurpose>()
    weak var window: UIWindow?
    var isAuthorizing: Bool { authorizationGeneration != nil }
    var authorizingPurpose: SpotifyCredentialPurpose? { authorizationPurpose }
    /// Stored authorization keeps the existing Disconnect action reachable even
    /// when an expired token needs refresh or the account is temporarily offline.
    func hasStoredAuthorization(_ purpose: SpotifyCredentialPurpose) throws -> Bool {
        try keychain.read(purpose) != nil
    }

    init(clientID: String, callbackURL: URL,
         credentialStore: SpotifyCredentialStore? = nil,
         transport: TokenTransport? = nil,
         now: @escaping () -> Date = Date.init,
         browserFactory: BrowserFactory? = nil) {
        self.clientID = clientID
        self.callbackURL = callbackURL
        self.keychain = credentialStore ?? SpotifyKeychain()
        self.transport = transport ?? Self.urlSessionTransport()
        self.now = now
        self.makeBrowser = browserFactory ?? { url, scheme, completion in
            SystemSpotifyAuthorizationBrowser(url: url, scheme: scheme, completion: completion)
        }
        super.init()
    }

    func begin(_ purpose: SpotifyCredentialPurpose, completion: @escaping (Result<SpotifyCredentialPurpose, Error>) -> Void) {
        cancel()
        // A cancellation completion may synchronously start a newer request.
        guard authorizationGeneration == nil else { completion(.failure(SpotifyAuthError.cancelled)); return }
        guard !clientID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            completion(.failure(SpotifyAuthError.unavailable)); return
        }
        do {
            let request = Pending(state: try randomToken(32), verifier: try randomToken(64),
                                  purpose: purpose, startedAt: now(), generation: UUID())
            invalidateRefresh(purpose)
            pending = request
            authorizationGeneration = request.generation
            authorizationPurpose = request.purpose
            self.completion = completion
            var url = URLComponents(string: "https://accounts.spotify.com/authorize")!
            url.queryItems = [
                URLQueryItem(name: "client_id", value: clientID),
                URLQueryItem(name: "response_type", value: "code"),
                URLQueryItem(name: "redirect_uri", value: callbackURL.absoluteString),
                URLQueryItem(name: "scope", value: purpose.scopes.sorted().joined(separator: " ")),
                URLQueryItem(name: "state", value: request.state),
                URLQueryItem(name: "code_challenge_method", value: "S256"),
                URLQueryItem(name: "code_challenge", value: Self.codeChallenge(for: request.verifier))
            ]
            let session = makeBrowser(url.url!, callbackURL.scheme) { [weak self] callback, error in
                Task { @MainActor in
                    guard let self, self.pending?.generation == request.generation,
                          self.authorizationGeneration == request.generation else { return }
                    if let callback { self.handle(callback) }
                    else { self.finish(.failure(error ?? SpotifyAuthError.cancelled), generation: request.generation) }
                }
            }
            browser = session
            if !session.start(presentationProvider: self) {
                finish(.failure(SpotifyAuthError.cancelled), generation: request.generation)
            }
        } catch { completion(.failure(error)) }
    }

    /// Scene delivery can contain callbacks owned by another Spotify SDK flow.
    /// Only consume a scene URL when it carries this pending PKCE request's
    /// unguessable state. The ASWebAuthenticationSession completion still uses
    /// `handle` below so malformed browser callbacks fail closed.
    @discardableResult
    func handleSceneCallback(_ url: URL) -> Bool {
        guard let request = pending, authorizationGeneration == request.generation,
              Self.matchesRedirectLocation(url, callbackURL), url.fragment == nil,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
        let states = components.queryItems?.filter { $0.name == "state" } ?? []
        guard states.count == 1, let state = states[0].value,
              Self.constantTimeEqual(state, request.state) else { return false }
        handle(url)
        return true
    }

    func handle(_ url: URL) {
        guard let request = pending, authorizationGeneration == request.generation else { return }
        guard Self.matchesRedirectLocation(url, callbackURL), url.fragment == nil,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            finish(.failure(SpotifyAuthError.invalidCallback), generation: request.generation); return
        }
        let items = components.queryItems ?? []
        guard items.filter({ $0.name == "state" }).count == 1,
              let state = items.first(where: { $0.name == "state" })?.value,
              Self.constantTimeEqual(state, request.state) else {
            finish(.failure(SpotifyAuthError.invalidCallback), generation: request.generation); return
        }
        guard (0.0...600.0).contains(now().timeIntervalSince(request.startedAt)) else {
            finish(.failure(SpotifyAuthError.expired), generation: request.generation); return
        }
        let errors = items.filter { $0.name == "error" }
        let codes = items.filter { $0.name == "code" }
        if errors.count == 1 && codes.isEmpty {
            finish(.failure(SpotifyAuthError.cancelled), generation: request.generation); return
        }
        guard errors.isEmpty, codes.count == 1, let code = codes.first?.value, !code.isEmpty else {
            finish(.failure(SpotifyAuthError.invalidCallback), generation: request.generation); return
        }
        // Consume the callback before exchange. Keep the generation until exchange
        // finishes so cancellation remains effective during the network request.
        pending = nil
        let previousBrowser = browser
        browser = nil
        previousBrowser?.cancel()
        exchangeTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let credentials = try await self.exchange([
                    "client_id": self.clientID, "grant_type": "authorization_code", "code": code,
                    "redirect_uri": self.callbackURL.absoluteString, "code_verifier": request.verifier
                ], previous: nil, purpose: request.purpose)
                try Task.checkCancellation()
                guard self.authorizationGeneration == request.generation else { return }
                // A refresh of the previous account could have started while the
                // browser was open; it must not overwrite this new account.
                self.invalidateRefresh(request.purpose)
                try self.keychain.write(credentials, purpose: request.purpose)
                self.revokedPurposes.remove(request.purpose)
                self.finish(.success(request.purpose), generation: request.generation)
            } catch {
                guard self.authorizationGeneration == request.generation else { return }
                self.finish(.failure(error), generation: request.generation)
            }
        }
    }

    func token(_ purpose: SpotifyCredentialPurpose, rejected: String? = nil) async throws -> String? {
        try Task.checkCancellation()
        guard !revokedPurposes.contains(purpose) else { return nil }
        guard let existing = try keychain.read(purpose), existing.scopes == purpose.scopes else { return nil }
        // Join forced refreshes even while the previously rejected token's expiry
        // is valid. Cancelling one waiter does not cancel the shared refresh.
        if let flight = refreshTasks[purpose] {
            let value = try await flight.task.value
            try checkCredentialGeneration(purpose, flight.generation)
            return value?.accessToken
        }
        if existing.expiresAt.timeIntervalSince(now()) > 60 && existing.accessToken != rejected { return existing.accessToken }
        let generation = credentialGeneration(purpose)
        let id = UUID()
        let task = Task<SpotifyCredentials?, Error> { @MainActor [weak self] in
            guard let self else { throw CancellationError() }
            return try await self.refresh(existing, purpose: purpose, generation: generation, rejected: rejected)
        }
        refreshTasks[purpose] = RefreshFlight(id: id, generation: generation, task: task)
        defer {
            // An old waiter must not erase a new flight created after reconnect.
            if refreshTasks[purpose]?.id == id { refreshTasks[purpose] = nil }
        }
        let value = try await task.value
        try checkCredentialGeneration(purpose, generation)
        return value?.accessToken
    }

    func disconnect() throws {
        // Fail closed for this process even if a Keychain deletion fails. The
        // stored record remains visible to Disconnect so the user can retry.
        revokedPurposes.formUnion([.playback, .lyrics])
        invalidateRefresh(.playback)
        invalidateRefresh(.lyrics)
        // Attempt both deletions even if one keychain operation fails.
        var firstError: Error?
        for purpose in [SpotifyCredentialPurpose.playback, .lyrics] {
            do { try keychain.clear(purpose); revokedPurposes.remove(purpose) }
            catch { if firstError == nil { firstError = error } }
        }
        cancel()
        if let firstError { throw firstError }
    }
    /// Internal targeted revocation; the app's Disconnect control uses the full
    /// account operation above so neither stored purpose becomes unreachable.
    func disconnect(_ purpose: SpotifyCredentialPurpose) throws {
        revokedPurposes.insert(purpose)
        invalidateRefresh(purpose)
        defer { if authorizationPurpose == purpose { cancel() } }
        try keychain.clear(purpose)
        revokedPurposes.remove(purpose)
    }
    func cancel() {
        let callback = completion
        let session = browser
        let task = exchangeTask
        pending = nil
        authorizationGeneration = nil
        authorizationPurpose = nil
        browser = nil
        completion = nil
        exchangeTask = nil
        task?.cancel()
        session?.cancel()
        callback?(.failure(SpotifyAuthError.cancelled))
    }
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor { window ?? ASPresentationAnchor() }

    private func credentialGeneration(_ purpose: SpotifyCredentialPurpose) -> UUID {
        if let value = credentialGenerations[purpose] { return value }
        let value = UUID()
        credentialGenerations[purpose] = value
        return value
    }

    private func invalidateRefresh(_ purpose: SpotifyCredentialPurpose) {
        credentialGenerations[purpose] = UUID()
        refreshTasks.removeValue(forKey: purpose)?.task.cancel()
    }

    private func checkCredentialGeneration(_ purpose: SpotifyCredentialPurpose, _ generation: UUID) throws {
        try Task.checkCancellation()
        guard credentialGenerations[purpose] == generation else { throw CancellationError() }
    }

    private func refresh(_ existing: SpotifyCredentials, purpose: SpotifyCredentialPurpose,
                         generation: UUID, rejected: String?) async throws -> SpotifyCredentials? {
        try checkCredentialGeneration(purpose, generation)
        guard let refreshToken = existing.refreshToken, !refreshToken.isEmpty else {
            try keychain.clear(purpose)
            return nil
        }
        do {
            let value = try await exchange([
                "client_id": clientID, "grant_type": "refresh_token", "refresh_token": refreshToken,
            ], previous: existing, purpose: purpose)
            try checkCredentialGeneration(purpose, generation)
            try keychain.write(value, purpose: purpose)
            return value
        } catch SpotifyAuthError.http(let status) where (400..<500).contains(status) && status != 429 {
            try checkCredentialGeneration(purpose, generation)
            try keychain.clear(purpose)
            throw SpotifyAuthError.http(status)
        } catch {
            // Cancellation or a newer account forbids even a failure-path write.
            try checkCredentialGeneration(purpose, generation)
            if rejected == existing.accessToken {
                var expired = existing
                expired.expiresAt = .distantPast
                try keychain.write(expired, purpose: purpose)
            }
            throw error
        }
    }

    private func exchange(_ fields: [String: String], previous: SpotifyCredentials?, purpose: SpotifyCredentialPurpose) async throws -> SpotifyCredentials {
        var request = URLRequest(url: URL(string: "https://accounts.spotify.com/api/token")!)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var form = URLComponents()
        form.queryItems = fields.sorted(by: { $0.key < $1.key }).map { URLQueryItem(name: $0.key, value: $0.value) }
        request.httpBody = form.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B").data(using: .utf8)
        let (data, response) = try await transport(request)
        try Task.checkCancellation()
        guard let response = response as? HTTPURLResponse else { throw SpotifyAuthError.invalidResponse }
        guard (200..<300).contains(response.statusCode) else { throw SpotifyAuthError.http(response.statusCode) }
        guard data.count <= 512 * 1024 else { throw SpotifyAuthError.invalidResponse }
        let decoded = try JSONDecoder().decode(TokenResponse.self, from: data)
        guard decoded.token_type.lowercased() == "bearer", !decoded.access_token.isEmpty,
              decoded.expires_in > 0 else { throw SpotifyAuthError.invalidResponse }
        let scopes = decoded.scope.map { Set($0.split(separator: " ").map(String.init)) } ?? previous?.scopes ?? purpose.scopes
        guard scopes == purpose.scopes else { throw SpotifyAuthError.invalidResponse }
        return SpotifyCredentials(accessToken: decoded.access_token, refreshToken: decoded.refresh_token ?? previous?.refreshToken,
            expiresAt: now().addingTimeInterval(TimeInterval(min(decoded.expires_in, 86400))), scopes: scopes)
    }
    private func finish(_ result: Result<SpotifyCredentialPurpose, Error>, generation: UUID) {
        guard authorizationGeneration == generation else { return }
        let callback = completion
        let session = browser
        pending = nil
        authorizationGeneration = nil
        authorizationPurpose = nil
        completion = nil
        browser = nil
        exchangeTask = nil
        session?.cancel()
        callback?(result)
    }
    private static func urlSessionTransport() -> TokenTransport {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        let network = URLSession(configuration: configuration, delegate: RejectRedirects(), delegateQueue: nil)
        return { request in try await network.data(for: request) }
    }
    private func randomToken(_ size: Int) throws -> String {
        var bytes = [UInt8](repeating: 0, count: size)
        let status = SecRandomCopyBytes(kSecRandomDefault, size, &bytes)
        guard status == errSecSuccess else { throw SpotifyAuthError.keychain(status) }
        return Self.base64url(Data(bytes))
    }
    static func codeChallenge(for verifier: String) -> String {
        base64url(Data(SHA256.hash(data: Data(verifier.utf8))))
    }
    static func base64url(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    static func constantTimeEqual(_ a: String, _ b: String) -> Bool {
        let left = Array(a.utf8), right = Array(b.utf8)
        guard left.count == right.count else { return false }
        return zip(left, right).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }
    /// Foundation and callback transports can represent an authority-only URI
    /// as either an empty path or `/`. They identify the same root callback;
    /// every non-root path, authority component and port remains exact.
    static func matchesRedirectLocation(_ actual: URL, _ expected: URL) -> Bool {
        guard let actualScheme = actual.scheme, let expectedScheme = expected.scheme,
              actualScheme.caseInsensitiveCompare(expectedScheme) == .orderedSame,
              sameHost(actual.host, expected.host),
              actual.port == expected.port,
              actual.user == nil, actual.password == nil,
              expected.user == nil, expected.password == nil else { return false }
        let actualPath = actual.path.isEmpty ? "/" : actual.path
        let expectedPath = expected.path.isEmpty ? "/" : expected.path
        return actualPath == expectedPath
    }

    private static func sameHost(_ actual: String?, _ expected: String?) -> Bool {
        switch (actual, expected) {
        case let (actual?, expected?):
            return actual.caseInsensitiveCompare(expected) == .orderedSame
        case (nil, nil):
            return true
        default:
            return false
        }
    }
}
