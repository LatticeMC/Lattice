# Unit tests for lattice-native. Each test file is a self-contained
# doctest binary linked against the lattice library's object files
# (so the tests can call into the same code that gets shipped in the
# final .so / .dll).
#
# Adding a test:
#   1. Create tests/test_<name>.cpp using the doctest TEST_CASE macros.
#   2. Add it to lattice_tests below; the helper takes care of the
#      target_sources / target_link_libraries / add_test plumbing.

include(CTest)

# Helper: declare a single-file doctest binary.
#
#   lattice_add_test(NAME      <name>
#                    SOURCES   tests/test_xxx.cpp ...
#                    DEPS_SRC  src/xxx.cpp ...)
#
# The DEPS_SRC list is the set of lattice-native source files this
# test wants to exercise directly. We compile them into the test
# binary rather than linking against the full `lattice` target so the
# tests can run without JNI / loader plumbing.
function(lattice_add_test name)
    cmake_parse_arguments(ARG "" "" "SOURCES;DEPS_SRC" ${ARGN})
    if(NOT ARG_SOURCES)
        message(FATAL_ERROR "lattice_add_test(${name}): SOURCES required")
    endif()
    set(target_name "lattice_test_${name}")
    add_executable(${target_name} ${ARG_SOURCES} ${ARG_DEPS_SRC})
    target_link_libraries(${target_name} PRIVATE doctest::doctest Threads::Threads)
    target_include_directories(${target_name} PRIVATE
        ${CMAKE_SOURCE_DIR}/include
        ${CMAKE_SOURCE_DIR}/src
        ${CMAKE_SOURCE_DIR}/jni
    )
    # Test binaries do NOT inherit `lattice_baseline_flags` (which sets
    # -fno-exceptions / -fno-rtti) because doctest internally relies on
    # exceptions for its assertion-failure path. The compiled library
    # code under test was already validated to work without exceptions
    # in the main build; tests just give it slightly more permissive
    # build flags.
    if(NOT MSVC)
        target_compile_options(${target_name} PRIVATE
            -Wall -Wextra -Wpedantic
            $<$<CONFIG:Release>:-O2>)
    else()
        target_compile_options(${target_name} PRIVATE
            /W4 /permissive- /utf-8 /EHsc
            $<$<CONFIG:Release>:/O2>)
    endif()
    # libdeflate is needed by tests that touch the zlib codec.
    target_link_libraries(${target_name} PRIVATE libdeflate::libdeflate_static)
    add_test(NAME ${name} COMMAND ${target_name})
endfunction()

# ---- Tests -----------------------------------------------------------------

lattice_add_test(adaptive_row_executor
    SOURCES  test_adaptive_row_executor.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/core/concurrency/adaptive_row_executor.cpp)

# SIMD specialisation sources, compiled into test binaries only when the
# target arch supports them. This keeps the dispatcher's undefined
# references resolved at link time. Each test that uses a dispatcher
# must include the matching SIMD source.
set(_palette_simd_srcs "")
set(_entity_simd_srcs "")
set(_heightmap_simd_srcs "")
set(_pathfinder_simd_srcs "")
set(_perlin_simd_srcs "")
set(_double_perlin_simd_srcs "")
set(_simplex_simd_srcs "")
set(_density_simd_srcs "")
if(LATTICE_ARCH_X86)
    list(APPEND _palette_simd_srcs ${CMAKE_SOURCE_DIR}/src/world/palette/packed_storage_bmi2.cpp)
    list(APPEND _heightmap_simd_srcs ${CMAKE_SOURCE_DIR}/src/world/heightmap/heightmap_scan_avx2.cpp)
    list(APPEND _entity_simd_srcs
        ${CMAKE_SOURCE_DIR}/src/world/entity/visibility_scan_avx2.cpp
        ${CMAKE_SOURCE_DIR}/src/world/entity/aabb_query_avx2.cpp
        ${CMAKE_SOURCE_DIR}/src/world/entity/collision_sweep_avx2.cpp)
    list(APPEND _pathfinder_simd_srcs ${CMAKE_SOURCE_DIR}/src/world/entity/pathfinder_avx2.cpp)
    list(APPEND _perlin_simd_srcs ${CMAKE_SOURCE_DIR}/src/world/gen/noise/perlin_noise_avx2.cpp)
    list(APPEND _double_perlin_simd_srcs ${CMAKE_SOURCE_DIR}/src/world/gen/noise/double_perlin_noise_avx2.cpp)
    list(APPEND _simplex_simd_srcs ${CMAKE_SOURCE_DIR}/src/world/gen/noise/simplex_noise_avx2.cpp)
    list(APPEND _density_simd_srcs ${CMAKE_SOURCE_DIR}/src/world/gen/densityfunction/density_function_avx2.cpp)
elseif(LATTICE_ARCH_ARM64)
    list(APPEND _palette_simd_srcs ${CMAKE_SOURCE_DIR}/src/world/palette/packed_storage_neon.cpp)
    list(APPEND _heightmap_simd_srcs ${CMAKE_SOURCE_DIR}/src/world/heightmap/heightmap_scan_neon.cpp)
    list(APPEND _entity_simd_srcs
        ${CMAKE_SOURCE_DIR}/src/world/entity/visibility_scan_neon.cpp
        ${CMAKE_SOURCE_DIR}/src/world/entity/aabb_query_neon.cpp
        ${CMAKE_SOURCE_DIR}/src/world/entity/collision_sweep_neon.cpp)
    list(APPEND _pathfinder_simd_srcs ${CMAKE_SOURCE_DIR}/src/world/entity/pathfinder_neon.cpp)
endif()

# We compile the SIMD TUs into the test binary at the same per-source
# flag level that the main library uses. CMake's source-file properties
# carry the flag.
foreach(s IN LISTS _palette_simd_srcs)
    if(MSVC)
        set_source_files_properties(${s} PROPERTIES COMPILE_OPTIONS "/arch:AVX2")
    elseif(LATTICE_ARCH_X86)
        set_source_files_properties(${s} PROPERTIES COMPILE_OPTIONS "-mbmi2;-mavx2")
    endif()
endforeach()

foreach(s IN LISTS _heightmap_simd_srcs _entity_simd_srcs _pathfinder_simd_srcs _perlin_simd_srcs _double_perlin_simd_srcs _simplex_simd_srcs _density_simd_srcs)
    if(MSVC)
        set_source_files_properties(${s} PROPERTIES COMPILE_OPTIONS "/arch:AVX2")
    elseif(LATTICE_ARCH_X86)
        set_source_files_properties(${s} PROPERTIES COMPILE_OPTIONS "-mavx2")
    endif()
endforeach()

lattice_add_test(palette
    SOURCES  test_palette.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/palette/packed_storage.cpp
             ${_palette_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/core/cpu/detect.cpp)

lattice_add_test(zlib_codec
    SOURCES  test_zlib_codec.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/io/compression/zlib_codec.cpp)

lattice_add_test(nbt_parser
    SOURCES  test_nbt_parser.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/io/nbt/nbt_parser.cpp)

lattice_add_test(heightmap
    SOURCES  test_heightmap.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/heightmap/heightmap_scan.cpp
             ${_heightmap_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/core/cpu/detect.cpp)

lattice_add_test(entity_visibility
    SOURCES  test_entity_visibility.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/entity/visibility_scan.cpp
             ${_entity_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/core/cpu/detect.cpp)

lattice_add_test(los
    SOURCES  test_los.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/entity/los.cpp)

lattice_add_test(aabb_query
    SOURCES  test_aabb_query.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/entity/aabb_query.cpp
             ${_entity_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/core/cpu/detect.cpp)

lattice_add_test(entity_query
    SOURCES  test_entity_query.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/entity/entity_query.cpp)

lattice_add_test(collision_sweep
    SOURCES  test_collision_sweep.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/entity/collision_sweep.cpp
             ${_entity_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/core/cpu/detect.cpp)

lattice_add_test(pathfinder
    SOURCES  test_pathfinder.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/entity/pathfinder.cpp
             ${_pathfinder_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/core/cpu/detect.cpp)

lattice_add_test(spawn_filter
    SOURCES  test_spawn_filter.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/entity/spawn_filter.cpp
             ${CMAKE_SOURCE_DIR}/src/world/palette/packed_storage.cpp
             ${_palette_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/core/cpu/detect.cpp)

lattice_add_test(biological_ai
    SOURCES  test_biological_ai.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/entity/biological_ai.cpp)

lattice_add_test(approach_target_sampler
    SOURCES  test_approach_target_sampler.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/entity/approach_target_sampler.cpp)

lattice_add_test(water_target_sampler
    SOURCES  test_water_target_sampler.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/entity/water_target_sampler.cpp)

lattice_add_test(home_target_sampler
    SOURCES  test_home_target_sampler.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/entity/home_target_sampler.cpp)

lattice_add_test(flee_target_sampler
    SOURCES  test_flee_target_sampler.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/entity/flee_target_sampler.cpp)

lattice_add_test(random_tick_filter
    SOURCES  test_random_tick_filter.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/tick/random_tick_filter.cpp)

lattice_add_test(level_propagator
    SOURCES  test_level_propagator.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/light/level_propagator.cpp)

lattice_add_test(block_light_engine
    SOURCES  test_block_light_engine.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/light/block_light_engine.cpp)

lattice_add_test(perlin_noise
    SOURCES  test_perlin_noise.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/gen/noise/perlin_noise.cpp
             ${_perlin_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/core/cpu/detect.cpp)
if(LATTICE_ARCH_X86)
    target_compile_definitions(lattice_test_perlin_noise PRIVATE LATTICE_TEST_HAS_PERLIN_AVX2=1)
endif()

lattice_add_test(octave_perlin_noise
    SOURCES  test_octave_perlin_noise.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/gen/noise/perlin_noise.cpp
             ${CMAKE_SOURCE_DIR}/src/world/gen/noise/octave_perlin_noise.cpp
             ${_perlin_simd_srcs})

lattice_add_test(double_perlin_noise
    SOURCES  test_double_perlin_noise.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/gen/noise/perlin_noise.cpp
             ${_perlin_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/world/gen/noise/octave_perlin_noise.cpp
             ${CMAKE_SOURCE_DIR}/src/world/gen/noise/double_perlin_noise.cpp
             ${_double_perlin_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/core/cpu/detect.cpp)
if(LATTICE_ARCH_X86)
    target_compile_definitions(lattice_test_double_perlin_noise PRIVATE LATTICE_TEST_HAS_DOUBLE_PERLIN_AVX2=1)
endif()

lattice_add_test(density_function
    SOURCES  test_density_function.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/gen/noise/perlin_noise.cpp
             ${_perlin_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/world/gen/noise/octave_perlin_noise.cpp
             ${CMAKE_SOURCE_DIR}/src/world/gen/noise/double_perlin_noise.cpp
             ${_double_perlin_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/world/gen/noise/simplex_noise.cpp
             ${_simplex_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/world/gen/noise/interpolated_noise.cpp
             ${CMAKE_SOURCE_DIR}/src/world/gen/densityfunction/density_function.cpp
             ${_density_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/world/gen/densityfunction/beardifier.cpp
             ${CMAKE_SOURCE_DIR}/src/core/cpu/detect.cpp)
if(LATTICE_ARCH_X86)
    target_compile_definitions(lattice_test_density_function PRIVATE LATTICE_HAS_DENSITY_AVX2=1 LATTICE_TEST_HAS_DENSITY_AVX2=1)
endif()

lattice_add_test(beardifier
    SOURCES  test_beardifier.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/gen/densityfunction/beardifier.cpp)

lattice_add_test(material_rules
    SOURCES  test_material_rules.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/gen/surfacebuilder/material_rules.cpp)

lattice_add_test(simplex_noise
    SOURCES  test_simplex_noise.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/gen/noise/simplex_noise.cpp
             ${_simplex_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/core/cpu/detect.cpp)
if(LATTICE_ARCH_X86)
    target_compile_definitions(lattice_test_simplex_noise PRIVATE LATTICE_TEST_HAS_SIMPLEX_AVX2=1)
endif()

lattice_add_test(interpolated_noise
    SOURCES  test_interpolated_noise.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/gen/noise/perlin_noise.cpp
             ${_perlin_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/world/gen/noise/octave_perlin_noise.cpp
             ${CMAKE_SOURCE_DIR}/src/world/gen/noise/interpolated_noise.cpp)

lattice_add_test(chunk_noise_sampler
    SOURCES  test_chunk_noise_sampler.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/gen/noise/perlin_noise.cpp
             ${_perlin_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/world/gen/noise/octave_perlin_noise.cpp
             ${CMAKE_SOURCE_DIR}/src/world/gen/noise/double_perlin_noise.cpp
             ${_double_perlin_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/world/gen/noise/simplex_noise.cpp
             ${_simplex_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/world/gen/noise/interpolated_noise.cpp
             ${CMAKE_SOURCE_DIR}/src/world/gen/densityfunction/density_function.cpp
             ${_density_simd_srcs}
             ${CMAKE_SOURCE_DIR}/src/world/gen/densityfunction/beardifier.cpp
             ${CMAKE_SOURCE_DIR}/src/world/gen/chunknoise/chunk_noise_sampler.cpp
             ${CMAKE_SOURCE_DIR}/src/core/cpu/detect.cpp)
if(LATTICE_ARCH_X86)
    target_compile_definitions(lattice_test_chunk_noise_sampler PRIVATE LATTICE_HAS_DENSITY_AVX2=1)
endif()

# Worldgen-8: header-only Xoroshiro128++ port + OreVeinSampler.
lattice_add_test(xoroshiro128pp
    SOURCES  test_xoroshiro128pp.cpp)

lattice_add_test(ore_vein
    SOURCES  test_ore_vein.cpp
    DEPS_SRC ${CMAKE_SOURCE_DIR}/src/world/gen/orevein/ore_vein.cpp)
