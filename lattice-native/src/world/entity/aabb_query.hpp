/**
 * @file aabb_query.hpp
 * @brief Batched AABB-vs-AABB intersection scan.
 *
 * Used by the Minecraft server's entity-collision and entity-area-of-effect
 * hotpath: when the world is asked "which entities intersect this box?",
 * vanilla iterates a section's entity list and tests each one's
 * {@code Entity.getBoundingBox().intersects(queryBox)}. This native helper
 * batches the test for Q query boxes against E entity boxes and returns
 * a flat Q × E bitmap.
 *
 * Box layout (matches Mojang's {@code net.minecraft.util.math.Box}):
 *
 *   double[6 * N] aabbs;
 *   per-box offset 0..5: minX, minY, minZ, maxX, maxY, maxZ
 *
 * Intersection predicate (strict in vanilla, mirrored exactly here):
 *
 *   intersects(A, B) =
 *       A.minX <= B.maxX && A.maxX >= B.minX
 *    && A.minY <= B.maxY && A.maxY >= B.minY
 *    && A.minZ <= B.maxZ && A.maxZ >= B.minZ
 *
 * Output: `visibility[i * row_longs + (j/64)]` has bit `j%64` set iff
 * `intersects(queries[i], entities[j])`, where
 * `row_longs = ceil(E / 64)`.
 *
 * Determinism
 * -----------
 * Pure compare-and-test; no floating-point arithmetic, no FMA hazard.
 * SIMD variants produce bit-identical bitmaps to the scalar reference,
 * verified by the diff-verify shadow on the Java side.
 */

#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::world::entity {

inline constexpr std::size_t kAabbStride = 6; // minX, minY, minZ, maxX, maxY, maxZ

/// `ceil(entity_count / 64)` — longs per query row.
[[nodiscard]] inline constexpr std::size_t aabb_row_longs(std::size_t entity_count) noexcept {
    return (entity_count + 63) / 64;
}

/// Scalar reference. Always available.
void aabb_scan_scalar(const double* query_aabbs, std::size_t query_count,
                      const double* entity_aabbs, std::size_t entity_count,
                      std::uint64_t* visibility) noexcept;

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
void aabb_scan_avx2(const double* query_aabbs, std::size_t query_count,
                    const double* entity_aabbs, std::size_t entity_count,
                    std::uint64_t* visibility) noexcept;
#endif

#if defined(__aarch64__) || defined(_M_ARM64)
void aabb_scan_neon(const double* query_aabbs, std::size_t query_count,
                    const double* entity_aabbs, std::size_t entity_count,
                    std::uint64_t* visibility) noexcept;
#endif

/// Runtime-dispatched entry point.
void aabb_scan(const double* query_aabbs, std::size_t query_count,
               const double* entity_aabbs, std::size_t entity_count,
               std::uint64_t* visibility) noexcept;

void init_aabb_dispatch() noexcept;

} // namespace lattice::world::entity
