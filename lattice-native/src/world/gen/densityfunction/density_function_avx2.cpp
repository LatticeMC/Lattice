#include "world/gen/densityfunction/density_function.hpp"

#include <algorithm>
#include <immintrin.h>

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

} // namespace

bool evaluate_y_column_avx2(const NodeArena& arena, NodeRef root,
                            double /*x*/, double y0, double /*z*/, double dy,
                            int /*cellX*/, int /*cellZ*/,
                            int ny,
                            CacheState* /*cache*/,
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

        default:
            return false;
    }
}

} // namespace lattice::world::gen::densityfunction
