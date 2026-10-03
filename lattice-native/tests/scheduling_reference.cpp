#include "scheduling_reference.hpp"
namespace scheduling_reference {
using namespace lattice::world::gen::chunknoise;
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

}
void fill_start_density_column(ChunkNoiseSampler& sampler, Channel ch,
                                                  double x, double z,
                                                  int cellX, int cellZ0,
                                                  double y0, double dy,
                                                  int horizontalCellCount,
                                                  int verticalCellCount) noexcept {
    const auto* arena = checked_channel_arena(sampler, ch);
    auto* cache = channel_cache(sampler, ch);
    if (!arena || !cache) return;
    fill_density_column_impl(*arena, *cache,
                             x, z, cellX, cellZ0,
                             y0, dy,
                             horizontalCellCount,
                             verticalCellCount,
                             false);
}

}
