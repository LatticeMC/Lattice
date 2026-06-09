// Chunk-level noise sampler. See chunk_noise_sampler.hpp.

#include "world/gen/chunknoise/chunk_noise_sampler.hpp"

namespace lattice::world::gen::chunknoise {

namespace {

inline const densityfunction::NodeArena* channel_arena(
        const NoiseRouter& r, Channel ch) noexcept {
    switch (ch) {
        case Channel::kBarrierNoise:                return r.barrier_noise;
        case Channel::kFluidLevelFloodednessNoise:  return r.fluid_level_floodedness_noise;
        case Channel::kFluidLevelSpreadNoise:       return r.fluid_level_spread_noise;
        case Channel::kLavaNoise:                   return r.lava_noise;
        case Channel::kTemperature:                 return r.temperature;
        case Channel::kVegetation:                  return r.vegetation;
        case Channel::kContinents:                  return r.continents;
        case Channel::kErosion:                     return r.erosion;
        case Channel::kDepth:                       return r.depth;
        case Channel::kRidges:                      return r.ridges;
        case Channel::kPreliminarySurfaceLevel:     return r.preliminary_surface_level;
        case Channel::kFinalDensity:                return r.final_density;
        case Channel::kVeinToggle:                  return r.vein_toggle;
        case Channel::kVeinRidged:                  return r.vein_ridged;
        case Channel::kVeinGap:                     return r.vein_gap;
        case Channel::kCount:                       return nullptr;
    }
    return nullptr;
}

inline double do_sample(const densityfunction::NodeArena* arena,
                        densityfunction::CacheState* cache,
                        double x, double y, double z,
                        int cellX, int cellZ) noexcept {
    if (!arena) return 0.0;
    densityfunction::Context ctx{};
    ctx.x = x; ctx.y = y; ctx.z = z;
    ctx.cellX = cellX; ctx.cellZ = cellZ;
    ctx.cache = cache;
    return densityfunction::evaluate(*arena, ctx);
}

inline densityfunction::CacheState* channel_cache(
        ChunkNoiseSampler& s, Channel ch) noexcept {
    const auto idx = static_cast<std::size_t>(ch);
    return idx < kChannelCount ? &s.caches[idx] : nullptr;
}

inline const densityfunction::NodeArena* checked_channel_arena(
        const ChunkNoiseSampler& s, Channel ch) noexcept {
    const auto idx = static_cast<std::size_t>(ch);
    return idx < kChannelCount ? channel_arena(s.router, ch) : nullptr;
}

inline void set_density_row_impl(densityfunction::CacheState& cache,
                                 int slot, int cellZ,
                                 std::span<const double> row,
                                 bool to_end) noexcept {
    if (slot < 0 || slot >= static_cast<int>(cache.interpolators.size())) return;
    if (cellZ < 0 || cellZ > cache.horizontal_cell_count) return;
    const int expected = cache.vertical_cell_count + 1;
    if (expected < 0 || row.size() != static_cast<std::size_t>(expected)) return;

    auto& it = cache.interpolators[slot];
    auto& buf = to_end ? it.end_density_buffer : it.start_density_buffer;
    const std::size_t base = static_cast<std::size_t>(cellZ)
                           * static_cast<std::size_t>(expected);
    if (base + row.size() > buf.size()) return;
    for (std::size_t i = 0; i < row.size(); ++i) {
        buf[base + i] = row[i];
    }
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
    if (slot_count <= 0) return;
    for (int slot = 0; slot < slot_count; ++slot) {
        const auto root = arena.interpolator_inputs[static_cast<std::size_t>(slot)];
        for (int cellZ = 0; cellZ <= horizontalCellCount; ++cellZ) {
            std::vector<double> row(static_cast<std::size_t>(verticalCellCount + 1), 0.0);
            densityfunction::Context ctx{};
            ctx.x = x;
            ctx.z = z + static_cast<double>(cellZ);
            ctx.cellX = cellX;
            ctx.cellZ = cellZ0 + cellZ;
            ctx.cache = &cache;
            for (int cellY = 0; cellY <= verticalCellCount; ++cellY) {
                ctx.y = y0 + static_cast<double>(cellY) * dy;
                row[static_cast<std::size_t>(cellY)] = densityfunction::evaluate(arena, root, ctx);
            }
            set_density_row_impl(cache, slot, cellZ,
                                 std::span<const double>(row.data(), row.size()),
                                 to_end);
        }
    }
}

} // namespace

void ChunkNoiseSampler::prepare_cache() {
    for (std::size_t i = 0; i < kChannelCount; ++i) {
        const auto* arena = channel_arena(router, static_cast<Channel>(i));
        if (!arena) {
            // Drop the cache for absent channels — saves memory if the
            // router is reused with a smaller channel set.
            caches[i] = {};
            continue;
        }
        caches[i].resize_for(*arena);
    }
}

void ChunkNoiseSampler::clear_cache() noexcept {
    for (auto& c : caches) c.clear();
}

double ChunkNoiseSampler::sample_final_density(double x, double y, double z,
                                               int cellX, int cellZ) noexcept {
    return do_sample(router.final_density,
                     &caches[static_cast<std::size_t>(Channel::kFinalDensity)],
                     x, y, z, cellX, cellZ);
}

double ChunkNoiseSampler::sample(Channel ch, double x, double y, double z,
                                 int cellX, int cellZ) noexcept {
    const auto idx = static_cast<std::size_t>(ch);
    if (idx >= kChannelCount) return 0.0;
    return do_sample(channel_arena(router, ch),
                     &caches[idx],
                     x, y, z, cellX, cellZ);
}

int ChunkNoiseSampler::num_interpolator_slots(Channel ch) const noexcept {
    const auto* arena = checked_channel_arena(*this, ch);
    return arena ? arena->num_interpolator_slots : 0;
}

void ChunkNoiseSampler::prepare_interpolators(Channel ch,
                                              int horizontalCellCount,
                                              int verticalCellCount) noexcept {
    auto* cache = channel_cache(*this, ch);
    if (!cache || horizontalCellCount < 0 || verticalCellCount < 0) return;
    cache->prepare_interpolators(horizontalCellCount, verticalCellCount);
}

void ChunkNoiseSampler::start_interpolation(Channel ch) noexcept {
    auto* cache = channel_cache(*this, ch);
    if (!cache) return;
    densityfunction::start_interpolation(*cache);
}

void ChunkNoiseSampler::stop_interpolation(Channel ch) noexcept {
    auto* cache = channel_cache(*this, ch);
    if (!cache) return;
    densityfunction::stop_interpolation(*cache);
}

void ChunkNoiseSampler::set_start_density(Channel ch, int slot,
                                          int cellZ, int cellY, double value) noexcept {
    auto* cache = channel_cache(*this, ch);
    if (!cache) return;
    densityfunction::set_start_density(*cache, slot, cellZ, cellY, value);
}

void ChunkNoiseSampler::set_end_density(Channel ch, int slot,
                                        int cellZ, int cellY, double value) noexcept {
    auto* cache = channel_cache(*this, ch);
    if (!cache) return;
    densityfunction::set_end_density(*cache, slot, cellZ, cellY, value);
}

void ChunkNoiseSampler::set_start_density_row(Channel ch, int slot,
                                              int cellZ,
                                              std::span<const double> row) noexcept {
    auto* cache = channel_cache(*this, ch);
    if (!cache) return;
    set_density_row_impl(*cache, slot, cellZ, row, false);
}

void ChunkNoiseSampler::set_end_density_row(Channel ch, int slot,
                                            int cellZ,
                                            std::span<const double> row) noexcept {
    auto* cache = channel_cache(*this, ch);
    if (!cache) return;
    set_density_row_impl(*cache, slot, cellZ, row, true);
}

void ChunkNoiseSampler::on_sampled_cell_corners(Channel ch,
                                                int cellY, int cellZ) noexcept {
    auto* cache = channel_cache(*this, ch);
    if (!cache) return;
    densityfunction::on_sampled_cell_corners(*cache, cellY, cellZ);
}

void ChunkNoiseSampler::interpolate_y(Channel ch, double deltaY) noexcept {
    auto* cache = channel_cache(*this, ch);
    if (!cache) return;
    densityfunction::interpolate_y(*cache, deltaY);
}

void ChunkNoiseSampler::interpolate_x(Channel ch, double deltaX) noexcept {
    auto* cache = channel_cache(*this, ch);
    if (!cache) return;
    densityfunction::interpolate_x(*cache, deltaX);
}

void ChunkNoiseSampler::interpolate_z(Channel ch, double deltaZ) noexcept {
    auto* cache = channel_cache(*this, ch);
    if (!cache) return;
    densityfunction::interpolate_z(*cache, deltaZ);
}

void ChunkNoiseSampler::swap_buffers(Channel ch) noexcept {
    auto* cache = channel_cache(*this, ch);
    if (!cache) return;
    densityfunction::swap_buffers(*cache);
}

void ChunkNoiseSampler::advance_column(Channel ch) noexcept {
    swap_buffers(ch);
}

void ChunkNoiseSampler::fill_start_density_column(Channel ch,
                                                  double x, double z,
                                                  int cellX, int cellZ0,
                                                  double y0, double dy,
                                                  int horizontalCellCount,
                                                  int verticalCellCount) noexcept {
    const auto* arena = checked_channel_arena(*this, ch);
    auto* cache = channel_cache(*this, ch);
    if (!arena || !cache) return;
    fill_density_column_impl(*arena, *cache,
                             x, z, cellX, cellZ0,
                             y0, dy,
                             horizontalCellCount,
                             verticalCellCount,
                             false);
}

void ChunkNoiseSampler::fill_end_density_column(Channel ch,
                                                double x, double z,
                                                int cellX, int cellZ0,
                                                double y0, double dy,
                                                int horizontalCellCount,
                                                int verticalCellCount) noexcept {
    const auto* arena = checked_channel_arena(*this, ch);
    auto* cache = channel_cache(*this, ch);
    if (!arena || !cache) return;
    fill_density_column_impl(*arena, *cache,
                             x, z, cellX, cellZ0,
                             y0, dy,
                             horizontalCellCount,
                             verticalCellCount,
                             true);
}

void ChunkNoiseSampler::prime_final_density_columns(double startX, double endX,
                                                    double z,
                                                    int startCellX, int endCellX,
                                                    int cellZ0,
                                                    double y0, double dy,
                                                    int horizontalCellCount,
                                                    int verticalCellCount) noexcept {
    prime_channel_columns(Channel::kFinalDensity,
                          startX, endX,
                          z,
                          startCellX, endCellX,
                          cellZ0,
                          y0, dy,
                          horizontalCellCount,
                          verticalCellCount);
}

void ChunkNoiseSampler::advance_final_density_column(double nextX,
                                                     double z,
                                                     int nextCellX,
                                                     int cellZ0,
                                                     double y0, double dy,
                                                     int horizontalCellCount,
                                                     int verticalCellCount) noexcept {
    advance_channel_column(Channel::kFinalDensity,
                           nextX,
                           z,
                           nextCellX,
                           cellZ0,
                           y0, dy,
                           horizontalCellCount,
                           verticalCellCount);
}

void ChunkNoiseSampler::prime_channel_columns(Channel ch,
                                              double startX, double endX,
                                              double z,
                                              int startCellX, int endCellX,
                                              int cellZ0,
                                              double y0, double dy,
                                              int horizontalCellCount,
                                              int verticalCellCount) noexcept {
    fill_start_density_column(ch,
                              startX, z,
                              startCellX, cellZ0,
                              y0, dy,
                              horizontalCellCount,
                              verticalCellCount);
    fill_end_density_column(ch,
                            endX, z,
                            endCellX, cellZ0,
                            y0, dy,
                            horizontalCellCount,
                            verticalCellCount);
}

void ChunkNoiseSampler::advance_channel_column(Channel ch,
                                               double nextX,
                                               double z,
                                               int nextCellX,
                                               int cellZ0,
                                               double y0, double dy,
                                               int horizontalCellCount,
                                               int verticalCellCount) noexcept {
    advance_column(ch);
    fill_end_density_column(ch,
                            nextX, z,
                            nextCellX, cellZ0,
                            y0, dy,
                            horizontalCellCount,
                            verticalCellCount);
}

void ChunkNoiseSampler::sample_final_density_cell_grid(int cellY, int cellZ,
                                                       double x0, double y0, double z0,
                                                       double dx, double dy, double dz,
                                                       int cellX, int cellZCoord,
                                                       int nx, int ny, int nz,
                                                       double* out) noexcept {
    sample_cell_grid(Channel::kFinalDensity,
                     cellY, cellZ,
                     x0, y0, z0,
                     dx, dy, dz,
                     cellX, cellZCoord,
                     nx, ny, nz,
                     out);
}

void ChunkNoiseSampler::sample_cell_grid(Channel ch,
                                         int cellY, int cellZ,
                                         double x0, double y0, double z0,
                                         double dx, double dy, double dz,
                                         int cellX, int cellZCoord,
                                         int nx, int ny, int nz,
                                         double* out) noexcept {
    if (!out || nx <= 0 || ny <= 0 || nz <= 0) return;
    const auto idx = static_cast<std::size_t>(ch);
    if (idx >= kChannelCount) return;
    auto& cache = caches[idx];
    densityfunction::on_sampled_cell_corners(cache, cellY, cellZ);

    const double inv_x = nx > 1 ? 1.0 / static_cast<double>(nx - 1) : 0.0;
    const double inv_y = ny > 1 ? 1.0 / static_cast<double>(ny - 1) : 0.0;
    const double inv_z = nz > 1 ? 1.0 / static_cast<double>(nz - 1) : 0.0;

    for (int iy = 0; iy < ny; ++iy) {
        const double delta_y = static_cast<double>(iy) * inv_y;
        densityfunction::interpolate_y(cache, delta_y);
        for (int ix = 0; ix < nx; ++ix) {
            const double delta_x = static_cast<double>(ix) * inv_x;
            densityfunction::interpolate_x(cache, delta_x);
            for (int iz = 0; iz < nz; ++iz) {
                const double delta_z = static_cast<double>(iz) * inv_z;
                densityfunction::interpolate_z(cache, delta_z);
                const std::size_t out_idx = (static_cast<std::size_t>(iy) * nz + iz)
                                          * static_cast<std::size_t>(nx)
                                          + static_cast<std::size_t>(ix);
                out[out_idx] = sample(ch,
                                      x0 + static_cast<double>(ix) * dx,
                                      y0 + static_cast<double>(iy) * dy,
                                      z0 + static_cast<double>(iz) * dz,
                                      cellX, cellZCoord);
            }
        }
    }
}

} // namespace lattice::world::gen::chunknoise
