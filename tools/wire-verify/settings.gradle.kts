dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Independent build: validates the Android-free wire model + builders against
// rr-mobile-schema.json without requiring the Android SDK.
rootProject.name = "wire-verify"
