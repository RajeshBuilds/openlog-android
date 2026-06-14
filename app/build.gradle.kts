import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "cloud.openlog.demo"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "cloud.openlog.demo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Ingest credentials are read from local.properties (git-ignored) or env
        // vars — never committed to source. To enable upload, add to local.properties:
        //   openlog.ingestToken=<INGEST_TOKEN>
        //   openlog.baseUrl=https://openlog.sh    (optional; this is the default)
        // With no token, the demo records to a local NDJSON file instead of uploading.
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val ingestToken = localProps.getProperty("openlog.ingestToken")
            ?: System.getenv("OPENLOG_INGEST_TOKEN") ?: ""
        val baseUrl = localProps.getProperty("openlog.baseUrl")
            ?: System.getenv("OPENLOG_BASE_URL") ?: "https://openlog.sh"
        buildConfigField("String", "OPENLOG_INGEST_TOKEN", "\"$ingestToken\"")
        buildConfigField("String", "OPENLOG_BASE_URL", "\"$baseUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(project(":openlog-replay"))
    implementation(libs.androidx.core.ktx)
    // Single-Activity / multi-Fragment demo screen (exercises per-fragment screen names).
    implementation(libs.androidx.fragment.ktx)
    // Used by the in-app recording viewer to pretty-print the NDJSON stream.
    implementation(libs.kotlinx.serialization.json)
}
