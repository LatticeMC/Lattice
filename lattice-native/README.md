# lattice-native

`lattice-native` is the C++20 native component of Lattice.  It exposes
performance-sensitive Minecraft server operations to Java through JNI while
retaining Java-visible behaviour at the boundary.

## Scope

The library groups native work around these server data paths:

- I/O: zlib compression and NBT-related data paths, using libdeflate where
  applicable.
- Chunk data: packed palettes and light propagation.
- Simulation queries: entity queries, collision, pathfinding, and AI sampling.
- World generation: noise, density evaluation, surface rules, ore selection,
  and ticking.

The JNI boundary owns conversion, lifetime, and error translation.  Native
implementations preserve the documented Java-side result and failure
semantics; they do not turn a native optimisation into a different gameplay or
data-format contract.

## Architecture principles

- C++20 is used for native code and JNI is the only Java/native integration
  boundary.
- CPU-specific code is isolated from the portable baseline and selected at
  runtime rather than by compiling for the build machine.
- `-march=native` and `-ffast-math` are not used.  The baseline remains
  portable, and floating-point-sensitive paths retain their required
  semantics.
- libdeflate is pinned to `v1.20` and obtained with CMake `FetchContent`; it
  is linked statically rather than selected from a system installation.
- `LATTICE_ENABLE_LTO` controls link-time optimisation for Release builds.

## Build and test

Build with CMake 3.20 or newer, a C++20 toolchain (GCC 11+, Clang 13+, or MSVC
19.30+), and a JDK that supplies `jni.h`:

```bash
cmake -S lattice-native -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native
```

The output is `lattice.dll` on Windows, `liblattice.dylib` on macOS, and
`liblattice.so` on Linux and FreeBSD.  Platform packaging is responsible for
placing that library where the Java loader expects it.

Enable the native test collection with:

```bash
cmake -S lattice-native -B build/native-test -DCMAKE_BUILD_TYPE=Release -DLATTICE_BUILD_TESTS=ON
cmake --build build/native-test
ctest --test-dir build/native-test --output-on-failure
```

The test collection contains 31 CTest tests.  For applicable Java/native
paths, `-Dlattice.verify=true` enables shadow/parity diagnostics against the
Java reference; it is not a claim that every module has one universal parity
oracle.

## CMake options

| Option | Default | Meaning |
|---|---:|---|
| `LATTICE_ENABLE_SIMD` | `ON` | Build runtime-dispatched SIMD specialisations. |
| `LATTICE_ENABLE_LTO` | `ON` | Request link-time optimisation for Release builds. |
| `LATTICE_BUILD_TESTS` | `OFF` | Build the CTest collection and differential-verification tools. |
| `LATTICE_WARNINGS_AS_ERRORS` | `OFF` | Treat compiler warnings as errors. |

## Runtime dispatch and semantic constraints

The dispatcher selects among scalar, SSE2, AVX2, BMI2, AVX512, and NEON
implementations only after checking runtime CPU and operating-system support.
The selection can be constrained for diagnosis with
`LATTICE_CPU_FORCE_SCALAR=1` or `LATTICE_CPU_DISABLE=avx512,bmi2`.

Specialised implementations must retain the portable path's observable
results, including bit-packed data conventions, JNI error behaviour, and the
non-fast-math floating-point rules used by light and world-generation code.
Runtime selection changes execution strategy; it does not alter the Java API
or serialized data semantics.

## Native PGO design

The [Native PGO plan](../docs/native-pgo-plan.md) documents the first private
PGO build loop and the later training/release work that is still pending.

| Option | Default | Meaning |
|---|---:|---|
| `LATTICE_PGO_MODE` | `OFF` | `OFF`, `GENERATE`, or `USE`; values are uppercase and PGO modes are private. |
| `LATTICE_PGO_PROFILE` | empty | Required only by `USE`; a non-empty `.profdata` file. |
| `LATTICE_PGO_STRICT` | `OFF` | Valid only with `USE`; promotes LLVM stale/unprofiled instrumentation diagnostics to errors. |

`OFF` is the default and preserves the normal `lattice` LTO-only build.
Public `native-latest`, Gradle, and the Java loader remain LTO-only; neither
private output is discovered or published by those paths. `GENERATE` creates
`lattice-pgo-generate`, and `USE` creates `lattice-pgo-use`. Both require a
single-config Release Clang build on 64-bit x86_64 with LTO enabled. Linux is
the formal PoC target; Windows x86_64 LLVM-MinGW is local experimental smoke
coverage only. PGO build trees reject `cmake --install`.

For a private DLL smoke load, pass an absolute library path to the existing
loader override, for example `-Dlattice.native.path=C:\absolute\lattice-pgo-generate.dll`.
This does not make a PGO artifact public or change normal loading behaviour.
