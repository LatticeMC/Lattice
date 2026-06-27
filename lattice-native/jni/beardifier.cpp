#include <jni.h>

#include <vector>

#include "jni_helper.hpp"
#include "world/gen/densityfunction/beardifier.hpp"

namespace bf = lattice::world::gen::densityfunction::beardifier;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativeBeardifier_nativeCreate(
        JNIEnv* env, jclass /*cls*/,
        jintArray pieceInts,
        jintArray junctionInts) {
    auto* store = new bf::BeardifierData{};

    if (pieceInts) {
        const jsize n = env->GetArrayLength(pieceInts);
        if (n % 8 != 0) { delete store; return 0; }
        lattice::jni::CriticalIntArray ints{env, pieceInts};
        if (!ints) { delete store; return 0; }
        auto* p = reinterpret_cast<const jint*>(ints.data());
        const int count = static_cast<int>(n / 8);
        store->pieces.reserve(static_cast<std::size_t>(count));
        for (int i = 0; i < count; ++i) {
            const int off = i * 8;
            store->pieces.push_back(bf::RigidPiece{
                static_cast<int>(p[off + 0]),
                static_cast<int>(p[off + 1]),
                static_cast<int>(p[off + 2]),
                static_cast<int>(p[off + 3]),
                static_cast<int>(p[off + 4]),
                static_cast<int>(p[off + 5]),
                static_cast<bf::TerrainAdjustment>(p[off + 6]),
                static_cast<int>(p[off + 7]),
            });
        }
    }

    if (junctionInts) {
        const jsize n = env->GetArrayLength(junctionInts);
        if (n % 3 != 0) { delete store; return 0; }
        lattice::jni::CriticalIntArray ints{env, junctionInts};
        if (!ints) { delete store; return 0; }
        auto* p = reinterpret_cast<const jint*>(ints.data());
        const int count = static_cast<int>(n / 3);
        store->junctions.reserve(static_cast<std::size_t>(count));
        for (int i = 0; i < count; ++i) {
            const int off = i * 3;
            store->junctions.push_back(bf::Junction{
                static_cast<int>(p[off + 0]),
                static_cast<int>(p[off + 1]),
                static_cast<int>(p[off + 2]),
            });
        }
    }

    bf::prepare_spatial_buckets(*store);

    return reinterpret_cast<jlong>(store);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeBeardifier_nativeDestroy(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    delete reinterpret_cast<bf::BeardifierData*>(handle);
}

JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativeBeardifier_nativeCompute(
        JNIEnv* /*env*/, jclass /*cls*/,
        jlong handle,
        jint blockX, jint blockY, jint blockZ) {
    auto* data = reinterpret_cast<bf::BeardifierData*>(handle);
    if (!data) return 0.0;
    return static_cast<jdouble>(bf::compute(*data,
                                            static_cast<int>(blockX),
                                            static_cast<int>(blockY),
                                            static_cast<int>(blockZ)));
}

} // extern "C"
