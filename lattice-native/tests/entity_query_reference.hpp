#pragma once
#include "world/entity/entity_query.hpp"
namespace entity_query_reference {
std::size_t query_entities(const lattice::world::entity::EntityQueryInputs&, int*, double*, std::size_t) noexcept;
}
