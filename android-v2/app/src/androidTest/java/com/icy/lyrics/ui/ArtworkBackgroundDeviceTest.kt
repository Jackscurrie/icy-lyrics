package com.icy.lyrics.ui

import android.graphics.RuntimeShader
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtworkBackgroundDeviceTest {
  @Test
  fun kawarpAgslCompilesOnSupportedAndroidRuntime() {
    assertNotNull(RuntimeShader(KAWARP_SHADER))
  }
}
