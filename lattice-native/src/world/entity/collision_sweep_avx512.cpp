// AVX-512 swept-AABB clamp. The predicate and reduction order intentionally
// mirror the AVX2 kernel; this only widens the obstacle batch to eight lanes.

#include "world/entity/collision_sweep.hpp"

#if defined(_MSC_VER)
#  include <intrin.h>
#else
#  include <immintrin.h>
#endif

namespace lattice::world::entity {

namespace {

[[nodiscard]] __mmask8 overlap_mask(__m512d m_min, __m512d m_max,
                                    __m512d o_min, __m512d o_max) noexcept {
    const __m512d epsilon = _mm512_set1_pd(kCollisionEpsilon);
    const __mmask8 first = _mm512_cmp_pd_mask(
        _mm512_sub_pd(m_max, o_min), epsilon, _CMP_GT_OQ);
    const __mmask8 second = _mm512_cmp_pd_mask(
        _mm512_sub_pd(o_max, m_min), epsilon, _CMP_GT_OQ);
    return first & second;
}

[[nodiscard]] __m512d gather8(const double* obstacles, std::size_t base,
                               int element_offset) noexcept {
    return _mm512_setr_pd(
        obstacles[(base + 0) * kCollisionAabbStride + element_offset],
        obstacles[(base + 1) * kCollisionAabbStride + element_offset],
        obstacles[(base + 2) * kCollisionAabbStride + element_offset],
        obstacles[(base + 3) * kCollisionAabbStride + element_offset],
        obstacles[(base + 4) * kCollisionAabbStride + element_offset],
        obstacles[(base + 5) * kCollisionAabbStride + element_offset],
        obstacles[(base + 6) * kCollisionAabbStride + element_offset],
        obstacles[(base + 7) * kCollisionAabbStride + element_offset]);
}

[[nodiscard]] double clamp_axis_obstacle_scalar(int axis, const double* moving,
                                                 double desired, const double* obstacle) noexcept {
    const int a1 = (axis + 1) % 3;
    const int a2 = (axis + 2) % 3;
    if (moving[a1] - obstacle[a1 + 3] >= -kCollisionEpsilon
        || moving[a1 + 3] - obstacle[a1] <= kCollisionEpsilon) return desired;
    if (moving[a2] - obstacle[a2 + 3] >= -kCollisionEpsilon
        || moving[a2 + 3] - obstacle[a2] <= kCollisionEpsilon) return desired;
    if (desired > 0.0) {
        const double max_move = obstacle[axis] - moving[axis + 3];
        if (max_move < -kCollisionEpsilon) return desired;
        if (max_move < desired) return max_move;
    } else if (desired < 0.0) {
        const double max_move = obstacle[axis + 3] - moving[axis];
        if (max_move > kCollisionEpsilon) return desired;
        if (max_move > desired) return max_move;
    }
    return desired;
}

} // namespace

double calc_max_offset_avx512(int axis, const double* moving,
                              double desired,
                              const double* obstacles,
                              std::size_t obstacle_count) noexcept {
    if (!moving || axis < 0 || axis > 2 || !obstacles || obstacle_count == 0) return desired;
    if (desired == 0.0) return 0.0;

    const int a1 = (axis + 1) % 3;
    const int a2 = (axis + 2) % 3;
    const __m512d m_min_a = _mm512_set1_pd(moving[axis]);
    const __m512d m_max_a = _mm512_set1_pd(moving[axis + 3]);
    const __m512d m_min_b1 = _mm512_set1_pd(moving[a1]);
    const __m512d m_max_b1 = _mm512_set1_pd(moving[a1 + 3]);
    const __m512d m_min_b2 = _mm512_set1_pd(moving[a2]);
    const __m512d m_max_b2 = _mm512_set1_pd(moving[a2 + 3]);
    const __m512d desired_v = _mm512_set1_pd(desired);
    const bool moving_positive = desired > 0.0;
    __m512d accumulator = desired_v;
    const std::size_t full = obstacle_count / 8;

    for (std::size_t chunk = 0; chunk < full; ++chunk) {
        const std::size_t base = chunk * 8;
        const __m512d o_min_a = gather8(obstacles, base, axis);
        const __m512d o_max_a = gather8(obstacles, base, axis + 3);
        const __m512d o_min_b1 = gather8(obstacles, base, a1);
        const __m512d o_max_b1 = gather8(obstacles, base, a1 + 3);
        const __m512d o_min_b2 = gather8(obstacles, base, a2);
        const __m512d o_max_b2 = gather8(obstacles, base, a2 + 3);
        const __mmask8 overlap = overlap_mask(m_min_b1, m_max_b1, o_min_b1, o_max_b1)
            & overlap_mask(m_min_b2, m_max_b2, o_min_b2, o_max_b2);

        if (moving_positive) {
            const __m512d gap = _mm512_sub_pd(o_min_a, m_max_a);
            const __mmask8 valid = overlap & _mm512_cmp_pd_mask(
                gap, _mm512_set1_pd(-kCollisionEpsilon), _CMP_GE_OQ);
            accumulator = _mm512_min_pd(accumulator,
                _mm512_mask_blend_pd(valid, desired_v, gap));
        } else {
            const __m512d gap = _mm512_sub_pd(o_max_a, m_min_a);
            const __mmask8 valid = overlap & _mm512_cmp_pd_mask(
                gap, _mm512_set1_pd(kCollisionEpsilon), _CMP_LE_OQ);
            accumulator = _mm512_max_pd(accumulator,
                _mm512_mask_blend_pd(valid, desired_v, gap));
        }
    }

    alignas(64) double lanes[8];
    _mm512_store_pd(lanes, accumulator);
    double result = lanes[0];
    for (int lane = 1; lane < 8; ++lane) {
        if (moving_positive ? lanes[lane] < result : lanes[lane] > result) {
            result = lanes[lane];
        }
    }
    for (std::size_t i = full * 8; i < obstacle_count; ++i) {
        result = clamp_axis_obstacle_scalar(axis, moving, result,
                                            obstacles + i * kCollisionAabbStride);
    }
    return result;
}

void adjust_movement_avx512(const double* moving,
                            double* out_movement,
                            const double* obstacles,
                            std::size_t obstacle_count) noexcept {
    if (!moving || !out_movement || !obstacles || obstacle_count == 0) return;
    double moved[6];
    for (int component = 0; component < 6; ++component) moved[component] = moving[component];
    constexpr int axis_order[3] = {1, 0, 2};
    for (int position = 0; position < 3; ++position) {
        const int axis = axis_order[position];
        const double offset = calc_max_offset_avx512(
            axis, moved, out_movement[axis], obstacles, obstacle_count);
        out_movement[axis] = offset;
        if (offset != 0.0) {
            moved[axis] += offset;
            moved[axis + 3] += offset;
        }
    }
}

} // namespace lattice::world::entity
