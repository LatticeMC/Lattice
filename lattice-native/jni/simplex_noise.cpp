// JNI bindings for NativeSimplexNoise.
//
// Java class: com.latticemc.lattice.nativelib.NativeSimplexNoise

#include <jni.h>

#include <cstring>
#include <new>

#include "jni_helper.hpp"
#include "world/gen/noise/simplex_noise.hpp"

namespace png = lattice::world::gen::noise;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativeSimplexNoise_nativeCreate(
        JNIEnv* env, jclass /*cls*/,
        jintArray jPermutation,
        jdouble originX, jdouble originY, jdouble originZ) {
    if (!jPermutation) {
        lattice::jni::throw_illegal_arg(env, "lattice simplex: null permutation");
        return 0;
    }
    if (env->GetArrayLength(jPermutation) < 256) {
        lattice::jni::throw_illegal_arg(env, "lattice simplex: permutation must contain at least 256 ints");
        return 0;
    }
    auto* s = new (std::nothrow) png::SimplexNoiseSampler{};
    if (!s) {
        lattice::jni::throw_oom(env, "lattice simplex: sampler alloc");
        return 0;
    }
    s->origin_x = originX;
    s->origin_y = originY;
    s->origin_z = originZ;
    jint* p = env->GetIntArrayElements(jPermutation, nullptr);
    if (!p) {
        delete s;
        lattice::jni::throw_oom(env, "lattice simplex: pin permutation");
        return 0;
    }
    for (int i = 0; i < 256; ++i) s->permutation[i] = static_cast<std::int32_t>(p[i]);
    env->ReleaseIntArrayElements(jPermutation, p, JNI_ABORT);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeSimplexNoise_nativeDestroy(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    delete reinterpret_cast<png::SimplexNoiseSampler*>(handle);
}

JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativeSimplexNoise_nativeSample2d(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jdouble x, jdouble y) {
    auto* s = reinterpret_cast<png::SimplexNoiseSampler*>(handle);
    if (!s) return 0.0;
    return png::sample_2d(*s, x, y);
}

JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativeSimplexNoise_nativeSample3d(
        JNIEnv* /*env*/, jclass /*cls*/,
        jlong handle, jdouble x, jdouble y, jdouble z) {
    auto* s = reinterpret_cast<png::SimplexNoiseSampler*>(handle);
    if (!s) return 0.0;
    return png::sample_3d(*s, x, y, z);
}

} // extern "C"
