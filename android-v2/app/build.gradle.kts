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
}

fun Properties.requiredSigningProperty(name: String): String =
  getProperty(name)?.takeIf(String::isNotBlank)
    ?: error("Missing '$name' in ${signingPropertiesFile.path}")

val privateFeatureProject = findProject(":local-private-feature")

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
    versionCode = 8
    versionName = "1.2.2"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField(
      "String",
      "SPOTIFY_CLIENT_ID",
      "\"${providers.gradleProperty("spotifyClientId").orNull ?: localProperties.getProperty("spotifyClientId", "")}\"",
    )
  }

  val uploadSigningConfig = if (signingPropertiesFile.exists()) {
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
      buildConfigField("boolean", "PRIVATE_FEATURE_INCLUDED", "false")
    }
    create("tv") {
      dimension = "distribution"
      minSdk = 30
      versionCode = 10
      versionName = "1.2.3"
      uploadSigningConfig?.let { signingConfig = it }
      buildConfigField("boolean", "PRIVATE_FEATURE_INCLUDED", "false")
    }
    create("personal") {
      dimension = "distribution"
      applicationIdSuffix = ".personal"
      versionNameSuffix = "-personal"
      buildConfigField(
        "boolean",
        "PRIVATE_FEATURE_INCLUDED",
        (privateFeatureProject != null).toString(),
      )
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

  if (privateFeatureProject != null) {
    val privateSourceSets =
      sourceSets as org.gradle.api.NamedDomainObjectContainer<com.android.build.api.dsl.AndroidSourceSet>
    privateSourceSets.getByName("personal").kotlin.srcDir(
      privateFeatureProject.layout.projectDirectory.dir("app-personal-src"),
    )
    privateSourceSets.maybeCreate("testPersonal").kotlin.srcDir(
      privateFeatureProject.layout.projectDirectory.dir("app-personal-test-src"),
    )
  }
}

dependencies {
  implementation(project(":core:lyrics"))
  implementation(project(":core:platform"))

  if (privateFeatureProject != null) {
    add("personalImplementation", privateFeatureProject)
  }

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
  androidTestImplementation(platform("androidx.compose:compose-bom:2026.04.01"))
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

fun registerPublicDistributionBoundary(
  taskName: String,
  distributionName: String,
  runtimeConfigurations: List<String>,
) = tasks.register(taskName) {
  group = "verification"
  description =
    "Verifies that public $distributionName variants contain neither the private feature nor Android for Cars."

  doLast {
    val leaks = runtimeConfigurations.flatMap { configurationName ->
      configurations.getByName(configurationName)
        .incoming
        .resolutionResult
        .allComponents
        .mapNotNull { component ->
          val identifier = component.id
          val isPrivateFeature =
            identifier is ProjectComponentIdentifier &&
              identifier.projectPath == ":local-private-feature"
          val isAndroidForCars =
            identifier is ModuleComponentIdentifier &&
              (identifier.group == "androidx.car" || identifier.group.startsWith("androidx.car."))
          if (isPrivateFeature || isAndroidForCars) {
            "$configurationName -> ${identifier.displayName}"
          } else {
            null
          }
        }
    }

    check(leaks.isEmpty()) {
      "Private or Android for Cars components leaked into public $distributionName configurations: " +
        leaks.joinToString()
    }
  }
}

val verifyPlayDistributionBoundary = registerPublicDistributionBoundary(
  taskName = "verifyPlayDistributionBoundary",
  distributionName = "Play",
  runtimeConfigurations = listOf(
    "playDebugRuntimeClasspath",
    "playReleaseRuntimeClasspath",
  ),
)

val verifyTvDistributionBoundary = registerPublicDistributionBoundary(
  taskName = "verifyTvDistributionBoundary",
  distributionName = "TV",
  runtimeConfigurations = listOf(
    "tvDebugRuntimeClasspath",
    "tvReleaseRuntimeClasspath",
  ),
)

tasks.named("check").configure {
  dependsOn(verifyPlayDistributionBoundary, verifyTvDistributionBoundary)
}
