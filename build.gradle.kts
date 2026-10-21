import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ============================================================================
// BattalionX build configuration
//
// Kotlin Multiplatform project. The entire game (rules engine, AI, data model,
// turn system) lives in `commonMain`. Each desktop platform (JVM, macOS, Linux)
// only provides a thin `main()` entry point plus asset loading.
// ============================================================================

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "cn.enaium"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
}

kotlin {
    // --- Desktop JVM target (primary development / run target) -------------
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        mainRun {
            mainClass = "cn.enaium.battalion.MainKt"
        }
    }

    // --- Native desktop targets --------------------------------------------
    macosArm64 {
        binaries.executable {
            entryPoint = "cn.enaium.battalion.main"
        }
    }

    linuxX64 {
        binaries.executable {
            entryPoint = "cn.enaium.battalion.main"
        }
    }

    // --- Shared source sets -------------------------------------------------
    sourceSets {
        commonMain.dependencies {
            // Graphics / audio / fonts (KMP bindings)
            implementation(libs.sdl.kmp)
            implementation(libs.sdl.ttf.kmp)
            implementation(libs.sdl.image.kmp)
            implementation(libs.sdl.mixer.kmp)

            // Kotlinx ecosystem
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)

            // File loading
            implementation(libs.filekit.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// ============================================================================
// Helper: build a runnable fat JAR containing the Kotlin stdlib + all deps.
// Usage: ./gradlew fatJar
// ============================================================================
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds a runnable fat jar (includes Kotlin stdlib etc.)"
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "cn.enaium.battalion.MainKt"
    }
    from(kotlin.jvm().compilations.getByName("main").output.allOutputs)
    dependsOn(configurations.named("jvmRuntimeClasspath"))
    from({
        configurations.named("jvmRuntimeClasspath").get().map { if (it.isDirectory) it else zipTree(it) }
    })
}

// ============================================================================
// macOS: SDL needs the native-access flag and the main thread for the window.
// ============================================================================
tasks.withType(JavaExec::class.java).configureEach {
    if (OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}