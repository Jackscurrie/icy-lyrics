plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.plugin.serialization")
}

android {
  (sourceSets as org.gradle.api.NamedDomainObjectContainer<com.android.build.api.dsl.AndroidSourceSet>).getByName("main").kotlin.srcDirs(
    "../../../iOS/shared/lyrics/src/commonMain/kotlin",
    "../../../iOS/shared/lyrics/src/androidMain/kotlin",
  )
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
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
  implementation("io.github.pdvrieze.xmlutil:core:0.91.3")
  implementation("it.krzeminski:snakeyaml-engine-kmp:4.0.1")
  implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
