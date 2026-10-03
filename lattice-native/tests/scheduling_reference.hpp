#pragma once
// 冻结自 8e93e8d，用于完整列填充隔离对照。
#include "world/gen/chunknoise/chunk_noise_sampler.hpp"
namespace scheduling_reference {
namespace densityfunction = lattice::world::gen::densityfunction;
inline double* density_row_data(densityfunction::CacheState& cache,
                                int slot, int cellZ,
                                int row_size,
                                bool to_end) noexcept {
    if (slot < 0 || slot >= static_cast<int>(cache.interpolators.size())) return nullptr;
    if (cellZ < 0 || cellZ > cache.horizontal_cell_count) return nullptr;
    if (row_size <= 0 || row_size != cache.vertical_cell_count + 1) return nullptr;

    auto& it = cache.interpolators[slot];
    auto& buf = to_end ? it.end_density_buffer : it.start_density_buffer;
    const std::size_t base = static_cast<std::size_t>(cellZ)
                           * static_cast<std::size_t>(row_size);
    if (base + static_cast<std::size_t>(row_size) > buf.size()) return nullptr;
    return buf.data() + base;
}

inline void fill_density_column_impl(const densityfunction::NodeArena& arena,
                                     densityfunction::CacheState& cache,
                                     double x, double z,
                                     int cellX, int cellZ0,
                                     double y0, double dy,
                                     int horizontalCellCount,
                                     int verticalCellCount,
                                     bool to_end) noexcept {
    const int slot_count = static_cast<int>(arena.interpolator_inputs.size());
    const int row_size = verticalCellCount + 1;
    if (slot_count <= 0 || row_size <= 0) return;
    for (int slot = 0; slot < slot_count; ++slot) {
        const auto root = arena.interpolator_inputs[static_cast<std::size_t>(slot)];
        for (int cellZ = 0; cellZ <= horizontalCellCount; ++cellZ) {
            double* row = density_row_data(cache, slot, cellZ, row_size, to_end);
            if (!row) continue;
            densityfunction::evaluate_y_column(arena, root,
                                               x, y0, z + static_cast<double>(cellZ), dy,
                                               cellX, cellZ0 + cellZ,
                                               row_size,
                                               &cache,
                                               row);
        }
    }
}

}

namespace scheduling_reference {
void fill_start_density_column(lattice::world::gen::chunknoise::ChunkNoiseSampler&,
    lattice::world::gen::chunknoise::Channel,double,double,int,int,double,double,int,int) noexcept;
}
