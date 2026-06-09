#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include "world/entity/water_target_sampler.hpp"

using namespace lattice::world::entity;

TEST_CASE("water_target_sampler: prefers water candidates when requested") {
    const double candidates[] = {
        1.0, 0.0, 0.0,
        2.0, 0.0, 0.0,
        3.0, 0.0, 0.0,
    };
    const bool is_water[] = {false, true, false};

    WaterTargetInputs inputs{};
    inputs.candidate_xyz = candidates;
    inputs.candidate_is_water = is_water;
    inputs.candidate_count = 3;
    inputs.self_x = 0.0;
    inputs.self_y = 0.0;
    inputs.self_z = 0.0;
    inputs.prefer_water = true;

    const WaterTargetResult result = sample_water_target(inputs);
    CHECK(result.candidate_index == 1);
}

TEST_CASE("water_target_sampler: prefers dry candidates when requested") {
    const double candidates[] = {
        1.0, 0.0, 0.0,
        2.0, 0.0, 0.0,
        3.0, 0.0, 0.0,
    };
    const bool is_water[] = {true, false, true};

    WaterTargetInputs inputs{};
    inputs.candidate_xyz = candidates;
    inputs.candidate_is_water = is_water;
    inputs.candidate_count = 3;
    inputs.self_x = 0.0;
    inputs.self_y = 0.0;
    inputs.self_z = 0.0;
    inputs.prefer_water = false;

    const WaterTargetResult result = sample_water_target(inputs);
    CHECK(result.candidate_index == 1);
}
