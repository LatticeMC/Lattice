#include "world/gen/densityfunction/beardifier.hpp"

#include <array>
#include <cmath>

namespace lattice::world::gen::densityfunction::beardifier {

namespace {

inline double length_sq(double x, double y, double z) noexcept {
    return x * x + y * y + z * z;
}

inline double length3(double x, double y, double z) noexcept {
    return std::sqrt(length_sq(x, y, z));
}

inline double clamp_map(double value,
                        double old_start, double old_end,
                        double new_start, double new_end) noexcept {
    const double t = (value - old_start) / (old_end - old_start);
    if (t <= 0.0) return new_start;
    if (t >= 1.0) return new_end;
    return new_start + t * (new_end - new_start);
}

inline double fast_inv_sqrt(double x) noexcept {
    return 1.0 / std::sqrt(x);
}

inline double compute_beard_contribution_kernel(int x, double y, int z) noexcept {
    const double d = length_sq(static_cast<double>(x), y, static_cast<double>(z));
    return std::exp(-d / 16.0);
}

constexpr int kBeardKernelRadius = 12;
constexpr int kBeardKernelSize = 24;
constexpr int kBeardKernelCount = kBeardKernelSize * kBeardKernelSize * kBeardKernelSize;

const std::array<float, kBeardKernelCount>& beard_kernel() noexcept {
    static const std::array<float, kBeardKernelCount> kernel = [] {
        std::array<float, kBeardKernelCount> out{};
        for (int i = 0; i < kBeardKernelSize; ++i) {
            for (int j = 0; j < kBeardKernelSize; ++j) {
                for (int k = 0; k < kBeardKernelSize; ++k) {
                    out[static_cast<std::size_t>(i * kBeardKernelSize * kBeardKernelSize + j * kBeardKernelSize + k)] =
                        static_cast<float>(compute_beard_contribution_kernel(j - kBeardKernelRadius,
                                                                            static_cast<double>(k - kBeardKernelRadius) + 0.5,
                                                                            i - kBeardKernelRadius));
                }
            }
        }
        return out;
    }();
    return kernel;
}

inline bool in_kernel_range(int value) noexcept {
    return value >= 0 && value < kBeardKernelSize;
}

inline double get_bury_contribution(double x, double y, double z) noexcept {
    return clamp_map(length3(x, y, z), 0.0, 6.0, 1.0, 0.0);
}

inline double get_beard_contribution(int x, int y, int z, int height) noexcept {
    const int i = x + kBeardKernelRadius;
    const int j = y + kBeardKernelRadius;
    const int k = z + kBeardKernelRadius;
    if (!in_kernel_range(i) || !in_kernel_range(j) || !in_kernel_range(k)) return 0.0;
    const double d = static_cast<double>(height) + 0.5;
    const double d1 = length_sq(static_cast<double>(x), d, static_cast<double>(z));
    const double d2 = -d * fast_inv_sqrt(d1 / 2.0) / 2.0;
    return d2 * beard_kernel()[static_cast<std::size_t>(k * kBeardKernelSize * kBeardKernelSize + i * kBeardKernelSize + j)];
}

} // namespace

double compute(const RigidPiece* pieces, int piece_count,
               const Junction* junctions, int junction_count,
               int block_x, int block_y, int block_z) noexcept {
    double d = 0.0;

    for (int idx = 0; idx < piece_count; ++idx) {
        const auto& rigid = pieces[idx];
        const int dx = std::max(0, std::max(rigid.min_x - block_x, block_x - rigid.max_x));
        const int dz = std::max(0, std::max(rigid.min_z - block_z, block_z - rigid.max_z));
        const int base_y = rigid.min_y + rigid.ground_level_delta;
        const int dy = block_y - base_y;

        int adjusted_y = 0;
        switch (rigid.terrain_adjustment) {
            case TerrainAdjustment::kNone:
                adjusted_y = 0;
                break;
            case TerrainAdjustment::kBury:
            case TerrainAdjustment::kBeardThin:
                adjusted_y = dy;
                break;
            case TerrainAdjustment::kBeardBox:
                adjusted_y = std::max(0, std::max(base_y - block_y, block_y - rigid.max_y));
                break;
            case TerrainAdjustment::kEncapsulate:
                adjusted_y = std::max(0, std::max(rigid.min_y - block_y, block_y - rigid.max_y));
                break;
        }

        switch (rigid.terrain_adjustment) {
            case TerrainAdjustment::kNone:
                break;
            case TerrainAdjustment::kBury:
                d += get_bury_contribution(static_cast<double>(dx), adjusted_y / 2.0, static_cast<double>(dz));
                break;
            case TerrainAdjustment::kBeardThin:
            case TerrainAdjustment::kBeardBox:
                d += get_beard_contribution(dx, adjusted_y, dz, dy) * 0.8;
                break;
            case TerrainAdjustment::kEncapsulate:
                d += get_bury_contribution(dx / 2.0, adjusted_y / 2.0, dz / 2.0) * 0.8;
                break;
        }
    }

    for (int idx = 0; idx < junction_count; ++idx) {
        const auto& j = junctions[idx];
        const int dx = block_x - j.source_x;
        const int dy = block_y - j.source_ground_y;
        const int dz = block_z - j.source_z;
        d += get_beard_contribution(dx, dy, dz, dy) * 0.4;
    }

    return d;
}

} // namespace lattice::world::gen::densityfunction::beardifier
