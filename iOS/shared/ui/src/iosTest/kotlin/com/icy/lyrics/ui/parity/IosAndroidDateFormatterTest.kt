@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.icy.lyrics.ui.parity

import com.icy.lyrics.ui.IosAndroidDateFormatter
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSTimeZone
import platform.Foundation.timeZoneWithName
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals

class IosAndroidDateFormatterTest {
  @Test fun foundationCalendarAndMeasuredProfilesMatch144OriginalAndroidStrings() {
    val assets = DeterministicFixtureAssets()
    val fixturePath = requireNotNull(getenv("ICY_ANDROID_DATE_REFERENCE")?.toKString()).toPath()
    val fixture = FileSystem.SYSTEM.source(fixturePath).buffer().use { it.readUtf8() }
    val samples = Json.parseToJsonElement(fixture).jsonObject.getValue("samples").jsonArray
    assertEquals(144, samples.size)
    val observations = samples.map { value ->
      val sample = value.jsonObject
      val locale = sample.getValue("locale").jsonPrimitive.content
      val zone = sample.getValue("timezone").jsonPrimitive.content
      val epoch = sample.getValue("epochMs").jsonPrimitive.long
      val formatter = IosAndroidDateFormatter(assets::read, { locale }, {
        requireNotNull(NSTimeZone.timeZoneWithName(zone))
      })
      val actual = formatter.format(epoch)
      buildJsonObject {
        put("locale", locale); put("timezone", zone); put("epochMs", epoch)
        put("expected", sample.getValue("combined").jsonPrimitive.content); put("actual", actual)
      }
    }
    val report = buildJsonObject {
      put("schemaVersion", 1); put("backend", "Foundation Gregorian calendar with preserved Android locale profiles")
      put("scope", "Exact Unicode string equality; does not assert font shaping or screenshot parity")
      put("samples", JsonArray(observations))
    }
    assets.write(assets.outputRoot / "native-date-format" / "report.json", report.toString().encodeToByteArray())
    observations.forEach { row ->
      assertEquals(row.getValue("expected").jsonPrimitive.content, row.getValue("actual").jsonPrimitive.content,
        row.toString())
    }
  }
}
