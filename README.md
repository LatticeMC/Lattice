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

Target samplers use a shared Java gate by default; set `-Dlattice.nativeTargetSampler.minWork=N`
only after measuring the target CPU. The work unit is `candidateCount * max(1, obstacleCount)`.

## Private Native PGO tutorial

<details>
<summary>Show the private PGO build and training guide</summary>

The normal release and an unspecified `LATTICE_PGO_MODE` remain LTO-only. Native PGO is a
private, explicit build workflow: it neither changes the public release nor makes PGO
artifacts available through the normal loader.

The formal PoC below is for Linux x86_64. Install a Clang toolchain that includes
`llvm-profdata`, plus CMake, Ninja, and a JDK. Both PGO phases require a single-config
Release Clang build on x86_64 with LTO enabled.

1. Configure and build an instrumented private library from the repository root:

   ```bash
   cmake -S lattice-native -B build/native-pgo-generate -G Ninja \
     -DCMAKE_BUILD_TYPE=Release \
     -DCMAKE_C_COMPILER=clang \
     -DCMAKE_CXX_COMPILER=clang++ \
     -DLATTICE_ENABLE_LTO=ON \
     -DLATTICE_PGO_MODE=GENERATE
   cmake --build build/native-pgo-generate
   ```

2. Before every training round, clear the raw-profile directory. Start the server with the
   generated library and run a representative normal workload; then stop the server
   cleanly so all profile data is written.

   ```bash
   rm -rf /absolute/path/to/pgo/raw
   mkdir -p /absolute/path/to/pgo/raw
   export LLVM_PROFILE_FILE='/absolute/path/to/pgo/raw/lattice-%m-%p.profraw'
   java '-Dlattice.native.path=/absolute/path/to/build/native-pgo-generate/liblattice-pgo-generate.so' \
     -jar /absolute/path/to/lattice-server.jar nogui
   ```

3. After the clean shutdown, merge the collected raw profiles:

   ```bash
   llvm-profdata merge -o /absolute/path/to/pgo/lattice.profdata \
     /absolute/path/to/pgo/raw/*.profraw
   ```

4. Build a separate strict profile-use library, then start the server with its absolute
   library path:

   ```bash
   cmake -S lattice-native -B build/native-pgo-use -G Ninja \
     -DCMAKE_BUILD_TYPE=Release \
     -DCMAKE_C_COMPILER=clang \
     -DCMAKE_CXX_COMPILER=clang++ \
     -DLATTICE_ENABLE_LTO=ON \
     -DLATTICE_PGO_MODE=USE \
     -DLATTICE_PGO_PROFILE=/absolute/path/to/pgo/lattice.profdata \
     -DLATTICE_PGO_STRICT=ON
   cmake --build build/native-pgo-use
   java '-Dlattice.native.path=/absolute/path/to/build/native-pgo-use/liblattice-pgo-use.so' \
     -jar /absolute/path/to/lattice-server.jar nogui
   ```

Do not reuse a `.profdata` file across source revisions, compiler/toolchain versions,
operating systems, or architectures. If profile merging or the strict `USE` build fails,
discard that training round and retrain in a matching environment.

Windows x86_64 with LLVM-MinGW can run the already-verified local smoke check:

```powershell
pwsh -File .\scripts\native-pgo\Invoke-NativePgoSmoke.ps1 `
  -LlvmMingwRoot 'C:\llvm-mingw' `
  -JdkRoot 'C:\Program Files\Zulu\zulu-25'
```

It is not representative training and does not replace the Linux PoC. On Windows, use
the corresponding absolute DLL paths, such as `lattice-pgo-generate.dll` and
`lattice-pgo-use.dll`, instead of the Linux `.so` paths above.

</details>

## Contributing

Lattice uses the Paperweight patch workflow. See [Purpur's contributing guide](https://github.com/PurpurMC/Purpur/blob/HEAD/CONTRIBUTING.md) before editing patched upstream sources. Imported or adapted optimizations must retain their original author, source, and license attribution.

## License

Lattice-original code and the derived server/API distribution are licensed under GNU GPL v3 (GPL-3.0-only). Imported or adapted files and patches retain their original licenses and attribution when their headers state one; third-party dependencies remain under their own licenses.

See [LICENSE](LICENSE), [GPL-3.0](licenses/GPL.md), and [LGPL-3.0](licenses/LGPL-3.0.txt).

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
