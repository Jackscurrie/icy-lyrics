pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "IcyLyricsAndroidV2"
include(":app", ":core:lyrics", ":core:platform")

val privateFeaturePath = providers
  .gradleProperty("icyLyrics.privateFeaturePath")
  .orElse(providers.environmentVariable("ICY_LYRICS_PRIVATE_FEATURE_PATH"))
  .orNull
  ?.trim()
  ?.takeIf(String::isNotEmpty)

if (privateFeaturePath != null) {
  val publicRepositoryDirectory = rootDir.parentFile.canonicalFile
  val privateFeatureDirectory = file(privateFeaturePath).canonicalFile
  val publicRepositoryPath = publicRepositoryDirectory.toPath()
  val privateFeatureDirectoryPath = privateFeatureDirectory.toPath()

  if (!privateFeatureDirectory.isDirectory) {
    throw GradleException(
      "The configured Icy Lyrics private feature directory does not exist: $privateFeatureDirectory",
    )
  }
  if (
    privateFeatureDirectoryPath.startsWith(publicRepositoryPath) ||
    publicRepositoryPath.startsWith(privateFeatureDirectoryPath)
  ) {
    throw GradleException(
      "The Icy Lyrics private feature must be in a separate directory outside the public repository. " +
        "Configured path: $privateFeatureDirectory",
    )
  }
  if (
    !privateFeatureDirectory.resolve("build.gradle.kts").isFile &&
    !privateFeatureDirectory.resolve("build.gradle").isFile
  ) {
    throw GradleException(
      "The configured Icy Lyrics private feature is not a Gradle module: $privateFeatureDirectory",
    )
  }

  include(":local-private-feature")
  project(":local-private-feature").projectDir = privateFeatureDirectory
}
