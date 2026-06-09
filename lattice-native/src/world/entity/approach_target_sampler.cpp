#include "world/entity/approach_target_sampler.hpp"

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

[[nodiscard]] bool collides_with_obstacle(const ApproachTargetInputs& in,
                                          double x, double y, double z) noexcept {
    if (!in.obstacle_aabbs || in.obstacle_count == 0) return false;

    const double min_x = x - in.min_clearance;
    const double min_y = y;
    const double min_z = z - in.min_clearance;
    const double max_x = x + in.min_clearance;
    const double max_y = y + 1.0;
    const double max_z = z + in.min_clearance;

    for (std::size_t i = 0; i < in.obstacle_count; ++i) {
        const double* aabb = in.obstacle_aabbs + i * 6;
        if (min_x <= aabb[3] && max_x >= aabb[0] &&
            min_y <= aabb[4] && max_y >= aabb[1] &&
            min_z <= aabb[5] && max_z >= aabb[2]) {
            return true;
        }
    }
    return false;
}

} // namespace

ApproachTargetResult sample_approach_target(const ApproachTargetInputs& in) noexcept {
    ApproachTargetResult result{};
    if (!in.candidate_xyz || in.candidate_count == 0) {
        return result;
    }

    const double preferred_sq = in.preferred_distance * in.preferred_distance;
    double best_score = -std::numeric_limits<double>::infinity();

    for (std::size_t i = 0; i < in.candidate_count; ++i) {
        const double x = in.candidate_xyz[i * 3 + 0];
        const double y = in.candidate_xyz[i * 3 + 1];
        const double z = in.candidate_xyz[i * 3 + 2];

        if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z)) continue;
        if (collides_with_obstacle(in, x, y, z)) continue;

        const double candidate_target_sq = sq_distance(x, y, z, in.target_x, in.target_y, in.target_z);
        const double candidate_self_sq = sq_distance(x, y, z, in.self_x, in.self_y, in.self_z);
        const double distance_error = std::abs(candidate_target_sq - preferred_sq);
        const double score = -distance_error - candidate_self_sq * 0.10;

        if (score > best_score) {
            best_score = score;
            result.candidate_index = static_cast<int>(i);
            result.score = score;
        }
    }

    return result;
}

} // namespace lattice::world::entity
