import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The protocol, transport and state layer of the Android and Windows apps: plain Kotlin on the JVM,
// so both builds compile the same module (win/ includes this directory as its :core).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.junbingao.remotecontrol"

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
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
    // The frozen contract's fixtures, which the protocol tests decode.
    systemProperty("rc.protocol.dir", projectDir.resolve("../../protocol").canonicalPath)
}
