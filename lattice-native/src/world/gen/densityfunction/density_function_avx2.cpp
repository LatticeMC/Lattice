#include "world/gen/densityfunction/density_function.hpp"

#include <algorithm>
#include <cmath>
#include <immintrin.h>
#include <vector>

namespace lattice::world::gen::densityfunction {
namespace {

inline double clamp_d(double v, double lo, double hi) noexcept {
    return std::max(lo, std::min(hi, v));
}

inline double lerp(double t, double a, double b) noexcept {
    return a + t * (b - a);
}

inline double y_clamped_gradient(int from_y, int to_y,
                                 double from_v, double to_v,
                                 double y) noexcept {
    const double dy_total = static_cast<double>(to_y - from_y);
    if (dy_total == 0.0) return (from_v + to_v) * 0.5;
    const double y_clamped = clamp_d(y, static_cast<double>(from_y), static_cast<double>(to_y));
    const double t = (y_clamped - from_y) / dy_total;
    return lerp(t, from_v, to_v);
}

inline bool evaluate_child_column(const NodeArena& arena, NodeRef child,
                                  double x, double y0, double z, double dy,
                                  int cellX, int cellZ, int ny,
                                  CacheState* cache, double* out) noexcept {
    if (evaluate_y_column_avx2(arena, child, x, y0, z, dy, cellX, cellZ, ny, cache, out)) return true;
    evaluate_y_column(arena, child, x, y0, z, dy, cellX, cellZ, ny, cache, out);
    return true;
}

struct ColumnScratchLease {
    CacheState* cache = nullptr;
    std::vector<double> local;
    std::size_t index = 0;

    ColumnScratchLease(CacheState* cache_in, int ny) : cache(cache_in) {
        const std::size_t count = static_cast<std::size_t>(ny);
        if (!cache) {
            local.resize(count);
            return;
        }
        index = cache->scratch_column_depth++;
        if (cache->scratch_columns.size() <= index) cache->scratch_columns.resize(index + 1u);
        cache->scratch_columns[index].resize(count);
    }

    ~ColumnScratchLease() {
        if (cache) --cache->scratch_column_depth;
    }

    double* data() noexcept {
        return cache ? cache->scratch_columns[index].data() : local.data();
    }

    ColumnScratchLease(const ColumnScratchLease&) = delete;
    ColumnScratchLease& operator=(const ColumnScratchLease&) = delete;
};

} // namespace

bool evaluate_y_column_avx2(const NodeArena& arena, NodeRef root,
                            double x, double y0, double z, double dy,
                            int cellX, int cellZ,
                            int ny,
                            CacheState* cache,
                            double* out) noexcept {
    if (!out || ny <= 0 || root < 0 || root >= static_cast<NodeRef>(arena.nodes.size())) return false;
    const Node& n = arena.nodes[static_cast<std::size_t>(root)];

    switch (n.kind) {
        case NodeKind::kConstant:
        case NodeKind::kBlendAlpha:
        case NodeKind::kBlendOffset: {
            const double value = n.kind == NodeKind::kConstant ? n.d0 : (n.kind == NodeKind::kBlendAlpha ? 1.0 : 0.0);
            const __m256d vv = _mm256_set1_pd(value);
            int i = 0;
            for (; i + 4 <= ny; i += 4) {
                _mm256_storeu_pd(out + i, vv);
            }
            for (; i < ny; ++i) out[i] = value;
            return true;
        }

        case NodeKind::kYClampedGradient: {
            const double dy_total = static_cast<double>(n.i1 - n.i0);
            if (dy_total == 0.0) {
                const double value = (n.d0 + n.d1) * 0.5;
                const __m256d vv = _mm256_set1_pd(value);
                int i = 0;
                for (; i + 4 <= ny; i += 4) _mm256_storeu_pd(out + i, vv);
                for (; i < ny; ++i) out[i] = value;
                return true;
            }

            const __m256d from_y = _mm256_set1_pd(static_cast<double>(n.i0));
            const __m256d to_y = _mm256_set1_pd(static_cast<double>(n.i1));
            const __m256d from_v = _mm256_set1_pd(n.d0);
            const __m256d delta_v = _mm256_set1_pd(n.d1 - n.d0);
            const __m256d inv_dy = _mm256_set1_pd(1.0 / dy_total);
            const __m256d step = _mm256_set_pd(3.0 * dy, 2.0 * dy, dy, 0.0);
            int i = 0;
            for (; i + 4 <= ny; i += 4) {
                const __m256d base = _mm256_set1_pd(y0 + static_cast<double>(i) * dy);
                __m256d y = _mm256_add_pd(base, step);
                y = _mm256_min_pd(_mm256_max_pd(y, from_y), to_y);
                const __m256d t = _mm256_mul_pd(_mm256_sub_pd(y, from_y), inv_dy);
                _mm256_storeu_pd(out + i, _mm256_add_pd(from_v, _mm256_mul_pd(t, delta_v)));
            }
            for (; i < ny; ++i) out[i] = y_clamped_gradient(n.i0, n.i1, n.d0, n.d1, y0 + static_cast<double>(i) * dy);
            return true;
        }

        case NodeKind::kNoise:
            if (!n.noise_ptr) {
                const __m256d zero = _mm256_setzero_pd();
                int i = 0;
                for (; i + 4 <= ny; i += 4) _mm256_storeu_pd(out + i, zero);
                for (; i < ny; ++i) out[i] = 0.0;
                return true;
            }
            noise::sample_y_column(*n.noise_ptr,
                                   x * n.d0,
                                   y0 * n.d1,
                                   z * n.d0,
                                   dy * n.d1,
                                   static_cast<std::size_t>(ny),
                                   out);
            return true;

        case NodeKind::kShiftA:
        case NodeKind::kShiftB: {
            const double value = !n.noise_ptr ? 0.0
                : (n.kind == NodeKind::kShiftA
                    ? noise::sample(*n.noise_ptr, x * 0.25, 0.0, z * 0.25) * 4.0
                    : noise::sample(*n.noise_ptr, z * 0.25, x * 0.25, 0.0) * 4.0);
            const __m256d vv = _mm256_set1_pd(value);
            int i = 0;
            for (; i + 4 <= ny; i += 4) _mm256_storeu_pd(out + i, vv);
            for (; i < ny; ++i) out[i] = value;
            return true;
        }

        case NodeKind::kShift:
            if (!n.noise_ptr) {
                const __m256d zero = _mm256_setzero_pd();
                int i = 0;
                for (; i + 4 <= ny; i += 4) _mm256_storeu_pd(out + i, zero);
                for (; i < ny; ++i) out[i] = 0.0;
                return true;
            }
            noise::sample_y_column(*n.noise_ptr,
                                   x * 0.25,
                                   y0 * 0.25,
                                   z * 0.25,
                                   dy * 0.25,
                                   static_cast<std::size_t>(ny),
                                   out);
            {
                const __m256d scale = _mm256_set1_pd(4.0);
                int i = 0;
                for (; i + 4 <= ny; i += 4) {
                    _mm256_storeu_pd(out + i, _mm256_mul_pd(_mm256_loadu_pd(out + i), scale));
                }
                for (; i < ny; ++i) out[i] *= 4.0;
            }
            return true;

        case NodeKind::kShiftedNoise: {
            if (!n.noise_ptr) {
                const __m256d zero = _mm256_setzero_pd();
                int i = 0;
                for (; i + 4 <= ny; i += 4) _mm256_storeu_pd(out + i, zero);
                for (; i < ny; ++i) out[i] = 0.0;
                return true;
            }
            ColumnScratchLease sx(cache, ny);
            ColumnScratchLease sy(cache, ny);
            ColumnScratchLease sz(cache, ny);
            if (!evaluate_child_column(arena, n.a, x, y0, z, dy, cellX, cellZ, ny, cache, sx.data())) return false;
            if (!evaluate_child_column(arena, n.b, x, y0, z, dy, cellX, cellZ, ny, cache, sy.data())) return false;
            if (!evaluate_child_column(arena, n.c, x, y0, z, dy, cellX, cellZ, ny, cache, sz.data())) return false;

            std::vector<double> local_xs;
            std::vector<double> local_ys;
            std::vector<double> local_zs;
            std::vector<double>& xs = cache ? cache->scratch_x : local_xs;
            std::vector<double>& ys = cache ? cache->scratch_y : local_ys;
            std::vector<double>& zs = cache ? cache->scratch_z : local_zs;
            const std::size_t count = static_cast<std::size_t>(ny);
            xs.resize(count);
            ys.resize(count);
            zs.resize(count);

            const __m256d x_base = _mm256_set1_pd(x * n.d0);
            const __m256d z_base = _mm256_set1_pd(z * n.d0);
            const __m256d y_scale = _mm256_set1_pd(n.d1);
            const __m256d step = _mm256_set_pd(3.0 * dy, 2.0 * dy, dy, 0.0);
            int i = 0;
            for (; i + 4 <= ny; i += 4) {
                const __m256d y_base = _mm256_set1_pd(y0 + static_cast<double>(i) * dy);
                const __m256d y_values = _mm256_add_pd(y_base, step);
                _mm256_storeu_pd(xs.data() + i, _mm256_add_pd(x_base, _mm256_loadu_pd(sx.data() + i)));
                _mm256_storeu_pd(ys.data() + i, _mm256_add_pd(_mm256_mul_pd(y_values, y_scale), _mm256_loadu_pd(sy.data() + i)));
                _mm256_storeu_pd(zs.data() + i, _mm256_add_pd(z_base, _mm256_loadu_pd(sz.data() + i)));
            }
            for (; i < ny; ++i) {
                const std::size_t idx = static_cast<std::size_t>(i);
                const double y = y0 + static_cast<double>(i) * dy;
                xs[idx] = x * n.d0 + sx.data()[idx];
                ys[idx] = y * n.d1 + sy.data()[idx];
                zs[idx] = z * n.d0 + sz.data()[idx];
            }
            noise::sample_batch(*n.noise_ptr, xs.data(), ys.data(), zs.data(), count, out);
            return true;
        }

        case NodeKind::kAbs:
        case NodeKind::kSquare:
        case NodeKind::kCube:
        case NodeKind::kHalfNegative:
        case NodeKind::kQuarterNegative:
        case NodeKind::kInvert:
        case NodeKind::kSqueeze:
        case NodeKind::kClamp:
        case NodeKind::kBlendDensity: {
            ColumnScratchLease values(cache, ny);
            if (!evaluate_child_column(arena, n.a, x, y0, z, dy, cellX, cellZ, ny, cache, values.data())) return false;
            const __m256d zero = _mm256_setzero_pd();
            const __m256d sign_mask = _mm256_set1_pd(-0.0);
            const __m256d half = _mm256_set1_pd(0.5);
            const __m256d quarter = _mm256_set1_pd(0.25);
            const __m256d one = _mm256_set1_pd(1.0);
            const __m256d neg_one = _mm256_set1_pd(-1.0);
            const __m256d inv24 = _mm256_set1_pd(1.0 / 24.0);
            const __m256d clamp_min = _mm256_set1_pd(n.d0);
            const __m256d clamp_max = _mm256_set1_pd(n.d1);
            int i = 0;
            for (; i + 4 <= ny; i += 4) {
                __m256d v = _mm256_loadu_pd(values.data() + i);
                switch (n.kind) {
                    case NodeKind::kAbs:
                        v = _mm256_andnot_pd(sign_mask, v);
                        break;
                    case NodeKind::kSquare:
                        v = _mm256_mul_pd(v, v);
                        break;
                    case NodeKind::kCube:
                        v = _mm256_mul_pd(_mm256_mul_pd(v, v), v);
                        break;
                    case NodeKind::kHalfNegative:
                        v = _mm256_blendv_pd(v, _mm256_mul_pd(v, half), _mm256_cmp_pd(v, zero, _CMP_LT_OQ));
                        break;
                    case NodeKind::kQuarterNegative:
                        v = _mm256_blendv_pd(v, _mm256_mul_pd(v, quarter), _mm256_cmp_pd(v, zero, _CMP_LT_OQ));
                        break;
                    case NodeKind::kInvert:
                        v = _mm256_div_pd(one, v);
                        break;
                    case NodeKind::kSqueeze: {
                        v = _mm256_min_pd(_mm256_max_pd(v, neg_one), one);
                        const __m256d v3 = _mm256_mul_pd(_mm256_mul_pd(v, v), v);
                        v = _mm256_sub_pd(_mm256_mul_pd(v, half), _mm256_mul_pd(v3, inv24));
                        break;
                    }
                    case NodeKind::kClamp:
                        v = _mm256_min_pd(_mm256_max_pd(v, clamp_min), clamp_max);
                        break;
                    case NodeKind::kBlendDensity:
                        break;
                    default:
                        return false;
                }
                _mm256_storeu_pd(out + i, v);
            }
            for (; i < ny; ++i) {
                const double v = values.data()[static_cast<std::size_t>(i)];
                switch (n.kind) {
                    case NodeKind::kAbs: out[i] = std::abs(v); break;
                    case NodeKind::kSquare: out[i] = v * v; break;
                    case NodeKind::kCube: out[i] = v * v * v; break;
                    case NodeKind::kHalfNegative: out[i] = v < 0.0 ? v * 0.5 : v; break;
                    case NodeKind::kQuarterNegative: out[i] = v < 0.0 ? v * 0.25 : v; break;
                    case NodeKind::kInvert: out[i] = 1.0 / v; break;
                    case NodeKind::kSqueeze: {
                        const double c = clamp_d(v, -1.0, 1.0);
                        out[i] = c * 0.5 - c * c * c / 24.0;
                        break;
                    }
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
            ColumnScratchLease left(cache, ny);
            if (!evaluate_child_column(arena, n.a, x, y0, z, dy, cellX, cellZ, ny, cache, left.data())) return false;
            if (n.a == n.b) {
                int i = 0;
                for (; i + 4 <= ny; i += 4) {
                    __m256d v = _mm256_loadu_pd(left.data() + i);
                    switch (n.kind) {
                        case NodeKind::kAdd: v = _mm256_add_pd(v, v); break;
                        case NodeKind::kMul: v = _mm256_mul_pd(v, v); break;
                        case NodeKind::kMin:
                        case NodeKind::kMax: break;
                        default: return false;
                    }
                    _mm256_storeu_pd(out + i, v);
                }
                for (; i < ny; ++i) {
                    const double v = left.data()[static_cast<std::size_t>(i)];
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
            ColumnScratchLease right(cache, ny);
            if (!evaluate_child_column(arena, n.b, x, y0, z, dy, cellX, cellZ, ny, cache, right.data())) return false;
            const __m256d zero = _mm256_setzero_pd();
            int i = 0;
            for (; i + 4 <= ny; i += 4) {
                const __m256d a = _mm256_loadu_pd(left.data() + i);
                const __m256d b = _mm256_loadu_pd(right.data() + i);
                __m256d v;
                switch (n.kind) {
                    case NodeKind::kAdd:
                        v = _mm256_add_pd(a, b);
                        break;
                    case NodeKind::kMul:
                        v = _mm256_blendv_pd(_mm256_mul_pd(a, b), zero, _mm256_cmp_pd(a, zero, _CMP_EQ_OQ));
                        break;
                    case NodeKind::kMin:
                        v = _mm256_min_pd(a, b);
                        break;
                    case NodeKind::kMax:
                        v = _mm256_max_pd(a, b);
                        break;
                    default:
                        return false;
                }
                _mm256_storeu_pd(out + i, v);
            }
            for (; i < ny; ++i) {
                const double a = left.data()[static_cast<std::size_t>(i)];
                const double b = right.data()[static_cast<std::size_t>(i)];
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
            ColumnScratchLease input(cache, ny);
            if (!evaluate_child_column(arena, n.a, x, y0, z, dy, cellX, cellZ, ny, cache, input.data())) return false;
            const __m256d from_min = _mm256_set1_pd(n.d0);
            const __m256d inv_range = _mm256_set1_pd(1.0 / (n.d1 - n.d0));
            const __m256d to_min = _mm256_set1_pd(n.d2);
            const __m256d to_delta = _mm256_set1_pd(n.d3 - n.d2);
            int i = 0;
            for (; i + 4 <= ny; i += 4) {
                const __m256d v = _mm256_loadu_pd(input.data() + i);
                const __m256d t = _mm256_mul_pd(_mm256_sub_pd(v, from_min), inv_range);
                _mm256_storeu_pd(out + i, _mm256_add_pd(to_min, _mm256_mul_pd(t, to_delta)));
            }
            for (; i < ny; ++i) {
                const double value = input.data()[static_cast<std::size_t>(i)];
                const double t = (value - n.d0) / (n.d1 - n.d0);
                out[i] = n.d2 + t * (n.d3 - n.d2);
            }
            return true;
        }

        case NodeKind::kRangeChoice: {
            ColumnScratchLease input(cache, ny);
            ColumnScratchLease in_values(cache, ny);
            ColumnScratchLease out_values(cache, ny);
            if (!evaluate_child_column(arena, n.a, x, y0, z, dy, cellX, cellZ, ny, cache, input.data())) return false;
            if (!evaluate_child_column(arena, n.b, x, y0, z, dy, cellX, cellZ, ny, cache, in_values.data())) return false;
            if (!evaluate_child_column(arena, n.c, x, y0, z, dy, cellX, cellZ, ny, cache, out_values.data())) return false;
            const __m256d min_inclusive = _mm256_set1_pd(n.d0);
            const __m256d max_exclusive = _mm256_set1_pd(n.d1);
            int i = 0;
            for (; i + 4 <= ny; i += 4) {
                const __m256d value = _mm256_loadu_pd(input.data() + i);
                const __m256d mask = _mm256_and_pd(
                    _mm256_cmp_pd(value, min_inclusive, _CMP_GE_OQ),
                    _mm256_cmp_pd(value, max_exclusive, _CMP_LT_OQ));
                const __m256d in_v = _mm256_loadu_pd(in_values.data() + i);
                const __m256d out_v = _mm256_loadu_pd(out_values.data() + i);
                _mm256_storeu_pd(out + i, _mm256_blendv_pd(out_v, in_v, mask));
            }
            for (; i < ny; ++i) {
                const double value = input.data()[static_cast<std::size_t>(i)];
                out[i] = value >= n.d0 && value < n.d1
                       ? in_values.data()[static_cast<std::size_t>(i)]
                       : out_values.data()[static_cast<std::size_t>(i)];
            }
            return true;
        }

        case NodeKind::kLerp: {
            ColumnScratchLease t_values(cache, ny);
            ColumnScratchLease low_values(cache, ny);
            ColumnScratchLease high_values(cache, ny);
            if (!evaluate_child_column(arena, n.a, x, y0, z, dy, cellX, cellZ, ny, cache, t_values.data())) return false;
            if (!evaluate_child_column(arena, n.b, x, y0, z, dy, cellX, cellZ, ny, cache, low_values.data())) return false;
            if (!evaluate_child_column(arena, n.c, x, y0, z, dy, cellX, cellZ, ny, cache, high_values.data())) return false;
            int i = 0;
            for (; i + 4 <= ny; i += 4) {
                const __m256d t = _mm256_loadu_pd(t_values.data() + i);
                const __m256d low = _mm256_loadu_pd(low_values.data() + i);
                const __m256d high = _mm256_loadu_pd(high_values.data() + i);
                _mm256_storeu_pd(out + i, _mm256_add_pd(low, _mm256_mul_pd(t, _mm256_sub_pd(high, low))));
            }
            for (; i < ny; ++i) {
                const double t = t_values.data()[static_cast<std::size_t>(i)];
                const double low = low_values.data()[static_cast<std::size_t>(i)];
                out[i] = low + t * (high_values.data()[static_cast<std::size_t>(i)] - low);
            }
            return true;
        }

        case NodeKind::kInterpolatedNoise: {
            if (!n.interp_noise_ptr) {
                const __m256d zero = _mm256_setzero_pd();
                int i = 0;
                for (; i + 4 <= ny; i += 4) _mm256_storeu_pd(out + i, zero);
                for (; i < ny; ++i) out[i] = 0.0;
                return true;
            }
            std::vector<double> local_xs;
            std::vector<double> local_ys;
            std::vector<double> local_zs;
            std::vector<double>& xs = cache ? cache->scratch_x : local_xs;
            std::vector<double>& ys = cache ? cache->scratch_y : local_ys;
            std::vector<double>& zs = cache ? cache->scratch_z : local_zs;
            const std::size_t count = static_cast<std::size_t>(ny);
            xs.resize(count);
            ys.resize(count);
            zs.resize(count);
            const __m256d x_v = _mm256_set1_pd(x);
            const __m256d z_v = _mm256_set1_pd(z);
            const __m256d step = _mm256_set_pd(3.0 * dy, 2.0 * dy, dy, 0.0);
            int i = 0;
            for (; i + 4 <= ny; i += 4) {
                const __m256d y_base = _mm256_set1_pd(y0 + static_cast<double>(i) * dy);
                _mm256_storeu_pd(xs.data() + i, x_v);
                _mm256_storeu_pd(ys.data() + i, _mm256_add_pd(y_base, step));
                _mm256_storeu_pd(zs.data() + i, z_v);
            }
            for (; i < ny; ++i) {
                const std::size_t idx = static_cast<std::size_t>(i);
                xs[idx] = x;
                ys[idx] = y0 + static_cast<double>(i) * dy;
                zs[idx] = z;
            }
            noise::sample_batch(*n.interp_noise_ptr, xs.data(), ys.data(), zs.data(), count, out);
            return true;
        }

        case NodeKind::kWeirdScaledSampler: {
            if (!n.noise_ptr) {
                return evaluate_child_column(arena, n.a, x, y0, z, dy, cellX, cellZ, ny, cache, out);
            }
            ColumnScratchLease input(cache, ny);
            ColumnScratchLease rarity_values(cache, ny);
            if (!evaluate_child_column(arena, n.a, x, y0, z, dy, cellX, cellZ, ny, cache, input.data())) return false;

            const bool type2 = static_cast<int>(n.d0) == 1;
            const __m256d r05 = _mm256_set1_pd(0.5);
            const __m256d r075 = _mm256_set1_pd(0.75);
            const __m256d r10 = _mm256_set1_pd(1.0);
            const __m256d r15 = _mm256_set1_pd(1.5);
            const __m256d r20 = _mm256_set1_pd(2.0);
            const __m256d r30 = _mm256_set1_pd(3.0);
            const __m256d neg075 = _mm256_set1_pd(-0.75);
            const __m256d neg05 = _mm256_set1_pd(-0.5);
            const __m256d zero = _mm256_setzero_pd();
            const __m256d pos05 = _mm256_set1_pd(0.5);
            const __m256d pos075 = _mm256_set1_pd(0.75);

            int i = 0;
            for (; i + 4 <= ny; i += 4) {
                const __m256d v = _mm256_loadu_pd(input.data() + i);
                __m256d rarity;
                if (type2) {
                    rarity = r30;
                    rarity = _mm256_blendv_pd(rarity, r20, _mm256_cmp_pd(v, pos075, _CMP_LT_OQ));
                    rarity = _mm256_blendv_pd(rarity, r10, _mm256_cmp_pd(v, pos05, _CMP_LT_OQ));
                    rarity = _mm256_blendv_pd(rarity, r075, _mm256_cmp_pd(v, neg05, _CMP_LT_OQ));
                    rarity = _mm256_blendv_pd(rarity, r05, _mm256_cmp_pd(v, neg075, _CMP_LT_OQ));
                } else {
                    rarity = r20;
                    rarity = _mm256_blendv_pd(rarity, r15, _mm256_cmp_pd(v, pos05, _CMP_LT_OQ));
                    rarity = _mm256_blendv_pd(rarity, r10, _mm256_cmp_pd(v, zero, _CMP_LT_OQ));
                    rarity = _mm256_blendv_pd(rarity, r075, _mm256_cmp_pd(v, neg05, _CMP_LT_OQ));
                }
                _mm256_storeu_pd(rarity_values.data() + i, rarity);
            }
            for (; i < ny; ++i) {
                const double value = input.data()[static_cast<std::size_t>(i)];
                rarity_values.data()[static_cast<std::size_t>(i)] = type2
                    ? (value < -0.75 ? 0.5 : value < -0.5 ? 0.75 : value < 0.5 ? 1.0 : value < 0.75 ? 2.0 : 3.0)
                    : (value < -0.5 ? 0.75 : value < 0.0 ? 1.0 : value < 0.5 ? 1.5 : 2.0);
            }

            std::vector<double> local_xs;
            std::vector<double> local_ys;
            std::vector<double> local_zs;
            std::vector<double>& xs = cache ? cache->scratch_x : local_xs;
            std::vector<double>& ys = cache ? cache->scratch_y : local_ys;
            std::vector<double>& zs = cache ? cache->scratch_z : local_zs;
            const std::size_t count = static_cast<std::size_t>(ny);
            xs.resize(count);
            ys.resize(count);
            zs.resize(count);

            const __m256d x_v = _mm256_set1_pd(x);
            const __m256d z_v = _mm256_set1_pd(z);
            const __m256d step = _mm256_set_pd(3.0 * dy, 2.0 * dy, dy, 0.0);
            i = 0;
            for (; i + 4 <= ny; i += 4) {
                const __m256d rarity = _mm256_loadu_pd(rarity_values.data() + i);
                const __m256d y_base = _mm256_set1_pd(y0 + static_cast<double>(i) * dy);
                const __m256d y_values = _mm256_add_pd(y_base, step);
                _mm256_storeu_pd(xs.data() + i, _mm256_div_pd(x_v, rarity));
                _mm256_storeu_pd(ys.data() + i, _mm256_div_pd(y_values, rarity));
                _mm256_storeu_pd(zs.data() + i, _mm256_div_pd(z_v, rarity));
            }
            for (; i < ny; ++i) {
                const std::size_t idx = static_cast<std::size_t>(i);
                const double rarity = rarity_values.data()[idx];
                xs[idx] = x / rarity;
                ys[idx] = (y0 + static_cast<double>(i) * dy) / rarity;
                zs[idx] = z / rarity;
            }

            noise::sample_batch(*n.noise_ptr, xs.data(), ys.data(), zs.data(), count, out);
            const __m256d sign_mask = _mm256_set1_pd(-0.0);
            i = 0;
            for (; i + 4 <= ny; i += 4) {
                const __m256d sampled = _mm256_loadu_pd(out + i);
                const __m256d abs_sampled = _mm256_andnot_pd(sign_mask, sampled);
                _mm256_storeu_pd(out + i, _mm256_mul_pd(abs_sampled, _mm256_loadu_pd(rarity_values.data() + i)));
            }
            for (; i < ny; ++i) {
                out[i] = std::abs(out[i]) * rarity_values.data()[static_cast<std::size_t>(i)];
            }
            return true;
        }

        default:
            return false;
    }
}

} // namespace lattice::world::gen::densityfunction
