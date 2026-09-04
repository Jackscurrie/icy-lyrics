package com.icy.lyrics.parity

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.text.DateFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in measurement of the exact java.text API used by the frozen Android library screen. */
@RunWith(AndroidJUnit4::class)
class DateFormatParityProbeTest {
  @Test fun captureAndroidPatternsAndSymbols() {
    val runId = InstrumentationRegistry.getArguments().getString("dateProbeRunId")
    assumeTrue("Date-format inventory is an explicit emulator probe", !runId.isNullOrBlank())
    require(runId!!.matches(Regex("[A-Za-z0-9_-]+")))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "date-format/$runId").apply { mkdirs() }
    val locales = DateFormat.getAvailableLocales().distinctBy(Locale::toLanguageTag).sortedBy(Locale::toLanguageTag)
    val inventory = JSONArray()
    for (locale in locales) inventory.put(describe(locale))
    val source = JSONObject()
      .put("schemaVersion", 1).put("sdk", Build.VERSION.SDK_INT).put("buildFingerprint", Build.FINGERPRINT)
      .put("javaRuntimeVersion", System.getProperty("java.runtime.version"))
      .put("icuVersion", android.icu.util.VersionInfo.ICU_VERSION.toString())
      .put("cldrVersion", android.icu.util.LocaleData.getCLDRVersion().toString())
      .put("api", "java.text.DateFormat.getDateTimeInstance(MEDIUM, SHORT, locale)")
      .put("requestedTimezone", "America/Los_Angeles").put("localeCount", locales.size)
      .put("defaultLocale", Locale.getDefault().toLanguageTag())
      .put("defaultFormatLocale", Locale.getDefault(Locale.Category.FORMAT).toLanguageTag())
      .put("defaultTimezone", TimeZone.getDefault().id)
      .put("locales", inventory)
    File(directory, "patterns-and-symbols.json").writeText(source.toString(2))

    val samples = JSONArray()
    val originalLocale = Locale.getDefault()
    val originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT)
    val originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY)
    val originalTimezone = TimeZone.getDefault()
    try {
      for (tag in SAMPLE_LOCALES) {
        val locale = Locale.forLanguageTag(tag)
        for (zoneId in listOf("America/Los_Angeles", "UTC")) {
          val zone = TimeZone.getTimeZone(zoneId)
          val combined = formatter(locale).apply { timeZone = zone }
          val dateOnly = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = zone }
          val timeOnly = DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply { timeZone = zone }
          Locale.setDefault(locale)
          TimeZone.setDefault(zone)
          for (epoch in SAMPLE_EPOCHS) {
            val instant = Date(epoch)
            val actual = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(instant)
            assertEquals("Explicit locale must match the unmodified production API", actual, combined.format(instant))
            samples.put(JSONObject().put("locale", tag).put("timezone", zoneId).put("epochMs", epoch)
              .put("combined", actual).put("codePoints", codePoints(actual))
              .put("dateOnly", dateOnly.format(instant)).put("timeOnly", timeOnly.format(instant))
              .put("pattern", combined.toPattern()).put("calendarType", combined.calendar.calendarType))
          }
        }
      }
    } finally {
      Locale.setDefault(originalLocale)
      Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale)
      Locale.setDefault(Locale.Category.DISPLAY, originalDisplayLocale)
      TimeZone.setDefault(originalTimezone)
    }
    File(directory, "samples.json").writeText(JSONObject().put("schemaVersion", 1).put("runId", runId)
      .put("sampleLocales", JSONArray(SAMPLE_LOCALES)).put("samples", samples)
      .put("productionApiVerifiedForEverySample", true).put("globalDefaultsRestored", true).toString(2))
  }

  private fun formatter(locale: Locale): SimpleDateFormat =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) as SimpleDateFormat

  private fun describe(locale: Locale): JSONObject {
    val combined = formatter(locale)
    val dateOnly = DateFormat.getDateInstance(DateFormat.MEDIUM, locale) as SimpleDateFormat
    val timeOnly = DateFormat.getTimeInstance(DateFormat.SHORT, locale) as SimpleDateFormat
    val symbols = combined.dateFormatSymbols
    val digits = DecimalFormatSymbols.getInstance(locale)
    return JSONObject().put("locale", locale.toLanguageTag()).put("javaLocale", locale.toString())
      .put("combinedPattern", combined.toPattern()).put("datePattern", dateOnly.toPattern())
      .put("timePattern", timeOnly.toPattern()).put("localizedPatternChars", symbols.localPatternChars)
      .put("calendarType", combined.calendar.calendarType).put("calendarClass", combined.calendar.javaClass.name)
      .put("months", JSONArray(symbols.months)).put("shortMonths", JSONArray(symbols.shortMonths))
      .put("weekdays", JSONArray(symbols.weekdays)).put("shortWeekdays", JSONArray(symbols.shortWeekdays))
      .put("eras", JSONArray(symbols.eras)).put("amPmStrings", JSONArray(symbols.amPmStrings))
      .put("zeroDigit", digits.zeroDigit.toString()).put("numberFormatClass", combined.numberFormat.javaClass.name)
  }

  private fun codePoints(value: String): JSONArray = JSONArray().also { result ->
    var index = 0
    while (index < value.length) {
      val codePoint = value.codePointAt(index)
      result.put("U+" + codePoint.toString(16).uppercase(Locale.ROOT).padStart(4, '0'))
      index += Character.charCount(codePoint)
    }
  }

  companion object {
    private val SAMPLE_LOCALES = listOf("en-US", "en-CA", "en-GB", "fr-CA", "fr-FR", "de-DE", "es-ES",
      "pt-BR", "ru-RU", "ar-EG", "fa-IR", "hi-IN", "bn-BD", "th-TH", "ja-JP", "zh-CN", "zh-TW", "ko-KR")
    // September fixture, winter midnight, noon, and local spring DST boundary; all exact instants.
    private val SAMPLE_EPOCHS = listOf(1_788_436_800_000L, 1_767_513_600_000L, 1_767_556_800_000L, 1_773_007_200_000L)
  }
}
