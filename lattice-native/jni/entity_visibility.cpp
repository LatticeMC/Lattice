// JNI bindings for entity-related batch primitives:
//
//   - NativeEntityVisibility.scanVisibility    — O(N×M) distance scan
//   - NativeAabbQuery.scanIntersect            — O(Q×E) AABB intersect scan
//   - NativeCollisionSweep.adjustMovement      — swept-AABB clamp
//   - NativeSpawnFilter.filterCandidates       — composite spawn filter
//
// All four use the same shape: pin input/output arrays via
// GetPrimitiveArrayCritical (or GetXxxArrayElements for object arrays
// like long[][]), run the native batch function, release.
// No JNI calls inside the critical region.

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <new>

#include "jni_helper.hpp"
#include "world/entity/approach_target_sampler.hpp"
#include "world/entity/aabb_query.hpp"
#include "world/entity/collision_sweep.hpp"
#include "world/entity/flee_target_sampler.hpp"
#include "world/entity/home_target_sampler.hpp"
#include "world/entity/water_target_sampler.hpp"
#include "world/entity/spawn_filter.hpp"
#include "world/entity/visibility_scan.hpp"

namespace ve = lattice::world::entity;

extern "C" {

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeEntityVisibility_nativeScanVisibility(
        JNIEnv* env, jclass /*cls*/,
        jdoubleArray jEntityXyz,
        jdoubleArray jEntityRangeSq,
        jint         entityCount,
        jdoubleArray jPlayerXyz,
        jint         playerCount,
        jlongArray   jVisibility) {
    if (!jVisibility) {
        lattice::jni::throw_illegal_arg(env, "lattice entity-vis: null visibility");
        return;
    }
    if (entityCount < 0 || playerCount < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice entity-vis: negative count");
        return;
    }

    const std::size_t ec = static_cast<std::size_t>(entityCount);
    const std::size_t pc = static_cast<std::size_t>(playerCount);
    const std::size_t row_l = ve::row_longs(pc);

    // Validate array lengths.
    if (ec > 0) {
        if (!jEntityXyz || !jEntityRangeSq) {
            lattice::jni::throw_illegal_arg(env, "lattice entity-vis: null entity arrays");
            return;
        }
        if (env->GetArrayLength(jEntityXyz)    < static_cast<jsize>(ec * 3) ||
            env->GetArrayLength(jEntityRangeSq) < static_cast<jsize>(ec)) {
            lattice::jni::throw_illegal_arg(env, "lattice entity-vis: entity arrays too short");
            return;
        }
    }
    if (pc > 0) {
        if (!jPlayerXyz) {
            lattice::jni::throw_illegal_arg(env, "lattice entity-vis: null player array");
            return;
        }
        if (env->GetArrayLength(jPlayerXyz) < static_cast<jsize>(pc * 3)) {
            lattice::jni::throw_illegal_arg(env, "lattice entity-vis: player array too short");
            return;
        }
    }
    if (env->GetArrayLength(jVisibility) < static_cast<jsize>(ec * row_l)) {
        lattice::jni::throw_illegal_arg(env, "lattice entity-vis: visibility too short");
        return;
    }

    // Pin all the input/output arrays. None of the operations between
    // here and the release() calls take a JVM safepoint, so it's safe to
    // hold them all simultaneously with GetPrimitiveArrayCritical (which
    // is significantly faster than GetArrayElements for the input
    // double[]s that may be 100s of KB).
    void* ent_xyz_p = ec > 0
        ? env->GetPrimitiveArrayCritical(jEntityXyz, nullptr) : nullptr;
    if (ec > 0 && !ent_xyz_p) {
        lattice::jni::throw_oom(env, "lattice entity-vis: pin entity xyz");
        return;
    }
    void* ent_r2_p = ec > 0
        ? env->GetPrimitiveArrayCritical(jEntityRangeSq, nullptr) : nullptr;
    if (ec > 0 && !ent_r2_p) {
        if (ent_xyz_p) env->ReleasePrimitiveArrayCritical(jEntityXyz, ent_xyz_p, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice entity-vis: pin entity r²");
        return;
    }
    void* pl_xyz_p = pc > 0
        ? env->GetPrimitiveArrayCritical(jPlayerXyz, nullptr) : nullptr;
    if (pc > 0 && !pl_xyz_p) {
        if (ent_r2_p)  env->ReleasePrimitiveArrayCritical(jEntityRangeSq, ent_r2_p, JNI_ABORT);
        if (ent_xyz_p) env->ReleasePrimitiveArrayCritical(jEntityXyz, ent_xyz_p, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice entity-vis: pin player xyz");
        return;
    }
    void* vis_p = env->GetPrimitiveArrayCritical(jVisibility, nullptr);
    if (!vis_p) {
        if (pl_xyz_p)  env->ReleasePrimitiveArrayCritical(jPlayerXyz, pl_xyz_p, JNI_ABORT);
        if (ent_r2_p)  env->ReleasePrimitiveArrayCritical(jEntityRangeSq, ent_r2_p, JNI_ABORT);
        if (ent_xyz_p) env->ReleasePrimitiveArrayCritical(jEntityXyz, ent_xyz_p, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice entity-vis: pin visibility");
        return;
    }

    ve::scan(
        static_cast<const double*>(ent_xyz_p),
        static_cast<const double*>(ent_r2_p),
        ec,
        static_cast<const double*>(pl_xyz_p),
        pc,
        static_cast<std::uint64_t*>(vis_p));

    // Release: commit only the output, abort the read-only inputs.
    env->ReleasePrimitiveArrayCritical(jVisibility, vis_p, 0);
    if (pl_xyz_p)  env->ReleasePrimitiveArrayCritical(jPlayerXyz, pl_xyz_p, JNI_ABORT);
    if (ent_r2_p)  env->ReleasePrimitiveArrayCritical(jEntityRangeSq, ent_r2_p, JNI_ABORT);
    if (ent_xyz_p) env->ReleasePrimitiveArrayCritical(jEntityXyz, ent_xyz_p, JNI_ABORT);
}

// ---------------------------------------------------------------------------
// NativeAabbQuery.scanIntersect
// ---------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeAabbQuery_nativeScanIntersect(
        JNIEnv* env, jclass /*cls*/,
        jdoubleArray jQueryAabbs, jint queryCount,
        jdoubleArray jEntityAabbs, jint entityCount,
        jlongArray jVisibility) {
    if (!jVisibility) {
        lattice::jni::throw_illegal_arg(env, "lattice aabb: null visibility");
        return;
    }
    if (queryCount < 0 || entityCount < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice aabb: negative count");
        return;
    }

    const std::size_t qc    = static_cast<std::size_t>(queryCount);
    const std::size_t ec    = static_cast<std::size_t>(entityCount);
    const std::size_t row_l = ve::aabb_row_longs(ec);

    if (qc > 0) {
        if (!jQueryAabbs ||
            env->GetArrayLength(jQueryAabbs) <
                static_cast<jsize>(qc * ve::kAabbStride)) {
            lattice::jni::throw_illegal_arg(env, "lattice aabb: queries too short");
            return;
        }
    }
    if (ec > 0) {
        if (!jEntityAabbs ||
            env->GetArrayLength(jEntityAabbs) <
                static_cast<jsize>(ec * ve::kAabbStride)) {
            lattice::jni::throw_illegal_arg(env, "lattice aabb: entities too short");
            return;
        }
    }
    if (env->GetArrayLength(jVisibility) < static_cast<jsize>(qc * row_l)) {
        lattice::jni::throw_illegal_arg(env, "lattice aabb: visibility too short");
        return;
    }

    void* q_p = qc > 0
        ? env->GetPrimitiveArrayCritical(jQueryAabbs, nullptr) : nullptr;
    if (qc > 0 && !q_p) {
        lattice::jni::throw_oom(env, "lattice aabb: pin queries");
        return;
    }
    void* e_p = ec > 0
        ? env->GetPrimitiveArrayCritical(jEntityAabbs, nullptr) : nullptr;
    if (ec > 0 && !e_p) {
        if (q_p) env->ReleasePrimitiveArrayCritical(jQueryAabbs, q_p, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice aabb: pin entities");
        return;
    }
    void* v_p = env->GetPrimitiveArrayCritical(jVisibility, nullptr);
    if (!v_p) {
        if (e_p) env->ReleasePrimitiveArrayCritical(jEntityAabbs, e_p, JNI_ABORT);
        if (q_p) env->ReleasePrimitiveArrayCritical(jQueryAabbs, q_p, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice aabb: pin visibility");
        return;
    }

    ve::aabb_scan(
        static_cast<const double*>(q_p), qc,
        static_cast<const double*>(e_p), ec,
        static_cast<std::uint64_t*>(v_p));

    env->ReleasePrimitiveArrayCritical(jVisibility, v_p, 0);
    if (e_p) env->ReleasePrimitiveArrayCritical(jEntityAabbs, e_p, JNI_ABORT);
    if (q_p) env->ReleasePrimitiveArrayCritical(jQueryAabbs, q_p, JNI_ABORT);
}

// ---------------------------------------------------------------------------
// NativeCollisionSweep.adjustMovement
// ---------------------------------------------------------------------------

/*
 * Method:    nativeAdjustMovement
 * Signature: ([D[D[DI)V
 *
 * Inputs:
 *   moving       — 6 doubles (minXYZ, maxXYZ) of the moving entity
 *   movement     — 3 doubles (dx, dy, dz) input/output; clamped in place
 *   obstacles    — N × 6 doubles
 *   obstacleCount
 *
 * No output other than the clamped movement[]; the moving box itself is
 * not modified.
 */
JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeCollisionSweep_nativeAdjustMovement(
        JNIEnv* env, jclass /*cls*/,
        jdoubleArray jMoving,
        jdoubleArray jMovement,
        jdoubleArray jObstacles,
        jint         obstacleCount) {
    if (!jMoving || !jMovement) {
        lattice::jni::throw_illegal_arg(env, "lattice collision: null array");
        return;
    }
    if (obstacleCount < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice collision: negative obstacle count");
        return;
    }
    if (env->GetArrayLength(jMoving)   < static_cast<jsize>(ve::kCollisionAabbStride) ||
        env->GetArrayLength(jMovement) < 3) {
        lattice::jni::throw_illegal_arg(env, "lattice collision: moving/movement too short");
        return;
    }
    if (obstacleCount > 0) {
        if (!jObstacles ||
            env->GetArrayLength(jObstacles) <
                static_cast<jsize>(obstacleCount * ve::kCollisionAabbStride)) {
            lattice::jni::throw_illegal_arg(env, "lattice collision: obstacles too short");
            return;
        }
    }

    void* mv_p = env->GetPrimitiveArrayCritical(jMoving, nullptr);
    if (!mv_p) {
        lattice::jni::throw_oom(env, "lattice collision: pin moving");
        return;
    }
    void* mo_p = env->GetPrimitiveArrayCritical(jMovement, nullptr);
    if (!mo_p) {
        env->ReleasePrimitiveArrayCritical(jMoving, mv_p, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice collision: pin movement");
        return;
    }
    void* ob_p = obstacleCount > 0
        ? env->GetPrimitiveArrayCritical(jObstacles, nullptr) : nullptr;
    if (obstacleCount > 0 && !ob_p) {
        env->ReleasePrimitiveArrayCritical(jMovement, mo_p, JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(jMoving, mv_p, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice collision: pin obstacles");
        return;
    }

    ve::adjust_movement(
        static_cast<const double*>(mv_p),
        static_cast<double*>(mo_p),
        static_cast<const double*>(ob_p),
        static_cast<std::size_t>(obstacleCount));

    if (ob_p) env->ReleasePrimitiveArrayCritical(jObstacles, ob_p, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(jMovement, mo_p, 0); // commit clamp
    env->ReleasePrimitiveArrayCritical(jMoving, mv_p, JNI_ABORT);
}

// ---------------------------------------------------------------------------
// NativeCollisionSweep.calcMaxOffset  (single-axis)
// ---------------------------------------------------------------------------

/*
 * Method:    nativeCalcMaxOffset
 * Signature: (I[D[D[DI)D
 *
 * Single-axis swept-AABB clamp. Returns the clamped displacement on the
 * specified axis.
 */
JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativeCollisionSweep_nativeCalcMaxOffset(
        JNIEnv* env, jclass /*cls*/,
        jint       axis,
        jdoubleArray jMoving,
        jdouble    desired,
        jdoubleArray jObstacles,
        jint       obstacleCount) {
    if (!jMoving) {
        lattice::jni::throw_illegal_arg(env, "lattice calcMaxOffset: null moving");
        return desired;
    }
    if (axis < 0 || axis > 2) {
        lattice::jni::throw_illegal_arg(env, "lattice calcMaxOffset: invalid axis");
        return desired;
    }
    if (obstacleCount < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice calcMaxOffset: negative count");
        return desired;
    }
    if (env->GetArrayLength(jMoving) < static_cast<jsize>(ve::kCollisionAabbStride)) {
        lattice::jni::throw_illegal_arg(env, "lattice calcMaxOffset: moving too short");
        return desired;
    }
    if (obstacleCount > 0) {
        if (!jObstacles ||
            env->GetArrayLength(jObstacles) <
                static_cast<jsize>(obstacleCount * ve::kCollisionAabbStride)) {
            lattice::jni::throw_illegal_arg(env, "lattice calcMaxOffset: obstacles too short");
            return desired;
        }
    }

    void* mv_p = env->GetPrimitiveArrayCritical(jMoving, nullptr);
    if (!mv_p) {
        lattice::jni::throw_oom(env, "lattice calcMaxOffset: pin moving");
        return desired;
    }
    void* ob_p = obstacleCount > 0
        ? env->GetPrimitiveArrayCritical(jObstacles, nullptr) : nullptr;
    if (obstacleCount > 0 && !ob_p) {
        env->ReleasePrimitiveArrayCritical(jMoving, mv_p, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice calcMaxOffset: pin obstacles");
        return desired;
    }

    const double result = ve::calc_max_offset(
        static_cast<int>(axis),
        static_cast<const double*>(mv_p),
        static_cast<double>(desired),
        static_cast<const double*>(ob_p),
        static_cast<std::size_t>(obstacleCount));

    if (ob_p) env->ReleasePrimitiveArrayCritical(jObstacles, ob_p, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(jMoving, mv_p, JNI_ABORT);
    return static_cast<jdouble>(result);
}

// ---------------------------------------------------------------------------
// NativeSpawnFilter.filterCandidates
// ---------------------------------------------------------------------------
//
// Big-input function: takes per-section long[]s (via Object[] of long[]),
// per-section int[], flat double[]s, and a flat output long[]. We don't
// use GetPrimitiveArrayCritical for the section storages because nested
// critical regions across object-array element accesses are awkward;
// use GetLongArrayElements + small stack-side bookkeeping.

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeSpawnFilter_nativeFilterCandidates(
        JNIEnv* env, jclass /*cls*/,
        jdoubleArray   jCandidateXyz, jint candidateCount,
        jdoubleArray   jCandidateDims,
        jobjectArray   jSectionStorages,
        jintArray      jSectionElementBits,
        jlongArray     jSectionPassMasks,
        jint           sectionCount,
        jint           sectionBaseY,
        jdoubleArray   jEntityAabbs, jint entityCount,
        jdoubleArray   jPlayerXyz, jint playerCount,
        jdouble        maxSpawnDistanceSq,
        jlongArray     jAcceptable) {
    if (!jAcceptable) {
        lattice::jni::throw_illegal_arg(env, "lattice spawn: null acceptable");
        return 0;
    }
    if (candidateCount < 0 || sectionCount < 0
        || entityCount < 0 || playerCount < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice spawn: negative count");
        return 0;
    }

    // Collect per-section pinned storages on the native heap.
    const std::size_t sc = static_cast<std::size_t>(sectionCount);
    const std::uint64_t**  storage_ptrs  = nullptr;
    std::size_t*           storage_lens  = nullptr;
    jlongArray*            storage_jarrs = nullptr;
    jlong**                storage_pinned = nullptr;

    if (sc > 0) {
        storage_ptrs   = static_cast<const std::uint64_t**>(::operator new(sc * sizeof(void*), std::nothrow));
        storage_lens   = static_cast<std::size_t*>(::operator new(sc * sizeof(std::size_t), std::nothrow));
        storage_jarrs  = static_cast<jlongArray*>(::operator new(sc * sizeof(jlongArray), std::nothrow));
        storage_pinned = static_cast<jlong**>(::operator new(sc * sizeof(void*), std::nothrow));
        if (!storage_ptrs || !storage_lens || !storage_jarrs || !storage_pinned) {
            ::operator delete(storage_ptrs);
            ::operator delete(storage_lens);
            ::operator delete(storage_jarrs);
            ::operator delete(storage_pinned);
            lattice::jni::throw_oom(env, "lattice spawn: section ptrs alloc");
            return 0;
        }
        for (std::size_t i = 0; i < sc; ++i) {
            storage_ptrs[i]   = nullptr;
            storage_lens[i]   = 0;
            storage_jarrs[i]  = nullptr;
            storage_pinned[i] = nullptr;
        }
    }

    auto cleanup_storages = [&]() noexcept {
        if (storage_pinned) {
            for (std::size_t i = 0; i < sc; ++i) {
                if (storage_pinned[i]) {
                    env->ReleaseLongArrayElements(storage_jarrs[i], storage_pinned[i], JNI_ABORT);
                    env->DeleteLocalRef(storage_jarrs[i]);
                }
            }
        }
        ::operator delete(storage_ptrs);
        ::operator delete(storage_lens);
        ::operator delete(storage_jarrs);
        ::operator delete(storage_pinned);
    };

    // Pin section storages (long[][]).
    if (sc > 0 && jSectionStorages) {
        for (std::size_t i = 0; i < sc; ++i) {
            jobject elem = env->GetObjectArrayElement(jSectionStorages, static_cast<jsize>(i));
            if (!elem) continue;
            jlongArray arr = static_cast<jlongArray>(elem);
            jlong* ptr = env->GetLongArrayElements(arr, nullptr);
            if (!ptr) {
                env->DeleteLocalRef(elem);
                cleanup_storages();
                lattice::jni::throw_oom(env, "lattice spawn: pin section storage");
                return 0;
            }
            storage_jarrs[i]  = arr;
            storage_pinned[i] = ptr;
            storage_ptrs[i]   = reinterpret_cast<const std::uint64_t*>(ptr);
            storage_lens[i]   = static_cast<std::size_t>(env->GetArrayLength(arr));
        }
    }

    // Pin int[] section_element_bits, double[] section_pass_masks, double[] entity_aabbs,
    // double[] player_xyz, double[] candidate_xyz, double[] candidate_dims, long[] acceptable.
    jint*   element_bits = nullptr;
    jlong*  pass_masks   = nullptr;
    jdouble* candidate_p = nullptr;
    jdouble* dims_p      = nullptr;
    jdouble* entity_p    = nullptr;
    jdouble* player_p    = nullptr;
    jlong*  acceptable_p = nullptr;

    auto cleanup_pins = [&]() noexcept {
        if (acceptable_p) env->ReleaseLongArrayElements(jAcceptable, acceptable_p, JNI_ABORT);
        if (player_p)     env->ReleaseDoubleArrayElements(jPlayerXyz, player_p, JNI_ABORT);
        if (entity_p)     env->ReleaseDoubleArrayElements(jEntityAabbs, entity_p, JNI_ABORT);
        if (dims_p)       env->ReleaseDoubleArrayElements(jCandidateDims, dims_p, JNI_ABORT);
        if (candidate_p)  env->ReleaseDoubleArrayElements(jCandidateXyz, candidate_p, JNI_ABORT);
        if (pass_masks)   env->ReleaseLongArrayElements(jSectionPassMasks, pass_masks, JNI_ABORT);
        if (element_bits) env->ReleaseIntArrayElements(jSectionElementBits, element_bits, JNI_ABORT);
        cleanup_storages();
    };

    if (sc > 0) {
        if (!jSectionElementBits || !jSectionPassMasks) {
            cleanup_storages();
            lattice::jni::throw_illegal_arg(env, "lattice spawn: null section ints/masks");
            return 0;
        }
        element_bits = env->GetIntArrayElements(jSectionElementBits, nullptr);
        pass_masks   = env->GetLongArrayElements(jSectionPassMasks, nullptr);
        if (!element_bits || !pass_masks) {
            cleanup_pins();
            lattice::jni::throw_oom(env, "lattice spawn: pin section ints/masks");
            return 0;
        }
    }
    if (candidateCount > 0) {
        if (!jCandidateXyz) {
            cleanup_pins();
            lattice::jni::throw_illegal_arg(env, "lattice spawn: null candidates");
            return 0;
        }
        candidate_p = env->GetDoubleArrayElements(jCandidateXyz, nullptr);
        if (!candidate_p) { cleanup_pins(); lattice::jni::throw_oom(env, "lattice spawn: pin candidates"); return 0; }
        // candidate_dims is optional (nullable); when provided it must have 2*N entries.
        if (jCandidateDims) {
            if (env->GetArrayLength(jCandidateDims) < candidateCount * 2) {
                cleanup_pins();
                lattice::jni::throw_illegal_arg(env, "lattice spawn: candidate dims too short");
                return 0;
            }
            dims_p = env->GetDoubleArrayElements(jCandidateDims, nullptr);
            if (!dims_p) { cleanup_pins(); lattice::jni::throw_oom(env, "lattice spawn: pin dims"); return 0; }
        }
    }
    if (entityCount > 0) {
        if (!jEntityAabbs) {
            cleanup_pins();
            lattice::jni::throw_illegal_arg(env, "lattice spawn: null entity aabbs");
            return 0;
        }
        entity_p = env->GetDoubleArrayElements(jEntityAabbs, nullptr);
        if (!entity_p) { cleanup_pins(); lattice::jni::throw_oom(env, "lattice spawn: pin entities"); return 0; }
    }
    if (playerCount > 0) {
        if (!jPlayerXyz) {
            cleanup_pins();
            lattice::jni::throw_illegal_arg(env, "lattice spawn: null players");
            return 0;
        }
        player_p = env->GetDoubleArrayElements(jPlayerXyz, nullptr);
        if (!player_p) { cleanup_pins(); lattice::jni::throw_oom(env, "lattice spawn: pin players"); return 0; }
    }
    acceptable_p = env->GetLongArrayElements(jAcceptable, nullptr);
    if (!acceptable_p) { cleanup_pins(); lattice::jni::throw_oom(env, "lattice spawn: pin acceptable"); return 0; }

    ve::SpawnFilterInputs in{};
    in.candidate_xyz         = reinterpret_cast<const double*>(candidate_p);
    in.candidate_count       = static_cast<std::size_t>(candidateCount);
    in.candidate_dims        = reinterpret_cast<const double*>(dims_p);
    in.section_storages      = storage_ptrs;
    in.section_storage_lens  = storage_lens;
    in.section_element_bits  = reinterpret_cast<const int*>(element_bits);
    in.section_pass_masks    = reinterpret_cast<const std::uint64_t*>(pass_masks);
    in.section_count         = sc;
    in.section_base_y        = static_cast<int>(sectionBaseY);
    in.entity_aabbs          = reinterpret_cast<const double*>(entity_p);
    in.entity_count          = static_cast<std::size_t>(entityCount);
    in.player_xyz            = reinterpret_cast<const double*>(player_p);
    in.player_count          = static_cast<std::size_t>(playerCount);
    in.max_spawn_distance_sq = static_cast<double>(maxSpawnDistanceSq);

    const std::size_t accepted = ve::filter_spawn_candidates(
        in, reinterpret_cast<std::uint64_t*>(acceptable_p));

    // Commit acceptable bitmap; abort everything else.
    env->ReleaseLongArrayElements(jAcceptable, acceptable_p, 0);
    acceptable_p = nullptr;
    cleanup_pins();
    return static_cast<jint>(accepted);
}

// ---------------------------------------------------------------------------
// NativeFleeTargetSampler.sampleFleeTarget
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeFleeTargetSampler_nativeSampleFleeTarget(
        JNIEnv* env, jclass /*cls*/,
        jdoubleArray jCandidateXyz, jint candidateCount,
        jdouble selfX, jdouble selfY, jdouble selfZ,
        jdouble threatX, jdouble threatY, jdouble threatZ,
        jdoubleArray jObstacleAabbs, jint obstacleCount,
        jdouble minClearance) {
    if (candidateCount < 0 || obstacleCount < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice flee-sampler: negative count");
        return -1;
    }
    if (candidateCount > 0) {
        if (!jCandidateXyz || env->GetArrayLength(jCandidateXyz) < candidateCount * 3) {
            lattice::jni::throw_illegal_arg(env, "lattice flee-sampler: candidates too short");
            return -1;
        }
    }
    if (obstacleCount > 0) {
        if (!jObstacleAabbs || env->GetArrayLength(jObstacleAabbs) < obstacleCount * 6) {
            lattice::jni::throw_illegal_arg(env, "lattice flee-sampler: obstacles too short");
            return -1;
        }
    }

    jdouble* candidates = candidateCount > 0 ? env->GetDoubleArrayElements(jCandidateXyz, nullptr) : nullptr;
    if (candidateCount > 0 && !candidates) {
        lattice::jni::throw_oom(env, "lattice flee-sampler: pin candidates");
        return -1;
    }
    jdouble* obstacles = obstacleCount > 0 ? env->GetDoubleArrayElements(jObstacleAabbs, nullptr) : nullptr;
    if (obstacleCount > 0 && !obstacles) {
        if (candidates) env->ReleaseDoubleArrayElements(jCandidateXyz, candidates, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice flee-sampler: pin obstacles");
        return -1;
    }

    ve::FleeTargetInputs in{};
    in.candidate_xyz = reinterpret_cast<const double*>(candidates);
    in.candidate_count = static_cast<std::size_t>(candidateCount);
    in.self_x = selfX;
    in.self_y = selfY;
    in.self_z = selfZ;
    in.threat_x = threatX;
    in.threat_y = threatY;
    in.threat_z = threatZ;
    in.obstacle_aabbs = reinterpret_cast<const double*>(obstacles);
    in.obstacle_count = static_cast<std::size_t>(obstacleCount);
    in.min_clearance = minClearance;

    const ve::FleeTargetResult result = ve::sample_flee_target(in);

    if (obstacles) env->ReleaseDoubleArrayElements(jObstacleAabbs, obstacles, JNI_ABORT);
    if (candidates) env->ReleaseDoubleArrayElements(jCandidateXyz, candidates, JNI_ABORT);
    return static_cast<jint>(result.candidate_index);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeApproachTargetSampler_nativeSampleApproachTarget(
        JNIEnv* env, jclass /*cls*/,
        jdoubleArray jCandidateXyz, jint candidateCount,
        jdouble selfX, jdouble selfY, jdouble selfZ,
        jdouble targetX, jdouble targetY, jdouble targetZ,
        jdoubleArray jObstacleAabbs, jint obstacleCount,
        jdouble preferredDistance,
        jdouble minClearance) {
    if (candidateCount < 0 || obstacleCount < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice approach-sampler: negative count");
        return -1;
    }
    if (candidateCount > 0) {
        if (!jCandidateXyz || env->GetArrayLength(jCandidateXyz) < candidateCount * 3) {
            lattice::jni::throw_illegal_arg(env, "lattice approach-sampler: candidates too short");
            return -1;
        }
    }
    if (obstacleCount > 0) {
        if (!jObstacleAabbs || env->GetArrayLength(jObstacleAabbs) < obstacleCount * 6) {
            lattice::jni::throw_illegal_arg(env, "lattice approach-sampler: obstacles too short");
            return -1;
        }
    }

    jdouble* candidates = candidateCount > 0 ? env->GetDoubleArrayElements(jCandidateXyz, nullptr) : nullptr;
    if (candidateCount > 0 && !candidates) {
        lattice::jni::throw_oom(env, "lattice approach-sampler: pin candidates");
        return -1;
    }
    jdouble* obstacles = obstacleCount > 0 ? env->GetDoubleArrayElements(jObstacleAabbs, nullptr) : nullptr;
    if (obstacleCount > 0 && !obstacles) {
        if (candidates) env->ReleaseDoubleArrayElements(jCandidateXyz, candidates, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice approach-sampler: pin obstacles");
        return -1;
    }

    ve::ApproachTargetInputs in{};
    in.candidate_xyz = reinterpret_cast<const double*>(candidates);
    in.candidate_count = static_cast<std::size_t>(candidateCount);
    in.self_x = selfX;
    in.self_y = selfY;
    in.self_z = selfZ;
    in.target_x = targetX;
    in.target_y = targetY;
    in.target_z = targetZ;
    in.obstacle_aabbs = reinterpret_cast<const double*>(obstacles);
    in.obstacle_count = static_cast<std::size_t>(obstacleCount);
    in.preferred_distance = preferredDistance;
    in.min_clearance = minClearance;

    const ve::ApproachTargetResult result = ve::sample_approach_target(in);

    if (obstacles) env->ReleaseDoubleArrayElements(jObstacleAabbs, obstacles, JNI_ABORT);
    if (candidates) env->ReleaseDoubleArrayElements(jCandidateXyz, candidates, JNI_ABORT);
    return static_cast<jint>(result.candidate_index);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeWaterTargetSampler_nativeSampleWaterTarget(
        JNIEnv* env, jclass /*cls*/,
        jdoubleArray jCandidateXyz,
        jbooleanArray jCandidateIsWater,
        jint candidateCount,
        jdouble selfX,
        jdouble selfY,
        jdouble selfZ,
        jboolean preferWater) {
    if (candidateCount < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice water-sampler: negative count");
        return -1;
    }
    if (candidateCount > 0) {
        if (!jCandidateXyz || env->GetArrayLength(jCandidateXyz) < candidateCount * 3) {
            lattice::jni::throw_illegal_arg(env, "lattice water-sampler: candidates too short");
            return -1;
        }
        if (!jCandidateIsWater || env->GetArrayLength(jCandidateIsWater) < candidateCount) {
            lattice::jni::throw_illegal_arg(env, "lattice water-sampler: water flags too short");
            return -1;
        }
    }

    jdouble* candidates = candidateCount > 0 ? env->GetDoubleArrayElements(jCandidateXyz, nullptr) : nullptr;
    if (candidateCount > 0 && !candidates) {
        lattice::jni::throw_oom(env, "lattice water-sampler: pin candidates");
        return -1;
    }
    jboolean* water_flags = candidateCount > 0 ? env->GetBooleanArrayElements(jCandidateIsWater, nullptr) : nullptr;
    if (candidateCount > 0 && !water_flags) {
        if (candidates) env->ReleaseDoubleArrayElements(jCandidateXyz, candidates, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice water-sampler: pin water flags");
        return -1;
    }

    ve::WaterTargetInputs in{};
    in.candidate_xyz = reinterpret_cast<const double*>(candidates);
    in.candidate_count = static_cast<std::size_t>(candidateCount);
    in.candidate_is_water = reinterpret_cast<const bool*>(water_flags);
    in.self_x = selfX;
    in.self_y = selfY;
    in.self_z = selfZ;
    in.prefer_water = preferWater == JNI_TRUE;

    const ve::WaterTargetResult result = ve::sample_water_target(in);

    if (water_flags) env->ReleaseBooleanArrayElements(jCandidateIsWater, water_flags, JNI_ABORT);
    if (candidates) env->ReleaseDoubleArrayElements(jCandidateXyz, candidates, JNI_ABORT);
    return static_cast<jint>(result.candidate_index);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeHomeTargetSampler_nativeSampleHomeTarget(
        JNIEnv* env, jclass /*cls*/,
        jdoubleArray jCandidateXyz, jint candidateCount,
        jdouble selfX, jdouble selfY, jdouble selfZ,
        jdouble homeX, jdouble homeY, jdouble homeZ,
        jdoubleArray jObstacleAabbs, jint obstacleCount,
        jdouble preferredDistance,
        jdouble minClearance) {
    if (candidateCount < 0 || obstacleCount < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice home-sampler: negative count");
        return -1;
    }
    if (candidateCount > 0) {
        if (!jCandidateXyz || env->GetArrayLength(jCandidateXyz) < candidateCount * 3) {
            lattice::jni::throw_illegal_arg(env, "lattice home-sampler: candidates too short");
            return -1;
        }
    }
    if (obstacleCount > 0) {
        if (!jObstacleAabbs || env->GetArrayLength(jObstacleAabbs) < obstacleCount * 6) {
            lattice::jni::throw_illegal_arg(env, "lattice home-sampler: obstacles too short");
            return -1;
        }
    }

    jdouble* candidates = candidateCount > 0 ? env->GetDoubleArrayElements(jCandidateXyz, nullptr) : nullptr;
    if (candidateCount > 0 && !candidates) {
        lattice::jni::throw_oom(env, "lattice home-sampler: pin candidates");
        return -1;
    }
    jdouble* obstacles = obstacleCount > 0 ? env->GetDoubleArrayElements(jObstacleAabbs, nullptr) : nullptr;
    if (obstacleCount > 0 && !obstacles) {
        if (candidates) env->ReleaseDoubleArrayElements(jCandidateXyz, candidates, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice home-sampler: pin obstacles");
        return -1;
    }

    ve::HomeTargetInputs in{};
    in.candidate_xyz = reinterpret_cast<const double*>(candidates);
    in.candidate_count = static_cast<std::size_t>(candidateCount);
    in.self_x = selfX;
    in.self_y = selfY;
    in.self_z = selfZ;
    in.home_x = homeX;
    in.home_y = homeY;
    in.home_z = homeZ;
    in.obstacle_aabbs = reinterpret_cast<const double*>(obstacles);
    in.obstacle_count = static_cast<std::size_t>(obstacleCount);
    in.preferred_distance = preferredDistance;
    in.min_clearance = minClearance;

    const ve::HomeTargetResult result = ve::sample_home_target(in);

    if (obstacles) env->ReleaseDoubleArrayElements(jObstacleAabbs, obstacles, JNI_ABORT);
    if (candidates) env->ReleaseDoubleArrayElements(jCandidateXyz, candidates, JNI_ABORT);
    return static_cast<jint>(result.candidate_index);
}

} // extern "C"
