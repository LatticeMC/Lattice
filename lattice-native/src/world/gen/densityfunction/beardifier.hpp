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

struct BeardifierData {
    std::vector<RigidPiece> pieces;
    std::vector<Junction> junctions;
};

[[nodiscard]] double compute(const RigidPiece* pieces, int piece_count,
                             const Junction* junctions, int junction_count,
                             int block_x, int block_y, int block_z) noexcept;

} // namespace lattice::world::gen::densityfunction::beardifier
