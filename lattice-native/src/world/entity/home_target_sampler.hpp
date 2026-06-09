/**
 * @file home_target_sampler.hpp
 * @brief Pick a destination that biases toward a home position.
 */

#pragma once

#include <cstddef>

namespace lattice::world::entity {

struct HomeTargetInputs {
    const double* candidate_xyz   = nullptr;
    std::size_t   candidate_count = 0;

    double self_x = 0.0;
    double self_y = 0.0;
    double self_z = 0.0;

    double home_x = 0.0;
    double home_y = 0.0;
    double home_z = 0.0;

    const double* obstacle_aabbs = nullptr;
    std::size_t   obstacle_count = 0;

    double preferred_distance = 0.0;
    double min_clearance      = 0.0;
};

struct HomeTargetResult {
    int    candidate_index = -1;
    double score           = 0.0;
};

[[nodiscard]] HomeTargetResult sample_home_target(const HomeTargetInputs& inputs) noexcept;

} // namespace lattice::world::entity
