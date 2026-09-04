package com.icy.lyrics.ui

import org.jetbrains.skia.RuntimeEffect
import kotlin.test.Test

class IosArtworkShaderTest {
  @Test fun originalAndroidRuntimeShaderCompilesOnSkia() {
    val effect = RuntimeEffect.makeForShader(KAWARP_SHADER)
    effect.close()
  }
}
