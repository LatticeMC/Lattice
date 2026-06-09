#include <jni.h>

#include <vector>

#include "jni_helper.hpp"
#include "world/gen/densityfunction/beardifier.hpp"

namespace bf = lattice::world::gen::densityfunction::beardifier;

extern "C" {

JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativeBeardifier_nativeCompute(
        JNIEnv* env, jclass /*cls*/,
        jintArray pieceInts,
        jintArray junctionInts,
        jint blockX, jint blockY, jint blockZ) {
    std::vector<bf::RigidPiece> pieces;
    std::vector<bf::Junction> junctions;

    if (pieceInts) {
        const jsize n = env->GetArrayLength(pieceInts);
        if (n % 8 != 0) return 0.0;
        lattice::jni::CriticalIntArray ints{env, pieceInts};
        if (!ints) return 0.0;
        auto* p = reinterpret_cast<const jint*>(ints.data());
        const int count = static_cast<int>(n / 8);
        pieces.reserve(static_cast<std::size_t>(count));
        for (int i = 0; i < count; ++i) {
            const int off = i * 8;
            pieces.push_back(bf::RigidPiece{
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
        if (n % 3 != 0) return 0.0;
        lattice::jni::CriticalIntArray ints{env, junctionInts};
        if (!ints) return 0.0;
        auto* p = reinterpret_cast<const jint*>(ints.data());
        const int count = static_cast<int>(n / 3);
        junctions.reserve(static_cast<std::size_t>(count));
        for (int i = 0; i < count; ++i) {
            const int off = i * 3;
            junctions.push_back(bf::Junction{
                static_cast<int>(p[off + 0]),
                static_cast<int>(p[off + 1]),
                static_cast<int>(p[off + 2]),
            });
        }
    }

    return static_cast<jdouble>(bf::compute(pieces.data(), static_cast<int>(pieces.size()),
                                            junctions.data(), static_cast<int>(junctions.size()),
                                            static_cast<int>(blockX),
                                            static_cast<int>(blockY),
                                            static_cast<int>(blockZ)));
}

} // extern "C"
