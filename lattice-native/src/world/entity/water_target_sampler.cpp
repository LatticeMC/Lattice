#include "world/entity/water_target_sampler.hpp"

#include <cmath>
#include <limits>

namespace lattice::world::entity {

namespace {

[[nodiscard]] inline double sq_distance(double ax, double ay, double az,
                                        double bx, double by, double bz) noexcept {
    const double dx = ax - bx;
    const double dy = ay - by;
    const double dz = az - bz;
    return dx * dx + dy * dy + dz * dz;
}

} // namespace

WaterTargetResult sample_water_target(const WaterTargetInputs& in) noexcept {
    WaterTargetResult result{};
    if (!in.candidate_xyz || !in.candidate_is_water || in.candidate_count == 0) {
        return result;
    }

    double best_score = -std::numeric_limits<double>::infinity();
    for (std::size_t i = 0; i < in.candidate_count; ++i) {
        const double x = in.candidate_xyz[i * 3 + 0];
        const double y = in.candidate_xyz[i * 3 + 1];
        const double z = in.candidate_xyz[i * 3 + 2];
        if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z)) continue;

        const bool is_water = in.candidate_is_water[i];
        const double distance_penalty = sq_distance(x, y, z, in.self_x, in.self_y, in.self_z) * 0.10;
        const double terrain_bonus = (is_water == in.prefer_water) ? 10.0 : 0.0;
        const double score = terrain_bonus - distance_penalty;
        if (score > best_score) {
            best_score = score;
            result.candidate_index = static_cast<int>(i);
            result.score = score;
        }
    }
    return result;
}

} // namespace lattice::world::entity
