pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(providers.gradleProperty("icy.skikoRepo").get()) } }
            filter { includeGroup("org.jetbrains.skiko") }
        }
        mavenCentral()
    }
}
rootProject.name = "icy-skiko-freetype-consumer"
