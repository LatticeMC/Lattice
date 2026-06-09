/**
 * @file water_target_sampler.hpp
 * @brief Pick a destination that prefers water or dry land from pre-generated candidates.
 */

#pragma once

#include <cstddef>

namespace lattice::world::entity {

struct WaterTargetInputs {
    const double* candidate_xyz      = nullptr;
    std::size_t   candidate_count    = 0;
    const bool*   candidate_is_water = nullptr;

    double self_x = 0.0;
    double self_y = 0.0;
    double self_z = 0.0;

    bool prefer_water = true;
};

struct WaterTargetResult {
    int    candidate_index = -1;
    double score           = 0.0;
};

[[nodiscard]] WaterTargetResult sample_water_target(const WaterTargetInputs& inputs) noexcept;

} // namespace lattice::world::entity
