import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.security.MessageDigest

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

data class NativeBundleAsset(val assetName: String, val platformTag: String, val libraryName: String)

val nativeBundleAssets = listOf(
    NativeBundleAsset("lattice-native-linux-x86_64.so", "linux-x86_64", "liblattice.so"),
    NativeBundleAsset("lattice-native-linux-aarch64.so", "linux-aarch64", "liblattice.so"),
    NativeBundleAsset("lattice-native-windows-x86_64.dll", "windows-x86_64", "lattice.dll"),
    NativeBundleAsset("lattice-native-windows-aarch64.dll", "windows-aarch64", "lattice.dll"),
    NativeBundleAsset("lattice-native-macos-x86_64.dylib", "macos-x86_64", "liblattice.dylib"),
    NativeBundleAsset("lattice-native-macos-aarch64.dylib", "macos-aarch64", "liblattice.dylib"),
    NativeBundleAsset("lattice-native-freebsd-x86_64.so", "freebsd-x86_64", "liblattice.so"),
    NativeBundleAsset("lattice-native-freebsd-aarch64.so", "freebsd-aarch64", "liblattice.so"),
)
val nativeBundleDirectory = providers.gradleProperty("latticeNativeBundleDir").map { file(it) }
val nativeDigestManifest = layout.buildDirectory.file("generated-resources/native/META-INF/native/digests.properties")

val prepareLatticeNativeBundle by tasks.registering {
    group = "build"
    description = "Validate an optional multi-platform native bundle and generate its trusted digest manifest"
    onlyIf { nativeBundleDirectory.isPresent }
    inputs.dir(nativeBundleDirectory)
    outputs.file(nativeDigestManifest)

    doLast {
        val bundleDirectory = nativeBundleDirectory.get()
        if (!bundleDirectory.isDirectory) {
            throw GradleException("latticeNativeBundleDir is not a directory: ${bundleDirectory.absolutePath}")
        }
        val manifest = nativeDigestManifest.get().asFile
        manifest.parentFile.mkdirs()
        val contents = buildString {
            for (asset in nativeBundleAssets) {
                val assetFile = bundleDirectory.resolve(asset.assetName)
                val checksumFile = bundleDirectory.resolve("${asset.assetName}.sha256")
                if (!assetFile.isFile || !checksumFile.isFile) {
                    throw GradleException("Missing native bundle asset or checksum: ${asset.assetName}")
                }
                val checksumParts = checksumFile.readText(Charsets.UTF_8).trim().split(Regex("\\s+"), limit = 2)
                if (checksumParts.size != 2 || checksumParts[1] != asset.assetName
                    || !checksumParts[0].matches(Regex("[0-9A-Fa-f]{64}"))) {
                    throw GradleException("Invalid native checksum record: ${checksumFile.absolutePath}")
                }
                val expected = checksumParts[0].lowercase()
                val digest = MessageDigest.getInstance("SHA-256")
                assetFile.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) digest.update(buffer, 0, read)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (actual != expected) {
                    throw GradleException("Native checksum mismatch for ${asset.assetName}: expected $expected, got $actual")
                }
                append(asset.assetName).append('=').append(expected).append('\n')
            }
        }
        manifest.writeText(contents, Charsets.UTF_8)
    }
}

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
        val bundleDirectory = nativeBundleDirectory.orNull
        inputs.property("latticeNativeBundleMode", bundleDirectory?.absolutePath ?: "local")
        doFirst {
            if (bundleDirectory == null) {
                delete(nativeDigestManifest.get().asFile)
                for (asset in nativeBundleAssets) {
                    delete(destinationDir.resolve("META-INF/native/${asset.platformTag}"))
                }
            }
        }
        if (bundleDirectory == null) {
            dependsOn(buildLatticeNative)
            from(nativeLibraryFile) {
                into("META-INF/native/$nativePlatform")
            }
        } else {
            dependsOn(prepareLatticeNativeBundle)
            for (asset in nativeBundleAssets) {
                from(bundleDirectory.resolve(asset.assetName)) {
                    into("META-INF/native/${asset.platformTag}")
                    rename { asset.libraryName }
                }
            }
            from(nativeDigestManifest) {
                into("META-INF/native")
            }
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
