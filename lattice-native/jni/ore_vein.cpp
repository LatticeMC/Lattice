// JNI bindings for NativeOreVeinSampler.
//
// Java class: com.latticemc.lattice.nativelib.NativeOreVeinSampler
//
// This is a stateless functional module: each call passes the three
// pre-sampled density-function values (vein_toggle / vein_ridged /
// vein_gap), the world-deriver Splitter's two seed words, and the
// (x, y, z) block coordinates. Output is one OreVeinResult code that
// the Java side maps to a concrete BlockState.
//
// Stateless — there's no `nativeCreate`/`nativeDestroy` pair. Callers
// can keep the Splitter seeds in a final long[] field on the Java
// side; we never take ownership.

#include <jni.h>

#include <vector>

#include "world/gen/orevein/ore_vein.hpp"
#include "world/gen/rng/xoroshiro128pp.hpp"
#include "jni_helper.hpp"

namespace orevein = lattice::world::gen::orevein;
namespace rng     = lattice::world::gen::rng;

extern "C" {

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeOreVeinSampler_nativeSample(
        JNIEnv* /*env*/, jclass /*cls*/,
        jdouble veinToggle, jdouble veinRidged, jdouble veinGap,
        jlong splitterSeedLo, jlong splitterSeedHi,
        jint blockX, jint blockY, jint blockZ) {
    const orevein::VeinSamples samples{
        static_cast<double>(veinToggle),
        static_cast<double>(veinRidged),
        static_cast<double>(veinGap),
    };
    const rng::Splitter splitter{
        static_cast<std::uint64_t>(splitterSeedLo),
        static_cast<std::uint64_t>(splitterSeedHi),
    };
    const orevein::OreVeinResult r = orevein::sample_at(
        samples, splitter,
        static_cast<int>(blockX),
        static_cast<int>(blockY),
        static_cast<int>(blockZ));
    return static_cast<jint>(r);
}

} // extern "C"
