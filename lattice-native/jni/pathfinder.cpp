#include <jni.h>

#include <cstddef>
#include <cstdint>

#include "jni_helper.hpp"
#include "world/entity/pathfinder.hpp"

namespace pf = lattice::world::entity;

namespace {

constexpr jint kPathfinderAbiVersion = 7;
constexpr int kResultHeaderInts = 3;

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativePathfinder_nativeAbiVersion(
        JNIEnv* /*env*/, jclass /*cls*/) {
    return kPathfinderAbiVersion;
}

JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativePathfinder_nativeFindPath(
        JNIEnv* env, jclass /*cls*/,
        jbyteArray jPathTypes,
        jfloatArray jFloorLevels,
        jint regionMinX, jint regionMinY, jint regionMinZ,
        jint regionSizeX, jint regionSizeY, jint regionSizeZ,
        jint startX, jint startY, jint startZ,
        jintArray jTargetX, jintArray jTargetY, jintArray jTargetZ,
        jint targetCount,
        jfloat maxRange, jint maxVisitedNodes, jint reachRange,
        jint entityWidth, jint entityHeight, jfloat maxUpStep,
        jint maxFallDistance, jfloatArray jPathfindingMalus,
        jfloat mobJumpHeight, jfloat bbWidth,
        jboolean canWalkOverFences, jboolean mobsIgnoreRails,
        jboolean canFloat, jboolean isAmphibious,
        jint levelMinY,
        jintArray jOutPath) {
    if (!jPathTypes || !jTargetX || !jTargetY || !jTargetZ || !jPathfindingMalus || !jOutPath) {
        lattice::jni::throw_illegal_arg(env, "lattice pathfinder: null array");
        return 0L;
    }
    if (regionSizeX <= 0 || regionSizeY <= 0 || regionSizeZ <= 0
            || maxVisitedNodes <= 0 || maxRange <= 0.0F) {
        lattice::jni::throw_illegal_arg(env, "lattice pathfinder: invalid dimensions/config");
        return 0L;
    }
    if (targetCount <= 0 || env->GetArrayLength(jTargetX) < targetCount
            || env->GetArrayLength(jTargetY) < targetCount
            || env->GetArrayLength(jTargetZ) < targetCount) {
        lattice::jni::throw_illegal_arg(env, "lattice pathfinder: target arrays too short");
        return 0L;
    }
    const long long volume = static_cast<long long>(regionSizeX)
        * static_cast<long long>(regionSizeY)
        * static_cast<long long>(regionSizeZ);
    if (volume <= 0 || volume > env->GetArrayLength(jPathTypes)) {
        lattice::jni::throw_illegal_arg(env, "lattice pathfinder: path type array too short");
        return 0L;
    }
    const jsize malusCount = env->GetArrayLength(jPathfindingMalus);
    if (malusCount <= 0) {
        lattice::jni::throw_illegal_arg(env, "lattice pathfinder: empty malus array");
        return 0L;
    }
    if (env->GetArrayLength(jOutPath) < 3 + maxVisitedNodes * 3) {
        lattice::jni::throw_illegal_arg(env, "lattice pathfinder: output array too short");
        return 0L;
    }
    // floorLevels is optional: a null array means "assume integer cell Y",
    // which matches an empty collision shape. When present it must cover the
    // same cells as pathTypes since both are indexed identically.
    if (jFloorLevels && env->GetArrayLength(jFloorLevels) < volume) {
        lattice::jni::throw_illegal_arg(env, "lattice pathfinder: floor level array too short");
        return 0L;
    }

    jbyte* pathTypes = env->GetByteArrayElements(jPathTypes, nullptr);
    if (!pathTypes) {
        lattice::jni::throw_oom(env, "lattice pathfinder: pin path types");
        return 0L;
    }
    jint* targetX = env->GetIntArrayElements(jTargetX, nullptr);
    if (!targetX) {
        env->ReleaseByteArrayElements(jPathTypes, pathTypes, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice pathfinder: pin target x");
        return 0L;
    }
    jint* targetY = env->GetIntArrayElements(jTargetY, nullptr);
    if (!targetY) {
        env->ReleaseIntArrayElements(jTargetX, targetX, JNI_ABORT);
        env->ReleaseByteArrayElements(jPathTypes, pathTypes, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice pathfinder: pin target y");
        return 0L;
    }
    jint* targetZ = env->GetIntArrayElements(jTargetZ, nullptr);
    if (!targetZ) {
        env->ReleaseIntArrayElements(jTargetY, targetY, JNI_ABORT);
        env->ReleaseIntArrayElements(jTargetX, targetX, JNI_ABORT);
        env->ReleaseByteArrayElements(jPathTypes, pathTypes, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice pathfinder: pin target z");
        return 0L;
    }
    jfloat* malus = env->GetFloatArrayElements(jPathfindingMalus, nullptr);
    if (!malus) {
        env->ReleaseIntArrayElements(jTargetZ, targetZ, JNI_ABORT);
        env->ReleaseIntArrayElements(jTargetY, targetY, JNI_ABORT);
        env->ReleaseIntArrayElements(jTargetX, targetX, JNI_ABORT);
        env->ReleaseByteArrayElements(jPathTypes, pathTypes, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice pathfinder: pin malus");
        return 0L;
    }

    // Optional: a null array means "assume integer cell Y", so a failed pin is
    // only an error when the caller actually supplied the array.
    jfloat* floorLevels = nullptr;
    if (jFloorLevels) {
        floorLevels = env->GetFloatArrayElements(jFloorLevels, nullptr);
        if (!floorLevels) {
            env->ReleaseFloatArrayElements(jPathfindingMalus, malus, JNI_ABORT);
            env->ReleaseIntArrayElements(jTargetZ, targetZ, JNI_ABORT);
            env->ReleaseIntArrayElements(jTargetY, targetY, JNI_ABORT);
            env->ReleaseIntArrayElements(jTargetX, targetX, JNI_ABORT);
            env->ReleaseByteArrayElements(jPathTypes, pathTypes, JNI_ABORT);
            lattice::jni::throw_oom(env, "lattice pathfinder: pin floor levels");
            return 0L;
        }
    }

    jint* out = env->GetIntArrayElements(jOutPath, nullptr);
    if (!out) {
        if (floorLevels) env->ReleaseFloatArrayElements(jFloorLevels, floorLevels, JNI_ABORT);
        env->ReleaseFloatArrayElements(jPathfindingMalus, malus, JNI_ABORT);
        env->ReleaseIntArrayElements(jTargetZ, targetZ, JNI_ABORT);
        env->ReleaseIntArrayElements(jTargetY, targetY, JNI_ABORT);
        env->ReleaseIntArrayElements(jTargetX, targetX, JNI_ABORT);
        env->ReleaseByteArrayElements(jPathTypes, pathTypes, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice pathfinder: pin output");
        return 0L;
    }

    pf::PathfinderInputs inputs{};
    inputs.path_types = reinterpret_cast<const std::int8_t*>(pathTypes);
    inputs.floor_levels = reinterpret_cast<const float*>(floorLevels);
    inputs.region_min_x = regionMinX;
    inputs.region_min_y = regionMinY;
    inputs.region_min_z = regionMinZ;
    inputs.region_size_x = regionSizeX;
    inputs.region_size_y = regionSizeY;
    inputs.region_size_z = regionSizeZ;
    inputs.start_x = startX;
    inputs.start_y = startY;
    inputs.start_z = startZ;
    inputs.target_x = reinterpret_cast<const int*>(targetX);
    inputs.target_y = reinterpret_cast<const int*>(targetY);
    inputs.target_z = reinterpret_cast<const int*>(targetZ);
    inputs.target_count = targetCount;
    inputs.config.max_range = maxRange;
    inputs.config.max_visited_nodes = maxVisitedNodes;
    inputs.config.reach_range = reachRange;
    inputs.config.fudge = 1.5F;
    inputs.entity_width = entityWidth;
    inputs.entity_height = entityHeight;
    inputs.max_up_step = maxUpStep;
    inputs.max_fall_distance = maxFallDistance;
    inputs.pathfinding_malus = reinterpret_cast<const float*>(malus);
    inputs.pathfinding_malus_count = malusCount;
    inputs.mob_jump_height = mobJumpHeight;
    inputs.bb_width = bbWidth;
    inputs.can_walk_over_fences = canWalkOverFences != JNI_FALSE;
    inputs.mobs_ignore_rails = mobsIgnoreRails != JNI_FALSE;
    inputs.can_float = canFloat != JNI_FALSE;
    inputs.is_amphibious = isAmphibious != JNI_FALSE;
    inputs.level_min_y = levelMinY;

    pf::PathfinderOutput output{};
    output.coords = reinterpret_cast<int*>(out + kResultHeaderInts);
    output.capacity_nodes = maxVisitedNodes;

    thread_local pf::PathfinderScratch scratch{};
    const bool ok = pf::find_path_into(inputs, output, scratch);

    env->ReleaseIntArrayElements(jOutPath, out, 0);
    if (floorLevels) env->ReleaseFloatArrayElements(jFloorLevels, floorLevels, JNI_ABORT);
    env->ReleaseFloatArrayElements(jPathfindingMalus, malus, JNI_ABORT);
    env->ReleaseIntArrayElements(jTargetZ, targetZ, JNI_ABORT);
    env->ReleaseIntArrayElements(jTargetY, targetY, JNI_ABORT);
    env->ReleaseIntArrayElements(jTargetX, targetX, JNI_ABORT);
    env->ReleaseByteArrayElements(jPathTypes, pathTypes, JNI_ABORT);

    if (!ok) return 0L;

    const std::uint64_t length = static_cast<std::uint64_t>(output.path_length);
    const std::uint64_t reached = output.reached_target ? 1ULL : 0ULL;
    const std::uint64_t target = static_cast<std::uint32_t>(output.target_index);
    return static_cast<jlong>((target << 33) | (reached << 32) | length);
}

} // extern "C"
