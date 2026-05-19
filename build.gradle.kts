plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.moraouf"
version = "1.0.11"

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
