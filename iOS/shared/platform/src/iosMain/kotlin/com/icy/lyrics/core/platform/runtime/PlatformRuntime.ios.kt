package com.icy.lyrics.core.platform.runtime

import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.NSTimeZone
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneForSecondsFromGMT

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfd(value: String): String = (value as NSString).decomposedStringWithCanonicalMapping

internal actual fun parseHttpDate(value: String): Long? {
  val formatter = NSDateFormatter().apply {
    locale = NSLocale("en_US_POSIX")
    timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
    dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
    lenient = false
  }
  return formatter.dateFromString(value)?.timeIntervalSince1970?.times(1_000.0)?.toLong()
}
