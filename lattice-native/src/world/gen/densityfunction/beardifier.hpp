#pragma once

#include <cstdint>
#include <vector>

namespace lattice::world::gen::densityfunction::beardifier {

enum class TerrainAdjustment : std::uint8_t {
    kNone = 0,
    kBury,
    kBeardThin,
    kBeardBox,
    kEncapsulate,
};

struct RigidPiece {
    int min_x;
    int min_y;
    int min_z;
    int max_x;
    int max_y;
    int max_z;
    TerrainAdjustment terrain_adjustment;
    int ground_level_delta;
};

struct Junction {
    int source_x;
    int source_ground_y;
    int source_z;
};

struct SpatialBucketGrid {
    static constexpr int kBucketSize = 16;

    int min_bucket_x = 0;
    int min_bucket_z = 0;
    int bucket_width = 0;
    int bucket_depth = 0;
    std::vector<std::vector<int>> piece_buckets;
    std::vector<std::vector<int>> junction_buckets;

    [[nodiscard]] bool empty() const noexcept {
        return bucket_width <= 0 || bucket_depth <= 0;
    }
};

struct BeardifierData {
    std::vector<RigidPiece> pieces;
    std::vector<Junction> junctions;
    SpatialBucketGrid buckets;
};

/// Build the spatial bucket grid used by the bucketed compute path.
void prepare_spatial_buckets(BeardifierData& data) noexcept;

[[nodiscard]] double compute(const RigidPiece* pieces, int piece_count,
                             const Junction* junctions, int junction_count,
                             int block_x, int block_y, int block_z) noexcept;

[[nodiscard]] double compute(const BeardifierData& data,
                             int block_x, int block_y, int block_z) noexcept;

} // namespace lattice::world::gen::densityfunction::beardifier
