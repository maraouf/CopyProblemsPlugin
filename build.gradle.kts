// Force jackson off the buildscript classpath onto the patched line. jackson-core 2.20.2 arrives
// transitively via the intellij-platform Gradle plugin and trips a Dependabot DoS alert
// (GHSA async-parser maxNumberLength bypass). It is a build-time-only dependency — not bundled into
// the plugin zip — and the vulnerable async-parser sink isn't reached by a normal build, but we pin
// the whole jackson family in lockstep to keep the alert quiet and the versions aligned.
buildscript {
    configurations.classpath {
        resolutionStrategy.force(
            "com.fasterxml.jackson.core:jackson-core:2.21.1",
            "com.fasterxml.jackson.core:jackson-databind:2.21.1",
            "com.fasterxml.jackson.core:jackson-annotations:2.21",
            "com.fasterxml.jackson.module:jackson-module-kotlin:2.21.1",
            // undertow-core 2.3.24.Final (transitive via the same plugin) trips a Dependabot DoS
            // alert (multipart/form-data on HTTP GET). Also build-time-only with an unreachable sink;
            // the only patched build is the 2.4.0.Beta1 pre-release.
            "io.undertow:undertow-core:2.4.0.Beta1",
        )
    }
}

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.moraouf"
version = "1.0.12"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    sandboxContainer = file("${System.getProperty("user.home")}/.cache/copyproblems-sandbox")
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = "261.*"
        }
    }
}

tasks.buildPlugin {
    destinationDirectory = layout.projectDirectory.dir("dist")
}

// Disabled: launches a full headless IDE that instantiates every Configurable to scrape searchable
// text, which (a) adds a slow heavy step to every build and (b) crashes when an unrelated IDE-side
// Configurable hits user-environment edge cases (e.g. a malformed Windows PATH tripping the Gradle
// plugin's path parser). Our single Configurable is still findable by its display name in the
// Settings search without this index.
tasks.buildSearchableOptions {
    enabled = false
}
