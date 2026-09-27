package com.icy.lyrics

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class TvManifestContractTest {
  @Test
  fun tvManifestIsLeanbackOnlyAndDisablesPhoneOnlyActivities() {
    val manifest = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
    }.newDocumentBuilder().parse(tvManifestFile())

    val features = manifest.getElementsByTagName("uses-feature")
      .elements()
      .associate { it.androidAttribute("name") to it.androidAttribute("required") }
    assertEquals("true", features["android.software.leanback"])
    assertEquals("false", features["android.hardware.touchscreen"])

    val application = manifest.getElementsByTagName("application").item(0) as Element
    assertEquals("@drawable/tv_banner", application.androidAttribute("banner"))

    val activities = manifest.getElementsByTagName("activity").elements()
      .associateBy { it.androidAttribute("name") }
    assertEquals("remove", activities.getValue(".MainActivity").toolsAttribute("node"))
    assertEquals(
      "remove",
      activities.getValue(".creator.LyricCreatorActivity").toolsAttribute("node"),
    )

    val tvActivity = activities[".TvMainActivity"]
    assertNotNull(tvActivity)
    requireNotNull(tvActivity)
    assertEquals("true", tvActivity.androidAttribute("exported"))
    assertEquals("landscape", tvActivity.androidAttribute("screenOrientation"))
    val categories = tvActivity.getElementsByTagName("category").elements()
      .map { it.androidAttribute("name") }
    assertTrue("android.intent.category.LEANBACK_LAUNCHER" in categories)

    val rawManifest = tvManifestFile().readText()
    assertFalse(rawManifest.contains("android.hardware.type.automotive"))
    assertFalse(rawManifest.contains("com.google.android.gms.car.application"))
    assertFalse(rawManifest.contains("automotive_app_desc"))
  }

  private fun tvManifestFile(): File = sequenceOf(
    File("src/tv/AndroidManifest.xml"),
    File("app/src/tv/AndroidManifest.xml"),
  ).firstOrNull(File::isFile) ?: error("Could not locate the TV source-set manifest")

  private fun org.w3c.dom.NodeList.elements(): List<Element> =
    (0 until length).map { item(it) as Element }

  private fun Element.androidAttribute(name: String): String =
    getAttributeNS(ANDROID_NAMESPACE, name)

  private fun Element.toolsAttribute(name: String): String =
    getAttributeNS(TOOLS_NAMESPACE, name)

  private companion object {
    const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    const val TOOLS_NAMESPACE = "http://schemas.android.com/tools"
  }
}
