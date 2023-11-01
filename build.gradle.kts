import org.gradle.nativeplatform.platform.internal.ArchitectureInternal
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.gradle.nativeplatform.platform.internal.DefaultOperatingSystem
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithHostTests
import java.lang.System.getenv

plugins {
    kotlin("multiplatform") version "1.9.20"
}

group = "in.procyk"
version = "1.0"

repositories {
    mavenCentral()
}

private val hostOs: DefaultOperatingSystem = DefaultNativePlatform.getCurrentOperatingSystem()
private val hostArchitecture: ArchitectureInternal = DefaultNativePlatform.getCurrentArchitecture()

private val exeExt: String get() = when {
    hostOs.isWindows -> ".exe"
    else -> ""
}

private val cargoAbsolutePath: String
    get() = when {
        hostOs.isWindows -> getenv("USERPROFILE")
        else -> getenv("HOME")
    }
        ?.let(::File)
        ?.resolve(".cargo/bin/cargo$exeExt")
        ?.takeIf { it.exists() }
        ?.absolutePath
        ?: throw GradleException("cargo binary is required to build project but it wasn't found")

fun projectFile(path: String): String = projectDir.resolve(path).absolutePath

private val rustLibAbsolutePath: String
    get() = projectFile(
        path = when {
            hostOs.isWindows -> "rust_lib/target/release/rust_lib.lib"
            else -> "rust_lib/target/release/librust_lib.a"
        }
    )

kotlin {
    applyDefaultHierarchyTemplate()

    val host = when {
        hostOs.isMacOsX && hostArchitecture.isAmd64 -> Host(macosX64())
        hostOs.isMacOsX && hostArchitecture.isArm64 -> Host(macosArm64())
        hostOs.isLinux && hostArchitecture.isAmd64 -> Host(linuxX64())
        hostOs.isWindows && hostArchitecture.isAmd64 -> Host(mingwX64())
        else -> throw GradleException("OS: $hostOs and architecture: $hostArchitecture is not supported in script configuration.")
    }
    tasks.create("binaries") {
        dependsOn("${host.targetName}Binaries")
        doLast { host.renameBinaries() }
    }
    host.target {
        compilations.getByName("main").cinterops {
            create("librust_lib") {
                val buildRustLib by tasks.creating {
                    exec {
                        executable = cargoAbsolutePath
                        args(
                            "build",
                            "--manifest-path", projectFile("rust_lib/Cargo.toml"),
                            "--package", "rust_lib",
                            "--lib",
                            "--release"
                        )
                    }
                }
                tasks.getByName(interopProcessingTaskName) {
                    dependsOn(buildRustLib)
                }
                header(projectFile("rust_lib/target/rust_lib.h"))
            }
        }
        binaries.executable {
            entryPoint = "main"
            baseName = "kotlin-tool"
            linkerOpts += rustLibAbsolutePath
        }
    }
    sourceSets {
        getByName("nativeMain").dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.3.0")
        }
    }
}

class Host(
    private val target: KotlinNativeTargetWithHostTests,
) {
    val targetName: String get() = target.targetName

    fun target(configure: KotlinNativeTargetWithHostTests.() -> Unit): Unit = target.run(configure)

    fun renameBinaries() {
        project.buildDir.resolve("bin/${target.name}").walkTopDown().forEach rename@{
            if (it.extension != "kexe") return@rename
            val renamed = it.parentFile.resolve(it.nameWithoutExtension + exeExt)
            it.renameTo(renamed)
        }
    }
}
