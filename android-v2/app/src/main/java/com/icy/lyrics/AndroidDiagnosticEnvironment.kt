package com.icy.lyrics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/** Reads system state, never sends a probe or includes network/device identifiers. */
internal object AndroidDiagnosticEnvironment {
  fun snapshot(context: Context): String {
    val network = runCatching {
      val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return@runCatching "unavailable"
      val active = manager.activeNetwork ?: return@runCatching "no active network"
      val capabilities = manager.getNetworkCapabilities(active)
        ?: return@runCatching "active network; capabilities unavailable"
      val transports = buildList {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
      }
      buildString {
        append(transports.ifEmpty { listOf("other") }.joinToString(" + "))
        append("; validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
        append("; metered=${!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}")
        append("; captivePortal=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)}")
        // The hostname, DNS addresses and VPN package are deliberately not collected.
        val privateDnsActive = manager.getLinkProperties(active)?.isPrivateDnsActive
        append("; privateDns=${privateDnsActive ?: "unknown"}")
      }
    }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
    return formatDiagnosticEnvironment(
      versionName = BuildConfig.VERSION_NAME,
      versionCode = BuildConfig.VERSION_CODE,
      flavor = BuildConfig.FLAVOR,
      buildType = BuildConfig.BUILD_TYPE,
      testLabel = BuildConfig.TEST_BUILD_LABEL,
      androidApi = Build.VERSION.SDK_INT,
      network = network,
    )
  }
}

/** Pure formatting kept separate so report identity has a JVM regression test. */
internal fun formatDiagnosticEnvironment(
  versionName: String,
  versionCode: Int,
  flavor: String,
  buildType: String,
  testLabel: String,
  androidApi: Int,
  network: String,
): String = buildString {
  appendLine("App: Icy Lyrics Android $versionName (version code $versionCode)")
  append("Build: $flavor/$buildType")
  if (testLabel.isNotBlank()) append(" [$testLabel]")
  appendLine()
  appendLine("Android API: $androidApi")
  append("Network (snapshot when diagnostics refreshed): $network")
}
