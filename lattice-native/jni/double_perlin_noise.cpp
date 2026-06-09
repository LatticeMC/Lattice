// JNI bindings for NativeDoublePerlinNoise.
//
// Java class: com.latticemc.lattice.nativelib.NativeDoublePerlinNoise
//
// A DoublePerlinNoise wraps two OctavePerlinNoise samplers + a fixed
// amplitude. Construction takes flattened arrays for both halves; the
// native side allocates a single bundle that owns all the per-octave
// state.

#include <jni.h>

#include <cstddef>
#include <cstdio>
#include <cstring>
#include <new>

#include "jni_helper.hpp"
#include "world/gen/noise/double_perlin_noise.hpp"

namespace png = lattice::world::gen::noise;

namespace {

struct Bundle {
    // `sampler` MUST stay the first member: jni/density_function.cpp
    // looks up the DoublePerlinNoiseSampler from a NativeDoublePerlinNoise
    // handle by reinterpret_cast'ing to DoublePerlinNoiseSampler*, which
    // only works if the sampler sits at offset 0. See jni/noise_handle.hpp.
    png::DoublePerlinNoiseSampler   sampler;
    // First-octave-sampler state:
    png::PerlinNoiseSampler*        first_octaves    = nullptr;
    double*                         first_amplitudes = nullptr;
    // Second-octave-sampler state:
    png::PerlinNoiseSampler*        second_octaves   = nullptr;
    double*                         second_amplitudes= nullptr;
};

static_assert(offsetof(Bundle, sampler) == 0,
              "DoublePerlinNoise Bundle must keep its sampler at offset 0 "
              "(see jni/noise_handle.hpp for the cross-TU contract)");

bool populate_half(JNIEnv* env,
                   jdoubleArray jOrigins, jbyteArray jPerms, jdoubleArray jAmps,
                   png::PerlinNoiseSampler*& out_octs,
                   double*&                  out_amps,
                   std::size_t&              out_count,
                   const char* tag) noexcept {
    const jsize amp_len = env->GetArrayLength(jAmps);
    if (amp_len <= 0) {
        lattice::jni::throw_illegal_arg(env, "lattice double-perlin: empty amplitudes");
        return false;
    }
    const std::size_t N = static_cast<std::size_t>(amp_len);
    if (env->GetArrayLength(jOrigins) != static_cast<jsize>(N * 3)) {
        char buf[128];
        std::snprintf(buf, sizeof buf,
                      "lattice double-perlin %s: origins must be 3*N doubles", tag);
        lattice::jni::throw_illegal_arg(env, buf);
        return false;
    }
    if (env->GetArrayLength(jPerms) != static_cast<jsize>(N * 256)) {
        char buf[128];
        std::snprintf(buf, sizeof buf,
                      "lattice double-perlin %s: permutations must be 256*N bytes", tag);
        lattice::jni::throw_illegal_arg(env, buf);
        return false;
    }
    out_octs = new (std::nothrow) png::PerlinNoiseSampler[N];
    out_amps = new (std::nothrow) double[N];
    if (!out_octs || !out_amps) {
        delete[] out_octs;
        delete[] out_amps;
        out_octs = nullptr; out_amps = nullptr;
        lattice::jni::throw_oom(env, "lattice double-perlin: half alloc");
        return false;
    }
    jdouble* origins = env->GetDoubleArrayElements(jOrigins, nullptr);
    jdouble* amps    = env->GetDoubleArrayElements(jAmps, nullptr);
    jbyte*   perms   = env->GetByteArrayElements(jPerms, nullptr);
    if (!origins || !amps || !perms) {
        if (perms)   env->ReleaseByteArrayElements(jPerms, perms, JNI_ABORT);
        if (amps)    env->ReleaseDoubleArrayElements(jAmps, amps, JNI_ABORT);
        if (origins) env->ReleaseDoubleArrayElements(jOrigins, origins, JNI_ABORT);
        delete[] out_octs;
        delete[] out_amps;
        out_octs = nullptr; out_amps = nullptr;
        lattice::jni::throw_oom(env, "lattice double-perlin: pin");
        return false;
    }
    for (std::size_t i = 0; i < N; ++i) {
        out_octs[i].origin_x = origins[i * 3 + 0];
        out_octs[i].origin_y = origins[i * 3 + 1];
        out_octs[i].origin_z = origins[i * 3 + 2];
        std::memcpy(out_octs[i].permutation, perms + i * 256, 256);
        out_amps[i] = amps[i];
    }
    env->ReleaseByteArrayElements(jPerms, perms, JNI_ABORT);
    env->ReleaseDoubleArrayElements(jAmps, amps, JNI_ABORT);
    env->ReleaseDoubleArrayElements(jOrigins, origins, JNI_ABORT);
    out_count = N;
    return true;
}

} // namespace

extern "C" {

/*
 * nativeCreate(double[] firstOrigins, byte[] firstPerms, double[] firstAmps,
 *              double firstLacunarity, double firstPersistence,
 *              double[] secondOrigins, byte[] secondPerms, double[] secondAmps,
 *              double secondLacunarity, double secondPersistence,
 *              double amplitude)
 *   → long handle
 */
JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativeDoublePerlinNoise_nativeCreate(
        JNIEnv* env, jclass /*cls*/,
        jdoubleArray jFirstOrigins, jbyteArray jFirstPerms, jdoubleArray jFirstAmps,
        jdouble firstLacunarity, jdouble firstPersistence,
        jdoubleArray jSecondOrigins, jbyteArray jSecondPerms, jdoubleArray jSecondAmps,
        jdouble secondLacunarity, jdouble secondPersistence,
        jdouble amplitude) {
    if (!jFirstOrigins || !jFirstPerms || !jFirstAmps
        || !jSecondOrigins || !jSecondPerms || !jSecondAmps) {
        lattice::jni::throw_illegal_arg(env, "lattice double-perlin: null array");
        return 0;
    }
    Bundle* b = new (std::nothrow) Bundle{};
    if (!b) {
        lattice::jni::throw_oom(env, "lattice double-perlin: bundle alloc");
        return 0;
    }

    std::size_t n1 = 0, n2 = 0;
    if (!populate_half(env, jFirstOrigins, jFirstPerms, jFirstAmps,
                       b->first_octaves, b->first_amplitudes, n1, "first")) {
        delete b;
        return 0;
    }
    if (!populate_half(env, jSecondOrigins, jSecondPerms, jSecondAmps,
                       b->second_octaves, b->second_amplitudes, n2, "second")) {
        delete[] b->first_octaves;
        delete[] b->first_amplitudes;
        delete b;
        return 0;
    }

    b->sampler.first.octaves       = b->first_octaves;
    b->sampler.first.amplitudes    = b->first_amplitudes;
    b->sampler.first.octave_count  = n1;
    b->sampler.first.lacunarity    = firstLacunarity;
    b->sampler.first.persistence   = firstPersistence;

    b->sampler.second.octaves      = b->second_octaves;
    b->sampler.second.amplitudes   = b->second_amplitudes;
    b->sampler.second.octave_count = n2;
    b->sampler.second.lacunarity   = secondLacunarity;
    b->sampler.second.persistence  = secondPersistence;

    b->sampler.amplitude           = amplitude;
    return reinterpret_cast<jlong>(b);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDoublePerlinNoise_nativeDestroy(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    auto* b = reinterpret_cast<Bundle*>(handle);
    if (!b) return;
    delete[] b->first_octaves;
    delete[] b->first_amplitudes;
    delete[] b->second_octaves;
    delete[] b->second_amplitudes;
    delete b;
}

JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativeDoublePerlinNoise_nativeSample(
        JNIEnv* /*env*/, jclass /*cls*/,
        jlong handle, jdouble x, jdouble y, jdouble z) {
    auto* b = reinterpret_cast<Bundle*>(handle);
    if (!b) return 0.0;
    return png::sample(b->sampler, x, y, z);
}

} // extern "C"
