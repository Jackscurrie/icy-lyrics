import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins { kotlin("multiplatform") version "2.4.10" }

kotlin {
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies { implementation("org.jetbrains.skiko:skiko:0.144.6-icy-freetype.1") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    deviceId.set(providers.gradleProperty("icy.simulator"))
    environment("SIMCTL_CHILD_ICY_SKIKO_FONT_ROOT", providers.gradleProperty("icy.fontRoot").get())
    environment("SIMCTL_CHILD_ICY_SKIKO_OUTPUT_ROOT", providers.gradleProperty("icy.outputRoot").get())
}
