#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cstdint>
#include <vector>

#include "world/entity/spawn_filter.hpp"
#include "world/palette/packed_storage.hpp"

using namespace lattice::world::entity;

TEST_CASE("spawn_filter: 1 candidate, 1 player in range, no obstacles, no mask") {
    const double candidates[3] = {0, 0, 0};
    const double players[3]    = {1, 1, 1};

    SpawnFilterInputs in{};
    in.candidate_xyz         = candidates;
    in.candidate_count       = 1;
    in.player_xyz            = players;
    in.player_count          = 1;
    in.max_spawn_distance_sq = 100.0;
    // section_pass_masks == null → palette check skipped

    std::uint64_t out[1] = {0};
    auto n = filter_spawn_candidates(in, out);
    CHECK(n == 1);
    CHECK(out[0] == 1u);
}

TEST_CASE("spawn_filter: player too far rejects candidate") {
    const double candidates[3] = {0, 0, 0};
    const double players[3]    = {1000, 1000, 1000};

    SpawnFilterInputs in{};
    in.candidate_xyz         = candidates;
    in.candidate_count       = 1;
    in.player_xyz            = players;
    in.player_count          = 1;
    in.max_spawn_distance_sq = 100.0;

    std::uint64_t out[1] = {0};
    auto n = filter_spawn_candidates(in, out);
    CHECK(n == 0);
    CHECK(out[0] == 0u);
}

TEST_CASE("spawn_filter: entity blocks the spawn point") {
    const double candidates[3] = {0, 0, 0};
    const double players[3]    = {1, 1, 1};
    const double entity[6]     = {-1, -1, -1, 1, 1, 1}; // covers (0,0,0)

    SpawnFilterInputs in{};
    in.candidate_xyz         = candidates;
    in.candidate_count       = 1;
    in.player_xyz            = players;
    in.player_count          = 1;
    in.max_spawn_distance_sq = 100.0;
    in.entity_aabbs          = entity;
    in.entity_count          = 1;

    std::uint64_t out[1] = {0};
    auto n = filter_spawn_candidates(in, out);
    CHECK(n == 0);
}

TEST_CASE("spawn_filter: palette mask rejects when bit 0 (air) not set") {
    const double candidates[3] = {0, 0, 0};
    const double players[3]    = {1, 1, 1};
    // Mask: only palette idx 1 passes (bit 1), but the default cell is idx 0.
    std::uint64_t mask[4] = {0b10ULL, 0, 0, 0};

    SpawnFilterInputs in{};
    in.candidate_xyz         = candidates;
    in.candidate_count       = 1;
    in.player_xyz            = players;
    in.player_count          = 1;
    in.max_spawn_distance_sq = 100.0;
    in.section_pass_masks    = mask;
    in.section_count         = 1;
    in.section_base_y        = 0;
    // section_storages == null & section_element_bits == null → all idx 0 → mask bit 0 = 0 → reject

    std::uint64_t out[1] = {0};
    auto n = filter_spawn_candidates(in, out);
    CHECK(n == 0);
}

TEST_CASE("spawn_filter: palette mask passes when bit 0 set") {
    const double candidates[3] = {0, 0, 0};
    const double players[3]    = {1, 1, 1};
    std::uint64_t mask[4] = {0b01ULL, 0, 0, 0};

    SpawnFilterInputs in{};
    in.candidate_xyz         = candidates;
    in.candidate_count       = 1;
    in.player_xyz            = players;
    in.player_count          = 1;
    in.max_spawn_distance_sq = 100.0;
    in.section_pass_masks    = mask;
    in.section_count         = 1;
    in.section_base_y        = 0;

    std::uint64_t out[1] = {0};
    auto n = filter_spawn_candidates(in, out);
    CHECK(n == 1);
}

TEST_CASE("spawn_filter: negative world coordinates map to floor-mod local coords") {
    const double candidates[3] = {-1, 0, -1};
    const double players[3]    = {-1, 0, -1};

    std::vector<std::uint64_t> storage(lattice::world::palette::required_long_count(4, 4096));
    lattice::world::palette::set(storage.data(), 4, 15 + 15 * 16, 1);

    const std::uint64_t* storages[1] = {storage.data()};
    const std::size_t lens[1]        = {storage.size()};
    const int element_bits[1]        = {4};
    std::uint64_t mask[4]            = {0b10ULL, 0, 0, 0};

    SpawnFilterInputs in{};
    in.candidate_xyz         = candidates;
    in.candidate_count       = 1;
    in.player_xyz            = players;
    in.player_count          = 1;
    in.max_spawn_distance_sq = 100.0;
    in.section_storages      = storages;
    in.section_storage_lens  = lens;
    in.section_element_bits  = element_bits;
    in.section_pass_masks    = mask;
    in.section_count         = 1;
    in.section_base_y        = 0;

    std::uint64_t out[1] = {0};
    auto n = filter_spawn_candidates(in, out);
    CHECK(n == 1);
    CHECK(out[0] == 1u);
}

TEST_CASE("spawn_filter: custom candidate dims — large mob blocked by nearby entity") {
    // Candidate at (0, 0, 0) with half_width=1.0, height=2.0 → AABB [-1,0,-1]→[1,2,1]
    // Entity AABB at [0.8, 0, 0.8]→[2, 2, 2] — overlaps the large mob but NOT a 1×1×1 cube
    const double candidates[3] = {0, 0, 0};
    const double players[3]    = {1, 1, 1};
    const double entity[6]     = {0.8, 0, 0.8, 2, 2, 2};
    const double dims[2]       = {1.0, 2.0}; // half_width, height

    SpawnFilterInputs in{};
    in.candidate_xyz         = candidates;
    in.candidate_count       = 1;
    in.candidate_dims        = dims;
    in.player_xyz            = players;
    in.player_count          = 1;
    in.max_spawn_distance_sq = 100.0;
    in.entity_aabbs          = entity;
    in.entity_count          = 1;

    std::uint64_t out[1] = {0};
    auto n = filter_spawn_candidates(in, out);
    CHECK(n == 0); // blocked: large mob overlaps entity
}

TEST_CASE("spawn_filter: custom candidate dims — small mob passes where default would block") {
    // Candidate at (0, 0, 0) with half_width=0.2, height=0.5 → AABB [-0.2,0,-0.2]→[0.2,0.5,0.2]
    // Entity AABB at [0.3, 0, 0.3]→[1, 1, 1] — does NOT overlap the tiny mob
    // But a default 1×1×1 ([-0.5,0,-0.5]→[0.5,1,0.5]) WOULD overlap it.
    const double candidates[3] = {0, 0, 0};
    const double players[3]    = {1, 1, 1};
    const double entity[6]     = {0.3, 0, 0.3, 1, 1, 1};
    const double dims[2]       = {0.2, 0.5}; // half_width, height

    SpawnFilterInputs in{};
    in.candidate_xyz         = candidates;
    in.candidate_count       = 1;
    in.candidate_dims        = dims;
    in.player_xyz            = players;
    in.player_count          = 1;
    in.max_spawn_distance_sq = 100.0;
    in.entity_aabbs          = entity;
    in.entity_count          = 1;

    std::uint64_t out[1] = {0};
    auto n = filter_spawn_candidates(in, out);
    CHECK(n == 1); // passes: tiny mob doesn't overlap
    CHECK(out[0] == 1u);
}

TEST_CASE("spawn_filter: null candidate_dims falls back to default 1x1x1") {
    // Same setup as "entity blocks the spawn point" — entity fully covers default AABB
    const double candidates[3] = {0, 0, 0};
    const double players[3]    = {1, 1, 1};
    const double entity[6]     = {-1, -1, -1, 1, 1, 1};

    SpawnFilterInputs in{};
    in.candidate_xyz         = candidates;
    in.candidate_count       = 1;
    in.candidate_dims        = nullptr; // explicit null → default 0.5/1.0
    in.player_xyz            = players;
    in.player_count          = 1;
    in.max_spawn_distance_sq = 100.0;
    in.entity_aabbs          = entity;
    in.entity_count          = 1;

    std::uint64_t out[1] = {0};
    auto n = filter_spawn_candidates(in, out);
    CHECK(n == 0); // blocked with default dims
}
