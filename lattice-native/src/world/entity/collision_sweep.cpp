// Scalar swept-AABB collision clamp + runtime dispatcher.

#include "world/entity/collision_sweep.hpp"

#include <atomic>

#include "lattice/dispatch.hpp"

namespace lattice::world::entity {

namespace {

inline double clamp_axis_obstacle(int axis,
                                  const double* m,   // moving box (minXYZ, maxXYZ)
                                  double desired,
                                  const double* o    // obstacle box
                                 ) noexcept {
    // Determine the two cross axes for an overlap test.
    const int a1 = (axis + 1) % 3;
    const int a2 = (axis + 2) % 3;

    // Overlap on cross axes with epsilon tolerance, matching Paper's
    // CollisionUtil.collideX/Y/Z semantics:
    //   overlap iff  m_min < o_max - epsilon  &&  m_max > o_min + epsilon
    // Negated skip condition:
    //   skip iff  m_min >= o_max - epsilon  ||  m_max <= o_min + epsilon
    const double m_min_a1 = m[a1];
    const double m_max_a1 = m[a1 + 3];
    const double o_min_a1 = o[a1];
    const double o_max_a1 = o[a1 + 3];
    if (m_min_a1 - o_max_a1 >= -kCollisionEpsilon
            || m_max_a1 - o_min_a1 <= kCollisionEpsilon) return desired;

    const double m_min_a2 = m[a2];
    const double m_max_a2 = m[a2 + 3];
    const double o_min_a2 = o[a2];
    const double o_max_a2 = o[a2 + 3];
    if (m_min_a2 - o_max_a2 >= -kCollisionEpsilon
            || m_max_a2 - o_min_a2 <= kCollisionEpsilon) return desired;

    // Now resolve the clamp on `axis`.
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

double calc_max_offset_scalar(int axis, const double* moving,
                              double desired,
                              const double* obstacles,
                              std::size_t obstacle_count) noexcept {
    if (!moving || axis < 0 || axis > 2) return desired;
    if (!obstacles || obstacle_count == 0) return desired;
    if (desired == 0.0) return 0.0;

    for (std::size_t i = 0; i < obstacle_count; ++i) {
        desired = clamp_axis_obstacle(axis, moving, desired,
                                      obstacles + i * kCollisionAabbStride);
        if (desired == 0.0) break;
    }
    return desired;
}

void adjust_movement_scalar(const double* moving,
                            double* out_movement,
                            const double* obstacles,
                            std::size_t obstacle_count) noexcept {
    if (!moving || !out_movement) return;
    if (!obstacles || obstacle_count == 0) return;

    // We need a mutable copy of the moving box because each axis
    // resolution shifts it (offsetting by the clamped distance) before
    // the next axis's overlap test runs.
    double mv[6];
    for (int k = 0; k < 6; ++k) mv[k] = moving[k];

    // Process Y first (Mojang's gravity-priority order).
    constexpr int axis_order[3] = { 1, 0, 2 };

    for (int idx = 0; idx < 3; ++idx) {
        const int axis  = axis_order[idx];
        const double dv = calc_max_offset_scalar(
            axis, mv, out_movement[axis], obstacles, obstacle_count);
        out_movement[axis] = dv;
        if (dv != 0.0) {
            mv[axis]     += dv;
            mv[axis + 3] += dv;
        }
    }
}

// ---- Runtime dispatch ------------------------------------------------------

namespace {

using AdjustFn = void (*)(const double*, double*, const double*, std::size_t) noexcept;
using CalcFn = double (*)(int, const double*, double, const double*, std::size_t) noexcept;

std::atomic<AdjustFn> g_adjust{&adjust_movement_scalar};
std::atomic<CalcFn>   g_calc{&calc_max_offset_scalar};
std::atomic<bool>     g_initialised{false};

} // namespace

void init_collision_dispatch() noexcept {
    if (g_initialised.load(std::memory_order_acquire)) return;
    AdjustFn fn = &adjust_movement_scalar;
    CalcFn cfn = &calc_max_offset_scalar;
    const auto& f = lattice::cpu::features();
    (void)f;

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
    if (f.avx512f) {
        fn = &adjust_movement_avx512;
        cfn = &calc_max_offset_avx512;
    } else if (f.avx2) {
        fn = &adjust_movement_avx2;
        cfn = &calc_max_offset_avx2;
    }
#elif defined(__aarch64__) || defined(_M_ARM64)
    if (f.neon) { fn = &adjust_movement_neon; cfn = &calc_max_offset_neon; }
#endif

    g_adjust.store(fn, std::memory_order_release);
    g_calc.store(cfn, std::memory_order_release);
    g_initialised.store(true, std::memory_order_release);
}

void adjust_movement(const double* moving,
                     double* out_movement,
                     const double* obstacles,
                     std::size_t obstacle_count) noexcept {
    if (!g_initialised.load(std::memory_order_acquire)) {
        init_collision_dispatch();
    }
    g_adjust.load(std::memory_order_acquire)(
        moving, out_movement, obstacles, obstacle_count);
}

// ---- Runtime-dispatched single-axis entry -----------------------------------

double calc_max_offset(int axis, const double* moving,
                       double desired,
                       const double* obstacles,
                       std::size_t obstacle_count) noexcept {
    if (!g_initialised.load(std::memory_order_acquire)) {
        init_collision_dispatch();
    }
    return g_calc.load(std::memory_order_acquire)(
        axis, moving, desired, obstacles, obstacle_count);
}

} // namespace lattice::world::entity
