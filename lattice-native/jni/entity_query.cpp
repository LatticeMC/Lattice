#include <jni.h>

#include <cstddef>
#include <cstdint>

#include "jni_helper.hpp"
#include "world/entity/entity_query.hpp"

namespace ve = lattice::world::entity;

extern "C" JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeEntityQuery_nativeQuery(
        JNIEnv* env, jclass /*cls*/,
        jdouble queryMinX, jdouble queryMinY, jdouble queryMinZ,
        jdouble queryMaxX, jdouble queryMaxY, jdouble queryMaxZ,
        jintArray jEntityIds,
        jintArray jEntityTypeIds,
        jdoubleArray jEntityPositions,
        jdoubleArray jEntityAabbs,
        jbooleanArray jEntityAlive,
        jbooleanArray jEntitySpectator,
        jintArray jAllowedTypeIds,
        jint predicateKind,
        jint excludedEntityId,
        jboolean sortByDistance,
        jint maxResults,
        jdouble refX, jdouble refY, jdouble refZ,
        jintArray jOutputIds,
        jdoubleArray jOutputDistances) {
    if (!jOutputIds) {
        lattice::jni::throw_illegal_arg(env, "lattice entity-query: null output ids");
        return 0;
    }
    if (maxResults < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice entity-query: negative max results");
        return 0;
    }
    if (!jEntityIds || !jEntityTypeIds || !jEntityPositions || !jEntityAabbs ||
        !jEntityAlive || !jEntitySpectator) {
        lattice::jni::throw_illegal_arg(env, "lattice entity-query: null entity arrays");
        return 0;
    }

    const jsize entity_count = env->GetArrayLength(jEntityIds);
    if (entity_count < 0) return 0;
    const auto ec = static_cast<std::size_t>(entity_count);
    if (env->GetArrayLength(jEntityTypeIds) < entity_count ||
        env->GetArrayLength(jEntityAlive) < entity_count ||
        env->GetArrayLength(jEntitySpectator) < entity_count ||
        env->GetArrayLength(jEntityPositions) < static_cast<jsize>(ec * ve::kEntityPositionStride) ||
        env->GetArrayLength(jEntityAabbs) < static_cast<jsize>(ec * ve::kEntityAabbStride)) {
        lattice::jni::throw_illegal_arg(env, "lattice entity-query: entity arrays too short");
        return 0;
    }

    const jsize output_capacity_jsize = env->GetArrayLength(jOutputIds);
    const jsize needed_distances = sortByDistance == JNI_TRUE ? output_capacity_jsize * 2 : output_capacity_jsize;
    if (jOutputDistances && env->GetArrayLength(jOutputDistances) < needed_distances) {
        lattice::jni::throw_illegal_arg(env, "lattice entity-query: distances too short");
        return 0;
    }
    const auto output_capacity = static_cast<std::size_t>(output_capacity_jsize);
    if (output_capacity == 0 || ec == 0) return 0;

    const jsize allowed_count_jsize = jAllowedTypeIds ? env->GetArrayLength(jAllowedTypeIds) : 0;
    const auto allowed_count = static_cast<std::size_t>(allowed_count_jsize);

    lattice::jni::CriticalIntArray entity_ids{env, jEntityIds};
    lattice::jni::CriticalIntArray entity_type_ids{env, jEntityTypeIds};
    lattice::jni::CriticalDoubleArray entity_positions{env, jEntityPositions};
    lattice::jni::CriticalDoubleArray entity_aabbs{env, jEntityAabbs};
    lattice::jni::CriticalBooleanArray entity_alive{env, jEntityAlive};
    lattice::jni::CriticalBooleanArray entity_spectator{env, jEntitySpectator};
    lattice::jni::CriticalIntArray allowed_type_ids{env, jAllowedTypeIds};
    lattice::jni::CriticalIntArray output_ids{env, jOutputIds};
    lattice::jni::CriticalDoubleArray output_distances{env, jOutputDistances};

    if (!entity_ids || !entity_type_ids || !entity_positions || !entity_aabbs ||
        !entity_alive || !entity_spectator || !output_ids ||
        (jAllowedTypeIds && !allowed_type_ids) || (jOutputDistances && !output_distances)) {
        lattice::jni::throw_oom(env, "lattice entity-query: pin arrays");
        return 0;
    }

    const ve::EntityQueryInputs inputs{
        queryMinX, queryMinY, queryMinZ,
        queryMaxX, queryMaxY, queryMaxZ,
        reinterpret_cast<const int*>(entity_ids.data()),
        reinterpret_cast<const int*>(entity_type_ids.data()),
        reinterpret_cast<const double*>(entity_positions.data()),
        reinterpret_cast<const double*>(entity_aabbs.data()),
        reinterpret_cast<const std::uint8_t*>(entity_alive.data()),
        reinterpret_cast<const std::uint8_t*>(entity_spectator.data()),
        ec,
        jAllowedTypeIds ? reinterpret_cast<const int*>(allowed_type_ids.data()) : nullptr,
        allowed_count,
        static_cast<ve::EntityPredicateKind>(predicateKind),
        excludedEntityId,
        sortByDistance == JNI_TRUE,
        static_cast<std::size_t>(maxResults),
        refX, refY, refZ,
    };

    const std::size_t count = ve::query_entities(
        inputs,
        reinterpret_cast<int*>(output_ids.data()),
        jOutputDistances ? reinterpret_cast<double*>(output_distances.data()) : nullptr,
        output_capacity);

    entity_ids.release_ro();
    entity_type_ids.release_ro();
    entity_positions.release_ro();
    entity_aabbs.release_ro();
    entity_alive.release_ro();
    entity_spectator.release_ro();
    if (jAllowedTypeIds) allowed_type_ids.release_ro();
    return static_cast<jint>(count);
}
