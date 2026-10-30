#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

#include "world/entity/pathfinder.hpp"

using namespace lattice::world::entity;

namespace {

constexpr std::int8_t BLOCKED = 0;
constexpr std::int8_t OPEN = 1;
constexpr std::int8_t WALKABLE = 2;
constexpr std::int8_t WALKABLE_DOOR = 3;
constexpr std::int8_t DOOR_WOOD_CLOSED = 18;
constexpr std::int8_t WATER = 9;
constexpr std::int8_t COCOA = 23;
constexpr std::int8_t WATER_BORDER = 10;
constexpr std::int8_t RAIL = 11;

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

void check_mirrors_equal(const PathfinderStateMirror& expected,
                         const PathfinderStateMirror& actual) {
    REQUIRE(actual.world_key == expected.world_key);
    REQUIRE(actual.sections.size() == expected.sections.size());
    for (const auto& [key, section] : expected.sections) {
        const auto found = actual.sections.find(key);
        REQUIRE(found != actual.sections.end());
        CHECK(found->second.raw_path_types == section.raw_path_types);
        CHECK(std::equal(found->second.floor_heights.begin(), found->second.floor_heights.end(),
                         section.floor_heights.begin(), [](float actual, float expected) {
                             return actual == expected
                                 || (std::isnan(actual) && std::isnan(expected));
                         }));
        CHECK(found->second.valid == section.valid);
        CHECK(found->second.valid_count == section.valid_count);
    }
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

TEST_CASE("pathfinder fused snapshot materialization matches canonical PathType semantics") {
    constexpr int snapshot_size_x = 3;
    constexpr int snapshot_size_y = 3;
    constexpr int snapshot_size_z = 3;
    constexpr int world_key = 43;
    std::array<std::int8_t, 26> descriptor_types{};
    std::array<float, 26> descriptor_floors{};
    for (int type = 0; type < static_cast<int>(descriptor_types.size()); ++type) {
        descriptor_types[static_cast<std::size_t>(type)] = static_cast<std::int8_t>(type);
        descriptor_floors[static_cast<std::size_t>(type)] = 0.25F;
    }

    std::vector<int> cells(snapshot_size_x * snapshot_size_y * snapshot_size_z, OPEN);
    cells[snapshot_index(snapshot_size_x, snapshot_size_z, 1, 0, 1)] = BLOCKED;
    PathfinderStateSnapshot snapshot{};
    snapshot.cells = cells.data();
    snapshot.raw_path_types = descriptor_types.data();
    snapshot.floor_heights = descriptor_floors.data();
    snapshot.descriptor_count = static_cast<int>(descriptor_types.size());
    snapshot.min_x = -1;
    snapshot.min_y = -1;
    snapshot.min_z = -1;
    snapshot.size_x = snapshot_size_x;
    snapshot.size_y = snapshot_size_y;
    snapshot.size_z = snapshot_size_z;

    std::array<float, 26> malus{};
    for (int type = 0; type < static_cast<int>(malus.size()); ++type) {
        switch (type % 4) {
            case 0: malus[static_cast<std::size_t>(type)] = -1.0F; break;
            case 1: malus[static_cast<std::size_t>(type)] = 0.25F; break;
            case 2: malus[static_cast<std::size_t>(type)] = 0.5F; break;
            default: malus[static_cast<std::size_t>(type)] = 0.75F; break;
        }
    }
    malus[BLOCKED] = 0.5F;
    const int target_x = 0;
    const int target_y = 0;
    const int target_z = 0;
    PathfinderInputs inputs{};
    inputs.region_min_x = target_x;
    inputs.region_min_y = target_y;
    inputs.region_min_z = target_z;
    inputs.region_size_x = 1;
    inputs.region_size_y = 1;
    inputs.region_size_z = 1;
    inputs.start_x = target_x;
    inputs.start_y = target_y;
    inputs.start_z = target_z;
    inputs.target_x = &target_x;
    inputs.target_y = &target_y;
    inputs.target_z = &target_z;
    inputs.target_count = 1;
    inputs.config = PathfinderConfig{16.0F, 8, 0, 1.5F};
    inputs.entity_width = 1;
    inputs.entity_height = 1;
    inputs.level_min_y = -64;
    inputs.pathfinding_malus = malus.data();
    inputs.pathfinding_malus_count = static_cast<int>(malus.size());

    auto check_parity = [&]() {
        int old_order_coords[24]{};
        int fused_coords[24]{};
        PathfinderOutput old_order_output{old_order_coords, 8};
        PathfinderOutput fused_output{fused_coords, 8};
        PathfinderScratch old_order_scratch{};
        PathfinderScratch fused_scratch{};
        PathfinderStateMirror old_order_mirror{};
        PathfinderStateMirror fused_mirror{};

        store_pathfinder_state_snapshot(old_order_mirror, world_key, snapshot);
        const bool old_order_ok = find_path_from_state_snapshot_into(
            inputs, snapshot, old_order_output, old_order_scratch);
        const bool fused_ok = find_path_from_state_snapshot_and_store_into(
            fused_mirror, world_key, inputs, snapshot, fused_output, fused_scratch);

        CHECK(fused_ok == old_order_ok);
        CHECK(fused_output.path_length == old_order_output.path_length);
        CHECK(fused_output.target_index == old_order_output.target_index);
        CHECK(fused_output.reached_target == old_order_output.reached_target);
        if (fused_output.path_length == old_order_output.path_length
                && fused_output.path_length >= 0 && fused_output.path_length <= 8) {
            CHECK(std::equal(fused_coords, fused_coords + fused_output.path_length * 3,
                             old_order_coords));
        }
        CHECK(fused_scratch.materialized_path_types == old_order_scratch.materialized_path_types);
        CHECK(fused_scratch.materialized_floor_levels == old_order_scratch.materialized_floor_levels);
        check_mirrors_equal(old_order_mirror, fused_mirror);
    };

    for (int raw_type = 0; raw_type < static_cast<int>(descriptor_types.size()); ++raw_type) {
        cells[snapshot_index(snapshot_size_x, snapshot_size_z, 1, 1, 1)] = raw_type;
        for (int water_mode = 0; water_mode < 3; ++water_mode) {
            inputs.can_float = water_mode == 1;
            inputs.is_amphibious = water_mode == 2;
            for (int door_mode = 0; door_mode < 4; ++door_mode) {
                inputs.can_pass_doors = (door_mode & 1) != 0;
                inputs.can_open_doors = (door_mode & 2) != 0;
                check_parity();
            }
        }
    }

    inputs.mob_block_x = 1;
    inputs.mob_block_y = 0;
    inputs.mob_block_z = 1;
    inputs.level_min_y = -1;
    cells[snapshot_index(snapshot_size_x, snapshot_size_z, 1, 1, 1)] = RAIL;
    check_parity();

    inputs.entity_width = 2;
    inputs.entity_height = 2;
    inputs.mob_block_x = 0;
    inputs.mob_block_y = 0;
    inputs.mob_block_z = 0;
    inputs.level_min_y = -64;
    inputs.can_pass_doors = true;
    inputs.can_open_doors = true;
    cells[snapshot_index(snapshot_size_x, snapshot_size_z, 1, 1, 1)] = WALKABLE;
    cells[snapshot_index(snapshot_size_x, snapshot_size_z, 2, 1, 2)] = DOOR_WOOD_CLOSED;
    check_parity();
}

TEST_CASE("pathfinder fused snapshot and canonical path preserve invalid floor failure") {
    constexpr int sx = 3;
    constexpr int sy = 3;
    constexpr int sz = 3;
    const std::int8_t descriptor_types[] = {BLOCKED, WALKABLE};
    const float descriptor_floors[] = {
        std::numeric_limits<float>::quiet_NaN(), 0.25F};
    std::vector<int> cells(sx * sy * sz, 1);
    cells[snapshot_index(sx, sz, 1, 0, 1)] = 0;
    PathfinderStateSnapshot snapshot{};
    snapshot.cells = cells.data();
    snapshot.raw_path_types = descriptor_types;
    snapshot.floor_heights = descriptor_floors;
    snapshot.descriptor_count = 2;
    snapshot.min_x = -1;
    snapshot.min_y = -1;
    snapshot.min_z = -1;
    snapshot.size_x = sx;
    snapshot.size_y = sy;
    snapshot.size_z = sz;

    const int target_x = 0;
    const int target_y = 0;
    const int target_z = 0;
    float malus[] = {-1.0F, 0.0F};
    PathfinderInputs inputs{};
    inputs.region_min_x = target_x;
    inputs.region_min_y = target_y;
    inputs.region_min_z = target_z;
    inputs.region_size_x = 1;
    inputs.region_size_y = 1;
    inputs.region_size_z = 1;
    inputs.start_x = target_x;
    inputs.start_y = target_y;
    inputs.start_z = target_z;
    inputs.target_x = &target_x;
    inputs.target_y = &target_y;
    inputs.target_z = &target_z;
    inputs.target_count = 1;
    inputs.config = PathfinderConfig{16.0F, 8, 0, 1.5F};
    inputs.entity_width = 1;
    inputs.entity_height = 1;
    inputs.level_min_y = -64;
    inputs.pathfinding_malus = malus;
    inputs.pathfinding_malus_count = 2;

    int old_order_coords[24]{};
    int fused_coords[24]{};
    PathfinderOutput old_order_output{old_order_coords, 8};
    PathfinderOutput fused_output{fused_coords, 8};
    PathfinderScratch old_order_scratch{};
    PathfinderScratch fused_scratch{};
    PathfinderStateMirror old_order_mirror{};
    PathfinderStateMirror fused_mirror{};
    store_pathfinder_state_snapshot(old_order_mirror, 44, snapshot);
    const bool old_order_ok = find_path_from_state_snapshot_into(
        inputs, snapshot, old_order_output, old_order_scratch);
    const bool fused_ok = find_path_from_state_snapshot_and_store_into(
        fused_mirror, 44, inputs, snapshot, fused_output, fused_scratch);

    CHECK_FALSE(old_order_ok);
    CHECK_FALSE(fused_ok);
    CHECK(fused_output.path_length == old_order_output.path_length);
    CHECK(fused_output.target_index == old_order_output.target_index);
    CHECK(fused_output.reached_target == old_order_output.reached_target);
    CHECK(fused_scratch.materialized_path_types == old_order_scratch.materialized_path_types);
    CHECK(fused_scratch.materialized_floor_levels == old_order_scratch.materialized_floor_levels);
    check_mirrors_equal(old_order_mirror, fused_mirror);
}

TEST_CASE("pathfinder cold snapshot upload and search matches the sequential path") {
    constexpr int sx = 6;
    constexpr int sy = 4;
    constexpr int sz = 6;
    const std::int8_t descriptor_types[] = {WALKABLE, BLOCKED};
    const float descriptor_floors[] = {0.0F, 1.0F};
    std::vector<int> cells(sx * sy * sz, 0);
    for (int z = 0; z < sz; ++z) {
        for (int x = 0; x < sx; ++x) {
            cells[snapshot_index(sx, sz, x, 0, z)] = 1;
        }
    }
    PathfinderStateSnapshot snapshot{};
    snapshot.cells = cells.data();
    snapshot.raw_path_types = descriptor_types;
    snapshot.floor_heights = descriptor_floors;
    snapshot.descriptor_count = 2;
    snapshot.min_x = -1;
    snapshot.min_y = -1;
    snapshot.min_z = -1;
    snapshot.size_x = sx;
    snapshot.size_y = sy;
    snapshot.size_z = sz;

    float malus[26]{};
    malus[BLOCKED] = -1.0F;
    const int target_x = 2;
    const int target_y = 0;
    const int target_z = 1;
    PathfinderInputs inputs{};
    inputs.region_min_x = 0;
    inputs.region_min_y = 0;
    inputs.region_min_z = 0;
    inputs.region_size_x = 3;
    inputs.region_size_y = 1;
    inputs.region_size_z = 3;
    inputs.start_x = 0;
    inputs.start_y = 0;
    inputs.start_z = 1;
    inputs.target_x = &target_x;
    inputs.target_y = &target_y;
    inputs.target_z = &target_z;
    inputs.target_count = 1;
    inputs.config = PathfinderConfig{16.0F, 16, 0, 1.5F};
    inputs.entity_width = 1;
    inputs.entity_height = 1;
    inputs.level_min_y = -64;
    inputs.pathfinding_malus = malus;
    inputs.pathfinding_malus_count = 26;

    auto compare_paths = [&](PathfinderStateMirror& expected_mirror,
                             PathfinderStateMirror& actual_mirror) {
        int expected_coords[48]{};
        int actual_coords[48]{};
        PathfinderOutput expected_output{expected_coords, 16, 7, 3, true};
        PathfinderOutput actual_output{actual_coords, 16, 7, 3, true};
        PathfinderScratch expected_scratch{};
        PathfinderScratch actual_scratch{};
        store_pathfinder_state_snapshot(expected_mirror, 41, snapshot);
        const bool expected_ok = find_path_from_state_snapshot_into(
            inputs, snapshot, expected_output, expected_scratch);
        const bool actual_ok = find_path_from_state_snapshot_and_store_into(
            actual_mirror, 41, inputs, snapshot, actual_output, actual_scratch);
        CHECK(actual_ok == expected_ok);
        CHECK(actual_output.path_length == expected_output.path_length);
        CHECK(actual_output.target_index == expected_output.target_index);
        CHECK(actual_output.reached_target == expected_output.reached_target);
        CHECK(std::equal(actual_coords, actual_coords + actual_output.path_length * 3,
                         expected_coords));
        CHECK(actual_scratch.materialized_path_types == expected_scratch.materialized_path_types);
        CHECK(actual_scratch.materialized_floor_levels == expected_scratch.materialized_floor_levels);
        check_mirrors_equal(expected_mirror, actual_mirror);
        if (expected_ok) CHECK(expected_output.reached_target);
    };

    SUBCASE("larger snapshot and search boxes") {
        PathfinderStateMirror expected{};
        PathfinderStateMirror actual{};
        expected.world_key = 41;
        actual.world_key = 41;
        compare_paths(expected, actual);
    }

    SUBCASE("invalid descriptor after the search box preserves upload prefix") {
        cells.back() = 9;
        PathfinderStateMirror expected{};
        PathfinderStateMirror actual{};
        expected.world_key = 41;
        actual.world_key = 41;
        compare_paths(expected, actual);
    }

    SUBCASE("world change clears old mirror sections before uploading") {
        PathfinderStateMirror expected{};
        PathfinderStateMirror actual{};
        expected.world_key = 7;
        actual.world_key = 7;
        const std::int8_t old_type[] = {OPEN};
        const float old_floor[] = {0.0F};
        const int old_cell[] = {0};
        PathfinderStateSnapshot old_snapshot{};
        old_snapshot.cells = old_cell;
        old_snapshot.raw_path_types = old_type;
        old_snapshot.floor_heights = old_floor;
        old_snapshot.descriptor_count = 1;
        old_snapshot.min_x = 32;
        old_snapshot.min_y = 64;
        old_snapshot.min_z = 96;
        old_snapshot.size_x = 1;
        old_snapshot.size_y = 1;
        old_snapshot.size_z = 1;
        store_pathfinder_state_snapshot(expected, 7, old_snapshot);
        store_pathfinder_state_snapshot(actual, 7, old_snapshot);
        CHECK_FALSE(expected.sections.empty());
        compare_paths(expected, actual);
    }

    SUBCASE("invalid descriptor needed by materialization preserves failure behavior") {
        cells[snapshot_index(sx, sz, 2, 1, 2)] = 9;
        PathfinderStateMirror expected{};
        PathfinderStateMirror actual{};
        expected.world_key = 41;
        actual.world_key = 41;
        compare_paths(expected, actual);
        CHECK(expected.sections.size() == actual.sections.size());
    }
}

TEST_CASE("pathfinder fused snapshot upload matches old order across section boundary") {
    constexpr int snapshot_size_x = 4;
    constexpr int snapshot_size_y = 2;
    constexpr int snapshot_size_z = 1;
    constexpr int region_min_x = 15;
    constexpr int region_size_x = 2;
    constexpr int max_visited_nodes = 8;
    constexpr int world_key = 42;
    const int cells[snapshot_size_x * snapshot_size_y * snapshot_size_z]{};
    const std::int8_t descriptor_types[] = {WALKABLE};
    const float descriptor_floors[] = {1.0F};

    PathfinderStateSnapshot snapshot{};
    snapshot.cells = cells;
    snapshot.raw_path_types = descriptor_types;
    snapshot.floor_heights = descriptor_floors;
    snapshot.descriptor_count = 1;
    snapshot.min_x = 14;
    snapshot.min_y = -1;
    snapshot.min_z = 0;
    snapshot.size_x = snapshot_size_x;
    snapshot.size_y = snapshot_size_y;
    snapshot.size_z = snapshot_size_z;

    float malus[26]{};
    malus[BLOCKED] = -1.0F;
    const int target_x = 16;
    const int target_y = 0;
    const int target_z = 0;
    PathfinderInputs inputs{};
    inputs.region_min_x = region_min_x;
    inputs.region_min_y = 0;
    inputs.region_min_z = 0;
    inputs.region_size_x = region_size_x;
    inputs.region_size_y = 1;
    inputs.region_size_z = 1;
    inputs.start_x = 15;
    inputs.start_y = 0;
    inputs.start_z = 0;
    inputs.target_x = &target_x;
    inputs.target_y = &target_y;
    inputs.target_z = &target_z;
    inputs.target_count = 1;
    inputs.config = PathfinderConfig{16.0F, max_visited_nodes, 0, 1.5F};
    inputs.entity_width = 1;
    inputs.entity_height = 1;
    inputs.level_min_y = -64;
    inputs.pathfinding_malus = malus;
    inputs.pathfinding_malus_count = 26;

    int old_order_coords[max_visited_nodes * 3]{};
    int fused_coords[max_visited_nodes * 3]{};
    PathfinderOutput old_order_output{old_order_coords, max_visited_nodes};
    PathfinderOutput fused_output{fused_coords, max_visited_nodes};
    PathfinderScratch old_order_scratch{};
    PathfinderScratch fused_scratch{};
    PathfinderStateMirror old_order_mirror{};
    PathfinderStateMirror fused_mirror{};

    store_pathfinder_state_snapshot(old_order_mirror, world_key, snapshot);
    const bool old_order_ok = find_path_from_state_snapshot_into(
        inputs, snapshot, old_order_output, old_order_scratch);
    const bool fused_ok = find_path_from_state_snapshot_and_store_into(
        fused_mirror, world_key, inputs, snapshot, fused_output, fused_scratch);

    CHECK(fused_ok == old_order_ok);
    REQUIRE(old_order_ok);
    REQUIRE(fused_ok);
    CHECK(old_order_output.target_index == fused_output.target_index);
    CHECK(old_order_output.path_length == fused_output.path_length);
    CHECK(old_order_output.reached_target == fused_output.reached_target);
    CHECK(old_order_output.target_index == 0);
    CHECK(old_order_output.path_length == 2);
    CHECK(old_order_output.reached_target);
    CHECK(std::equal(old_order_coords,
                     old_order_coords + old_order_output.path_length * 3,
                     fused_coords));
    CHECK(old_order_coords[0] == 15);
    CHECK(old_order_coords[1] == 0);
    CHECK(old_order_coords[2] == 0);
    CHECK(old_order_coords[3] == 16);
    CHECK(old_order_coords[4] == 0);
    CHECK(old_order_coords[5] == 0);
    CHECK(old_order_mirror.sections.contains(pathfinder_mirror_section_key(15, 0, 0)));
    CHECK(old_order_mirror.sections.contains(pathfinder_mirror_section_key(16, 0, 0)));
    check_mirrors_equal(old_order_mirror, fused_mirror);
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
    REQUIRE(scratch.mirror_sections.size() == 1);
    CHECK(scratch.mirror_sections[0] != nullptr);
    CHECK(loaded.mirror_sections == nullptr);
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
    CHECK_FALSE(state_mirror_covers(mirror, 5, std::numeric_limits<int>::max(), 0, 0, 2, 1, 1));
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

TEST_CASE("pathfinder materializes a resident state mirror like a snapshot") {
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
    CHECK(scratch.mirror_sections.size() > 0);
    CHECK(scratch.mirror_cells.size() == 0);
    CHECK(scratch.mirror_raw_path_types.size() == 0);
    CHECK(scratch.mirror_floor_heights.size() == 0);
    CHECK(scratch.materialized_path_types == direct_scratch.materialized_path_types);
    CHECK(scratch.materialized_floor_levels == direct_scratch.materialized_floor_levels);
    CHECK(output.reached_target);
    CHECK(output.path_length == direct.path_length);
    CHECK(std::equal(output_cells, output_cells + output.path_length * 3, direct_cells));
    REQUIRE(scratch.nodes.size() == direct_scratch.nodes.size());
    for (std::size_t i = 0; i < direct_scratch.nodes.size(); ++i) {
        const PathfinderNode& expected = direct_scratch.nodes[i];
        const PathfinderNode& actual = scratch.nodes[i];
        CHECK(actual.x == expected.x);
        CHECK(actual.y == expected.y);
        CHECK(actual.z == expected.z);
        CHECK(actual.g == expected.g);
        CHECK(actual.h == expected.h);
        CHECK(actual.f == expected.f);
        CHECK(actual.walked_distance == expected.walked_distance);
        CHECK(actual.came_from == expected.came_from);
        CHECK(actual.type == expected.type);
        CHECK(actual.flags == expected.flags);
        CHECK(actual.cost_malus == expected.cost_malus);
    }

}

TEST_CASE("pathfinder mirror materialization supports the maximum int coordinate") {
    const std::int8_t descriptors[] = {BLOCKED, WALKABLE};
    const float floors[] = {0.0F, 0.0F};
    std::vector<int> cells(3 * 3 * 3, 0);
    cells[snapshot_index(3, 3, 1, 1, 1)] = 1;
    PathfinderStateSnapshot source{};
    source.cells = cells.data();
    source.raw_path_types = descriptors;
    source.floor_heights = floors;
    source.descriptor_count = 2;
    source.min_x = std::numeric_limits<int>::max() - 2;
    source.min_y = 0;
    source.min_z = -1;
    source.size_x = 3;
    source.size_y = 3;
    source.size_z = 3;
    PathfinderStateMirror mirror{};
    store_pathfinder_state_snapshot(mirror, 17, source);

    std::vector<float> malus(26, 0.0F);
    malus[BLOCKED] = -1.0F;
    const int target_x = std::numeric_limits<int>::max() - 1;
    const int target_y = 1;
    const int target_z = 0;
    PathfinderInputs inputs{};
    inputs.region_min_x = target_x;
    inputs.region_min_y = target_y;
    inputs.region_min_z = target_z;
    inputs.region_size_x = 1;
    inputs.region_size_y = 1;
    inputs.region_size_z = 1;
    inputs.start_x = target_x;
    inputs.start_y = target_y;
    inputs.start_z = target_z;
    inputs.target_x = &target_x;
    inputs.target_y = &target_y;
    inputs.target_z = &target_z;
    inputs.target_count = 1;
    inputs.config = PathfinderConfig{16.0F, 8, 0, 1.5F};
    inputs.entity_width = 1;
    inputs.entity_height = 1;
    inputs.level_min_y = -64;
    inputs.pathfinding_malus = malus.data();
    inputs.pathfinding_malus_count = static_cast<int>(malus.size());

    int coords[24]{};
    PathfinderOutput output{coords, 8};
    PathfinderScratch scratch{};
    REQUIRE(find_path_from_state_mirror_into(inputs, mirror, 17, output, scratch));
    CHECK(output.reached_target);
    CHECK(output.path_length == 1);
    CHECK(coords[0] == target_x);
    CHECK(coords[1] == target_y);
    CHECK(coords[2] == target_z);
}

TEST_CASE("pathfinder mirror water floor does not require an unused below cell") {
    const std::int8_t descriptors[] = {BLOCKED, WATER};
    const float floors[] = {0.0F, 0.0F};
    int cells[3 * 3 * 3]{};
    cells[snapshot_index(3, 3, 1, 2, 1)] = 1;
    PathfinderStateSnapshot source{};
    source.cells = cells;
    source.raw_path_types = descriptors;
    source.floor_heights = floors;
    source.descriptor_count = 2;
    source.min_x = -1;
    source.min_y = -1;
    source.min_z = -1;
    source.size_x = 3;
    source.size_y = 3;
    source.size_z = 3;
    PathfinderStateMirror mirror{};
    store_pathfinder_state_snapshot(mirror, 23, source);
    invalidate_pathfinder_state_mirror_cell(mirror, 23, 0, 0, 0);

    const int target_x = 0;
    const int target_y = 1;
    const int target_z = 0;
    float malus[26]{};
    PathfinderInputs inputs{};
    inputs.region_min_x = target_x;
    inputs.region_min_y = target_y;
    inputs.region_min_z = target_z;
    inputs.region_size_x = 1;
    inputs.region_size_y = 1;
    inputs.region_size_z = 1;
    inputs.start_x = target_x;
    inputs.start_y = target_y;
    inputs.start_z = target_z;
    inputs.target_x = &target_x;
    inputs.target_y = &target_y;
    inputs.target_z = &target_z;
    inputs.target_count = 1;
    inputs.config = PathfinderConfig{16.0F, 8, 0, 1.5F};
    inputs.entity_width = 1;
    inputs.entity_height = 1;
    inputs.can_float = true;
    inputs.pathfinding_malus = malus;
    inputs.pathfinding_malus_count = 26;

    int mirror_coords[3]{};
    PathfinderOutput mirror_output{mirror_coords, 1};
    PathfinderScratch mirror_scratch{};
    REQUIRE(find_path_from_state_mirror_into(inputs, mirror, 23, mirror_output, mirror_scratch));
    CHECK(mirror_output.reached_target);
    CHECK(mirror_scratch.materialized_path_types[0] == WATER);
    CHECK(mirror_scratch.materialized_floor_levels[0] == doctest::Approx(1.5F));
}

TEST_CASE("pathfinder mirror materialization rejects checked coordinate and section bounds") {
    const int target_x = 0;
    const int target_y = 0;
    const int target_z = 0;
    const float malus[26] = {};
    PathfinderStateMirror mirror{};
    mirror.world_key = 17;

    auto make_inputs = [&]() {
        PathfinderInputs inputs{};
        inputs.target_x = &target_x;
        inputs.target_y = &target_y;
        inputs.target_z = &target_z;
        inputs.target_count = 1;
        inputs.config = PathfinderConfig{64.0F, 8, 0, 1.5F};
        inputs.entity_width = 1;
        inputs.entity_height = 1;
        inputs.pathfinding_malus = malus;
        inputs.pathfinding_malus_count = 26;
        return inputs;
    };

    int coords[24]{};
    PathfinderOutput output{coords, 8};
    PathfinderScratch scratch{};

    PathfinderInputs high = make_inputs();
    high.region_min_x = std::numeric_limits<int>::max();
    high.region_size_x = 2;
    high.region_size_y = 1;
    high.region_size_z = 1;
    CHECK_FALSE(find_path_from_state_mirror_into(high, mirror, 17, output, scratch));

    PathfinderInputs low = make_inputs();
    low.region_min_x = std::numeric_limits<int>::min();
    low.region_size_x = 1;
    low.region_size_y = 1;
    low.region_size_z = 1;
    CHECK_FALSE(find_path_from_state_mirror_into(low, mirror, 17, output, scratch));

    // Keep the cell volume below the normal int-sized limit while exercising
    // a region that used to exceed the lazy section budget.
    PathfinderInputs too_many_sections = make_inputs();
    too_many_sections.region_size_x = 8192;
    too_many_sections.region_size_y = 1;
    too_many_sections.region_size_z = 8192;
    CHECK_FALSE(find_path_from_state_mirror_into(too_many_sections, mirror, 17, output, scratch));
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

TEST_CASE("pathfinder: reused dirty masks and changing shapes match fresh search") {
    PathfinderScratch scratch;
    for (int sx : {65, 3, 17, 65, 2}) {
        Grid grid(sx, 2, 3); fill_floor(grid, 0);
        int tx = sx - 1, ty = 0, tz = 1;
        PathfinderInputs in{};
        in.path_types = grid.cells.data(); in.region_size_x = sx; in.region_size_y = 2; in.region_size_z = 3;
        in.start_x = 0; in.start_y = 0; in.start_z = 1;
        in.target_x = &tx; in.target_y = &ty; in.target_z = &tz; in.target_count = 1;
        in.config.max_range = 128; in.config.max_visited_nodes = 1000; in.config.fudge = 1.5F;
        in.max_up_step = 1; in.max_fall_distance = 3;
        in.pathfinding_malus = grid.malus.data(); in.pathfinding_malus_count = static_cast<int>(grid.malus.size());
        std::fill(scratch.passable.begin(), scratch.passable.end(), UINT64_MAX);
        std::fill(scratch.standing.begin(), scratch.standing.end(), UINT64_MAX);
        int ac[3000], bc[3000];
        PathfinderOutput a{ac, 1000}, b{bc, 1000}; PathfinderScratch fresh;
        REQUIRE(find_path_into(in, a, scratch) == find_path_into(in, b, fresh));
        CHECK(a.path_length == b.path_length); CHECK(a.reached_target == b.reached_target);
        for (int i = 0; i < a.path_length * 3; ++i) CHECK(ac[i] == bc[i]);
        CHECK(scratch.passable == fresh.passable); CHECK(scratch.standing == fresh.standing);
        CHECK(scratch.passable.size() == (grid.cells.size() + 63) / 64);
    }
    for (std::size_t n : {1u, 63u, 64u, 65u, 257u}) {
        std::vector<std::int8_t> types(n, OPEN); types[n - 1] = WALKABLE;
        float malus[] = {-1, 0, 0};
        std::vector<std::uint64_t> a((n+63)/64, UINT64_MAX), b(a), c(a), d(a);
        build_pathfinder_masks_scalar(types.data(), n, malus, 3, {a.data(), b.data()});
        build_pathfinder_masks(types.data(), n, malus, 3, {c.data(), d.data()});
        CHECK(a == c); CHECK(b == d);
        if (n % 64) { CHECK((a.back() >> (n%64)) == 0); CHECK((b.back() >> (n%64)) == 0); }
    }
}
