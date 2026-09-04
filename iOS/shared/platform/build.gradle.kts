plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
  id("com.google.devtools.ksp")
}
ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}
kotlin {
  iosArm64()
  iosSimulatorArm64()
  jvmToolchain(17)
  sourceSets {
    commonMain.dependencies {
      api(project(":shared:lyrics"))
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
      implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
      implementation("androidx.room:room-runtime:2.8.3")
      implementation("androidx.sqlite:sqlite-bundled:2.6.1")
      implementation("io.ktor:ktor-client-core:3.3.3")
      implementation("com.squareup.okio:okio:3.16.4")
    }
    iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.3.3") }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}
dependencies {
  add("kspIosArm64", "androidx.room:room-compiler:2.8.3")
  add("kspIosSimulatorArm64", "androidx.room:room-compiler:2.8.3")
}
