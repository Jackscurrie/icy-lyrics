plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.plugin.serialization")
  id("com.google.devtools.ksp")
}

android {
  namespace = "com.icy.lyrics.core.platform"
  compileSdk = 36

  defaultConfig { minSdk = 33 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
}

dependencies {
  api(project(":core:lyrics"))
  implementation("androidx.core:core-ktx:1.17.0")
  implementation("androidx.browser:browser:1.9.0")
  implementation("androidx.datastore:datastore-preferences:1.2.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
  implementation("androidx.room:room-runtime:2.8.3")
  implementation("androidx.room:room-ktx:2.8.3")
  ksp("androidx.room:room-compiler:2.8.3")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

  testImplementation("junit:junit:4.13.2")
  testImplementation("androidx.room:room-testing:2.8.3")
  testImplementation("androidx.test:core:1.7.0")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
  testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
  testImplementation("org.robolectric:robolectric:4.16.1")
}
