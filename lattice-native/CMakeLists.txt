cmake_minimum_required(VERSION 3.20)
project(lattice-native VERSION 0.1.0 LANGUAGES CXX)

# ============================================================================
# Lattice Native — clean skeleton (T1 rewrite, post-attic cleanup)
#
# Modules currently wired:
#   (none yet — this is the skeleton committed during clean-up phase)
#
# Modules planned (T1):
#   - src/world/light/        → NativeLightEngine
#   - src/world/palette/      → NativePaletteOps
#   - src/io/anvil/           → NativeChunkSerializer (RegionFile + NbtIo)
#
# Compression: libdeflate (zlib format only, matching Minecraft RegionFile
# COMPRESSION_ZLIB = 2). Linked via find_package or vendored — TBD when the
# first IO module is implemented.
#
# CPU dispatch: every SIMD-bearing translation unit is compiled into its own
# OBJECT library with the minimal `-m<feature>` flags needed; the main library
# selects implementations at runtime via `lattice::cpu::features()`.
# This is set up here as scaffolding; concrete TUs will be added per-module.
# ============================================================================

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# ---------------------------------------------------------------------------
# Build options
# ---------------------------------------------------------------------------
option(LATTICE_ENABLE_SIMD     "Enable SIMD specialisations (runtime-dispatched)" ON)
option(LATTICE_ENABLE_LTO      "Enable link-time optimisation in Release builds" ON)
option(LATTICE_BUILD_TESTS     "Build unit tests + diff-verification harness" OFF)
option(LATTICE_WARNINGS_AS_ERRORS "Promote warnings to errors" OFF)

# ---------------------------------------------------------------------------
# Dependencies
# ---------------------------------------------------------------------------
find_package(JNI REQUIRED)

# libdeflate — pinned source build via FetchContent. We deliberately don't
# `find_package` system libdeflate: developers should get the same version
# regardless of distro, and the static binary is small (~120 KB).
include(FetchContent)
set(LIBDEFLATE_BUILD_SHARED_LIB     OFF CACHE BOOL "" FORCE)
set(LIBDEFLATE_BUILD_GZIP           OFF CACHE BOOL "" FORCE)
set(LIBDEFLATE_USE_SHARED_LIB       OFF CACHE BOOL "" FORCE)
set(LIBDEFLATE_BUILD_TESTS          OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    libdeflate
    GIT_REPOSITORY https://github.com/ebiggers/libdeflate.git
    GIT_TAG        v1.20
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(libdeflate)

# doctest — single-header C++ test framework. Only fetched when tests
# are enabled; the static FetchContent_Declare is still cheap even when
# unused because no actual download happens until MakeAvailable.
if(LATTICE_BUILD_TESTS)
    set(DOCTEST_NO_INSTALL ON CACHE BOOL "" FORCE)
    set(DOCTEST_WITH_TESTS OFF CACHE BOOL "" FORCE)
    set(DOCTEST_WITH_MAIN_IN_STATIC_LIB OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(
        doctest
        GIT_REPOSITORY https://github.com/doctest/doctest.git
        GIT_TAG        v2.4.11
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(doctest)
endif()

# ---------------------------------------------------------------------------
# Architecture detection
# ---------------------------------------------------------------------------
include(CheckCXXCompilerFlag)

set(LATTICE_ARCH_X86 OFF)
set(LATTICE_ARCH_ARM64 OFF)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64|i[3-6]86)$")
    set(LATTICE_ARCH_X86 ON)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64)$")
    set(LATTICE_ARCH_ARM64 ON)
endif()

message(STATUS "Lattice arch: x86=${LATTICE_ARCH_X86} arm64=${LATTICE_ARCH_ARM64}")

# ---------------------------------------------------------------------------
# Baseline compile flags (portable; SIMD flags are scoped to OBJECT libraries)
# ---------------------------------------------------------------------------
add_library(lattice_baseline_flags INTERFACE)

if(MSVC)
    target_compile_options(lattice_baseline_flags INTERFACE
        /W4 /permissive- /Zc:__cplusplus /utf-8
        $<$<CONFIG:Release>:/O2 /Ob2 /Oi /Ot /GR- /EHsc>
    )
    if(LATTICE_WARNINGS_AS_ERRORS)
        target_compile_options(lattice_baseline_flags INTERFACE /WX)
    endif()
else()
    target_compile_options(lattice_baseline_flags INTERFACE
        -Wall -Wextra -Wpedantic
        -fno-exceptions -fno-rtti
        $<$<CONFIG:Release>:-O3 -fomit-frame-pointer>
    )
    # Portable baseline ISA (avoid -march=native: produced .so wouldn't run on
    # users' CPUs lacking AVX/AVX-512). Specialised TUs opt in via -m<flag>.
    if(LATTICE_ARCH_X86)
        target_compile_options(lattice_baseline_flags INTERFACE -msse2)
    endif()
    if(LATTICE_WARNINGS_AS_ERRORS)
        target_compile_options(lattice_baseline_flags INTERFACE -Werror)
    endif()
    # IMPORTANT: do NOT enable -ffast-math — light-engine float math must be
    # bit-exact w.r.t. the JVM's strict IEEE-754 evaluation.
endif()

# ---------------------------------------------------------------------------
# Main shared library (sources added by modules below)
# ---------------------------------------------------------------------------
add_library(lattice SHARED)
target_link_libraries(lattice PRIVATE lattice_baseline_flags)
target_include_directories(lattice
    PUBLIC  ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src
            ${CMAKE_CURRENT_SOURCE_DIR}/jni
            ${JNI_INCLUDE_DIRS}
)
set_target_properties(lattice PROPERTIES
    OUTPUT_NAME "lattice"
    VERSION ${PROJECT_VERSION}
    SOVERSION ${PROJECT_VERSION_MAJOR}
)

if(LATTICE_ENABLE_LTO)
    include(CheckIPOSupported)
    check_ipo_supported(RESULT _ipo_ok OUTPUT _ipo_err)
    if(_ipo_ok)
        set_property(TARGET lattice PROPERTY INTERPROCEDURAL_OPTIMIZATION_RELEASE ON)
    else()
        message(STATUS "LTO not supported: ${_ipo_err}")
    endif()
endif()

# ---------------------------------------------------------------------------
# SIMD helper — call before any module that wants a SIMD-specialised TU.
#
# Helper: declare a SIMD specialisation TU that's always built but only opts
# into specific ISA extensions via compile options. The resulting object is
# linked into the main library; it's the runtime's job (via dispatch.hpp) to
# avoid entering symbols the host CPU can't execute.
#
#   lattice_add_simd_object(NAME  SOURCES src/... FLAGS -mavx2 -mbmi2)
# ---------------------------------------------------------------------------
function(lattice_add_simd_object name)
    cmake_parse_arguments(ARG "" "" "SOURCES;FLAGS" ${ARGN})
    if(NOT ARG_SOURCES)
        message(FATAL_ERROR "lattice_add_simd_object(${name}): SOURCES required")
    endif()
    add_library(${name} OBJECT ${ARG_SOURCES})
    target_link_libraries(${name} PRIVATE lattice_baseline_flags)
    target_include_directories(${name} PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/src
        ${CMAKE_CURRENT_SOURCE_DIR}/jni
        ${JNI_INCLUDE_DIRS}
    )
    if(ARG_FLAGS)
        # MSVC uses different flag syntax (/arch:AVX2 etc.) and accepts
        # them via target_compile_options identically — caller is
        # responsible for passing platform-appropriate flags.
        target_compile_options(${name} PRIVATE ${ARG_FLAGS})
    endif()
    target_sources(lattice PRIVATE $<TARGET_OBJECTS:${name}>)
endfunction()

# ---------------------------------------------------------------------------
# Core sources (always built)
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/core/cpu/detect.cpp
    jni/loader.cpp
)

# ---------------------------------------------------------------------------
# Module: NativeChunkSerializer (Phase 1 libdeflate zlib + Phase 2 NBT index)
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/io/compression/zlib_codec.cpp
    src/io/nbt/nbt_parser.cpp
    jni/chunk_serializer.cpp
)
target_link_libraries(lattice PRIVATE libdeflate::libdeflate_static)

# ---------------------------------------------------------------------------
# Module: NativePaletteOps (PackedIntegerArray bit-pack ops)
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/world/palette/packed_storage.cpp
    jni/palette_ops.cpp
)

# BMI2 + AVX2 specialisation of bulk_get / bulk_set. Compiled into its
# own OBJECT library with the AVX2-class ISA enabled so the compiler can
# issue AVX2 / BMI2 instructions for the per-element peel/pack loops.
# The runtime dispatcher only enters this TU when
# `lattice::cpu::features().bmi2 && bmi2_fast`.
#
# Skip on non-x86 platforms; the SIMD intrinsic headers don't exist
# there and the dispatcher would never select these symbols anyway.
if(LATTICE_ARCH_X86 AND LATTICE_ENABLE_SIMD)
    if(MSVC)
        lattice_add_simd_object(lattice_palette_bmi2
            SOURCES src/world/palette/packed_storage_bmi2.cpp
            FLAGS   /arch:AVX2)
    else()
        lattice_add_simd_object(lattice_palette_bmi2
            SOURCES src/world/palette/packed_storage_bmi2.cpp
            FLAGS   -mbmi2 -mavx2)
    endif()
endif()

# NEON specialisation for AArch64. The AArch64 baseline ISA already
# includes NEON so no extra compile flag is strictly needed, but we
# pass -O3 explicitly so the per-element-bits template instantiations
# get fully unrolled even in non-Release builds.
if(LATTICE_ARCH_ARM64 AND LATTICE_ENABLE_SIMD)
    if(MSVC)
        # Windows on ARM64: baseline already implies NEON; no flag needed.
        lattice_add_simd_object(lattice_palette_neon
            SOURCES src/world/palette/packed_storage_neon.cpp)
    else()
        lattice_add_simd_object(lattice_palette_neon
            SOURCES src/world/palette/packed_storage_neon.cpp
            FLAGS   -O3)
    endif()
endif()

# ---------------------------------------------------------------------------
# Module: NativeLightEngine (LevelPropagator + ChunkLightProvider)
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/world/light/level_propagator.cpp
    src/world/light/block_light_engine.cpp
    jni/light_engine.cpp
)

# ---------------------------------------------------------------------------
# Module: NativeHeightmap (multi-section column scanner)
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/world/heightmap/heightmap_scan.cpp
    jni/heightmap.cpp
)

if(LATTICE_ARCH_X86 AND LATTICE_ENABLE_SIMD)
    if(MSVC)
        lattice_add_simd_object(lattice_heightmap_avx2
            SOURCES src/world/heightmap/heightmap_scan_avx2.cpp
            FLAGS   /arch:AVX2)
    else()
        lattice_add_simd_object(lattice_heightmap_avx2
            SOURCES src/world/heightmap/heightmap_scan_avx2.cpp
            FLAGS   -mavx2)
    endif()
endif()

if(LATTICE_ARCH_ARM64 AND LATTICE_ENABLE_SIMD)
    if(MSVC)
        lattice_add_simd_object(lattice_heightmap_neon
            SOURCES src/world/heightmap/heightmap_scan_neon.cpp)
    else()
        lattice_add_simd_object(lattice_heightmap_neon
            SOURCES src/world/heightmap/heightmap_scan_neon.cpp
            FLAGS   -O3)
    endif()
endif()

# ---------------------------------------------------------------------------
# Module: NativeRandomTickFilter (random-tick candidate mask filter)
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/world/tick/random_tick_filter.cpp
    jni/tick.cpp
)

# ---------------------------------------------------------------------------
# Module: NativePerlinNoise (world-generation single-octave noise sampler)
#   First module in the world-generation accelerator series (Worldgen-1).
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/world/gen/noise/perlin_noise.cpp
    jni/perlin_noise.cpp
)

if(LATTICE_ARCH_X86 AND LATTICE_ENABLE_SIMD)
    target_compile_definitions(lattice PRIVATE LATTICE_HAS_PERLIN_AVX2=1)
    if(MSVC)
        lattice_add_simd_object(lattice_perlin_avx2
            SOURCES src/world/gen/noise/perlin_noise_avx2.cpp
            FLAGS   /arch:AVX2)
    else()
        lattice_add_simd_object(lattice_perlin_avx2
            SOURCES src/world/gen/noise/perlin_noise_avx2.cpp
            FLAGS   -mavx2)
    endif()
endif()

# ---------------------------------------------------------------------------
# Module: NativeOctavePerlinNoise (Worldgen-2): N-octave weighted sum.
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/world/gen/noise/octave_perlin_noise.cpp
    jni/octave_perlin_noise.cpp
)

# ---------------------------------------------------------------------------
# Module: NativeDoublePerlinNoise (Worldgen-3): Mojang's two-OctavePerlin
# sampler stack with DOMAIN_SCALE shift on the second sampler.
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/world/gen/noise/double_perlin_noise.cpp
    jni/double_perlin_noise.cpp
)

# ---------------------------------------------------------------------------
# Module: DensityFunction evaluator (Worldgen-4) + JNI bindings (Worldgen-4b).
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/world/gen/densityfunction/density_function.cpp
    src/world/gen/densityfunction/beardifier.cpp
    jni/density_function.cpp
    jni/beardifier.cpp
)

if(LATTICE_ARCH_X86 AND LATTICE_ENABLE_SIMD)
    target_compile_definitions(lattice PRIVATE LATTICE_HAS_DENSITY_AVX2=1)
    if(MSVC)
        lattice_add_simd_object(lattice_density_function_avx2
            SOURCES src/world/gen/densityfunction/density_function_avx2.cpp
            FLAGS   /arch:AVX2)
    else()
        lattice_add_simd_object(lattice_density_function_avx2
            SOURCES src/world/gen/densityfunction/density_function_avx2.cpp
            FLAGS   -mavx2)
    endif()
endif()

# ---------------------------------------------------------------------------
# Module: MaterialRules evaluator (Worldgen-5): SurfaceBuilder's rule tree.
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/world/gen/surfacebuilder/material_rules.cpp
    jni/material_rules.cpp
)

# ---------------------------------------------------------------------------
# Module: SimplexNoise (Worldgen-4e): Ken Perlin simplex noise.
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/world/gen/noise/simplex_noise.cpp
    jni/simplex_noise.cpp
)

if(LATTICE_ARCH_X86 AND LATTICE_ENABLE_SIMD)
    target_compile_definitions(lattice PRIVATE LATTICE_HAS_SIMPLEX_AVX2=1)
    if(MSVC)
        lattice_add_simd_object(lattice_simplex_avx2
            SOURCES src/world/gen/noise/simplex_noise_avx2.cpp
            FLAGS   /arch:AVX2)
    else()
        lattice_add_simd_object(lattice_simplex_avx2
            SOURCES src/world/gen/noise/simplex_noise_avx2.cpp
            FLAGS   -mavx2)
    endif()
endif()

# ---------------------------------------------------------------------------
# Module: InterpolatedNoiseSampler (Worldgen-12): legacy 1.16-style
# "blended noise" used by `old_blended_noise` density-function nodes.
# Composes three OctavePerlinNoiseSampler instances; stateless w.r.t.
# the underlying samplers (the JVM caller keeps them alive).
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/world/gen/noise/interpolated_noise.cpp
    jni/interpolated_noise.cpp
)

# ---------------------------------------------------------------------------
# Module: ChunkNoiseSampler (Worldgen-6): NoiseRouter bundle + per-channel
# DensityFunction cache state. Pure facade over density_function.cpp's
# evaluator; no new evaluator paths.
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/world/gen/chunknoise/chunk_noise_sampler.cpp
    jni/chunk_noise_sampler.cpp
)

# ---------------------------------------------------------------------------
# Module: OreVeinSampler (Worldgen-8): bit-exact port of
# OreVeinSampler.java's per-block decision logic. Stateless;
# Java passes pre-sampled DF values + Splitter seeds.
# Pulls in the Xoroshiro128++ port (header-only) at translation-unit
# scope.
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/world/gen/orevein/ore_vein.cpp
    jni/ore_vein.cpp
)

# ---------------------------------------------------------------------------
# Module: entity batch primitives
#   - NativeEntityVisibility (O(N×M) distance scan)
#   - NativeAabbQuery        (O(Q×E) AABB intersect scan)
#   - NativeCollisionSweep   (swept-AABB clamp for entity movement)
# All three share the JNI TU (jni/entity_visibility.cpp) and dispatcher
# infrastructure.
# ---------------------------------------------------------------------------
target_sources(lattice PRIVATE
    src/world/entity/approach_target_sampler.cpp
    src/world/entity/visibility_scan.cpp
    src/world/entity/aabb_query.cpp
    src/world/entity/entity_query.cpp
    src/world/entity/collision_sweep.cpp
    src/world/entity/los.cpp
    src/world/entity/pathfinder.cpp
    src/world/entity/biological_ai.cpp
    src/world/entity/flee_target_sampler.cpp
    src/world/entity/home_target_sampler.cpp
    src/world/entity/water_target_sampler.cpp
    src/world/entity/spawn_filter.cpp
    jni/biological_ai.cpp
    jni/entity_query.cpp
    jni/entity_visibility.cpp
    jni/los.cpp
    jni/pathfinder.cpp
)

# AVX2 specialisations (x86). Each TU compiled with -mavx2 / /arch:AVX2.
# Conservative: no FMA, lanes are __m256d (4 doubles).
if(LATTICE_ARCH_X86 AND LATTICE_ENABLE_SIMD)
    if(MSVC)
        lattice_add_simd_object(lattice_entity_vis_avx2
            SOURCES src/world/entity/visibility_scan_avx2.cpp
            FLAGS   /arch:AVX2)
        lattice_add_simd_object(lattice_entity_aabb_avx2
            SOURCES src/world/entity/aabb_query_avx2.cpp
            FLAGS   /arch:AVX2)
        lattice_add_simd_object(lattice_entity_collision_avx2
            SOURCES src/world/entity/collision_sweep_avx2.cpp
            FLAGS   /arch:AVX2)
        lattice_add_simd_object(lattice_entity_pathfinder_avx2
            SOURCES src/world/entity/pathfinder_avx2.cpp
            FLAGS   /arch:AVX2)
    else()
        lattice_add_simd_object(lattice_entity_vis_avx2
            SOURCES src/world/entity/visibility_scan_avx2.cpp
            FLAGS   -mavx2)
        lattice_add_simd_object(lattice_entity_aabb_avx2
            SOURCES src/world/entity/aabb_query_avx2.cpp
            FLAGS   -mavx2)
        lattice_add_simd_object(lattice_entity_collision_avx2
            SOURCES src/world/entity/collision_sweep_avx2.cpp
            FLAGS   -mavx2)
        lattice_add_simd_object(lattice_entity_pathfinder_avx2
            SOURCES src/world/entity/pathfinder_avx2.cpp
            FLAGS   -mavx2)
    endif()
endif()

# NEON specialisations (AArch64). float64x2_t, 2 elements per iteration.
if(LATTICE_ARCH_ARM64 AND LATTICE_ENABLE_SIMD)
    if(MSVC)
        lattice_add_simd_object(lattice_entity_vis_neon
            SOURCES src/world/entity/visibility_scan_neon.cpp)
        lattice_add_simd_object(lattice_entity_aabb_neon
            SOURCES src/world/entity/aabb_query_neon.cpp)
        lattice_add_simd_object(lattice_entity_collision_neon
            SOURCES src/world/entity/collision_sweep_neon.cpp)
        lattice_add_simd_object(lattice_entity_pathfinder_neon
            SOURCES src/world/entity/pathfinder_neon.cpp)
    else()
        lattice_add_simd_object(lattice_entity_vis_neon
            SOURCES src/world/entity/visibility_scan_neon.cpp
            FLAGS   -O3)
        lattice_add_simd_object(lattice_entity_aabb_neon
            SOURCES src/world/entity/aabb_query_neon.cpp
            FLAGS   -O3)
        lattice_add_simd_object(lattice_entity_collision_neon
            SOURCES src/world/entity/collision_sweep_neon.cpp
            FLAGS   -O3)
        lattice_add_simd_object(lattice_entity_pathfinder_neon
            SOURCES src/world/entity/pathfinder_neon.cpp
            FLAGS   -O3)
    endif()
endif()

# All T1 modules (ChunkSerializer, PaletteOps, LightEngine) are now wired
# above. New modules should follow the same target_sources(lattice PRIVATE ...)
# shape, with their JNI wrapper under jni/ and any SIMD specialisations
# registered via lattice_add_simd_object(...).

# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
if(LATTICE_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()

# ---------------------------------------------------------------------------
# Install
# ---------------------------------------------------------------------------
install(TARGETS lattice
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
    ARCHIVE DESTINATION lib
)

# ---------------------------------------------------------------------------
# Diagnostic summary
# ---------------------------------------------------------------------------
message(STATUS "==================== Lattice Native ====================")
message(STATUS "  Version       : ${PROJECT_VERSION}")
message(STATUS "  C++ standard  : C++${CMAKE_CXX_STANDARD}")
message(STATUS "  Build type    : ${CMAKE_BUILD_TYPE}")
message(STATUS "  SIMD enabled  : ${LATTICE_ENABLE_SIMD}")
message(STATUS "  LTO requested : ${LATTICE_ENABLE_LTO}")
message(STATUS "  Tests         : ${LATTICE_BUILD_TESTS}")
message(STATUS "  Warn-as-error : ${LATTICE_WARNINGS_AS_ERRORS}")
message(STATUS "========================================================")
