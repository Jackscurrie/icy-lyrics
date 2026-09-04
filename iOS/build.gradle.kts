import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
  kotlin("multiplatform") version "2.4.10" apply false
  kotlin("plugin.serialization") version "2.4.10" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
  id("org.jetbrains.compose") version "1.11.1" apply false
  id("com.google.devtools.ksp") version "2.3.9" apply false
}

subprojects {
  dependencyLocking { lockAllConfigurations() }
  tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("icy.iosSimulator").orNull?.let { device.set(it) }
  }

  tasks.register("resolveDependencyLocks") {
    group = "build setup"
    description = "Resolves this project's common/native graphs; use --write-locks for an intentional lock update."
    doLast {
      val selected = configurations.filter { configuration ->
        configuration.isCanBeResolved && with(configuration.name) {
          endsWith("ResolvableDependenciesMetadata") ||
            endsWith("CompilationDependenciesMetadata") ||
            startsWith("allSourceSetsCompileDependenciesMetadata") ||
            startsWith("allTestSourceSetsCompileDependenciesMetadata") ||
            (startsWith("metadata") && endsWith("CompileClasspath")) ||
            (startsWith("ios") && (endsWith("CompileKlibraries") || endsWith("FrameworkExport"))) ||
            (startsWith("resolvableIos") && endsWith("CompilationApi")) ||
            (startsWith("ksp") && endsWith("ProcessorClasspath"))
        }
      }
      selected.forEach { configuration ->
        val result = configuration.incoming.resolutionResult
        val unresolved = result.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
        check(unresolved.isEmpty()) {
          "${project.path}:${configuration.name} has unresolved dependencies: ${unresolved.map { it.attempted }}"
        }
        result.allComponents.size // Complete the graph so Gradle can write its lock state.
      }
      logger.lifecycle("${project.path}: resolved ${selected.size} common/native dependency configurations.")
    }
  }
}

tasks.register("resolveDependencyLocks") {
  group = "build setup"
  description = "Resolves all three shared modules' dependency graphs without compiling Apple binaries."
  dependsOn(":shared:lyrics:resolveDependencyLocks", ":shared:platform:resolveDependencyLocks", ":shared:ui:resolveDependencyLocks")
}
