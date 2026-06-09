plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.networknt:json-schema-validator:1.5.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

// Compile the Android-free SDK sources directly from the library module so the
// validation runs against the *real* wire model — no copies, no drift.
sourceSets {
    main {
        kotlin.srcDir("../../openlog-replay/src/main/java/cloud/openlog/replay/wire")
        kotlin.srcDir("../../openlog-replay/src/main/java/cloud/openlog/replay/diff")
    }
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
