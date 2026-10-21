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
    alias(libs.plugins.embed.resource)
    alias(libs.plugins.android.kmp.library)
}

// The game's assets (src/commonMain/resources: data JSON, images, sounds) are embedded by
// cn.enaium.embedresource: the application ships them with the code and materializes them
// into a writable directory at startup (see EmbeddedAssets), so no platform bundle or
// filesystem copy of `src/commonMain/resources/` is needed at runtime (Android, packaged builds).
embedres {
    packageName.set("cn.enaium.battalion.embedded")
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

    // --- Android (JVM/ART: SDL3 + the JNI bridge from sdl-kmp's Android AARs) ---
    // The Android application lives in :androidApp; this module publishes the shared code as an
    // Android library (AGP 9 requires com.android.kotlin.multiplatform.library for KMP).
    android {
        namespace = "cn.enaium.battalion"
        compileSdk = 36
        minSdk = 24

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    // --- Native desktop targets --------------------------------------------
    macosArm64 {
        binaries.executable {
            entryPoint = "cn.enaium.battalion.main"
        }
    }

    macosX64 {
        binaries.executable {
            entryPoint = "cn.enaium.battalion.main"
        }
    }

    linuxX64 {
        binaries.executable {
            entryPoint = "cn.enaium.battalion.main"
        }
    }

    linuxArm64 {
        binaries.executable {
            entryPoint = "cn.enaium.battalion.main"
            // Cross-linking from x86_64 needs the aarch64 libgcc for the atomic builtins
            // (__aarch64_cas*/swp*/ldadd*) that SDL's static archives reference. K/N links its
            // own runtime/compiler-rt, so the cross libgcc has to be supplied explicitly.
            val gccCross = listOf(
                "/usr/lib/gcc-cross/aarch64-linux-gnu",
                "/usr/lib/gcc/aarch64-linux-gnu",
            ).flatMap { root -> File(root).listFiles()?.toList().orEmpty() }
                .firstOrNull { File(it, "libgcc.a").isFile }
            if (gccCross != null) {
                linkerOpts(
                    "-Wl,--allow-multiple-definition",
                    "-Wl,--whole-archive",
                    File(gccCross, "libgcc.a").absolutePath,
                    "-Wl,--no-whole-archive",
                )
            }
        }
    }

    mingwX64 {
        binaries.executable {
            entryPoint = "cn.enaium.battalion.main"
            // SDL's Windows HID/COM support resolves through MinGW import libraries.
            linkerOpts("-loleaut32", "-lhid")
        }
    }

    // --- Shared source sets -------------------------------------------------
    sourceSets {
        commonMain.dependencies {
            // SDL core (window, renderer, input). Its Android variant brings SDLActivity and the
            // per-ABI libsdl_jni.so; image/ttf/mixer are not published for Android JVM and live
            // in sdlAddonsMain instead.
            api(libs.sdl.kmp)

            // Kotlinx ecosystem
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)

        }

        // SDL_image / SDL_ttf / SDL_mixer bindings, shared by the desktop JVM and native
        // targets. Android JVM has no artifacts for them and implements the same expect API
        // with the platform's BitmapFactory / Paint / SoundPool (androidMain).
        val sdlAddonsMain = create("sdlAddonsMain") {
            dependsOn(commonMain.get())
        }
        sdlAddonsMain.dependencies {
            implementation(libs.sdl.image.kmp)
            implementation(libs.sdl.ttf.kmp)
            implementation(libs.sdl.mixer.kmp)
        }
        // JVM and Android share java.io-based file I/O (Android's external files directory is
        // a normal path; FileKit's Android path handling could not list it).
        val jvmSharedMain = create("jvmSharedMain") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmSharedMain)
        getByName("androidMain").dependsOn(jvmSharedMain)

        jvmMain.get().dependsOn(sdlAddonsMain)
        val nativeMain = maybeCreate("nativeMain")
        nativeMain.dependsOn(commonMain.get())
        nativeMain.dependencies {
            implementation(libs.kotlinx.io.core)
        }
        getByName("macosArm64Main").dependsOn(nativeMain)
        getByName("macosX64Main").dependsOn(nativeMain)
        getByName("linuxX64Main").dependsOn(nativeMain)
        getByName("linuxArm64Main").dependsOn(nativeMain)
        getByName("mingwX64Main").dependsOn(nativeMain)
        nativeMain.dependsOn(sdlAddonsMain)

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