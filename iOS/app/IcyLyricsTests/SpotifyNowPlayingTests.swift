import Foundation
import XCTest
@testable import IcyLyrics

final class SpotifyNowPlayingTests: XCTestCase {
    func testRequestUsesExactEndpointGetAndBearerToken() throws {
        let request = try SpotifyNowPlayingClient.request(accessToken: "test-token")

        XCTAssertEqual(request.url?.absoluteString,
                       "https://api.spotify.com/v1/me/player/currently-playing?additional_types=track%2Cepisode")
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "application/json")
        XCTAssertNil(request.httpBody)
    }

    func testRejectsBlankOrHeaderInjectingTokensBeforeTransport() async {
        var transportCalls = 0
        let client = SpotifyNowPlayingClient { _ in
            transportCalls += 1
            throw ProbeError.unexpectedTransport
        }

        for token in ["", " token", "token\nInjected: yes"] {
            do {
                _ = try await client.fetch(accessToken: token)
                XCTFail("Invalid token was accepted")
            } catch {
                XCTAssertEqual(error as? SpotifyNowPlayingError, .invalidAccessToken)
            }
        }
        XCTAssertEqual(transportCalls, 0)
    }

    func testParsesTrackAndPreservesCompleteLocalURI() async throws {
        let localURI = "spotify:local:Artist:Album:Title:with:colons:237"
        let client = makeClient(status: 200, json: """
        {
          "progress_ms": 4567,
          "is_playing": true,
          "item": {
            "type": "track",
            "uri": "\(localURI)",
            "name": "Local title",
            "duration_ms": 237000,
            "artists": [{"name":"First"},{"name":"Second"}],
            "album": {
              "name": "Local album",
              "images": [
                {"url":"http://unsafe.example/art.jpg"},
                {"url":"https://i.scdn.co/image/safe"}
              ]
            }
          }
        }
        """)

        let fetched = try await client.fetch(accessToken: "token")
        let item = try XCTUnwrap(fetched)
        XCTAssertEqual(item.kind, .track)
        XCTAssertEqual(item.uri, localURI)
        XCTAssertEqual(item.title, "Local title")
        XCTAssertEqual(item.artist, "First, Second")
        XCTAssertEqual(item.album, "Local album")
        XCTAssertEqual(item.durationMs, 237_000)
        XCTAssertEqual(item.positionMs, 4_567)
        XCTAssertTrue(item.isPlaying)
        XCTAssertEqual(item.artworkURL?.absoluteString, "https://i.scdn.co/image/safe")
    }

    func testParsesEpisodeMetadataAndSafeArtwork() async throws {
        let client = makeClient(status: 200, json: """
        {
          "progress_ms": null,
          "is_playing": false,
          "item": {
            "type": "episode",
            "uri": "spotify:episode:episode-id",
            "name": "Episode title",
            "duration_ms": 1800123,
            "images": [{"url":"https://image-cdn-ak.spotifycdn.com/episode.jpg"}],
            "show": {
              "name": "Show title",
              "publisher": "Publisher",
              "images": [{"url":"https://i.scdn.co/image/show"}]
            }
          }
        }
        """)

        let fetched = try await client.fetch(accessToken: "token")
        let item = try XCTUnwrap(fetched)
        XCTAssertEqual(item.kind, .episode)
        XCTAssertEqual(item.uri, "spotify:episode:episode-id")
        XCTAssertEqual(item.artist, "Publisher")
        XCTAssertEqual(item.album, "Show title")
        XCTAssertEqual(item.positionMs, 0)
        XCTAssertFalse(item.isPlaying)
        XCTAssertEqual(item.artworkURL?.absoluteString,
                       "https://image-cdn-ak.spotifycdn.com/episode.jpg")
    }

    func test204MeansNothingIsPlaying() async throws {
        let subject = makeClient(status: 204, data: Data())
        let fetched = try await subject.fetch(accessToken: "token")
        XCTAssertNil(fetched)
    }

    func test200NullableItemAlsoMeansNothingIsPlaying() async throws {
        let subject = makeClient(status: 200, json:
            "{\"progress_ms\":null,\"is_playing\":false,\"item\":null}")
        let fetched = try await subject.fetch(accessToken: "token")
        XCTAssertNil(fetched)
    }

    func testProductionRedirectDelegateRefusesTheRedirectRequest() {
        let delegate = SpotifyHTTPRedirectRejector()
        let session = URLSession(configuration: .ephemeral)
        let original = URL(string: "https://api.spotify.com/v1/me/player/currently-playing")!
        let redirected = URL(string: "https://example.invalid/redirected")!
        let task = session.dataTask(with: original)
        let response = HTTPURLResponse(url: original, statusCode: 302,
                                       httpVersion: "HTTP/1.1",
                                       headerFields: ["Location": redirected.absoluteString])!
        let completed = expectation(description: "redirect decision")

        delegate.urlSession(session, task: task, willPerformHTTPRedirection: response,
                            newRequest: URLRequest(url: redirected)) { request in
            XCTAssertNil(request)
            completed.fulfill()
        }

        wait(for: [completed], timeout: 1)
        task.cancel()
        session.invalidateAndCancel()
    }

    func testMapsAuthorizationAndRateLimitResponses() async {
        await assertError(status: 401, expected: .unauthorized)
        await assertError(status: 403, expected: .forbidden)
        await assertError(status: 429, headers: ["Retry-After": "17"],
                          expected: .rateLimited(retryAfterSeconds: 17))
        await assertError(status: 429, headers: ["Retry-After": "invalid"],
                          expected: .rateLimited(retryAfterSeconds: nil))
        await assertError(status: 503, expected: .http(503))
    }

    func testRejectsMalformedAndOversizedResponses() async {
        let malformed = makeClient(status: 200, json: """
        {"progress_ms":1,"is_playing":true,"item":{"type":"ad","uri":"spotify:ad:1","name":"Ad","duration_ms":1}}
        """)
        do {
            _ = try await malformed.fetch(accessToken: "token")
            XCTFail("Malformed response was accepted")
        } catch {
            XCTAssertEqual(error as? SpotifyNowPlayingError, .invalidResponse)
        }

        let oversized = makeClient(status: 200,
            data: Data(repeating: 0x20, count: SpotifyNowPlayingClient.maximumResponseBytes + 1))
        do {
            _ = try await oversized.fetch(accessToken: "token")
            XCTFail("Oversized response was accepted")
        } catch {
            XCTAssertEqual(error as? SpotifyNowPlayingError, .responseTooLarge)
        }
    }

    func testRejectsUnsafeArtworkWithoutRejectingTrack() async throws {
        let client = makeClient(status: 200, json: """
        {
          "progress_ms": 10,
          "is_playing": true,
          "item": {
            "type": "track", "uri": "spotify:track:id", "name": "Title", "duration_ms": 100,
            "artists": [{"name":"Artist"}],
            "album": {"name":"Album","images":[{"url":"https://user:password@example.com/art#fragment"}]}
          }
        }
        """)

        let fetched = try await client.fetch(accessToken: "token")
        let item = try XCTUnwrap(fetched)
        XCTAssertNil(item.artworkURL)
    }

    private func assertError(status: Int, headers: [String: String]? = nil,
                             expected: SpotifyNowPlayingError) async {
        let client = makeClient(status: status, data: Data(), headers: headers)
        do {
            _ = try await client.fetch(accessToken: "token")
            XCTFail("HTTP \(status) was accepted")
        } catch {
            XCTAssertEqual(error as? SpotifyNowPlayingError, expected)
        }
    }

    private func makeClient(status: Int, json: String,
                            headers: [String: String]? = nil) -> SpotifyNowPlayingClient {
        makeClient(status: status, data: Data(json.utf8), headers: headers)
    }

    private func makeClient(status: Int, data: Data,
                            headers: [String: String]? = nil) -> SpotifyNowPlayingClient {
        SpotifyNowPlayingClient { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: status,
                                           httpVersion: "HTTP/1.1", headerFields: headers)!
            return (data, response)
        }
    }
}

final class SpotifyArtworkTests: XCTestCase {
    func testRequestRequiresHTTPSAndNeverSendsAuthorization() throws {
        let url = URL(string: "https://i.scdn.co/image/artwork")!
        let request = try SpotifyArtworkClient.request(url: url)

        XCTAssertEqual(request.url, url)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "image/*")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertNil(request.httpBody)
    }

    func testRejectsUnsafeArtworkURLBeforeTransport() async {
        var transportCalls = 0
        let client = SpotifyArtworkClient { _ in
            transportCalls += 1
            throw ProbeError.unexpectedTransport
        }
        let unsafe = [
            "http://i.scdn.co/image/artwork",
            "https://user:password@i.scdn.co/image/artwork",
            "https://i.scdn.co/image/artwork#fragment",
        ]

        for value in unsafe {
            do {
                _ = try await client.fetch(URL(string: value)!)
                XCTFail("Unsafe artwork URL was accepted")
            } catch {
                XCTAssertEqual(error as? SpotifyArtworkError, .invalidURL)
            }
        }
        XCTAssertEqual(transportCalls, 0)
    }

    func testReturnsSuccessfulArtworkBytesWithoutAddingAuthorization() async throws {
        let expected = Data([0x89, 0x50, 0x4e, 0x47])
        var captured: URLRequest?
        let client = SpotifyArtworkClient { request in
            captured = request
            return (expected, HTTPURLResponse(url: request.url!, statusCode: 200,
                                               httpVersion: "HTTP/1.1", headerFields: nil)!)
        }

        let data = try await client.fetch(URL(string: "https://i.scdn.co/image/artwork")!)
        XCTAssertEqual(data, expected)
        XCTAssertNil(captured?.value(forHTTPHeaderField: "Authorization"))
    }

    func testRejectsArtworkHTTPFailureAndOversizedData() async {
        let url = URL(string: "https://i.scdn.co/image/artwork")!
        let failure = SpotifyArtworkClient { request in
            (Data(), HTTPURLResponse(url: request.url!, statusCode: 302,
                                     httpVersion: "HTTP/1.1", headerFields: nil)!)
        }
        do {
            _ = try await failure.fetch(url)
            XCTFail("Non-2xx artwork was accepted")
        } catch {
            XCTAssertEqual(error as? SpotifyArtworkError, .http(302))
        }

        let oversized = SpotifyArtworkClient { request in
            (Data(repeating: 0, count: SpotifyArtworkClient.maximumResponseBytes + 1),
             HTTPURLResponse(url: request.url!, statusCode: 200,
                             httpVersion: "HTTP/1.1", headerFields: nil)!)
        }
        do {
            _ = try await oversized.fetch(url)
            XCTFail("Oversized artwork was accepted")
        } catch {
            XCTAssertEqual(error as? SpotifyArtworkError, .responseTooLarge)
        }
    }
}

private enum ProbeError: Error {
    case unexpectedTransport
}
