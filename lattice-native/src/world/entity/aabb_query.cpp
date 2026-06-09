// Scalar AABB intersection scan + runtime dispatcher.

#include "world/entity/aabb_query.hpp"

#include <atomic>
#include <cstring>

#include "lattice/dispatch.hpp"

namespace lattice::world::entity {

void aabb_scan_scalar(const double* query_aabbs, std::size_t query_count,
                      const double* entity_aabbs, std::size_t entity_count,
                      std::uint64_t* visibility) noexcept {
    if (query_count == 0 || !visibility) return;
    const std::size_t row_l = aabb_row_longs(entity_count);
    std::memset(visibility, 0, row_l * query_count * sizeof(std::uint64_t));
    if (!query_aabbs || entity_count == 0 || !entity_aabbs) return;

    for (std::size_t q = 0; q < query_count; ++q) {
        const double qMinX = query_aabbs[q * kAabbStride + 0];
        const double qMinY = query_aabbs[q * kAabbStride + 1];
        const double qMinZ = query_aabbs[q * kAabbStride + 2];
        const double qMaxX = query_aabbs[q * kAabbStride + 3];
        const double qMaxY = query_aabbs[q * kAabbStride + 4];
        const double qMaxZ = query_aabbs[q * kAabbStride + 5];
        std::uint64_t* row = visibility + q * row_l;

        for (std::size_t e = 0; e < entity_count; ++e) {
            const double eMinX = entity_aabbs[e * kAabbStride + 0];
            const double eMinY = entity_aabbs[e * kAabbStride + 1];
            const double eMinZ = entity_aabbs[e * kAabbStride + 2];
            const double eMaxX = entity_aabbs[e * kAabbStride + 3];
            const double eMaxY = entity_aabbs[e * kAabbStride + 4];
            const double eMaxZ = entity_aabbs[e * kAabbStride + 5];

            const bool overlap =
                qMinX <= eMaxX && qMaxX >= eMinX &&
                qMinY <= eMaxY && qMaxY >= eMinY &&
                qMinZ <= eMaxZ && qMaxZ >= eMinZ;
            if (overlap) {
                row[e >> 6] |= std::uint64_t{1} << (e & 63);
            }
        }
    }
}

namespace {

using AabbScanFn = void (*)(const double*, std::size_t,
                            const double*, std::size_t,
                            std::uint64_t*) noexcept;

std::atomic<AabbScanFn> g_aabb_scan{&aabb_scan_scalar};
std::atomic<bool>       g_aabb_initialised{false};

} // namespace

void init_aabb_dispatch() noexcept {
    if (g_aabb_initialised.load(std::memory_order_acquire)) return;
    AabbScanFn fn = &aabb_scan_scalar;
    const auto& f = lattice::cpu::features();
    (void)f;

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
    if (f.avx2) fn = &aabb_scan_avx2;
#elif defined(__aarch64__) || defined(_M_ARM64)
    if (f.neon) fn = &aabb_scan_neon;
#endif

    g_aabb_scan.store(fn, std::memory_order_release);
    g_aabb_initialised.store(true, std::memory_order_release);
}

void aabb_scan(const double* query_aabbs, std::size_t query_count,
               const double* entity_aabbs, std::size_t entity_count,
               std::uint64_t* visibility) noexcept {
    if (!g_aabb_initialised.load(std::memory_order_acquire)) {
        init_aabb_dispatch();
    }
    g_aabb_scan.load(std::memory_order_acquire)(
        query_aabbs, query_count, entity_aabbs, entity_count, visibility);
}

} // namespace lattice::world::entity
