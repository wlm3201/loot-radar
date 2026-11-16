import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
    application
}

// Isolate benchmark class outputs when different native versions run concurrently.
if (providers.gradleProperty("coverageBuild").isPresent) {
    val experiment = providers.gradleProperty("coverageBuild").get().ifBlank { "default" }
    require(experiment.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid coverageBuild label" }
    layout.buildDirectory = layout.projectDirectory.dir("build/native-${property("minecraft_version")}-$experiment")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation(project(":core"))
    implementation("org.objenesis:objenesis:3.4")
    // NOTE: no slf4j binding on purpose — Minecraft's LoggedPrintStream routes
    // System.out through slf4j, and console bindings recurse (StackOverflow).
}

application {
    mainClass = "dev.lootradar.app.Main"
}

tasks.named<Zip>("distZip") {
    archiveVersion.set("")
}

// Compile native API bindings against the actual Minecraft version, never mix
// 26.2 worldgen code with a 26.3 data catalog during coverage experiments.
// Snapshot builds ("26.4-snapshot-7") share the release's adapter (mc26_4).
sourceSets.main {
    java.srcDir("src/mc${property("minecraft_version").toString().substringBefore('-').replace('.', '_')}/java")
}

// vanilla runtime needs legacy sun.misc access at run/test time (see reference projects)
tasks.withType<Test>().configureEach {
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
}

tasks.register<JavaExec>("runApp") {
    group = "application"
    description = "Starts the loot-radar local web app"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
    workingDir = rootProject.projectDir
    // -PnoBrowser=true or env LOOTRADAR_NO_BROWSER suppresses the auto browser
    if (providers.gradleProperty("noBrowser").isPresent
        || System.getenv("LOOTRADAR_NO_BROWSER") != null) {
        systemProperty("lootradar.noBrowser", "true")
    }
}

tasks.register<JavaExec>("runVerify") {
    group = "verification"
    description = "Compares a dump-mod ground truth JSON against engine predictions"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.lootradar.app.tools.VerifyMain"
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
    workingDir = rootProject.projectDir
    if (project.hasProperty("dump")) {
        args(project.property("dump").toString())
    }
}

tasks.register<JavaExec>("runLootingCompare") {
    group = "verification"
    description = "Replays the elder-guardian kill model against a loot-radar-looting/1 dump"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.lootradar.app.tools.LootingCompareMain"
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
    workingDir = rootProject.projectDir
    if (project.hasProperty("dump")) {
        args(project.property("dump").toString())
    }
}

tasks.register<JavaExec>("runBench") {
    group = "verification"
    description = "Search timing benchmark (docs/bench-report.{md,csv})"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.lootradar.app.tools.BenchMain"
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
    workingDir = rootProject.projectDir
    if (project.hasProperty("radius")) {
        args(project.property("radius").toString())
    }
}

tasks.register<JavaExec>("runBounceCoverage") {
    group = "verification"
    description = "Exhaustive Bounce predictions: --args='outputDir radius seed...'"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.lootradar.app.tools.BounceCoverageMain"
    jvmArgs("--sun-misc-unsafe-memory-access=allow", "-Xmx4G")
    workingDir = rootProject.projectDir
}

tasks.register("writeRoomCoverageLaunch") {
    dependsOn(tasks.named("classes"), ":core:classes")
    doLast {
        // Gradle may remove a task's previous output when buildDirectory changes.
        // Export content-addressed copies outside those managed output folders,
        // so another version/build cannot invalidate a running benchmark JVM.
        val snapshotRoot = rootProject.layout.buildDirectory.dir("room-launches").get().asFile
        val rootPath = rootProject.projectDir.toPath().toAbsolutePath().normalize()
        val frozenClasspath = sourceSets.main.get().runtimeClasspath.files.filter { it.exists() }.map { entry ->
            if (!entry.toPath().toAbsolutePath().normalize().startsWith(rootPath)) entry else {
                val digest = MessageDigest.getInstance("SHA-256")
                val contents = if (entry.isDirectory) entry.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(entry).invariantSeparatorsPath }.toList() else listOf(entry)
                for (file in contents) {
                    digest.update((if (entry.isDirectory) file.relativeTo(entry).invariantSeparatorsPath else file.name).toByteArray())
                    digest.update(file.readBytes())
                }
                val hash = HexFormat.of().formatHex(digest.digest())
                val destination = snapshotRoot.resolve(hash).resolve(entry.name)
                if (!destination.exists()) {
                    destination.parentFile.mkdirs()
                    if (entry.isDirectory) entry.copyRecursively(destination) else entry.copyTo(destination)
                }
                destination
            }
        }.joinToString(File.pathSeparator) { it.absolutePath }
        // Placeholder main; run-source-tests.py swaps in the concrete Coverage*Main.
        val command = listOf(javaToolchains.launcherFor(java.toolchain).get().executablePath.asFile.absolutePath,
            "--sun-misc-unsafe-memory-access=allow", "-Xmx4G", "-cp", frozenClasspath,
            "dev.lootradar.app.tools.SourceCoverageMain")
        layout.buildDirectory.file("room-coverage-launch.json").get().asFile.writeText(groovy.json.JsonOutput.toJson(command))
    }
}
