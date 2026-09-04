@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.icy.lyrics.ui.parity

import androidx.compose.runtime.Composable
import com.icy.lyrics.ui.IcyParityFixtures
import com.icy.lyrics.ui.IcyUiPlatform
import com.icy.lyrics.ui.IosIcyUiPlatform
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localeIdentifier
import platform.Foundation.timeZoneWithName
import platform.posix.getenv

/** Test-only filesystem adapter. Production still defaults to the app bundle's IcyAssets. */
internal class DeterministicFixtureAssets {
  private val root = requiredEnvironment("ICY_DETERMINISTIC_ASSET_ROOT").toPath(normalize = true)
  val outputRoot = requiredEnvironment("ICY_DETERMINISTIC_OUTPUT_ROOT").toPath(normalize = true)
  val loadedAssetSha256 = linkedMapOf<String, String>()
  val formattedDates = linkedMapOf<String, String>()
  private val dateFormatter = NSDateFormatter().apply {
    // Same styles as production, with the reference environment explicitly selected.
    // NSDateFormatter's punctuation and Unicode spaces remain untouched and visible in the PNG.
    locale = NSLocale("en_US")
    timeZone = requireNotNull(NSTimeZone.timeZoneWithName("America/Los_Angeles"))
    dateStyle = NSDateFormatterMediumStyle
    timeStyle = NSDateFormatterShortStyle
  }
  val locale: String get() = dateFormatter.locale.localeIdentifier
  val timezone: String get() = dateFormatter.timeZone.name

  fun read(relativePath: String): ByteArray {
    require(relativePath.isNotEmpty() && !relativePath.startsWith('/') && '\\' !in relativePath)
    require(relativePath.split('/').none { it == ".." || it == "." })
    return FileSystem.SYSTEM.source(root / relativePath).buffer().use { it.readByteArray() }.also {
      loadedAssetSha256[relativePath] = it.toByteString().sha256().hex()
    }
  }

  fun verifyOriginalFontBytes() {
    val primary = Json.parseToJsonElement(read("font/PROVENANCE.json").decodeToString()).jsonObject
    verifyFont("Roboto-Regular.ttf", requireNotNull(primary["sha256"]).jsonPrimitive.content)
    val fallback = Json.parseToJsonElement(read("font/FALLBACK-PROVENANCE.json").decodeToString()).jsonObject
    for (entry in requireNotNull(fallback["fonts"]).jsonArray) {
      val font = entry.jsonObject
      verifyFont(requireNotNull(font["file"]).jsonPrimitive.content, requireNotNull(font["sha256"]).jsonPrimitive.content)
    }
  }

  private fun verifyFont(name: String, expected: String) {
    val actual = read("font/$name").toByteString().sha256().hex()
    check(actual == expected) { "Bundled Android font differs from recorded provenance: $name" }
  }

  fun platform(): IcyUiPlatform {
    val native = IosIcyUiPlatform("fixture", IcyParityFixtures.FRAME_TIME_NANOS, ::read)
    return object : IcyUiPlatform by native {
      // An offscreen scene has no UIKit navigation controller. This has no drawn representation.
      @Composable override fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

      override fun formatDateTime(epochMs: Long): String = dateFormatter.stringFromDate(
        NSDate.dateWithTimeIntervalSince1970(epochMs / 1_000.0),
      ).also { formattedDates[epochMs.toString()] = it }
    }
  }

  fun write(path: Path, bytes: ByteArray) {
    FileSystem.SYSTEM.createDirectories(requireNotNull(path.parent))
    FileSystem.SYSTEM.sink(path).buffer().use { it.write(bytes) }
  }

  private fun requiredEnvironment(name: String): String =
    requireNotNull(getenv(name)?.toKString()?.takeIf(String::isNotBlank)) {
      "$name must be supplied by the iosSimulatorArm64Test Gradle task"
    }
}
