@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.icy.lyrics.ui

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.defaultTimeZone
import platform.Foundation.localeIdentifier

/** Foundation supplies timezone arithmetic; preserved Android profiles own all visible characters. */
internal class IosAndroidDateFormatter(
  assetLoader: (String) -> ByteArray,
  private val localeIdentifier: () -> String = { NSLocale.currentLocale.localeIdentifier },
  private val timeZone: () -> NSTimeZone = { NSTimeZone.defaultTimeZone },
) {
  private val profiles by lazy { AndroidDateProfiles(assetLoader("date/android36-medium-short.json").decodeToString()) }

  fun format(epochMs: Long): String {
    val calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)
    calendar.timeZone = timeZone()
    val fields = calendar.components(NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
      NSCalendarUnitHour or NSCalendarUnitMinute,
      fromDate = NSDate.dateWithTimeIntervalSince1970(epochMs / 1_000.0))
    return profiles.format(localeIdentifier(), AndroidDateFields(fields.year.toInt(), fields.month.toInt(),
      fields.day.toInt(), fields.hour.toInt(), fields.minute.toInt()))
  }
}
