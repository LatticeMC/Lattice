import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.19"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

paperweight {
    upstreams.register("purpur") {
        repo = github("PurpurMC", "Purpur")
        ref = providers.gradleProperty("purpurCommit")

        patchFile {
            path = "purpur-server/build.gradle.kts"
            outputFile = file("lattice-server/build.gradle.kts")
            patchFile = file("lattice-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "purpur-api/build.gradle.kts"
            outputFile = file("lattice-api/build.gradle.kts")
            patchFile = file("lattice-api/build.gradle.kts.patch")
        }
        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("lattice-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("purpurApi") {
            upstreamPath = "purpur-api"
            excludes = listOf("build.gradle.kts", "build.gradle.kts.patch", "paper-patches")
            patchesDir = file("lattice-api/purpur-patches")
            outputDir = file("purpur-api")
        }
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(providers.gradleProperty("latticeJavaVersion").map(String::toInt).getOrElse(21))
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven("https://repo.spongepowered.org/repository/maven-public/")
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        val latticeJavaVersion = providers.gradleProperty("latticeJavaVersion").map(String::toInt).getOrElse(21)
        options.release = latticeJavaVersion
        options.isFork = true
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
        if (latticeJavaVersion == 21) {
            options.compilerArgs.add("--enable-preview")
        }
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven("https://repo.purpurmc.org/snapshots") {
                name = "lattice"
                credentials(PasswordCredentials::class)
            }
        }
    }
}

val nativeSourceDirectory = layout.projectDirectory.dir("lattice-native")
val nativeBuildDirectory = layout.buildDirectory.dir("lattice-native")
val nativeOsName = System.getProperty("os.name").lowercase()
val nativeArchName = System.getProperty("os.arch").lowercase()
val nativeIsWindows = nativeOsName.contains("win")
val nativePlatform = when {
    nativeIsWindows && nativeArchName in setOf("amd64", "x86_64", "x64") -> "windows-x86_64"
    nativeIsWindows && nativeArchName in setOf("aarch64", "arm64") -> "windows-aarch64"
    nativeOsName.contains("linux") && nativeArchName in setOf("amd64", "x86_64", "x64") -> "linux-x86_64"
    nativeOsName.contains("linux") && nativeArchName in setOf("aarch64", "arm64") -> "linux-aarch64"
    (nativeOsName.contains("mac") || nativeOsName.contains("darwin")) && nativeArchName in setOf("amd64", "x86_64", "x64") -> "macos-x86_64"
    (nativeOsName.contains("mac") || nativeOsName.contains("darwin")) && nativeArchName in setOf("aarch64", "arm64") -> "macos-aarch64"
    else -> error("Unsupported native build platform: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
}
val nativeLibraryName = when {
    nativeIsWindows -> "lattice.dll"
    nativeOsName.contains("linux") -> "liblattice.so"
    else -> "liblattice.dylib"
}
val nativeLibraryFile = nativeBuildDirectory.map { it.file(nativeLibraryName) }

val configureLatticeNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Configure the Lattice C++ native library with CMake and Ninja"
    inputs.file(nativeSourceDirectory.file("CMakeLists.txt"))
    outputs.file(nativeBuildDirectory.map { it.file("CMakeCache.txt") })

    doFirst {
        val cmake = providers.gradleProperty("latticeCmakeExecutable").orNull
            ?: if (nativeIsWindows && file("C:/Program Files/CMake/bin/cmake.exe").isFile) {
                "C:/Program Files/CMake/bin/cmake.exe"
            } else {
                "cmake"
            }
        val ninja = providers.gradleProperty("latticeNinjaExecutable").orNull
            ?: if (nativeIsWindows && file("C:/Program Files/Ninja/ninja.exe").isFile) {
                "C:/Program Files/Ninja/ninja.exe"
            } else {
                "ninja"
            }
        val arguments = mutableListOf(
            cmake,
            "-S", nativeSourceDirectory.asFile.absolutePath,
            "-B", nativeBuildDirectory.get().asFile.absolutePath,
            "-G", "Ninja",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_MAKE_PROGRAM=$ninja",
        )
        if (nativeIsWindows) {
            val llvmMingwHome = providers.gradleProperty("latticeLlvmMingwHome").orNull
                ?: providers.environmentVariable("LLVM_MINGW_HOME").orNull
                ?: "C:/Program Files/llvm-mingw"
            val cCompiler = file("$llvmMingwHome/bin/clang.exe")
            val cxxCompiler = file("$llvmMingwHome/bin/clang++.exe")
            if (!cCompiler.isFile || !cxxCompiler.isFile) {
                throw GradleException("llvm-mingw not found at $llvmMingwHome; set -PlatticeLlvmMingwHome=<path>")
            }
            arguments.add("-DCMAKE_C_COMPILER=${cCompiler.absolutePath.replace('\\', '/')}")
            arguments.add("-DCMAKE_CXX_COMPILER=${cxxCompiler.absolutePath.replace('\\', '/')}")
        }
        environment("JAVA_HOME", System.getProperty("java.home"))
        commandLine(arguments)
    }
}

val buildLatticeNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the Lattice C++ native library"
    dependsOn(configureLatticeNative)
    inputs.files(fileTree(nativeSourceDirectory) {
        include("CMakeLists.txt", "include/**", "jni/**", "src/**")
    })
    outputs.file(nativeLibraryFile)

    doFirst {
        val cmake = providers.gradleProperty("latticeCmakeExecutable").orNull
            ?: if (nativeIsWindows && file("C:/Program Files/CMake/bin/cmake.exe").isFile) {
                "C:/Program Files/CMake/bin/cmake.exe"
            } else {
                "cmake"
            }
        commandLine(cmake, "--build", nativeBuildDirectory.get().asFile.absolutePath, "--parallel")
    }
}

project(":lattice-server") {
    tasks.named<ProcessResources>("processResources") {
        dependsOn(buildLatticeNative)
        from(nativeLibraryFile) {
            into("META-INF/native/$nativePlatform")
        }
    }
}

tasks.register("printMinecraftVersion") {
    doLast {
        println(providers.gradleProperty("mcVersion").get().trim())
    }
}

tasks.register("printLatticeVersion") {
    doLast {
        println(project.version)
    }
}
