<div align="center">

# Lattice

[![Build](https://github.com/LatticeMC/Lattice/actions/workflows/build.yml/badge.svg)](https://github.com/LatticeMC/Lattice/actions/workflows/build.yml)
[![Native CI](https://github.com/LatticeMC/Lattice/actions/workflows/native.yml/badge.svg)](https://github.com/LatticeMC/Lattice/actions/workflows/native.yml)
[![GitHub release](https://img.shields.io/github/v/release/LatticeMC/Lattice?include_prereleases)](https://github.com/LatticeMC/Lattice/releases)

A performance-oriented Minecraft server fork built on Purpur, with native C++ acceleration for selected server hotspots.

**English** | [中文](README_zh.md)

</div>

> [!WARNING]
> Lattice is under active development. Back up your worlds and configuration before switching an existing server, and validate plugins in a test environment before production use.

## Features

- Built on [Purpur](https://purpurmc.org/) and compatible with the Paper and Purpur plugin ecosystems.
- Native C++ implementations for selected pathfinding, entity, world generation, compression, and data-processing hotspots.
- Runtime SIMD dispatch for supported x86-64 and AArch64 processors without requiring `-march=native`.
- Explicit JNI boundaries with Java fallback when native acceleration is unavailable or a request is unsupported.
- Verification modes and parity tests for comparing accelerated paths with their Java reference implementations.
- Paperweight source and feature patches that keep upstream changes reviewable.

## Download

Development builds are published by [GitHub Actions](https://github.com/LatticeMC/Lattice/actions/workflows/build.yml). Tagged builds are available from [GitHub Releases](https://github.com/LatticeMC/Lattice/releases).

Only download Lattice from project-controlled sources. Third-party builds may use different patches, native libraries, or licenses.

## Documentation

- Report defects and compatibility problems through [GitHub Issues](https://github.com/LatticeMC/Lattice/issues).
- Paper configuration and administration documentation is available from the [Paper documentation](https://docs.papermc.io/paper/).
- Purpur-specific configuration is documented by the [Purpur project](https://purpurmc.org/docs/).

## Building

Requirements:

- Java 21
- Git
- CMake 3.20 or newer
- Ninja
- A C++20 compiler

Build the patched server and Paperclip JAR:

```bash
./gradlew applyAllPatches
./gradlew :lattice-server:createMojmapPaperclipJar
```

On Windows, use `gradlew.bat`. The build looks for LLVM-MinGW in `C:/Program Files/llvm-mingw`; override it with `-PlatticeLlvmMingwHome=<path>` when necessary.

<details>
<summary>Build and test the native library separately</summary>

```bash
cmake -S lattice-native -B lattice-native/build -DCMAKE_BUILD_TYPE=Release -DLATTICE_BUILD_TESTS=ON
cmake --build lattice-native/build --config Release --parallel
ctest --test-dir lattice-native/build --output-on-failure
```

Important CMake options:

| Option | Default | Purpose |
| --- | --- | --- |
| `LATTICE_ENABLE_SIMD` | `ON` | Build runtime-dispatched SIMD implementations |
| `LATTICE_ENABLE_LTO` | `ON` | Enable link-time optimization |
| `LATTICE_BUILD_TESTS` | `OFF` | Build native tests |
| `LATTICE_WARNINGS_AS_ERRORS` | `OFF` | Treat native compiler warnings as errors |

</details>

## Native Acceleration

The native library is loaded through JNI as `liblattice.so`, `lattice.dll`, or `liblattice.dylib`. Accelerated operations keep an explicit Java fallback. `-Dlattice.verify=true` enables reference comparisons for supported paths and is intended for testing rather than production benchmarking.

Current acceleration work covers areas including:

- pathfinding and path-type classification;
- entity visibility, AABB queries, and collision scans;
- packed storage, NBT, compression, and heightmaps;
- density functions, terrain noise, ore veins, and material rules;
- light propagation and selected AI target samplers.

Availability and profitability depend on the request shape, CPU, world state, and runtime configuration. A native implementation is not automatically selected when the Java path is faster for a request.

## Contributing

Lattice uses the Paperweight patch workflow. See [Purpur's contributing guide](https://github.com/PurpurMC/Purpur/blob/HEAD/CONTRIBUTING.md) before editing patched upstream sources. Imported or adapted optimizations must retain their original author, source, and license attribution.

## License

Lattice inherits licenses from its upstream projects. The derived server and API distribution is GPL-3.0. Standalone Lattice-authored code is MIT unless a file or patch header states otherwise. Imported patches retain their original licenses and attribution.

See [LICENSE](LICENSE), [GPL-3.0](licenses/GPL.md), [MIT](licenses/MIT.md), and [LGPL-3.0](licenses/LGPL-3.0.txt).

## Credits

Lattice builds on work from the Minecraft server and performance community, including:

- [Paper](https://papermc.io/) and [Purpur](https://purpurmc.org/)
- [Leaf](https://github.com/Winds-Studio/Leaf)
- [Luminol](https://github.com/LuminolMC/Luminol)
- [Moonrise](https://github.com/Tuinity/Moonrise)
- [libdeflate](https://github.com/ebiggers/libdeflate)

Individual imported patches carry more specific attribution in their patch headers.

## Special Thanks

- [YourKit](https://www.yourkit.com/) supports open source projects with profiling tools used to investigate Java and native performance.
- [IntelliJ IDEA](https://www.jetbrains.com/idea/) provides the development environment used for Java and JVM-side work on Lattice.
