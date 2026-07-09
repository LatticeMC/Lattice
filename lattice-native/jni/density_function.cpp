// JNI bindings for NativeDensityFunction.
//
// Java class: com.latticemc.lattice.nativelib.NativeDensityFunction
//
// Handle-based API. Java creates an empty tree, then calls addXxx()
// per node, supplying NodeRefs returned by previous calls as operands.
// setRoot() picks the top-level node. evaluate() walks the tree.
//
// Noise samplers (Noise / ShiftedNoise nodes) reference a pre-built
// DoublePerlinNoiseSampler by its NativeDoublePerlinNoise handle — see
// addNoise / addShiftedNoise below.

#include <jni.h>

#include <algorithm>
#include <new>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "jni_helper.hpp"
#include "noise_handle.hpp"
#include "world/gen/densityfunction/beardifier.hpp"
#include "world/gen/densityfunction/density_function.hpp"
#include "world/gen/noise/double_perlin_noise.hpp"
#include "world/gen/noise/interpolated_noise.hpp"

namespace df  = lattice::world::gen::densityfunction;
namespace pns = lattice::world::gen::noise;
namespace bf  = lattice::world::gen::densityfunction::beardifier;

namespace {

inline df::NodeArena* arena_from(jlong h) noexcept {
    return reinterpret_cast<df::NodeArena*>(h);
}

inline const pns::DoublePerlinNoiseSampler* dpn_from(jlong h) noexcept {
    return lattice::jni::noise::sampler_from_handle(
        static_cast<long long>(h));
}

inline jint push_node(df::NodeArena* a, const df::Node& n) noexcept {
    return static_cast<jint>(a->push(n));
}

struct PinnedDoubleArray {
    JNIEnv* env = nullptr;
    jdoubleArray array = nullptr;
    jdouble* data = nullptr;
    std::size_t length = 0;
    bool delete_local_ref = true;

    PinnedDoubleArray() = default;

    PinnedDoubleArray(JNIEnv* env_in, jdoubleArray array_in, bool delete_local_ref_in = true)
        : env(env_in), array(array_in), delete_local_ref(delete_local_ref_in) {
        if (!env || !array) return;
        length = static_cast<std::size_t>(env->GetArrayLength(array));
        data = env->GetDoubleArrayElements(array, nullptr);
    }

    ~PinnedDoubleArray() {
        if (env && array && data) env->ReleaseDoubleArrayElements(array, data, JNI_ABORT);
        if (delete_local_ref && env && array) env->DeleteLocalRef(array);
    }

    PinnedDoubleArray(const PinnedDoubleArray&) = delete;
    PinnedDoubleArray& operator=(const PinnedDoubleArray&) = delete;

    PinnedDoubleArray(PinnedDoubleArray&& other) noexcept
        : env(other.env), array(other.array), data(other.data), length(other.length),
          delete_local_ref(other.delete_local_ref) {
        other.env = nullptr;
        other.array = nullptr;
        other.data = nullptr;
        other.length = 0;
        other.delete_local_ref = true;
    }

    PinnedDoubleArray& operator=(PinnedDoubleArray&&) = delete;
};

struct BoundCacheArrays {
    std::vector<jdoubleArray> arrays;
};

struct BoundInterpolatorColumns {
    std::vector<jint> slots;
    std::vector<jdoubleArray> starts;
    std::vector<jdoubleArray> ends;
};

std::mutex g_bindings_mutex;
std::unordered_map<df::CacheState*, BoundCacheArrays> g_cache_array_bindings;
std::unordered_map<df::CacheState*, BoundInterpolatorColumns> g_interpolator_bindings;

void delete_global_refs(JNIEnv* env, BoundCacheArrays& binding) {
    for (jdoubleArray array : binding.arrays) {
        if (array) env->DeleteGlobalRef(array);
    }
    binding.arrays.clear();
}

void delete_global_refs(JNIEnv* env, BoundInterpolatorColumns& binding) {
    for (jdoubleArray array : binding.starts) {
        if (array) env->DeleteGlobalRef(array);
    }
    for (jdoubleArray array : binding.ends) {
        if (array) env->DeleteGlobalRef(array);
    }
    binding.slots.clear();
    binding.starts.clear();
    binding.ends.clear();
}

void clear_bindings(JNIEnv* env, df::CacheState* cache) {
    if (!env || !cache) return;
    std::lock_guard<std::mutex> lock(g_bindings_mutex);
    if (auto it = g_cache_array_bindings.find(cache); it != g_cache_array_bindings.end()) {
        delete_global_refs(env, it->second);
        g_cache_array_bindings.erase(it);
    }
    if (auto it = g_interpolator_bindings.find(cache); it != g_interpolator_bindings.end()) {
        delete_global_refs(env, it->second);
        g_interpolator_bindings.erase(it);
    }
}

std::vector<PinnedDoubleArray> bind_cache_all_in_cell_arrays(JNIEnv* env, df::CacheState& cache, jobjectArray arrays) {
    std::vector<PinnedDoubleArray> pinned;
    if (!arrays) return pinned;
    const jsize count = env->GetArrayLength(arrays);
    const std::size_t slots = std::min<std::size_t>(static_cast<std::size_t>(count), cache.cache_all_in_cell_arrays.size());
    pinned.reserve(slots);
    for (std::size_t i = 0; i < slots; ++i) {
        auto* array = static_cast<jdoubleArray>(env->GetObjectArrayElement(arrays, static_cast<jsize>(i)));
        if (!array) continue;
        pinned.emplace_back(env, array);
        if (env->ExceptionCheck()) return pinned;
        auto& guard = pinned.back();
        if (!guard.data) continue;
        cache.cache_all_in_cell_arrays[i] = reinterpret_cast<const double*>(guard.data);
        cache.cache_all_in_cell_array_lengths[i] = guard.length;
    }
    return pinned;
}

std::vector<PinnedDoubleArray> bind_bound_cache_all_in_cell_arrays(JNIEnv* env, df::CacheState& cache) {
    std::vector<jdoubleArray> refs;
    {
        std::lock_guard<std::mutex> lock(g_bindings_mutex);
        if (auto it = g_cache_array_bindings.find(&cache); it != g_cache_array_bindings.end()) {
            refs = it->second.arrays;
        }
    }

    std::vector<PinnedDoubleArray> pinned;
    const std::size_t slots = std::min<std::size_t>(refs.size(), cache.cache_all_in_cell_arrays.size());
    pinned.reserve(slots);
    for (std::size_t i = 0; i < slots; ++i) {
        if (!refs[i]) continue;
        pinned.emplace_back(env, refs[i], false);
        if (env->ExceptionCheck()) return pinned;
        auto& guard = pinned.back();
        if (!guard.data) continue;
        cache.cache_all_in_cell_arrays[i] = reinterpret_cast<const double*>(guard.data);
        cache.cache_all_in_cell_array_lengths[i] = guard.length;
    }
    return pinned;
}

void unbind_cache_all_in_cell_arrays(df::CacheState& cache) {
    for (auto& p : cache.cache_all_in_cell_arrays) p = nullptr;
    for (auto& n : cache.cache_all_in_cell_array_lengths) n = 0;
}

} // namespace

extern "C" {

// ---- Plain C ABI for Java FFM ---------------------------------------------

JNIEXPORT long long lattice_density_create() {
    auto* a = new (std::nothrow) df::NodeArena{};
    return reinterpret_cast<long long>(a);
}

JNIEXPORT void lattice_density_destroy(long long handle) {
    delete arena_from(static_cast<jlong>(handle));
}

JNIEXPORT void lattice_density_set_root(long long handle, int nodeRef) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return;
    a->root = static_cast<df::NodeRef>(nodeRef);
}

JNIEXPORT long long lattice_density_create_cache(long long arenaHandle) {
    auto* a = arena_from(static_cast<jlong>(arenaHandle));
    if (!a) return 0;
    auto* c = new (std::nothrow) df::CacheState{};
    if (!c) return 0;
    c->resize_for(*a);
    return reinterpret_cast<long long>(c);
}

JNIEXPORT void lattice_density_destroy_cache(long long cacheHandle) {
    delete reinterpret_cast<df::CacheState*>(static_cast<jlong>(cacheHandle));
}

JNIEXPORT void lattice_density_clear_cache(long long cacheHandle) {
    auto* c = reinterpret_cast<df::CacheState*>(static_cast<jlong>(cacheHandle));
    if (c) c->clear();
}

JNIEXPORT int lattice_density_add_constant(long long handle, double value) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kConstant;
    n.d0 = value;
    return static_cast<int>(push_node(a, n));
}

JNIEXPORT int lattice_density_add_unary(long long handle, int kind, int input) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    switch (kind) {
        case 1: n.kind = df::NodeKind::kAbs; break;
        case 2: n.kind = df::NodeKind::kSquare; break;
        case 3: n.kind = df::NodeKind::kCube; break;
        case 4: n.kind = df::NodeKind::kHalfNegative; break;
        case 5: n.kind = df::NodeKind::kQuarterNegative; break;
        case 6: n.kind = df::NodeKind::kInvert; break;
        case 7: n.kind = df::NodeKind::kSqueeze; break;
        default: return -1;
    }
    n.a = static_cast<df::NodeRef>(input);
    return static_cast<int>(push_node(a, n));
}

JNIEXPORT int lattice_density_add_binary(long long handle, int kind, int left, int right) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    switch (kind) {
        case 1: n.kind = df::NodeKind::kAdd; break;
        case 2: n.kind = df::NodeKind::kMul; break;
        case 3: n.kind = df::NodeKind::kMin; break;
        case 4: n.kind = df::NodeKind::kMax; break;
        default: return -1;
    }
    n.a = static_cast<df::NodeRef>(left);
    n.b = static_cast<df::NodeRef>(right);
    return static_cast<int>(push_node(a, n));
}

JNIEXPORT int lattice_density_add_y_clamped_gradient(long long handle, int fromY, int toY, double fromValue, double toValue) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kYClampedGradient;
    n.i0 = fromY;
    n.i1 = toY;
    n.d0 = fromValue;
    n.d1 = toValue;
    return static_cast<int>(push_node(a, n));
}

JNIEXPORT int lattice_density_add_clamp(long long handle, int input, double minValue, double maxValue) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kClamp;
    n.a = static_cast<df::NodeRef>(input);
    n.d0 = minValue;
    n.d1 = maxValue;
    return static_cast<int>(push_node(a, n));
}

JNIEXPORT int lattice_density_add_blend_alpha(long long handle) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kBlendAlpha;
    return static_cast<int>(push_node(a, n));
}

JNIEXPORT int lattice_density_add_blend_offset(long long handle) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kBlendOffset;
    return static_cast<int>(push_node(a, n));
}

JNIEXPORT int lattice_density_add_blend_density(long long handle, int input) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kBlendDensity;
    n.a = static_cast<df::NodeRef>(input);
    return static_cast<int>(push_node(a, n));
}

JNIEXPORT int lattice_density_add_noise(long long handle, long long noiseHandle, double scaleXZ, double scaleY) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kNoise;
    n.noise_ptr = dpn_from(static_cast<jlong>(noiseHandle));
    n.d0 = scaleXZ;
    n.d1 = scaleY;
    return n.noise_ptr ? static_cast<int>(push_node(a, n)) : -1;
}

JNIEXPORT int lattice_density_add_shifted_noise(long long handle, int shiftX, int shiftY, int shiftZ, long long noiseHandle, double xzScale, double yScale) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kShiftedNoise;
    n.a = static_cast<df::NodeRef>(shiftX);
    n.b = static_cast<df::NodeRef>(shiftY);
    n.c = static_cast<df::NodeRef>(shiftZ);
    n.noise_ptr = dpn_from(static_cast<jlong>(noiseHandle));
    n.d0 = xzScale;
    n.d1 = yScale;
    return n.noise_ptr ? static_cast<int>(push_node(a, n)) : -1;
}

JNIEXPORT int lattice_density_add_shift(long long handle, int kind, long long noiseHandle) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    switch (kind) {
        case 1: n.kind = df::NodeKind::kShiftA; break;
        case 2: n.kind = df::NodeKind::kShiftB; break;
        case 3: n.kind = df::NodeKind::kShift; break;
        default: return -1;
    }
    n.noise_ptr = dpn_from(static_cast<jlong>(noiseHandle));
    return n.noise_ptr ? static_cast<int>(push_node(a, n)) : -1;
}

JNIEXPORT int lattice_density_add_range_choice(long long handle, int input, double minInclusive, double maxExclusive, int whenIn, int whenOut) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kRangeChoice;
    n.a = static_cast<df::NodeRef>(input);
    n.b = static_cast<df::NodeRef>(whenIn);
    n.c = static_cast<df::NodeRef>(whenOut);
    n.d0 = minInclusive;
    n.d1 = maxExclusive;
    return static_cast<int>(push_node(a, n));
}

JNIEXPORT int lattice_density_add_map_range(long long handle, int input, double fromLow, double fromHigh, double toLow, double toHigh) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kMapRange;
    n.a = static_cast<df::NodeRef>(input);
    n.d0 = fromLow;
    n.d1 = fromHigh;
    n.d2 = toLow;
    n.d3 = toHigh;
    return static_cast<int>(push_node(a, n));
}

JNIEXPORT int lattice_density_add_cache(long long handle, int kind, int input) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    switch (kind) {
        case 1: n.kind = df::NodeKind::kCache2D; break;
        case 2: n.kind = df::NodeKind::kCacheOnce; break;
        case 3: n.kind = df::NodeKind::kCacheAllInCell; break;
        case 4: n.kind = df::NodeKind::kFlatCache; break;
        case 5: n.kind = df::NodeKind::kInterpolated; break;
        default: return -1;
    }
    n.a = static_cast<df::NodeRef>(input);
    return static_cast<int>(push_node(a, n));
}

JNIEXPORT int lattice_density_add_cache_all_in_cell_value(long long handle) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kCacheAllInCell;
    return static_cast<int>(push_node(a, n));
}

JNIEXPORT int lattice_density_cache_slot(long long handle, int nodeRef) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a || nodeRef < 0 || static_cast<std::size_t>(nodeRef) >= a->nodes.size()) return -1;
    return a->nodes[static_cast<std::size_t>(nodeRef)].cache_slot_id;
}

JNIEXPORT int lattice_density_add_weird_scaled_sampler(long long handle, int input, long long noiseHandle, int type) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kWeirdScaledSampler;
    n.a = static_cast<df::NodeRef>(input);
    n.noise_ptr = dpn_from(static_cast<jlong>(noiseHandle));
    n.d0 = static_cast<double>(type);
    return n.noise_ptr ? static_cast<int>(push_node(a, n)) : -1;
}

JNIEXPORT int lattice_density_add_interpolated_noise(long long handle, long long samplerHandle) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kInterpolatedNoise;
    n.interp_noise_ptr = reinterpret_cast<const pns::InterpolatedNoiseSampler*>(samplerHandle);
    return n.interp_noise_ptr ? static_cast<int>(push_node(a, n)) : -1;
}

JNIEXPORT int lattice_density_add_spline(long long handle, int splineRef) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kSpline;
    n.i0 = splineRef;
    return static_cast<int>(push_node(a, n));
}

JNIEXPORT int lattice_density_add_beardifier(long long handle, long long beardifierHandle) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kBeardifier;
    n.beardifier_ptr = reinterpret_cast<const bf::BeardifierData*>(beardifierHandle);
    return n.beardifier_ptr ? static_cast<int>(push_node(a, n)) : -1;
}

JNIEXPORT void lattice_density_evaluate_y_column(long long handle, long long cacheHandle,
                                                 double x, double y0, double z, double dy,
                                                 int cellX, int cellZ, int ny,
                                                 double* out) {
    auto* a = arena_from(static_cast<jlong>(handle));
    if (!a || !out || ny <= 0) return;
    auto* cache = reinterpret_cast<df::CacheState*>(static_cast<jlong>(cacheHandle));
    if (cache) cache->clear();
    df::evaluate_y_column(*a, a->root, x, y0, z, dy, cellX, cellZ, ny, cache, out);
}

JNIEXPORT void lattice_density_evaluate_y_columns(const long long* handles,
                                                  const long long* cacheHandles,
                                                  int count,
                                                  double x, double y0, double z, double dy,
                                                  int cellX, int cellZ, int ny,
                                                  double* outPacked) {
    if (!handles || !cacheHandles || !outPacked || count <= 0 || ny <= 0) return;
    for (int i = 0; i < count; ++i) {
        auto* a = arena_from(static_cast<jlong>(handles[i]));
        if (!a) continue;
        auto* cache = reinterpret_cast<df::CacheState*>(static_cast<jlong>(cacheHandles[i]));
        if (cache) cache->clear();
        df::evaluate_y_column(*a, a->root, x, y0, z, dy, cellX, cellZ, ny, cache,
                              outPacked + static_cast<std::size_t>(i) * static_cast<std::size_t>(ny));
    }
}

JNIEXPORT void lattice_density_evaluate_interpolated_columns(
        const long long* handles,
        const long long* cacheHandles,
        int count,
        double x0, double z0, double yMin,
        int cellX, int firstCellZ,
        int cellWidth, int cellHeight,
        int cellCountXZ, int cellCountY,
        double* outPacked,
        const double* cacheValuesPacked,
        const long long* cacheOffsets,
        const long long* cacheLengths,
        int maxCacheSlots) {
    if (!handles || !cacheHandles || !outPacked || count <= 0) return;
    if (cellWidth <= 0 || cellHeight <= 0 || cellCountXZ <= 0 || cellCountY <= 0) return;

    const long long cell_value_count = static_cast<long long>(cellWidth)
                                     * static_cast<long long>(cellHeight)
                                     * static_cast<long long>(cellWidth);
    const long long required = static_cast<long long>(cellCountXZ)
                             * static_cast<long long>(cellCountY)
                             * cell_value_count;

    for (int i = 0; i < count; ++i) {
        auto* a = arena_from(static_cast<jlong>(handles[i]));
        auto* cache = reinterpret_cast<df::CacheState*>(static_cast<jlong>(cacheHandles[i]));
        if (!a || !cache) continue;

        if (cacheValuesPacked && cacheOffsets && cacheLengths && maxCacheSlots > 0) {
            const int slots = std::min<int>(maxCacheSlots, static_cast<int>(cache->cache_all_in_cell_arrays.size()));
            for (int slot = 0; slot < slots; ++slot) {
                const int index = i * maxCacheSlots + slot;
                const long long length = cacheLengths[index];
                if (length <= 0) continue;
                cache->cache_all_in_cell_arrays[static_cast<std::size_t>(slot)] = cacheValuesPacked + cacheOffsets[index];
                cache->cache_all_in_cell_array_lengths[static_cast<std::size_t>(slot)] = static_cast<std::size_t>(length);
            }
        }

        df::Context ctx{};
        ctx.cache = cache;
        ctx.cellX = cellX;
        ctx.cellWidth = cellWidth;
        ctx.cellHeight = cellHeight;

        double* dst = outPacked + static_cast<std::size_t>(i) * static_cast<std::size_t>(required);
        for (int lz = 0; lz < cellCountXZ; ++lz) {
            ctx.cellZ = firstCellZ + lz;
            const double cell_z0 = z0 + static_cast<double>(lz * cellWidth);
            for (int ly = 0; ly < cellCountY; ++ly) {
                df::start_interpolation(*cache);
                df::on_sampled_cell_corners(*cache, ly, lz);
                const double y_top = yMin + static_cast<double>(ly * cellHeight + cellHeight - 1);
                const std::size_t base = (static_cast<std::size_t>(lz) * static_cast<std::size_t>(cellCountY)
                                       + static_cast<std::size_t>(ly))
                                      * static_cast<std::size_t>(cell_value_count);
                std::size_t index = base;
                for (int iy = 0; iy < cellHeight; ++iy) {
                    const int in_cell_y = cellHeight - 1 - iy;
                    ctx.inCellY = in_cell_y;
                    ctx.y = y_top - static_cast<double>(iy);
                    df::interpolate_y(*cache, static_cast<double>(in_cell_y) / static_cast<double>(cellHeight));
                    for (int ix = 0; ix < cellWidth; ++ix) {
                        ctx.inCellX = ix;
                        ctx.x = x0 + static_cast<double>(ix);
                        df::interpolate_x(*cache, static_cast<double>(ix) / static_cast<double>(cellWidth));
                        for (int iz = 0; iz < cellWidth; ++iz) {
                            ctx.inCellZ = iz;
                            ctx.z = cell_z0 + static_cast<double>(iz);
                            df::interpolate_z(*cache, static_cast<double>(iz) / static_cast<double>(cellWidth));
                            dst[index++] = df::evaluate(*a, a->root, ctx);
                        }
                    }
                }
                df::stop_interpolation(*cache);
            }
        }
        unbind_cache_all_in_cell_arrays(*cache);
    }
}

// ---- Tree lifecycle -------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeCreate(
        JNIEnv* env, jclass /*cls*/) {
    auto* a = new (std::nothrow) df::NodeArena{};
    if (!a) {
        lattice::jni::throw_oom(env, "lattice density: arena alloc");
        return 0;
    }
    return reinterpret_cast<jlong>(a);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeDestroy(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    delete arena_from(handle);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeSetRoot(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint nodeRef) {
    auto* a = arena_from(handle);
    if (!a) return;
    a->root = static_cast<df::NodeRef>(nodeRef);
}

// ---- Sampling -------------------------------------------------------------

JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeEvaluate(
        JNIEnv* /*env*/, jclass /*cls*/,
        jlong handle, jdouble x, jdouble y, jdouble z) {
    auto* a = arena_from(handle);
    if (!a) return 0.0;
    df::Context c{};
    c.x = x; c.y = y; c.z = z;
    return df::evaluate(*a, c);
}

JNIEXPORT jdouble JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeEvaluateCached(
        JNIEnv* /*env*/, jclass /*cls*/,
        jlong handle, jlong cacheHandle,
        jdouble x, jdouble y, jdouble z, jint cellX, jint cellZ) {
    auto* a = arena_from(handle);
    if (!a) return 0.0;
    df::Context c{};
    c.x = x; c.y = y; c.z = z;
    c.cellX = cellX; c.cellZ = cellZ;
    c.cache = reinterpret_cast<df::CacheState*>(cacheHandle);
    return df::evaluate(*a, c);
}

// ---- Cache state lifecycle ------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeCreateCache(
        JNIEnv* env, jclass /*cls*/, jlong arenaHandle) {
    auto* a = arena_from(arenaHandle);
    if (!a) return 0;
    auto* c = new (std::nothrow) df::CacheState{};
    if (!c) {
        lattice::jni::throw_oom(env, "lattice density: cache alloc");
        return 0;
    }
    c->resize_for(*a);
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeDestroyCache(
        JNIEnv* env, jclass /*cls*/, jlong cacheHandle) {
    auto* cache = reinterpret_cast<df::CacheState*>(cacheHandle);
    clear_bindings(env, cache);
    delete cache;
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeBindCacheAllInCellArrays(
        JNIEnv* env, jclass /*cls*/, jlong cacheHandle, jobjectArray arrays) {
    auto* cache = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!cache) return;

    BoundCacheArrays next;
    if (arrays) {
        const jsize count = env->GetArrayLength(arrays);
        const std::size_t slots = std::min<std::size_t>(static_cast<std::size_t>(count), cache->cache_all_in_cell_arrays.size());
        next.arrays.reserve(slots);
        for (std::size_t i = 0; i < slots; ++i) {
            auto* array = static_cast<jdoubleArray>(env->GetObjectArrayElement(arrays, static_cast<jsize>(i)));
            if (!array) {
                next.arrays.push_back(nullptr);
                continue;
            }
            auto* global = static_cast<jdoubleArray>(env->NewGlobalRef(array));
            env->DeleteLocalRef(array);
            if (!global) {
                delete_global_refs(env, next);
                lattice::jni::throw_oom(env, "lattice density: cache array global ref");
                return;
            }
            next.arrays.push_back(global);
        }
    }

    std::lock_guard<std::mutex> lock(g_bindings_mutex);
    auto& current = g_cache_array_bindings[cache];
    delete_global_refs(env, current);
    current = std::move(next);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeClearCache(
        JNIEnv* /*env*/, jclass /*cls*/, jlong cacheHandle) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (c) c->clear();
}

// ---- Node builders --------------------------------------------------------

// Each `add*` returns the NodeRef (>= 0) of the newly-pushed node, or
// -1 on error (null handle). Operand validation is light — the
// evaluator's bounds check is the authoritative one.

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddConstant(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jdouble value) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kConstant;
    n.d0   = value;
    return push_node(a, n);
}

static jint add_unary(jlong handle, df::NodeKind kind, jint input) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = kind;
    n.a    = static_cast<df::NodeRef>(input);
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddAbs(
        JNIEnv*, jclass, jlong h, jint a) { return add_unary(h, df::NodeKind::kAbs, a); }

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddSquare(
        JNIEnv*, jclass, jlong h, jint a) { return add_unary(h, df::NodeKind::kSquare, a); }

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddCube(
        JNIEnv*, jclass, jlong h, jint a) { return add_unary(h, df::NodeKind::kCube, a); }

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddHalfNegative(
        JNIEnv*, jclass, jlong h, jint a) { return add_unary(h, df::NodeKind::kHalfNegative, a); }

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddQuarterNegative(
        JNIEnv*, jclass, jlong h, jint a) { return add_unary(h, df::NodeKind::kQuarterNegative, a); }

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddInvert(
        JNIEnv*, jclass, jlong h, jint a) { return add_unary(h, df::NodeKind::kInvert, a); }

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddSqueeze(
        JNIEnv*, jclass, jlong h, jint a) { return add_unary(h, df::NodeKind::kSqueeze, a); }

static jint add_binary(jlong handle, df::NodeKind kind, jint left, jint right) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = kind;
    n.a    = static_cast<df::NodeRef>(left);
    n.b    = static_cast<df::NodeRef>(right);
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddAdd(
        JNIEnv*, jclass, jlong h, jint l, jint r) { return add_binary(h, df::NodeKind::kAdd, l, r); }

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddMul(
        JNIEnv*, jclass, jlong h, jint l, jint r) { return add_binary(h, df::NodeKind::kMul, l, r); }

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddMin(
        JNIEnv*, jclass, jlong h, jint l, jint r) { return add_binary(h, df::NodeKind::kMin, l, r); }

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddMax(
        JNIEnv*, jclass, jlong h, jint l, jint r) { return add_binary(h, df::NodeKind::kMax, l, r); }

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddYClampedGradient(
        JNIEnv*, jclass, jlong handle,
        jint fromY, jint toY, jdouble fromValue, jdouble toValue) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kYClampedGradient;
    n.i0   = fromY;
    n.i1   = toY;
    n.d0   = fromValue;
    n.d1   = toValue;
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddMapRange(
        JNIEnv*, jclass, jlong handle,
        jint input, jdouble fromMin, jdouble fromMax,
        jdouble toMin, jdouble toMax) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kMapRange;
    n.a    = static_cast<df::NodeRef>(input);
    n.d0   = fromMin;
    n.d1   = fromMax;
    n.d2   = toMin;
    n.d3   = toMax;
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddLerp(
        JNIEnv*, jclass, jlong handle, jint t, jint low, jint high) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kLerp;
    n.a    = static_cast<df::NodeRef>(t);
    n.b    = static_cast<df::NodeRef>(low);
    n.c    = static_cast<df::NodeRef>(high);
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddRangeChoice(
        JNIEnv*, jclass, jlong handle,
        jint input, jdouble minIncl, jdouble maxExcl,
        jint whenIn, jint whenOut) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kRangeChoice;
    n.a    = static_cast<df::NodeRef>(input);
    n.b    = static_cast<df::NodeRef>(whenIn);
    n.c    = static_cast<df::NodeRef>(whenOut);
    n.d0   = minIncl;
    n.d1   = maxExcl;
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddNoise(
        JNIEnv*, jclass, jlong handle,
        jlong noiseHandle, jdouble scaleXZ, jdouble scaleY) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind      = df::NodeKind::kNoise;
    n.d0        = scaleXZ;
    n.d1        = scaleY;
    n.noise_ptr = dpn_from(noiseHandle);
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddShiftedNoise(
        JNIEnv*, jclass, jlong handle,
        jint shiftX, jint shiftY, jint shiftZ,
        jlong noiseHandle, jdouble xzScale, jdouble yScale) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind      = df::NodeKind::kShiftedNoise;
    n.a         = static_cast<df::NodeRef>(shiftX);
    n.b         = static_cast<df::NodeRef>(shiftY);
    n.c         = static_cast<df::NodeRef>(shiftZ);
    n.d0        = xzScale;
    n.d1        = yScale;
    n.noise_ptr = dpn_from(noiseHandle);
    return push_node(a, n);
}

// ---- Worldgen-4c additions ------------------------------------------------

static jint add_shift_family(jlong handle, df::NodeKind kind, jlong noiseHandle) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind      = kind;
    n.noise_ptr = dpn_from(noiseHandle);
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddShiftA(
        JNIEnv*, jclass, jlong h, jlong nh) {
    return add_shift_family(h, df::NodeKind::kShiftA, nh);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddShiftB(
        JNIEnv*, jclass, jlong h, jlong nh) {
    return add_shift_family(h, df::NodeKind::kShiftB, nh);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddShift(
        JNIEnv*, jclass, jlong h, jlong nh) {
    return add_shift_family(h, df::NodeKind::kShift, nh);
}

static jint add_cache(jlong handle, df::NodeKind kind, jint input) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = kind;
    n.a    = static_cast<df::NodeRef>(input);
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddCache2D(
        JNIEnv*, jclass, jlong h, jint i) { return add_cache(h, df::NodeKind::kCache2D, i); }
JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddCacheOnce(
        JNIEnv*, jclass, jlong h, jint i) { return add_cache(h, df::NodeKind::kCacheOnce, i); }
JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddCacheAllInCell(
        JNIEnv*, jclass, jlong h, jint i) { return add_cache(h, df::NodeKind::kCacheAllInCell, i); }
JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddCacheAllInCellValue(
        JNIEnv*, jclass, jlong handle) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kCacheAllInCell;
    n.a = -1;
    return push_node(a, n);
}
JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddFlatCache(
        JNIEnv*, jclass, jlong h, jint i) { return add_cache(h, df::NodeKind::kFlatCache, i); }

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddInterpolated(
        JNIEnv*, jclass, jlong h, jint i) {
    return add_cache(h, df::NodeKind::kInterpolated, i);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddWeirdScaledSampler(
        JNIEnv*, jclass, jlong handle, jint input, jlong noiseHandle, jint type) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind      = df::NodeKind::kWeirdScaledSampler;
    n.a         = static_cast<df::NodeRef>(input);
    n.noise_ptr = dpn_from(noiseHandle);
    n.d0        = static_cast<double>(type);
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddEndIslands(
        JNIEnv*, jclass, jlong handle, jlong simplexHandle) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind        = df::NodeKind::kEndIslands;
    // SimplexNoiseSampler is a standalone heap allocation; its
    // jlong handle is a direct pointer. No layout-offset trick needed
    // (unlike NativeDoublePerlinNoise's Bundle wrapper).
    n.simplex_ptr = reinterpret_cast<const lattice::world::gen::noise::SimplexNoiseSampler*>(simplexHandle);
    return push_node(a, n);
}

// ---- Worldgen-7 additions -------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddClamp(
        JNIEnv*, jclass, jlong handle, jint input, jdouble minValue, jdouble maxValue) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kClamp;
    n.a    = static_cast<df::NodeRef>(input);
    n.d0   = minValue;
    n.d1   = maxValue;
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddBlendAlpha(
        JNIEnv*, jclass, jlong handle) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kBlendAlpha;
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddBlendOffset(
        JNIEnv*, jclass, jlong handle) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kBlendOffset;
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddBlendDensity(
        JNIEnv*, jclass, jlong handle, jint input) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kBlendDensity;
    n.a    = static_cast<df::NodeRef>(input);
    return push_node(a, n);
}

// ---- Worldgen-9: batched cell-grid evaluation ------------------------------

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeEvaluateGrid(
        JNIEnv* env, jclass /*cls*/,
        jlong handle, jlong cacheHandle,
        jdouble x0, jdouble y0, jdouble z0,
        jdouble dx, jdouble dy, jdouble dz,
        jint cellX0, jint cellZ0,
        jint nx, jint ny, jint nz,
        jdoubleArray out) {
    auto* a = arena_from(handle);
    if (!a) {
        lattice::jni::throw_illegal_state(env, "lattice density: null arena");
        return;
    }
    if (!out) {
        lattice::jni::throw_illegal_arg(env, "lattice density: null output array");
        return;
    }
    if (nx <= 0 || ny <= 0 || nz <= 0) return;

    const long long required = static_cast<long long>(nx)
                             * static_cast<long long>(ny)
                             * static_cast<long long>(nz);

    lattice::jni::CriticalDoubleArray buf{env, out};
    if (!buf) {
        lattice::jni::throw_illegal_state(env, "lattice density: array critical lock failed");
        return;
    }
    if (static_cast<long long>(buf.size()) < required) {
        lattice::jni::throw_illegal_arg(env, "lattice density: output array too small");
        return;
    }

    auto* cache = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (cache) cache->clear();
    df::evaluate_grid(*a, a->root,
                      static_cast<double>(x0),
                      static_cast<double>(y0),
                      static_cast<double>(z0),
                      static_cast<double>(dx),
                      static_cast<double>(dy),
                      static_cast<double>(dz),
                      static_cast<int>(cellX0),
                      static_cast<int>(cellZ0),
                      static_cast<int>(nx),
                      static_cast<int>(ny),
                      static_cast<int>(nz),
                        cache,
                        reinterpret_cast<double*>(buf.data()));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeEvaluateYColumn(
        JNIEnv* env, jclass /*cls*/,
        jlong handle, jlong cacheHandle,
        jdouble x, jdouble y0, jdouble z, jdouble dy,
        jint cellX, jint cellZ,
        jint ny,
        jdoubleArray out) {
    auto* a = arena_from(handle);
    if (!a) {
        lattice::jni::throw_illegal_state(env, "lattice density: null arena");
        return;
    }
    if (!out) {
        lattice::jni::throw_illegal_arg(env, "lattice density: null column output array");
        return;
    }
    if (ny <= 0) return;

    lattice::jni::CriticalDoubleArray buf{env, out};
    if (!buf) {
        lattice::jni::throw_illegal_state(env, "lattice density: column array critical lock failed");
        return;
    }
    if (static_cast<long long>(buf.size()) < static_cast<long long>(ny)) {
        lattice::jni::throw_illegal_arg(env, "lattice density: column output array too small");
        return;
    }

    auto* cache = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (cache) cache->clear();
    df::evaluate_y_column(*a, a->root,
                          static_cast<double>(x),
                          static_cast<double>(y0),
                          static_cast<double>(z),
                          static_cast<double>(dy),
                          static_cast<int>(cellX),
                          static_cast<int>(cellZ),
                          static_cast<int>(ny),
                          cache,
                          reinterpret_cast<double*>(buf.data()));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeEvaluateYColumns(
        JNIEnv* env, jclass /*cls*/,
        jlongArray handles, jlongArray cacheHandles, jint count,
        jdouble x, jdouble y0, jdouble z, jdouble dy,
        jint cellX, jint cellZ,
        jint ny,
        jobjectArray out) {
    if (!handles || !cacheHandles || !out) {
        lattice::jni::throw_illegal_arg(env, "lattice density: null batch arrays");
        return;
    }
    if (count <= 0 || ny <= 0) return;
    const jsize handle_count = env->GetArrayLength(handles);
    const jsize cache_count = env->GetArrayLength(cacheHandles);
    const jsize out_count = env->GetArrayLength(out);
    if (handle_count < count || cache_count < count || out_count < count) {
        lattice::jni::throw_illegal_arg(env, "lattice density: batch arrays too small");
        return;
    }

    jlong* handle_data = env->GetLongArrayElements(handles, nullptr);
    if (!handle_data) return;
    jlong* cache_data = env->GetLongArrayElements(cacheHandles, nullptr);
    if (!cache_data) {
        env->ReleaseLongArrayElements(handles, handle_data, JNI_ABORT);
        return;
    }

    for (jsize i = 0; i < count; ++i) {
        auto* a = arena_from(handle_data[i]);
        auto* cache = reinterpret_cast<df::CacheState*>(cache_data[i]);
        if (!a) {
            env->ReleaseLongArrayElements(cacheHandles, cache_data, JNI_ABORT);
            env->ReleaseLongArrayElements(handles, handle_data, JNI_ABORT);
            lattice::jni::throw_illegal_state(env, "lattice density: null arena in batch");
            return;
        }
        auto* out_row = static_cast<jdoubleArray>(env->GetObjectArrayElement(out, i));
        if (!out_row) {
            env->ReleaseLongArrayElements(cacheHandles, cache_data, JNI_ABORT);
            env->ReleaseLongArrayElements(handles, handle_data, JNI_ABORT);
            lattice::jni::throw_illegal_arg(env, "lattice density: null batch output row");
            return;
        }
        lattice::jni::CriticalDoubleArray buf{env, out_row};
        env->DeleteLocalRef(out_row);
        if (!buf) {
            env->ReleaseLongArrayElements(cacheHandles, cache_data, JNI_ABORT);
            env->ReleaseLongArrayElements(handles, handle_data, JNI_ABORT);
            lattice::jni::throw_illegal_state(env, "lattice density: batch output lock failed");
            return;
        }
        if (static_cast<long long>(buf.size()) < static_cast<long long>(ny)) {
            env->ReleaseLongArrayElements(cacheHandles, cache_data, JNI_ABORT);
            env->ReleaseLongArrayElements(handles, handle_data, JNI_ABORT);
            lattice::jni::throw_illegal_arg(env, "lattice density: batch output row too small");
            return;
        }
        if (cache) cache->clear();
        df::evaluate_y_column(*a, a->root,
                              static_cast<double>(x),
                              static_cast<double>(y0),
                              static_cast<double>(z),
                              static_cast<double>(dy),
                              static_cast<int>(cellX),
                              static_cast<int>(cellZ),
                              static_cast<int>(ny),
                              cache,
                              reinterpret_cast<double*>(buf.data()));
    }

    env->ReleaseLongArrayElements(cacheHandles, cache_data, JNI_ABORT);
    env->ReleaseLongArrayElements(handles, handle_data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeEvaluateYColumnsPacked(
        JNIEnv* env, jclass /*cls*/,
        jlongArray handles, jlongArray cacheHandles, jint count,
        jdouble x, jdouble y0, jdouble z, jdouble dy,
        jint cellX, jint cellZ,
        jint ny,
        jdoubleArray outPacked) {
    if (!handles || !cacheHandles || !outPacked) {
        lattice::jni::throw_illegal_arg(env, "lattice density: null packed batch arrays");
        return;
    }
    if (count <= 0 || ny <= 0) return;
    const jsize handle_count = env->GetArrayLength(handles);
    const jsize cache_count = env->GetArrayLength(cacheHandles);
    const std::size_t required = static_cast<std::size_t>(count) * static_cast<std::size_t>(ny);
    if (handle_count < count || cache_count < count) {
        lattice::jni::throw_illegal_arg(env, "lattice density: packed batch handle arrays too small");
        return;
    }

    lattice::jni::CriticalLongArray handle_data{env, handles};
    if (!handle_data) {
        lattice::jni::throw_illegal_state(env, "lattice density: packed handles lock failed");
        return;
    }
    lattice::jni::CriticalLongArray cache_data{env, cacheHandles};
    if (!cache_data) {
        lattice::jni::throw_illegal_state(env, "lattice density: packed cache handles lock failed");
        return;
    }
    lattice::jni::CriticalDoubleArray out_data{env, outPacked};
    if (!out_data) {
        lattice::jni::throw_illegal_state(env, "lattice density: packed output lock failed");
        return;
    }
    if (out_data.size() < required) {
        lattice::jni::throw_illegal_arg(env, "lattice density: packed output array too small");
        return;
    }

    for (jint i = 0; i < count; ++i) {
        auto* a = arena_from(handle_data.data()[i]);
        if (!a) {
            lattice::jni::throw_illegal_state(env, "lattice density: null arena in packed batch");
            return;
        }
        auto* cache = reinterpret_cast<df::CacheState*>(cache_data.data()[i]);
        if (cache) cache->clear();
        df::evaluate_y_column(*a, a->root,
                              static_cast<double>(x),
                              static_cast<double>(y0),
                              static_cast<double>(z),
                              static_cast<double>(dy),
                              static_cast<int>(cellX),
                              static_cast<int>(cellZ),
                              static_cast<int>(ny),
                              cache,
                              reinterpret_cast<double*>(out_data.data())
                                      + static_cast<std::size_t>(i) * static_cast<std::size_t>(ny));
    }

    handle_data.release_ro();
    cache_data.release_ro();
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeEvaluateInterpolatedCell(
        JNIEnv* env, jclass /*cls*/,
        jlong handle, jlong cacheHandle,
        jdouble x0, jdouble yTop, jdouble z0,
        jint cellX, jint cellZ,
        jint localCellY, jint localCellZ,
        jint cellWidth, jint cellHeight,
        jobjectArray cacheAllInCellValues,
        jdoubleArray out) {
    auto* a = arena_from(handle);
    auto* cache = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!a || !cache) {
        lattice::jni::throw_illegal_state(env, "lattice density: null arena/cache");
        return;
    }
    if (!out) {
        lattice::jni::throw_illegal_arg(env, "lattice density: null output array");
        return;
    }
    if (cellWidth <= 0 || cellHeight <= 0) return;

    const long long required = static_cast<long long>(cellWidth)
                             * static_cast<long long>(cellHeight)
                             * static_cast<long long>(cellWidth);
    auto pinned_cache_arrays = cacheAllInCellValues
        ? bind_cache_all_in_cell_arrays(env, *cache, cacheAllInCellValues)
        : bind_bound_cache_all_in_cell_arrays(env, *cache);
    if (env->ExceptionCheck()) return;
    lattice::jni::CriticalDoubleArray buf{env, out};
    if (!buf) {
        unbind_cache_all_in_cell_arrays(*cache);
        lattice::jni::throw_illegal_state(env, "lattice density: array critical lock failed");
        return;
    }
    if (static_cast<long long>(buf.size()) < required) {
        unbind_cache_all_in_cell_arrays(*cache);
        lattice::jni::throw_illegal_arg(env, "lattice density: output array too small");
        return;
    }

    df::start_interpolation(*cache);
    df::on_sampled_cell_corners(*cache, static_cast<int>(localCellY), static_cast<int>(localCellZ));

    df::Context ctx{};
    ctx.cache = cache;
    ctx.cellX = static_cast<int>(cellX);
    ctx.cellZ = static_cast<int>(cellZ);
    ctx.cellWidth = static_cast<int>(cellWidth);
    ctx.cellHeight = static_cast<int>(cellHeight);

    double* dst = reinterpret_cast<double*>(buf.data());
    std::size_t index = 0;
    for (int iy = 0; iy < cellHeight; ++iy) {
        const int in_cell_y = cellHeight - 1 - iy;
        ctx.inCellY = in_cell_y;
        ctx.y = yTop - static_cast<double>(iy);
        df::interpolate_y(*cache, static_cast<double>(in_cell_y) / static_cast<double>(cellHeight));
        for (int ix = 0; ix < cellWidth; ++ix) {
            ctx.inCellX = ix;
            ctx.x = x0 + static_cast<double>(ix);
            df::interpolate_x(*cache, static_cast<double>(ix) / static_cast<double>(cellWidth));
            for (int iz = 0; iz < cellWidth; ++iz) {
                ctx.inCellZ = iz;
                ctx.z = z0 + static_cast<double>(iz);
                df::interpolate_z(*cache, static_cast<double>(iz) / static_cast<double>(cellWidth));
                dst[index++] = df::evaluate(*a, a->root, ctx);
            }
        }
    }
    df::stop_interpolation(*cache);
    unbind_cache_all_in_cell_arrays(*cache);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeEvaluateCell(
        JNIEnv* env, jclass /*cls*/,
        jlong handle, jlong cacheHandle,
        jdouble x0, jdouble yTop, jdouble z0,
        jint cellX, jint cellZ,
        jint cellWidth, jint cellHeight,
        jobjectArray cacheAllInCellValues,
        jdoubleArray out) {
    auto* a = arena_from(handle);
    if (!a) {
        lattice::jni::throw_illegal_state(env, "lattice density: null arena");
        return;
    }
    if (!out) {
        lattice::jni::throw_illegal_arg(env, "lattice density: null output array");
        return;
    }
    if (cellWidth <= 0 || cellHeight <= 0) return;

    const long long required = static_cast<long long>(cellWidth)
                             * static_cast<long long>(cellHeight)
                             * static_cast<long long>(cellWidth);
    auto* cache = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (cache) cache->clear();
    auto pinned_cache_arrays = cache
        ? (cacheAllInCellValues
                ? bind_cache_all_in_cell_arrays(env, *cache, cacheAllInCellValues)
                : bind_bound_cache_all_in_cell_arrays(env, *cache))
        : std::vector<PinnedDoubleArray>{};
    if (env->ExceptionCheck()) return;
    lattice::jni::CriticalDoubleArray buf{env, out};
    if (!buf) {
        if (cache) unbind_cache_all_in_cell_arrays(*cache);
        lattice::jni::throw_illegal_state(env, "lattice density: array critical lock failed");
        return;
    }
    if (static_cast<long long>(buf.size()) < required) {
        if (cache) unbind_cache_all_in_cell_arrays(*cache);
        lattice::jni::throw_illegal_arg(env, "lattice density: output array too small");
        return;
    }

    df::Context ctx{};
    ctx.cache = cache;
    ctx.cellX = static_cast<int>(cellX);
    ctx.cellZ = static_cast<int>(cellZ);
    ctx.cellWidth = static_cast<int>(cellWidth);
    ctx.cellHeight = static_cast<int>(cellHeight);

    double* dst = reinterpret_cast<double*>(buf.data());
    std::size_t index = 0;
    for (int iy = 0; iy < cellHeight; ++iy) {
        ctx.inCellY = cellHeight - 1 - iy;
        ctx.y = yTop - static_cast<double>(iy);
        for (int ix = 0; ix < cellWidth; ++ix) {
            ctx.inCellX = ix;
            ctx.x = x0 + static_cast<double>(ix);
            for (int iz = 0; iz < cellWidth; ++iz) {
                ctx.inCellZ = iz;
                ctx.z = z0 + static_cast<double>(iz);
                dst[index++] = df::evaluate(*a, a->root, ctx);
            }
        }
    }
    if (cache) unbind_cache_all_in_cell_arrays(*cache);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeEvaluateInterpolatedColumn(
        JNIEnv* env, jclass /*cls*/,
        jlong handle, jlong cacheHandle,
        jdouble x0, jdouble z0, jdouble yMin,
        jint cellX, jint firstCellZ,
        jint cellWidth, jint cellHeight,
        jint cellCountXZ, jint cellCountY,
        jobjectArray cacheAllInCellValues,
        jdoubleArray out) {
    auto* a = arena_from(handle);
    auto* cache = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!a || !cache) {
        lattice::jni::throw_illegal_state(env, "lattice density: null arena/cache");
        return;
    }
    if (!out) {
        lattice::jni::throw_illegal_arg(env, "lattice density: null column output array");
        return;
    }
    if (cellWidth <= 0 || cellHeight <= 0 || cellCountXZ <= 0 || cellCountY <= 0) return;

    const long long cell_value_count = static_cast<long long>(cellWidth)
                                     * static_cast<long long>(cellHeight)
                                     * static_cast<long long>(cellWidth);
    const long long required = static_cast<long long>(cellCountXZ)
                             * static_cast<long long>(cellCountY)
                             * cell_value_count;
    auto pinned_cache_arrays = cacheAllInCellValues
        ? bind_cache_all_in_cell_arrays(env, *cache, cacheAllInCellValues)
        : bind_bound_cache_all_in_cell_arrays(env, *cache);
    if (env->ExceptionCheck()) return;
    lattice::jni::CriticalDoubleArray buf{env, out};
    if (!buf) {
        unbind_cache_all_in_cell_arrays(*cache);
        lattice::jni::throw_illegal_state(env, "lattice density: column output pin failed");
        return;
    }
    if (static_cast<long long>(buf.size()) < required) {
        unbind_cache_all_in_cell_arrays(*cache);
        lattice::jni::throw_illegal_arg(env, "lattice density: column output array too small");
        return;
    }

    df::Context ctx{};
    ctx.cache = cache;
    ctx.cellX = static_cast<int>(cellX);
    ctx.cellWidth = static_cast<int>(cellWidth);
    ctx.cellHeight = static_cast<int>(cellHeight);

    double* dst = reinterpret_cast<double*>(buf.data());
    for (int lz = 0; lz < cellCountXZ; ++lz) {
        ctx.cellZ = static_cast<int>(firstCellZ + lz);
        const double cell_z0 = z0 + static_cast<double>(lz * cellWidth);
        for (int ly = 0; ly < cellCountY; ++ly) {
            df::start_interpolation(*cache);
            df::on_sampled_cell_corners(*cache, ly, lz);
            const double y_top = yMin + static_cast<double>(ly * cellHeight + cellHeight - 1);
            const std::size_t base = (static_cast<std::size_t>(lz) * static_cast<std::size_t>(cellCountY)
                                   + static_cast<std::size_t>(ly))
                                  * static_cast<std::size_t>(cell_value_count);
            std::size_t index = base;
            for (int iy = 0; iy < cellHeight; ++iy) {
                const int in_cell_y = cellHeight - 1 - iy;
                ctx.inCellY = in_cell_y;
                ctx.y = y_top - static_cast<double>(iy);
                df::interpolate_y(*cache, static_cast<double>(in_cell_y) / static_cast<double>(cellHeight));
                for (int ix = 0; ix < cellWidth; ++ix) {
                    ctx.inCellX = ix;
                    ctx.x = x0 + static_cast<double>(ix);
                    df::interpolate_x(*cache, static_cast<double>(ix) / static_cast<double>(cellWidth));
                    for (int iz = 0; iz < cellWidth; ++iz) {
                        ctx.inCellZ = iz;
                        ctx.z = cell_z0 + static_cast<double>(iz);
                        df::interpolate_z(*cache, static_cast<double>(iz) / static_cast<double>(cellWidth));
                        dst[index++] = df::evaluate(*a, a->root, ctx);
                    }
                }
            }
            df::stop_interpolation(*cache);
        }
    }
    unbind_cache_all_in_cell_arrays(*cache);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeEvaluateInterpolatedColumns(
        JNIEnv* env, jclass /*cls*/,
        jlongArray handles, jlongArray cacheHandles, jint count,
        jdouble x0, jdouble z0, jdouble yMin,
        jint cellX, jint firstCellZ,
        jint cellWidth, jint cellHeight,
        jint cellCountXZ, jint cellCountY,
        jobjectArray out) {
    if (!handles || !cacheHandles || !out) {
        lattice::jni::throw_illegal_arg(env, "lattice density: null column batch arrays");
        return;
    }
    if (count <= 0 || cellWidth <= 0 || cellHeight <= 0 || cellCountXZ <= 0 || cellCountY <= 0) return;
    const jsize handle_count = env->GetArrayLength(handles);
    const jsize cache_count = env->GetArrayLength(cacheHandles);
    const jsize out_count = env->GetArrayLength(out);
    if (handle_count < count || cache_count < count || out_count < count) {
        lattice::jni::throw_illegal_arg(env, "lattice density: column batch arrays too small");
        return;
    }

    jlong* handle_data = env->GetLongArrayElements(handles, nullptr);
    if (!handle_data) return;
    jlong* cache_data = env->GetLongArrayElements(cacheHandles, nullptr);
    if (!cache_data) {
        env->ReleaseLongArrayElements(handles, handle_data, JNI_ABORT);
        return;
    }

    const long long cell_value_count = static_cast<long long>(cellWidth)
                                     * static_cast<long long>(cellHeight)
                                     * static_cast<long long>(cellWidth);
    const long long required = static_cast<long long>(cellCountXZ)
                             * static_cast<long long>(cellCountY)
                             * cell_value_count;

    for (jsize i = 0; i < count; ++i) {
        auto* a = arena_from(handle_data[i]);
        auto* cache = reinterpret_cast<df::CacheState*>(cache_data[i]);
        if (!a || !cache) {
            env->ReleaseLongArrayElements(cacheHandles, cache_data, JNI_ABORT);
            env->ReleaseLongArrayElements(handles, handle_data, JNI_ABORT);
            lattice::jni::throw_illegal_state(env, "lattice density: null arena/cache in column batch");
            return;
        }
        auto* out_row = static_cast<jdoubleArray>(env->GetObjectArrayElement(out, i));
        if (!out_row) {
            env->ReleaseLongArrayElements(cacheHandles, cache_data, JNI_ABORT);
            env->ReleaseLongArrayElements(handles, handle_data, JNI_ABORT);
            lattice::jni::throw_illegal_arg(env, "lattice density: null column batch output row");
            return;
        }
        auto pinned_cache_arrays = bind_bound_cache_all_in_cell_arrays(env, *cache);
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(out_row);
            env->ReleaseLongArrayElements(cacheHandles, cache_data, JNI_ABORT);
            env->ReleaseLongArrayElements(handles, handle_data, JNI_ABORT);
            return;
        }
        lattice::jni::CriticalDoubleArray buf{env, out_row};
        env->DeleteLocalRef(out_row);
        if (!buf) {
            unbind_cache_all_in_cell_arrays(*cache);
            env->ReleaseLongArrayElements(cacheHandles, cache_data, JNI_ABORT);
            env->ReleaseLongArrayElements(handles, handle_data, JNI_ABORT);
            lattice::jni::throw_illegal_state(env, "lattice density: column batch output pin failed");
            return;
        }
        if (static_cast<long long>(buf.size()) < required) {
            unbind_cache_all_in_cell_arrays(*cache);
            env->ReleaseLongArrayElements(cacheHandles, cache_data, JNI_ABORT);
            env->ReleaseLongArrayElements(handles, handle_data, JNI_ABORT);
            lattice::jni::throw_illegal_arg(env, "lattice density: column batch output row too small");
            return;
        }

        df::Context ctx{};
        ctx.cache = cache;
        ctx.cellX = static_cast<int>(cellX);
        ctx.cellWidth = static_cast<int>(cellWidth);
        ctx.cellHeight = static_cast<int>(cellHeight);

        double* dst = reinterpret_cast<double*>(buf.data());
        for (int lz = 0; lz < cellCountXZ; ++lz) {
            ctx.cellZ = static_cast<int>(firstCellZ + lz);
            const double cell_z0 = z0 + static_cast<double>(lz * cellWidth);
            for (int ly = 0; ly < cellCountY; ++ly) {
                df::start_interpolation(*cache);
                df::on_sampled_cell_corners(*cache, ly, lz);
                const double y_top = yMin + static_cast<double>(ly * cellHeight + cellHeight - 1);
                const std::size_t base = (static_cast<std::size_t>(lz) * static_cast<std::size_t>(cellCountY)
                                       + static_cast<std::size_t>(ly))
                                      * static_cast<std::size_t>(cell_value_count);
                std::size_t index = base;
                for (int iy = 0; iy < cellHeight; ++iy) {
                    const int in_cell_y = cellHeight - 1 - iy;
                    ctx.inCellY = in_cell_y;
                    ctx.y = y_top - static_cast<double>(iy);
                    df::interpolate_y(*cache, static_cast<double>(in_cell_y) / static_cast<double>(cellHeight));
                    for (int ix = 0; ix < cellWidth; ++ix) {
                        ctx.inCellX = ix;
                        ctx.x = x0 + static_cast<double>(ix);
                        df::interpolate_x(*cache, static_cast<double>(ix) / static_cast<double>(cellWidth));
                        for (int iz = 0; iz < cellWidth; ++iz) {
                            ctx.inCellZ = iz;
                            ctx.z = cell_z0 + static_cast<double>(iz);
                            df::interpolate_z(*cache, static_cast<double>(iz) / static_cast<double>(cellWidth));
                            dst[index++] = df::evaluate(*a, a->root, ctx);
                        }
                    }
                }
                df::stop_interpolation(*cache);
            }
        }
        unbind_cache_all_in_cell_arrays(*cache);
    }

    env->ReleaseLongArrayElements(cacheHandles, cache_data, JNI_ABORT);
    env->ReleaseLongArrayElements(handles, handle_data, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeCacheSlot(
        JNIEnv*, jclass, jlong handle, jint nodeRef) {
    auto* a = arena_from(handle);
    if (!a || nodeRef < 0 || static_cast<std::size_t>(nodeRef) >= a->nodes.size()) return -1;
    return a->nodes[static_cast<std::size_t>(nodeRef)].cache_slot_id;
}

// ---- Worldgen-10: Spline support -----------------------------------------

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddFixedFloatSpline(
        JNIEnv*, jclass /*cls*/, jlong handle, jfloat value) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Spline s{};
    s.kind = df::SplineKind::kFixedFloat;
    s.fixed_value = static_cast<float>(value);
    return static_cast<jint>(a->push_spline(s));
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddImplSpline(
        JNIEnv* env, jclass /*cls*/, jlong handle,
        jint locationFunctionNodeRef,
        jfloatArray locations,
        jfloatArray derivatives,
        jintArray   valueSplineRefs) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    if (!locations || !derivatives || !valueSplineRefs) {
        lattice::jni::throw_illegal_arg(env, "lattice spline: null array");
        return -1;
    }
    const jsize n_loc = env->GetArrayLength(locations);
    const jsize n_der = env->GetArrayLength(derivatives);
    const jsize n_val = env->GetArrayLength(valueSplineRefs);
    if (n_loc != n_der || n_loc != n_val) {
        lattice::jni::throw_illegal_arg(env, "lattice spline: array length mismatch");
        return -1;
    }
    if (n_loc <= 0) {
        lattice::jni::throw_illegal_arg(env, "lattice spline: empty breakpoints");
        return -1;
    }

    // Reserve breakpoint storage and copy in. We don't use the
    // critical helpers here because we want a small, well-defined
    // copy out of the JVM array into our arena's vector — this only
    // happens at arena-build time, not on the hot path.
    const int start = a->reserve_spline_breakpoints(static_cast<int>(n_loc));
    df::SplineBreakpoint* bps = a->spline_breakpoints.data() + start;

    {
        lattice::jni::CriticalFloatArray loc_g{env, locations};
        lattice::jni::CriticalFloatArray der_g{env, derivatives};
        lattice::jni::CriticalIntArray   val_g{env, valueSplineRefs};
        if (!loc_g || !der_g || !val_g) {
            lattice::jni::throw_illegal_state(env, "lattice spline: critical lock failed");
            return -1;
        }
        for (jsize i = 0; i < n_loc; ++i) {
            bps[i].location   = loc_g.data()[i];
            bps[i].derivative = der_g.data()[i];
            bps[i].value      = static_cast<df::SplineRef>(val_g.data()[i]);
        }
    }

    df::Spline s{};
    s.kind              = df::SplineKind::kImpl;
    s.location_function = locationFunctionNodeRef;
    s.breakpoints_start = start;
    s.breakpoint_count  = static_cast<int>(n_loc);
    return static_cast<jint>(a->push_spline(s));
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddSpline(
        JNIEnv*, jclass /*cls*/, jlong handle, jint splineRef) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kSpline;
    n.i0   = splineRef;
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddFindTopSurface(
        JNIEnv*, jclass /*cls*/, jlong handle,
        jint densityNodeRef, jint upperBoundNodeRef,
        jint lowerBound, jint cellHeight) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kFindTopSurface;
    n.a    = static_cast<df::NodeRef>(densityNodeRef);
    n.b    = static_cast<df::NodeRef>(upperBoundNodeRef);
    n.i0   = lowerBound;
    n.i1   = cellHeight;
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddInterpolatedNoise(
        JNIEnv*, jclass /*cls*/, jlong handle, jlong samplerHandle) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kInterpolatedNoise;
    // The sampler is at offset 0 of NativeInterpolatedNoise's Bundle.
    n.interp_noise_ptr = reinterpret_cast<const lattice::world::gen::noise::InterpolatedNoiseSampler*>(samplerHandle);
    return push_node(a, n);
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeAddBeardifier(
        JNIEnv*, jclass /*cls*/, jlong handle, jlong beardifierHandle) {
    auto* a = arena_from(handle);
    if (!a || beardifierHandle == 0) return -1;
    df::Node n{};
    n.kind = df::NodeKind::kBeardifier;
    n.beardifier_ptr = reinterpret_cast<const bf::BeardifierData*>(beardifierHandle);
    return push_node(a, n);
}

// ---- Worldgen-13: Interpolator (DensityInterpolator) operations -------

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeNumInterpolatorSlots(
        JNIEnv*, jclass /*cls*/, jlong handle) {
    auto* a = arena_from(handle);
    if (!a) return 0;
    return static_cast<jint>(a->num_interpolator_slots);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativePrepareInterpolators(
        JNIEnv*, jclass /*cls*/, jlong cacheHandle,
        jint horizontalCellCount, jint verticalCellCount) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    c->prepare_interpolators(static_cast<int>(horizontalCellCount),
                             static_cast<int>(verticalCellCount));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeStartInterpolation(
        JNIEnv*, jclass /*cls*/, jlong cacheHandle) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    df::start_interpolation(*c);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeStopInterpolation(
        JNIEnv*, jclass /*cls*/, jlong cacheHandle) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    df::stop_interpolation(*c);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeSetStartDensity(
        JNIEnv*, jclass /*cls*/, jlong cacheHandle,
        jint slot, jint cellZ, jint cellY, jdouble value) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    df::set_start_density(*c, static_cast<int>(slot),
                          static_cast<int>(cellZ),
                          static_cast<int>(cellY),
                          static_cast<double>(value));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeSetEndDensity(
        JNIEnv*, jclass /*cls*/, jlong cacheHandle,
        jint slot, jint cellZ, jint cellY, jdouble value) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    df::set_end_density(*c, static_cast<int>(slot),
                        static_cast<int>(cellZ),
                        static_cast<int>(cellY),
                        static_cast<double>(value));
}

/// Bulk fill: write a row of (vCC+1) doubles into either start- or
/// end-density buffer for one (slot, cellZ). Avoids one JNI call per
/// (slot, cellY, cellZ) triple, which is the hot path during chunk
/// fill (typically ~1024 calls per chunk per interpolator otherwise).
JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeSetDensityRow(
        JNIEnv* env, jclass /*cls*/, jlong cacheHandle,
        jint slot, jint cellZ, jboolean toEndBuffer,
        jdoubleArray values) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    if (slot < 0 || slot >= static_cast<jint>(c->interpolators.size())) return;
    if (!values) {
        lattice::jni::throw_illegal_arg(env, "lattice df: null density row");
        return;
    }
    auto& it = c->interpolators[static_cast<std::size_t>(slot)];
    auto* dst_buf = (toEndBuffer == JNI_TRUE)
        ? &it.end_density_buffer
        : &it.start_density_buffer;

    const int vCC = c->vertical_cell_count;
    const std::size_t row_size = static_cast<std::size_t>(vCC + 1);
    const std::size_t row_start =
        static_cast<std::size_t>(cellZ) * row_size;
    if (row_start + row_size > dst_buf->size()) {
        lattice::jni::throw_illegal_arg(env, "lattice df: density row out of range");
        return;
    }

    lattice::jni::CriticalDoubleArray src{env, values};
    if (!src) {
        lattice::jni::throw_illegal_state(env, "lattice df: density row pin failed");
        return;
    }
    if (src.size() < row_size) {
        lattice::jni::throw_illegal_arg(env, "lattice df: density row too short");
        return;
    }
    for (std::size_t i = 0; i < row_size; ++i) {
        (*dst_buf)[row_start + i] = src.data()[i];
    }
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeSetInterpolatorColumn(
        JNIEnv* env, jclass /*cls*/, jlong cacheHandle,
        jint slot, jobjectArray startSlice, jobjectArray endSlice,
        jint zRows, jint yRows) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    if (slot < 0 || slot >= static_cast<jint>(c->interpolators.size())) return;
    if (!startSlice || !endSlice) {
        lattice::jni::throw_illegal_arg(env, "lattice df: null interpolator slice");
        return;
    }
    if (zRows <= 0 || yRows <= 0) return;
    if (env->GetArrayLength(startSlice) < zRows || env->GetArrayLength(endSlice) < zRows) {
        lattice::jni::throw_illegal_arg(env, "lattice df: interpolator slice z rows too short");
        return;
    }

    auto& it = c->interpolators[static_cast<std::size_t>(slot)];
    const std::size_t required = static_cast<std::size_t>(zRows) * static_cast<std::size_t>(yRows);
    if (it.start_density_buffer.size() < required || it.end_density_buffer.size() < required) {
        lattice::jni::throw_illegal_arg(env, "lattice df: interpolator native buffers too short");
        return;
    }

    for (jint z = 0; z < zRows; ++z) {
        auto* startRow = static_cast<jdoubleArray>(env->GetObjectArrayElement(startSlice, z));
        auto* endRow = static_cast<jdoubleArray>(env->GetObjectArrayElement(endSlice, z));
        if (!startRow || !endRow) {
            if (startRow) env->DeleteLocalRef(startRow);
            if (endRow) env->DeleteLocalRef(endRow);
            lattice::jni::throw_illegal_arg(env, "lattice df: null interpolator slice row");
            return;
        }
        if (env->GetArrayLength(startRow) < yRows || env->GetArrayLength(endRow) < yRows) {
            env->DeleteLocalRef(startRow);
            env->DeleteLocalRef(endRow);
            lattice::jni::throw_illegal_arg(env, "lattice df: interpolator slice y row too short");
            return;
        }

        const std::size_t offset = static_cast<std::size_t>(z) * static_cast<std::size_t>(yRows);
        env->GetDoubleArrayRegion(startRow, 0, yRows, it.start_density_buffer.data() + offset);
        env->GetDoubleArrayRegion(endRow, 0, yRows, it.end_density_buffer.data() + offset);
        env->DeleteLocalRef(startRow);
        env->DeleteLocalRef(endRow);
        if (env->ExceptionCheck()) return;
    }
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeSetInterpolatorColumnFlat(
        JNIEnv* env, jclass /*cls*/, jlong cacheHandle,
        jint slot, jdoubleArray startSlice, jdoubleArray endSlice,
        jint zRows, jint yRows) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    if (slot < 0 || slot >= static_cast<jint>(c->interpolators.size())) return;
    if (!startSlice || !endSlice) {
        lattice::jni::throw_illegal_arg(env, "lattice df: null flat interpolator slice");
        return;
    }
    if (zRows <= 0 || yRows <= 0) return;

    auto& it = c->interpolators[static_cast<std::size_t>(slot)];
    const std::size_t required = static_cast<std::size_t>(zRows) * static_cast<std::size_t>(yRows);
    if (it.start_density_buffer.size() < required || it.end_density_buffer.size() < required) {
        lattice::jni::throw_illegal_arg(env, "lattice df: interpolator native buffers too short");
        return;
    }

    lattice::jni::CriticalDoubleArray start{env, startSlice};
    lattice::jni::CriticalDoubleArray end{env, endSlice};
    if (!start || !end) {
        lattice::jni::throw_illegal_state(env, "lattice df: flat interpolator slice pin failed");
        return;
    }
    if (start.size() < required || end.size() < required) {
        lattice::jni::throw_illegal_arg(env, "lattice df: flat interpolator slice too short");
        return;
    }

    std::copy_n(start.data(), required, it.start_density_buffer.data());
    std::copy_n(end.data(), required, it.end_density_buffer.data());
    start.release_ro();
    end.release_ro();
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeSetInterpolatorColumnsFlat(
        JNIEnv* env, jclass /*cls*/, jlong cacheHandle,
        jintArray slots, jobjectArray startSlices, jobjectArray endSlices,
        jint zRows, jint yRows) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    if (!slots || !startSlices || !endSlices) {
        lattice::jni::throw_illegal_arg(env, "lattice df: null interpolator column arrays");
        return;
    }
    if (zRows <= 0 || yRows <= 0) return;

    const jsize count = env->GetArrayLength(slots);
    if (env->GetArrayLength(startSlices) < count || env->GetArrayLength(endSlices) < count) {
        lattice::jni::throw_illegal_arg(env, "lattice df: interpolator column array length mismatch");
        return;
    }
    const std::size_t required = static_cast<std::size_t>(zRows) * static_cast<std::size_t>(yRows);

    lattice::jni::CriticalIntArray slot_data{env, slots};
    if (!slot_data) {
        lattice::jni::throw_illegal_state(env, "lattice df: interpolator slots pin failed");
        return;
    }

    for (jsize i = 0; i < count; ++i) {
        const jint slot = slot_data.data()[i];
        if (slot < 0 || slot >= static_cast<jint>(c->interpolators.size())) continue;
        auto* startArray = static_cast<jdoubleArray>(env->GetObjectArrayElement(startSlices, i));
        auto* endArray = static_cast<jdoubleArray>(env->GetObjectArrayElement(endSlices, i));
        if (!startArray || !endArray) {
            if (startArray) env->DeleteLocalRef(startArray);
            if (endArray) env->DeleteLocalRef(endArray);
            lattice::jni::throw_illegal_arg(env, "lattice df: null flat interpolator column");
            return;
        }

        auto& it = c->interpolators[static_cast<std::size_t>(slot)];
        if (it.start_density_buffer.size() < required || it.end_density_buffer.size() < required) {
            env->DeleteLocalRef(startArray);
            env->DeleteLocalRef(endArray);
            lattice::jni::throw_illegal_arg(env, "lattice df: interpolator native buffers too short");
            return;
        }

        lattice::jni::CriticalDoubleArray start{env, startArray};
        lattice::jni::CriticalDoubleArray end{env, endArray};
        if (!start || !end) {
            env->DeleteLocalRef(startArray);
            env->DeleteLocalRef(endArray);
            lattice::jni::throw_illegal_state(env, "lattice df: flat interpolator column pin failed");
            return;
        }
        if (start.size() < required || end.size() < required) {
            env->DeleteLocalRef(startArray);
            env->DeleteLocalRef(endArray);
            lattice::jni::throw_illegal_arg(env, "lattice df: flat interpolator column too short");
            return;
        }

        std::copy_n(start.data(), required, it.start_density_buffer.data());
        std::copy_n(end.data(), required, it.end_density_buffer.data());
        start.release_ro();
        end.release_ro();
        env->DeleteLocalRef(startArray);
        env->DeleteLocalRef(endArray);
        if (env->ExceptionCheck()) return;
    }
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeBindInterpolatorColumnsFlat(
        JNIEnv* env, jclass /*cls*/, jlong cacheHandle,
        jintArray slots, jobjectArray startSlices, jobjectArray endSlices) {
    auto* cache = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!cache) return;
    if (!slots || !startSlices || !endSlices) {
        lattice::jni::throw_illegal_arg(env, "lattice df: null interpolator binding arrays");
        return;
    }

    const jsize count = env->GetArrayLength(slots);
    if (env->GetArrayLength(startSlices) < count || env->GetArrayLength(endSlices) < count) {
        lattice::jni::throw_illegal_arg(env, "lattice df: interpolator binding length mismatch");
        return;
    }

    lattice::jni::CriticalIntArray slot_data{env, slots};
    if (!slot_data) {
        lattice::jni::throw_illegal_state(env, "lattice df: interpolator binding slots pin failed");
        return;
    }

    BoundInterpolatorColumns next;
    next.slots.reserve(static_cast<std::size_t>(count));
    next.starts.reserve(static_cast<std::size_t>(count));
    next.ends.reserve(static_cast<std::size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto* startArray = static_cast<jdoubleArray>(env->GetObjectArrayElement(startSlices, i));
        auto* endArray = static_cast<jdoubleArray>(env->GetObjectArrayElement(endSlices, i));
        if (!startArray || !endArray) {
            if (startArray) env->DeleteLocalRef(startArray);
            if (endArray) env->DeleteLocalRef(endArray);
            delete_global_refs(env, next);
            lattice::jni::throw_illegal_arg(env, "lattice df: null interpolator binding column");
            return;
        }
        auto* startGlobal = static_cast<jdoubleArray>(env->NewGlobalRef(startArray));
        auto* endGlobal = static_cast<jdoubleArray>(env->NewGlobalRef(endArray));
        env->DeleteLocalRef(startArray);
        env->DeleteLocalRef(endArray);
        if (!startGlobal || !endGlobal) {
            if (startGlobal) env->DeleteGlobalRef(startGlobal);
            if (endGlobal) env->DeleteGlobalRef(endGlobal);
            delete_global_refs(env, next);
            lattice::jni::throw_oom(env, "lattice density: interpolator column global ref");
            return;
        }
        next.slots.push_back(slot_data.data()[i]);
        next.starts.push_back(startGlobal);
        next.ends.push_back(endGlobal);
    }

    std::lock_guard<std::mutex> lock(g_bindings_mutex);
    auto& current = g_interpolator_bindings[cache];
    delete_global_refs(env, current);
    current = std::move(next);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeSyncBoundInterpolatorColumnsFlat(
        JNIEnv* env, jclass /*cls*/, jlong cacheHandle,
        jint zRows, jint yRows) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    if (zRows <= 0 || yRows <= 0) return;

    std::lock_guard<std::mutex> lock(g_bindings_mutex);
    auto found = g_interpolator_bindings.find(c);
    if (found == g_interpolator_bindings.end()) return;
    const BoundInterpolatorColumns& binding = found->second;
    const std::size_t required = static_cast<std::size_t>(zRows) * static_cast<std::size_t>(yRows);
    for (std::size_t i = 0; i < binding.slots.size(); ++i) {
        const jint slot = binding.slots[i];
        if (slot < 0 || slot >= static_cast<jint>(c->interpolators.size())) continue;
        auto& it = c->interpolators[static_cast<std::size_t>(slot)];
        if (it.start_density_buffer.size() < required || it.end_density_buffer.size() < required) {
            lattice::jni::throw_illegal_arg(env, "lattice df: bound interpolator native buffers too short");
            return;
        }
        lattice::jni::CriticalDoubleArray start{env, binding.starts[i]};
        lattice::jni::CriticalDoubleArray end{env, binding.ends[i]};
        if (!start || !end) {
            lattice::jni::throw_illegal_state(env, "lattice df: bound interpolator column pin failed");
            return;
        }
        if (start.size() < required || end.size() < required) {
            lattice::jni::throw_illegal_arg(env, "lattice df: bound interpolator column too short");
            return;
        }
        std::copy_n(start.data(), required, it.start_density_buffer.data());
        std::copy_n(end.data(), required, it.end_density_buffer.data());
        start.release_ro();
        end.release_ro();
    }
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeSetInterpolatorColumnPacked(
        JNIEnv* env, jclass /*cls*/, jlong cacheHandle,
        jint slot, jdoubleArray packedSlices,
        jint zRows, jint yRows) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    if (slot < 0 || slot >= static_cast<jint>(c->interpolators.size())) return;
    if (!packedSlices) {
        lattice::jni::throw_illegal_arg(env, "lattice df: null packed interpolator slice");
        return;
    }
    if (zRows <= 0 || yRows <= 0) return;

    auto& it = c->interpolators[static_cast<std::size_t>(slot)];
    const std::size_t required = static_cast<std::size_t>(zRows) * static_cast<std::size_t>(yRows);
    if (it.start_density_buffer.size() < required || it.end_density_buffer.size() < required) {
        lattice::jni::throw_illegal_arg(env, "lattice df: interpolator native buffers too short");
        return;
    }

    lattice::jni::CriticalDoubleArray packed{env, packedSlices};
    if (!packed) {
        lattice::jni::throw_illegal_state(env, "lattice df: packed interpolator slice pin failed");
        return;
    }
    if (packed.size() < required * 2) {
        lattice::jni::throw_illegal_arg(env, "lattice df: packed interpolator slice too short");
        return;
    }

    const double* data = packed.data();
    std::copy_n(data, required, it.start_density_buffer.data());
    std::copy_n(data + required, required, it.end_density_buffer.data());
    packed.release_ro();
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeSwapBuffers(
        JNIEnv*, jclass /*cls*/, jlong cacheHandle) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    df::swap_buffers(*c);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeOnSampledCellCorners(
        JNIEnv*, jclass /*cls*/, jlong cacheHandle, jint cellY, jint cellZ) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    df::on_sampled_cell_corners(*c, static_cast<int>(cellY),
                                    static_cast<int>(cellZ));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeInterpolateY(
        JNIEnv*, jclass /*cls*/, jlong cacheHandle, jdouble deltaY) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    df::interpolate_y(*c, static_cast<double>(deltaY));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeInterpolateX(
        JNIEnv*, jclass /*cls*/, jlong cacheHandle, jdouble deltaX) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    df::interpolate_x(*c, static_cast<double>(deltaX));
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeInterpolateZ(
        JNIEnv*, jclass /*cls*/, jlong cacheHandle, jdouble deltaZ) {
    auto* c = reinterpret_cast<df::CacheState*>(cacheHandle);
    if (!c) return;
    df::interpolate_z(*c, static_cast<double>(deltaZ));
}

} // extern "C"
