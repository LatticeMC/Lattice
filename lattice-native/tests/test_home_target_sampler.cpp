#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include "world/entity/home_target_sampler.hpp"

using namespace lattice::world::entity;

TEST_CASE("home_target_sampler: prefers candidate near home radius") {
    const double candidates[] = {
        1.0, 0.0, 0.0,
        4.0, 0.0, 0.0,
        8.0, 0.0, 0.0,
    };

    HomeTargetInputs inputs{};
    inputs.candidate_xyz = candidates;
    inputs.candidate_count = 3;
    inputs.self_x = 10.0;
    inputs.self_y = 0.0;
    inputs.self_z = 0.0;
    inputs.home_x = 0.0;
    inputs.home_y = 0.0;
    inputs.home_z = 0.0;
    inputs.preferred_distance = 4.0;

    const HomeTargetResult result = sample_home_target(inputs);
    CHECK(result.candidate_index == 1);
}

TEST_CASE("home_target_sampler: skips obstructed candidates") {
    const double candidates[] = {
        4.0, 0.0, 0.0,
        3.0, 0.0, 3.0,
    };
    const double obstacles[] = {
        3.5, -1.0, -0.5, 4.5, 1.0, 0.5,
    };

    HomeTargetInputs inputs{};
    inputs.candidate_xyz = candidates;
    inputs.candidate_count = 2;
    inputs.self_x = 10.0;
    inputs.self_y = 0.0;
    inputs.self_z = 0.0;
    inputs.home_x = 0.0;
    inputs.home_y = 0.0;
    inputs.home_z = 0.0;
    inputs.preferred_distance = 4.0;
    inputs.obstacle_aabbs = obstacles;
    inputs.obstacle_count = 1;
    inputs.min_clearance = 0.5;

    const HomeTargetResult result = sample_home_target(inputs);
    CHECK(result.candidate_index == 1);
}
