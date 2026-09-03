plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.plugin.serialization")
}

android {
  namespace = "com.icy.lyrics.core.lyrics"
  compileSdk = 36

  defaultConfig { minSdk = 33 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
  implementation("org.snakeyaml:snakeyaml-engine:2.9")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
