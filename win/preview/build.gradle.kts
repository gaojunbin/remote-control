import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The offscreen renderer: draws the app's screens to PNG files without a window, on any desktop
// OS, so a Windows screen can be compared with the Mac renderer's picture of the same scenario.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    application
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
    implementation(project(":app"))
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
}

application {
    mainClass = "com.junbingao.remotecontrol.win.preview.MainKt"
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}
