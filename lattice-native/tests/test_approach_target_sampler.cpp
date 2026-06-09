#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include "world/entity/approach_target_sampler.hpp"

using namespace lattice::world::entity;

TEST_CASE("approach_target_sampler: prefers candidate nearest preferred distance") {
    const double candidates[] = {
        1.0, 0.0, 0.0,
        3.0, 0.0, 0.0,
        6.0, 0.0, 0.0,
    };

    ApproachTargetInputs inputs{};
    inputs.candidate_xyz = candidates;
    inputs.candidate_count = 3;
    inputs.self_x = 0.0;
    inputs.self_y = 0.0;
    inputs.self_z = 0.0;
    inputs.target_x = 8.0;
    inputs.target_y = 0.0;
    inputs.target_z = 0.0;
    inputs.preferred_distance = 2.0;

    const ApproachTargetResult result = sample_approach_target(inputs);
    CHECK(result.candidate_index == 2);
}

TEST_CASE("approach_target_sampler: skips obstructed candidates") {
    const double candidates[] = {
        6.0, 0.0, 0.0,
        4.0, 0.0, 2.0,
    };
    const double obstacles[] = {
        5.5, -1.0, -0.5, 6.5, 1.0, 0.5,
    };

    ApproachTargetInputs inputs{};
    inputs.candidate_xyz = candidates;
    inputs.candidate_count = 2;
    inputs.self_x = 0.0;
    inputs.self_y = 0.0;
    inputs.self_z = 0.0;
    inputs.target_x = 8.0;
    inputs.target_y = 0.0;
    inputs.target_z = 0.0;
    inputs.preferred_distance = 2.0;
    inputs.obstacle_aabbs = obstacles;
    inputs.obstacle_count = 1;
    inputs.min_clearance = 0.5;

    const ApproachTargetResult result = sample_approach_target(inputs);
    CHECK(result.candidate_index == 1);
}
