/**
 * @file noise_handle.hpp
 * @brief Shared accessor for "DoublePerlinNoise / OctavePerlinNoise
 *        handle → sampler*".
 *
 * The NativeDoublePerlinNoise and NativeOctavePerlinNoise JNI
 * modules each store their sampler inside a Bundle struct (TU-local).
 * Other JNI modules that take a noise handle (e.g. density_function,
 * interpolated_noise) need to look up the sampler from the same
 * handle without depending on Bundle's layout.
 *
 * The first field of each Bundle is the corresponding sampler, so
 * the handle pointer doubles as a sampler pointer when read at
 * offset 0. That property is guaranteed by the standard for
 * standard-layout structs.
 */

#pragma once

#include "world/gen/noise/double_perlin_noise.hpp"
#include "world/gen/noise/octave_perlin_noise.hpp"

namespace lattice::jni::noise {

inline const lattice::world::gen::noise::DoublePerlinNoiseSampler*
sampler_from_handle(long long handle) noexcept {
    if (handle == 0) return nullptr;
    // DoublePerlin Bundle::sampler is at offset 0; safe to reinterpret.
    return reinterpret_cast<const lattice::world::gen::noise::DoublePerlinNoiseSampler*>(handle);
}

inline const lattice::world::gen::noise::OctavePerlinNoiseSampler*
octave_sampler_from_handle(long long handle) noexcept {
    if (handle == 0) return nullptr;
    // NativeOctavePerlinNoise's Bundle::sampler is at offset 0.
    return reinterpret_cast<const lattice::world::gen::noise::OctavePerlinNoiseSampler*>(handle);
}

} // namespace lattice::jni::noise
