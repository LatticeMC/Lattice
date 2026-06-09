#include "world/entity/flee_target_sampler.hpp"

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

[[nodiscard]] bool collides_with_obstacle(const FleeTargetInputs& in,
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

FleeTargetResult sample_flee_target(const FleeTargetInputs& in) noexcept {
    FleeTargetResult result{};
    if (!in.candidate_xyz || in.candidate_count == 0) {
        return result;
    }

    const double self_threat_sq = sq_distance(in.self_x, in.self_y, in.self_z,
                                              in.threat_x, in.threat_y, in.threat_z);
    double best_score = -std::numeric_limits<double>::infinity();

    for (std::size_t i = 0; i < in.candidate_count; ++i) {
        const double x = in.candidate_xyz[i * 3 + 0];
        const double y = in.candidate_xyz[i * 3 + 1];
        const double z = in.candidate_xyz[i * 3 + 2];

        if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z)) continue;
        if (collides_with_obstacle(in, x, y, z)) continue;

        const double candidate_threat_sq = sq_distance(x, y, z, in.threat_x, in.threat_y, in.threat_z);
        const double candidate_self_sq   = sq_distance(x, y, z, in.self_x, in.self_y, in.self_z);
        const double score = (candidate_threat_sq - self_threat_sq) - candidate_self_sq * 0.25;

        if (score > best_score) {
            best_score = score;
            result.candidate_index = static_cast<int>(i);
            result.score = score;
        }
    }

    return result;
}

} // namespace lattice::world::entity
