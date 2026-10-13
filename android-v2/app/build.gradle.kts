import java.util.Properties
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
  val file = rootProject.file("local.properties")
  if (file.exists()) file.inputStream().use(::load)
}

val signingPropertiesFile = rootProject.file("signing.properties")
val signingProperties = Properties().apply {
  if (signingPropertiesFile.exists()) signingPropertiesFile.inputStream().use(::load)
  // CI supplies secrets through its environment, never through source control.
  mapOf(
    "storeFile" to "ICY_ANDROID_KEYSTORE",
    "storePassword" to "ICY_ANDROID_STORE_PASSWORD",
    "keyAlias" to "ICY_ANDROID_KEY_ALIAS",
    "keyPassword" to "ICY_ANDROID_KEY_PASSWORD",
  ).forEach { (property, environment) ->
    providers.environmentVariable(environment).orNull?.takeIf(String::isNotBlank)?.let {
      setProperty(property, it)
    }
  }
}

fun Properties.requiredSigningProperty(name: String): String =
  getProperty(name)?.takeIf(String::isNotBlank)
    ?: error("Missing '$name' in ${signingPropertiesFile.path}")

val phoneRelease = Properties().apply {
  rootProject.file("phone-release.properties").inputStream().use(::load)
}
val phoneVersionName = phoneRelease.getProperty("versionName")
  ?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
  ?: error("phone-release.properties requires a semantic versionName")
val phoneVersionCode = phoneRelease.getProperty("versionCode")?.toIntOrNull()
  ?.takeIf { it > 10 } ?: error("Phone versionCode must be greater than the released TV code 10")
// Explicit opt-in for locally shared test APKs; public release builds remain unlabelled.
val icyTestBuildLabel = providers.gradleProperty("icyTestBuildLabel").orNull.orEmpty()
  .replace(Regex("[^A-Za-z0-9._-]"), "-")
  .take(48)

android {
  (sourceSets as org.gradle.api.NamedDomainObjectContainer<com.android.build.api.dsl.AndroidSourceSet>).getByName("main").kotlin.srcDirs(
    "../../iOS/shared/ui/src/commonMain/kotlin",
    "../../iOS/shared/ui/src/androidMain/kotlin",
  )
  (sourceSets as org.gradle.api.NamedDomainObjectContainer<com.android.build.api.dsl.AndroidSourceSet>).getByName("androidTest").kotlin.srcDir("../../iOS/tests/android")
  (sourceSets as org.gradle.api.NamedDomainObjectContainer<com.android.build.api.dsl.AndroidSourceSet>).getByName("test").kotlin.srcDirs(
    "../../iOS/tests/unit",
    "../../iOS/shared/ui/src/commonTest/kotlin",
  )
  namespace = "com.icy.lyrics"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.icy.lyrics"
    minSdk = 33
    targetSdk = 36
    // Code 10 was used by the TV release under the same Play application ID.
    versionCode = phoneVersionCode
    versionName = phoneVersionName
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("String", "TEST_BUILD_LABEL", "\"$icyTestBuildLabel\"")

    buildConfigField(
      "String",
      "SPOTIFY_CLIENT_ID",
      "\"${providers.gradleProperty("spotifyClientId").orNull ?: localProperties.getProperty("spotifyClientId", "")}\"",
    )
  }

  val uploadSigningConfig = if (signingProperties.isNotEmpty()) {
    signingConfigs.create("upload") {
      storeFile = file(signingProperties.requiredSigningProperty("storeFile"))
      storePassword = signingProperties.requiredSigningProperty("storePassword")
      keyAlias = signingProperties.requiredSigningProperty("keyAlias")
      keyPassword = signingProperties.requiredSigningProperty("keyPassword")
    }
  } else {
    null
  }

  flavorDimensions += "distribution"
  productFlavors {
    create("play") {
      dimension = "distribution"
      uploadSigningConfig?.let { signingConfig = it }
      buildConfigField("boolean", "ANDROID_AUTO_INCLUDED", "false")
    }
    create("tv") {
      dimension = "distribution"
      minSdk = 30
      versionCode = 10
      versionName = "1.2.3"
      uploadSigningConfig?.let { signingConfig = it }
      buildConfigField("boolean", "ANDROID_AUTO_INCLUDED", "false")
    }
    create("github") {
      dimension = "distribution"
      applicationIdSuffix = ".github"
      uploadSigningConfig?.let { signingConfig = it }
      buildConfigField("boolean", "ANDROID_AUTO_INCLUDED", "true")
    }
  }

  buildFeatures {
    buildConfig = true
    compose = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

  packaging.resources.excludes += setOf(
    "META-INF/AL2.0",
    "META-INF/LGPL2.1",
  )

}

dependencies {
  implementation(project(":core:lyrics"))
  implementation(project(":core:platform"))

  add("githubImplementation", project(":android-auto"))

  implementation(platform("androidx.compose:compose-bom:2026.04.01"))
  implementation("androidx.activity:activity-compose:1.12.0")
  implementation("androidx.browser:browser:1.9.0")
  implementation("androidx.core:core-ktx:1.17.0")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-graphics")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  // Common UI source includes the iOS date-profile parser; this version is already used by core:platform.
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

  debugImplementation("androidx.compose.ui:ui-tooling")
  debugImplementation("androidx.compose.ui:ui-test-manifest")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.21")

  androidTestImplementation("androidx.test.ext:junit:1.3.0")
  androidTestImplementation("androidx.test:core:1.7.0")
  androidTestImplementation("androidx.test:runner:1.7.0")
  androidTestImplementation("com.squareup.okhttp3:okhttp:5.3.2")
  androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
  androidTestImplementation("com.squareup.okhttp3:okhttp-tls:5.3.2")
  androidTestImplementation(platform("androidx.compose:compose-bom:2026.04.01"))
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

fun registerNonCarDistributionBoundary(
  taskName: String,
  distributionName: String,
  runtimeConfigurations: List<String>,
) = tasks.register(taskName) {
  group = "verification"
  description =
    "Verifies that $distributionName variants contain no Android Auto components."

  doLast {
    val leaks = runtimeConfigurations.flatMap { configurationName ->
      configurations.getByName(configurationName)
        .incoming
        .resolutionResult
        .allComponents
        .mapNotNull { component ->
          val identifier = component.id
          val isAutoModule =
            identifier is ProjectComponentIdentifier &&
              identifier.projectPath in setOf(":android-auto", ":local-private-feature")
          val isAndroidForCars =
            identifier is ModuleComponentIdentifier &&
              (identifier.group == "androidx.car" || identifier.group.startsWith("androidx.car."))
          if (isAutoModule || isAndroidForCars) {
            "$configurationName -> ${identifier.displayName}"
          } else {
            null
          }
        }
    }

    check(leaks.isEmpty()) {
      "Android Auto components leaked into $distributionName configurations: " +
        leaks.joinToString()
    }
  }
}

val verifyPlayDistributionBoundary = registerNonCarDistributionBoundary(
  taskName = "verifyPlayDistributionBoundary",
  distributionName = "Play",
  runtimeConfigurations = listOf(
    "playDebugRuntimeClasspath",
    "playReleaseRuntimeClasspath",
  ),
)

val verifyTvDistributionBoundary = registerNonCarDistributionBoundary(
  taskName = "verifyTvDistributionBoundary",
  distributionName = "TV",
  runtimeConfigurations = listOf(
    "tvDebugRuntimeClasspath",
    "tvReleaseRuntimeClasspath",
  ),
)

val verifyPhoneDistributionParity = tasks.register("verifyPhoneDistributionParity") {
  group = "verification"
  description = "Requires matching phone versions/platforms and GitHub-only Android Auto."
  dependsOn(verifyPlayDistributionBoundary)
  doLast {
    val play = android.productFlavors.getByName("play")
    val github = android.productFlavors.getByName("github")
    check(listOf(play, github).all {
      it.versionName == null && it.versionNameSuffix == null && it.versionCode == null &&
        it.minSdk == null && it.targetSdk == null
    }) { "Phone channels must inherit their version and platform from defaultConfig." }
    check(play.signingConfig == github.signingConfig) {
      "Phone release channels must use the configured release signing identity."
    }
    listOf("githubDebugRuntimeClasspath", "githubReleaseRuntimeClasspath").forEach { name ->
      check(configurations.getByName(name).incoming.resolutionResult.allComponents.any {
        val id = it.id
        id is ProjectComponentIdentifier && id.projectPath == ":android-auto"
      }) { "$name is missing Android Auto." }
    }
    check(android.productFlavors.findByName("personal") == null) {
      "The obsolete personal distribution must not be configured."
    }
  }
}

tasks.register("assemblePhoneReleasePair") {
  group = "build"
  description = "Tests and packages BOTH phone releases together; never publishes a single-channel update."
  dependsOn(
    verifyPhoneDistributionParity,
    ":core:lyrics:testDebugUnitTest", ":core:platform:testDebugUnitTest",
    "testPlayDebugUnitTest", "testGithubDebugUnitTest",
    "lintPlayRelease", "lintGithubRelease",
    "assembleGithubRelease", "assemblePlayRelease", "bundlePlayRelease",
  )
}

tasks.named("check").configure {
  dependsOn(verifyPhoneDistributionParity, verifyTvDistributionBoundary)
}
