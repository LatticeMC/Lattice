#include "world/gen/densityfunction/beardifier.hpp"

#include <algorithm>
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
constexpr int kBeardKernelMaxDistance = kBeardKernelRadius - 1;
constexpr int kBeardKernelSize = 24;
constexpr int kBeardKernelCount = kBeardKernelSize * kBeardKernelSize * kBeardKernelSize;

inline int floor_div(int value, int divisor) noexcept {
    const int q = value / divisor;
    const int r = value % divisor;
    return (r != 0 && ((r < 0) != (divisor < 0))) ? (q - 1) : q;
}

inline int bucket_index(const SpatialBucketGrid& grid, int bucket_x, int bucket_z) noexcept {
    const int local_x = bucket_x - grid.min_bucket_x;
    const int local_z = bucket_z - grid.min_bucket_z;
    if (local_x < 0 || local_x >= grid.bucket_width || local_z < 0 || local_z >= grid.bucket_depth) {
        return -1;
    }
    return local_x + local_z * grid.bucket_width;
}

template <typename Callback>
void for_each_piece_candidate(const BeardifierData& data,
                              int block_x, int block_z,
                              Callback&& callback) noexcept {
    if (data.buckets.empty()) {
        for (int idx = 0; idx < static_cast<int>(data.pieces.size()); ++idx) {
            callback(idx);
        }
        return;
    }

    const int bx = floor_div(block_x, SpatialBucketGrid::kBucketSize);
    const int bz = floor_div(block_z, SpatialBucketGrid::kBucketSize);
    const int bucket = bucket_index(data.buckets, bx, bz);
    if (bucket < 0) return;
    for (const int idx : data.buckets.piece_buckets[static_cast<std::size_t>(bucket)]) {
        callback(idx);
    }
}

template <typename Callback>
void for_each_junction_candidate(const BeardifierData& data,
                                 int block_x, int block_z,
                                 Callback&& callback) noexcept {
    if (data.buckets.empty()) {
        for (int idx = 0; idx < static_cast<int>(data.junctions.size()); ++idx) {
            callback(idx);
        }
        return;
    }

    const int bx = floor_div(block_x, SpatialBucketGrid::kBucketSize);
    const int bz = floor_div(block_z, SpatialBucketGrid::kBucketSize);
    const int bucket = bucket_index(data.buckets, bx, bz);
    if (bucket < 0) return;
    for (const int idx : data.buckets.junction_buckets[static_cast<std::size_t>(bucket)]) {
        callback(idx);
    }
}

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

void prepare_spatial_buckets(BeardifierData& data) noexcept {
    data.buckets = {};

    bool have_bounds = false;
    int min_x = 0;
    int min_z = 0;
    int max_x = 0;
    int max_z = 0;

    auto extend_bounds = [&](int lo_x, int hi_x, int lo_z, int hi_z) noexcept {
        if (!have_bounds) {
            min_x = lo_x;
            max_x = hi_x;
            min_z = lo_z;
            max_z = hi_z;
            have_bounds = true;
            return;
        }
        min_x = std::min(min_x, lo_x);
        max_x = std::max(max_x, hi_x);
        min_z = std::min(min_z, lo_z);
        max_z = std::max(max_z, hi_z);
    };

    for (const auto& piece : data.pieces) {
        extend_bounds(piece.min_x - kBeardKernelRadius,
                      piece.max_x + kBeardKernelRadius,
                      piece.min_z - kBeardKernelRadius,
                      piece.max_z + kBeardKernelRadius);
    }
    for (const auto& junction : data.junctions) {
        extend_bounds(junction.source_x - kBeardKernelRadius,
                      junction.source_x + kBeardKernelRadius,
                      junction.source_z - kBeardKernelRadius,
                      junction.source_z + kBeardKernelRadius);
    }

    if (!have_bounds) return;

    const int min_bucket_x = floor_div(min_x, SpatialBucketGrid::kBucketSize);
    const int max_bucket_x = floor_div(max_x, SpatialBucketGrid::kBucketSize);
    const int min_bucket_z = floor_div(min_z, SpatialBucketGrid::kBucketSize);
    const int max_bucket_z = floor_div(max_z, SpatialBucketGrid::kBucketSize);
    const int bucket_width = max_bucket_x - min_bucket_x + 1;
    const int bucket_depth = max_bucket_z - min_bucket_z + 1;
    if (bucket_width <= 0 || bucket_depth <= 0) return;

    data.buckets.min_bucket_x = min_bucket_x;
    data.buckets.min_bucket_z = min_bucket_z;
    data.buckets.bucket_width = bucket_width;
    data.buckets.bucket_depth = bucket_depth;

    const std::size_t bucket_count = static_cast<std::size_t>(bucket_width) * static_cast<std::size_t>(bucket_depth);
    data.buckets.piece_buckets.resize(bucket_count);
    data.buckets.junction_buckets.resize(bucket_count);

    for (int idx = 0; idx < static_cast<int>(data.pieces.size()); ++idx) {
        const auto& piece = data.pieces[static_cast<std::size_t>(idx)];
        const int first_bucket_x = floor_div(piece.min_x - kBeardKernelRadius, SpatialBucketGrid::kBucketSize);
        const int last_bucket_x = floor_div(piece.max_x + kBeardKernelRadius, SpatialBucketGrid::kBucketSize);
        const int first_bucket_z = floor_div(piece.min_z - kBeardKernelRadius, SpatialBucketGrid::kBucketSize);
        const int last_bucket_z = floor_div(piece.max_z + kBeardKernelRadius, SpatialBucketGrid::kBucketSize);
        for (int bz = first_bucket_z; bz <= last_bucket_z; ++bz) {
            for (int bx = first_bucket_x; bx <= last_bucket_x; ++bx) {
                const int bucket = bucket_index(data.buckets, bx, bz);
                if (bucket >= 0) {
                    data.buckets.piece_buckets[static_cast<std::size_t>(bucket)].push_back(idx);
                }
            }
        }
    }

    for (int idx = 0; idx < static_cast<int>(data.junctions.size()); ++idx) {
        const auto& junction = data.junctions[static_cast<std::size_t>(idx)];
        const int first_bucket_x = floor_div(junction.source_x - kBeardKernelRadius, SpatialBucketGrid::kBucketSize);
        const int last_bucket_x = floor_div(junction.source_x + kBeardKernelRadius, SpatialBucketGrid::kBucketSize);
        const int first_bucket_z = floor_div(junction.source_z - kBeardKernelRadius, SpatialBucketGrid::kBucketSize);
        const int last_bucket_z = floor_div(junction.source_z + kBeardKernelRadius, SpatialBucketGrid::kBucketSize);
        for (int bz = first_bucket_z; bz <= last_bucket_z; ++bz) {
            for (int bx = first_bucket_x; bx <= last_bucket_x; ++bx) {
                const int bucket = bucket_index(data.buckets, bx, bz);
                if (bucket >= 0) {
                    data.buckets.junction_buckets[static_cast<std::size_t>(bucket)].push_back(idx);
                }
            }
        }
    }
}

double compute(const RigidPiece* pieces, int piece_count,
               const Junction* junctions, int junction_count,
               int block_x, int block_y, int block_z) noexcept {
    double d = 0.0;

    for (int idx = 0; idx < piece_count; ++idx) {
        const auto& rigid = pieces[idx];
        if (block_x < rigid.min_x - kBeardKernelRadius || block_x > rigid.max_x + kBeardKernelRadius) {
            continue;
        }
        if (block_z < rigid.min_z - kBeardKernelRadius || block_z > rigid.max_z + kBeardKernelRadius) {
            continue;
        }
        const int dx = std::max(0, std::max(rigid.min_x - block_x, block_x - rigid.max_x));
        const int dz = std::max(0, std::max(rigid.min_z - block_z, block_z - rigid.max_z));
        const int base_y = rigid.min_y + rigid.ground_level_delta;
        const int dy = block_y - base_y;

        int adjusted_y = 0;
        switch (rigid.terrain_adjustment) {
            case TerrainAdjustment::kNone:
                continue;
            case TerrainAdjustment::kBury:
                if (block_y < base_y - kBeardKernelMaxDistance || block_y > base_y + kBeardKernelMaxDistance) {
                    continue;
                }
                adjusted_y = dy;
                break;
            case TerrainAdjustment::kBeardThin:
                if (block_y < base_y - kBeardKernelRadius || block_y > base_y + kBeardKernelMaxDistance) {
                    continue;
                }
                adjusted_y = dy;
                break;
            case TerrainAdjustment::kBeardBox:
                if (block_y < base_y - kBeardKernelMaxDistance || block_y > rigid.max_y + kBeardKernelMaxDistance) {
                    continue;
                }
                adjusted_y = std::max(0, std::max(base_y - block_y, block_y - rigid.max_y));
                break;
            case TerrainAdjustment::kEncapsulate:
                if (block_y < rigid.min_y - kBeardKernelMaxDistance || block_y > rigid.max_y + kBeardKernelMaxDistance) {
                    continue;
                }
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

double compute(const BeardifierData& data,
               int block_x, int block_y, int block_z) noexcept {
    double d = 0.0;

    for_each_piece_candidate(data, block_x, block_z, [&](int idx) noexcept {
        const auto& rigid = data.pieces[static_cast<std::size_t>(idx)];
        if (block_x < rigid.min_x - kBeardKernelRadius || block_x > rigid.max_x + kBeardKernelRadius) {
            return;
        }
        if (block_z < rigid.min_z - kBeardKernelRadius || block_z > rigid.max_z + kBeardKernelRadius) {
            return;
        }
        const int dx = std::max(0, std::max(rigid.min_x - block_x, block_x - rigid.max_x));
        const int dz = std::max(0, std::max(rigid.min_z - block_z, block_z - rigid.max_z));
        const int base_y = rigid.min_y + rigid.ground_level_delta;
        const int dy = block_y - base_y;

        int adjusted_y = 0;
        switch (rigid.terrain_adjustment) {
            case TerrainAdjustment::kNone:
                return;
            case TerrainAdjustment::kBury:
                if (block_y < base_y - kBeardKernelMaxDistance || block_y > base_y + kBeardKernelMaxDistance) {
                    return;
                }
                adjusted_y = dy;
                break;
            case TerrainAdjustment::kBeardThin:
                if (block_y < base_y - kBeardKernelRadius || block_y > base_y + kBeardKernelMaxDistance) {
                    return;
                }
                adjusted_y = dy;
                break;
            case TerrainAdjustment::kBeardBox:
                if (block_y < base_y - kBeardKernelMaxDistance || block_y > rigid.max_y + kBeardKernelMaxDistance) {
                    return;
                }
                adjusted_y = std::max(0, std::max(base_y - block_y, block_y - rigid.max_y));
                break;
            case TerrainAdjustment::kEncapsulate:
                if (block_y < rigid.min_y - kBeardKernelMaxDistance || block_y > rigid.max_y + kBeardKernelMaxDistance) {
                    return;
                }
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
    });

    for_each_junction_candidate(data, block_x, block_z, [&](int idx) noexcept {
        const auto& junction = data.junctions[static_cast<std::size_t>(idx)];
        const int dx = block_x - junction.source_x;
        const int dy = block_y - junction.source_ground_y;
        const int dz = block_z - junction.source_z;
        d += get_beard_contribution(dx, dy, dz, dy) * 0.4;
    });

    return d;
}

} // namespace lattice::world::gen::densityfunction::beardifier
