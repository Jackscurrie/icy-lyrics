package com.icy.lyrics.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class LegalInfoTest {
  @Test
  fun `legal copy includes required attribution and modification notice`() {
    val copy = listOf(
      LegalInfo.DEVELOPER,
      LegalInfo.UPSTREAM_CREATOR,
      LegalInfo.MODIFICATION_NOTICE,
      LegalInfo.LICENSE_NOTICE,
      LegalInfo.NON_AFFILIATION_NOTICE,
    ).joinToString(" ")

    assertTrue(copy.contains("Jackscurrie"))
    assertTrue(copy.contains("Spikerko"))
    assertTrue(copy.contains("Spicy Lyrics"))
    assertTrue(copy.contains("2026"))
    assertTrue(copy.contains("GNU Affero General Public License"))
    assertTrue(copy.contains("any later version"))
    assertTrue(copy.contains("not affiliated"))
    assertTrue(copy.contains("Spotify"))
    assertTrue(copy.contains("Apple"))
    assertTrue(copy.contains("LRCLIB"))
  }

  @Test
  fun `published legal links are secure and explicit`() {
    assertTrue(LegalInfo.PRIVACY_POLICY_URL.startsWith("https://"))
    assertTrue(LegalInfo.LEGAL_URL.startsWith("https://"))
    assertTrue(LegalInfo.UPSTREAM_REPOSITORY_URL == "https://github.com/spikerko/spicy-lyrics")
  }
}
