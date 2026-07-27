#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>

namespace lattice::world::entity {

struct PathfinderConfig {
    float max_range = 0.0F;
    int max_visited_nodes = 0;
    int reach_range = 0;
    float fudge = 1.5F;
};

struct PathfinderNode {
    int x = 0;
    int y = 0;
    int z = 0;
    float g = 0.0F;
    float h = 0.0F;
    float f = 0.0F;
    float walked_distance = 0.0F;
    int came_from = -1;
    std::int8_t type = 0;
    std::int8_t flags = 0;
    float cost_malus = 0.0F;
};

struct PathfinderInputs {
    const std::int8_t* path_types = nullptr;
    /// Per-cell floor level, mirroring `WalkNodeEvaluator.getFloorLevel(BlockPos)`:
    /// the Y coordinate an entity actually stands at, which for non-cuboid shapes
    /// (slabs, stairs, farmland, snow layers) is NOT the integer cell Y. Indexed
    /// identically to `path_types`. Required to reproduce the
    /// `floorLevel - nodeFloorLevel > getMobJumpHeight()` early-out in
    /// `findAcceptedNode`; without it the native search mis-judges step-ups onto
    /// partial blocks. May be null, in which case the integer cell Y is assumed.
    const float* floor_levels = nullptr;
    int region_min_x = 0;
    int region_min_y = 0;
    int region_min_z = 0;
    int region_size_x = 0;
    int region_size_y = 0;
    int region_size_z = 0;

    int start_x = 0;
    int start_y = 0;
    int start_z = 0;

    const int* target_x = nullptr;
    const int* target_y = nullptr;
    const int* target_z = nullptr;
    int target_count = 0;

    PathfinderConfig config{};

    int entity_width = 1;
    int entity_height = 1;
    float max_up_step = 1.0F;
    int max_fall_distance = 3;
    const float* pathfinding_malus = nullptr;
    int pathfinding_malus_count = 0;

    /// `WalkNodeEvaluator.getMobJumpHeight()` = max(1.125, maxUpStep). Kept as an
    /// explicit input rather than derived, so the Java side stays the single source
    /// of truth for the constant.
    float mob_jump_height = 1.125F;
    /// Mirrors `mob.getBbWidth()`. `tryJumpOn` only performs its extra gap
    /// collision probe when this is < 1.0, and `isDiagonalValid` has a fence
    /// special case gated on < 0.5.
    float bb_width = 0.6F;
    /// `WalkNodeEvaluator.canWalkOverFences()`.
    bool can_walk_over_fences = false;
    /// Purpur's `level.purpurConfig.mobsIgnoreRails`, which disables the
    /// UNPASSABLE_RAIL guard in the `tryJumpOn` branch of `findAcceptedNode`.
    bool mobs_ignore_rails = false;
    /// `canFloat()` / `isAmphibious()`, which select between the WATER branches
    /// of `findAcceptedNode`.
    bool can_float = false;
    bool is_amphibious = false;
    /// Lowest buildable Y (`level.getMinY()`). Carried for reference only: the
    /// downward scans in `find_accepted_node` stop at `region_min_y` instead,
    /// because the snapshot holds no path types below its own floor. Where the
    /// snapshot is shallower than the world, native gives up and Java retries —
    /// conservative, never a wrong result. Kept so the bound can be tightened
    /// later without touching all four layers again.
    int level_min_y = 0;
};

struct PathfinderResult {
    std::vector<PathfinderNode> path{};
    int target_index = -1;
    bool reached_target = false;
};

struct PathfinderOutput {
    int* coords = nullptr;
    int capacity_nodes = 0;
    int path_length = 0;
    int target_index = -1;
    bool reached_target = false;
};

struct PathfinderScratch {
    std::vector<std::uint64_t> passable{};
    std::vector<std::uint64_t> standing{};
    std::vector<int> grid_to_node{};
    std::vector<std::uint32_t> grid_stamp{};
    std::uint32_t current_stamp = 1;
    std::vector<int> heap_index{};
    std::vector<int> heap_entries{};
    std::vector<PathfinderNode> nodes{};
};

struct PathfinderMasks {
    std::uint64_t* passable = nullptr;
    std::uint64_t* standing = nullptr;
};

void build_pathfinder_masks_scalar(const std::int8_t* path_types,
                                   std::size_t count,
                                   const float* pathfinding_malus,
                                   int pathfinding_malus_count,
                                   PathfinderMasks masks) noexcept;

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
void build_pathfinder_masks_avx2(const std::int8_t* path_types,
                                 std::size_t count,
                                 const float* pathfinding_malus,
                                 int pathfinding_malus_count,
                                 PathfinderMasks masks) noexcept;
#endif

#if defined(__aarch64__) || defined(_M_ARM64)
void build_pathfinder_masks_neon(const std::int8_t* path_types,
                                 std::size_t count,
                                 const float* pathfinding_malus,
                                 int pathfinding_malus_count,
                                 PathfinderMasks masks) noexcept;
#endif

void init_pathfinder_dispatch() noexcept;

void build_pathfinder_masks(const std::int8_t* path_types,
                            std::size_t count,
                            const float* pathfinding_malus,
                            int pathfinding_malus_count,
                            PathfinderMasks masks) noexcept;

[[nodiscard]] PathfinderResult find_path(const PathfinderInputs& inputs) noexcept;

[[nodiscard]] bool find_path_into(const PathfinderInputs& inputs,
                                  PathfinderOutput& output) noexcept;

[[nodiscard]] bool find_path_into(const PathfinderInputs& inputs,
                                  PathfinderOutput& output,
                                  PathfinderScratch& scratch) noexcept;

} // namespace lattice::world::entity
