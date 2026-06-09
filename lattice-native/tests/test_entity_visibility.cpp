#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cstdint>
#include <vector>

#include "world/entity/visibility_scan.hpp"

using namespace lattice::world::entity;

TEST_CASE("visibility: single entity, single player, in range") {
    const double entities[3] = {0.0, 0.0, 0.0};
    const double ranges[1]   = {25.0}; // 5²
    const double players[3]  = {3.0, 0.0, 4.0}; // distance = 5

    std::uint64_t vis[1] = {0};
    scan_scalar(entities, ranges, 1, players, 1, vis);
    CHECK((vis[0] & 1ULL) == 1ULL); // boundary inclusive
}

TEST_CASE("visibility: out of range") {
    const double entities[3] = {0.0, 0.0, 0.0};
    const double ranges[1]   = {1.0};
    const double players[3]  = {10.0, 0.0, 0.0};

    std::uint64_t vis[1] = {0};
    scan_scalar(entities, ranges, 1, players, 1, vis);
    CHECK((vis[0] & 1ULL) == 0ULL);
}

TEST_CASE("visibility: dispatcher matches scalar") {
    // 5 entities × 8 players, random positions.
    std::vector<double> entities{
        0.0, 0.0, 0.0,
        100.0, 64.0, 100.0,
        -50.0, 70.0, -50.0,
        10.0, 5.0, 10.0,
        200.0, 100.0, 200.0,
    };
    std::vector<double> ranges{64.0*64.0, 32.0*32.0, 16.0*16.0, 1.0, 256.0*256.0};
    std::vector<double> players{
        0.0, 0.0, 0.0,
        50.0, 64.0, 50.0,
        100.0, 64.0, 100.0,
        -25.0, 70.0, -25.0,
        20.0, 5.0, 20.0,
        15.0, 5.0, 10.0,
        300.0, 100.0, 300.0,
        -100.0, 0.0, -100.0,
    };
    const std::size_t E = ranges.size();
    const std::size_t P = players.size() / 3;
    const std::size_t row_l = row_longs(P);

    std::vector<std::uint64_t> vis_scalar(E * row_l, 0xCDCDCDCD);
    std::vector<std::uint64_t> vis_dispatch(E * row_l, 0xCDCDCDCD);
    scan_scalar(entities.data(), ranges.data(), E,
                players.data(), P, vis_scalar.data());
    scan(entities.data(), ranges.data(), E,
         players.data(), P, vis_dispatch.data());

    for (std::size_t i = 0; i < E * row_l; ++i) {
        CHECK(vis_dispatch[i] == vis_scalar[i]);
    }
}

TEST_CASE("visibility: empty inputs are safe") {
    std::uint64_t vis[4] = {0xAA, 0xBB, 0xCC, 0xDD};
    scan_scalar(nullptr, nullptr, 0, nullptr, 0, vis);
    // No entities → nothing written. Output buffer left as supplied.
    // (The implementation does memset to row_l*entity_count which is 0,
    // so the original bytes remain.)
    CHECK(vis[0] == 0xAA);
}
