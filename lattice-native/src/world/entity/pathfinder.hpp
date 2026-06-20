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

struct PathfinderMasks {
    std::uint8_t* passable = nullptr;
    std::uint8_t* standing = nullptr;
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

} // namespace lattice::world::entity
