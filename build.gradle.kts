plugins {
    java
    id("com.diffplug.spotless") version "8.10.4"
}

spotless {
    java {
        target("core/src/**/*.java", "engine/src/**/*.java")
        googleJavaFormat().aosp()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

allprojects {
    group = "dev.lootradar"
    version =
        rootProject.file("dump-mod/gradle.properties")
            .readLines()
            .first { it.startsWith("version=") }
            .substringAfter("=")
            .trim()
}

repositories {
    mavenCentral()
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://libraries.minecraft.net/")
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 25
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "2G"
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.12.2"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
