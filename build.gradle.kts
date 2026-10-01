plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "dev.alexmiller"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("pycharmLocalPath").orNull

        if (!localPath.isNullOrBlank()) {
            local(localPath)
        } else {
            pycharmCommunity("2025.2.6")
            bundledPlugin("PythonCore")
        }
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

intellijPlatform {
    pluginConfiguration {
        id = "dev.alexmiller.repo-scope-search"
        name = "Repo Scope Search"
        version = provider { project.version.toString() }
        description =
            "Reliable streaming content search across all GitLab repositories available to the current account."
        ideaVersion {
            sinceBuild = "252"
            untilBuild = "252.*"
        }
        vendor {
            name = "Alex Miller"
        }
    }
}

tasks {
    test { useJUnitPlatform() }
}
