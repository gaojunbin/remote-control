import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    // DPAPI for the token, and the Windows settings the app reads.
    implementation(libs.jna)
    implementation(libs.jna.platform)
    // The terminal page's emulator.
    implementation(libs.jediterm.core)
    implementation(libs.jediterm.ui)
    // The web's Markdown pipeline runs in QuickJS; the library ships its natives for Windows,
    // macOS and Linux. Not in the shared catalog, which only this app needs it from.
    implementation("io.github.dokar3:quickjs-kt-jvm:1.0.15")

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // The Markdown corpus is read as JSON.
    testImplementation(libs.kotlinx.serialization.json)
}

// The chat's Markdown is the Mac app's own bundle of the web's pipeline, served from where it
// lives rather than copied, with the licences that travel with it.
tasks.processResources {
    from(rootDir.resolve("../macos/Sources/RCMac/Resources/Highlight")) {
        include("markdown.bundle.js", "LICENSE-highlight.js.txt", "LICENSES-markdown.txt")
        into("highlight")
    }
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.junbingao.remotecontrol.win.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Remote Control"
            packageVersion = "1.12.0"
            vendor = "Junbin Gao"
            windows {
                menuGroup = "Remote Control"
                shortcut = true
                perUserInstall = true
                // Stable across releases, so an installer upgrades the previous install in place.
                upgradeUuid = "7d0f1c3e-5b2a-4e8f-9a61-3c4d2b8e5f10"
                iconFile.set(project.file("packaging/RemoteControl.ico"))
            }
        }
    }
}
