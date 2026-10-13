package com.icy.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDiagnosticEnvironmentTest {
  @Test
  fun `test build is identifiable without changing public version`() {
    val report = formatDiagnosticEnvironment(
      "1.3.0", 11, "play", "release", "test2", 36,
      "Wi-Fi + VPN; validated=true; metered=false; captivePortal=false; privateDns=true",
    )
    assertTrue(report.contains("1.3.0 (version code 11)"))
    assertTrue(report.contains("Build: play/release [test2]"))
    assertTrue(report.contains("Android API: 36"))
    assertTrue(report.contains("Network (snapshot when diagnostics refreshed): Wi-Fi + VPN"))
  }

  @Test
  fun `public build does not claim a test label`() {
    val report = formatDiagnosticEnvironment("1.3.0", 11, "play", "release", "", 33, "no active network")
    assertTrue(report.contains("Build: play/release\n"))
    assertFalse(report.contains("[]"))
    assertTrue(report.contains("no active network"))
  }
}
