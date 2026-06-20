#include "world/entity/entity_query.hpp"

#include <algorithm>

namespace lattice::world::entity {
namespace {

struct Match {
    int id;
    double distance_sq;
    std::size_t ordinal;
};

[[nodiscard]] bool intersects(const EntityQueryInputs& inputs, std::size_t index) noexcept {
    const double* box = inputs.entity_aabbs + index * kEntityAabbStride;
    return inputs.query_min_x <= box[3] && inputs.query_max_x >= box[0] &&
           inputs.query_min_y <= box[4] && inputs.query_max_y >= box[1] &&
           inputs.query_min_z <= box[5] && inputs.query_max_z >= box[2];
}

[[nodiscard]] bool type_allowed(const EntityQueryInputs& inputs, std::size_t index) noexcept {
    if (inputs.allowed_type_count == 0) return true;
    const int type_id = inputs.entity_type_ids[index];
    for (std::size_t i = 0; i < inputs.allowed_type_count; ++i) {
        if (inputs.allowed_type_ids[i] == type_id) return true;
    }
    return false;
}

[[nodiscard]] bool predicate_allowed(const EntityQueryInputs& inputs, std::size_t index) noexcept {
    switch (inputs.predicate_kind) {
        case EntityPredicateKind::None:
            return true;
        case EntityPredicateKind::IsAlive:
            return inputs.entity_alive[index] != 0;
        case EntityPredicateKind::IsAliveNotSelf:
            return inputs.entity_alive[index] != 0 && inputs.entity_ids[index] != inputs.excluded_entity_id;
        case EntityPredicateKind::IsAliveNotSpectator:
            return inputs.entity_alive[index] != 0 && inputs.entity_spectator[index] == 0;
        case EntityPredicateKind::IsAliveNotSelfNotSpectator:
            return inputs.entity_alive[index] != 0 &&
                   inputs.entity_ids[index] != inputs.excluded_entity_id &&
                   inputs.entity_spectator[index] == 0;
    }
    return false;
}

[[nodiscard]] double distance_sq(const EntityQueryInputs& inputs, std::size_t index) noexcept {
    const double* pos = inputs.entity_positions + index * kEntityPositionStride;
    const double dx = pos[0] - inputs.ref_x;
    const double dy = pos[1] - inputs.ref_y;
    const double dz = pos[2] - inputs.ref_z;
    return dx * dx + dy * dy + dz * dz;
}

} // namespace

[[nodiscard]] bool nearer(const Match& a, const Match& b) noexcept {
    return a.distance_sq < b.distance_sq ||
           (a.distance_sq == b.distance_sq && a.ordinal > b.ordinal);
}

[[nodiscard]] bool farther(const Match& a, const Match& b) noexcept {
    return a.distance_sq > b.distance_sq ||
           (a.distance_sq == b.distance_sq && a.ordinal < b.ordinal);
}

std::size_t query_entities(const EntityQueryInputs& inputs,
                           int* matched_entity_ids,
                           double* distances,
                           std::size_t output_capacity) noexcept {
    if (!matched_entity_ids || output_capacity == 0 || inputs.entity_count == 0) return 0;
    if (!inputs.entity_ids || !inputs.entity_type_ids || !inputs.entity_positions ||
        !inputs.entity_aabbs || !inputs.entity_alive || !inputs.entity_spectator) {
        return 0;
    }
    if (inputs.allowed_type_count > 0 && !inputs.allowed_type_ids) return 0;

    if (!inputs.sort_by_distance) {
        std::size_t out = 0;
        for (std::size_t i = 0; i < inputs.entity_count && out < output_capacity; ++i) {
            if (!type_allowed(inputs, i) || !predicate_allowed(inputs, i) || !intersects(inputs, i)) continue;
            matched_entity_ids[out] = inputs.entity_ids[i];
            if (distances) distances[out] = 0.0;
            ++out;
        }
        return out;
    }

    if (!distances) return 0;
    const std::size_t limit = inputs.max_results == 0
        ? output_capacity
        : std::min(inputs.max_results, output_capacity);
    if (limit == 0) return 0;

    double* ordinals = distances + output_capacity;
    std::size_t count = 0;
    for (std::size_t i = 0; i < inputs.entity_count; ++i) {
        if (!type_allowed(inputs, i) || !predicate_allowed(inputs, i) || !intersects(inputs, i)) continue;
        const Match match{inputs.entity_ids[i], distance_sq(inputs, i), i};
        if (count < limit) {
            const auto out = count++;
            matched_entity_ids[out] = match.id;
            distances[out] = match.distance_sq;
            ordinals[out] = static_cast<double>(match.ordinal);
            continue;
        }
        std::size_t farthest = 0;
        for (std::size_t j = 1; j < limit; ++j) {
            const Match current{matched_entity_ids[j], distances[j], static_cast<std::size_t>(ordinals[j])};
            const Match worst{matched_entity_ids[farthest], distances[farthest], static_cast<std::size_t>(ordinals[farthest])};
            if (farther(current, worst)) farthest = j;
        }
        const Match worst{matched_entity_ids[farthest], distances[farthest], static_cast<std::size_t>(ordinals[farthest])};
        if (nearer(match, worst)) {
            matched_entity_ids[farthest] = match.id;
            distances[farthest] = match.distance_sq;
            ordinals[farthest] = static_cast<double>(match.ordinal);
        }
    }

    for (std::size_t i = 1; i < count; ++i) {
        Match value{matched_entity_ids[i], distances[i], static_cast<std::size_t>(ordinals[i])};
        std::size_t j = i;
        while (j > 0) {
            const Match previous{matched_entity_ids[j - 1], distances[j - 1], static_cast<std::size_t>(ordinals[j - 1])};
            if (!nearer(value, previous)) break;
            matched_entity_ids[j] = previous.id;
            distances[j] = previous.distance_sq;
            ordinals[j] = static_cast<double>(previous.ordinal);
            --j;
        }
        matched_entity_ids[j] = value.id;
        distances[j] = value.distance_sq;
        ordinals[j] = static_cast<double>(value.ordinal);
    }
    return count;
}

} // namespace lattice::world::entity
