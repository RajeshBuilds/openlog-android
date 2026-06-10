plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "cloud.openlog.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "cloud.openlog.demo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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

    kotlinOptions {
        jvmTarget = "17"
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
