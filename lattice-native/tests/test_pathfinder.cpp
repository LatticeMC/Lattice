#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <algorithm>
#include <cstdint>
#include <vector>

#include "world/entity/pathfinder.hpp"

using namespace lattice::world::entity;

namespace {

constexpr std::int8_t BLOCKED = 0;
constexpr std::int8_t OPEN = 1;
constexpr std::int8_t WALKABLE = 2;
constexpr std::int8_t WALKABLE_DOOR = 3;
constexpr std::int8_t WATER = 9;
constexpr std::int8_t COCOA = 23;
constexpr std::int8_t WATER_BORDER = 10;

struct Grid {
    int sx;
    int sy;
    int sz;
    std::vector<std::int8_t> cells;
    std::vector<float> malus;

    Grid(int x, int y, int z) : sx(x), sy(y), sz(z), cells(x * y * z, BLOCKED), malus(3, -1.0F) {
        malus[OPEN] = 0.0F;
        malus[WALKABLE] = 0.0F;
        malus.resize(25, 0.0F);
    }

    std::int8_t& at(int x, int y, int z) {
        return cells[(y * sz + z) * sx + x];
    }
};

PathfinderResult run(Grid& grid, int startX, int startY, int startZ,
                     int targetX, int targetY, int targetZ,
                     int maxVisitedNodes = -1, bool isAmphibious = false) {
    PathfinderInputs inputs{};
    inputs.path_types = grid.cells.data();
    inputs.region_size_x = grid.sx;
    inputs.region_size_y = grid.sy;
    inputs.region_size_z = grid.sz;
    inputs.start_x = startX;
    inputs.start_y = startY;
    inputs.start_z = startZ;
    inputs.target_x = &targetX;
    inputs.target_y = &targetY;
    inputs.target_z = &targetZ;
    inputs.target_count = 1;
    inputs.config.max_range = 64.0F;
    inputs.config.max_visited_nodes = maxVisitedNodes < 0
        ? grid.sx * grid.sy * grid.sz
        : maxVisitedNodes;
    inputs.config.reach_range = 0;
    inputs.config.fudge = 1.5F;
    inputs.max_up_step = 1.0F;
    inputs.max_fall_distance = 3;
    inputs.is_amphibious = isAmphibious;
    inputs.pathfinding_malus = grid.malus.data();
    inputs.pathfinding_malus_count = static_cast<int>(grid.malus.size());
    return find_path(inputs);
}

void fill_floor(Grid& grid, int y) {
    for (int z = 0; z < grid.sz; ++z) {
        for (int x = 0; x < grid.sx; ++x) {
            grid.at(x, y, z) = WALKABLE;
        }
    }
}

bool mask_get(const std::uint64_t* mask, std::size_t index) {
    return ((mask[index >> 6] >> (index & 63)) & 1ULL) != 0ULL;
}

int snapshot_index(int sx, int sz, int x, int y, int z) {
    return (y * sz + z) * sx + x;
}

} // namespace

TEST_CASE("pathfinder: straight path") {
    Grid grid(8, 2, 3);
    fill_floor(grid, 0);
    PathfinderResult result = run(grid, 0, 0, 1, 7, 0, 1);
    REQUIRE(result.reached_target);
    REQUIRE(result.path.size() >= 2);
    CHECK(result.path.front().x == 0);
    CHECK(result.path.back().x == 7);
}

TEST_CASE("pathfinder materializes static BlockState descriptors") {
    constexpr int sx = 5;
    constexpr int sy = 3;
    constexpr int sz = 5;
    // 0=open, 1=solid block, 2=water. The output cell is at
    // (2, 1, 2), with its collision floor supplied by the block at y=0.
    const std::int8_t descriptorTypes[] = {OPEN, BLOCKED, WATER};
    const float descriptorFloors[] = {0.0F, 0.5F, 0.0F};
    std::vector<int> cells(sx * sy * sz, 0);
    for (int z = 0; z < sz; ++z) {
        for (int x = 0; x < sx; ++x) cells[snapshot_index(sx, sz, x, 0, z)] = 1;
    }
    std::vector<float> malus(26, 0.0F);
    malus[BLOCKED] = -1.0F;
    PathfinderInputs inputs{};
    inputs.region_min_x = 2;
    inputs.region_min_y = 1;
    inputs.region_min_z = 2;
    inputs.region_size_x = 1;
    inputs.region_size_y = 1;
    inputs.region_size_z = 1;
    inputs.entity_width = 1;
    inputs.entity_height = 1;
    inputs.level_min_y = 0;
    inputs.pathfinding_malus = malus.data();
    inputs.pathfinding_malus_count = static_cast<int>(malus.size());
    PathfinderStateSnapshot snapshot{};
    snapshot.cells = cells.data();
    snapshot.raw_path_types = descriptorTypes;
    snapshot.floor_heights = descriptorFloors;
    snapshot.descriptor_count = 3;
    snapshot.size_x = sx;
    snapshot.size_y = sy;
    snapshot.size_z = sz;
    PathfinderScratch scratch{};

    REQUIRE(materialize_pathfinder_state_snapshot(inputs, snapshot, scratch));
    CHECK(scratch.materialized_path_types[0] == WALKABLE);
    CHECK(scratch.materialized_floor_levels[0] == doctest::Approx(0.5F));

    cells[snapshot_index(sx, sz, 3, 1, 2)] = 2;
    REQUIRE(materialize_pathfinder_state_snapshot(inputs, snapshot, scratch));
    CHECK(scratch.materialized_path_types[0] == WATER_BORDER);
}

TEST_CASE("pathfinder state mirror stores, loads, and invalidates cells") {
    const std::int8_t descriptors[] = {OPEN, WALKABLE};
    const float floors[] = {0.0F, 0.5F};
    const int cells[] = {0, 1};
    PathfinderStateSnapshot source{};
    source.cells = cells;
    source.raw_path_types = descriptors;
    source.floor_heights = floors;
    source.descriptor_count = 2;
    source.min_x = 32;
    source.min_y = 64;
    source.min_z = -16;
    source.size_x = 2;
    source.size_y = 1;
    source.size_z = 1;
    PathfinderStateMirror mirror{};
    store_pathfinder_state_snapshot(mirror, 17, source);

    PathfinderScratch scratch{};
    PathfinderStateSnapshot loaded{};
    REQUIRE(load_pathfinder_state_snapshot(mirror, 17, 32, 64, -16, 2, 1, 1, scratch, loaded));
    CHECK(loaded.descriptor_count == 2);
    CHECK(loaded.raw_path_types[0] == OPEN);
    CHECK(loaded.raw_path_types[1] == WALKABLE);
    CHECK(loaded.floor_heights[1] == doctest::Approx(0.5F));

    invalidate_pathfinder_state_mirror_cell(mirror, 17, 33, 64, -16);
    CHECK_FALSE(load_pathfinder_state_snapshot(mirror, 17, 32, 64, -16, 2, 1, 1, scratch, loaded));
}

TEST_CASE("pathfinder state mirror coverage probe matches the load path") {
    // One full 16^3 section plus two loose cells in the neighbouring section, so
    // the probe exercises both its valid_count fast path and its per-cell scan.
    const std::int8_t descriptors[] = {OPEN};
    const float floors[] = {0.0F};
    std::vector<int> full_cells(16 * 16 * 16, 0);
    PathfinderStateSnapshot full{};
    full.cells = full_cells.data();
    full.raw_path_types = descriptors;
    full.floor_heights = floors;
    full.descriptor_count = 1;
    full.min_x = 0;
    full.min_y = 0;
    full.min_z = 0;
    full.size_x = 16;
    full.size_y = 16;
    full.size_z = 16;
    PathfinderStateMirror mirror{};
    store_pathfinder_state_snapshot(mirror, 5, full);

    // Whole section accepted without touching `valid`.
    CHECK(state_mirror_covers(mirror, 5, 0, 0, 0, 16, 16, 16));
    CHECK(state_mirror_covers(mirror, 5, 3, 4, 5, 4, 4, 4));
    // A different world never matches, and degenerate sizes are refused.
    CHECK_FALSE(state_mirror_covers(mirror, 6, 0, 0, 0, 16, 16, 16));
    CHECK_FALSE(state_mirror_covers(mirror, 5, 0, 0, 0, 0, 16, 16));
    // Absent neighbouring section.
    CHECK_FALSE(state_mirror_covers(mirror, 5, 0, 0, 0, 17, 16, 16));

    const int loose_cells[] = {0, 0};
    PathfinderStateSnapshot loose{};
    loose.cells = loose_cells;
    loose.raw_path_types = descriptors;
    loose.floor_heights = floors;
    loose.descriptor_count = 1;
    loose.min_x = 16;
    loose.min_y = 0;
    loose.min_z = 0;
    loose.size_x = 2;
    loose.size_y = 1;
    loose.size_z = 1;
    store_pathfinder_state_snapshot(mirror, 5, loose);
    // Partially populated section: only the queried cells matter.
    CHECK(state_mirror_covers(mirror, 5, 16, 0, 0, 2, 1, 1));
    CHECK(state_mirror_covers(mirror, 5, 15, 0, 0, 3, 1, 1));
    CHECK_FALSE(state_mirror_covers(mirror, 5, 16, 0, 0, 3, 1, 1));
    CHECK_FALSE(state_mirror_covers(mirror, 5, 16, 1, 0, 2, 1, 1));

    // A single invalidated cell reopens the fast path's section.
    invalidate_pathfinder_state_mirror_cell(mirror, 5, 7, 8, 9);
    CHECK_FALSE(state_mirror_covers(mirror, 5, 7, 8, 9, 1, 1, 1));
    CHECK_FALSE(state_mirror_covers(mirror, 5, 0, 0, 0, 16, 16, 16));
    // Cells elsewhere in the same section are unaffected.
    CHECK(state_mirror_covers(mirror, 5, 0, 0, 0, 4, 4, 4));

    // Emptying a section drops it, and the probe must then report a miss rather
    // than reading a stale entry.
    invalidate_pathfinder_state_mirror_cell(mirror, 5, 16, 0, 0);
    CHECK(state_mirror_covers(mirror, 5, 17, 0, 0, 1, 1, 1));
    invalidate_pathfinder_state_mirror_cell(mirror, 5, 17, 0, 0);
    CHECK_FALSE(state_mirror_covers(mirror, 5, 17, 0, 0, 1, 1, 1));
}

TEST_CASE("pathfinder searches from a resident state mirror") {
    constexpr int sx = 5;
    constexpr int sy = 4;
    constexpr int sz = 5;
    const std::int8_t descriptors[] = {OPEN, BLOCKED};
    const float floors[] = {0.0F, 1.0F};
    std::vector<int> cells(sx * sy * sz, 0);
    for (int z = 0; z < sz; ++z) {
        for (int x = 0; x < sx; ++x) cells[snapshot_index(sx, sz, x, 0, z)] = 1;
    }
    PathfinderStateSnapshot source{};
    source.cells = cells.data();
    source.raw_path_types = descriptors;
    source.floor_heights = floors;
    source.descriptor_count = 2;
    source.min_x = -1;
    source.min_y = -1;
    source.min_z = -1;
    source.size_x = sx;
    source.size_y = sy;
    source.size_z = sz;
    PathfinderStateMirror mirror{};
    store_pathfinder_state_snapshot(mirror, 3, source);

    std::vector<float> malus(26, 0.0F);
    malus[BLOCKED] = -1.0F;
    int target_x = 2;
    int target_y = 0;
    int target_z = 1;
    int output_cells[30]{};
    PathfinderInputs inputs{};
    inputs.region_size_x = 3;
    inputs.region_size_y = 2;
    inputs.region_size_z = 3;
    inputs.start_x = 0;
    inputs.start_y = 0;
    inputs.start_z = 1;
    inputs.target_x = &target_x;
    inputs.target_y = &target_y;
    inputs.target_z = &target_z;
    inputs.target_count = 1;
    inputs.config = PathfinderConfig{16.0F, 10, 0, 1.5F};
    inputs.entity_width = 1;
    inputs.entity_height = 1;
    inputs.level_min_y = -64;
    inputs.pathfinding_malus = malus.data();
    inputs.pathfinding_malus_count = static_cast<int>(malus.size());
    int direct_cells[30]{};
    PathfinderOutput direct{direct_cells, 10};
    PathfinderScratch direct_scratch{};
    REQUIRE(find_path_from_state_snapshot_into(inputs, source, direct, direct_scratch));
    REQUIRE(direct.reached_target);
    PathfinderOutput output{output_cells, 10};
    PathfinderScratch scratch{};
    REQUIRE(find_path_from_state_mirror_into(inputs, mirror, 3, output, scratch));
    CHECK(output.reached_target);
    CHECK(output.path_length == direct.path_length);
    CHECK(std::equal(output_cells, output_cells + output.path_length * 3, direct_cells));

}

TEST_CASE("pathfinder: L shaped path around obstacle") {
    Grid grid(7, 2, 5);
    fill_floor(grid, 0);
    for (int z = 0; z < 5; ++z) {
        if (z != 4) grid.at(3, 0, z) = BLOCKED;
    }
    PathfinderResult result = run(grid, 0, 0, 2, 6, 0, 2);
    REQUIRE(result.reached_target);
    bool usedGap = false;
    for (const auto& node : result.path) {
        if (node.x == 3 && node.z == 4) usedGap = true;
    }
    CHECK(usedGap);
}

TEST_CASE("pathfinder: unreachable target returns partial path") {
    Grid grid(5, 2, 5);
    fill_floor(grid, 0);
    for (int z = 0; z < 5; ++z) grid.at(2, 0, z) = BLOCKED;
    PathfinderResult result = run(grid, 0, 0, 2, 4, 0, 2);
    CHECK_FALSE(result.reached_target);
    CHECK_FALSE(result.path.empty());
    // Target.bestNode is chosen by raw distance, not the A* h value after
    // PathFinder's 1.5 fudge factor. The closest explored node is x=1; a
    // partial path ending at the start means those two domains were mixed.
    CHECK(result.path.back().x == 1);
}

TEST_CASE("pathfinder: symmetric detour follows vanilla tie order") {
    Grid grid(7, 1, 5);
    fill_floor(grid, 0);
    for (int z = 1; z < 4; ++z) grid.at(3, 0, z) = BLOCKED;
    PathfinderResult result = run(grid, 0, 0, 2, 6, 0, 2);
    REQUIRE(result.reached_target);
    bool usedNorthGap = false;
    bool usedSouthGap = false;
    for (const auto& node : result.path) {
        if (node.x == 3 && node.z == 0) usedNorthGap = true;
        if (node.x == 3 && node.z == 4) usedSouthGap = true;
    }
    CHECK_FALSE(usedNorthGap);
    CHECK(usedSouthGap);
}

TEST_CASE("pathfinder: visited node limit matches vanilla pre-pop check") {
    Grid grid(3, 1, 3);
    fill_floor(grid, 0);
    PathfinderResult result = run(grid, 1, 0, 1, 1, 0, 1, 1);
    CHECK_FALSE(result.reached_target);
    REQUIRE(result.path.size() == 1);
    CHECK(result.path.front().x == 1);
    CHECK(result.path.front().z == 1);
}

TEST_CASE("pathfinder: one block jump") {
    Grid grid(5, 3, 3);
    for (int x = 0; x < 5; ++x) grid.at(x, 0, 1) = WALKABLE;
    grid.at(2, 0, 1) = BLOCKED;
    grid.at(2, 1, 1) = WALKABLE;
    grid.at(3, 1, 1) = OPEN;
    // Head clearance above the approach cells. Vanilla's getNeighbors only
    // grants a vertical step allowance when the cell above the origin has a
    // non-negative malus, so without modelled air the jump is (correctly)
    // refused -- BLOCKED carries malus -1.
    grid.at(0, 1, 1) = OPEN;
    grid.at(1, 1, 1) = OPEN;
    PathfinderResult result = run(grid, 0, 0, 1, 4, 0, 1);
    REQUIRE(result.reached_target);
    bool jumped = false;
    for (const auto& node : result.path) {
        if (node.x == 2 && node.y == 1) jumped = true;
    }
    CHECK(jumped);
}

TEST_CASE("pathfinder: drops to lower floor") {
    Grid grid(6, 4, 3);
    fill_floor(grid, 2);
    for (int x = 3; x < 6; ++x) grid.at(x, 0, 1) = WALKABLE;
    for (int x = 3; x < 6; ++x) grid.at(x, 1, 1) = OPEN;
    for (int x = 3; x < 6; ++x) grid.at(x, 2, 1) = OPEN;
    PathfinderResult result = run(grid, 0, 2, 1, 5, 0, 1);
    REQUIRE(result.reached_target);
    CHECK(result.path.back().y == 0);
}

TEST_CASE("pathfinder: supports safe extra standing path types") {
    Grid grid(5, 2, 3);
    for (int x = 0; x < 5; ++x) grid.at(x, 0, 1) = WALKABLE;
    grid.at(2, 0, 1) = WALKABLE_DOOR;
    grid.at(3, 0, 1) = COCOA;
    PathfinderResult result = run(grid, 0, 0, 1, 4, 0, 1);
    REQUIRE(result.reached_target);
    bool usedDoor = false;
    bool usedCocoa = false;
    for (const auto& node : result.path) {
        if (node.x == 2 && node.z == 1) usedDoor = true;
        if (node.x == 3 && node.z == 1) usedCocoa = true;
    }
    CHECK(usedDoor);
    CHECK(usedCocoa);
}

TEST_CASE("pathfinder: supports water when caller marks it passable") {
    Grid grid(5, 2, 3);
    for (int x = 0; x < 5; ++x) grid.at(x, 0, 1) = WALKABLE;
    grid.at(2, 0, 1) = WATER;
    PathfinderResult result = run(grid, 0, 0, 1, 4, 0, 1);
    REQUIRE(result.reached_target);
    bool usedWater = false;
    for (const auto& node : result.path) {
        if (node.x == 2 && node.z == 1) usedWater = true;
    }
    CHECK(usedWater);
}

TEST_CASE("pathfinder: amphibious path uses vertical water neighbours") {
    Grid grid(3, 3, 3);
    grid.at(1, 0, 1) = WATER;
    grid.at(1, 1, 1) = WATER;
    grid.at(1, 2, 1) = WATER;
    PathfinderResult result = run(grid, 1, 0, 1, 1, 2, 1, -1, true);
    REQUIRE(result.reached_target);
    REQUIRE(result.path.size() == 3);
    CHECK(result.path[1].y == 1);
}

TEST_CASE("pathfinder: amphibious snapshot marks water borders and water floor") {
    constexpr int sx = 3;
    constexpr int sy = 3;
    constexpr int sz = 3;
    const std::int8_t descriptorTypes[] = {WATER, BLOCKED};
    const float descriptorFloors[] = {0.0F, 1.0F};
    std::vector<int> cells(sx * sy * sz, 0);
    cells[snapshot_index(sx, sz, 2, 1, 1)] = 1;
    std::vector<float> malus(26, 0.0F);
    malus[BLOCKED] = -1.0F;
    PathfinderInputs inputs{};
    inputs.region_min_x = 0;
    inputs.region_min_y = 0;
    inputs.region_min_z = 0;
    inputs.region_size_x = 1;
    inputs.region_size_y = 1;
    inputs.region_size_z = 1;
    inputs.entity_width = 1;
    inputs.entity_height = 1;
    inputs.is_amphibious = true;
    inputs.level_min_y = -64;
    inputs.pathfinding_malus = malus.data();
    inputs.pathfinding_malus_count = static_cast<int>(malus.size());
    PathfinderStateSnapshot snapshot{};
    snapshot.cells = cells.data();
    snapshot.raw_path_types = descriptorTypes;
    snapshot.floor_heights = descriptorFloors;
    snapshot.descriptor_count = 2;
    snapshot.min_x = -1;
    snapshot.min_y = -1;
    snapshot.min_z = -1;
    snapshot.size_x = sx;
    snapshot.size_y = sy;
    snapshot.size_z = sz;
    PathfinderScratch scratch{};
    REQUIRE(materialize_pathfinder_state_snapshot(inputs, snapshot, scratch));
    CHECK(scratch.materialized_path_types[0] == WATER_BORDER);
    CHECK(scratch.materialized_floor_levels[0] == doctest::Approx(0.5F));
}

TEST_CASE("pathfinder: walkable door blocks diagonal shortcut") {
    Grid grid(3, 2, 3);
    fill_floor(grid, 0);
    grid.at(1, 0, 0) = WALKABLE_DOOR;
    grid.at(0, 0, 1) = WALKABLE;
    grid.at(1, 0, 1) = WALKABLE;
    PathfinderResult result = run(grid, 0, 0, 0, 2, 0, 2);
    REQUIRE(result.reached_target);
    CHECK(result.path.size() > 3);
}

TEST_CASE("pathfinder masks: scalar classifies passable and standing") {
    const std::int8_t types[] = {BLOCKED, OPEN, WALKABLE, 3};
    const float malus[] = {-1.0F, 0.0F, 0.0F, -1.0F};
    std::uint64_t passable[1] = {};
    std::uint64_t standing[1] = {};
    build_pathfinder_masks_scalar(types, 4, malus, 4, PathfinderMasks{passable, standing});

    CHECK_FALSE(mask_get(passable, 0));
    CHECK(mask_get(passable, 1));
    CHECK(mask_get(passable, 2));
    CHECK_FALSE(mask_get(passable, 3));
    CHECK_FALSE(mask_get(standing, 0));
    CHECK_FALSE(mask_get(standing, 1));
    CHECK(mask_get(standing, 2));
    CHECK_FALSE(mask_get(standing, 3));
}

TEST_CASE("pathfinder masks: dispatcher matches scalar") {
    std::vector<std::int8_t> types(257, BLOCKED);
    for (std::size_t i = 0; i < types.size(); ++i) {
        types[i] = static_cast<std::int8_t>(i % 3);
    }
    const float malus[] = {-1.0F, 0.0F, 0.0F};
    const std::size_t words = (types.size() + 63) / 64;
    std::vector<std::uint64_t> passableScalar(words);
    std::vector<std::uint64_t> standingScalar(words);
    std::vector<std::uint64_t> passableDispatch(words);
    std::vector<std::uint64_t> standingDispatch(words);

    build_pathfinder_masks_scalar(types.data(), types.size(), malus, 3,
            PathfinderMasks{passableScalar.data(), standingScalar.data()});
    build_pathfinder_masks(types.data(), types.size(), malus, 3,
            PathfinderMasks{passableDispatch.data(), standingDispatch.data()});

    CHECK(passableDispatch == passableScalar);
    CHECK(standingDispatch == standingScalar);
}
