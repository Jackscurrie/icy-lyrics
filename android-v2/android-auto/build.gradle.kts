plugins {
  id("com.android.library")
}

android {
  namespace = "com.icy.lyrics.androidauto"
  compileSdk = 36

  defaultConfig {
    minSdk = 33
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
  // Exported only to the GitHub app flavor; never added to Play or TV.
  api("androidx.car.app:app:1.7.0")
  // Driving media browser and host-rendered media controls.
  api("androidx.media:media:1.8.0")
}
