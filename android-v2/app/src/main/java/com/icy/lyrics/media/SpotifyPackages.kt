package com.icy.lyrics.media

internal object SpotifyPackages {
  const val MOBILE = "com.spotify.music"
  const val ANDROID_TV = "com.spotify.tv.android"

  private val supported = setOf(MOBILE, ANDROID_TV)

  fun contains(packageName: String?): Boolean = packageName in supported
}
