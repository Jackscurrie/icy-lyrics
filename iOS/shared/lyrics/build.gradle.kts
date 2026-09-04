plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
}
kotlin {
  iosArm64()
  iosSimulatorArm64()
  jvm("verification")
  jvmToolchain(17)
  sourceSets {
    commonMain.dependencies {
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
      implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
      implementation("io.github.pdvrieze.xmlutil:core:0.91.3")
      implementation("it.krzeminski:snakeyaml-engine-kmp:4.0.1")
      implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    }
  }
}
