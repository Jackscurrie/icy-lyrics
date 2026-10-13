package com.icy.lyrics

import java.io.File
import java.util.Properties
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/** Manifest/source-set contracts complement the resolved-dependency Gradle guards. */
class PhoneDistributionContractTest {
  private val appDirectory = sequenceOf(File("."), File("app"), File("android-v2/app"))
    .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
    ?.canonicalFile ?: error("Could not locate the app source directory")

  @Test
  fun sharedPhoneManifestHasNoAndroidAutoEntryPoints() {
    val manifest = manifest(File(appDirectory, "src/main/AndroidManifest.xml"))
    val names = manifest.getElementsByTagName("*").elements()
      .map { it.androidAttribute("name") }
    assertFalse("android.intent.category.CAR_LAUNCHER" in names)
    assertFalse("android.media.browse.MediaBrowserService" in names)
    assertFalse("com.google.android.gms.car.application" in names)
    assertFalse("com.icy.lyrics.OPTIONAL_MIXED_MODE_PRESENTATION" in names)
    assertFalse(names.any { it.startsWith("com.icy.lyrics.androidauto.") })
  }

  @Test
  fun overlayAndCreatorAreSharedPhoneComponentsWithOneAppBrand() {
    val manifest = manifest(File(appDirectory, "src/main/AndroidManifest.xml"))
    val creator = manifest.getElementsByTagName("activity").elements()
      .single { it.androidAttribute("name") == ".creator.LyricCreatorActivity" }
    assertEquals("false", creator.androidAttribute("exported"))
    val overlay = manifest.getElementsByTagName("service").elements()
      .single { it.androidAttribute("name") == ".overlay.LyricsOverlayService" }
    assertEquals("false", overlay.androidAttribute("exported"))
    assertEquals("specialUse", overlay.androidAttribute("foregroundServiceType"))
    val permissions = manifest.getElementsByTagName("uses-permission").elements()
      .map { it.androidAttribute("name") }
    assertTrue("android.permission.SYSTEM_ALERT_WINDOW" in permissions)

    val strings = manifest(File(appDirectory, "src/main/res/values/strings.xml"))
    val appName = strings.getElementsByTagName("string").elements()
      .single { it.getAttribute("name") == "app_name" }
    assertEquals("Icy Lyrics", appName.textContent)

    // Neither channel may quietly remove or replace shared phone components.
    listOf("play", "github").forEach { flavor ->
      val flavorManifest = File(appDirectory, "src/$flavor/AndroidManifest.xml")
      if (flavorManifest.isFile) {
        val overrides = manifest(flavorManifest).getElementsByTagName("*").elements()
        assertFalse(overrides.any {
          it.androidAttribute("name") in setOf(
            ".creator.LyricCreatorActivity",
            "com.icy.lyrics.creator.LyricCreatorActivity",
            ".overlay.LyricsOverlayService",
            "com.icy.lyrics.overlay.LyricsOverlayService",
          )
        })
      }
    }
  }

  @Test
  fun androidAutoModuleOwnsTheParkedLauncherAndDrivingMediaService() {
    val moduleDirectory = File(appDirectory.parentFile, "android-auto")
    val manifest = manifest(File(moduleDirectory, "src/main/AndroidManifest.xml"))
    val metadata = manifest.getElementsByTagName("meta-data").elements()
      .associateBy { it.androidAttribute("name") }
    assertNotNull(metadata["com.google.android.gms.car.application"])
    assertEquals(
      "com.icy.lyrics.androidauto.AndroidAutoMixedModePresentation",
      metadata.getValue("com.icy.lyrics.OPTIONAL_MIXED_MODE_PRESENTATION")
        .androidAttribute("value"),
    )
    val parkedActivity = manifest.getElementsByTagName("activity").elements()
      .single { it.androidAttribute("name") == "com.icy.lyrics.MainActivity" }
    assertTrue(parkedActivity.getElementsByTagName("category").elements().any {
      it.androidAttribute("name") == "android.intent.category.CAR_LAUNCHER"
    })
    val drivingService = manifest.getElementsByTagName("service").elements()
      .single { it.androidAttribute("name") == "com.icy.lyrics.androidauto.IcyLyricsCarMediaService" }
    assertEquals("true", drivingService.androidAttribute("exported"))
    assertTrue(drivingService.getElementsByTagName("action").elements().any {
      it.androidAttribute("name") == "android.media.browse.MediaBrowserService"
    })
    val automotiveDescription = manifest(File(moduleDirectory, "src/main/res/xml/automotive_app_desc.xml"))
    assertEquals(
      listOf("media"),
      automotiveDescription.getElementsByTagName("uses").elements().map { it.getAttribute("name") },
    )
  }

  @Test
  fun compiledFlavorFlagAndPhoneVersionsMatchTheirDistribution() {
    assertEquals(BuildConfig.FLAVOR == "github", BuildConfig.ANDROID_AUTO_INCLUDED)
    if (BuildConfig.FLAVOR in setOf("play", "github")) {
      val release = Properties().apply {
        File(appDirectory.parentFile, "phone-release.properties").inputStream().use(::load)
      }
      assertEquals(release.getProperty("versionName"), BuildConfig.VERSION_NAME)
      assertEquals(release.getProperty("versionCode").toInt(), BuildConfig.VERSION_CODE)
      assertEquals(
        if (BuildConfig.FLAVOR == "github") "com.icy.lyrics.github" else "com.icy.lyrics",
        BuildConfig.APPLICATION_ID,
      )
    }
  }

  private fun manifest(file: File): Document = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
    setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
  }.newDocumentBuilder().parse(file)

  private fun NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

  private fun Element.androidAttribute(name: String): String =
    getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
