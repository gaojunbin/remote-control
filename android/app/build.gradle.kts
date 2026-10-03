import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.junbingao.remotecontrol"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.junbingao.remotecontrol"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.12.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric reads the merged resources and manifest, and draws with the real
            // graphics stack so a screenshot is the pixels a phone would show.
            isIncludeAndroidResources = true
            all { test ->
                test.systemProperty("robolectric.graphicsMode", "NATIVE")
                test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                test.maxHeapSize = "3g"
                // The Android 16 framework Robolectric runs reaches into the JDK's file
                // descriptors, which JDK 17 and later keep closed unless opened here.
                test.jvmArgs(
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                )
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

roborazzi {
    // The pictures are evidence to compare with the iPhone's, as the Mac renderer's are with the
    // web's, not a committed baseline: `recordRoborazziDebug` draws them into the build.
    outputDir.set(layout.buildDirectory.dir("outputs/roborazzi"))
}

/**
 * The iPhone's string catalogue, turned into the Kotlin table the app reads its words from.
 *
 * `ios/App/Localizable.xcstrings` is the one place a word lives (`docs/DESIGN.md` § "Palette and
 * type"), so the Android app reads it at build time rather than keeping a copy that would drift.
 * `src/main/strings/overlay.json` holds the words `docs/DESIGN.md` § "The Android app" changes —
 * where the iPhone names Apple, Android names its own counterpart — and nothing else.
 * `src/main/strings/system.json` holds the words UIKit supplies on the iPhone for the pieces this
 * app draws itself (a back button's name, a search field's placeholder), which the iPhone's
 * catalogue therefore never needed; none of them may be a catalogue key.
 * `src/main/strings/android.json` holds the words only the Android app shows (the launcher badge's
 * notification, A47); none of them may be a catalogue key either, so the iPhone's catalogue keeps
 * only the iPhone's words.
 */
abstract class GenerateStringCatalog : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalog: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val overlay: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val system: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val androidWords: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val english = sortedMapOf<String, String>()
        val chinese = sortedMapOf<String, String>()
        @Suppress("UNCHECKED_CAST")
        val strings = (JsonSlurper().parse(catalog.get().asFile) as Map<String, Any?>)["strings"]
            as Map<String, Map<String, Any?>>
        for ((key, entry) in strings) {
            @Suppress("UNCHECKED_CAST")
            val localizations = entry["localizations"] as? Map<String, Map<String, Map<String, String>>>
                ?: emptyMap()
            english[key] = localizations["en"]?.get("stringUnit")?.get("value") ?: key
            chinese[key] = localizations["zh-Hans"]?.get("stringUnit")?.get("value")
                ?: throw GradleException("\"$key\" has no zh-Hans translation in the catalogue")
        }
        val catalogued = english.keys.toSet()
        val overlaid = sortedSetOf<String>()
        for ((key, words) in entries(overlay.get().asFile)) {
            if (key !in catalogued) throw GradleException("overlay \"$key\" is not a catalogue key")
            english[key] = words.first
            chinese[key] = words.second
            overlaid.add(key)
        }
        for ((key, words) in entries(system.get().asFile)) {
            if (key in catalogued) throw GradleException("system word \"$key\" is already a catalogue key")
            english[key] = words.first
            chinese[key] = words.second
        }
        for ((key, words) in entries(androidWords.get().asFile)) {
            if (key in catalogued) throw GradleException("Android word \"$key\" is already a catalogue key")
            english[key] = words.first
            chinese[key] = words.second
        }
        val file = outputDir.get().asFile
            .resolve("com/junbingao/remotecontrol/android/strings/Catalog.kt")
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("// Generated from ios/App/Localizable.xcstrings and app/src/main/strings/{overlay,system,android}.json")
            appendLine("// by the generateStringCatalog task. Edit those files, never this one.")
            appendLine("package com.junbingao.remotecontrol.android.strings")
            appendLine()
            appendLine("internal object Catalog {")
            appendTable("en", english)
            appendTable("zhHans", chinese)
            appendLine("    val overlaid: Set<String> = hashSetOf(")
            for (key in overlaid) appendLine("        ${literal(key)},")
            appendLine("    )")
            appendLine("}")
        })
    }

    /** A `{"key": {"en": …, "zh-Hans": …}}` file; a key starting with "//" is the file's own note. */
    private fun entries(file: java.io.File): List<Pair<String, Pair<String, String>>> {
        @Suppress("UNCHECKED_CAST")
        val parsed = JsonSlurper().parse(file) as Map<String, Any?>
        return parsed.filterKeys { !it.startsWith("//") }.map { (key, entry) ->
            @Suppress("UNCHECKED_CAST")
            val words = entry as? Map<String, String> ?: throw GradleException("${file.name}: \"$key\" is not an object")
            val en = words["en"] ?: throw GradleException("${file.name}: \"$key\" has no en")
            val zh = words["zh-Hans"] ?: throw GradleException("${file.name}: \"$key\" has no zh-Hans")
            key to (en to zh)
        }
    }

    private fun StringBuilder.appendTable(name: String, table: Map<String, String>) {
        appendLine("    val $name: Map<String, String> = hashMapOf(")
        for ((key, value) in table) appendLine("        ${literal(key)} to ${literal(value)},")
        appendLine("    )")
    }

    private fun literal(text: String): String = buildString {
        append('"')
        for (character in text) when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
        append('"')
    }
}

/**
 * The iPhone's Markdown renderer and Mermaid, served to the app's web view from the directory the
 * iPhone ships them in (`ios/Sources/RCUI/Resources/Markdown`), so both apps draw a diagram with
 * the same files and a vendored update reaches both.
 */
abstract class CopyMarkdownAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(source)
            into(outputDir.dir("markdown"))
        }
    }
}

val generateStringCatalog = tasks.register<GenerateStringCatalog>("generateStringCatalog") {
    catalog.set(rootProject.layout.projectDirectory.file("../ios/App/Localizable.xcstrings"))
    overlay.set(layout.projectDirectory.file("src/main/strings/overlay.json"))
    system.set(layout.projectDirectory.file("src/main/strings/system.json"))
    androidWords.set(layout.projectDirectory.file("src/main/strings/android.json"))
    outputDir.set(layout.buildDirectory.dir("generated/source/stringCatalog"))
}

val copyMarkdownAssets = tasks.register<CopyMarkdownAssets>("copyMarkdownAssets") {
    source.set(rootProject.layout.projectDirectory.dir("../ios/Sources/RCUI/Resources/Markdown"))
    outputDir.set(layout.buildDirectory.dir("generated/assets/markdown"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateStringCatalog, GenerateStringCatalog::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(copyMarkdownAssets, CopyMarkdownAssets::outputDir)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
