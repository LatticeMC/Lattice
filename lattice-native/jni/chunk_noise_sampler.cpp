// JNI bindings for NativeChunkNoiseSampler.
//
// Java class: com.latticemc.lattice.nativelib.NativeChunkNoiseSampler
//
// A ChunkNoiseSampler bundles up to 15 NativeDensityFunction arenas
// (one per NoiseRouter channel) and a per-channel cache state. The
// Java side wires it up by calling setChannel(channelIndex, dfHandle)
// for each channel that's present, then prepareCache() once. Sampling
// is then a handle + (x, y, z, cellX, cellZ) call.
//
// The sampler does NOT take ownership of the NativeDensityFunction
// arenas it references. Callers must keep the NativeDensityFunction
// instances alive for as long as the sampler is in use; destroying a
// referenced NativeDensityFunction before the sampler will dangle.

#include <jni.h>

#include <cstddef>
#include <new>
#include <vector>

#include "jni_helper.hpp"
#include "world/gen/chunknoise/chunk_noise_sampler.hpp"
#include "world/gen/densityfunction/density_function.hpp"

namespace cns = lattice::world::gen::chunknoise;
namespace df  = lattice::world::gen::densityfunction;

namespace {

inline cns::ChunkNoiseSampler* sampler_from(jlong h) noexcept {
    return reinterpret_cast<cns::ChunkNoiseSampler*>(h);
}

inline const df::NodeArena* arena_from(jlong h) noexcept {
    return reinterpret_cast<const df::NodeArena*>(h);
}

/// Set the appropriate router slot. The Java side passes the channel
/// index from `Channel` enum; we translate to the matching pointer
/// field. Returns true on success, false if the index is out of range.
bool assign_channel(cns::NoiseRouter& r, int channel,
                    const df::NodeArena* arena) noexcept {
    switch (static_cast<cns::Channel>(channel)) {
        case cns::Channel::kBarrierNoise:               r.barrier_noise = arena; return true;
        case cns::Channel::kFluidLevelFloodednessNoise: r.fluid_level_floodedness_noise = arena; return true;
        case cns::Channel::kFluidLevelSpreadNoise:      r.fluid_level_spread_noise = arena; return true;
        case cns::Channel::kLavaNoise:                  r.lava_noise = arena; return true;
        case cns::Channel::kTemperature:                r.temperature = arena; return true;
        case cns::Channel::kVegetation:                 r.vegetation = arena; return true;
        case cns::Channel::kContinents:                 r.continents = arena; return true;
        case cns::Channel::kErosion:                    r.erosion = arena; return true;
        case cns::Channel::kDepth:                      r.depth = arena; return true;
        case cns::Channel::kRidges:                     r.ridges = arena; return true;
        case cns::Channel::kPreliminarySurfaceLevel:    r.preliminary_surface_level = arena; return true;
        case cns::Channel::kFinalDensity:               r.final_density = arena; return true;
        case cns::Channel::kVeinToggle:                 r.vein_toggle = arena; return true;
        case cns::Channel::kVeinRidged:                 r.vein_ridged = arena; return true;
        case cns::Channel::kVeinGap:                    r.vein_gap = arena; return true;
        case cns::Channel::kCount:                      return false;
    }
    return false;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeCreate(
        JNIEnv* env, jclass /*cls*/) {
    auto* s = new (std::nothrow) cns::ChunkNoiseSampler{};
    if (!s) {
        lattice::jni::throw_oom(env, "lattice chunknoise: sampler alloc");
        return 0;
    }
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeDestroy(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    delete sampler_from(handle);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeSetChannel(
        JNIEnv* env, jclass /*cls*/,
        jlong handle, jint channel, jlong arenaHandle) {
    auto* s = sampler_from(handle);
    if (!s) {
        lattice::jni::throw_illegal_state(env, "lattice chunknoise: null sampler");
        return;
    }
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) {
        lattice::jni::throw_illegal_arg(env, "lattice chunknoise: channel out of range");
        return;
    }
    // arenaHandle == 0 → unset the channel (use the previously-assigned
    // arena no more). This is symmetric with the C++ "nullptr means
    // absent" convention.
    const df::NodeArena* arena = (arenaHandle == 0)
        ? nullptr
        : arena_from(arenaHandle);
    if (!assign_channel(s->router, channel, arena)) {
        lattice::jni::throw_illegal_arg(env, "lattice chunknoise: unknown channel");
    }
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativePrepareCache(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    auto* s = sampler_from(handle);
    if (!s) return;
    s->prepare_cache();
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeClearCache(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    auto* s = sampler_from(handle);
    if (!s) return;
    s->clear_cache();
}

JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeSampleFinalDensity(
        JNIEnv* /*env*/, jclass /*cls*/,
        jlong handle, jdouble x, jdouble y, jdouble z,
        jint cellX, jint cellZ) {
    auto* s = sampler_from(handle);
    if (!s) return 0.0;
    return s->sample_final_density(x, y, z, cellX, cellZ);
}

JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeSample(
        JNIEnv* /*env*/, jclass /*cls*/,
        jlong handle, jint channel,
        jdouble x, jdouble y, jdouble z,
        jint cellX, jint cellZ) {
    auto* s = sampler_from(handle);
    if (!s) return 0.0;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return 0.0;
    return s->sample(static_cast<cns::Channel>(channel),
                     x, y, z, cellX, cellZ);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeNumInterpolatorSlots(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel) {
    auto* s = sampler_from(handle);
    if (!s) return 0;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return 0;
    return static_cast<jint>(s->num_interpolator_slots(static_cast<cns::Channel>(channel)));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativePrepareInterpolators(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel,
        jint horizontalCellCount, jint verticalCellCount) {
    auto* s = sampler_from(handle);
    if (!s) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    s->prepare_interpolators(static_cast<cns::Channel>(channel),
                             static_cast<int>(horizontalCellCount),
                             static_cast<int>(verticalCellCount));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeStartInterpolation(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel) {
    auto* s = sampler_from(handle);
    if (!s) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    s->start_interpolation(static_cast<cns::Channel>(channel));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeStopInterpolation(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel) {
    auto* s = sampler_from(handle);
    if (!s) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    s->stop_interpolation(static_cast<cns::Channel>(channel));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeSetStartDensity(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel,
        jint slot, jint cellZ, jint cellY, jdouble value) {
    auto* s = sampler_from(handle);
    if (!s) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    s->set_start_density(static_cast<cns::Channel>(channel),
                         static_cast<int>(slot),
                         static_cast<int>(cellZ),
                         static_cast<int>(cellY),
                         static_cast<double>(value));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeSetEndDensity(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel,
        jint slot, jint cellZ, jint cellY, jdouble value) {
    auto* s = sampler_from(handle);
    if (!s) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    s->set_end_density(static_cast<cns::Channel>(channel),
                       static_cast<int>(slot),
                       static_cast<int>(cellZ),
                       static_cast<int>(cellY),
                       static_cast<double>(value));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeOnSampledCellCorners(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel,
        jint cellY, jint cellZ) {
    auto* s = sampler_from(handle);
    if (!s) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    s->on_sampled_cell_corners(static_cast<cns::Channel>(channel),
                               static_cast<int>(cellY),
                               static_cast<int>(cellZ));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeInterpolateY(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel, jdouble deltaY) {
    auto* s = sampler_from(handle);
    if (!s) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    s->interpolate_y(static_cast<cns::Channel>(channel), static_cast<double>(deltaY));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeInterpolateX(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel, jdouble deltaX) {
    auto* s = sampler_from(handle);
    if (!s) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    s->interpolate_x(static_cast<cns::Channel>(channel), static_cast<double>(deltaX));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeInterpolateZ(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel, jdouble deltaZ) {
    auto* s = sampler_from(handle);
    if (!s) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    s->interpolate_z(static_cast<cns::Channel>(channel), static_cast<double>(deltaZ));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeSwapBuffers(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel) {
    auto* s = sampler_from(handle);
    if (!s) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    s->swap_buffers(static_cast<cns::Channel>(channel));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeAdvanceColumn(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel) {
    auto* s = sampler_from(handle);
    if (!s) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    s->advance_column(static_cast<cns::Channel>(channel));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeSetDensityRow(
        JNIEnv* env, jclass /*cls*/, jlong handle, jint channel,
        jint slot, jint cellZ, jboolean toEnd, jdoubleArray row) {
    auto* s = sampler_from(handle);
    if (!s || !row) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    const jsize n = env->GetArrayLength(row);
    if (n < 0) return;
    jdouble* p = env->GetDoubleArrayElements(row, nullptr);
    if (!p) return;
    std::vector<double> values(static_cast<std::size_t>(n));
    for (jsize i = 0; i < n; ++i) values[static_cast<std::size_t>(i)] = static_cast<double>(p[i]);
    env->ReleaseDoubleArrayElements(row, p, JNI_ABORT);

    if (toEnd == JNI_TRUE) {
        s->set_end_density_row(static_cast<cns::Channel>(channel),
                               static_cast<int>(slot),
                               static_cast<int>(cellZ),
                               std::span<const double>(values.data(), values.size()));
    } else {
        s->set_start_density_row(static_cast<cns::Channel>(channel),
                                 static_cast<int>(slot),
                                 static_cast<int>(cellZ),
                                 std::span<const double>(values.data(), values.size()));
    }
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeFillDensityColumn(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel,
        jboolean toEnd,
        jdouble x, jdouble z,
        jint cellX, jint cellZ0,
        jdouble y0, jdouble dy,
        jint horizontalCellCount, jint verticalCellCount) {
    auto* s = sampler_from(handle);
    if (!s) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    const auto ch = static_cast<cns::Channel>(channel);
    if (toEnd == JNI_TRUE) {
        s->fill_end_density_column(ch,
                                   static_cast<double>(x),
                                   static_cast<double>(z),
                                   static_cast<int>(cellX),
                                   static_cast<int>(cellZ0),
                                   static_cast<double>(y0),
                                   static_cast<double>(dy),
                                   static_cast<int>(horizontalCellCount),
                                   static_cast<int>(verticalCellCount));
    } else {
        s->fill_start_density_column(ch,
                                     static_cast<double>(x),
                                     static_cast<double>(z),
                                     static_cast<int>(cellX),
                                     static_cast<int>(cellZ0),
                                     static_cast<double>(y0),
                                     static_cast<double>(dy),
                                     static_cast<int>(horizontalCellCount),
                                     static_cast<int>(verticalCellCount));
    }
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativePrimeFinalDensityColumns(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle,
        jdouble startX, jdouble endX,
        jdouble z,
        jint startCellX, jint endCellX,
        jint cellZ0,
        jdouble y0, jdouble dy,
        jint horizontalCellCount, jint verticalCellCount) {
    auto* s = sampler_from(handle);
    if (!s) return;
    s->prime_final_density_columns(static_cast<double>(startX),
                                   static_cast<double>(endX),
                                   static_cast<double>(z),
                                   static_cast<int>(startCellX),
                                   static_cast<int>(endCellX),
                                   static_cast<int>(cellZ0),
                                   static_cast<double>(y0),
                                   static_cast<double>(dy),
                                   static_cast<int>(horizontalCellCount),
                                   static_cast<int>(verticalCellCount));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeAdvanceFinalDensityColumn(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle,
        jdouble nextX,
        jdouble z,
        jint nextCellX,
        jint cellZ0,
        jdouble y0, jdouble dy,
        jint horizontalCellCount, jint verticalCellCount) {
    auto* s = sampler_from(handle);
    if (!s) return;
    s->advance_final_density_column(static_cast<double>(nextX),
                                    static_cast<double>(z),
                                    static_cast<int>(nextCellX),
                                    static_cast<int>(cellZ0),
                                    static_cast<double>(y0),
                                    static_cast<double>(dy),
                                    static_cast<int>(horizontalCellCount),
                                    static_cast<int>(verticalCellCount));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativePrimeChannelColumns(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel,
        jdouble startX, jdouble endX,
        jdouble z,
        jint startCellX, jint endCellX,
        jint cellZ0,
        jdouble y0, jdouble dy,
        jint horizontalCellCount, jint verticalCellCount) {
    auto* s = sampler_from(handle);
    if (!s) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    s->prime_channel_columns(static_cast<cns::Channel>(channel),
                             static_cast<double>(startX),
                             static_cast<double>(endX),
                             static_cast<double>(z),
                             static_cast<int>(startCellX),
                             static_cast<int>(endCellX),
                             static_cast<int>(cellZ0),
                             static_cast<double>(y0),
                             static_cast<double>(dy),
                             static_cast<int>(horizontalCellCount),
                             static_cast<int>(verticalCellCount));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeAdvanceChannelColumn(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint channel,
        jdouble nextX,
        jdouble z,
        jint nextCellX,
        jint cellZ0,
        jdouble y0, jdouble dy,
        jint horizontalCellCount, jint verticalCellCount) {
    auto* s = sampler_from(handle);
    if (!s) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    s->advance_channel_column(static_cast<cns::Channel>(channel),
                              static_cast<double>(nextX),
                              static_cast<double>(z),
                              static_cast<int>(nextCellX),
                              static_cast<int>(cellZ0),
                              static_cast<double>(y0),
                              static_cast<double>(dy),
                              static_cast<int>(horizontalCellCount),
                              static_cast<int>(verticalCellCount));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeSampleFinalDensityCellGrid(
        JNIEnv* env, jclass /*cls*/, jlong handle,
        jint cellY, jint cellZ,
        jdouble x0, jdouble y0, jdouble z0,
        jdouble dx, jdouble dy, jdouble dz,
        jint cellX, jint cellZCoord,
        jint nx, jint ny, jint nz,
        jdoubleArray out) {
    auto* s = sampler_from(handle);
    if (!s || !out) return;
    if (nx <= 0 || ny <= 0 || nz <= 0) return;
    const long long required = static_cast<long long>(nx)
                             * static_cast<long long>(ny)
                             * static_cast<long long>(nz);
    lattice::jni::CriticalDoubleArray buf{env, out};
    if (!buf) return;
    if (static_cast<long long>(buf.size()) < required) return;
    s->sample_final_density_cell_grid(static_cast<int>(cellY),
                                      static_cast<int>(cellZ),
                                      static_cast<double>(x0),
                                      static_cast<double>(y0),
                                      static_cast<double>(z0),
                                      static_cast<double>(dx),
                                      static_cast<double>(dy),
                                      static_cast<double>(dz),
                                      static_cast<int>(cellX),
                                      static_cast<int>(cellZCoord),
                                      static_cast<int>(nx),
                                      static_cast<int>(ny),
                                      static_cast<int>(nz),
                                      reinterpret_cast<double*>(buf.data()));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkNoiseSampler_nativeSampleCellGrid(
        JNIEnv* env, jclass /*cls*/, jlong handle, jint channel,
        jint cellY, jint cellZ,
        jdouble x0, jdouble y0, jdouble z0,
        jdouble dx, jdouble dy, jdouble dz,
        jint cellX, jint cellZCoord,
        jint nx, jint ny, jint nz,
        jdoubleArray out) {
    auto* s = sampler_from(handle);
    if (!s || !out) return;
    if (channel < 0 || channel >= static_cast<jint>(cns::kChannelCount)) return;
    if (nx <= 0 || ny <= 0 || nz <= 0) return;
    const long long required = static_cast<long long>(nx)
                             * static_cast<long long>(ny)
                             * static_cast<long long>(nz);
    lattice::jni::CriticalDoubleArray buf{env, out};
    if (!buf) return;
    if (static_cast<long long>(buf.size()) < required) return;
    s->sample_cell_grid(static_cast<cns::Channel>(channel),
                        static_cast<int>(cellY),
                        static_cast<int>(cellZ),
                        static_cast<double>(x0),
                        static_cast<double>(y0),
                        static_cast<double>(z0),
                        static_cast<double>(dx),
                        static_cast<double>(dy),
                        static_cast<double>(dz),
                        static_cast<int>(cellX),
                        static_cast<int>(cellZCoord),
                        static_cast<int>(nx),
                        static_cast<int>(ny),
                        static_cast<int>(nz),
                        reinterpret_cast<double*>(buf.data()));
}

} // extern "C"
