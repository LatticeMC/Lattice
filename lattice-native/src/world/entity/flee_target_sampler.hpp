/**
 * @file flee_target_sampler.hpp
 * @brief Score and pick a flee destination from pre-generated candidates.
 *
 * This helper does not replace vanilla pathfinding. It selects the best
 * candidate point for a fleeing entity from a caller-supplied set of reachable
 * or potentially-reachable positions. Java remains responsible for generating
 * candidates and handing the winning point to vanilla navigation.
 */

#pragma once

#include <cstddef>

namespace lattice::world::entity {

struct FleeTargetInputs {
    const double* candidate_xyz   = nullptr;
    std::size_t   candidate_count = 0;

    double self_x = 0.0;
    double self_y = 0.0;
    double self_z = 0.0;

    double threat_x = 0.0;
    double threat_y = 0.0;
    double threat_z = 0.0;

    const double* obstacle_aabbs = nullptr;
    std::size_t   obstacle_count = 0;

    double min_clearance = 0.0;
};

struct FleeTargetResult {
    int    candidate_index = -1;
    double score           = 0.0;
};

[[nodiscard]] FleeTargetResult sample_flee_target(const FleeTargetInputs& inputs) noexcept;

} // namespace lattice::world::entity
