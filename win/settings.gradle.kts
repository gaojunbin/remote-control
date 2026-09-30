// The Windows app. It shares the Android app's Kotlin core (android/core) and version catalog, as
// the Mac app shares the iPhone app's RCCore: the core is included here by path, never copied.
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
        // JediTerm, the terminal emulator the terminal page draws with, is published here only.
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            content { includeGroup("org.jetbrains.jediterm") }
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("../android/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "RemoteControlWindows"
include(":core", ":app", ":preview")
project(":core").projectDir = file("../android/core")
