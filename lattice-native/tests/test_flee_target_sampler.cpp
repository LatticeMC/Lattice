#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include "world/entity/flee_target_sampler.hpp"

using namespace lattice::world::entity;

TEST_CASE("flee_target_sampler: prefers candidate farthest from threat") {
    const double candidates[] = {
        1.0, 0.0, 0.0,
        -5.0, 0.0, 0.0,
        0.0, 0.0, 4.0,
    };

    FleeTargetInputs inputs{};
    inputs.candidate_xyz = candidates;
    inputs.candidate_count = 3;
    inputs.self_x = 0.0;
    inputs.self_y = 0.0;
    inputs.self_z = 0.0;
    inputs.threat_x = 2.0;
    inputs.threat_y = 0.0;
    inputs.threat_z = 0.0;

    const FleeTargetResult result = sample_flee_target(inputs);
    CHECK(result.candidate_index == 1);
}

TEST_CASE("flee_target_sampler: skips obstructed candidates") {
    const double candidates[] = {
        -5.0, 0.0, 0.0,
        -3.0, 0.0, 4.0,
    };
    const double obstacles[] = {
        -5.5, -1.0, -0.5, -4.5, 1.0, 0.5,
    };

    FleeTargetInputs inputs{};
    inputs.candidate_xyz = candidates;
    inputs.candidate_count = 2;
    inputs.self_x = 0.0;
    inputs.self_y = 0.0;
    inputs.self_z = 0.0;
    inputs.threat_x = 2.0;
    inputs.threat_y = 0.0;
    inputs.threat_z = 0.0;
    inputs.obstacle_aabbs = obstacles;
    inputs.obstacle_count = 1;
    inputs.min_clearance = 0.5;

    const FleeTargetResult result = sample_flee_target(inputs);
    CHECK(result.candidate_index == 1);
}

TEST_CASE("flee_target_sampler: returns invalid when no candidates survive") {
    const double candidates[] = {
        1.0, 0.0, 0.0,
    };
    const double obstacles[] = {
        0.0, -1.0, -1.0, 2.0, 1.0, 1.0,
    };

    FleeTargetInputs inputs{};
    inputs.candidate_xyz = candidates;
    inputs.candidate_count = 1;
    inputs.self_x = 0.0;
    inputs.self_y = 0.0;
    inputs.self_z = 0.0;
    inputs.threat_x = -1.0;
    inputs.threat_y = 0.0;
    inputs.threat_z = 0.0;
    inputs.obstacle_aabbs = obstacles;
    inputs.obstacle_count = 1;
    inputs.min_clearance = 0.5;

    const FleeTargetResult result = sample_flee_target(inputs);
    CHECK(result.candidate_index == -1);
}
