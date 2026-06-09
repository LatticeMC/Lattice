// Scalar visibility scan + runtime dispatcher. See visibility_scan.hpp.

#include "world/entity/visibility_scan.hpp"

#include <atomic>
#include <cstring>

#include "lattice/dispatch.hpp"

namespace lattice::world::entity {

void scan_scalar(const double* entity_xyz, const double* entity_range_sq,
                 std::size_t entity_count,
                 const double* player_xyz,
                 std::size_t player_count,
                 std::uint64_t* visibility) noexcept {
    if (entity_count == 0 || !visibility) return;
    const std::size_t row_l = row_longs(player_count);
    std::memset(visibility, 0, row_l * entity_count * sizeof(std::uint64_t));
    if (!entity_xyz || !entity_range_sq) return;
    if (player_count == 0 || !player_xyz) return;

    for (std::size_t i = 0; i < entity_count; ++i) {
        const double ex = entity_xyz[i * 3 + 0];
        const double ey = entity_xyz[i * 3 + 1];
        const double ez = entity_xyz[i * 3 + 2];
        const double r2 = entity_range_sq[i];
        std::uint64_t* row = visibility + i * row_l;
        for (std::size_t j = 0; j < player_count; ++j) {
            const double dx = player_xyz[j * 3 + 0] - ex;
            const double dy = player_xyz[j * 3 + 1] - ey;
            const double dz = player_xyz[j * 3 + 2] - ez;
            const double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= r2) {
                row[j / kBitsPerLong] |= std::uint64_t{1} << (j % kBitsPerLong);
            }
        }
    }
}

// ---- Runtime dispatch ----------------------------------------------------

namespace {

using ScanFn = void (*)(const double*, const double*, std::size_t,
                        const double*, std::size_t,
                        std::uint64_t*) noexcept;

std::atomic<ScanFn> g_scan{&scan_scalar};
std::atomic<bool>   g_initialised{false};

} // namespace

void init_visibility_dispatch() noexcept {
    if (g_initialised.load(std::memory_order_acquire)) return;
    ScanFn fn = &scan_scalar;
    const auto& f = lattice::cpu::features();
    (void)f;

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
    if (f.avx2) fn = &scan_avx2;
#elif defined(__aarch64__) || defined(_M_ARM64)
    if (f.neon) fn = &scan_neon;
#endif

    g_scan.store(fn, std::memory_order_release);
    g_initialised.store(true, std::memory_order_release);
}

void scan(const double* entity_xyz, const double* entity_range_sq,
          std::size_t entity_count,
          const double* player_xyz,
          std::size_t player_count,
          std::uint64_t* visibility) noexcept {
    if (!g_initialised.load(std::memory_order_acquire)) {
        init_visibility_dispatch();
    }
    g_scan.load(std::memory_order_acquire)(
        entity_xyz, entity_range_sq, entity_count,
        player_xyz, player_count, visibility);
}

} // namespace lattice::world::entity
