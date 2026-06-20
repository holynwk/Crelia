import io.papermc.paperweight.tasks.RebuildGitPatches
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

// ============================================================================
// Crelia-NeoForge: Folia 1.21.1 + NeoForge 21.1.x hybrid server
// Uses paperweight-patcher 1.7.3 (same as Folia dev/1.21.1)
// ============================================================================

plugins {
    java
    id("io.papermc.paperweight.patcher") version "1.7.3"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

repositories {
    mavenCentral()
    maven(paperMavenPublicUrl) {
        content { onlyForConfigurations(configurations.paperclip.name) }
    }
}

dependencies {
    remapper("net.fabricmc:tiny-remapper:0.10.3:fat")
    decompiler("org.vineflower:vineflower:1.10.1")
    paperclip("io.papermc:paperclip:3.0.3")
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }
}

subprojects {
    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(21)
        options.isFork = true
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven("https://maven.neoforged.net/releases") // Crelia-NeoForge: FancyModLoader
        maven("https://repo.spongepowered.org/repository/maven-public/") // SpongePowered: Configurate
        maven("https://oss.sonatype.org/content/repositories/snapshots") // Spark snapshots
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") // Spigot / Spark
    }
}

paperweight {
    serverProject.set(project(":folia-server"))

    remapRepo.set(paperMavenPublicUrl)
    decompileRepo.set(paperMavenPublicUrl)

    usePaperUpstream(providers.gradleProperty("paperRef")) {
        withPaperPatcher {
            // Crelia-NeoForge: API patches (Folia region scheduler API + NeoForge hooks)
            apiPatchDir.set(layout.projectDirectory.dir("patches/api"))
            apiOutputDir.set(layout.projectDirectory.dir("Folia-API"))

            // Crelia-NeoForge: Server patches (Folia region threading + NeoForge event hooks)
            serverPatchDir.set(layout.projectDirectory.dir("patches/server"))
            serverOutputDir.set(layout.projectDirectory.dir("Folia-Server"))
        }
        patchTasks.register("generatedApi") {
            isBareDirectory = true
            upstreamDirPath = "paper-api-generator/generated"
            patchDir = layout.projectDirectory.dir("patches/generatedApi")
            outputDir = layout.projectDirectory.dir("paper-api-generator/generated")
        }
    }
}

tasks.generateDevelopmentBundle {
    apiCoordinates.set("dev.folia:folia-api")
    libraryRepositories.addAll(
        "https://repo.maven.apache.org/maven2/",
        paperMavenPublicUrl,
        "https://maven.neoforged.net/releases",
    )
}

tasks.withType<RebuildGitPatches> {
    filterPatches.set(false)
}

tasks.register("printMinecraftVersion") {
    doLast {
        println(providers.gradleProperty("mcVersion").get().trim())
    }
}

tasks.register("printPaperVersion") {
    doLast {
        println(project.version)
    }
}

// ============================================================================
// Crelia-NeoForge: Standalone JAR builder (creliatest2.jar)
// ============================================================================

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

fun jarDirectory(sourceDir: File, destination: File) {
    JarOutputStream(FileOutputStream(destination)).use { output ->
        sourceDir.walkTopDown()
            .filter { file -> file.isFile }
            .sortedBy { file -> file.relativeTo(sourceDir).invariantSeparatorsPath }
            .forEach { file ->
                val entry = JarEntry(file.relativeTo(sourceDir).invariantSeparatorsPath)
                entry.time = 0
                output.putNextEntry(entry)
                file.inputStream().use { input -> input.copyTo(output) }
                output.closeEntry()
            }
    }
}

project(":folia-server") {
    afterEvaluate {
        // Crelia-NeoForge: Compile shim classes (they must compile before folia-server sources)
        // Shims provide compile-only stubs for net.neoforged.* packages
        val shimDir = layout.buildDirectory.dir("crelia/shim-classes")
        val compileShims by tasks.registering(JavaCompile::class) {
            val shimClasses = rootProject.fileTree("build-data/crelia-neoforge-shims/src/main/java") { include("**/*.java") }
            source(shimClasses)
            classpath = files() // Shims use only java.lang types (Object, boolean, int, etc.)
            destinationDirectory.set(shimDir)
            options.release.set(21)
        }
        val compileJava = tasks.findByName("compileJava") as? JavaCompile
        if (compileJava != null) {
            compileJava.dependsOn(compileShims)
            compileJava.doFirst {
                compileJava.classpath = files(compileJava.classpath, shimDir)
            }
        }
    }
    afterEvaluate {
        val serverJar = tasks.named<Jar>("jar")
        // Crelia-NeoForge: NeoForge tasks may not exist yet — graceful degradation
        val neoforgeResourcesJar = tasks.findByName("neoforgeResourcesJar") as? Jar
        val fmlLoaderConfig = configurations.findByName("fmlLoader")
        val runtimeClasspath = configurations.named("runtimeClasspath")
        val paperTransformerJarPrefixes = listOf("folia-api-", "spark-api-", "spark-paper-")
        val stagingDir = layout.buildDirectory.dir("crelia/standalone")

        // Crelia-NeoForge: compile launcher, core runtime, and server templates
        val creliaLauncherSources = rootProject.fileTree("build-data/crelia-launcher/src/main/java") { include("**/*.java") }
        val creliaCoreSources = rootProject.fileTree("build-data/crelia-core/src/main/java") { include("**/*.java") }
        val creliaServerTemplateSources = rootProject.fileTree("build-data/crelia-server-templates/src/main/java") { include("**/*.java") }

        val compileCreliaLauncher by tasks.registering(JavaCompile::class) {
            description = "Compile the Crelia jar launcher"
            source(creliaLauncherSources)
            classpath = files()
            destinationDirectory.set(layout.buildDirectory.dir("crelia/launcher-classes"))
            options.release.set(21)
        }

        val compileCreliaCore by tasks.registering(JavaCompile::class) {
            description = "Compile Crelia core runtime (RegionAwareEventBus, CreliaModLoadingPlugin, etc.)"
            source(creliaCoreSources)
            classpath = files(serverJar.map { it.archiveFile })
            destinationDirectory.set(layout.buildDirectory.dir("crelia/core-classes"))
            options.release.set(21)
            dependsOn(compileCreliaLauncher)
        }

        val compileCreliaServerTemplates by tasks.registering(JavaCompile::class) {
            description = "Compile Crelia server template classes (CreliaServer entry point)"
            source(creliaServerTemplateSources)
            classpath = files(serverJar.map { it.archiveFile }, layout.buildDirectory.dir("crelia/core-classes"))
            destinationDirectory.set(layout.buildDirectory.dir("crelia/server-template-classes"))
            options.release.set(21)
            dependsOn(compileCreliaCore)
        }

        val creliaCoreResources = rootProject.fileTree("build-data/crelia-core/src/main/resources") {
            include("**/*")
        }

        val creliaCoreJar by tasks.registering(Jar::class) {
            description = "Package Crelia core runtime into a jar (classes + resources)"
            from(layout.buildDirectory.dir("crelia/core-classes"))
            from(creliaCoreResources)
            archiveFileName.set("crelia-core.jar")
            destinationDirectory.set(layout.buildDirectory.dir("crelia/intermediate-jars"))
            dependsOn(compileCreliaCore)
        }

        val creliaServerTemplateJar by tasks.registering(Jar::class) {
            description = "Package Crelia server templates into a jar"
            from(layout.buildDirectory.dir("crelia/server-template-classes"))
            archiveFileName.set("crelia-server-templates.jar")
            destinationDirectory.set(layout.buildDirectory.dir("crelia/intermediate-jars"))
            dependsOn(compileCreliaServerTemplates)
        }

        val prepareCreliaStandalone by tasks.registering {
            description = "Stage the exact runServerFml classpath as nested jars"
            dependsOn(serverJar, creliaCoreJar, creliaServerTemplateJar)
            if (neoforgeResourcesJar != null) dependsOn(neoforgeResourcesJar)
            inputs.file(serverJar.flatMap { it.archiveFile })
            if (neoforgeResourcesJar != null) {
                inputs.file(neoforgeResourcesJar.archiveFile)
            }
            inputs.files(runtimeClasspath)
            if (fmlLoaderConfig != null) {
                inputs.files(fmlLoaderConfig)
            }
            outputs.dir(stagingDir)

            doLast {
                val outputDir = stagingDir.get().asFile
                val librariesDir = outputDir.resolve("libraries")
                project.delete(outputDir)
                librariesDir.mkdirs()

                val candidates = buildList {
                    add(serverJar.get().archiveFile.get().asFile)
                    if (neoforgeResourcesJar != null) {
                        add(neoforgeResourcesJar.archiveFile.get().asFile)
                    }
                    // Crelia-NeoForge: embed core runtime and server templates
                    add(creliaCoreJar.get().archiveFile.get().asFile)
                    add(creliaServerTemplateJar.get().archiveFile.get().asFile)
                    addAll(runtimeClasspath.get().files.filterNot { file ->
                        paperTransformerJarPrefixes.any { prefix -> file.name.startsWith(prefix) }
                    })
                    if (fmlLoaderConfig != null) {
                        addAll(fmlLoaderConfig.files)
                    }
                }
                val seenPaths = mutableSetOf<String>()
                val classpathFiles = candidates.filter { file ->
                    file.exists() &&
                    seenPaths.add(file.absoluteFile.normalize().path)
                }

                val indexLines = classpathFiles.mapIndexed { index, file ->
                    val embeddedName = "%03d-%s%s".format(index, file.name, if (file.isDirectory) ".jar" else "")
                    val embeddedFile = librariesDir.resolve(embeddedName)
                    if (file.isDirectory) {
                        jarDirectory(file, embeddedFile)
                    } else {
                        file.copyTo(embeddedFile, overwrite = true)
                    }
                    "${sha256(embeddedFile)}\t$embeddedName"
                }
                outputDir.resolve("crelia-libraries.index")
                    .writeText(indexLines.joinToString(separator = "\n", postfix = "\n"))
            }
        }

        tasks.register<Jar>("creliaStandaloneJar") {
            group = "build"
            description = "Build creliatest2.jar with the full Crelia FML runtime nested inside"
            dependsOn(compileCreliaLauncher, prepareCreliaStandalone)
            archiveFileName.set("creliatest2.jar")
            destinationDirectory.set(rootProject.layout.projectDirectory.dir(".."))
            from(compileCreliaLauncher.flatMap { it.destinationDirectory })
            from(stagingDir.map { it.dir("libraries") }) {
                into("META-INF/crelia-libraries")
            }
            from(stagingDir.map { it.file("crelia-libraries.index") }) {
                into("META-INF")
            }
            from(rootProject.file("folia-server/crelia-supported.json")) {
                into("META-INF")
            }
            manifest {
                attributes(
                    "Main-Class" to "crelia.launcher.Main",
                    "Enable-Native-Access" to "ALL-UNNAMED",
                    "Crelia-NeoForge" to "true",
                    "Crelia-MC-Version" to "1.21.1",
                    "Crelia-NeoForge-Version" to providers.gradleProperty("neoforgeVersion").get(),
                )
            }
        }
    }
}
