plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.github.collinvandyck.diffnavigator"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("/Applications/GoLand.app")
        bundledPlugin("Git4Idea")
        // GitRepositoryManager extends dvcs classes that are not on the compile classpath by default. Both are needed.
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
    }
    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
    pluginVerification {
        ides {
            local(file("/Applications/GoLand.app"))
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
