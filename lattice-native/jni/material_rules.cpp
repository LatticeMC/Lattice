// JNI bindings for NativeMaterialRules.
//
// Java class: com.latticemc.lattice.nativelib.NativeMaterialRules
//
// Two-phase API:
//   1. Build: addConditionXxx() / addRuleXxx() / setRootRule(). Each
//      returns a small int ref; callers compose the tree bottom-up.
//   2. Sample: evaluate(...) takes a flat 14-double sample context
//      and returns the matched block index (or -1 for no match).
//
// The sample-time call is hot — vanilla SurfaceBuilder evaluates the
// tree at every (x, y, z) of every chunk's surface region. The shape
// of evaluate() therefore avoids JNI overhead per condition: the
// caller fills the context (precomputed biome / stone-depth / noise
// values) once and the entire tree is walked in C++.

#include <jni.h>

#include <algorithm>
#include <new>
#include <vector>

#include "jni_helper.hpp"
#include "world/gen/surfacebuilder/material_rules.hpp"

namespace mr = lattice::world::gen::surfacebuilder;

namespace {

inline mr::Arena* arena_from(jlong h) noexcept {
    return reinterpret_cast<mr::Arena*>(h);
}

jint push_cond(mr::Arena* a, const mr::Condition& c) noexcept {
    a->conditions.push_back(c);
    return static_cast<jint>(a->conditions.size() - 1);
}

jint push_rule(mr::Arena* a, const mr::Rule& r) noexcept {
    a->rules.push_back(r);
    return static_cast<jint>(a->rules.size() - 1);
}

int append_ints(mr::Arena* a, const jint* xs, jsize n) noexcept {
    const int off = static_cast<int>(a->int_pool.size());
    for (jsize i = 0; i < n; ++i) a->int_pool.push_back(static_cast<int>(xs[i]));
    return off;
}

} // namespace

extern "C" {

// ---- Arena lifecycle ------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeCreate(
        JNIEnv* env, jclass /*cls*/) {
    auto* a = new (std::nothrow) mr::Arena{};
    if (!a) {
        lattice::jni::throw_oom(env, "lattice material-rules: arena alloc");
        return 0;
    }
    return reinterpret_cast<jlong>(a);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeDestroy(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    delete arena_from(handle);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeSetRootRule(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint rule_ref) {
    auto* a = arena_from(handle);
    if (!a) return;
    a->root_rule = rule_ref;
}

// ---- Condition builders ---------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondAlwaysTrue(
        JNIEnv*, jclass, jlong h) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kAlwaysTrue;
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondAboveY(
        JNIEnv*, jclass, jlong h, jint minYIncl) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kAboveY; c.i0 = minYIncl;
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondAboveYWithSurface(
        JNIEnv*, jclass, jlong h, jint minY, jint surfaceDepthAdjust) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kAboveYWithSurface;
    c.i0 = minY; c.i1 = surfaceDepthAdjust; c.i2 = 0;
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondAboveYWithStoneDepth(
        JNIEnv*, jclass, jlong h, jint minY, jint surfaceDepthMultiplier) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kAboveYWithSurface;
    c.i0 = minY; c.i1 = surfaceDepthMultiplier; c.i2 = 1;
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondStoneDepth(
        JNIEnv*, jclass, jlong h,
        jint offset, jboolean adjustSurfaceDepth, jint secondaryRange, jboolean ceiling) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kStoneDepth;
    c.i0 = offset;
    c.i1 = (adjustSurfaceDepth == JNI_TRUE) ? 1 : 0;
    c.i2 = secondaryRange;
    c.i3 = (ceiling == JNI_TRUE) ? 1 : 0;
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondVerticalGradient(
        JNIEnv*, jclass, jlong h, jint trueAtAndBelowY, jint falseAtAndAboveY,
        jlong seedLo, jlong seedHi) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kVerticalGradient;
    c.i0 = trueAtAndBelowY; c.i1 = falseAtAndAboveY;
    c.s0 = static_cast<std::int64_t>(seedLo);
    c.s1 = static_cast<std::int64_t>(seedHi);
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondNoiseThreshold(
        JNIEnv*, jclass, jlong h, jdouble lower, jdouble upper) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kNoiseThreshold;
    c.d0 = lower; c.d1 = upper;
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondNamedNoiseThreshold(
        JNIEnv*, jclass, jlong h, jint noiseSlot, jdouble lower, jdouble upper) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kNamedNoiseThreshold;
    c.i0 = noiseSlot;
    c.d0 = lower;
    c.d1 = upper;
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondSecondaryNoiseThreshold(
        JNIEnv*, jclass, jlong h, jdouble lower, jdouble upper) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kSecondaryNoiseThreshold;
    c.d0 = lower; c.d1 = upper;
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondHole(
        JNIEnv*, jclass, jlong h) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kHole;
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondSteepSlope(
        JNIEnv*, jclass, jlong h) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kSteepSlope;
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondTemperatureFrozen(
        JNIEnv*, jclass, jlong h) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kTemperatureFrozen;
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondBiomeIs(
        JNIEnv* env, jclass, jlong h, jintArray jBiomeIds) {
    auto* a = arena_from(h); if (!a) return -1;
    if (!jBiomeIds) return -1;
    const jsize n = env->GetArrayLength(jBiomeIds);
    jint* p = env->GetIntArrayElements(jBiomeIds, nullptr);
    if (!p) return -1;
    std::vector<jint> ids(p, p + n);
    env->ReleaseIntArrayElements(jBiomeIds, p, JNI_ABORT);

    std::sort(ids.begin(), ids.end());
    ids.erase(std::unique(ids.begin(), ids.end()), ids.end());
    const int off = append_ints(a, ids.data(), static_cast<jsize>(ids.size()));

    mr::Condition c{}; c.kind = mr::ConditionKind::kBiomeIs;
    c.i0 = 1;
    c.pool_off = off; c.pool_count = static_cast<int>(ids.size());
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondWater(
        JNIEnv*, jclass, jlong h, jint offset, jint surfaceDepthMultiplier, jboolean addStoneDepth) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kWater;
    c.i0 = offset;
    c.i1 = surfaceDepthMultiplier;
    c.i2 = (addStoneDepth == JNI_TRUE) ? 1 : 0;
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondAbovePreliminarySurface(
        JNIEnv*, jclass, jlong h) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kAbovePreliminarySurface;
    return push_cond(a, c);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddCondNot(
        JNIEnv*, jclass, jlong h, jint innerCond) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Condition c{}; c.kind = mr::ConditionKind::kNot; c.a = innerCond;
    return push_cond(a, c);
}

// ---- Rule builders --------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddRuleBlock(
        JNIEnv*, jclass, jlong h, jint blockIndex) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Rule r{}; r.kind = mr::RuleKind::kBlock; r.block_index = blockIndex;
    return push_rule(a, r);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddRuleConditional(
        JNIEnv*, jclass, jlong h, jint condRef, jint childRuleRef) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Rule r{}; r.kind = mr::RuleKind::kConditional;
    r.cond_ref = condRef; r.child_rule_ref = childRuleRef;
    return push_rule(a, r);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddRuleSequence(
        JNIEnv* env, jclass, jlong h, jintArray jChildRefs) {
    auto* a = arena_from(h); if (!a) return -1;
    if (!jChildRefs) return -1;
    const jsize n = env->GetArrayLength(jChildRefs);
    jint* p = env->GetIntArrayElements(jChildRefs, nullptr);
    if (!p) return -1;
    const int off = append_ints(a, p, n);
    env->ReleaseIntArrayElements(jChildRefs, p, JNI_ABORT);

    mr::Rule r{}; r.kind = mr::RuleKind::kSequence;
    r.pool_off = off; r.pool_count = static_cast<int>(n);
    return push_rule(a, r);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeAddRuleBandlands(
        JNIEnv*, jclass, jlong h) {
    auto* a = arena_from(h); if (!a) return -1;
    mr::Rule r{}; r.kind = mr::RuleKind::kBandlands;
    return push_rule(a, r);
}

// ---- Sample ---------------------------------------------------------------

/*
 * nativeEvaluate(handle, ctxIntArr, temperature, surfaceNoise, surfaceSecondaryNoise, namedNoiseArr, ctxBoolArr) → int
 *   ctxIntArr (length 10): x, y, z, surface_y, fluid_height,
 *                          stone_depth_floor, stone_depth_ceiling,
 *                          biome_id, surface_depth, min_surface_level
 *   temperature: temperature value (0.0 for frozen, 1.0 otherwise)
 *   surfaceNoise: surface noise value
 *   surfaceSecondaryNoise: surface secondary noise value
 *   namedNoiseArr: named noise values (may be null or empty)
 *   ctxBoolArr (length 2)  : hole_at_position, steep_slope (encoded as bytes)
 *
 * Returns matched block index or -1.
 */
JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeEvaluate(
        JNIEnv* env, jclass /*cls*/,
        jlong handle,
        jintArray jCtxInts, jdouble temperature, jdouble surfaceNoise, jdouble surfaceSecondaryNoise,
        jdoubleArray jNamedNoiseArr, jbyteArray jCtxBools) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    if (!jCtxInts || !jCtxBools) return -1;
    if (env->GetArrayLength(jCtxInts) < 10 || env->GetArrayLength(jCtxBools) < 2) {
        return -1;
    }

    jint*    ints    = env->GetIntArrayElements(jCtxInts, nullptr);
    jbyte*   bools   = env->GetByteArrayElements(jCtxBools, nullptr);
    jdouble* namedNoise = nullptr;
    jsize namedNoiseLen = 0;
    if (jNamedNoiseArr) {
        namedNoiseLen = env->GetArrayLength(jNamedNoiseArr);
        if (namedNoiseLen > 0) {
            namedNoise = env->GetDoubleArrayElements(jNamedNoiseArr, nullptr);
        }
    }
    if (!ints || !bools) {
        if (namedNoise) env->ReleaseDoubleArrayElements(jNamedNoiseArr, namedNoise, JNI_ABORT);
        if (bools)      env->ReleaseByteArrayElements(jCtxBools, bools, JNI_ABORT);
        if (ints)       env->ReleaseIntArrayElements(jCtxInts, ints, JNI_ABORT);
        return -1;
    }

    mr::SampleContext ctx{};
    ctx.x                       = ints[0];
    ctx.y                       = ints[1];
    ctx.z                       = ints[2];
    ctx.surface_y               = ints[3];
    ctx.fluid_height            = ints[4];
    ctx.stone_depth_floor       = ints[5];
    ctx.stone_depth_ceiling     = ints[6];
    ctx.biome_id                = ints[7];
    ctx.surface_depth           = ints[8];
    ctx.min_surface_level       = ints[9];
    ctx.temperature             = temperature;
    ctx.surface_noise           = surfaceNoise;
    ctx.surface_secondary_noise = surfaceSecondaryNoise;
    if (namedNoise && namedNoiseLen > 0) {
        ctx.noise_values = namedNoise;
        ctx.noise_value_count = static_cast<int>(namedNoiseLen);
    }
    ctx.hole_at_position        = (bools[0] != 0);
    ctx.steep_slope             = (bools[1] != 0);

    const int result = mr::evaluate(*a, ctx);

    if (namedNoise) env->ReleaseDoubleArrayElements(jNamedNoiseArr, namedNoise, JNI_ABORT);
    env->ReleaseByteArrayElements(jCtxBools, bools, JNI_ABORT);
    env->ReleaseIntArrayElements(jCtxInts, ints, JNI_ABORT);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_latticemc_lattice_nativelib_NativeMaterialRules_nativeEvaluateBatch(
        JNIEnv* env, jclass /*cls*/,
        jlong handle,
        jint count,
        jintArray jColumnCtx,
        jdouble surfaceNoise,
        jdouble surfaceSecondaryNoise,
        jdoubleArray jNamedNoiseArr,
        jbyteArray jColumnBools,
        jintArray jBlockData) {
    auto* a = arena_from(handle);
    if (!a) return nullptr;
    if (count <= 0) return env->NewIntArray(0);
    if (!jColumnCtx || !jColumnBools || !jBlockData) return nullptr;
    if (env->GetArrayLength(jColumnCtx) < 6 || env->GetArrayLength(jColumnBools) < 2 || env->GetArrayLength(jBlockData) < count * 5) {
        return nullptr;
    }

    jint* columnCtx = env->GetIntArrayElements(jColumnCtx, nullptr);
    jbyte* columnBools = env->GetByteArrayElements(jColumnBools, nullptr);
    jint* blockData = env->GetIntArrayElements(jBlockData, nullptr);
    jdouble* namedNoise = nullptr;
    jsize namedNoiseLen = 0;
    if (jNamedNoiseArr) {
        namedNoiseLen = env->GetArrayLength(jNamedNoiseArr);
        if (namedNoiseLen > 0) {
            namedNoise = env->GetDoubleArrayElements(jNamedNoiseArr, nullptr);
        }
    }
    if (!columnCtx || !columnBools || !blockData) {
        if (namedNoise) env->ReleaseDoubleArrayElements(jNamedNoiseArr, namedNoise, JNI_ABORT);
        if (blockData) env->ReleaseIntArrayElements(jBlockData, blockData, JNI_ABORT);
        if (columnBools) env->ReleaseByteArrayElements(jColumnBools, columnBools, JNI_ABORT);
        if (columnCtx) env->ReleaseIntArrayElements(jColumnCtx, columnCtx, JNI_ABORT);
        return nullptr;
    }

    jintArray result = env->NewIntArray(count);
    if (!result) {
        if (namedNoise) env->ReleaseDoubleArrayElements(jNamedNoiseArr, namedNoise, JNI_ABORT);
        env->ReleaseIntArrayElements(jBlockData, blockData, JNI_ABORT);
        env->ReleaseByteArrayElements(jColumnBools, columnBools, JNI_ABORT);
        env->ReleaseIntArrayElements(jColumnCtx, columnCtx, JNI_ABORT);
        return nullptr;
    }

    std::vector<jint> out(static_cast<std::size_t>(count));

    mr::SampleContext ctx{};
    ctx.x = columnCtx[0];
    ctx.z = columnCtx[1];
    ctx.surface_y = columnCtx[2];
    ctx.biome_id = columnCtx[3];
    ctx.surface_depth = columnCtx[4];
    ctx.min_surface_level = columnCtx[5];
    ctx.surface_noise = surfaceNoise;
    ctx.surface_secondary_noise = surfaceSecondaryNoise;
    ctx.hole_at_position = (columnBools[0] != 0);
    ctx.steep_slope = (columnBools[1] != 0);
    if (namedNoise && namedNoiseLen > 0) {
        ctx.noise_values = namedNoise;
        ctx.noise_value_count = static_cast<int>(namedNoiseLen);
    }

    for (jint i = 0; i < count; ++i) {
        const jint off = i * 5;
        ctx.y = blockData[off];
        ctx.fluid_height = blockData[off + 1];
        ctx.stone_depth_floor = blockData[off + 2];
        ctx.stone_depth_ceiling = blockData[off + 3];
        ctx.temperature = blockData[off + 4] != 0 ? 0.0 : 1.0;
        out[static_cast<std::size_t>(i)] = mr::evaluate(*a, ctx);
    }

    env->SetIntArrayRegion(result, 0, count, out.data());
    if (namedNoise) env->ReleaseDoubleArrayElements(jNamedNoiseArr, namedNoise, JNI_ABORT);
    env->ReleaseIntArrayElements(jBlockData, blockData, JNI_ABORT);
    env->ReleaseByteArrayElements(jColumnBools, columnBools, JNI_ABORT);
    env->ReleaseIntArrayElements(jColumnCtx, columnCtx, JNI_ABORT);
    return result;
}

} // extern "C"
