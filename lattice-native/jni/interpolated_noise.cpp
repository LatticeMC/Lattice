// JNI bindings for NativeInterpolatedNoise.
//
// Java class: com.latticemc.lattice.nativelib.NativeInterpolatedNoise
//
// An interpolated-noise sampler is the legacy 1.16-style "blended
// noise" used by Mojang's `old_blended_noise` density-function node
// type. It composes three NativeOctavePerlinNoise samplers
// (lower / upper / interpolation) with five tuning doubles.
//
// Construction takes the three octave-noise handles already owned
// by the JVM caller. The sampler does NOT take ownership; the
// caller must keep all three NativeOctavePerlinNoise alive for as
// long as this sampler is used.

#include <jni.h>

#include <cstddef>
#include <new>

#include "jni_helper.hpp"
#include "noise_handle.hpp"
#include "world/gen/noise/interpolated_noise.hpp"

namespace pns = lattice::world::gen::noise;

namespace {

struct Bundle {
    // Sampler is the first member so a long handle can be reinterpret-
    // cast directly to InterpolatedNoiseSampler* by other JNI modules
    // that need it (e.g. density_function's kInterpolatedNoise builder
    // looks up the sampler by handle this way).
    pns::InterpolatedNoiseSampler sampler;
};

static_assert(offsetof(Bundle, sampler) == 0,
              "NativeInterpolatedNoise Bundle must keep its sampler at "
              "offset 0");

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativeInterpolatedNoise_nativeCreate(
        JNIEnv* env, jclass /*cls*/,
        jlong   lowerOctaveHandle,
        jlong   upperOctaveHandle,
        jlong   interpolationOctaveHandle,
        jdouble xzScale, jdouble yScale,
        jdouble xzFactor, jdouble yFactor,
        jdouble smearScaleMultiplier) {
    const auto* lower  = lattice::jni::noise::octave_sampler_from_handle(
        static_cast<long long>(lowerOctaveHandle));
    const auto* upper  = lattice::jni::noise::octave_sampler_from_handle(
        static_cast<long long>(upperOctaveHandle));
    const auto* interp = lattice::jni::noise::octave_sampler_from_handle(
        static_cast<long long>(interpolationOctaveHandle));
    if (!lower || !upper || !interp) {
        lattice::jni::throw_illegal_arg(env, "lattice interpolated noise: null octave handle");
        return 0;
    }
    auto* b = new (std::nothrow) Bundle{};
    if (!b) {
        lattice::jni::throw_oom(env, "lattice interpolated noise: bundle alloc");
        return 0;
    }
    b->sampler.lower_interpolated_noise = lower;
    b->sampler.upper_interpolated_noise = upper;
    b->sampler.interpolation_noise      = interp;
    b->sampler.xz_scale  = static_cast<double>(xzScale);
    b->sampler.y_scale   = static_cast<double>(yScale);
    b->sampler.xz_factor = static_cast<double>(xzFactor);
    b->sampler.y_factor  = static_cast<double>(yFactor);
    b->sampler.smear_scale_multiplier = static_cast<double>(smearScaleMultiplier);
    return reinterpret_cast<jlong>(b);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeInterpolatedNoise_nativeDestroy(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    delete reinterpret_cast<Bundle*>(handle);
}

JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativeInterpolatedNoise_nativeSample(
        JNIEnv* /*env*/, jclass /*cls*/,
        jlong handle, jdouble blockX, jdouble blockY, jdouble blockZ) {
    auto* b = reinterpret_cast<Bundle*>(handle);
    if (!b) return 0.0;
    return pns::sample(b->sampler,
                       static_cast<double>(blockX),
                       static_cast<double>(blockY),
                       static_cast<double>(blockZ));
}

} // extern "C"
