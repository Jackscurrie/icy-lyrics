package com.icy.lyrics.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPackagesTest {
  @Test
  fun `recognizes Spotify mobile and Android TV packages`() {
    assertTrue(SpotifyPackages.contains(SpotifyPackages.MOBILE))
    assertTrue(SpotifyPackages.contains(SpotifyPackages.ANDROID_TV))
  }

  @Test
  fun `rejects null unrelated and near-match packages`() {
    assertFalse(SpotifyPackages.contains(null))
    assertFalse(SpotifyPackages.contains("com.example.player"))
    assertFalse(SpotifyPackages.contains("com.spotify.music.beta"))
    assertFalse(SpotifyPackages.contains("COM.SPOTIFY.TV.ANDROID"))
  }
}
