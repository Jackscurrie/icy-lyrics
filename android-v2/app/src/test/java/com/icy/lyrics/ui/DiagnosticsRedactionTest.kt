package com.icy.lyrics.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRedactionTest {
  @Test
  fun `export redacts secrets from live provider attempts and errors`() {
    val diagnostics = LyricsDiagnosticsUi(
      attempts = listOf(
        ProviderAttemptUi(
          provider = "SPICY",
          outcome = "FAILED",
          source = "SPOTIFY",
          syncKind = "SYLLABLE",
          fromCache = true,
          retryAfterMs = 2_000L,
          message = "Authorization: Bearer live-secret-token https://example.test/?code=auth-code&state=oauth-state",
        ),
      ),
      error = "refresh_token=refresh-secret access_token=access-secret",
    )

    val exported = diagnostics.asText()

    assertFalse(exported.contains("live-secret-token"))
    assertFalse(exported.contains("auth-code"))
    assertFalse(exported.contains("oauth-state"))
    assertFalse(exported.contains("refresh-secret"))
    assertFalse(exported.contains("access-secret"))
    assertTrue(exported.contains("[redacted]"))
    assertTrue(exported.contains("SPICY: FAILED"))
    assertTrue(exported.contains("source=SPOTIFY"))
    assertTrue(exported.contains("sync=SYLLABLE"))
    assertTrue(exported.contains("cache=true"))
    assertTrue(exported.contains("retryAfter=2000ms"))
  }
}
