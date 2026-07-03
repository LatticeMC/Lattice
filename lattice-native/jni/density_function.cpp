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

#include <new>

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

} // namespace

extern "C" {

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
        JNIEnv* /*env*/, jclass /*cls*/, jlong cacheHandle) {
    delete reinterpret_cast<df::CacheState*>(cacheHandle);
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
        jlong noiseHandle, jdouble scale) {
    auto* a = arena_from(handle);
    if (!a) return -1;
    df::Node n{};
    n.kind      = df::NodeKind::kShiftedNoise;
    n.a         = static_cast<df::NodeRef>(shiftX);
    n.b         = static_cast<df::NodeRef>(shiftY);
    n.c         = static_cast<df::NodeRef>(shiftZ);
    n.d0        = scale;
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
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeEvaluateInterpolatedCell(
        JNIEnv* env, jclass /*cls*/,
        jlong handle, jlong cacheHandle,
        jdouble x0, jdouble yTop, jdouble z0,
        jint cellX, jint cellZ,
        jint cellWidth, jint cellHeight,
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
    lattice::jni::CriticalDoubleArray buf{env, out};
    if (!buf) {
        lattice::jni::throw_illegal_state(env, "lattice density: array critical lock failed");
        return;
    }
    if (static_cast<long long>(buf.size()) < required) {
        lattice::jni::throw_illegal_arg(env, "lattice density: output array too small");
        return;
    }

    df::start_interpolation(*cache);
    df::on_sampled_cell_corners(*cache, 0, 0);

    df::Context ctx{};
    ctx.cache = cache;
    ctx.cellX = static_cast<int>(cellX);
    ctx.cellZ = static_cast<int>(cellZ);

    double* dst = reinterpret_cast<double*>(buf.data());
    std::size_t index = 0;
    for (int iy = 0; iy < cellHeight; ++iy) {
        const int in_cell_y = cellHeight - 1 - iy;
        ctx.y = yTop - static_cast<double>(iy);
        df::interpolate_y(*cache, static_cast<double>(in_cell_y) / static_cast<double>(cellHeight));
        for (int ix = 0; ix < cellWidth; ++ix) {
            ctx.x = x0 + static_cast<double>(ix);
            df::interpolate_x(*cache, static_cast<double>(ix) / static_cast<double>(cellWidth));
            for (int iz = 0; iz < cellWidth; ++iz) {
                ctx.z = z0 + static_cast<double>(iz);
                df::interpolate_z(*cache, static_cast<double>(iz) / static_cast<double>(cellWidth));
                dst[index++] = df::evaluate(*a, a->root, ctx);
            }
        }
    }
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeDensityFunction_nativeEvaluateCell(
        JNIEnv* env, jclass /*cls*/,
        jlong handle, jlong cacheHandle,
        jdouble x0, jdouble yTop, jdouble z0,
        jint cellX, jint cellZ,
        jint cellWidth, jint cellHeight,
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
    lattice::jni::CriticalDoubleArray buf{env, out};
    if (!buf) {
        lattice::jni::throw_illegal_state(env, "lattice density: array critical lock failed");
        return;
    }
    if (static_cast<long long>(buf.size()) < required) {
        lattice::jni::throw_illegal_arg(env, "lattice density: output array too small");
        return;
    }

    df::Context ctx{};
    ctx.cache = reinterpret_cast<df::CacheState*>(cacheHandle);
    ctx.cellX = static_cast<int>(cellX);
    ctx.cellZ = static_cast<int>(cellZ);

    double* dst = reinterpret_cast<double*>(buf.data());
    std::size_t index = 0;
    for (int iy = 0; iy < cellHeight; ++iy) {
        ctx.y = yTop - static_cast<double>(iy);
        for (int ix = 0; ix < cellWidth; ++ix) {
            ctx.x = x0 + static_cast<double>(ix);
            for (int iz = 0; iz < cellWidth; ++iz) {
                ctx.z = z0 + static_cast<double>(iz);
                dst[index++] = df::evaluate(*a, a->root, ctx);
            }
        }
    }
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
