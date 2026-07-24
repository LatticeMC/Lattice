# Lattice

[English](README.md) | [中文](README_zh.md)

A high-performance Minecraft server fork built on [Purpur](https://purpurmc.org), with a native C++ acceleration layer that replaces server hotspots via JNI while maintaining bit-exact compatibility with the reference Java implementations.

## Requirements

- Java 21 (Temurin recommended)
- Git
- CMake >= 3.20 (for building the native library separately)

## Building

### Full Server Build

```bash
./gradlew applyAllPatches
./gradlew build
```

This applies all upstream patches (Paper -> Purpur -> Lattice) and compiles the server jar.

### Native Library (standalone)

The native library lives under `lattice-native/` and can be built independently:

```bash
cmake -S lattice-native -B lattice-native/build -DCMAKE_BUILD_TYPE=Release
cmake --build lattice-native/build --config Release --parallel
```

Required: C++20 toolchain (GCC >= 11, Clang >= 13, or MSVC 19.30+), and a JDK for JNI headers.

CMake options:

| Option | Default | Description |
|---|---|---|
| `LATTICE_ENABLE_SIMD` | `ON` | Runtime-dispatched SIMD specialisations (AVX2, BMI2, NEON) |
| `LATTICE_ENABLE_LTO` | `ON` | Link-time optimisation |
| `LATTICE_BUILD_TESTS` | `OFF` | Build unit test binaries |
| `LATTICE_WARNINGS_AS_ERRORS` | `OFF` | Treat compiler warnings as errors |

### Running Tests

```bash
# Server tests
./gradlew test

# Native tests (requires -DLATTICE_BUILD_TESTS=ON)
cd lattice-native/build && ctest --output-on-failure
```

## Architecture

Lattice follows the standard Purpur patch system. Server modifications are stored as file patches rather than full source files. Over time, Mixin-based hooks will be gradually phased out in favor of direct patches for better maintainability and compatibility with upstream updates:

- `lattice-api/paper-patches/` -- API additions on top of Paper
- `lattice-server/paper-patches/` -- Server changes on top of Paper
- `lattice-server/purpur-patches/` -- Server changes on top of Purpur

The native library is a shared object (`liblattice.so` / `lattice.dll` / `liblattice.dylib`) loaded at startup via JNI. Every native call is guarded by `LatticeNative.isLoaded()` and falls back transparently to the JDK implementation when unavailable. Under `-Dlattice.verify=true`, each native call is shadowed by the JDK reference and outputs are compared.

## Native Modules

The `lattice-native` C++ library accelerates 20+ Minecraft server systems:

| Module | Target |
|---|---|
| Zlib Codec | RegionFile chunk compression via libdeflate |
| NBT Parser | Binary NBT deserialisation |
| Packed Storage | Bit-packed `long[]` operations (scalar, BMI2, AVX2, NEON) |
| Level Propagator | BFS-based light level propagation |
| Block Light Engine | Full block light engine with JNI world queries |
| Heightmap Scan | Multi-section column heightmap scanner |
| Random Tick Filter | Random-tick candidate mask filter |
| Biological AI | Decision layer for 20 animal species |
| Approach/Flee/Home/Water Target Samplers | Local navigation evaluation |
| Spawn Filter | Entity spawn eligibility |
| Entity Visibility | O(N x M) distance scan |
| AABB Query | O(Q x E) AABB intersection scan |
| Collision Sweep | Swept-AABB clamp for entity movement |
| Pathfinder | A* pathfinding with native BinaryHeap and node pool |
| Line-of-Sight | DDA raytrace with section-level skip |
| Density Function | Batched grid fill and evaluator |
| Chunk Noise Sampler | NoiseRouter bundle facade |
| Beardifier | Structure-adjacent terrain beard blending |
| Ore Vein Sampler | Per-block vein decision with Xoroshiro128++ |
| Material Rules | Surface builder rule tree |
| Interpolated Noise | Legacy 1.16-style blended noise |
| Perlin / Octave / Double / Simplex Noise | Noise generation primitives |

All entity and noise modules include SIMD specialisations (AVX2 for x86-64, NEON for AArch64) with runtime CPU feature detection. The build baseline is x86-64-v1 + SSE2 or armv8-a; no `-march=native` is used, so binaries are portable across all CPUs of the same architecture.

## Platform Support

The native library is built and tested in CI on:

| Platform | Compiler | Architecture |
|---|---|---|
| Ubuntu (latest) | GCC | x86-64 |
| Ubuntu (latest) | Clang | x86-64 |
| Windows (latest) | MSVC | x86-64 |
| macOS 14 (ARM) | Clang | AArch64 |

The Java server builds and runs on any platform with a Java 21 runtime.

## Project Structure

```
Lattice/
├── lattice-native/          C++ native acceleration library (CMake, C++20)
│   ├── jni/                 JNI bridge layer
│   ├── src/                 Core implementations (io, world, core)
│   ├── include/lattice/     Public headers
│   └── tests/               Unit tests (doctest)
├── lattice-api/             Server API module
│   └── paper-patches/       API patches on top of Paper
├── lattice-server/          Server implementation module
│   ├── paper-patches/       Server patches on top of Paper
│   ├── purpur-patches/      Server patches on top of Purpur
│   └── src/                 Java sources (bootstrap, nativelib, mixin)
├── scripts/                 Build and upstream sync scripts
├── build.gradle.kts         Root build (paperweight patcher)
├── settings.gradle.kts      Project settings
└── gradle.properties        Version and build configuration
```

## Contributing

See [Purpur's contributing guide](https://github.com/PurpurMC/Purpur/blob/HEAD/CONTRIBUTING.md) for the patch workflow.

## License

[MIT](LICENSE)
