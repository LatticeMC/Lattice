// JNI bindings for NativeOctavePerlinNoise.
//
// Java class: com.latticemc.lattice.nativelib.NativeOctavePerlinNoise
//
// An octave sampler owns:
//   - N PerlinNoiseSampler instances (flattened into one heap allocation)
//   - N amplitudes
//   - lacunarity + persistence
//
// Construction takes:
//   double[3*N] origins    : (originX, originY, originZ) per octave
//   byte[256*N] perms      : 256 bytes per octave, concatenated
//   double[N]   amplitudes
//   double lacunarity, persistence
//
// All octaves are allocated contiguously to keep cache footprint
// small and to make the inner loop's strided access predictable.

#include <jni.h>

#include <cstddef>
#include <cstring>
#include <new>

#include "jni_helper.hpp"
#include "world/gen/noise/octave_perlin_noise.hpp"

namespace png = lattice::world::gen::noise;

namespace {

struct Bundle {
    // `sampler` MUST stay the first member: jni/noise_handle.hpp's
    // `octave_sampler_from_handle` reinterpret_casts a NativeOctave-
    // PerlinNoise handle directly to OctavePerlinNoiseSampler*, which
    // only works if the sampler sits at offset 0.
    png::OctavePerlinNoiseSampler   sampler;
    png::PerlinNoiseSampler*        octaves    = nullptr;
    double*                         amplitudes = nullptr;
};

static_assert(offsetof(Bundle, sampler) == 0,
              "NativeOctavePerlinNoise Bundle must keep its sampler at "
              "offset 0 — see jni/noise_handle.hpp");

} // namespace

extern "C" {

/*
 * nativeCreate(double[] origins, byte[] permutations, double[] amplitudes,
 *              double lacunarity, double persistence)
 *   → long handle (0 on error)
 */
JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativeOctavePerlinNoise_nativeCreate(
        JNIEnv* env, jclass /*cls*/,
        jdoubleArray jOrigins,
        jbyteArray   jPermutations,
        jdoubleArray jAmplitudes,
        jdouble      lacunarity,
        jdouble      persistence) {
    if (!jOrigins || !jPermutations || !jAmplitudes) {
        lattice::jni::throw_illegal_arg(env, "lattice octave: null array");
        return 0;
    }
    const jsize amp_len = env->GetArrayLength(jAmplitudes);
    if (amp_len <= 0) {
        lattice::jni::throw_illegal_arg(env, "lattice octave: empty amplitudes");
        return 0;
    }
    const std::size_t N = static_cast<std::size_t>(amp_len);
    if (env->GetArrayLength(jOrigins) != static_cast<jsize>(N * 3)) {
        lattice::jni::throw_illegal_arg(env, "lattice octave: origins must be 3*N doubles");
        return 0;
    }
    if (env->GetArrayLength(jPermutations) != static_cast<jsize>(N * 256)) {
        lattice::jni::throw_illegal_arg(env, "lattice octave: permutations must be 256*N bytes");
        return 0;
    }

    Bundle* b = new (std::nothrow) Bundle{};
    if (!b) {
        lattice::jni::throw_oom(env, "lattice octave: bundle alloc");
        return 0;
    }
    b->octaves    = new (std::nothrow) png::PerlinNoiseSampler[N];
    b->amplitudes = new (std::nothrow) double[N];
    if (!b->octaves || !b->amplitudes) {
        delete[] b->octaves;
        delete[] b->amplitudes;
        delete b;
        lattice::jni::throw_oom(env, "lattice octave: octaves/amps alloc");
        return 0;
    }

    jdouble* origins = env->GetDoubleArrayElements(jOrigins, nullptr);
    jdouble* amps    = env->GetDoubleArrayElements(jAmplitudes, nullptr);
    jbyte*   perms   = env->GetByteArrayElements(jPermutations, nullptr);
    if (!origins || !amps || !perms) {
        if (perms)   env->ReleaseByteArrayElements(jPermutations, perms, JNI_ABORT);
        if (amps)    env->ReleaseDoubleArrayElements(jAmplitudes, amps, JNI_ABORT);
        if (origins) env->ReleaseDoubleArrayElements(jOrigins, origins, JNI_ABORT);
        delete[] b->octaves;
        delete[] b->amplitudes;
        delete b;
        lattice::jni::throw_oom(env, "lattice octave: pin arrays");
        return 0;
    }

    for (std::size_t i = 0; i < N; ++i) {
        b->octaves[i].origin_x = origins[i * 3 + 0];
        b->octaves[i].origin_y = origins[i * 3 + 1];
        b->octaves[i].origin_z = origins[i * 3 + 2];
        std::memcpy(b->octaves[i].permutation, perms + i * 256, 256);
        b->amplitudes[i] = amps[i];
    }
    env->ReleaseByteArrayElements(jPermutations, perms, JNI_ABORT);
    env->ReleaseDoubleArrayElements(jAmplitudes, amps, JNI_ABORT);
    env->ReleaseDoubleArrayElements(jOrigins, origins, JNI_ABORT);

    b->sampler.octaves      = b->octaves;
    b->sampler.amplitudes   = b->amplitudes;
    b->sampler.octave_count = N;
    b->sampler.lacunarity   = static_cast<double>(lacunarity);
    b->sampler.persistence  = static_cast<double>(persistence);
    return reinterpret_cast<jlong>(b);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeOctavePerlinNoise_nativeDestroy(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    auto* b = reinterpret_cast<Bundle*>(handle);
    if (!b) return;
    delete[] b->octaves;
    delete[] b->amplitudes;
    delete b;
}

JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativeOctavePerlinNoise_nativeSample(
        JNIEnv* /*env*/, jclass /*cls*/,
        jlong handle, jdouble x, jdouble y, jdouble z) {
    auto* b = reinterpret_cast<Bundle*>(handle);
    if (!b) return 0.0;
    return png::sample(b->sampler, x, y, z);
}

JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativeOctavePerlinNoise_nativeSampleFull(
        JNIEnv* /*env*/, jclass /*cls*/,
        jlong handle, jdouble x, jdouble y, jdouble z,
        jdouble yScale, jdouble yMax, jboolean useOrigin) {
    auto* b = reinterpret_cast<Bundle*>(handle);
    if (!b) return 0.0;
    return png::sample_full(b->sampler, x, y, z, yScale, yMax,
                            useOrigin == JNI_TRUE);
}

} // extern "C"
