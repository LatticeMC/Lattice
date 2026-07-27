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
                     int maxVisitedNodes = -1) {
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
    CHECK(result.path.back().x < 2);
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
