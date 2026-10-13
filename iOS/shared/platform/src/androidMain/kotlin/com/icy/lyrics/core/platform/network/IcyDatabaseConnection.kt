package com.icy.lyrics.core.platform.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Keep the device's DNS, VPN/proxy, certificates and connection pool. OkHttp's Happy Eyeballs
 * races TCP connections (not HTTP requests), so a broken IPv6 route cannot consume the whole
 * lyrics deadline before a working IPv4 route is tried. Neither address family is disabled.
 */
fun icyDatabaseHttpClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
  .fastFallback(true)
  .connectTimeout(5L, TimeUnit.SECONDS)
  .followRedirects(false)
  .followSslRedirects(false)
  .build()
