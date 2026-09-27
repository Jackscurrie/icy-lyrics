package com.icy.lyrics.ui

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtworkBackgroundDeviceTest {
  @Test
  @SdkSuppress(minSdkVersion = 33)
  fun kawarpAgslCompilesOnSupportedAndroidRuntime() {
    assertNotNull(RuntimeShader(KAWARP_SHADER))
  }

  @Test
  @SdkSuppress(minSdkVersion = 30)
  fun hardwareArtworkIsCopiedBeforeCpuPreprocessing() {
    val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    val hardware = requireNotNull(source.copy(Bitmap.Config.HARDWARE, false))

    val processed = preprocessArtwork(hardware)

    assertEquals(BLUR_SIZE, processed.width)
    assertEquals(BLUR_SIZE, processed.height)
    assertEquals(Bitmap.Config.ARGB_8888, processed.config)
    processed.recycle()
    hardware.recycle()
    source.recycle()
  }
}
