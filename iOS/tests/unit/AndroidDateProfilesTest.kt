package com.icy.lyrics.ui

import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidDateProfilesTest {
  private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .first { File(it, "iOS/tests/fixtures/android36-date-samples.json").isFile }
  private val profileText = File(root, "iOS/shared/ui/assets/date/android36-medium-short.json").readText()

  @Test fun allMeasuredSamplesRetainExactAndroidPunctuationDigitsAndHourCycles() {
    val profiles = AndroidDateProfiles(profileText)
    val samples = Json.parseToJsonElement(File(root, "iOS/tests/fixtures/android36-date-samples.json").readText())
      .jsonObject.getValue("samples").jsonArray
    assertEquals(144, samples.size)
    samples.forEach { value ->
      val sample = value.jsonObject
      val fields = Instant.ofEpochMilli(sample.getValue("epochMs").jsonPrimitive.long)
        .atZone(ZoneId.of(sample.getValue("timezone").jsonPrimitive.content))
      assertEquals(sample.toString(), sample.getValue("combined").jsonPrimitive.content,
        profiles.format(sample.getValue("locale").jsonPrimitive.content,
          AndroidDateFields(fields.year, fields.monthValue, fields.dayOfMonth, fields.hour, fields.minute)))
    }
  }

  @Test fun measuredRegionAndScriptAliasesRemainDistinctFromExplicitFallback() {
    val profiles = AndroidDateProfiles(profileText)
    assertEquals("en-CA", profiles.resolvedLocale("en_CA"))
    assertEquals("en-US", profiles.resolvedLocale("en_US@calendar=gregorian"))
    assertEquals("en-GB", profiles.resolvedLocale("en-GB-u-hc-h12"))
    assertEquals("zh-Hans-CN", profiles.resolvedLocale("zh_CN"))
    assertEquals("zh-Hant-TW", profiles.resolvedLocale("zh-TW"))
    assertEquals("en", profiles.resolvedLocale("zz-ZZ"))
    assertEquals("fr", profiles.resolvedLocale("fr-ZZ"))
  }

  @Test fun unknownPatternFieldsFailInsteadOfSilentlyUsingAppleFormatting() {
    assertThrows(IllegalArgumentException::class.java) {
      AndroidDateProfiles(profileText.replace("MMM d, y h:mm a", "EEE MMM d, y h:mm a"))
    }
  }
}
