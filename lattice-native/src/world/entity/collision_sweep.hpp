/**
 * @file collision_sweep.hpp
 * @brief Swept-AABB collision: clamp a desired movement against a list
 *        of static AABB obstacles, returning the maximum allowable
 *        displacement along each axis.
 *
 * Mirrors the AABB-only fast path of vanilla's
 * {@code net.minecraft.util.shape.VoxelShapes.calculateMaxOffset(axis, box, shapes, maxDist)}
 * (class_259). For each axis (X, then Y, then Z, or in whatever order
 * the caller chooses), and each obstacle:
 *
 *   1. If the moving box, *after offsetting on the already-processed
 *      axes*, fails to overlap the obstacle on the other two axes,
 *      the obstacle cannot block movement on this axis. Skip.
 *   2. Compute the per-obstacle clamp on the active axis.
 *   3. Take the min (or max, depending on sign) across all obstacles.
 *
 * This routine only handles cuboid obstacles. Vanilla's VoxelShape
 * supports arbitrary voxel grids (e.g. stairs), which we leave to the
 * Java side — `NativeCollisionSweep.adjustMovement` will fall back to
 * the JVM path when any obstacle is non-cuboid. For typical entity
 * movement the vast majority of nearby blocks ARE cuboids (full or half),
 * so the native fast path dominates throughput.
 *
 * Box layout (matches `aabb_query.hpp`):
 *
 *   double[6] aabb = { minX, minY, minZ, maxX, maxY, maxZ };
 *
 * The clamp respects the standard Mojang/Bukkit ordering: Y axis is
 * applied first (gravity), then X, then Z. Callers wanting a different
 * ordering can supply the axes in their preferred order.
 *
 * Determinism note: the algorithm is pure compare + branch + scalar
 * subtract; SIMD variants must produce bit-exact results so the
 * diff-verify shadow doesn't get tripped at edge cases (e.g. when an
 * obstacle is touching but not penetrating).
 */

#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::world::entity {

/// Stride of the AABB encoding in a flat `double[]`. Same as aabb_query.hpp.
inline constexpr std::size_t kCollisionAabbStride = 6;

/// Apply swept-AABB collision on the **single** axis indicated by `axis`
/// (0 = X, 1 = Y, 2 = Z). Returns the clamped distance.
///
/// `moving` is the current moving box; `desired` is the un-clamped
/// displacement on this axis (sign matters). `obstacles` is the list
/// of static AABBs that might collide.
///
/// Implementation note: callers that move on multiple axes should
/// `adjust_movement_full` below instead. This single-axis primitive is
/// exposed mainly for testing and for callers that want their own
/// axis-ordering logic.
[[nodiscard]] double calc_max_offset_scalar(int axis, const double* moving,
                                            double desired,
                                            const double* obstacles,
                                            std::size_t obstacle_count) noexcept;

/// Three-axis swept-AABB clamp. Applies Y, then X, then Z (Mojang's
/// vanilla order). Writes the clamped (dx, dy, dz) into `out_movement`.
///
/// Pre-condition: `out_movement[0..3]` has been pre-filled with the
/// desired displacement.
void adjust_movement_scalar(const double* moving,
                            double* out_movement,
                            const double* obstacles,
                            std::size_t obstacle_count) noexcept;

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
[[nodiscard]] double calc_max_offset_avx2(int axis, const double* moving,
                                          double desired,
                                          const double* obstacles,
                                          std::size_t obstacle_count) noexcept;

void adjust_movement_avx2(const double* moving,
                          double* out_movement,
                          const double* obstacles,
                          std::size_t obstacle_count) noexcept;
#endif

#if defined(__aarch64__) || defined(_M_ARM64)
[[nodiscard]] double calc_max_offset_neon(int axis, const double* moving,
                                          double desired,
                                          const double* obstacles,
                                          std::size_t obstacle_count) noexcept;

void adjust_movement_neon(const double* moving,
                          double* out_movement,
                          const double* obstacles,
                          std::size_t obstacle_count) noexcept;
#endif

/// Runtime-dispatched entry. Selects the fastest variant on first call.
void adjust_movement(const double* moving,
                     double* out_movement,
                     const double* obstacles,
                     std::size_t obstacle_count) noexcept;

void init_collision_dispatch() noexcept;

} // namespace lattice::world::entity
