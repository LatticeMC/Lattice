// Recursive evaluator for the DensityFunction tagged-union tree.
//
// Reference: Mojang's DensityFunctionTypes (class_6916) factories +
// the per-class apply() impls. We implement the same operations one
// node-kind at a time. For nodes that need a noise sampler, the Java
// side has already constructed a DoublePerlinNoiseSampler and pinned
// its address inside the corresponding Node.
//
// Bit-exactness target: ±1 ULP of vanilla Java output, same as the
// other Worldgen modules. We avoid FMA inline and don't rely on
// `-ffast-math` reassociation.

#include "world/gen/densityfunction/density_function.hpp"

#include <algorithm>
#include <cmath>

#include "lattice/dispatch.hpp"
#include "world/gen/noise/interpolated_noise.hpp"

namespace lattice::world::gen::densityfunction {

#if !defined(LATTICE_HAS_DENSITY_AVX2)
bool evaluate_y_column_avx2(const NodeArena&, NodeRef,
                            double, double, double, double,
                            int, int, int,
                            CacheState*, double*) noexcept {
    return false;
}
#endif

namespace {

inline double eval_const(const Node& n) noexcept { return n.d0; }

inline double clamp_d(double v, double lo, double hi) noexcept {
    return std::max(lo, std::min(hi, v));
}

inline int floor_to_int(double v) noexcept {
    return static_cast<int>(std::floor(v));
}

// Vanilla's `method_40484 mapRange` (linearly remap from
// [fromMin, fromMax] to [toMin, toMax], NOT clamped):
//
//   t = (input - fromMin) / (fromMax - fromMin)
//   return toMin + t * (toMax - toMin)
inline double map_range(double input, double from_min, double from_max,
                        double to_min, double to_max) noexcept {
    const double t = (input - from_min) / (from_max - from_min);
    return to_min + t * (to_max - to_min);
}

// `method_40488 lerp` is the same as our lerp helper but kept as a
// named function so the caller can grep for it.
inline double lerp(double t, double a, double b) noexcept {
    return a + t * (b - a);
}

inline float lerp_f(float t, float a, float b) noexcept {
    return a + t * (b - a);
}

// Vanilla's "y-clamped gradient": linearly interpolate `fromValue` at
// `fromY` to `toValue` at `toY`, clamping the y at both ends. Useful
// for "depth bias" terms in the noise router.
inline double y_clamped_gradient(int from_y, int to_y,
                                 double from_v, double to_v,
                                 double y) noexcept {
    const double dy_total = static_cast<double>(to_y - from_y);
    if (dy_total == 0.0) return (from_v + to_v) * 0.5;
    const double y_clamped = clamp_d(y, static_cast<double>(from_y),
                                        static_cast<double>(to_y));
    const double t = (y_clamped - from_y) / dy_total;
    return lerp(t, from_v, to_v);
}

inline double half_negative(double v) noexcept {
    // Mojang's transform: returns v if v >= 0 else v * 0.5.
    return (v < 0.0) ? v * 0.5 : v;
}

inline double quarter_negative(double v) noexcept {
    return (v < 0.0) ? v * 0.25 : v;
}

inline double squeeze(double v) noexcept {
    // `Squeeze`: clamp to [-1, 1], then return v/2 - v*v*v/24 (vanilla's
    // smoothing curve used for clamping noise to a fixed range without
    // hard saturation).
    const double clamped = clamp_d(v, -1.0, 1.0);
    return clamped * 0.5 - clamped * clamped * clamped / 24.0;
}

bool evaluate_y_column_fast(const NodeArena& arena, NodeRef root,
                            const Context& base,
                            double y0, double dy, int ny,
                            double* out) {
    if (root < 0 || root >= static_cast<NodeRef>(arena.nodes.size()) || !out) return false;
    const Node& n = arena.nodes[root];

    auto eval_child = [&](NodeRef child, double* dst) {
        return evaluate_y_column_fast(arena, child, base, y0, dy, ny, dst);
    };

    switch (n.kind) {
        case NodeKind::kConstant:
            for (int i = 0; i < ny; ++i) out[i] = n.d0;
            return true;

        case NodeKind::kYClampedGradient:
            for (int i = 0; i < ny; ++i) {
                out[i] = y_clamped_gradient(n.i0, n.i1, n.d0, n.d1, y0 + static_cast<double>(i) * dy);
            }
            return true;

        case NodeKind::kBlendAlpha:
            for (int i = 0; i < ny; ++i) out[i] = 1.0;
            return true;

        case NodeKind::kBlendOffset:
            for (int i = 0; i < ny; ++i) out[i] = 0.0;
            return true;

        case NodeKind::kNoise:
            if (!n.noise_ptr) {
                for (int i = 0; i < ny; ++i) out[i] = 0.0;
                return true;
            }
            for (int i = 0; i < ny; ++i) {
                const double y = y0 + static_cast<double>(i) * dy;
                out[i] = noise::sample(*n.noise_ptr, base.x * n.d0, y * n.d1, base.z * n.d0);
            }
            return true;

        case NodeKind::kShiftA: {
            const double value = n.noise_ptr ? noise::sample(*n.noise_ptr, base.x * 0.25, 0.0, base.z * 0.25) * 4.0 : 0.0;
            for (int i = 0; i < ny; ++i) out[i] = value;
            return true;
        }

        case NodeKind::kShiftB: {
            const double value = n.noise_ptr ? noise::sample(*n.noise_ptr, base.z * 0.25, base.x * 0.25, 0.0) * 4.0 : 0.0;
            for (int i = 0; i < ny; ++i) out[i] = value;
            return true;
        }

        case NodeKind::kShift:
            if (!n.noise_ptr) {
                for (int i = 0; i < ny; ++i) out[i] = 0.0;
                return true;
            }
            for (int i = 0; i < ny; ++i) {
                const double y = y0 + static_cast<double>(i) * dy;
                out[i] = noise::sample(*n.noise_ptr, base.x * 0.25, y * 0.25, base.z * 0.25) * 4.0;
            }
            return true;

        case NodeKind::kAbs:
        case NodeKind::kSquare:
        case NodeKind::kCube:
        case NodeKind::kHalfNegative:
        case NodeKind::kQuarterNegative:
        case NodeKind::kInvert:
        case NodeKind::kSqueeze:
        case NodeKind::kClamp:
        case NodeKind::kBlendDensity: {
            std::vector<double> values(static_cast<std::size_t>(ny));
            if (!eval_child(n.a, values.data())) return false;
            for (int i = 0; i < ny; ++i) {
                const double v = values[static_cast<std::size_t>(i)];
                switch (n.kind) {
                    case NodeKind::kAbs: out[i] = std::abs(v); break;
                    case NodeKind::kSquare: out[i] = v * v; break;
                    case NodeKind::kCube: out[i] = v * v * v; break;
                    case NodeKind::kHalfNegative: out[i] = half_negative(v); break;
                    case NodeKind::kQuarterNegative: out[i] = quarter_negative(v); break;
                    case NodeKind::kInvert: out[i] = 1.0 / v; break;
                    case NodeKind::kSqueeze: out[i] = squeeze(v); break;
                    case NodeKind::kClamp: out[i] = clamp_d(v, n.d0, n.d1); break;
                    case NodeKind::kBlendDensity: out[i] = v; break;
                    default: return false;
                }
            }
            return true;
        }

        case NodeKind::kAdd:
        case NodeKind::kMul:
        case NodeKind::kMin:
        case NodeKind::kMax: {
            std::vector<double> left(static_cast<std::size_t>(ny));
            if (!eval_child(n.a, left.data())) return false;
            if (n.a == n.b) {
                for (int i = 0; i < ny; ++i) {
                    const double v = left[static_cast<std::size_t>(i)];
                    switch (n.kind) {
                        case NodeKind::kAdd: out[i] = v + v; break;
                        case NodeKind::kMul: out[i] = v * v; break;
                        case NodeKind::kMin:
                        case NodeKind::kMax: out[i] = v; break;
                        default: return false;
                    }
                }
                return true;
            }
            std::vector<double> right(static_cast<std::size_t>(ny));
            if (!eval_child(n.b, right.data())) return false;
            for (int i = 0; i < ny; ++i) {
                const double a = left[static_cast<std::size_t>(i)];
                const double b = right[static_cast<std::size_t>(i)];
                switch (n.kind) {
                    case NodeKind::kAdd: out[i] = a + b; break;
                    case NodeKind::kMul: out[i] = a == 0.0 ? 0.0 : a * b; break;
                    case NodeKind::kMin: out[i] = std::min(a, b); break;
                    case NodeKind::kMax: out[i] = std::max(a, b); break;
                    default: return false;
                }
            }
            return true;
        }

        case NodeKind::kMapRange: {
            std::vector<double> input(static_cast<std::size_t>(ny));
            if (!eval_child(n.a, input.data())) return false;
            for (int i = 0; i < ny; ++i) out[i] = map_range(input[static_cast<std::size_t>(i)], n.d0, n.d1, n.d2, n.d3);
            return true;
        }

        case NodeKind::kRangeChoice: {
            std::vector<double> input(static_cast<std::size_t>(ny));
            std::vector<double> in_values(static_cast<std::size_t>(ny));
            std::vector<double> out_values(static_cast<std::size_t>(ny));
            if (!eval_child(n.a, input.data())) return false;
            if (!eval_child(n.b, in_values.data())) return false;
            if (!eval_child(n.c, out_values.data())) return false;
            for (int i = 0; i < ny; ++i) {
                const double value = input[static_cast<std::size_t>(i)];
                out[i] = value >= n.d0 && value < n.d1 ? in_values[static_cast<std::size_t>(i)] : out_values[static_cast<std::size_t>(i)];
            }
            return true;
        }

        case NodeKind::kShiftedNoise: {
            if (!n.noise_ptr) {
                for (int i = 0; i < ny; ++i) out[i] = 0.0;
                return true;
            }
            std::vector<double> sx(static_cast<std::size_t>(ny));
            std::vector<double> sy(static_cast<std::size_t>(ny));
            std::vector<double> sz(static_cast<std::size_t>(ny));
            if (!eval_child(n.a, sx.data())) return false;
            if (!eval_child(n.b, sy.data())) return false;
            if (!eval_child(n.c, sz.data())) return false;
            for (int i = 0; i < ny; ++i) {
                const double y = y0 + static_cast<double>(i) * dy;
                out[i] = noise::sample(*n.noise_ptr,
                                       base.x * n.d0 + sx[static_cast<std::size_t>(i)],
                                       y * n.d1 + sy[static_cast<std::size_t>(i)],
                                       base.z * n.d0 + sz[static_cast<std::size_t>(i)]);
            }
            return true;
        }

        default:
            return false;
    }
}

// ---- Spline evaluation ----------------------------------------------------
//
// Mirrors Spline.Implementation.apply(C x) in
// net.minecraft.util.math.Spline (yarn-1.21.11):
//
//     f = locationFunction.apply(x)
//     i = findRangeForLocation(locations, f)
//     if (i < 0)   return sampleOutsideRange(f, locations, values[0].apply(x), derivatives, 0)
//     if (i == j)  return sampleOutsideRange(f, locations, values[j].apply(x), derivatives, j)
//     k = (f - locations[i]) / (locations[i+1] - locations[i])
//     n = values[i].apply(x);  o = values[i+1].apply(x)
//     p = derivatives[i]   * (h - g) - (o - n)
//     q = -derivatives[i+1] * (h - g) + (o - n)
//     return lerp(k, n, o) + k*(1-k)*lerp(k, p, q)
//
// `findRangeForLocation`: largest i such that locations[i] <= f, or
//   -1 if f < locations[0]. Mojang uses MathHelper.binarySearch which
//   is O(log n); a linear scan over a typical 8-point spline is fine.

float evaluate_spline(const NodeArena& arena, SplineRef ref,
                      const Context& ctx) noexcept;

float sample_outside_range(float point, float location, float value,
                           float derivative) noexcept {
    if (derivative == 0.0f) return value;
    return value + derivative * (point - location);
}

int find_range_for_location(const SplineBreakpoint* bps, int n, float f) noexcept {
    // Returns the largest i in [0, n) with bps[i].location <= f, or
    // -1 if f is below all locations. (When f equals an exact
    // boundary, the binary search returns the index whose location
    // equals f; below the explicit `i == j` check covers the upper
    // edge.)
    int lo = 0;
    int hi = n;
    // Custom binary search: find the smallest index whose location > f.
    while (lo < hi) {
        const int mid = (lo + hi) >> 1;
        if (f < bps[mid].location) hi = mid;
        else                       lo = mid + 1;
    }
    return lo - 1;
}

float evaluate_spline_impl(const NodeArena& arena, const Spline& s,
                           const Context& ctx) noexcept {
    if (s.kind == SplineKind::kFixedFloat) return s.fixed_value;
    // Implementation: location_function evaluated as a normal DF.
    const double f_d = (s.location_function >= 0)
        ? evaluate(arena, static_cast<NodeRef>(s.location_function), ctx)
        : 0.0;
    const float  f   = static_cast<float>(f_d);

    const int n = s.breakpoint_count;
    if (n <= 0) return 0.0f;
    const SplineBreakpoint* bps = arena.spline_breakpoints.data() + s.breakpoints_start;

    const int i = find_range_for_location(bps, n, f);
    const int j = n - 1;
    if (i < 0) {
        const float v0 = evaluate_spline(arena, bps[0].value, ctx);
        return sample_outside_range(f, bps[0].location, v0, bps[0].derivative);
    }
    if (i == j) {
        const float vj = evaluate_spline(arena, bps[j].value, ctx);
        return sample_outside_range(f, bps[j].location, vj, bps[j].derivative);
    }
    const float g = bps[i].location;
    const float h = bps[i + 1].location;
    const float k = (f - g) / (h - g);
    const float vn = evaluate_spline(arena, bps[i].value,     ctx);
    const float vo = evaluate_spline(arena, bps[i + 1].value, ctx);
    const float l = bps[i].derivative;
    const float m = bps[i + 1].derivative;
    const float p = l * (h - g) - (vo - vn);
    const float q = -m * (h - g) + (vo - vn);
    const float r = lerp_f(k, vn, vo) + k * (1.0f - k) * lerp_f(k, p, q);
    return r;
}

float evaluate_spline(const NodeArena& arena, SplineRef ref,
                      const Context& ctx) noexcept {
    if (ref < 0 || ref >= static_cast<SplineRef>(arena.splines.size())) return 0.0f;
    return evaluate_spline_impl(arena, arena.splines[ref], ctx);
}

} // namespace

double evaluate(const NodeArena& arena, NodeRef root, const Context& ctx) noexcept {
    if (root < 0 || root >= static_cast<NodeRef>(arena.nodes.size())) return 0.0;
    const Node& n = arena.nodes[root];
    switch (n.kind) {
        case NodeKind::kConstant:
            return eval_const(n);

        case NodeKind::kAbs: {
            const double v = evaluate(arena, n.a, ctx);
            return std::abs(v);
        }
        case NodeKind::kSquare: {
            const double v = evaluate(arena, n.a, ctx);
            return v * v;
        }
        case NodeKind::kCube: {
            const double v = evaluate(arena, n.a, ctx);
            return v * v * v;
        }
        case NodeKind::kHalfNegative: {
            return half_negative(evaluate(arena, n.a, ctx));
        }
        case NodeKind::kQuarterNegative: {
            return quarter_negative(evaluate(arena, n.a, ctx));
        }
        case NodeKind::kInvert: {
            return 1.0 / evaluate(arena, n.a, ctx);
        }
        case NodeKind::kSqueeze: {
            return squeeze(evaluate(arena, n.a, ctx));
        }

        case NodeKind::kAdd: {
            if (n.a == n.b) {
                const double value = evaluate(arena, n.a, ctx);
                return value + value;
            }
            return evaluate(arena, n.a, ctx) + evaluate(arena, n.b, ctx);
        }
        case NodeKind::kMul: {
            const double left = evaluate(arena, n.a, ctx);
            if (n.a == n.b) return left * left;
            return left == 0.0 ? 0.0 : left * evaluate(arena, n.b, ctx);
        }
        case NodeKind::kMin: {
            if (n.a == n.b) return evaluate(arena, n.a, ctx);
            const double a = evaluate(arena, n.a, ctx);
            const double b = evaluate(arena, n.b, ctx);
            return std::min(a, b);
        }
        case NodeKind::kMax: {
            if (n.a == n.b) return evaluate(arena, n.a, ctx);
            const double a = evaluate(arena, n.a, ctx);
            const double b = evaluate(arena, n.b, ctx);
            return std::max(a, b);
        }

        case NodeKind::kYClampedGradient: {
            return y_clamped_gradient(n.i0, n.i1, n.d0, n.d1, ctx.y);
        }

        case NodeKind::kMapRange: {
            const double input = evaluate(arena, n.a, ctx);
            return map_range(input, n.d0, n.d1, n.d2, n.d3);
        }

        case NodeKind::kLerp: {
            const double t    = evaluate(arena, n.a, ctx);
            const double low  = evaluate(arena, n.b, ctx);
            const double high = evaluate(arena, n.c, ctx);
            return lerp(t, low, high);
        }

        case NodeKind::kRangeChoice: {
            const double input = evaluate(arena, n.a, ctx);
            if (input >= n.d0 && input < n.d1) {
                return evaluate(arena, n.b, ctx);
            } else {
                return evaluate(arena, n.c, ctx);
            }
        }

        case NodeKind::kNoise: {
            if (!n.noise_ptr) return 0.0;
            // n.d0 = scaleXZ, n.d1 = scaleY
            return noise::sample(*n.noise_ptr,
                                 ctx.x * n.d0, ctx.y * n.d1, ctx.z * n.d0);
        }

        case NodeKind::kShiftedNoise: {
            if (!n.noise_ptr) return 0.0;
            // n.d0 = scaleXZ, n.d1 = scaleY
            // operands: a = shiftX function, b = shiftY function, c = shiftZ function
            const double sx = evaluate(arena, n.a, ctx);
            const double sy = evaluate(arena, n.b, ctx);
            const double sz = evaluate(arena, n.c, ctx);
            return noise::sample(*n.noise_ptr,
                                  ctx.x * n.d0 + sx,
                                  ctx.y * n.d1 + sy,
                                  ctx.z * n.d0 + sz);
        }

        // ---- Worldgen-4c additions ----

        case NodeKind::kShiftA: {
            // ShiftA(noiseSampler): sample at (x, 0, z), scaled ×4.
            // Mojang factor matches method_40501 / impl.
            if (!n.noise_ptr) return 0.0;
            return noise::sample(*n.noise_ptr, ctx.x * 0.25, 0.0, ctx.z * 0.25) * 4.0;
        }
        case NodeKind::kShiftB: {
            // ShiftB(noiseSampler): sample at (z, x, 0), scaled ×4.
            if (!n.noise_ptr) return 0.0;
            return noise::sample(*n.noise_ptr, ctx.z * 0.25, ctx.x * 0.25, 0.0) * 4.0;
        }
        case NodeKind::kShift: {
            // Shift(noiseSampler): sample at (x, y, z), scaled ×4.
            if (!n.noise_ptr) return 0.0;
            return noise::sample(*n.noise_ptr,
                                 ctx.x * 0.25, ctx.y * 0.25, ctx.z * 0.25) * 4.0;
        }

        case NodeKind::kCache2D: {
            // Key by floored (x, z). When no CacheState is supplied
            // (e.g. unit tests), the node degrades to passthrough.
            if (!ctx.cache || n.cache_slot_id < 0
                || n.cache_slot_id >= static_cast<int>(ctx.cache->cache_2d.size())) {
                return evaluate(arena, n.a, ctx);
            }
            auto& slot = ctx.cache->cache_2d[n.cache_slot_id];
            const int kx = floor_to_int(ctx.x);
            const int kz = floor_to_int(ctx.z);
            if (slot.valid && slot.x == kx && slot.z == kz) {
                return slot.value;
            }
            const double v = evaluate(arena, n.a, ctx);
            slot.valid = true; slot.x = kx; slot.z = kz; slot.value = v;
            return v;
        }
        case NodeKind::kCacheOnce: {
            if (!ctx.cache || n.cache_slot_id < 0
                || n.cache_slot_id >= static_cast<int>(ctx.cache->cache_once.size())) {
                return evaluate(arena, n.a, ctx);
            }
            auto& slot = ctx.cache->cache_once[n.cache_slot_id];
            if (slot.valid && slot.x == ctx.x && slot.y == ctx.y && slot.z == ctx.z) {
                return slot.value;
            }
            const double v = evaluate(arena, n.a, ctx);
            slot.valid = true;
            slot.x = ctx.x; slot.y = ctx.y; slot.z = ctx.z; slot.value = v;
            return v;
        }
        case NodeKind::kCacheAllInCell: {
            if (!ctx.cache || n.cache_slot_id < 0
                || n.cache_slot_id >= static_cast<int>(ctx.cache->cache_all_in_cell.size())) {
                return evaluate(arena, n.a, ctx);
            }
            const int slot_id = n.cache_slot_id;
            if (slot_id < static_cast<int>(ctx.cache->cache_all_in_cell_arrays.size())) {
                const double* values = ctx.cache->cache_all_in_cell_arrays[static_cast<std::size_t>(slot_id)];
                const std::size_t length = ctx.cache->cache_all_in_cell_array_lengths[static_cast<std::size_t>(slot_id)];
                if (values && ctx.inCellX >= 0 && ctx.inCellY >= 0 && ctx.inCellZ >= 0
                    && ctx.inCellX < ctx.cellWidth && ctx.inCellY < ctx.cellHeight && ctx.inCellZ < ctx.cellWidth) {
                    const std::size_t index = (static_cast<std::size_t>(ctx.cellHeight - 1 - ctx.inCellY)
                                             * static_cast<std::size_t>(ctx.cellWidth)
                                             + static_cast<std::size_t>(ctx.inCellX))
                                            * static_cast<std::size_t>(ctx.cellWidth)
                                            + static_cast<std::size_t>(ctx.inCellZ);
                    if (index < length) return values[index];
                }
            }
            if (n.a < 0) return 0.0;
            auto& bucket = ctx.cache->cache_all_in_cell[n.cache_slot_id];
            // Pack (cellX, cellZ, y) into one 64-bit key. cellX / cellZ
            // fit in 24 bits each (Mojang sample range is well within
            // ±8M); y fits in the remaining 16.
            const std::uint64_t key =
                  (static_cast<std::uint64_t>(static_cast<std::uint32_t>(ctx.cellX) & 0xFFFFFFu) << 40)
                | (static_cast<std::uint64_t>(static_cast<std::uint32_t>(ctx.cellZ) & 0xFFFFFFu) << 16)
                | (static_cast<std::uint64_t>(static_cast<std::uint32_t>(static_cast<int>(ctx.y)) & 0xFFFFu));
            if (double* cached = bucket.find(key)) return *cached;
            const double v = evaluate(arena, n.a, ctx);
            bucket.get_or_insert(key) = v;
            return v;
        }
        case NodeKind::kFlatCache: {
            if (!ctx.cache || n.cache_slot_id < 0
                || n.cache_slot_id >= static_cast<int>(ctx.cache->flat_cache.size())) {
                return evaluate(arena, n.a, ctx);
            }
            auto& slot = ctx.cache->flat_cache[n.cache_slot_id];
            if (slot.valid && slot.cellX == ctx.cellX && slot.cellZ == ctx.cellZ) {
                return slot.value;
            }
            const double v = evaluate(arena, n.a, ctx);
            slot.valid = true; slot.cellX = ctx.cellX; slot.cellZ = ctx.cellZ;
            slot.value = v;
            return v;
        }

        case NodeKind::kInterpolated: {
            // Mojang's `DensityInterpolator.sample(pos)`:
            //   if (pos != ChunkNoiseSampler.this) return delegate.sample(pos);
            //   if (!isInInterpolationLoop) throw IllegalStateException(...);
            //   if (isSamplingForCaches) return MathHelper.lerp3(...)  // (we
            //                              don't model isSamplingForCaches;
            //                              we always read `result`).
            //   return this.result;
            //
            // We collapse:
            //   - When the cache is null OR no interpolation loop is
            //     active OR the slot id is unset, fall back to
            //     passthrough evaluation (matches the Worldgen-9
            //     behaviour preserved here for non-chunk-gen callers).
            //   - When the loop is active, return interpolators[slot].result.
            if (!ctx.cache || !ctx.cache->is_in_interpolation_loop
                || n.cache_slot_id < 0
                || n.cache_slot_id >= static_cast<int>(ctx.cache->interpolators.size())) {
                return evaluate(arena, n.a, ctx);
            }
            return ctx.cache->interpolators[n.cache_slot_id].result;
        }

        case NodeKind::kWeirdScaledSampler: {
            if (!n.noise_ptr) return evaluate(arena, n.a, ctx);
            // Mojang's `WeirdScaledSampler` applies a rarity-value
            // mapper to the input, then samples noise at coordinates
            // scaled by that mapper. The two mappers (Type1 / Type2)
            // produce different rarity → scale curves; we replicate
            // both. n.d0 selects the type: 0 = Type1, 1 = Type2.
            const double input = evaluate(arena, n.a, ctx);
            const int    type  = static_cast<int>(n.d0);
            // RarityValueMapper rarities, vanilla constants:
            const double rarity = (type == 1)
                ? /* Type2 / spaghetti rarity 2D */ (input < -0.75 ? 0.5
                              : input < -0.5   ? 0.75
                              : input < 0.5    ? 1.0
                              : input < 0.75   ? 2.0
                              :                  3.0)
                : /* Type1 / spaghetti rarity 3D */ (input < -0.5   ? 0.75
                              : input < 0.0    ? 1.0
                              : input < 0.5    ? 1.5
                              :                  2.0);
            return std::abs(noise::sample(*n.noise_ptr,
                                  ctx.x / rarity,
                                  ctx.y / rarity,
                                  ctx.z / rarity)) * rarity;
        }

        case NodeKind::kEndIslands: {
            if (!n.simplex_ptr) return 0.0;
            // Mojang's `EndIslands.compute(noise, blockX, blockZ)`:
            //
            //   chunkX = blockX // 8
            //   chunkZ = blockZ // 8
            //   height = (blockX² + blockZ²) / 4096 * -8 + 100
            //   clamp height to [-100, 80]
            //   for dx in [-12, 12]:
            //     for dz in [-12, 12]:
            //       cx = chunkX + dx
            //       cz = chunkZ + dz
            //       if (cx² + cz² > 4096 && noise.sample(cx, cz) < -0.9):
            //         islandRadius = (|cx|*3439 + |cz|*147) % 13 + 9
            //         localX = blockX/8 - cx * 2
            //         localZ = blockZ/8 - cz * 2
            //         islandHeight = islandRadius - sqrt(localX² + localZ²)
            //         if islandHeight > height: height = islandHeight
            //   return height
            //
            // The integer division for chunkX/Z is Java's truncate-toward-zero;
            // we match it by using static_cast<int>(double) which truncates.
            const int blockX = static_cast<int>(ctx.x);
            const int blockZ = static_cast<int>(ctx.z);
            const int chunkX = blockX / 8;
            const int chunkZ = blockZ / 8;
            double height =
                (static_cast<double>(blockX) * blockX
                 + static_cast<double>(blockZ) * blockZ) / 4096.0 * -8.0 + 100.0;
            if (height > 80.0)   height = 80.0;
            if (height < -100.0) height = -100.0;

            for (int dx = -12; dx <= 12; ++dx) {
                for (int dz = -12; dz <= 12; ++dz) {
                    const int cx = chunkX + dx;
                    const int cz = chunkZ + dz;
                    if (static_cast<long long>(cx) * cx
                      + static_cast<long long>(cz) * cz > 4096) {
                        const double island_noise = noise::sample_2d(
                            *n.simplex_ptr,
                            static_cast<double>(cx),
                            static_cast<double>(cz));
                        if (island_noise < -0.9) {
                            const int abs_cx = cx < 0 ? -cx : cx;
                            const int abs_cz = cz < 0 ? -cz : cz;
                            const double island_radius =
                                static_cast<double>((abs_cx * 3439 + abs_cz * 147) % 13 + 9);
                            const double local_x = blockX / 8.0 - cx * 2;
                            const double local_z = blockZ / 8.0 - cz * 2;
                            const double dist = std::sqrt(local_x * local_x
                                                        + local_z * local_z);
                            const double island_height = island_radius - dist;
                            if (island_height > height) height = island_height;
                        }
                    }
                }
            }
            return height;
        }

        // ---- Worldgen-7 additions ----

        case NodeKind::kClamp: {
            // Mojang `Clamp.apply(density)`: MathHelper.clamp(input, min, max).
            // n.d0 = min, n.d1 = max.
            const double v = evaluate(arena, n.a, ctx);
            return clamp_d(v, n.d0, n.d1);
        }

        case NodeKind::kBlendAlpha: {
            // No-blending Blender always returns 1.0. See
            // Blender.java:42-45 (NO_BLENDING anonymous override).
            return 1.0;
        }
        case NodeKind::kBlendOffset: {
            // Symmetric: NO_BLENDING returns 0.0.
            return 0.0;
        }
        case NodeKind::kBlendDensity: {
            // BlendDensity dispatches to `pos.getBlender().applyBlendDensity(pos, input)`.
            // Under NO_BLENDING that's a passthrough (Blender.java:48-50).
            return evaluate(arena, n.a, ctx);
        }

        case NodeKind::kSpline: {
            // i0 is the SplineRef. Mojang returns float; we widen.
            const float v = evaluate_spline(arena,
                                            static_cast<SplineRef>(n.i0),
                                            ctx);
            return static_cast<double>(v);
        }

        case NodeKind::kFindTopSurface: {
            // Mojang FindTopSurface.sample (DensityFunctionTypes.java:1063):
            //   i = floor(upperBound(pos) / cellHeight) * cellHeight
            //   if (i <= lowerBound) return lowerBound
            //   for (j = i; j >= lowerBound; j -= cellHeight)
            //       if (density(x, j, z) > 0) return j
            //   return lowerBound
            //
            // Mojang uses an UnblendedNoisePos to suppress blender-side
            // effects on the inner density sample. Our BlendDensity
            // already implements NO_BLENDING semantics regardless of
            // context, so plain Context with the same x/z/cell coords
            // (only y rebound) is correct.
            const int    lower_bound = n.i0;
            const int    cell_height = n.i1;
            if (cell_height <= 0) return static_cast<double>(lower_bound);

            const double upper = evaluate(arena, n.b, ctx);
            const int i = static_cast<int>(std::floor(upper / static_cast<double>(cell_height)))
                          * cell_height;
            if (i <= lower_bound) return static_cast<double>(lower_bound);

            Context inner = ctx;
            for (int j = i; j >= lower_bound; j -= cell_height) {
                inner.y = static_cast<double>(j);
                const double d = evaluate(arena, n.a, inner);
                if (d > 0.0) return static_cast<double>(j);
            }
            return static_cast<double>(lower_bound);
        }

        case NodeKind::kInterpolatedNoise: {
            if (!n.interp_noise_ptr) return 0.0;
            return noise::sample(*n.interp_noise_ptr, ctx.x, ctx.y, ctx.z);
        }

        case NodeKind::kBeardifier: {
            if (!n.beardifier_ptr) return 0.0;
            return beardifier::compute(*n.beardifier_ptr,
                                       static_cast<int>(ctx.x),
                                       static_cast<int>(ctx.y),
                                       static_cast<int>(ctx.z));
        }
    }
    return 0.0;
}

double evaluate(const NodeArena& arena, const Context& ctx) noexcept {
    return evaluate(arena, arena.root, ctx);
}

void evaluate_grid(const NodeArena& arena, NodeRef root,
                   double x0, double y0, double z0,
                   double dx, double dy, double dz,
                   int cellX0, int cellZ0,
                   int nx, int ny, int nz,
                   CacheState* cache,
                   double* out) noexcept {
    if (!out) return;
    if (nx <= 0 || ny <= 0 || nz <= 0) return;
    if (root < 0) {
        // Null root: zero-fill so callers don't see uninitialised memory.
        const std::size_t n = static_cast<std::size_t>(nx)
                            * static_cast<std::size_t>(ny)
                            * static_cast<std::size_t>(nz);
        for (std::size_t i = 0; i < n; ++i) out[i] = 0.0;
        return;
    }
    Context ctx{};
    ctx.cache = cache;
    // Layout: flat array indexed by ((iy * nz) + iz) * nx + ix.
    // Iteration order is iy outer, iz middle, ix inner so that the
    // hot inner loop walks ix — which matches the typical FlatCache
    // hit pattern (cellX changes per ix; the cache keys on (cellX, cellZ)
    // and stays warm as iz steps).
    for (int iy = 0; iy < ny; ++iy) {
        ctx.y = y0 + static_cast<double>(iy) * dy;
        for (int iz = 0; iz < nz; ++iz) {
            ctx.z     = z0 + static_cast<double>(iz) * dz;
            ctx.cellZ = cellZ0 + iz;
            double* row = out + (static_cast<std::size_t>(iy) * nz + iz)
                             * static_cast<std::size_t>(nx);
            for (int ix = 0; ix < nx; ++ix) {
                ctx.x     = x0 + static_cast<double>(ix) * dx;
                ctx.cellX = cellX0 + ix;
                row[ix]   = evaluate(arena, root, ctx);
            }
        }
    }
}

void evaluate_y_column(const NodeArena& arena, NodeRef root,
                       double x, double y0, double z, double dy,
                       int cellX, int cellZ,
                       int ny,
                       CacheState* cache,
                       double* out) noexcept {
    if (!out || ny <= 0) return;
    if (root < 0) {
        for (int i = 0; i < ny; ++i) out[i] = 0.0;
        return;
    }

    Context ctx{};
    ctx.cache = cache;
    ctx.x = x;
    ctx.z = z;
    ctx.cellX = cellX;
    ctx.cellZ = cellZ;
#if defined(LATTICE_HAS_DENSITY_AVX2)
    if (lattice::cpu::features().avx2
        && evaluate_y_column_avx2(arena, root, x, y0, z, dy, cellX, cellZ, ny, cache, out)) {
        return;
    }
#endif
    if (evaluate_y_column_fast(arena, root, ctx, y0, dy, ny, out)) return;
    for (int iy = 0; iy < ny; ++iy) {
        ctx.y = y0 + static_cast<double>(iy) * dy;
        out[iy] = evaluate(arena, root, ctx);
    }
}

// ---- Interpolator operations -------------------------------------------
//
// Implementation notes (Mojang's DensityInterpolator, yarn-1.21.11
// ChunkNoiseSampler.java:516-620):
//
//   - The buffer indexing in vanilla is `buffer[cellZ][cellY]`. We
//     flatten that to `buffer[cellZ * (vCC + 1) + cellY]`.
//   - on_sampled_cell_corners (line 555-563) loads `start[cellZ][cellY]`
//     and `start[cellZ+1][cellY]` etc. for the 8 corners. The X axis
//     uses `start` for x=0 and `end` for x=1 (since end is the next
//     cellX column).
//   - interpolateY (line 566-571) does 4 lerps along Y.
//   - interpolateX (line 573-576) does 2 lerps along X.
//   - interpolateZ (line 578-580) does 1 lerp along Z, producing the
//     final result.
//   - swapBuffers (line 610-614) just swaps the start/end pointers; we
//     swap the underlying std::vector<double> buffers.

namespace {

inline std::size_t buffer_index(int cellZ, int cellY, int vCC) noexcept {
    return static_cast<std::size_t>(cellZ) * static_cast<std::size_t>(vCC + 1)
         + static_cast<std::size_t>(cellY);
}

inline bool slot_in_range(const CacheState& cache, int slot) noexcept {
    return slot >= 0 && slot < static_cast<int>(cache.interpolators.size());
}

} // namespace

void start_interpolation(CacheState& cache) noexcept {
    cache.is_in_interpolation_loop = true;
}

void stop_interpolation(CacheState& cache) noexcept {
    cache.is_in_interpolation_loop = false;
}

void set_start_density(CacheState& cache, int slot,
                       int cellZ, int cellY, double value) noexcept {
    if (!slot_in_range(cache, slot)) return;
    auto& it = cache.interpolators[slot];
    const std::size_t i = buffer_index(cellZ, cellY, cache.vertical_cell_count);
    if (i < it.start_density_buffer.size()) {
        it.start_density_buffer[i] = value;
    }
}

void set_end_density(CacheState& cache, int slot,
                     int cellZ, int cellY, double value) noexcept {
    if (!slot_in_range(cache, slot)) return;
    auto& it = cache.interpolators[slot];
    const std::size_t i = buffer_index(cellZ, cellY, cache.vertical_cell_count);
    if (i < it.end_density_buffer.size()) {
        it.end_density_buffer[i] = value;
    }
}

void on_sampled_cell_corners(CacheState& cache, int cellY, int cellZ) noexcept {
    const int vCC = cache.vertical_cell_count;
    for (auto& it : cache.interpolators) {
        // Vanilla:
        //   x0y0z0 = startBuffer[cellZ    ][cellY    ];
        //   x0y0z1 = startBuffer[cellZ + 1][cellY    ];
        //   x1y0z0 = endBuffer  [cellZ    ][cellY    ];
        //   x1y0z1 = endBuffer  [cellZ + 1][cellY    ];
        //   x0y1z0 = startBuffer[cellZ    ][cellY + 1];
        //   x0y1z1 = startBuffer[cellZ + 1][cellY + 1];
        //   x1y1z0 = endBuffer  [cellZ    ][cellY + 1];
        //   x1y1z1 = endBuffer  [cellZ + 1][cellY + 1];
        //
        // Buffer must already have been sized via prepare_interpolators;
        // bounds check guards against caller bugs but does not modify
        // out-of-range writes (silent no-op).
        const std::size_t i00 = buffer_index(cellZ,     cellY,     vCC);
        const std::size_t i10 = buffer_index(cellZ + 1, cellY,     vCC);
        const std::size_t i01 = buffer_index(cellZ,     cellY + 1, vCC);
        const std::size_t i11 = buffer_index(cellZ + 1, cellY + 1, vCC);
        const std::size_t cap = it.start_density_buffer.size();
        if (i11 >= cap) {
            // Slot not provisioned (zero-size buffer) or cell out of range
            // — leave corners at their previous values so subsequent
            // sample reads are deterministic.
            continue;
        }
        it.x0y0z0 = it.start_density_buffer[i00];
        it.x0y0z1 = it.start_density_buffer[i10];
        it.x1y0z0 = it.end_density_buffer  [i00];
        it.x1y0z1 = it.end_density_buffer  [i10];
        it.x0y1z0 = it.start_density_buffer[i01];
        it.x0y1z1 = it.start_density_buffer[i11];
        it.x1y1z0 = it.end_density_buffer  [i01];
        it.x1y1z1 = it.end_density_buffer  [i11];
    }
}

void interpolate_y(CacheState& cache, double deltaY) noexcept {
    // Vanilla:
    //   x0z0 = MathHelper.lerp(deltaY, x0y0z0, x0y1z0);
    //   x1z0 = MathHelper.lerp(deltaY, x1y0z0, x1y1z0);
    //   x0z1 = MathHelper.lerp(deltaY, x0y0z1, x0y1z1);
    //   x1z1 = MathHelper.lerp(deltaY, x1y0z1, x1y1z1);
    for (auto& it : cache.interpolators) {
        it.x0z0 = it.x0y0z0 + deltaY * (it.x0y1z0 - it.x0y0z0);
        it.x1z0 = it.x1y0z0 + deltaY * (it.x1y1z0 - it.x1y0z0);
        it.x0z1 = it.x0y0z1 + deltaY * (it.x0y1z1 - it.x0y0z1);
        it.x1z1 = it.x1y0z1 + deltaY * (it.x1y1z1 - it.x1y0z1);
    }
}

void interpolate_x(CacheState& cache, double deltaX) noexcept {
    // Vanilla:
    //   z0 = MathHelper.lerp(deltaX, x0z0, x1z0);
    //   z1 = MathHelper.lerp(deltaX, x0z1, x1z1);
    for (auto& it : cache.interpolators) {
        it.z0 = it.x0z0 + deltaX * (it.x1z0 - it.x0z0);
        it.z1 = it.x0z1 + deltaX * (it.x1z1 - it.x0z1);
    }
}

void interpolate_z(CacheState& cache, double deltaZ) noexcept {
    // Vanilla:
    //   result = MathHelper.lerp(deltaZ, z0, z1);
    for (auto& it : cache.interpolators) {
        it.result = it.z0 + deltaZ * (it.z1 - it.z0);
    }
}

void swap_buffers(CacheState& cache) noexcept {
    for (auto& it : cache.interpolators) {
        it.start_density_buffer.swap(it.end_density_buffer);
    }
}

} // namespace lattice::world::gen::densityfunction
