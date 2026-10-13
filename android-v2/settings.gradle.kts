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
// Both phone distributions use the same checked-in source. Only github depends
// on this module; no machine-local/private checkout can alter a release.
include(":app", ":core:lyrics", ":core:platform", ":android-auto")
