package com.icy.lyrics.core.platform.auth

import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SpotifyPkceClientTest {
  private lateinit var server: MockWebServer
  private lateinit var store: InMemorySpotifyCredentialStore
  private lateinit var client: SpotifyPkceClient

  @Before
  fun setUp() {
    server = MockWebServer().also { it.start() }
    store = InMemorySpotifyCredentialStore()
    client = SpotifyPkceClient(
      config = SpotifyPkceConfig(
        clientId = "public-client-id",
        authorizationEndpoint = server.url("/authorize"),
        tokenEndpoint = server.url("/api/token"),
        allowInsecureForTests = true,
      ),
      client = OkHttpClient(),
      credentials = store,
      clock = { 1_000_000L },
    )
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun loopbackSuccessExchangesCodeWithVerifierAndStoresToken() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"access_token":"access","token_type":"Bearer","expires_in":3600,"refresh_token":"refresh"}""",
      ),
    )
    val launch = client.beginAuthorization()
    val state = launch.authorizationUri.getQueryParameter("state")!!
    val completion = async(Dispatchers.Default) { client.completeAuthorization(launch) }

    sendCallback(launch.redirectUri, "code=auth-code&state=${encode(state)}")
    val result = completion.await()

    assertTrue(result is SpotifyAuthorizationResult.Success)
    assertEquals("access", store.readTokens()?.accessToken)
    val tokenRequest = server.takeRequest()
    assertEquals("/api/token", tokenRequest.path)
    val form = tokenRequest.body.readUtf8()
    assertTrue(form.contains("grant_type=authorization_code"))
    assertTrue(form.contains("code_verifier="))
    assertTrue(form.contains("client_id=public-client-id"))
    assertFalse(form.contains("client_secret"))
  }

  @Test
  fun loopbackRejectsStateMismatchWithoutTokenRequest() = runTest {
    val launch = client.beginAuthorization()
    val completion = async(Dispatchers.Default) { client.completeAuthorization(launch) }

    sendCallback(launch.redirectUri, "code=auth-code&state=wrong")
    val result = completion.await()

    assertTrue(result is SpotifyAuthorizationResult.Failure)
    assertEquals(0, server.requestCount)
    assertEquals(null, store.readTokens())
    assertEquals(null, store.readPendingAuthorization())
  }

  @Test
  fun cancelAuthorizationClosesListenerAndClearsOnlyPendingState() = runTest {
    store.writeTokens(SpotifyTokenSet("existing", "refresh", expiresAtEpochMs = Long.MAX_VALUE))
    val launch = client.beginAuthorization()
    assertNotNull(store.readPendingAuthorization())

    client.cancelAuthorization(launch)

    assertEquals(null, store.readPendingAuthorization())
    assertEquals("existing", store.readTokens()?.accessToken)
  }

  @Test
  fun redirectMismatchClearsPendingAuthorization() = runTest {
    val launch = client.beginAuthorization()
    val state = launch.authorizationUri.getQueryParameter("state")!!

    val result = client.handleRedirect(Uri.parse("http://127.0.0.1:1/wrong?code=x&state=${encode(state)}"))
    launch.close()

    assertTrue(result is SpotifyAuthorizationResult.Failure)
    assertEquals(null, store.readPendingAuthorization())
    assertEquals(0, server.requestCount)
  }

  @Test
  fun callbackWithoutCodeClearsPendingAuthorization() = runTest {
    val launch = client.beginAuthorization()
    val state = launch.authorizationUri.getQueryParameter("state")!!

    val result = client.handleRedirect(Uri.parse("${launch.redirectUri}?state=${encode(state)}"))
    launch.close()

    assertTrue(result is SpotifyAuthorizationResult.Failure)
    assertEquals(null, store.readPendingAuthorization())
    assertEquals(0, server.requestCount)
  }

  @Test
  fun closingLoopbackUnblocksPendingAccept() = runBlocking {
    val callback = SpotifyLoopbackCallbackSession.open("/callback")
    val waiting = async(Dispatchers.IO) { runCatching { callback.awaitRedirect(10_000L) } }
    delay(50L)
    callback.close()

    val result = withTimeout(1_000L) { waiting.await() }
    assertTrue(result.isFailure)
  }

  @Test
  fun closingLoopbackUnblocksAClientThatNeverFinishesHeaders() = runBlocking {
    val callback = SpotifyLoopbackCallbackSession.open("/callback")
    val waiting = async(Dispatchers.IO) { runCatching { callback.awaitRedirect(10_000L) } }
    val uri = URI(callback.redirectUri)
    val stalled = Socket(uri.host, uri.port)
    delay(100L)

    callback.close()
    stalled.close()

    val result = withTimeout(1_000L) { waiting.await() }
    assertTrue(result.isFailure)
  }

  @Test
  fun oversizedRequestIsRejectedWithoutEndingTheAuthorizationSession() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"access_token":"access","token_type":"Bearer","expires_in":3600}""",
      ),
    )
    val launch = client.beginAuthorization()
    val state = launch.authorizationUri.getQueryParameter("state")!!
    val completion = async(Dispatchers.Default) { client.completeAuthorization(launch) }

    sendRawRequest(launch.redirectUri, "GET /${"x".repeat(4_200)} HTTP/1.1\r\n\r\n")
    sendCallback(launch.redirectUri, "code=auth-code&state=${encode(state)}")

    assertTrue(completion.await() is SpotifyAuthorizationResult.Success)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun loopbackTimeoutReturnsWithoutLeavingAcceptBlocked() = runBlocking {
    val callback = SpotifyLoopbackCallbackSession.open("/callback")
    val result = runCatching { callback.awaitRedirect(50L) }
    callback.close()

    assertTrue(result.isFailure)
  }

  @Test
  fun expiredTokenRefreshUsesPublicClientFieldsOnly() = runTest {
    store.writeTokens(SpotifyTokenSet("expired", "refresh", expiresAtEpochMs = 1L))
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"access_token":"new-access","token_type":"Bearer","expires_in":3600}""",
      ),
    )

    assertEquals("new-access", client.accessToken())
    assertEquals("refresh", store.readTokens()?.refreshToken)
    val form = server.takeRequest().body.readUtf8()
    assertTrue(form.contains("grant_type=refresh_token"))
    assertTrue(form.contains("refresh_token=refresh"))
    assertTrue(form.contains("client_id=public-client-id"))
    assertFalse(form.contains("client_secret"))
  }

  @Test
  fun resourceServerRejectionRefreshesAnOtherwiseUnexpiredToken() = runTest {
    store.writeTokens(
      SpotifyTokenSet("rejected-access", "refresh", expiresAtEpochMs = 9_000_000L),
    )
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"access_token":"replacement-access","token_type":"Bearer","expires_in":3600}""",
      ),
    )

    assertEquals("replacement-access", client.refreshAfterRejection("rejected-access"))
    assertEquals("replacement-access", store.readTokens()?.accessToken)
    val form = server.takeRequest().body.readUtf8()
    assertTrue(form.contains("grant_type=refresh_token"))
    assertTrue(form.contains("refresh_token=refresh"))
  }

  @Test
  fun resourceServerRejectionUsesAConcurrentReplacementWithoutRefreshingIt() = runTest {
    store.writeTokens(
      SpotifyTokenSet("already-new", "refresh", expiresAtEpochMs = 9_000_000L),
    )

    assertEquals("already-new", client.refreshAfterRejection("older-rejected"))
    assertEquals(0, server.requestCount)
  }

  @Test
  fun transientRejectedTokenRefreshFailureForcesTheNextCallThroughRefresh() = runTest {
    store.writeTokens(
      SpotifyTokenSet("rejected-access", "refresh", expiresAtEpochMs = 9_000_000L),
    )
    server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

    assertEquals(null, client.refreshAfterRejection("rejected-access"))
    assertEquals(0L, store.readTokens()?.expiresAtEpochMs)

    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"access_token":"later-access","token_type":"Bearer","expires_in":3600}""",
      ),
    )
    assertEquals("later-access", client.accessToken())
    assertEquals(2, server.requestCount)
  }

  @Test
  fun rejectedRefreshClearsUnrecoverableCredentials() = runTest {
    store.writeTokens(SpotifyTokenSet("expired", "invalid-refresh", expiresAtEpochMs = 1L))
    server.enqueue(MockResponse().setResponseCode(400).setBody("{}"))

    assertEquals(null, client.accessToken())
    assertEquals(null, store.readTokens())
    assertFalse(client.hasAuthorization())
  }

  @Test
  fun tokenRefreshNeverForwardsCredentialsAcrossRedirects() = runTest {
    val redirectTarget = MockWebServer().also { it.start() }
    try {
      store.writeTokens(SpotifyTokenSet("expired", "refresh", expiresAtEpochMs = 1L))
      redirectTarget.enqueue(
        MockResponse().setResponseCode(200).setBody(
          """{"access_token":"stolen","token_type":"Bearer","expires_in":3600}""",
        ),
      )
      server.enqueue(
        MockResponse()
          .setResponseCode(307)
          .addHeader("Location", redirectTarget.url("/capture")),
      )

      assertEquals(null, client.accessToken())
      assertEquals(1, server.requestCount)
      assertEquals(0, redirectTarget.requestCount)
    } finally {
      redirectTarget.shutdown()
    }
  }

  private suspend fun sendCallback(redirectUri: String, query: String) = withContext(Dispatchers.IO) {
    val uri = URI("$redirectUri?$query")
    Socket(uri.host, uri.port).use { socket ->
      val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII)
      writer.write("GET ${uri.rawPath}?${uri.rawQuery} HTTP/1.1\r\n")
      writer.write("Host: ${uri.host}:${uri.port}\r\n")
      writer.write("Connection: close\r\n\r\n")
      writer.flush()
      val response = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
      assertNotNull(response.readLine())
    }
  }

  private suspend fun sendRawRequest(redirectUri: String, request: String) = withContext(Dispatchers.IO) {
    val uri = URI(redirectUri)
    Socket(uri.host, uri.port).use { socket ->
      val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII)
      writer.write(request)
      writer.flush()
      val response = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
      assertTrue(response.readLine().contains("431"))
    }
  }

  private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
