import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.compose")
}

// CMP 1.11.1 publishes forwarding modules for libraries now maintained by AndroidX.
// Their common metadata still repeats the destination KLIB's unique_name. Resolve
// metadata coordinates directly to their published AndroidX destinations.
// These are the versions already selected by the unmodified CMP 1.11.1 graph.
// Native binaries must retain their forwarding KLIBs: precompiled CMP manifests
// explicitly depend on their distinct native unique_name values.
val migratedAndroidxModules = mapOf(
  "org.jetbrains.compose.runtime:runtime" to "androidx.compose.runtime:runtime:1.11.2",
  "org.jetbrains.compose.runtime:runtime-saveable" to "androidx.compose.runtime:runtime-saveable:1.11.2",
  "org.jetbrains.compose.annotation-internal:annotation" to "androidx.annotation:annotation:1.9.1",
  "org.jetbrains.compose.collection-internal:collection" to "androidx.collection:collection:1.5.0",
  "org.jetbrains.androidx.lifecycle:lifecycle-common" to "androidx.lifecycle:lifecycle-common:2.9.4",
  "org.jetbrains.androidx.lifecycle:lifecycle-runtime" to "androidx.lifecycle:lifecycle-runtime:2.9.4",
  "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose" to "androidx.lifecycle:lifecycle-runtime-compose:2.9.4",
  "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel" to "androidx.lifecycle:lifecycle-viewmodel:2.9.4",
  "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate" to "androidx.lifecycle:lifecycle-viewmodel-savedstate:2.9.4",
  "org.jetbrains.androidx.savedstate:savedstate" to "androidx.savedstate:savedstate:1.4.0",
  "org.jetbrains.androidx.savedstate:savedstate-compose" to "androidx.savedstate:savedstate-compose:1.4.0",
)

configurations.configureEach {
  if (name.contains("metadata", ignoreCase = true)) {
    resolutionStrategy.dependencySubstitution {
      migratedAndroidxModules.forEach { (forwarder, destination) ->
        substitute(module(forwarder)).using(module(destination))
          .because("Use one common metadata KLIB for each migrated AndroidX library")
      }
    }
  }
}

kotlin {
  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "IcyShared"
      isStatic = true
      export(project(":shared:lyrics"))
      export(project(":shared:platform"))
      binaryOption("bundleId", "com.icy.lyrics.shared")
    }
  }
  jvmToolchain(17)
  sourceSets {
    commonMain.dependencies {
      api(project(":shared:lyrics"))
      api(project(":shared:platform"))
      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.ui)
      implementation(compose.animation)
      implementation("org.jetbrains.compose.material3:material3:1.9.0")
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
      implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    }
    commonTest.dependencies { implementation(kotlin("test")) }
    iosMain.dependencies { implementation("org.jetbrains.compose.ui:ui-backhandler:1.11.1") }
    iosTest.dependencies {
      implementation("org.jetbrains.compose.ui:ui-test:1.11.1")
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
      implementation("com.squareup.okio:okio:3.16.4")
    }
  }
}

// simctl passes only variables with SIMCTL_CHILD_ into the simulator process.
// The offscreen test runner reads the same checked-in bytes without depending on an app bundle.
val deterministicCaptureAssets = layout.projectDirectory.dir("assets")
val deterministicCaptureOutput = rootProject.layout.buildDirectory.dir("reports/deterministic-ios-captures")
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
  inputs.dir(deterministicCaptureAssets)
  outputs.dir(deterministicCaptureOutput)
  environment("SIMCTL_CHILD_ICY_DETERMINISTIC_ASSET_ROOT", deterministicCaptureAssets.asFile.absolutePath)
  environment("SIMCTL_CHILD_ICY_DETERMINISTIC_OUTPUT_ROOT", deterministicCaptureOutput.get().asFile.absolutePath)
}

// This resolves and inspects the actual external native archives even on Windows;
// compiling project KLIBs or linking an Apple framework still requires macOS.
val verifyNativeDependencyGraph by tasks.registering {
  group = "verification"
  description = "Checks both iOS main/test native graphs for duplicate KLIBs and the parity version pins."
  doLast {
    val metadataModules = configurations.getByName("commonMainResolvableDependenciesMetadata")
      .incoming.resolutionResult.allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
    val duplicateMetadataForwarders = metadataModules.filter { "${it.group}:${it.module}" in migratedAndroidxModules }
    check(duplicateMetadataForwarders.isEmpty()) { "Common metadata still contains forwarding modules: $duplicateMetadataForwarders" }
    listOf("iosArm64", "iosSimulatorArm64").flatMap {
      listOf("${it}CompileKlibraries", "${it}TestCompileKlibraries")
    }.forEach { target ->
      val configuration = configurations.getByName(target)
      val modules = configuration.incoming.resolutionResult.allComponents
        .mapNotNull { it.id as? ModuleComponentIdentifier }
      val material3 = modules.single { it.group == "org.jetbrains.compose.material3" && it.module == "material3" }
      check(material3.version == "1.9.0") { "Material 3 must remain 1.9.0 for Android 1.4.0 visual parity." }
      if (target.endsWith("TestCompileKlibraries")) {
        val uiTest = modules.single { it.group == "org.jetbrains.compose.ui" && it.module == "ui-test" }
        check(uiTest.version == "1.11.1") { "The isolated insets test hook is pinned to Compose UI Test 1.11.1." }
      }

      val artifacts = configuration.incoming.artifactView {
        componentFilter { it is ModuleComponentIdentifier }
      }.artifacts.artifacts.filter { it.file.extension == "klib" }
      check(artifacts.isNotEmpty()) { "No external native KLIBs resolved for $target." }
      val identities = artifacts.map { artifact ->
        val manifest = ZipFile(artifact.file).use { archive ->
          val entry = archive.getEntry("default/manifest")
            ?: error("Missing KLIB manifest in ${artifact.file.name}")
          Properties().apply { archive.getInputStream(entry).use(::load) }
        }
        val name = requireNotNull(manifest.getProperty("unique_name")) {
          "Missing KLIB unique_name in ${artifact.file.name}"
        }
        Triple(name, artifact.id.componentIdentifier.displayName, manifest.getProperty("depends").orEmpty().split(' ').filter(String::isNotBlank))
      }
      val duplicates = identities.groupBy({ it.first }, { it.second }).filterValues { it.distinct().size > 1 }
      check(duplicates.isEmpty()) { "$target has duplicate native KLIB identities: $duplicates" }
      val names = identities.map { it.first }.toSet()
      val missing = identities.mapNotNull { (name, _, dependencies) ->
        dependencies.filter { it != "stdlib" && !it.startsWith("org.jetbrains.kotlin.native.platform.") && it !in names }
          .takeIf { it.isNotEmpty() }?.let { name to it }
      }.toMap()
      check(missing.isEmpty()) { "$target has missing external native KLIB dependencies: $missing" }
      logger.lifecycle("$target: ${artifacts.size} external native KLIBs, no duplicate or missing identities; Material 3 ${material3.version}.")
    }
  }
}

tasks.named("check") { dependsOn(verifyNativeDependencyGraph) }
tasks.matching { it.name.startsWith("link") && it.name.contains("Framework") }.configureEach {
  dependsOn(verifyNativeDependencyGraph)
}
