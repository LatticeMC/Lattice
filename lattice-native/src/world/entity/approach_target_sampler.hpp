/**
 * @file approach_target_sampler.hpp
 * @brief Score and pick an approach destination from pre-generated candidates.
 */

#pragma once

#include <cstddef>

namespace lattice::world::entity {

struct ApproachTargetInputs {
    const double* candidate_xyz   = nullptr;
    std::size_t   candidate_count = 0;

    double self_x = 0.0;
    double self_y = 0.0;
    double self_z = 0.0;

    double target_x = 0.0;
    double target_y = 0.0;
    double target_z = 0.0;

    const double* obstacle_aabbs = nullptr;
    std::size_t   obstacle_count = 0;

    double preferred_distance = 0.0;
    double min_clearance      = 0.0;
};

struct ApproachTargetResult {
    int    candidate_index = -1;
    double score           = 0.0;
};

[[nodiscard]] ApproachTargetResult sample_approach_target(const ApproachTargetInputs& inputs) noexcept;

} // namespace lattice::world::entity
