pluginManagement {
  repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
  repositories { google(); mavenCentral() }
}
rootProject.name = "IcyLyricsIOS"
include(":shared:lyrics", ":shared:platform", ":shared:ui")
