#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <limits>

#include "jni_helper.hpp"
#include "world/entity/los.hpp"

namespace ve = lattice::world::entity;

namespace {

[[nodiscard]] bool validate_region(JNIEnv* env,
                                   jbyteArray solid_mask,
                                   jint size_x,
                                   jint size_y,
                                   jint size_z) noexcept {
    if (!solid_mask) {
        lattice::jni::throw_illegal_arg(env, "lattice los: null solid mask");
        return false;
    }
    if (size_x <= 0 || size_y <= 0 || size_z <= 0) {
        lattice::jni::throw_illegal_arg(env, "lattice los: invalid region size");
        return false;
    }
    const std::size_t needed = static_cast<std::size_t>(size_x) *
                               static_cast<std::size_t>(size_y) *
                               static_cast<std::size_t>(size_z);
    if (needed > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        lattice::jni::throw_illegal_arg(env, "lattice los: region too large");
        return false;
    }
    if (env->GetArrayLength(solid_mask) < static_cast<jsize>(needed)) {
        lattice::jni::throw_illegal_arg(env, "lattice los: solid mask too short");
        return false;
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_latticemc_lattice_nativelib_NativeLineOfSight_nativeCheckSingle(
        JNIEnv* env, jclass /*cls*/,
        jdouble from_x, jdouble from_y, jdouble from_z,
        jdouble to_x, jdouble to_y, jdouble to_z,
        jbyteArray solid_mask,
        jint region_min_x, jint region_min_y, jint region_min_z,
        jint region_size_x, jint region_size_y, jint region_size_z) {
    if (!validate_region(env, solid_mask, region_size_x, region_size_y, region_size_z)) {
        return JNI_FALSE;
    }

    lattice::jni::CriticalByteArray mask{env, solid_mask};
    if (!mask) {
        lattice::jni::throw_oom(env, "lattice los: pin solid mask");
        return JNI_FALSE;
    }

    const double from_x_arr[1] = {from_x};
    const double from_y_arr[1] = {from_y};
    const double from_z_arr[1] = {from_z};
    const double to_x_arr[1] = {to_x};
    const double to_y_arr[1] = {to_y};
    const double to_z_arr[1] = {to_z};
    const ve::LosInputs inputs{
        from_x_arr, from_y_arr, from_z_arr,
        to_x_arr, to_y_arr, to_z_arr,
        1,
        reinterpret_cast<const std::int8_t*>(mask.data()),
        region_min_x, region_min_y, region_min_z,
        region_size_x, region_size_y, region_size_z,
    };
    const bool result = ve::check_line_of_sight(inputs, 0).has_line_of_sight;
    mask.release_ro();
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeLineOfSight_nativeCheckBatch(
        JNIEnv* env, jclass /*cls*/,
        jdoubleArray from_x, jdoubleArray from_y, jdoubleArray from_z,
        jdoubleArray to_x, jdoubleArray to_y, jdoubleArray to_z,
        jbyteArray solid_mask,
        jint region_min_x, jint region_min_y, jint region_min_z,
        jint region_size_x, jint region_size_y, jint region_size_z,
        jbooleanArray results) {
    if (!results) {
        lattice::jni::throw_illegal_arg(env, "lattice los: null results");
        return;
    }
    if (!from_x || !from_y || !from_z || !to_x || !to_y || !to_z) {
        lattice::jni::throw_illegal_arg(env, "lattice los: null coordinate array");
        return;
    }
    if (!validate_region(env, solid_mask, region_size_x, region_size_y, region_size_z)) {
        return;
    }

    const jsize count = env->GetArrayLength(results);
    if (env->GetArrayLength(from_x) < count || env->GetArrayLength(from_y) < count || env->GetArrayLength(from_z) < count ||
        env->GetArrayLength(to_x) < count || env->GetArrayLength(to_y) < count || env->GetArrayLength(to_z) < count) {
        lattice::jni::throw_illegal_arg(env, "lattice los: coordinate array too short");
        return;
    }

    lattice::jni::CriticalDoubleArray fx{env, from_x};
    lattice::jni::CriticalDoubleArray fy{env, from_y};
    lattice::jni::CriticalDoubleArray fz{env, from_z};
    lattice::jni::CriticalDoubleArray tx{env, to_x};
    lattice::jni::CriticalDoubleArray ty{env, to_y};
    lattice::jni::CriticalDoubleArray tz{env, to_z};
    lattice::jni::CriticalByteArray mask{env, solid_mask};
    lattice::jni::CriticalBooleanArray out{env, results};
    if (!fx || !fy || !fz || !tx || !ty || !tz || !mask || !out) {
        lattice::jni::throw_oom(env, "lattice los: pin arrays");
        return;
    }

    const ve::LosInputs inputs{
        fx.data(), fy.data(), fz.data(),
        tx.data(), ty.data(), tz.data(),
        static_cast<std::size_t>(count),
        reinterpret_cast<const std::int8_t*>(mask.data()),
        region_min_x, region_min_y, region_min_z,
        region_size_x, region_size_y, region_size_z,
    };
    for (jsize i = 0; i < count; ++i) {
        out.data()[i] = ve::check_line_of_sight(inputs, static_cast<std::size_t>(i)).has_line_of_sight ? JNI_TRUE : JNI_FALSE;
    }

    mask.release_ro();
    tz.release_ro();
    ty.release_ro();
    tx.release_ro();
    fz.release_ro();
    fy.release_ro();
    fx.release_ro();
}

} // extern "C"
