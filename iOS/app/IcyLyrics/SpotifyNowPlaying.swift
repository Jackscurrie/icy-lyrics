import Foundation

typealias SpotifyNowPlayingTransport = (URLRequest) async throws -> (Data, URLResponse)

struct SpotifyNowPlayingItem: Equatable, Sendable {
    enum Kind: String, Equatable, Sendable {
        case track
        case episode
    }

    let kind: Kind
    let uri: String
    let title: String
    let artist: String
    let album: String
    let durationMs: Int64
    let positionMs: Int64
    let isPlaying: Bool
    let artworkURL: URL?
}

enum SpotifyNowPlayingError: Error, Equatable, LocalizedError {
    case invalidAccessToken
    case invalidResponse
    case responseTooLarge
    case unauthorized
    case forbidden
    case rateLimited(retryAfterSeconds: Int?)
    case http(Int)

    var errorDescription: String? {
        switch self {
        case .invalidAccessToken:
            return "Spotify playback authorization is unavailable."
        case .invalidResponse:
            return "Spotify returned an invalid currently-playing response."
        case .responseTooLarge:
            return "Spotify returned an oversized currently-playing response."
        case .unauthorized:
            return "Spotify playback authorization expired."
        case .forbidden:
            return "Spotify did not allow current-playback access."
        case .rateLimited:
            return "Spotify temporarily limited current-playback requests."
        case .http(let status):
            return "Spotify current playback returned HTTP \(status)."
        }
    }
}

final class SpotifyNowPlayingClient {
    static let endpoint = URL(string:
        "https://api.spotify.com/v1/me/player/currently-playing?additional_types=track%2Cepisode")!
    static let maximumResponseBytes = 512 * 1024

    private let transport: SpotifyNowPlayingTransport

    init(transport: @escaping SpotifyNowPlayingTransport) {
        self.transport = transport
    }

    convenience init() {
        self.init(transport: Self.urlSessionTransport())
    }

    func fetch(accessToken: String) async throws -> SpotifyNowPlayingItem? {
        let request = try Self.request(accessToken: accessToken)
        let (data, response) = try await transport(request)
        return try Self.parse(data: data, response: response)
    }

    static func request(accessToken: String) throws -> URLRequest {
        let token = accessToken.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty, token == accessToken,
              !token.contains("\r"), !token.contains("\n") else {
            throw SpotifyNowPlayingError.invalidAccessToken
        }
        var request = URLRequest(url: endpoint, cachePolicy: .reloadIgnoringLocalCacheData,
                                 timeoutInterval: 20)
        request.httpMethod = "GET"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }

    static func parse(data: Data, response: URLResponse) throws -> SpotifyNowPlayingItem? {
        guard data.count <= maximumResponseBytes else {
            throw SpotifyNowPlayingError.responseTooLarge
        }
        guard let response = response as? HTTPURLResponse else {
            throw SpotifyNowPlayingError.invalidResponse
        }
        switch response.statusCode {
        case 200:
            break
        case 204:
            return nil
        case 401:
            throw SpotifyNowPlayingError.unauthorized
        case 403:
            throw SpotifyNowPlayingError.forbidden
        case 429:
            throw SpotifyNowPlayingError.rateLimited(
                retryAfterSeconds: retryAfterSeconds(response.value(forHTTPHeaderField: "Retry-After")))
        default:
            throw SpotifyNowPlayingError.http(response.statusCode)
        }

        let decoded: CurrentlyPlayingResponse
        do {
            decoded = try JSONDecoder().decode(CurrentlyPlayingResponse.self, from: data)
        } catch {
            throw SpotifyNowPlayingError.invalidResponse
        }
        // Spotify documents a nullable item even on a 200 response (for
        // example, during a transition between devices).
        guard let item = decoded.item else { return nil }
        guard !item.uri.isEmpty, !item.name.isEmpty,
              let duration = item.durationMs, duration >= 0 else {
            throw SpotifyNowPlayingError.invalidResponse
        }
        let position = max(0, decoded.progressMs ?? 0)
        let artwork: URL?
        let artist: String
        let album: String
        let kind: SpotifyNowPlayingItem.Kind

        switch item.type {
        case SpotifyNowPlayingItem.Kind.track.rawValue:
            let names = (item.artists ?? []).map(\.name).filter { !$0.isEmpty }
            guard !names.isEmpty, let trackAlbum = item.album, !trackAlbum.name.isEmpty else {
                throw SpotifyNowPlayingError.invalidResponse
            }
            kind = .track
            artist = names.joined(separator: ", ")
            album = trackAlbum.name
            artwork = safeArtworkURL(trackAlbum.images)
        case SpotifyNowPlayingItem.Kind.episode.rawValue:
            guard let show = item.show, !show.name.isEmpty else {
                throw SpotifyNowPlayingError.invalidResponse
            }
            kind = .episode
            artist = show.publisher.flatMap { $0.isEmpty ? nil : $0 } ?? show.name
            album = show.name
            artwork = safeArtworkURL(item.images ?? show.images)
        default:
            throw SpotifyNowPlayingError.invalidResponse
        }

        return SpotifyNowPlayingItem(
            kind: kind,
            // Keep the opaque Spotify identity intact. In particular, never
            // split or rebuild colon-delimited spotify:local: URIs.
            uri: item.uri,
            title: item.name,
            artist: artist,
            album: album,
            durationMs: duration,
            positionMs: position,
            isPlaying: decoded.isPlaying,
            artworkURL: artwork
        )
    }

    private static func retryAfterSeconds(_ value: String?) -> Int? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let seconds = Int(trimmed), seconds >= 0 else { return nil }
        return seconds
    }

    private static func safeArtworkURL(_ images: [ImageResponse]) -> URL? {
        for image in images {
            guard let components = URLComponents(string: image.url),
                  components.scheme?.lowercased() == "https",
                  components.host?.isEmpty == false,
                  components.user == nil, components.password == nil,
                  components.fragment == nil,
                  let url = components.url else { continue }
            return url
        }
        return nil
    }

    private static func urlSessionTransport() -> SpotifyNowPlayingTransport {
        spotifyEphemeralTransport()
    }

    private struct CurrentlyPlayingResponse: Decodable {
        let progressMs: Int64?
        let isPlaying: Bool
        let item: ItemResponse?

        enum CodingKeys: String, CodingKey {
            case progressMs = "progress_ms"
            case isPlaying = "is_playing"
            case item
        }
    }

    private struct ItemResponse: Decodable {
        let type: String
        let uri: String
        let name: String
        let durationMs: Int64?
        let artists: [ArtistResponse]?
        let album: AlbumResponse?
        let show: ShowResponse?
        let images: [ImageResponse]?

        enum CodingKeys: String, CodingKey {
            case type, uri, name, artists, album, show, images
            case durationMs = "duration_ms"
        }
    }

    private struct ArtistResponse: Decodable {
        let name: String
    }

    private struct AlbumResponse: Decodable {
        let name: String
        let images: [ImageResponse]
    }

    private struct ShowResponse: Decodable {
        let name: String
        let publisher: String?
        let images: [ImageResponse]
    }

    private struct ImageResponse: Decodable {
        let url: String
    }
}

enum SpotifyArtworkError: Error, Equatable, LocalizedError {
    case invalidURL
    case invalidResponse
    case responseTooLarge
    case http(Int)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Spotify returned an unsafe artwork URL."
        case .invalidResponse:
            return "Spotify artwork returned an invalid response."
        case .responseTooLarge:
            return "Spotify returned oversized artwork."
        case .http(let status):
            return "Spotify artwork returned HTTP \(status)."
        }
    }
}

final class SpotifyArtworkClient {
    static let maximumResponseBytes = 16 * 1024 * 1024

    private let transport: SpotifyNowPlayingTransport

    init(transport: @escaping SpotifyNowPlayingTransport) {
        self.transport = transport
    }

    convenience init() {
        self.init(transport: spotifyEphemeralTransport())
    }

    func fetch(_ url: URL) async throws -> Data {
        let request = try Self.request(url: url)
        let (data, response) = try await transport(request)
        guard data.count <= Self.maximumResponseBytes else {
            throw SpotifyArtworkError.responseTooLarge
        }
        guard let response = response as? HTTPURLResponse else {
            throw SpotifyArtworkError.invalidResponse
        }
        guard (200..<300).contains(response.statusCode) else {
            throw SpotifyArtworkError.http(response.statusCode)
        }
        return data
    }

    static func request(url: URL) throws -> URLRequest {
        guard isSafeHTTPS(url) else { throw SpotifyArtworkError.invalidURL }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData,
                                 timeoutInterval: 20)
        request.httpMethod = "GET"
        request.setValue("image/*", forHTTPHeaderField: "Accept")
        return request
    }
}

private func isSafeHTTPS(_ url: URL) -> Bool {
    guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
    return components.scheme?.lowercased() == "https"
        && components.host?.isEmpty == false
        && components.user == nil
        && components.password == nil
        && components.fragment == nil
}

private func spotifyEphemeralTransport() -> SpotifyNowPlayingTransport {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.urlCache = nil
    configuration.httpCookieStorage = nil
    configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
    let session = URLSession(configuration: configuration,
                             delegate: SpotifyHTTPRedirectRejector(), delegateQueue: nil)
    return { request in try await session.data(for: request) }
}

final class SpotifyHTTPRedirectRejector: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
