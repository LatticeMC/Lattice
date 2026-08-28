#include "world/entity/los.hpp"

#include <cmath>
#include <limits>

namespace lattice::world::entity {
namespace {

constexpr double kCollisionEpsilon = 1.0e-7;

[[nodiscard]] int floor_to_int(double value) noexcept {
    return static_cast<int>(std::floor(value));
}

[[nodiscard]] double frac(double value) noexcept {
    return value - std::floor(value);
}

[[nodiscard]] int signum_int(double value) noexcept {
    return (value > 0.0) - (value < 0.0);
}

[[nodiscard]] bool is_inside(const LosInputs& inputs, int x, int y, int z) noexcept {
    return x >= inputs.region_min_x && y >= inputs.region_min_y && z >= inputs.region_min_z &&
           x < inputs.region_min_x + inputs.region_size_x &&
           y < inputs.region_min_y + inputs.region_size_y &&
           z < inputs.region_min_z + inputs.region_size_z;
}

[[nodiscard]] bool has_valid_region(const LosInputs& inputs) noexcept {
    return inputs.solid_mask && inputs.region_size_x > 0 && inputs.region_size_y > 0 && inputs.region_size_z > 0;
}

} // namespace

LosResult check_line_of_sight(const LosInputs& inputs, std::size_t index) noexcept {
    LosResult result{};
    if (!has_valid_region(inputs) || index >= inputs.count ||
        !inputs.from_x || !inputs.from_y || !inputs.from_z || !inputs.to_x || !inputs.to_y || !inputs.to_z) {
        return result;
    }

    const double from_x = inputs.from_x[index];
    const double from_y = inputs.from_y[index];
    const double from_z = inputs.from_z[index];
    const double to_x = inputs.to_x[index];
    const double to_y = inputs.to_y[index];
    const double to_z = inputs.to_z[index];

    const double adj_x = kCollisionEpsilon * (from_x - to_x);
    const double adj_y = kCollisionEpsilon * (from_y - to_y);
    const double adj_z = kCollisionEpsilon * (from_z - to_z);
    if (adj_x == 0.0 && adj_y == 0.0 && adj_z == 0.0) {
        result.has_line_of_sight = true;
        return result;
    }

    const double from_x_adj = from_x + adj_x;
    const double from_y_adj = from_y + adj_y;
    const double from_z_adj = from_z + adj_z;
    const double to_x_adj = to_x - adj_x;
    const double to_y_adj = to_y - adj_y;
    const double to_z_adj = to_z - adj_z;

    int curr_x = floor_to_int(from_x_adj);
    int curr_y = floor_to_int(from_y_adj);
    int curr_z = floor_to_int(from_z_adj);

    const double diff_x = to_x_adj - from_x_adj;
    const double diff_y = to_y_adj - from_y_adj;
    const double diff_z = to_z_adj - from_z_adj;
    const int dx = signum_int(diff_x);
    const int dy = signum_int(diff_y);
    const int dz = signum_int(diff_z);

    const double max_double = std::numeric_limits<double>::max();
    const double norm_diff_x = diff_x == 0.0 ? max_double : static_cast<double>(dx) / diff_x;
    const double norm_diff_y = diff_y == 0.0 ? max_double : static_cast<double>(dy) / diff_y;
    const double norm_diff_z = diff_z == 0.0 ? max_double : static_cast<double>(dz) / diff_z;

    double norm_curr_x = norm_diff_x * (diff_x > 0.0 ? (1.0 - frac(from_x_adj)) : frac(from_x_adj));
    double norm_curr_y = norm_diff_y * (diff_y > 0.0 ? (1.0 - frac(from_y_adj)) : frac(from_y_adj));
    double norm_curr_z = norm_diff_z * (diff_z > 0.0 ? (1.0 - frac(from_z_adj)) : frac(from_z_adj));

    // Keep the flattened mask cursor alongside the integer DDA coordinates.
    // The previous implementation recomputed three multiplies and two adds
    // for every voxel.  Cursor deltas are exact and remain valid until the
    // bounds check rejects a coordinate outside the region.
    const std::ptrdiff_t stride_z = inputs.region_size_x;
    const std::ptrdiff_t stride_y = stride_z * inputs.region_size_z;
    std::ptrdiff_t mask_cursor =
            (static_cast<std::ptrdiff_t>(curr_y - inputs.region_min_y) * stride_y) +
            (static_cast<std::ptrdiff_t>(curr_z - inputs.region_min_z) * stride_z) +
            static_cast<std::ptrdiff_t>(curr_x - inputs.region_min_x);

    for (;;) {
        if (!is_inside(inputs, curr_x, curr_y, curr_z)) {
            return result;
        }
        ++result.blocks_checked;
        if (inputs.solid_mask[mask_cursor] != 0) {
            return result;
        }

        if (norm_curr_x > 1.0 && norm_curr_y > 1.0 && norm_curr_z > 1.0) {
            result.has_line_of_sight = true;
            return result;
        }

        if (norm_curr_x < norm_curr_y) {
            if (norm_curr_x < norm_curr_z) {
                curr_x += dx;
                mask_cursor += dx;
                norm_curr_x += norm_diff_x;
            } else {
                curr_z += dz;
                mask_cursor += dz * stride_z;
                norm_curr_z += norm_diff_z;
            }
        } else if (norm_curr_y < norm_curr_z) {
            curr_y += dy;
            mask_cursor += dy * stride_y;
            norm_curr_y += norm_diff_y;
        } else {
            curr_z += dz;
            mask_cursor += dz * stride_z;
            norm_curr_z += norm_diff_z;
        }
    }
}

void check_line_of_sight_batch(const LosInputs& inputs, bool* results) noexcept {
    if (!results) return;
    for (std::size_t i = 0; i < inputs.count; ++i) {
        results[i] = check_line_of_sight(inputs, i).has_line_of_sight;
    }
}

} // namespace lattice::world::entity
