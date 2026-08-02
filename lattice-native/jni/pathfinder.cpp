#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <unordered_map>

#include "jni_helper.hpp"
#include "world/entity/pathfinder.hpp"

namespace pf = lattice::world::entity;

namespace {

constexpr jint kPathfinderAbiVersion = 10;
constexpr int kResultHeaderInts = 3;
/// Bound on the shared invalidation log. A thread that falls further behind than
/// this degrades to a full mirror clear, which is what every invalidation used to
/// do unconditionally.
constexpr std::size_t kMaxInvalidationLog = 4096;

/// Shared, per-world log of invalidated section keys.
///
/// The mirror is `thread_local`, so an invalidating thread cannot reach another
/// thread's copy directly. Previously each invalidation bumped a single per-world
/// generation and every reader responded by clearing *all* of its sections: one
/// block update anywhere in the world discarded the entire mirror, which on a live
/// server (farms, redstone, mob-placed blocks) keeps the hit rate near zero.
///
/// Now each invalidation appends the affected section key. A reader replays only
/// the entries it has not seen and erases just those sections.
struct MirrorInvalidationLog {
    std::mutex mutex;
    /// Generation of the entry that would be at `keys.front()`.
    std::uint64_t base_generation = 0;
    std::deque<std::uint64_t> keys;

    [[nodiscard]] std::uint64_t generation() const noexcept {
        return base_generation + keys.size();
    }
};

struct ThreadStateMirror {
    pf::PathfinderStateMirror mirror{};
    std::shared_ptr<MirrorInvalidationLog> log{};
    std::uint64_t generation = 0;
};

std::mutex g_state_mirror_mutex;
std::unordered_map<int, std::shared_ptr<MirrorInvalidationLog>> g_state_mirror_logs;
thread_local ThreadStateMirror g_state_mirror{};

std::shared_ptr<MirrorInvalidationLog> state_mirror_log(int world_key) {
    std::lock_guard lock(g_state_mirror_mutex);
    auto& log = g_state_mirror_logs[world_key];
    if (!log) log = std::make_shared<MirrorInvalidationLog>();
    return log;
}

/// Brings this thread's mirror up to date. Returns true if anything was dropped.
bool refresh_state_mirror(int world_key) {
    if (g_state_mirror.mirror.world_key != world_key || !g_state_mirror.log) {
        g_state_mirror.log = state_mirror_log(world_key);
    }
    if (g_state_mirror.mirror.world_key != world_key) {
        g_state_mirror.mirror.world_key = world_key;
        g_state_mirror.mirror.sections.clear();
        std::lock_guard lock(g_state_mirror.log->mutex);
        g_state_mirror.generation = g_state_mirror.log->generation();
        return true;
    }

    std::lock_guard lock(g_state_mirror.log->mutex);
    const MirrorInvalidationLog& log = *g_state_mirror.log;
    const std::uint64_t current = log.generation();
    if (g_state_mirror.generation == current) return false;
    if (g_state_mirror.generation < log.base_generation) {
        // Fell off the back of the log: no way to know which sections changed.
        g_state_mirror.mirror.sections.clear();
        g_state_mirror.generation = current;
        return true;
    }
    for (std::size_t i = static_cast<std::size_t>(g_state_mirror.generation - log.base_generation);
            i < log.keys.size(); ++i) {
        g_state_mirror.mirror.sections.erase(log.keys[i]);
    }
    g_state_mirror.generation = current;
    return true;
}

jlong encode_result(const pf::PathfinderOutput& output) {
    const std::uint64_t length = static_cast<std::uint64_t>(output.path_length);
    const std::uint64_t reached = output.reached_target ? 1ULL : 0ULL;
    const std::uint64_t target = static_cast<std::uint32_t>(output.target_index);
    return static_cast<jlong>((target << 33) | (reached << 32) | length);
}

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

JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativePathfinder_nativeFindPathFromStateSnapshot(
        JNIEnv* env, jclass /*cls*/,
        jintArray jStateCells, jbyteArray jDescriptorPathTypes, jfloatArray jDescriptorFloorHeights,
        jint descriptorCount,
        jint stateMinX, jint stateMinY, jint stateMinZ,
        jint stateSizeX, jint stateSizeY, jint stateSizeZ,
        jint regionMinX, jint regionMinY, jint regionMinZ,
        jint regionSizeX, jint regionSizeY, jint regionSizeZ,
        jint startX, jint startY, jint startZ,
        jintArray jTargetX, jintArray jTargetY, jintArray jTargetZ, jint targetCount,
        jfloat maxRange, jint maxVisitedNodes, jint reachRange,
        jint entityWidth, jint entityHeight, jfloat maxUpStep,
        jint maxFallDistance, jfloatArray jPathfindingMalus,
        jfloat mobJumpHeight, jfloat bbWidth,
        jboolean canPassDoors, jboolean canOpenDoors,
        jint mobBlockX, jint mobBlockY, jint mobBlockZ,
        jboolean canWalkOverFences, jboolean mobsIgnoreRails,
        jboolean canFloat, jboolean isAmphibious, jint levelMinY,
        jint worldKey,
        jintArray jOutPath) {
    if (!jStateCells || !jDescriptorPathTypes || !jDescriptorFloorHeights
            || !jTargetX || !jTargetY || !jTargetZ || !jPathfindingMalus || !jOutPath) {
        lattice::jni::throw_illegal_arg(env, "lattice pathfinder: null state snapshot array");
        return 0L;
    }
    if (descriptorCount <= 0 || stateSizeX <= 0 || stateSizeY <= 0 || stateSizeZ <= 0
            || regionSizeX <= 0 || regionSizeY <= 0 || regionSizeZ <= 0
            || entityWidth <= 0 || entityHeight <= 0 || maxVisitedNodes <= 0 || maxRange <= 0.0F) {
        lattice::jni::throw_illegal_arg(env, "lattice pathfinder: invalid state snapshot dimensions/config");
        return 0L;
    }
    const long long stateVolume = static_cast<long long>(stateSizeX) * stateSizeY * stateSizeZ;
    if (stateVolume <= 0 || stateVolume > env->GetArrayLength(jStateCells)
            || descriptorCount > env->GetArrayLength(jDescriptorPathTypes)
            || descriptorCount > env->GetArrayLength(jDescriptorFloorHeights)) {
        lattice::jni::throw_illegal_arg(env, "lattice pathfinder: state snapshot array too short");
        return 0L;
    }
    if (targetCount <= 0 || env->GetArrayLength(jTargetX) < targetCount
            || env->GetArrayLength(jTargetY) < targetCount
            || env->GetArrayLength(jTargetZ) < targetCount
            || env->GetArrayLength(jPathfindingMalus) <= 0
            || env->GetArrayLength(jOutPath) < 3 + maxVisitedNodes * 3) {
        lattice::jni::throw_illegal_arg(env, "lattice pathfinder: target, malus, or output array too short");
        return 0L;
    }

    jint* stateCells = nullptr;
    jbyte* descriptorPathTypes = nullptr;
    jfloat* descriptorFloorHeights = nullptr;
    jint* targetX = nullptr;
    jint* targetY = nullptr;
    jint* targetZ = nullptr;
    jfloat* malus = nullptr;
    jint* out = nullptr;
    auto release_inputs = [&]() {
        if (malus) env->ReleaseFloatArrayElements(jPathfindingMalus, malus, JNI_ABORT);
        if (targetZ) env->ReleaseIntArrayElements(jTargetZ, targetZ, JNI_ABORT);
        if (targetY) env->ReleaseIntArrayElements(jTargetY, targetY, JNI_ABORT);
        if (targetX) env->ReleaseIntArrayElements(jTargetX, targetX, JNI_ABORT);
        if (descriptorFloorHeights) env->ReleaseFloatArrayElements(jDescriptorFloorHeights, descriptorFloorHeights, JNI_ABORT);
        if (descriptorPathTypes) env->ReleaseByteArrayElements(jDescriptorPathTypes, descriptorPathTypes, JNI_ABORT);
        if (stateCells) env->ReleaseIntArrayElements(jStateCells, stateCells, JNI_ABORT);
    };
    stateCells = env->GetIntArrayElements(jStateCells, nullptr);
    descriptorPathTypes = env->GetByteArrayElements(jDescriptorPathTypes, nullptr);
    descriptorFloorHeights = env->GetFloatArrayElements(jDescriptorFloorHeights, nullptr);
    targetX = env->GetIntArrayElements(jTargetX, nullptr);
    targetY = env->GetIntArrayElements(jTargetY, nullptr);
    targetZ = env->GetIntArrayElements(jTargetZ, nullptr);
    malus = env->GetFloatArrayElements(jPathfindingMalus, nullptr);
    out = env->GetIntArrayElements(jOutPath, nullptr);
    if (!stateCells || !descriptorPathTypes || !descriptorFloorHeights || !targetX || !targetY || !targetZ || !malus || !out) {
        if (out) env->ReleaseIntArrayElements(jOutPath, out, JNI_ABORT);
        release_inputs();
        lattice::jni::throw_oom(env, "lattice pathfinder: pin state snapshot");
        return 0L;
    }

    pf::PathfinderInputs inputs{};
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
    inputs.config = pf::PathfinderConfig{maxRange, maxVisitedNodes, reachRange, 1.5F};
    inputs.entity_width = entityWidth;
    inputs.entity_height = entityHeight;
    inputs.max_up_step = maxUpStep;
    inputs.max_fall_distance = maxFallDistance;
    inputs.pathfinding_malus = reinterpret_cast<const float*>(malus);
    inputs.pathfinding_malus_count = env->GetArrayLength(jPathfindingMalus);
    inputs.mob_jump_height = mobJumpHeight;
    inputs.bb_width = bbWidth;
    inputs.can_pass_doors = canPassDoors != JNI_FALSE;
    inputs.can_open_doors = canOpenDoors != JNI_FALSE;
    inputs.mob_block_x = mobBlockX;
    inputs.mob_block_y = mobBlockY;
    inputs.mob_block_z = mobBlockZ;
    inputs.can_walk_over_fences = canWalkOverFences != JNI_FALSE;
    inputs.mobs_ignore_rails = mobsIgnoreRails != JNI_FALSE;
    inputs.can_float = canFloat != JNI_FALSE;
    inputs.is_amphibious = isAmphibious != JNI_FALSE;
    inputs.level_min_y = levelMinY;

    pf::PathfinderStateSnapshot snapshot{};
    snapshot.cells = reinterpret_cast<const int*>(stateCells);
    snapshot.raw_path_types = reinterpret_cast<const std::int8_t*>(descriptorPathTypes);
    snapshot.floor_heights = reinterpret_cast<const float*>(descriptorFloorHeights);
    snapshot.descriptor_count = descriptorCount;
    snapshot.min_x = stateMinX;
    snapshot.min_y = stateMinY;
    snapshot.min_z = stateMinZ;
    snapshot.size_x = stateSizeX;
    snapshot.size_y = stateSizeY;
    snapshot.size_z = stateSizeZ;

    pf::PathfinderOutput output{};
    output.coords = reinterpret_cast<int*>(out + kResultHeaderInts);
    output.capacity_nodes = maxVisitedNodes;
    thread_local pf::PathfinderScratch scratch{};
    (void)refresh_state_mirror(worldKey);
    pf::store_pathfinder_state_snapshot(g_state_mirror.mirror, worldKey, snapshot);
    // Still eager here, even though the mirror now holds this exact box.
    //
    // Routing the upload path through find_path_from_state_mirror_into (LazyPathGrid)
    // measured ~5.5ms -> ~0.4ms, so it is a live candidate. It is not enabled yet
    // only because it cannot currently be validated: a verify=true run over it gave
    // verifyMismatches=3, but the eager path measured 4 on the same workload and a
    // whole-mirror-clear variant also 4, i.e. all three sit in the ~1-2% mismatch
    // band already documented for 2026-07-30. The lazy upload was therefore never
    // shown to be worse than this; switch it on once that pre-existing parity gap is
    // diagnosed and a verify run can actually distinguish the two.
    const bool ok = pf::find_path_from_state_snapshot_into(inputs, snapshot, output, scratch);

    env->ReleaseIntArrayElements(jOutPath, out, 0);
    release_inputs();
    if (!ok) return 0L;
    return encode_result(output);
}

JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativePathfinder_nativeFindPathFromStateMirror(
        JNIEnv* env, jclass /*cls*/, jint worldKey,
        jint regionMinX, jint regionMinY, jint regionMinZ,
        jint regionSizeX, jint regionSizeY, jint regionSizeZ,
        jint startX, jint startY, jint startZ,
        jintArray jTargetX, jintArray jTargetY, jintArray jTargetZ, jint targetCount,
        jfloat maxRange, jint maxVisitedNodes, jint reachRange,
        jint entityWidth, jint entityHeight, jfloat maxUpStep,
        jint maxFallDistance, jfloatArray jPathfindingMalus,
        jfloat mobJumpHeight, jfloat bbWidth,
        jboolean canPassDoors, jboolean canOpenDoors,
        jint mobBlockX, jint mobBlockY, jint mobBlockZ,
        jboolean canWalkOverFences, jboolean mobsIgnoreRails,
        jboolean canFloat, jboolean isAmphibious, jint levelMinY,
        jintArray jOutPath) {
    if (!jTargetX || !jTargetY || !jTargetZ || !jPathfindingMalus || !jOutPath) {
        lattice::jni::throw_illegal_arg(env, "lattice pathfinder: null state mirror array");
        return 0L;
    }
    if (regionSizeX <= 0 || regionSizeY <= 0 || regionSizeZ <= 0
            || entityWidth <= 0 || entityHeight <= 0 || maxVisitedNodes <= 0 || maxRange <= 0.0F
            || targetCount <= 0 || env->GetArrayLength(jTargetX) < targetCount
            || env->GetArrayLength(jTargetY) < targetCount || env->GetArrayLength(jTargetZ) < targetCount
            || env->GetArrayLength(jPathfindingMalus) <= 0
            || env->GetArrayLength(jOutPath) < 3 + maxVisitedNodes * 3) {
        lattice::jni::throw_illegal_arg(env, "lattice pathfinder: invalid state mirror dimensions/config");
        return 0L;
    }

    jint* targetX = env->GetIntArrayElements(jTargetX, nullptr);
    jint* targetY = env->GetIntArrayElements(jTargetY, nullptr);
    jint* targetZ = env->GetIntArrayElements(jTargetZ, nullptr);
    jfloat* malus = env->GetFloatArrayElements(jPathfindingMalus, nullptr);
    // This is a short, JVM-free region: use Critical access only for the large,
    // reusable output buffer, where an ordinary JNI access may copy every slot.
    // Nothing between acquisition and release may call back into JNI.
    jint* out = static_cast<jint*>(env->GetPrimitiveArrayCritical(jOutPath, nullptr));
    if (!targetX || !targetY || !targetZ || !malus || !out) {
        if (out) env->ReleasePrimitiveArrayCritical(jOutPath, out, JNI_ABORT);
        if (malus) env->ReleaseFloatArrayElements(jPathfindingMalus, malus, JNI_ABORT);
        if (targetZ) env->ReleaseIntArrayElements(jTargetZ, targetZ, JNI_ABORT);
        if (targetY) env->ReleaseIntArrayElements(jTargetY, targetY, JNI_ABORT);
        if (targetX) env->ReleaseIntArrayElements(jTargetX, targetX, JNI_ABORT);
        lattice::jni::throw_oom(env, "lattice pathfinder: pin state mirror");
        return 0L;
    }

    pf::PathfinderInputs inputs{};
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
    inputs.config = pf::PathfinderConfig{maxRange, maxVisitedNodes, reachRange, 1.5F};
    inputs.entity_width = entityWidth;
    inputs.entity_height = entityHeight;
    inputs.max_up_step = maxUpStep;
    inputs.max_fall_distance = maxFallDistance;
    inputs.pathfinding_malus = reinterpret_cast<const float*>(malus);
    inputs.pathfinding_malus_count = env->GetArrayLength(jPathfindingMalus);
    inputs.mob_jump_height = mobJumpHeight;
    inputs.bb_width = bbWidth;
    inputs.can_pass_doors = canPassDoors != JNI_FALSE;
    inputs.can_open_doors = canOpenDoors != JNI_FALSE;
    inputs.mob_block_x = mobBlockX;
    inputs.mob_block_y = mobBlockY;
    inputs.mob_block_z = mobBlockZ;
    inputs.can_walk_over_fences = canWalkOverFences != JNI_FALSE;
    inputs.mobs_ignore_rails = mobsIgnoreRails != JNI_FALSE;
    inputs.can_float = canFloat != JNI_FALSE;
    inputs.is_amphibious = isAmphibious != JNI_FALSE;
    inputs.level_min_y = levelMinY;

    pf::PathfinderOutput output{};
    output.coords = reinterpret_cast<int*>(out + kResultHeaderInts);
    output.capacity_nodes = maxVisitedNodes;
    thread_local pf::PathfinderScratch scratch{};
    (void)refresh_state_mirror(worldKey);
    const bool ok = pf::find_path_from_state_mirror_into(inputs, g_state_mirror.mirror, worldKey, output, scratch);
    out[0] = static_cast<jint>(scratch.nodes.size());

    env->ReleasePrimitiveArrayCritical(jOutPath, out, 0);
    env->ReleaseFloatArrayElements(jPathfindingMalus, malus, JNI_ABORT);
    env->ReleaseIntArrayElements(jTargetZ, targetZ, JNI_ABORT);
    env->ReleaseIntArrayElements(jTargetY, targetY, JNI_ABORT);
    env->ReleaseIntArrayElements(jTargetX, targetX, JNI_ABORT);
    return ok ? encode_result(output) : 0L;
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativePathfinder_nativeInvalidateStateMirror(
        JNIEnv* /*env*/, jclass /*cls*/, jint worldKey, jint x, jint y, jint z) {
    const auto log = state_mirror_log(worldKey);
    const std::uint64_t key = pf::pathfinder_mirror_section_key(x, y, z);
    std::lock_guard lock(log->mutex);
    // Every invalidation must append, even a repeat of the most recent key: a
    // reader may have consumed the previous entry and already re-uploaded that
    // section, so collapsing duplicates would leave it holding stale cells.
    log->keys.push_back(key);
    while (log->keys.size() > kMaxInvalidationLog) {
        log->keys.pop_front();
        ++log->base_generation;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_latticemc_lattice_nativelib_NativePathfinder_nativeStateMirrorCovers(
        JNIEnv* /*env*/, jclass /*cls*/, jint worldKey,
        jint minX, jint minY, jint minZ,
        jint sizeX, jint sizeY, jint sizeZ) {
    (void)refresh_state_mirror(worldKey);
    return pf::state_mirror_covers(g_state_mirror.mirror, worldKey, minX, minY, minZ, sizeX, sizeY, sizeZ)
        ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
