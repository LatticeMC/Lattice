#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cstdint>
#include <vector>

#include "world/entity/aabb_query.hpp"

using namespace lattice::world::entity;

TEST_CASE("aabb: overlap detected") {
    const double q[6] = {0, 0, 0, 1, 1, 1};
    const double e[6] = {0.5, 0.5, 0.5, 1.5, 1.5, 1.5};
    std::uint64_t vis[1] = {0};
    aabb_scan_scalar(q, 1, e, 1, vis);
    CHECK((vis[0] & 1ULL) == 1ULL);
}

TEST_CASE("aabb: no overlap (separated on x)") {
    const double q[6] = {0, 0, 0, 1, 1, 1};
    const double e[6] = {2, 0, 0, 3, 1, 1};
    std::uint64_t vis[1] = {0};
    aabb_scan_scalar(q, 1, e, 1, vis);
    CHECK((vis[0] & 1ULL) == 0ULL);
}

TEST_CASE("aabb: touching faces count as overlap (Mojang convention)") {
    // Vanilla Box.intersects uses <= and >=, so faces touching means intersecting.
    const double q[6] = {0, 0, 0, 1, 1, 1};
    const double e[6] = {1, 0, 0, 2, 1, 1};
    std::uint64_t vis[1] = {0};
    aabb_scan_scalar(q, 1, e, 1, vis);
    CHECK((vis[0] & 1ULL) == 1ULL);
}

TEST_CASE("aabb: 3 queries × 5 entities, scalar vs dispatcher") {
    const double queries[18] = {
        // q0: a cube at origin (1x1x1)
        0, 0, 0, 1, 1, 1,
        // q1: a thin sheet
        10, 10, 10, 20, 11, 20,
        // q2: large box
        -100, -100, -100, 100, 100, 100,
    };
    const double entities[30] = {
        0, 0, 0, 0.5, 0.5, 0.5,    // overlaps q0, q2
        15, 10, 15, 16, 10.5, 16,  // overlaps q1, q2
        99, 99, 99, 99.5, 99.5, 99.5, // overlaps q2 only
        -200, -200, -200, -199, -199, -199, // no overlap
        0.9, 0.9, 0.9, 1.0, 1.0, 1.0, // touches q0
    };
    std::uint64_t vis_s[3] = {0xCDCDCDCD, 0xCDCDCDCD, 0xCDCDCDCD};
    std::uint64_t vis_d[3] = {0xCDCDCDCD, 0xCDCDCDCD, 0xCDCDCDCD};
    aabb_scan_scalar(queries, 3, entities, 5, vis_s);
    aabb_scan(queries, 3, entities, 5, vis_d);
    for (int i = 0; i < 3; ++i) CHECK(vis_d[i] == vis_s[i]);

    // Manual: q0 should pass entities 0 and 4 (touch).
    CHECK((vis_s[0] & 0b00001) == 1u);  // entity 0
    CHECK((vis_s[0] & 0b10000) == 16u); // entity 4
}
