#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::world::entity {

inline constexpr std::size_t kEntityPositionStride = 3;
inline constexpr std::size_t kEntityAabbStride = 6;

enum class EntityPredicateKind : std::uint8_t {
    None = 0,
    IsAlive = 1,
    IsAliveNotSelf = 2,
    IsAliveNotSpectator = 3,
    IsAliveNotSelfNotSpectator = 4,
};

struct EntityQueryInputs {
    double query_min_x;
    double query_min_y;
    double query_min_z;
    double query_max_x;
    double query_max_y;
    double query_max_z;

    const int* entity_ids;
    const int* entity_type_ids;
    const double* entity_positions;
    const double* entity_aabbs;
    const std::uint8_t* entity_alive;
    const std::uint8_t* entity_spectator;
    std::size_t entity_count;

    const int* allowed_type_ids;
    std::size_t allowed_type_count;

    EntityPredicateKind predicate_kind;
    int excluded_entity_id;

    bool sort_by_distance;
    std::size_t max_results;
    double ref_x;
    double ref_y;
    double ref_z;
};

[[nodiscard]] std::size_t query_entities(const EntityQueryInputs& inputs,
                                         int* matched_entity_ids,
                                         double* distances,
                                         std::size_t output_capacity) noexcept;

} // namespace lattice::world::entity
