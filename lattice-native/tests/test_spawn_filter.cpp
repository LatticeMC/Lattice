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
