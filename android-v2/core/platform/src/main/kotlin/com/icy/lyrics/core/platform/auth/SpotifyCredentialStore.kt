package com.icy.lyrics.core.platform.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SpotifyTokenSet(
  val accessToken: String,
  val refreshToken: String? = null,
  val expiresAtEpochMs: Long,
  val scopes: Set<String> = emptySet(),
  val tokenType: String = "Bearer",
)

@Serializable
data class PendingSpotifyAuthorization(
  val state: String,
  val codeVerifier: String,
  val redirectUri: String,
  val createdAtEpochMs: Long,
)

interface SpotifyCredentialStore {
  suspend fun readTokens(): SpotifyTokenSet?
  suspend fun writeTokens(tokens: SpotifyTokenSet)
  suspend fun clearTokens()
  suspend fun readPendingAuthorization(): PendingSpotifyAuthorization?
  suspend fun writePendingAuthorization(pending: PendingSpotifyAuthorization)
  suspend fun clearPendingAuthorization()
  suspend fun clearAll()
}

/** AES-GCM encrypted preferences whose key material never leaves Android Keystore. */
class KeystoreSpotifyCredentialStore(
  context: Context,
  private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SpotifyCredentialStore {
  private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val mutex = Mutex()

  override suspend fun readTokens(): SpotifyTokenSet? = read(KEY_TOKENS)

  override suspend fun writeTokens(tokens: SpotifyTokenSet) {
    require(tokens.accessToken.isNotBlank())
    write(KEY_TOKENS, json.encodeToString(tokens))
  }

  override suspend fun clearTokens() {
    mutex.withLock { preferences.edit().remove(KEY_TOKENS).commit() }
  }

  override suspend fun readPendingAuthorization(): PendingSpotifyAuthorization? = read(KEY_PENDING)

  override suspend fun writePendingAuthorization(pending: PendingSpotifyAuthorization) {
    require(pending.state.isNotBlank() && pending.codeVerifier.isNotBlank())
    write(KEY_PENDING, json.encodeToString(pending))
  }

  override suspend fun clearPendingAuthorization() {
    mutex.withLock { preferences.edit().remove(KEY_PENDING).commit() }
  }

  override suspend fun clearAll() {
    mutex.withLock { preferences.edit().clear().commit() }
  }

  private suspend inline fun <reified T> read(key: String): T? = withContext(Dispatchers.IO) {
    mutex.withLock {
      val encrypted = preferences.getString(key, null) ?: return@withLock null
      runCatching {
        json.decodeFromString<T>(decrypt(encrypted, key))
      }.getOrElse {
        preferences.edit().remove(key).commit()
        null
      }
    }
  }

  private suspend fun write(key: String, plaintext: String) = withContext(Dispatchers.IO) {
    mutex.withLock {
      check(preferences.edit().putString(key, encrypt(plaintext, key)).commit()) {
        "Could not persist Spotify credentials"
      }
    }
  }

  private fun encrypt(plaintext: String, associatedKey: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    cipher.updateAAD(associatedKey.toByteArray(Charsets.UTF_8))
    val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    return listOf(cipher.iv, encrypted).joinToString(":") {
      Base64.encodeToString(it, Base64.NO_WRAP or Base64.NO_PADDING)
    }
  }

  private fun decrypt(encoded: String, associatedKey: String): String {
    val parts = encoded.split(':', limit = 2)
    require(parts.size == 2) { "Invalid encrypted credential" }
    val iv = Base64.decode(parts[0], Base64.NO_WRAP or Base64.NO_PADDING)
    val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP or Base64.NO_PADDING)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
    cipher.updateAAD(associatedKey.toByteArray(Charsets.UTF_8))
    return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
  }

  private fun getOrCreateKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(
        keyAlias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .build(),
    )
    return generator.generateKey()
  }

  companion object {
    const val PREFERENCES_NAME = "spotify_credentials_v2"
    private const val DEFAULT_KEY_ALIAS = "icy_lyrics_spotify_pkce_v2"
    private const val KEY_TOKENS = "tokens"
    private const val KEY_PENDING = "pending"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
  }
}

class InMemorySpotifyCredentialStore : SpotifyCredentialStore {
  private var tokens: SpotifyTokenSet? = null
  private var pending: PendingSpotifyAuthorization? = null

  override suspend fun readTokens() = tokens
  override suspend fun writeTokens(tokens: SpotifyTokenSet) { this.tokens = tokens }
  override suspend fun clearTokens() { tokens = null }
  override suspend fun readPendingAuthorization() = pending
  override suspend fun writePendingAuthorization(pending: PendingSpotifyAuthorization) { this.pending = pending }
  override suspend fun clearPendingAuthorization() { pending = null }
  override suspend fun clearAll() { tokens = null; pending = null }
}
