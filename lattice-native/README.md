# lattice-native

High-performance native optimisations for Minecraft 1.21.11 server hotspots,
exposed to the Java side via JNI.

## Status: clean skeleton (T1 rewrite in progress)

This subproject was previously populated with ~47 kloc of speculative C++ in
which only one module (Pathfinder, itself a stub) was reachable from Java.
That code has been archived under [`attic/`](./attic) and is not built.
See the conversation archived alongside this repo for the full audit.

What **is** built right now:

```
lattice-native/
├── CMakeLists.txt                     # clean skeleton, LTO-enabled, x86-64-v1 + SSE2 baseline
├── include/
│   └── lattice/
│       ├── config.hpp                 # compile-time flags
│       ├── dispatch.hpp               # runtime CPU-feature snapshot
│       └── lattice.hpp                # umbrella header
├── jni/
│   ├── jni_helper.hpp                 # exception-free JNI helpers
│   └── loader.cpp                     # JNI_OnLoad + Java_…_nativeCpuSummary
└── src/
    └── core/
        └── cpu/
            └── detect.cpp             # CPUID / getauxval / sysctl population
```

The resulting `liblattice.so` / `lattice.dll` exposes exactly one Java-visible
symbol today:

```java
// com.latticemc.lattice.nativelib.LatticeNative
static native String nativeCpuSummary();
```

which returns a line like `lattice cpu: vendor=Intel tier=AVX2 +BMI2(fast) family=0x6 model=0x8E`.

## T1 hotspot modules

Three Minecraft 1.21.11 hotspots have been selected for native rewrites;
each will be added as an independent module whose behaviour is bit-exact
against the reference Java implementation (verified by `-Dlattice.verify=true`):

| Module | Java package (lattice-server) | Minecraft target | Status |
|---|---|---|---|
| **Chunk serialiser** (libdeflate zlib)        | `com.latticemc.lattice.nativelib.NativeChunkSerializer` | `RegionFile` COMPRESSION_ZLIB path + `Inflater/Deflater` | **Phase 1 ✓** |
| **Paletted storage** (bit-packed `long[]` ops) | `com.latticemc.lattice.nativelib.NativePaletteOps` | `PalettedContainer`, `PackedIntegerArray` | **Phase 1 ✓** (scalar; BMI2/SIMD planned) |
| **Light engine** (`LevelPropagator` + `ChunkLightProvider`) | `com.latticemc.lattice.nativelib.NativeLightEngine` | `ChunkBlockLightProvider`, `ChunkSkyLightProvider`, `LevelPropagator` | **Phase 1 ✓** (BFS in C++, world queries via JNI callback) |
| **ChunkNoiseSampler facade** (NoiseRouter bundle + per-channel cache) | `com.latticemc.lattice.nativelib.NativeChunkNoiseSampler` | `ChunkNoiseSampler` (router-driven density-function sampling) | **Worldgen-6 ✓** |
| **OreVeinSampler** (per-block vein decision + Xoroshiro128++) | `com.latticemc.lattice.nativelib.NativeOreVeinSampler` | `net.minecraft.world.gen.OreVeinSampler` | **Worldgen-8 ✓** |
| **DensityFunction batched grid fill** (NativeDensityFunction.evaluateGrid) | `com.latticemc.lattice.nativelib.NativeDensityFunction#evaluateGrid` | `DensityFunction.fill` / `DensityInterpolator` cell pre-fill | **Worldgen-9 ✓** |
| **DensityFunction Spline node** (cubic-Hermite + recursive sub-splines) | `com.latticemc.lattice.nativelib.NativeDensityFunction#addSpline` | `net.minecraft.util.math.Spline` / `DensityFunctionTypes.Spline` | **Worldgen-10 ✓** |
| **DensityFunction FindTopSurface node** (downward y-scan for density > 0) | `com.latticemc.lattice.nativelib.NativeDensityFunction#addFindTopSurface` | `DensityFunctionTypes.FindTopSurface` | **Worldgen-11 ✓** |
| **InterpolatedNoiseSampler** (1.16-style blended noise; `old_blended_noise` DF node) | `com.latticemc.lattice.nativelib.NativeInterpolatedNoise` + `NativeDensityFunction#addOldBlendedNoise` | `net.minecraft.util.math.noise.InterpolatedNoiseSampler` | **Worldgen-12 ✓** |
| **DensityInterpolator** (per-cell trilinear blend driving `kInterpolated`) | `com.latticemc.lattice.nativelib.NativeDensityFunction.Cache#startInterpolation`, `prepareInterpolators`, `setStartDensity`, `setEndDensity`, `setStartDensityRow`, `setEndDensityRow`, `swapBuffers`, `onSampledCellCorners`, `interpolateY/X/Z`, `stopInterpolation` | `net.minecraft.world.gen.chunk.ChunkNoiseSampler.DensityInterpolator` | **Worldgen-13 ✓** |

### NativeChunkSerializer — Phase 1 scope

Replaces `java.util.zip.Inflater` / `Deflater` with a libdeflate-backed
implementation for the zlib format only. Java-side NBT parsing via
`NbtIo` is untouched. Every call is guarded by `LatticeNative.isLoaded()`
and falls back transparently to JDK streams when the native library
isn't available. Under `-Dlattice.verify=true`, every native call is
shadowed by the JDK reference and the outputs are compared
(round-trip for deflate, since zlib output bytes aren't required to be
bit-identical between encoders).

A later **Phase 2** may flatten NBT parsing into native with an off-heap
tree representation; this is gated on measurements showing that NBT
parsing (rather than decompression) is the dominant cost after Phase 1.

Compression library: libdeflate, pinned at tag `v1.20`, pulled in via
CMake `FetchContent` (static link, no system dependency).

## Building

Requires CMake ≥ 3.20, a C++20 toolchain (GCC ≥ 11, Clang ≥ 13, MSVC 19.30+),
and a JDK (for `<jni.h>`).

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build
```

Options:

| Option | Default | Description |
|---|---|---|
| `LATTICE_ENABLE_SIMD` | `ON` | Compile in the SIMD specialisations (runtime-dispatched) |
| `LATTICE_ENABLE_LTO` | `ON` | Link-time optimisation in Release |
| `LATTICE_BUILD_TESTS` | `OFF` | Build unit tests + diff-verification harness |
| `LATTICE_WARNINGS_AS_ERRORS` | `OFF` | Promote warnings to errors |

Runtime environment variables:

| Variable | Purpose |
|---|---|
| `LATTICE_CPU_FORCE_SCALAR=1` | Disable all SIMD specialisations |
| `LATTICE_CPU_DISABLE=avx512,bmi2` | Disable specific ISA extensions |

World-generation SIMD coverage:

- Density-function evaluation has an AVX-512 path (with its `AVX512DQ`
  guard). Perlin, DoublePerlin and Simplex remain AVX2/scalar: their current
  permutation lookups and branch-heavy topology do not have a verified
  8-lane implementation that preserves the non-FMA/floor semantics.
- Heightmap scanning and packed palette access intentionally remain on their
  AVX2/BMI2 paths because a wider irregular scan has no demonstrated safe
  benefit yet; scalar/NEON fallbacks remain available.
- `-Dlattice.nativeCpu=avx2` and `scalar` cap dispatch so AVX-512 objects are
  never entered; `auto`/`avx512` still require CPUID/XCR0 support.

Notes:

- The build **deliberately** does not use `-march=native`; the resulting
  binary is portable at the baseline-ISA level (x86-64-v1 + SSE2, or
  armv8-a), with SIMD specialisations runtime-dispatched.
- `-ffast-math` is **not** enabled — light-engine float math must remain
  bit-exact with the JVM.
- The library is built with `-fno-exceptions -fno-rtti` on non-MSVC
  toolchains. All helpers in `jni/jni_helper.hpp` are exception-free.
