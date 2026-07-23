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

inline int floor_to_int(double v) noexcept {
    return static_cast<int>(std::floor(v));
}

inline void fill_column(double value, int ny, double* out) noexcept {
    const __m256d vv = _mm256_set1_pd(value);
    int i = 0;
    for (; i + 4 <= ny; i += 4) _mm256_storeu_pd(out + i, vv);
    for (; i < ny; ++i) out[i] = value;
}

inline bool all_zero_column(const double* values, int ny) noexcept {
    const __m256d zero = _mm256_setzero_pd();
    int i = 0;
    for (; i + 4 <= ny; i += 4) {
        const __m256d v = _mm256_loadu_pd(values + i);
        if (_mm256_movemask_pd(_mm256_cmp_pd(v, zero, _CMP_EQ_OQ)) != 0xF) return false;
    }
    for (; i < ny; ++i) {
        if (values[i] != 0.0) return false;
    }
    return true;
}

inline bool all_equal_column(const double* values, int ny, double value) noexcept {
    const __m256d expected = _mm256_set1_pd(value);
    int i = 0;
    for (; i + 4 <= ny; i += 4) {
        const __m256d v = _mm256_loadu_pd(values + i);
        if (_mm256_movemask_pd(_mm256_cmp_pd(v, expected, _CMP_EQ_OQ)) != 0xF) return false;
    }
    for (; i < ny; ++i) {
        if (values[i] != value) return false;
    }
    return true;
}

inline void scan_range_column(const double* values, int ny, double min_inclusive, double max_exclusive,
                              bool& any_in, bool& any_out) noexcept {
    const __m256d min_v = _mm256_set1_pd(min_inclusive);
    const __m256d max_v = _mm256_set1_pd(max_exclusive);
    any_in = false;
    any_out = false;
    int i = 0;
    for (; i + 4 <= ny; i += 4) {
        const __m256d v = _mm256_loadu_pd(values + i);
        const __m256d mask = _mm256_and_pd(
            _mm256_cmp_pd(v, min_v, _CMP_GE_OQ),
            _mm256_cmp_pd(v, max_v, _CMP_LT_OQ));
        const int bits = _mm256_movemask_pd(mask);
        any_in = any_in || bits != 0;
        any_out = any_out || bits != 0xF;
        if (any_in && any_out) return;
    }
    for (; i < ny; ++i) {
        const bool in_range = values[i] >= min_inclusive && values[i] < max_exclusive;
        any_in = any_in || in_range;
        any_out = any_out || !in_range;
        if (any_in && any_out) return;
    }
}

inline bool evaluate_child_column(const NodeArena& arena, NodeRef child,
                                  double x, double y0, double z, double dy,
                                  int cellX, int cellZ, int ny,
                                  CacheState* cache, double* out) noexcept {
    if (evaluate_y_column_avx2(arena, child, x, y0, z, dy, cellX, cellZ, ny, cache, out)) return true;
    evaluate_y_column_fallback(arena, child, x, y0, z, dy, cellX, cellZ, ny, cache, out);
    return true;
}

inline SharedLeafColumnEntry* shared_leaf_entry(const Node& node, CacheState* cache) noexcept {
    if (!node.shared_batch_leaf || !cache || node.cache_slot_id < 0
        || static_cast<std::size_t>(node.cache_slot_id) >= cache->shared_leaf_columns.size()) return nullptr;
    return &cache->shared_leaf_columns[static_cast<std::size_t>(node.cache_slot_id)];
}

inline bool load_shared_leaf(const Node& node, CacheState* cache,
                             double x, double y0, double z, double dy,
                             int cellX, int cellZ, int ny, double* out) noexcept {
    auto* entry = shared_leaf_entry(node, cache);
    if (!entry || !entry->valid || entry->x != x || entry->y0 != y0 || entry->z != z
        || entry->dy != dy || entry->cellX != cellX || entry->cellZ != cellZ
        || entry->ny != ny || entry->values.size() < static_cast<std::size_t>(ny)) return false;
    std::copy_n(entry->values.data(), ny, out);
    return true;
}

inline void store_shared_leaf(const Node& node, CacheState* cache,
                              double x, double y0, double z, double dy,
                              int cellX, int cellZ, int ny, const double* values) {
    auto* entry = shared_leaf_entry(node, cache);
    if (!entry) return;
    entry->x = x;
    entry->y0 = y0;
    entry->z = z;
    entry->dy = dy;
    entry->cellX = cellX;
    entry->cellZ = cellZ;
    entry->ny = ny;
    entry->values.assign(values, values + ny);
    entry->valid = true;
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
            fill_column(value, ny, out);
            return true;
        }

        case NodeKind::kYClampedGradient: {
            const double dy_total = static_cast<double>(n.i1 - n.i0);
            if (dy_total == 0.0) {
                const double value = (n.d0 + n.d1) * 0.5;
                fill_column(value, ny, out);
                return true;
            }
            if (n.i0 < n.i1) {
                const double y_last = y0 + static_cast<double>(ny - 1) * dy;
                const double y_min = std::min(y0, y_last);
                const double y_max = std::max(y0, y_last);
                if (y_max <= static_cast<double>(n.i0)) {
                    fill_column(n.d0, ny, out);
                    return true;
                }
                if (y_min >= static_cast<double>(n.i1)) {
                    fill_column(n.d1, ny, out);
                    return true;
                }
            }

            const __m256d from_y = _mm256_set1_pd(static_cast<double>(n.i0));
            const __m256d to_y = _mm256_set1_pd(static_cast<double>(n.i1));
            const __m256d from_v = _mm256_set1_pd(n.d0);
            const __m256d delta_v = _mm256_set1_pd(n.d1 - n.d0);
            const __m256d inv_dy = _mm256_set1_pd(1.0 / dy_total);
            const __m256d step = _mm256_set_pd(3.0 * dy, 2.0 * dy, dy, 0.0);
            const __m256d dy4 = _mm256_set1_pd(4.0 * dy);
            __m256d yv = _mm256_add_pd(_mm256_set1_pd(y0), step);
            int i = 0;
            for (; i + 4 <= ny; i += 4) {
                __m256d y = yv;
                y = _mm256_min_pd(_mm256_max_pd(y, from_y), to_y);
                const __m256d t = _mm256_mul_pd(_mm256_sub_pd(y, from_y), inv_dy);
                _mm256_storeu_pd(out + i, _mm256_add_pd(from_v, _mm256_mul_pd(t, delta_v)));
                yv = _mm256_add_pd(yv, dy4);
            }
            for (; i < ny; ++i) out[i] = y_clamped_gradient(n.i0, n.i1, n.d0, n.d1, y0 + static_cast<double>(i) * dy);
            return true;
        }

        case NodeKind::kNoise: {
            if (load_shared_leaf(n, cache, x, y0, z, dy, cellX, cellZ, ny, out)) return true;
            if (!n.noise_ptr) {
                fill_column(0.0, ny, out);
                store_shared_leaf(n, cache, x, y0, z, dy, cellX, cellZ, ny, out);
                return true;
            }
            noise::sample_y_column(*n.noise_ptr,
                                   x * n.d0,
                                   y0 * n.d1,
                                   z * n.d0,
                                   dy * n.d1,
                                   static_cast<std::size_t>(ny),
                                   out);
            store_shared_leaf(n, cache, x, y0, z, dy, cellX, cellZ, ny, out);
            return true;
        }

        case NodeKind::kShiftA:
        case NodeKind::kShiftB: {
            if (load_shared_leaf(n, cache, x, y0, z, dy, cellX, cellZ, ny, out)) return true;
            const double value = !n.noise_ptr ? 0.0
                : (n.kind == NodeKind::kShiftA
                    ? noise::sample(*n.noise_ptr, x * 0.25, 0.0, z * 0.25) * 4.0
                    : noise::sample(*n.noise_ptr, z * 0.25, x * 0.25, 0.0) * 4.0);
            fill_column(value, ny, out);
            store_shared_leaf(n, cache, x, y0, z, dy, cellX, cellZ, ny, out);
            return true;
        }

        case NodeKind::kShift: {
            if (load_shared_leaf(n, cache, x, y0, z, dy, cellX, cellZ, ny, out)) return true;
            if (!n.noise_ptr) {
                fill_column(0.0, ny, out);
                store_shared_leaf(n, cache, x, y0, z, dy, cellX, cellZ, ny, out);
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
            store_shared_leaf(n, cache, x, y0, z, dy, cellX, cellZ, ny, out);
            return true;
        }

        case NodeKind::kShiftedNoise: {
            if (load_shared_leaf(n, cache, x, y0, z, dy, cellX, cellZ, ny, out)) return true;
            if (!n.noise_ptr) {
                fill_column(0.0, ny, out);
                store_shared_leaf(n, cache, x, y0, z, dy, cellX, cellZ, ny, out);
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
            const __m256d dy4 = _mm256_set1_pd(4.0 * dy);
            __m256d yv = _mm256_add_pd(_mm256_set1_pd(y0), step);
            int i = 0;
            for (; i + 4 <= ny; i += 4) {
                const __m256d y_values = yv;
                _mm256_storeu_pd(xs.data() + i, _mm256_add_pd(x_base, _mm256_loadu_pd(sx.data() + i)));
                _mm256_storeu_pd(ys.data() + i, _mm256_add_pd(_mm256_mul_pd(y_values, y_scale), _mm256_loadu_pd(sy.data() + i)));
                _mm256_storeu_pd(zs.data() + i, _mm256_add_pd(z_base, _mm256_loadu_pd(sz.data() + i)));
                yv = _mm256_add_pd(yv, dy4);
            }
            for (; i < ny; ++i) {
                const std::size_t idx = static_cast<std::size_t>(i);
                const double y = y0 + static_cast<double>(i) * dy;
                xs[idx] = x * n.d0 + sx.data()[idx];
                ys[idx] = y * n.d1 + sy.data()[idx];
                zs[idx] = z * n.d0 + sz.data()[idx];
            }
            noise::sample_batch(*n.noise_ptr, xs.data(), ys.data(), zs.data(), count, out);
            store_shared_leaf(n, cache, x, y0, z, dy, cellX, cellZ, ny, out);
            return true;
        }

        case NodeKind::kBlendDensity:
            return evaluate_child_column(arena, n.a, x, y0, z, dy, cellX, cellZ, ny, cache, out);

        case NodeKind::kAbs:
        case NodeKind::kSquare:
        case NodeKind::kCube:
        case NodeKind::kHalfNegative:
        case NodeKind::kQuarterNegative:
        case NodeKind::kInvert:
        case NodeKind::kSqueeze:
        case NodeKind::kClamp: {
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
            if (n.kind == NodeKind::kAbs) {
                for (; i + 4 <= ny; i += 4) {
                    const __m256d v = _mm256_loadu_pd(values.data() + i);
                    _mm256_storeu_pd(out + i, _mm256_andnot_pd(sign_mask, v));
                }
                for (; i < ny; ++i) out[i] = std::abs(values.data()[static_cast<std::size_t>(i)]);
            } else if (n.kind == NodeKind::kSquare) {
                for (; i + 4 <= ny; i += 4) {
                    const __m256d v = _mm256_loadu_pd(values.data() + i);
                    _mm256_storeu_pd(out + i, _mm256_mul_pd(v, v));
                }
                for (; i < ny; ++i) {
                    const double v = values.data()[static_cast<std::size_t>(i)];
                    out[i] = v * v;
                }
            } else if (n.kind == NodeKind::kCube) {
                for (; i + 4 <= ny; i += 4) {
                    const __m256d v = _mm256_loadu_pd(values.data() + i);
                    _mm256_storeu_pd(out + i, _mm256_mul_pd(_mm256_mul_pd(v, v), v));
                }
                for (; i < ny; ++i) {
                    const double v = values.data()[static_cast<std::size_t>(i)];
                    out[i] = v * v * v;
                }
            } else if (n.kind == NodeKind::kHalfNegative) {
                for (; i + 4 <= ny; i += 4) {
                    const __m256d v = _mm256_loadu_pd(values.data() + i);
                    _mm256_storeu_pd(out + i, _mm256_blendv_pd(v, _mm256_mul_pd(v, half), _mm256_cmp_pd(v, zero, _CMP_LT_OQ)));
                }
                for (; i < ny; ++i) {
                    const double v = values.data()[static_cast<std::size_t>(i)];
                    out[i] = v < 0.0 ? v * 0.5 : v;
                }
            } else if (n.kind == NodeKind::kQuarterNegative) {
                for (; i + 4 <= ny; i += 4) {
                    const __m256d v = _mm256_loadu_pd(values.data() + i);
                    _mm256_storeu_pd(out + i, _mm256_blendv_pd(v, _mm256_mul_pd(v, quarter), _mm256_cmp_pd(v, zero, _CMP_LT_OQ)));
                }
                for (; i < ny; ++i) {
                    const double v = values.data()[static_cast<std::size_t>(i)];
                    out[i] = v < 0.0 ? v * 0.25 : v;
                }
            } else if (n.kind == NodeKind::kInvert) {
                for (; i + 4 <= ny; i += 4) {
                    const __m256d v = _mm256_loadu_pd(values.data() + i);
                    _mm256_storeu_pd(out + i, _mm256_div_pd(one, v));
                }
                for (; i < ny; ++i) out[i] = 1.0 / values.data()[static_cast<std::size_t>(i)];
            } else if (n.kind == NodeKind::kSqueeze) {
                for (; i + 4 <= ny; i += 4) {
                    __m256d v = _mm256_loadu_pd(values.data() + i);
                    v = _mm256_min_pd(_mm256_max_pd(v, neg_one), one);
                    const __m256d v3 = _mm256_mul_pd(_mm256_mul_pd(v, v), v);
                    _mm256_storeu_pd(out + i, _mm256_sub_pd(_mm256_mul_pd(v, half), _mm256_mul_pd(v3, inv24)));
                }
                for (; i < ny; ++i) {
                    const double c = clamp_d(values.data()[static_cast<std::size_t>(i)], -1.0, 1.0);
                    out[i] = c * 0.5 - c * c * c / 24.0;
                }
            } else if (n.kind == NodeKind::kClamp) {
                for (; i + 4 <= ny; i += 4) {
                    const __m256d v = _mm256_loadu_pd(values.data() + i);
                    _mm256_storeu_pd(out + i, _mm256_min_pd(_mm256_max_pd(v, clamp_min), clamp_max));
                }
                for (; i < ny; ++i) out[i] = clamp_d(values.data()[static_cast<std::size_t>(i)], n.d0, n.d1);
            } else {
                return false;
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
                if (n.kind == NodeKind::kAdd) {
                    for (; i + 4 <= ny; i += 4) {
                        const __m256d v = _mm256_loadu_pd(left.data() + i);
                        _mm256_storeu_pd(out + i, _mm256_add_pd(v, v));
                    }
                    for (; i < ny; ++i) out[i] = left.data()[static_cast<std::size_t>(i)] + left.data()[static_cast<std::size_t>(i)];
                } else if (n.kind == NodeKind::kMul) {
                    for (; i + 4 <= ny; i += 4) {
                        const __m256d v = _mm256_loadu_pd(left.data() + i);
                        _mm256_storeu_pd(out + i, _mm256_mul_pd(v, v));
                    }
                    for (; i < ny; ++i) out[i] = left.data()[static_cast<std::size_t>(i)] * left.data()[static_cast<std::size_t>(i)];
                } else {
                    for (; i + 4 <= ny; i += 4) _mm256_storeu_pd(out + i, _mm256_loadu_pd(left.data() + i));
                    for (; i < ny; ++i) out[i] = left.data()[static_cast<std::size_t>(i)];
                }
                return true;
            }
            ColumnScratchLease right(cache, ny);
            if (n.kind == NodeKind::kMul) {
                if (all_zero_column(left.data(), ny)) {
                    fill_column(0.0, ny, out);
                    return true;
                }
            }
            if (!evaluate_child_column(arena, n.b, x, y0, z, dy, cellX, cellZ, ny, cache, right.data())) return false;
            const __m256d zero = _mm256_setzero_pd();
            int i = 0;
            if (n.kind == NodeKind::kAdd) {
                for (; i + 4 <= ny; i += 4) {
                    const __m256d a = _mm256_loadu_pd(left.data() + i);
                    const __m256d b = _mm256_loadu_pd(right.data() + i);
                    _mm256_storeu_pd(out + i, _mm256_add_pd(a, b));
                }
                for (; i < ny; ++i) out[i] = left.data()[static_cast<std::size_t>(i)] + right.data()[static_cast<std::size_t>(i)];
            } else if (n.kind == NodeKind::kMul) {
                for (; i + 4 <= ny; i += 4) {
                    const __m256d a = _mm256_loadu_pd(left.data() + i);
                    const __m256d b = _mm256_loadu_pd(right.data() + i);
                    _mm256_storeu_pd(out + i, _mm256_blendv_pd(_mm256_mul_pd(a, b), zero, _mm256_cmp_pd(a, zero, _CMP_EQ_OQ)));
                }
                for (; i < ny; ++i) {
                    const double a = left.data()[static_cast<std::size_t>(i)];
                    out[i] = a == 0.0 ? 0.0 : a * right.data()[static_cast<std::size_t>(i)];
                }
            } else if (n.kind == NodeKind::kMin) {
                for (; i + 4 <= ny; i += 4) {
                    const __m256d a = _mm256_loadu_pd(left.data() + i);
                    const __m256d b = _mm256_loadu_pd(right.data() + i);
                    _mm256_storeu_pd(out + i, _mm256_min_pd(a, b));
                }
                for (; i < ny; ++i) out[i] = std::min(left.data()[static_cast<std::size_t>(i)], right.data()[static_cast<std::size_t>(i)]);
            } else {
                for (; i + 4 <= ny; i += 4) {
                    const __m256d a = _mm256_loadu_pd(left.data() + i);
                    const __m256d b = _mm256_loadu_pd(right.data() + i);
                    _mm256_storeu_pd(out + i, _mm256_max_pd(a, b));
                }
                for (; i < ny; ++i) out[i] = std::max(left.data()[static_cast<std::size_t>(i)], right.data()[static_cast<std::size_t>(i)]);
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
            if (!evaluate_child_column(arena, n.a, x, y0, z, dy, cellX, cellZ, ny, cache, input.data())) return false;
            bool any_in = false;
            bool any_out = false;
            scan_range_column(input.data(), ny, n.d0, n.d1, any_in, any_out);
            if (!any_out) return evaluate_child_column(arena, n.b, x, y0, z, dy, cellX, cellZ, ny, cache, out);
            if (!any_in) return evaluate_child_column(arena, n.c, x, y0, z, dy, cellX, cellZ, ny, cache, out);

            ColumnScratchLease in_values(cache, ny);
            ColumnScratchLease out_values(cache, ny);
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
            if (load_shared_leaf(n, cache, x, y0, z, dy, cellX, cellZ, ny, out)) return true;
            if (!n.interp_noise_ptr) {
                fill_column(0.0, ny, out);
                store_shared_leaf(n, cache, x, y0, z, dy, cellX, cellZ, ny, out);
                return true;
            }
            noise::sample_y_column(*n.interp_noise_ptr,
                                   x,
                                   y0,
                                   z,
                                   dy,
                                   static_cast<std::size_t>(ny),
                                   out);
            store_shared_leaf(n, cache, x, y0, z, dy, cellX, cellZ, ny, out);
            return true;
        }

        case NodeKind::kInterpolated: {
            if (!cache || !cache->is_in_interpolation_loop
                || n.cache_slot_id < 0
                || n.cache_slot_id >= static_cast<int>(cache->interpolators.size())) {
                return evaluate_child_column(arena, n.a, x, y0, z, dy, cellX, cellZ, ny, cache, out);
            }
            const double value = cache->interpolators[static_cast<std::size_t>(n.cache_slot_id)].result;
            fill_column(value, ny, out);
            return true;
        }

        case NodeKind::kCache2D: {
            if (!cache || n.cache_slot_id < 0
                || n.cache_slot_id >= static_cast<int>(cache->cache_2d.size())) {
                return evaluate_child_column(arena, n.a, x, y0, z, dy, cellX, cellZ, ny, cache, out);
            }
            auto& slot = cache->cache_2d[static_cast<std::size_t>(n.cache_slot_id)];
            const int kx = floor_to_int(x);
            const int kz = floor_to_int(z);
            if (!(slot.valid && slot.x == kx && slot.z == kz)) {
                Context ctx{};
                ctx.cache = cache;
                ctx.x = x;
                ctx.y = y0;
                ctx.z = z;
                ctx.cellX = cellX;
                ctx.cellZ = cellZ;
                slot.value = evaluate(arena, n.a, ctx);
                slot.x = kx;
                slot.z = kz;
                slot.valid = true;
            }
            fill_column(slot.value, ny, out);
            return true;
        }

        case NodeKind::kFlatCache: {
            if (!cache || n.cache_slot_id < 0
                || n.cache_slot_id >= static_cast<int>(cache->flat_cache.size())) {
                return evaluate_child_column(arena, n.a, x, y0, z, dy, cellX, cellZ, ny, cache, out);
            }
            auto& slot = cache->flat_cache[static_cast<std::size_t>(n.cache_slot_id)];
            if (!(slot.valid && slot.cellX == cellX && slot.cellZ == cellZ)) {
                Context ctx{};
                ctx.cache = cache;
                ctx.x = x;
                ctx.y = y0;
                ctx.z = z;
                ctx.cellX = cellX;
                ctx.cellZ = cellZ;
                slot.value = evaluate(arena, n.a, ctx);
                slot.cellX = cellX;
                slot.cellZ = cellZ;
                slot.valid = true;
            }
            fill_column(slot.value, ny, out);
            return true;
        }

        case NodeKind::kCacheOnce: {
            if (!cache || n.cache_slot_id < 0
                || n.cache_slot_id >= static_cast<int>(cache->cache_once.size())) {
                return evaluate_child_column(arena, n.a, x, y0, z, dy, cellX, cellZ, ny, cache, out);
            }
            auto& slot = cache->cache_once[static_cast<std::size_t>(n.cache_slot_id)];
            Context ctx{};
            ctx.cache = cache;
            ctx.x = x;
            ctx.z = z;
            ctx.cellX = cellX;
            ctx.cellZ = cellZ;
            for (int i = 0; i < ny; ++i) {
                const double y = y0 + static_cast<double>(i) * dy;
                if (slot.valid && slot.x == x && slot.y == y && slot.z == z) {
                    out[i] = slot.value;
                    continue;
                }
                ctx.y = y;
                const double value = evaluate(arena, n.a, ctx);
                slot.valid = true;
                slot.x = x;
                slot.y = y;
                slot.z = z;
                slot.value = value;
                out[i] = value;
            }
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
            int i = 0;
            if (type2) {
                const __m256d r05 = _mm256_set1_pd(0.5);
                const __m256d r075 = _mm256_set1_pd(0.75);
                const __m256d r10 = _mm256_set1_pd(1.0);
                const __m256d r20 = _mm256_set1_pd(2.0);
                const __m256d r30 = _mm256_set1_pd(3.0);
                const __m256d neg075 = _mm256_set1_pd(-0.75);
                const __m256d neg05 = _mm256_set1_pd(-0.5);
                const __m256d pos05 = _mm256_set1_pd(0.5);
                const __m256d pos075 = _mm256_set1_pd(0.75);
                for (; i + 4 <= ny; i += 4) {
                    const __m256d v = _mm256_loadu_pd(input.data() + i);
                    __m256d rarity = r30;
                    rarity = _mm256_blendv_pd(rarity, r20, _mm256_cmp_pd(v, pos075, _CMP_LT_OQ));
                    rarity = _mm256_blendv_pd(rarity, r10, _mm256_cmp_pd(v, pos05, _CMP_LT_OQ));
                    rarity = _mm256_blendv_pd(rarity, r075, _mm256_cmp_pd(v, neg05, _CMP_LT_OQ));
                    rarity = _mm256_blendv_pd(rarity, r05, _mm256_cmp_pd(v, neg075, _CMP_LT_OQ));
                    _mm256_storeu_pd(rarity_values.data() + i, rarity);
                }
                for (; i < ny; ++i) {
                    const double value = input.data()[static_cast<std::size_t>(i)];
                    rarity_values.data()[static_cast<std::size_t>(i)] = value < -0.75 ? 0.5 : value < -0.5 ? 0.75 : value < 0.5 ? 1.0 : value < 0.75 ? 2.0 : 3.0;
                }
            } else {
                const __m256d r075 = _mm256_set1_pd(0.75);
                const __m256d r10 = _mm256_set1_pd(1.0);
                const __m256d r15 = _mm256_set1_pd(1.5);
                const __m256d r20 = _mm256_set1_pd(2.0);
                const __m256d neg05 = _mm256_set1_pd(-0.5);
                const __m256d zero = _mm256_setzero_pd();
                const __m256d pos05 = _mm256_set1_pd(0.5);
                for (; i + 4 <= ny; i += 4) {
                    const __m256d v = _mm256_loadu_pd(input.data() + i);
                    __m256d rarity = r20;
                    rarity = _mm256_blendv_pd(rarity, r15, _mm256_cmp_pd(v, pos05, _CMP_LT_OQ));
                    rarity = _mm256_blendv_pd(rarity, r10, _mm256_cmp_pd(v, zero, _CMP_LT_OQ));
                    rarity = _mm256_blendv_pd(rarity, r075, _mm256_cmp_pd(v, neg05, _CMP_LT_OQ));
                    _mm256_storeu_pd(rarity_values.data() + i, rarity);
                }
                for (; i < ny; ++i) {
                    const double value = input.data()[static_cast<std::size_t>(i)];
                    rarity_values.data()[static_cast<std::size_t>(i)] = value < -0.5 ? 0.75 : value < 0.0 ? 1.0 : value < 0.5 ? 1.5 : 2.0;
                }
            }

            if (ny > 0) {
                const double rarity = rarity_values.data()[0];
                if (all_equal_column(rarity_values.data(), ny, rarity)) {
                    noise::sample_y_column(*n.noise_ptr,
                                           x / rarity,
                                           y0 / rarity,
                                           z / rarity,
                                           dy / rarity,
                                           static_cast<std::size_t>(ny),
                                           out);
                    const __m256d sign_mask = _mm256_set1_pd(-0.0);
                    const __m256d rarity_v = _mm256_set1_pd(rarity);
                    i = 0;
                    for (; i + 4 <= ny; i += 4) {
                        const __m256d sampled = _mm256_loadu_pd(out + i);
                        _mm256_storeu_pd(out + i, _mm256_mul_pd(_mm256_andnot_pd(sign_mask, sampled), rarity_v));
                    }
                    for (; i < ny; ++i) out[i] = std::abs(out[i]) * rarity;
                    return true;
                }
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
            const __m256d dy4 = _mm256_set1_pd(4.0 * dy);
            __m256d yv = _mm256_add_pd(_mm256_set1_pd(y0), step);
            i = 0;
            for (; i + 4 <= ny; i += 4) {
                const __m256d rarity = _mm256_loadu_pd(rarity_values.data() + i);
                _mm256_storeu_pd(xs.data() + i, _mm256_div_pd(x_v, rarity));
                _mm256_storeu_pd(ys.data() + i, _mm256_div_pd(yv, rarity));
                _mm256_storeu_pd(zs.data() + i, _mm256_div_pd(z_v, rarity));
                yv = _mm256_add_pd(yv, dy4);
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

        case NodeKind::kBeardifier: {
            if (!n.beardifier_ptr) {
                fill_column(0.0, ny, out);
                return true;
            }
            const int block_x = static_cast<int>(x);
            const int block_z = static_cast<int>(z);
            int i = 0;
            for (; i < ny; ++i) {
                out[i] = beardifier::compute(*n.beardifier_ptr,
                                             block_x,
                                             static_cast<int>(y0 + static_cast<double>(i) * dy),
                                             block_z);
            }
            return true;
        }

        default:
            return false;
    }
}

} // namespace lattice::world::gen::densityfunction
