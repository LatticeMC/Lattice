#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::world::entity {

struct LosInputs {
    const double* from_x = nullptr;
    const double* from_y = nullptr;
    const double* from_z = nullptr;
    const double* to_x = nullptr;
    const double* to_y = nullptr;
    const double* to_z = nullptr;
    std::size_t count = 0;

    const std::int8_t* solid_mask = nullptr;
    int region_min_x = 0;
    int region_min_y = 0;
    int region_min_z = 0;
    int region_size_x = 0;
    int region_size_y = 0;
    int region_size_z = 0;
};

struct LosResult {
    bool has_line_of_sight = false;
    int blocks_checked = 0;
};

[[nodiscard]] LosResult check_line_of_sight(const LosInputs& inputs, std::size_t index) noexcept;

void check_line_of_sight_batch(const LosInputs& inputs, bool* results) noexcept;

} // namespace lattice::world::entity
