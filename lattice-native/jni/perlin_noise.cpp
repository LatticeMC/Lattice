// JNI bindings for NativePerlinNoise.
//
// Java class: com.latticemc.lattice.nativelib.NativePerlinNoise
//
// Handle-based API: Java constructs a sampler by passing the
// pre-computed permutation array + 3 origins; the native side owns
// the PerlinNoiseSampler in a heap allocation and returns a long
// handle. Sampling methods take the handle + the (x, y, z) of interest.

#include <jni.h>

#include <cstdint>
#include <new>
#include <cstring>

#include "jni_helper.hpp"
#include "world/gen/noise/perlin_noise.hpp"

namespace png = lattice::world::gen::noise;

extern "C" {

/*
 * nativeCreate(byte[] permutation, double originX, double originY, double originZ)
 *   → long handle (0 on error)
 *
 * `permutation` must be exactly 256 bytes. The native side copies it
 * into the heap-allocated sampler; the caller's array can be reused
 * after this call returns.
 */
JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativePerlinNoise_nativeCreate(
        JNIEnv* env, jclass /*cls*/,
        jbyteArray jPermutation,
        jdouble    originX, jdouble originY, jdouble originZ) {
    if (!jPermutation) {
        lattice::jni::throw_illegal_arg(env, "lattice perlin: null permutation");
        return 0;
    }
    if (env->GetArrayLength(jPermutation) != 256) {
        lattice::jni::throw_illegal_arg(env, "lattice perlin: permutation must be 256 bytes");
        return 0;
    }

    auto* s = new (std::nothrow) png::PerlinNoiseSampler{};
    if (!s) {
        lattice::jni::throw_oom(env, "lattice perlin: sampler alloc");
        return 0;
    }
    s->origin_x = static_cast<double>(originX);
    s->origin_y = static_cast<double>(originY);
    s->origin_z = static_cast<double>(originZ);

    // Copy permutation bytes. Java byte[] is signed; reinterpret to
    // unsigned for our sampler.
    jbyte* p = env->GetByteArrayElements(jPermutation, nullptr);
    if (!p) {
        delete s;
        lattice::jni::throw_oom(env, "lattice perlin: pin permutation");
        return 0;
    }
    std::memcpy(s->permutation, p, 256);
    env->ReleaseByteArrayElements(jPermutation, p, JNI_ABORT);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativePerlinNoise_nativeDestroy(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    auto* s = reinterpret_cast<png::PerlinNoiseSampler*>(handle);
    delete s;
}

JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativePerlinNoise_nativeSample(
        JNIEnv* /*env*/, jclass /*cls*/,
        jlong handle, jdouble x, jdouble y, jdouble z) {
    auto* s = reinterpret_cast<png::PerlinNoiseSampler*>(handle);
    if (!s) return 0.0;
    return png::sample(*s, static_cast<double>(x),
                       static_cast<double>(y),
                       static_cast<double>(z));
}

JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativePerlinNoise_nativeSampleYScaled(
        JNIEnv* /*env*/, jclass /*cls*/,
        jlong handle, jdouble x, jdouble y, jdouble z,
        jdouble yScale, jdouble yMax) {
    auto* s = reinterpret_cast<png::PerlinNoiseSampler*>(handle);
    if (!s) return 0.0;
    return png::sample_y_scaled(*s, x, y, z, yScale, yMax);
}

/*
 * nativeSampleDerivative(handle, x, y, z, outDxDyDz)
 *   → double value, writes 3 doubles into outDxDyDz
 */
JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativePerlinNoise_nativeSampleDerivative(
        JNIEnv* env, jclass /*cls*/,
        jlong handle, jdouble x, jdouble y, jdouble z,
        jdoubleArray jOutDeriv) {
    auto* s = reinterpret_cast<png::PerlinNoiseSampler*>(handle);
    if (!s) return 0.0;
    double deriv[3] = {0.0, 0.0, 0.0};
    const double v = png::sample_derivative(*s, x, y, z, deriv);
    if (jOutDeriv && env->GetArrayLength(jOutDeriv) >= 3) {
        env->SetDoubleArrayRegion(jOutDeriv, 0, 3, deriv);
    }
    return v;
}

} // extern "C"
