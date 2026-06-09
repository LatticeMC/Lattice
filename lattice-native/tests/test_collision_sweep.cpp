#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include "world/entity/collision_sweep.hpp"

using namespace lattice::world::entity;

TEST_CASE("collision: no obstacle, movement unchanged") {
    const double moving[6] = {0, 0, 0, 1, 1, 1};
    double mv[3] = {1.0, 2.0, 3.0};
    adjust_movement_scalar(moving, mv, nullptr, 0);
    CHECK(mv[0] == 1.0);
    CHECK(mv[1] == 2.0);
    CHECK(mv[2] == 3.0);
}

TEST_CASE("collision: wall at x=2 limits +x movement") {
    const double moving[6] = {0, 0, 0, 1, 1, 1};
    const double obstacles[6] = {2, -5, -5, 3, 5, 5};
    double mv[3] = {5.0, 0.0, 0.0}; // want to move +5 on x
    adjust_movement_scalar(moving, mv, obstacles, 1);
    CHECK(mv[0] == 1.0); // gap = 2 - 1 = 1
    CHECK(mv[1] == 0.0);
    CHECK(mv[2] == 0.0);
}

TEST_CASE("collision: floor at y=-1 limits -y movement") {
    const double moving[6] = {0, 0, 0, 1, 1, 1};
    const double obstacles[6] = {-5, -2, -5, 5, -1, 5};
    double mv[3] = {0.0, -3.0, 0.0};
    adjust_movement_scalar(moving, mv, obstacles, 1);
    // moving min y = 0, obstacle max y = -1, gap = -1 - 0 = -1
    CHECK(mv[1] == -1.0);
}

TEST_CASE("collision: closest obstacle clamps") {
    const double moving[6] = {0, 0, 0, 1, 1, 1};
    const double obstacles[12] = {
        2, -5, -5, 3, 5, 5,  // gap = 1 from x=1 to x=2
        5, -5, -5, 6, 5, 5,  // gap = 4
    };
    double mv[3] = {10.0, 0.0, 0.0};
    adjust_movement_scalar(moving, mv, obstacles, 2);
    CHECK(mv[0] == 1.0); // closer obstacle wins
}

TEST_CASE("collision: obstacle not on cross-axis is irrelevant") {
    const double moving[6] = {0, 0, 0, 1, 1, 1};
    // obstacle at y=10..11, far from moving box; should not affect x.
    const double obstacles[6] = {2, 10, -5, 3, 11, 5};
    double mv[3] = {10.0, 0.0, 0.0};
    adjust_movement_scalar(moving, mv, obstacles, 1);
    CHECK(mv[0] == 10.0); // no clamp
}

TEST_CASE("collision: scalar and dispatcher agree") {
    const double moving[6] = {0, 0, 0, 1, 1, 1};
    const double obstacles[18] = {
        2, -5, -5, 3, 5, 5,
        -3, -5, -5, -2, 5, 5,
        -5, 2, -5, 5, 3, 5,
    };
    double mv_s[3] = {5.0, -3.0, 2.0};
    double mv_d[3] = {5.0, -3.0, 2.0};
    adjust_movement_scalar(moving, mv_s, obstacles, 3);
    adjust_movement(moving, mv_d, obstacles, 3);
    CHECK(mv_d[0] == mv_s[0]);
    CHECK(mv_d[1] == mv_s[1]);
    CHECK(mv_d[2] == mv_s[2]);
}
