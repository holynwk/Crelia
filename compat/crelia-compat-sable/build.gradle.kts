plugins {
    id("java-library")
    id("net.neoforged.moddev") version "2.0.28-beta"
}

group = "com.crelia"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven {
        name = "NeoForge"
        url = uri("https://maven.neoforged.net/releases/")
    }
    maven {
        name = "Sable"
        // TODO: Update to actual Sable Maven repository URL when available
        url = uri("https://maven.creativetab.dev/releases/")
    }
    maven {
        name = "Create Aeronautics"
        // TODO: Update to actual Create Aeronautics Maven repository URL when available
        url = uri("https://maven.creativetab.dev/releases/")
    }
}

dependencies {
    // NeoForge API — provides the mod loading framework and event bus
    implementation("net.neoforged:neoforge:21.1.79")

    // Sable — physics engine mod with Rapier JNI backend
    compileOnly("dev.sable:sable:0.1.0")

    // Create Aeronautics — aircraft engineering add-on for Create
    compileOnly("com.simibubi.create_aeronautics:create_aeronautics:0.1.0")
}

tasks.withType<ProcessResources> {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(props)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 21
}

sourceSets {
    main {
        resources {
            srcDir("src/main/resources")
        }
    }
}

moddev {
    // NeoForge moddev configuration for local development
    neoForge {
        version = "21.1.79"
    }

    runs {
        create("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", "crelia_compat_sable")
        }
        create("server") {
            server()
            programArgument("--nogui")
            systemProperty("neoforge.enabledGameTestNamespaces", "crelia_compat_sable")
        }
    }
}
