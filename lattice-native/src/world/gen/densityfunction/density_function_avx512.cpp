#include "world/gen/densityfunction/density_function.hpp"

#include <algorithm>
#include <cmath>
#include <immintrin.h>
#include <vector>

namespace lattice::world::gen::densityfunction {
namespace {

// Match std::min/std::max exactly: on an unordered comparison they retain
// the first operand, whereas the hardware min/max instructions choose the
// second operand for NaNs.
inline __m512d std_min_pd(__m512d a, __m512d b) noexcept {
    const __mmask8 choose_b = _mm512_cmp_pd_mask(b, a, _CMP_LT_OQ);
    return _mm512_mask_blend_pd(choose_b, a, b);
}

inline __m512d std_max_pd(__m512d a, __m512d b) noexcept {
    const __mmask8 choose_b = _mm512_cmp_pd_mask(a, b, _CMP_LT_OQ);
    return _mm512_mask_blend_pd(choose_b, a, b);
}

inline __m512d clamp_pd(__m512d value, __m512d lo, __m512d hi) noexcept {
    return std_max_pd(lo, std_min_pd(hi, value));
}

inline void fill_column(double value, int ny, double* out) noexcept {
    const __m512d vv = _mm512_set1_pd(value);
    int i = 0;
    for (; i + 8 <= ny; i += 8) _mm512_storeu_pd(out + i, vv);
    for (; i < ny; ++i) out[i] = value;
}

inline bool evaluate_child(const NodeArena& arena, NodeRef child,
                           double x, double y0, double z, double dy,
                           int cell_x, int cell_z, int ny,
                           CacheState* cache, double* out) noexcept {
    const bool child_avx512 = arena.has_capability(child, NodeCapability::kAvx512);
    if (child_avx512
        && evaluate_y_column_avx512(arena, child, x, y0, z, dy, cell_x, cell_z, ny, cache, out)) return true;
    if (density_avx2_available()
        && evaluate_y_column_avx2(arena, child, x, y0, z, dy, cell_x, cell_z, ny, cache, out)) return true;
    evaluate_y_column_fallback(arena, child, x, y0, z, dy, cell_x, cell_z, ny, cache, out);
    return true;
}

inline bool evaluate_tail(const NodeArena& arena, NodeRef root,
                          double x, double y0, double z, double dy,
                          int cell_x, int cell_z, int ny,
                          CacheState* cache, double* out) noexcept {
    // The tail is intentionally delegated to AVX2/scalar. In particular, a
    // root without the AVX512 capability must never re-enter the failing
    // AVX512 evaluator while unwinding a vectorized parent.
    if (density_avx2_available()
        && evaluate_y_column_avx2(arena, root, x, y0, z, dy, cell_x, cell_z, ny, cache, out)) {
        return true;
    }
    evaluate_y_column_fallback(arena, root, x, y0, z, dy, cell_x, cell_z, ny, cache, out);
    return true;
}

struct Scratch {
    CacheState* cache;
    std::vector<double> local;
    std::size_t index = 0;
    double* values = nullptr;

    Scratch(CacheState* cache_in, int ny) : cache(cache_in) {
        const std::size_t count = static_cast<std::size_t>(ny);
        if (!cache) {
            local.resize(count);
            values = local.data();
            return;
        }
        index = cache->scratch_column_depth++;
        if (index < cache->scratch_columns.size()
            && cache->scratch_columns[index].size() == count) {
            values = cache->scratch_columns[index].data();
            return;
        }

        // Direct AVX-512 calls may bypass the public preparation entry point.
        if (cache->scratch_columns.size() <= index) cache->scratch_columns.resize(index + 1u);
        auto& column = cache->scratch_columns[index];
        column.resize(count);
        values = column.data();
    }
    ~Scratch() { if (cache) --cache->scratch_column_depth; }
    double* data() noexcept { return values; }
    Scratch(const Scratch&) = delete;
    Scratch& operator=(const Scratch&) = delete;
};

inline __m512d squeeze(__m512d value) noexcept {
    const __m512d one = _mm512_set1_pd(1.0);
    const __m512d neg_one = _mm512_set1_pd(-1.0);
    const __m512d half = _mm512_set1_pd(0.5);
    const __m512d inv24 = _mm512_set1_pd(1.0 / 24.0);
    value = clamp_pd(value, neg_one, one);
    const __m512d cube = _mm512_mul_pd(_mm512_mul_pd(value, value), value);
    return _mm512_sub_pd(_mm512_mul_pd(value, half), _mm512_mul_pd(cube, inv24));
}

inline bool all_zero_column(const double* values, int ny) noexcept {
    const __m512d zero = _mm512_setzero_pd();
    int i = 0;
    for (; i + 8 <= ny; i += 8) {
        if (_mm512_cmp_pd_mask(_mm512_loadu_pd(values + i), zero, _CMP_EQ_OQ) != 0xFF) return false;
    }
    for (; i < ny; ++i) {
        if (values[i] != 0.0) return false;
    }
    return true;
}

inline void scan_range_column(const double* values, int ny,
                              double min_inclusive, double max_exclusive,
                              bool& any_in, bool& any_out) noexcept {
    const __m512d min_v = _mm512_set1_pd(min_inclusive);
    const __m512d max_v = _mm512_set1_pd(max_exclusive);
    any_in = false;
    any_out = false;
    int i = 0;
    for (; i + 8 <= ny; i += 8) {
        const __m512d value = _mm512_loadu_pd(values + i);
        const __mmask8 in = _mm512_cmp_pd_mask(value, min_v, _CMP_GE_OQ)
                          & _mm512_cmp_pd_mask(value, max_v, _CMP_LT_OQ);
        any_in = any_in || in != 0;
        any_out = any_out || in != 0xFF;
        if (any_in && any_out) return;
    }
    for (; i < ny; ++i) {
        const bool in = values[i] >= min_inclusive && values[i] < max_exclusive;
        any_in = any_in || in;
        any_out = any_out || !in;
        if (any_in && any_out) return;
    }
}

} // namespace

bool evaluate_y_column_avx512(const NodeArena& arena, NodeRef root,
                              double x, double y0, double z, double dy,
                              int cell_x, int cell_z, int ny,
                              CacheState* cache, double* out) noexcept {
    if (!out || ny <= 0 || root < 0 || root >= static_cast<NodeRef>(arena.nodes.size())) return false;
    const Node& node = arena.nodes[static_cast<std::size_t>(root)];
    if (!arena.has_capability(root, NodeCapability::kAvx512)) return false;

    switch (node.kind) {
        case NodeKind::kConstant:
        case NodeKind::kBlendAlpha:
        case NodeKind::kBlendOffset:
            fill_column(node.kind == NodeKind::kConstant ? node.d0 : (node.kind == NodeKind::kBlendAlpha ? 1.0 : 0.0), ny, out);
            return true;

        case NodeKind::kYClampedGradient: {
            if (node.i0 > node.i1) return false;
            const double total = static_cast<double>(node.i1 - node.i0);
            if (total == 0.0) { fill_column((node.d0 + node.d1) * 0.5, ny, out); return true; }
            const __m512d from_y = _mm512_set1_pd(static_cast<double>(node.i0));
            const __m512d to_y = _mm512_set1_pd(static_cast<double>(node.i1));
            const __m512d from_v = _mm512_set1_pd(node.d0);
            const __m512d delta_v = _mm512_set1_pd(node.d1 - node.d0);
            const __m512d inverse = _mm512_set1_pd(1.0 / total);
            const __m512d step = _mm512_set_pd(7.0 * dy, 6.0 * dy, 5.0 * dy, 4.0 * dy, 3.0 * dy, 2.0 * dy, dy, 0.0);
            __m512d ys = _mm512_add_pd(_mm512_set1_pd(y0), step);
            const __m512d advance = _mm512_set1_pd(8.0 * dy);
            int i = 0;
            for (; i + 8 <= ny; i += 8) {
                const __m512d clamped = clamp_pd(ys, from_y, to_y);
                const __m512d t = _mm512_mul_pd(_mm512_sub_pd(clamped, from_y), inverse);
                _mm512_storeu_pd(out + i, _mm512_add_pd(from_v, _mm512_mul_pd(t, delta_v)));
                ys = _mm512_add_pd(ys, advance);
            }
            for (; i < ny; ++i) {
                const double yy = std::max(static_cast<double>(node.i0), std::min(static_cast<double>(node.i1), y0 + static_cast<double>(i) * dy));
                out[i] = node.d0 + ((yy - static_cast<double>(node.i0)) / total) * (node.d1 - node.d0);
            }
            return true;
        }

        case NodeKind::kAbs:
        case NodeKind::kSquare:
        case NodeKind::kCube:
        case NodeKind::kHalfNegative:
        case NodeKind::kQuarterNegative:
        case NodeKind::kInvert:
        case NodeKind::kSqueeze:
        case NodeKind::kClamp: {
            Scratch values(cache, ny);
            evaluate_child(arena, node.a, x, y0, z, dy, cell_x, cell_z, ny, cache, values.data());
            const __m512d zero = _mm512_setzero_pd();
            const __m512d sign = _mm512_set1_pd(-0.0);
            const __m512d half = _mm512_set1_pd(0.5);
            const __m512d quarter = _mm512_set1_pd(0.25);
            const __m512d one = _mm512_set1_pd(1.0);
            const __m512d lo = _mm512_set1_pd(node.d0);
            const __m512d hi = _mm512_set1_pd(node.d1);
            int i = 0;
            for (; i + 8 <= ny; i += 8) {
                const __m512d v = _mm512_loadu_pd(values.data() + i);
                __m512d result;
                switch (node.kind) {
                    case NodeKind::kAbs: result = _mm512_andnot_pd(sign, v); break;
                    case NodeKind::kSquare: result = _mm512_mul_pd(v, v); break;
                    case NodeKind::kCube: result = _mm512_mul_pd(_mm512_mul_pd(v, v), v); break;
                    case NodeKind::kHalfNegative: result = _mm512_mask_blend_pd(_mm512_cmp_pd_mask(v, zero, _CMP_LT_OQ), v, _mm512_mul_pd(v, half)); break;
                    case NodeKind::kQuarterNegative: result = _mm512_mask_blend_pd(_mm512_cmp_pd_mask(v, zero, _CMP_LT_OQ), v, _mm512_mul_pd(v, quarter)); break;
                    case NodeKind::kInvert: result = _mm512_div_pd(one, v); break;
                    case NodeKind::kSqueeze: result = squeeze(v); break;
                    case NodeKind::kClamp: result = clamp_pd(v, lo, hi); break;
                    default: return false;
                }
                _mm512_storeu_pd(out + i, result);
            }
            // Delegate scalar tails to the already parity-tested evaluator.
            if (i < ny) return evaluate_tail(arena, root, x, y0 + static_cast<double>(i) * dy, z, dy, cell_x, cell_z, ny - i, cache, out + i);
            return true;
        }

        case NodeKind::kAdd:
        case NodeKind::kMul:
        case NodeKind::kMin:
        case NodeKind::kMax: {
            Scratch left(cache, ny);
            evaluate_child(arena, node.a, x, y0, z, dy, cell_x, cell_z, ny, cache, left.data());
            if (node.a == node.b) {
                int i = 0;
                for (; i + 8 <= ny; i += 8) {
                    const __m512d value = _mm512_loadu_pd(left.data() + i);
                    const __m512d result = node.kind == NodeKind::kAdd ? _mm512_add_pd(value, value)
                        : node.kind == NodeKind::kMul ? _mm512_mul_pd(value, value) : value;
                    _mm512_storeu_pd(out + i, result);
                }
                if (i < ny) return evaluate_tail(arena, root, x, y0 + static_cast<double>(i) * dy, z, dy, cell_x, cell_z, ny - i, cache, out + i);
                return true;
            }
            if (node.kind == NodeKind::kMul && all_zero_column(left.data(), ny)) {
                fill_column(0.0, ny, out);
                return true;
            }
            Scratch right(cache, ny);
            evaluate_child(arena, node.b, x, y0, z, dy, cell_x, cell_z, ny, cache, right.data());
            int i = 0;
            for (; i + 8 <= ny; i += 8) {
                const __m512d a = _mm512_loadu_pd(left.data() + i);
                const __m512d b = _mm512_loadu_pd(right.data() + i);
                const __m512d result = node.kind == NodeKind::kAdd ? _mm512_add_pd(a, b)
                    : node.kind == NodeKind::kMul ? _mm512_mask_blend_pd(_mm512_cmp_pd_mask(a, _mm512_setzero_pd(), _CMP_EQ_OQ), _mm512_mul_pd(a, b), _mm512_setzero_pd())
                    : node.kind == NodeKind::kMin ? std_min_pd(a, b) : std_max_pd(a, b);
                _mm512_storeu_pd(out + i, result);
            }
            if (i < ny) return evaluate_tail(arena, root, x, y0 + static_cast<double>(i) * dy, z, dy, cell_x, cell_z, ny - i, cache, out + i);
            return true;
        }

        case NodeKind::kMapRange: {
            Scratch input(cache, ny);
            evaluate_child(arena, node.a, x, y0, z, dy, cell_x, cell_z, ny, cache, input.data());
            const __m512d from_min = _mm512_set1_pd(node.d0);
            const __m512d inv_range = _mm512_set1_pd(1.0 / (node.d1 - node.d0));
            const __m512d to_min = _mm512_set1_pd(node.d2);
            const __m512d to_delta = _mm512_set1_pd(node.d3 - node.d2);
            int i = 0;
            for (; i + 8 <= ny; i += 8) {
                const __m512d value = _mm512_loadu_pd(input.data() + i);
                const __m512d t = _mm512_mul_pd(_mm512_sub_pd(value, from_min), inv_range);
                _mm512_storeu_pd(out + i, _mm512_add_pd(to_min, _mm512_mul_pd(t, to_delta)));
            }
            if (i < ny) return evaluate_tail(arena, root, x, y0 + static_cast<double>(i) * dy, z, dy, cell_x, cell_z, ny - i, cache, out + i);
            return true;
        }

        case NodeKind::kRangeChoice: {
            Scratch input(cache, ny);
            evaluate_child(arena, node.a, x, y0, z, dy, cell_x, cell_z, ny, cache, input.data());
            bool any_in = false;
            bool any_out = false;
            scan_range_column(input.data(), ny, node.d0, node.d1, any_in, any_out);
            if (!any_out) return evaluate_child(arena, node.b, x, y0, z, dy, cell_x, cell_z, ny, cache, out);
            if (!any_in) return evaluate_child(arena, node.c, x, y0, z, dy, cell_x, cell_z, ny, cache, out);

            Scratch in_values(cache, ny);
            Scratch out_values(cache, ny);
            evaluate_child(arena, node.b, x, y0, z, dy, cell_x, cell_z, ny, cache, in_values.data());
            evaluate_child(arena, node.c, x, y0, z, dy, cell_x, cell_z, ny, cache, out_values.data());
            const __m512d min_v = _mm512_set1_pd(node.d0);
            const __m512d max_v = _mm512_set1_pd(node.d1);
            int i = 0;
            for (; i + 8 <= ny; i += 8) {
                const __m512d value = _mm512_loadu_pd(input.data() + i);
                const __mmask8 in = _mm512_cmp_pd_mask(value, min_v, _CMP_GE_OQ)
                                  & _mm512_cmp_pd_mask(value, max_v, _CMP_LT_OQ);
                _mm512_storeu_pd(out + i, _mm512_mask_blend_pd(in,
                    _mm512_loadu_pd(out_values.data() + i), _mm512_loadu_pd(in_values.data() + i)));
            }
            if (i < ny) return evaluate_tail(arena, root, x, y0 + static_cast<double>(i) * dy, z, dy, cell_x, cell_z, ny - i, cache, out + i);
            return true;
        }

        case NodeKind::kLerp: {
            Scratch t_values(cache, ny);
            Scratch low_values(cache, ny);
            Scratch high_values(cache, ny);
            evaluate_child(arena, node.a, x, y0, z, dy, cell_x, cell_z, ny, cache, t_values.data());
            evaluate_child(arena, node.b, x, y0, z, dy, cell_x, cell_z, ny, cache, low_values.data());
            evaluate_child(arena, node.c, x, y0, z, dy, cell_x, cell_z, ny, cache, high_values.data());
            int i = 0;
            for (; i + 8 <= ny; i += 8) {
                const __m512d t = _mm512_loadu_pd(t_values.data() + i);
                const __m512d low = _mm512_loadu_pd(low_values.data() + i);
                const __m512d high = _mm512_loadu_pd(high_values.data() + i);
                _mm512_storeu_pd(out + i, _mm512_add_pd(low, _mm512_mul_pd(t, _mm512_sub_pd(high, low))));
            }
            if (i < ny) return evaluate_tail(arena, root, x, y0 + static_cast<double>(i) * dy, z, dy, cell_x, cell_z, ny - i, cache, out + i);
            return true;
        }

        default:
            return false;
    }
}

} // namespace lattice::world::gen::densityfunction
