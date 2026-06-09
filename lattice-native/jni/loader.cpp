// JNI_OnLoad — library entry point.
//
// Responsibilities:
//   1. Initialise the CPU-feature snapshot exactly once.
//   2. Record JavaVM for any future thread-attach needs.
//   3. Report the minimum supported JNI version.
//
// Individual modules register their native methods lazily the first time a
// Java-side <clinit> calls them, not here — this keeps the loader decoupled
// from module wiring and ensures that a partial rebuild (missing module)
// doesn't break `System.loadLibrary`.

#include <jni.h>

#include "lattice/dispatch.hpp"
#include "world/entity/aabb_query.hpp"       // init_aabb_dispatch
#include "world/entity/collision_sweep.hpp"  // init_collision_dispatch
#include "world/entity/visibility_scan.hpp"  // init_visibility_dispatch
#include "world/palette/packed_storage.hpp"  // init_palette_dispatch

namespace lattice::runtime {

JavaVM* g_vm = nullptr;

JavaVM* vm() noexcept { return g_vm; }

} // namespace lattice::runtime

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    lattice::runtime::g_vm = vm;

    // Populate CPU features once, up front. Cheap (CPUID + a couple getenv).
    (void)lattice::cpu::initialize();

    // Pre-select the best implementation for every dispatched module
    // now so the very first Java call lands on the fastest variant.
    // All these calls are idempotent and cheap (single atomic check
    // after the first call).
    lattice::world::palette::init_palette_dispatch();
    lattice::world::entity::init_visibility_dispatch();
    lattice::world::entity::init_aabb_dispatch();
    lattice::world::entity::init_collision_dispatch();

    // We rely on JNI 1.8 (varargs NewObject, direct buffers, critical arrays).
    return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
    // Nothing to tear down globally; per-module state owns itself.
    lattice::runtime::g_vm = nullptr;
}

// ---- Java-visible entry points --------------------------------------------
//
// Symbol:  Java_com_latticemc_lattice_nativelib_LatticeNative_nativeCpuSummary
// Java:    static native String nativeCpuSummary();
//
// Returns a one-line description of the CPU/ISA tier the library chose at
// load time. Safe to call from any Java thread.

extern "C" JNIEXPORT jstring JNICALL
Java_com_latticemc_lattice_nativelib_LatticeNative_nativeCpuSummary(
        JNIEnv* env, jclass /*clazz*/) {
    const char* s = lattice::cpu::summary();
    return env->NewStringUTF(s ? s : "");
}
