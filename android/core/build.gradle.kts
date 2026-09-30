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
    // The transport's seams (`HTTPTransport`, `WebSocketFactory`) take OkHttp's request and
    // response, as RCCore's take `URLRequest`, so an app that brings its own sees the same types.
    api(libs.okhttp)
    // The stores keep their state in Compose snapshot state, as RCCore's are @Observable, so both
    // apps' screens read it directly. Each app brings its own runtime (AndroidX on Android,
    // JetBrains' on the desktop); the core only compiles against the shared API.
    compileOnly(libs.compose.runtime)

    testImplementation(kotlin("test"))
    testImplementation(libs.compose.runtime)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
    // The frozen contract's fixtures, which the protocol tests decode.
    systemProperty("rc.protocol.dir", projectDir.resolve("../../protocol").canonicalPath)
    // The live check runs only against the gateway RC_MOCK_GATEWAY names, and every such run
    // really runs: a result from another gateway, or from no gateway, says nothing about this one.
    val liveGateway = providers.environmentVariable("RC_MOCK_GATEWAY")
    inputs.property("rcMockGateway", liveGateway.orElse(""))
    if (liveGateway.isPresent) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}
