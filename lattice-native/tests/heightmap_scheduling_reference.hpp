#pragma once
#include "world/heightmap/heightmap_scan.hpp"
namespace heightmap_scheduling_reference {
std::size_t populate_scalar(const lattice::world::heightmap::SectionView*, std::size_t, int, std::size_t, int, std::int32_t*) noexcept;
}
