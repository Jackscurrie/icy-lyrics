package com.icy.lyrics.core.platform.auth

import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.icy.lyrics.core.platform.diagnostics.DiagnosticInput
import com.icy.lyrics.core.platform.diagnostics.DiagnosticSeverity
import com.icy.lyrics.core.platform.diagnostics.DiagnosticSink
import com.icy.lyrics.core.platform.network.await
import com.icy.lyrics.core.platform.network.readUtf8Limited
import com.icy.lyrics.core.platform.provider.SpotifyAccessTokenSource
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class SpotifyPkceConfig(
  val clientId: String,
  val scopes: Set<String> = emptySet(),
  val callbackPath: String = "/callback",
  val authorizationEndpoint: HttpUrl = "https://accounts.spotify.com/authorize".toHttpUrl(),
  val tokenEndpoint: HttpUrl = "https://accounts.spotify.com/api/token".toHttpUrl(),
  val allowInsecureForTests: Boolean = false,
) {
  init {
    require(callbackPath.startsWith('/') && '?' !in callbackPath && '#' !in callbackPath)
    require(
      allowInsecureForTests ||
        (authorizationEndpoint.isHttps && tokenEndpoint.isHttps &&
          authorizationEndpoint.host == "accounts.spotify.com" &&
          tokenEndpoint.host == "accounts.spotify.com"),
    ) { "Spotify OAuth endpoints must use the official HTTPS host" }
  }
}

class SpotifyAuthorizationLaunch internal constructor(
  val authorizationUri: Uri,
  val redirectUri: String,
  internal val callback: SpotifyLoopbackCallbackSession,
) : Closeable {
  fun customTabsIntent(): CustomTabsIntent = CustomTabsIntent.Builder().build()
  override fun close() = callback.close()
}

sealed interface SpotifyAuthorizationResult {
  data class Success(val scopes: Set<String>, val expiresAtEpochMs: Long) : SpotifyAuthorizationResult
  data class Cancelled(val reason: String) : SpotifyAuthorizationResult
  data class Failure(val message: String, val httpStatus: Int? = null) : SpotifyAuthorizationResult
}

/**
 * Experimental public-client OAuth capability. It uses PKCE and a short-lived
 * 127.0.0.1 listener; no client secret or custom URI scheme is involved.
 */
class SpotifyPkceClient(
  private val config: SpotifyPkceConfig,
  private val client: OkHttpClient,
  private val credentials: SpotifyCredentialStore,
  private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
  private val clock: () -> Long = System::currentTimeMillis,
  private val random: SecureRandom = SecureRandom(),
) : SpotifyAccessTokenSource {
  private val json = Json { ignoreUnknownKeys = true; isLenient = false }
  private val refreshMutex = Mutex()
  private val directClient = client.newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

  val isConfigured: Boolean
    get() = config.clientId.isNotBlank()

  suspend fun beginAuthorization(): SpotifyAuthorizationLaunch {
    check(isConfigured) { "Spotify client ID is not configured." }
    val callback = SpotifyLoopbackCallbackSession.open(config.callbackPath)
    return try {
      val verifier = randomUrlToken(64)
      val state = randomUrlToken(32)
      credentials.writePendingAuthorization(
        PendingSpotifyAuthorization(
          state = state,
          codeVerifier = verifier,
          redirectUri = callback.redirectUri,
          createdAtEpochMs = clock(),
        ),
      )
      val authorizationUri = Uri.parse(config.authorizationEndpoint.toString()).buildUpon()
        .appendQueryParameter("client_id", config.clientId)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("redirect_uri", callback.redirectUri)
        .appendQueryParameter("code_challenge_method", "S256")
        .appendQueryParameter("code_challenge", codeChallenge(verifier))
        .appendQueryParameter("state", state)
        .apply {
          config.scopes.sorted().takeIf(List<String>::isNotEmpty)
            ?.let { appendQueryParameter("scope", it.joinToString(" ")) }
        }
        .build()
      SpotifyAuthorizationLaunch(authorizationUri, callback.redirectUri, callback)
    } catch (error: Throwable) {
      callback.close()
      credentials.clearPendingAuthorization()
      throw error
    }
  }

  suspend fun completeAuthorization(
    launch: SpotifyAuthorizationLaunch,
    timeoutMs: Long = SpotifyLoopbackCallbackSession.DEFAULT_TIMEOUT_MS,
  ): SpotifyAuthorizationResult {
    return try {
      handleRedirect(launch.callback.awaitRedirect(timeoutMs))
    } catch (_: TimeoutCancellationException) {
      credentials.clearPendingAuthorization()
      log("callback-timeout", "Spotify authorization callback timed out")
      SpotifyAuthorizationResult.Failure("Spotify authorization callback timed out.")
    } catch (error: CancellationException) {
      credentials.clearPendingAuthorization()
      throw error
    } catch (error: Exception) {
      credentials.clearPendingAuthorization()
      log("callback-failed", error.message ?: "Spotify callback failed")
      SpotifyAuthorizationResult.Failure(error.message ?: "Spotify authorization callback failed.")
    } finally {
      launch.close()
    }
  }

  suspend fun handleRedirect(uri: Uri): SpotifyAuthorizationResult {
    val pending = credentials.readPendingAuthorization()
      ?: return SpotifyAuthorizationResult.Failure("No Spotify authorization is pending.")
    if (clock() - pending.createdAtEpochMs > PENDING_MAX_AGE_MS) {
      credentials.clearPendingAuthorization()
      return SpotifyAuthorizationResult.Failure("Spotify authorization expired. Try again.")
    }
    if (!matchesRedirect(uri, Uri.parse(pending.redirectUri))) {
      credentials.clearPendingAuthorization()
      log("redirect-mismatch", "Spotify callback did not match the requested loopback URI")
      return SpotifyAuthorizationResult.Failure("Spotify callback did not match the requested loopback URI.")
    }
    val returnedState = uri.getQueryParameter("state")
    if (returnedState == null || !constantTimeEquals(returnedState, pending.state)) {
      credentials.clearPendingAuthorization()
      log("state-mismatch", "Spotify authorization state did not match")
      return SpotifyAuthorizationResult.Failure("Spotify authorization state did not match.")
    }
    uri.getQueryParameter("error")?.let { error ->
      credentials.clearPendingAuthorization()
      return SpotifyAuthorizationResult.Cancelled(error.take(120))
    }
    val code = uri.getQueryParameter("code")
      ?: run {
        credentials.clearPendingAuthorization()
        log("missing-code", "Spotify callback did not include an authorization code")
        return SpotifyAuthorizationResult.Failure("Spotify callback did not include an authorization code.")
      }
    val result = exchangeCode(code, pending)
    credentials.clearPendingAuthorization()
    return result
  }

  override suspend fun accessToken(): String? = refreshMutex.withLock {
    val tokens = credentials.readTokens() ?: return@withLock null
    if (clock() < tokens.expiresAtEpochMs - REFRESH_MARGIN_MS) return@withLock tokens.accessToken
    refreshStoredTokens(tokens, rejectedByResourceServer = false)
  }

  /** Refresh exactly the credential that Spicy rejected, then let its caller retry once. */
  override suspend fun refreshAfterRejection(rejectedToken: String): String? = refreshMutex.withLock {
    val tokens = credentials.readTokens() ?: return@withLock null
    if (tokens.accessToken != rejectedToken &&
      clock() < tokens.expiresAtEpochMs - REFRESH_MARGIN_MS
    ) {
      return@withLock tokens.accessToken
    }
    refreshStoredTokens(tokens, rejectedByResourceServer = tokens.accessToken == rejectedToken)
  }

  suspend fun hasAuthorization(): Boolean = credentials.readTokens() != null

  suspend fun cancelAuthorization(launch: SpotifyAuthorizationLaunch? = null) {
    launch?.close()
    credentials.clearPendingAuthorization()
  }

  suspend fun disconnect() = credentials.clearAll()

  private suspend fun exchangeCode(
    code: String,
    pending: PendingSpotifyAuthorization,
  ): SpotifyAuthorizationResult {
    val body = FormBody.Builder()
      .add("client_id", config.clientId)
      .add("grant_type", "authorization_code")
      .add("code", code)
      .add("redirect_uri", pending.redirectUri)
      .add("code_verifier", pending.codeVerifier)
      .build()
    return when (val result = requestTokens(body, previousRefreshToken = null)) {
      is TokenRequestResult.Success -> SpotifyAuthorizationResult.Success(
        result.tokens.scopes,
        result.tokens.expiresAtEpochMs,
      )
      is TokenRequestResult.Failed -> SpotifyAuthorizationResult.Failure(result.message, result.httpStatus)
    }
  }

  private suspend fun refreshStoredTokens(
    tokens: SpotifyTokenSet,
    rejectedByResourceServer: Boolean,
  ): String? {
    val refreshToken = tokens.refreshToken ?: run {
      credentials.clearTokens()
      return null
    }
    return when (val refreshed = requestTokens(
      FormBody.Builder()
        .add("grant_type", "refresh_token")
        .add("refresh_token", refreshToken)
        .add("client_id", config.clientId)
        .build(),
      previousRefreshToken = refreshToken,
    )) {
      is TokenRequestResult.Success -> refreshed.tokens.accessToken
      is TokenRequestResult.Failed -> {
        if (refreshed.httpStatus in 400..499 && refreshed.httpStatus != 429) {
          credentials.clearTokens()
        } else if (rejectedByResourceServer) {
          // Keep a recoverable refresh token after a transient token-endpoint
          // failure, but force the next access through refresh instead of
          // reusing the access token that Spicy has already rejected.
          credentials.writeTokens(tokens.copy(expiresAtEpochMs = 0L))
        }
        null
      }
    }
  }

  private suspend fun requestTokens(
    body: FormBody,
    previousRefreshToken: String?,
  ): TokenRequestResult {
    if (!isConfigured) return TokenRequestResult.Failed("Spotify client ID is not configured.")
    val request = Request.Builder()
      .url(config.tokenEndpoint)
      .header("Accept", "application/json")
      .post(body)
      .build()
    return try {
      directClient.newCall(request).await().use { response ->
        if (response.isRedirect) {
          return TokenRequestResult.Failed("Spotify refused a redirect during token exchange.")
        }
        if (!response.isSuccessful) {
          log("token-http", "Spotify token endpoint returned HTTP ${response.code}", response.code)
          return TokenRequestResult.Failed("Spotify token exchange failed.", response.code)
        }
        val decoded = json.decodeFromString<TokenResponse>(response.readUtf8Limited(MAX_TOKEN_RESPONSE_BYTES))
        if (!decoded.tokenType.equals("Bearer", ignoreCase = true) || decoded.accessToken.isBlank()) {
          return TokenRequestResult.Failed("Spotify returned an unsupported token response.")
        }
        val tokenSet = SpotifyTokenSet(
          accessToken = decoded.accessToken,
          refreshToken = decoded.refreshToken ?: previousRefreshToken,
          expiresAtEpochMs = clock() + decoded.expiresIn.coerceIn(1L, MAX_TOKEN_LIFETIME_SECONDS) * 1_000L,
          scopes = decoded.scope?.split(' ')?.filter(String::isNotBlank)?.toSet() ?: config.scopes,
          tokenType = decoded.tokenType,
        )
        credentials.writeTokens(tokenSet)
        TokenRequestResult.Success(tokenSet)
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      log("token-failed", error.message ?: "Spotify token request failed")
      TokenRequestResult.Failed(error.message ?: "Spotify token request failed.")
    }
  }

  private suspend fun log(code: String, message: String, status: Int? = null) {
    diagnostics.record(
      DiagnosticInput(
        severity = DiagnosticSeverity.WARNING,
        component = "spotify-pkce",
        code = code,
        httpStatus = status,
        message = message,
      ),
    )
  }

  private fun randomUrlToken(byteCount: Int): String {
    val bytes = ByteArray(byteCount).also(random::nextBytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
  }

  private fun codeChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
  }

  private fun matchesRedirect(actual: Uri, expected: Uri): Boolean =
    actual.scheme == "http" &&
      actual.host == "127.0.0.1" &&
      actual.scheme == expected.scheme &&
      actual.host == expected.host &&
      actual.port == expected.port &&
      actual.path == expected.path

  private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.toByteArray(Charsets.UTF_8),
    right.toByteArray(Charsets.UTF_8),
  )

  @Serializable
  private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
  )

  private sealed interface TokenRequestResult {
    data class Success(val tokens: SpotifyTokenSet) : TokenRequestResult
    data class Failed(val message: String, val httpStatus: Int? = null) : TokenRequestResult
  }

  companion object {
    private const val REFRESH_MARGIN_MS = 60_000L
    private const val PENDING_MAX_AGE_MS = 10L * 60L * 1_000L
    private const val MAX_TOKEN_LIFETIME_SECONDS = 24L * 60L * 60L
    private const val MAX_TOKEN_RESPONSE_BYTES = 512L * 1_024L
  }
}
