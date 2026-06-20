// NEON specialisation of the swept-AABB clamp. 2-wide float64x2_t lanes.
// Same algorithm as the AVX2 TU, scaled down for NEON's smaller vectors.

#include "world/entity/collision_sweep.hpp"

#if defined(__aarch64__) || defined(_M_ARM64)
#  include <arm_neon.h>
#endif

namespace lattice::world::entity {

#if defined(__aarch64__) || defined(_M_ARM64)

namespace {

inline float64x2_t gather2(const double* obstacles, std::size_t base,
                           int element_offset) noexcept {
    const float64x2_t v = {
        obstacles[(base + 0) * kCollisionAabbStride + element_offset],
        obstacles[(base + 1) * kCollisionAabbStride + element_offset]
    };
    return v;
}

// Overlap with epsilon tolerance: overlap iff m_max - o_min > eps && o_max - m_min > eps
inline uint64x2_t overlap_mask(float64x2_t m_min, float64x2_t m_max,
                               float64x2_t o_min, float64x2_t o_max) noexcept {
    const float64x2_t eps = vdupq_n_f64(kCollisionEpsilon);
    const float64x2_t gap1 = vsubq_f64(m_max, o_min);
    const float64x2_t gap2 = vsubq_f64(o_max, m_min);
    const uint64x2_t cmp1 = vcgtq_f64(gap1, eps);
    const uint64x2_t cmp2 = vcgtq_f64(gap2, eps);
    return vandq_u64(cmp1, cmp2);
}

inline double clamp_axis_obstacle_scalar(int axis, const double* m,
                                         double desired, const double* o) noexcept {
    const int a1 = (axis + 1) % 3;
    const int a2 = (axis + 2) % 3;
    if (m[a1] - o[a1 + 3] >= -kCollisionEpsilon || m[a1 + 3] - o[a1] <= kCollisionEpsilon) return desired;
    if (m[a2] - o[a2 + 3] >= -kCollisionEpsilon || m[a2 + 3] - o[a2] <= kCollisionEpsilon) return desired;
    const double m_min = m[axis];
    const double m_max = m[axis + 3];
    const double o_min = o[axis];
    const double o_max = o[axis + 3];
    if (desired > 0.0) {
        const double max_move = o_min - m_max;
        if (max_move < -kCollisionEpsilon) return desired;
        if (max_move < desired) return max_move;
    } else if (desired < 0.0) {
        const double max_move = o_max - m_min;
        if (max_move > kCollisionEpsilon) return desired;
        if (max_move > desired) return max_move;
    }
    return desired;
}

} // namespace

double calc_max_offset_neon(int axis, const double* moving,
                            double desired,
                            const double* obstacles,
                            std::size_t obstacle_count) noexcept {
    if (!moving || axis < 0 || axis > 2 || !obstacles || obstacle_count == 0) return desired;
    if (desired == 0.0) return 0.0;

    const int a1 = (axis + 1) % 3;
    const int a2 = (axis + 2) % 3;

    const float64x2_t m_min_a  = vdupq_n_f64(moving[axis]);
    const float64x2_t m_max_a  = vdupq_n_f64(moving[axis + 3]);
    const float64x2_t m_min_b1 = vdupq_n_f64(moving[a1]);
    const float64x2_t m_max_b1 = vdupq_n_f64(moving[a1 + 3]);
    const float64x2_t m_min_b2 = vdupq_n_f64(moving[a2]);
    const float64x2_t m_max_b2 = vdupq_n_f64(moving[a2 + 3]);

    const bool moving_positive = desired > 0.0;
    const float64x2_t desired_v = vdupq_n_f64(desired);
    float64x2_t acc = desired_v;
    const std::size_t full = obstacle_count / 2;

    for (std::size_t i = 0; i < full; ++i) {
        const std::size_t base = i * 2;
        const float64x2_t o_min_a  = gather2(obstacles, base, axis);
        const float64x2_t o_max_a  = gather2(obstacles, base, axis + 3);
        const float64x2_t o_min_b1 = gather2(obstacles, base, a1);
        const float64x2_t o_max_b1 = gather2(obstacles, base, a1 + 3);
        const float64x2_t o_min_b2 = gather2(obstacles, base, a2);
        const float64x2_t o_max_b2 = gather2(obstacles, base, a2 + 3);

        const uint64x2_t ov1 = overlap_mask(m_min_b1, m_max_b1, o_min_b1, o_max_b1);
        const uint64x2_t ov2 = overlap_mask(m_min_b2, m_max_b2, o_min_b2, o_max_b2);
        const uint64x2_t ovAll = vandq_u64(ov1, ov2);

        if (moving_positive) {
            const float64x2_t gap    = vsubq_f64(o_min_a, m_max_a);
            const float64x2_t neg_eps = vdupq_n_f64(-kCollisionEpsilon);
            const uint64x2_t  valid  = vandq_u64(ovAll, vcgeq_f64(gap, neg_eps));
            // bslq with the lane bitmask: where valid, use gap; else desired.
            const float64x2_t cand  = vbslq_f64(valid, gap, desired_v);
            acc = vminq_f64(acc, cand);
        } else {
            const float64x2_t gap  = vsubq_f64(o_max_a, m_min_a);
            const float64x2_t eps  = vdupq_n_f64(kCollisionEpsilon);
            const uint64x2_t  valid = vandq_u64(ovAll, vcleq_f64(gap, eps));
            const float64x2_t cand  = vbslq_f64(valid, gap, desired_v);
            acc = vmaxq_f64(acc, cand);
        }
    }

    double r;
    if (moving_positive) {
        const double l0 = vgetq_lane_f64(acc, 0);
        const double l1 = vgetq_lane_f64(acc, 1);
        r = l0 < l1 ? l0 : l1;
    } else {
        const double l0 = vgetq_lane_f64(acc, 0);
        const double l1 = vgetq_lane_f64(acc, 1);
        r = l0 > l1 ? l0 : l1;
    }

    for (std::size_t i = full * 2; i < obstacle_count; ++i) {
        r = clamp_axis_obstacle_scalar(axis, moving, r,
                                       obstacles + i * kCollisionAabbStride);
    }
    return r;
}

void adjust_movement_neon(const double* moving,
                          double* out_movement,
                          const double* obstacles,
                          std::size_t obstacle_count) noexcept {
    if (!moving || !out_movement) return;
    if (!obstacles || obstacle_count == 0) return;

    double mv[6];
    for (int k = 0; k < 6; ++k) mv[k] = moving[k];

    constexpr int axis_order[3] = { 1, 0, 2 };
    for (int idx = 0; idx < 3; ++idx) {
        const int axis = axis_order[idx];
        const double dv = calc_max_offset_neon(
            axis, mv, out_movement[axis], obstacles, obstacle_count);
        out_movement[axis] = dv;
        if (dv != 0.0) {
            mv[axis]     += dv;
            mv[axis + 3] += dv;
        }
    }
}

#endif // aarch64

} // namespace lattice::world::entity
