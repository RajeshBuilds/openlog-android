pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "openlog-android"

include(":openlog-replay")

// NOTE: The pure-JVM wire-contract validation harness in `tools/wire-verify`
// is an INDEPENDENT Gradle build (it has its own settings.gradle.kts) so it
// can run the Part 5 schema-conformance gate without the Android SDK.
// Run it with:  gradle -p tools/wire-verify test
