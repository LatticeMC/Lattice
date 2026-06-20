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

TEST_CASE("collision: touching boxes (gap=0) are not blocked") {
    const double moving[6] = {0, 0, 0, 1, 1, 1};
    // Obstacle starts exactly at moving box max (touching, not overlapping)
    const double obstacles[6] = {1, -5, -5, 2, 5, 5};
    double mv[3] = {5.0, 0.0, 0.0};
    adjust_movement_scalar(moving, mv, obstacles, 1);
    // With epsilon: max_move = 1 - 1 = 0, which is > -epsilon, so NOT blocked
    CHECK(mv[0] == 5.0);
}

TEST_CASE("collision: near-touching within epsilon is not blocked") {
    const double moving[6] = {0, 0, 0, 1, 1, 1};
    // Obstacle starts 0.5*epsilon ahead of moving box
    const double eps = kCollisionEpsilon;
    const double obstacles[6] = {1.0 + 0.5 * eps, -5, -5, 2, 5, 5};
    double mv[3] = {5.0, 0.0, 0.0};
    adjust_movement_scalar(moving, mv, obstacles, 1);
    // gap = 0.5*eps, which is < desired (5.0) but > -eps, so NOT blocked
    CHECK(mv[0] == 5.0);
}

TEST_CASE("collision: penetrating beyond epsilon is blocked") {
    const double moving[6] = {0, 0, 0, 1, 1, 1};
    // Obstacle starts 0.5*epsilon BEFORE moving box max (slight penetration)
    const double eps = kCollisionEpsilon;
    const double obstacles[6] = {1.0 - 0.5 * eps, -5, -5, 2, 5, 5};
    double mv[3] = {5.0, 0.0, 0.0};
    adjust_movement_scalar(moving, mv, obstacles, 1);
    // max_move = (1 - 0.5*eps) - 1 = -0.5*eps, which is < -eps? No, -0.5*eps > -eps
    // So NOT blocked (epsilon tolerance allows this)
    CHECK(mv[0] == 5.0);
}

TEST_CASE("collision: penetrating beyond epsilon by more than epsilon is blocked") {
    const double moving[6] = {0, 0, 0, 1, 1, 1};
    // Obstacle starts 2*epsilon BEFORE moving box max
    const double eps = kCollisionEpsilon;
    const double obstacles[6] = {1.0 - 2.0 * eps, -5, -5, 2, 5, 5};
    double mv[3] = {5.0, 0.0, 0.0};
    adjust_movement_scalar(moving, mv, obstacles, 1);
    // max_move = (1 - 2*eps) - 1 = -2*eps, which IS < -eps, so blocked
    CHECK(mv[0] == 5.0 - 2.0 * eps);
}

TEST_CASE("collision: calc_max_offset single axis matches adjust_movement") {
    const double moving[6] = {0, 0, 0, 1, 1, 1};
    const double obstacles[12] = {
        2, -5, -5, 3, 5, 5,
        -3, -5, -5, -2, 5, 5,
    };
    // calc_max_offset on Y axis (axis=1), desired=0
    double result_y = calc_max_offset_scalar(1, moving, 0.0, obstacles, 2);
    CHECK(result_y == 0.0);

    // calc_max_offset on X axis (axis=0), desired=10
    double result_x = calc_max_offset_scalar(0, moving, 10.0, obstacles, 2);
    CHECK(result_x == 1.0); // gap = 2 - 1 = 1

    // calc_max_offset on Z axis (axis=2), desired=3
    double result_z = calc_max_offset_scalar(2, moving, 3.0, obstacles, 2);
    CHECK(result_z == 3.0); // no obstacle on Z
}
