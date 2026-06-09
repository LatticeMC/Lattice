// OreVeinSampler — see ore_vein.hpp.
//
// 1:1 port of OreVeinSampler.create(...)'s lambda in
// net.minecraft.world.gen.OreVeinSampler (1.21.11). Java does this
// inside a lambda capturing the four DensityFunctions; we compute
// the same expression with the three sampled values pre-supplied
// (the Java side or NativeChunkNoiseSampler runs the DFs first).

#include "world/gen/orevein/ore_vein.hpp"

#include <algorithm>
#include <cmath>

namespace lattice::world::gen::orevein {

OreVeinResult sample_at(const VeinSamples& samples,
                        const rng::Splitter& splitter,
                        int block_x, int block_y, int block_z) noexcept {
    // Vanilla path (production: SharedConstants.ORE_VEINS = false):
    //   d   = vein_toggle.sample(pos);
    //   i   = pos.blockY();
    //   lv  = d > 0 ? COPPER : IRON;     // VeinType (inner shadow)
    //   e   = abs(d);
    //   j   = lv.maxY - i;
    //   k   = i - lv.minY;
    //   if (k < 0 || j < 0) return null;     // outside vein layer
    //   l   = min(j, k);
    //   f   = clampedMap(l, 0, 20, -0.2, 0.0);
    //   if (e + f < 0.4) return null;
    //   r   = randomDeriver.split(x, y, z);
    //   if (r.nextFloat() > 0.7) return null;
    //   if (vein_ridged.sample(pos) >= 0) return null;
    //   g   = clampedMap(e, 0.4, 0.6, 0.1, 0.3);
    //   if (r.nextFloat() < g && vein_gap.sample(pos) > -0.3) {
    //       return r.nextFloat() < 0.02 ? lv.rawOreBlock : lv.ore;
    //   }
    //   return lv.stone;                     // filler ring
    //
    // We omit Mojang's ORE_VEINS=true debug code path (returns AIR /
    // OAK_BUTTON) since that flag is always false in shipped builds.

    const double d  = samples.vein_toggle;
    const int    i  = block_y;
    const bool   is_copper = d > 0.0;
    const VeinType& lv = is_copper ? kCopper : kIron;
    const double e  = std::abs(d);
    const int    j  = lv.max_y - i;
    const int    k  = i - lv.min_y;

    if (k < 0 || j < 0) return OreVeinResult::kNone;

    const int    l = std::min(j, k);
    const double f = clamped_map(static_cast<double>(l), 0.0,
                                 static_cast<double>(kMaxDensityIntrusion),
                                 -kLiminalDensityReduction, 0.0);
    if (e + f < static_cast<double>(kDensityThreshold)) {
        return OreVeinResult::kNone;
    }

    auto r = splitter.split(block_x, i, block_z);

    // First gate: 30% reject.
    if (r.next_float() > kBlockGenerationChance) {
        return OreVeinResult::kNone;
    }
    // Second gate: must be inside the ridged sub-region.
    if (samples.vein_ridged >= 0.0) {
        return OreVeinResult::kNone;
    }

    const double g = clamped_map(e,
                                 static_cast<double>(kDensityThreshold),
                                 static_cast<double>(kDensityForMaxOreChance),
                                 static_cast<double>(kMinOreChance),
                                 static_cast<double>(kMaxOreChance));

    if (static_cast<double>(r.next_float()) < g
        && samples.vein_gap > static_cast<double>(kVeinGapThreshold)) {
        // Inner ore. 2% chance of "raw block" promotion.
        if (r.next_float() < kRawOreBlockChance) {
            return is_copper ? OreVeinResult::kRawCopperBlock
                             : OreVeinResult::kRawIronBlock;
        }
        return is_copper ? OreVeinResult::kCopperOre
                         : OreVeinResult::kIronOre;
    }

    // Filler ring.
    return is_copper ? OreVeinResult::kCopperFiller
                     : OreVeinResult::kIronFiller;
}

void sample_grid(const double* vein_toggle,
                 const double* vein_ridged,
                 const double* vein_gap,
                 int nx, int ny, int nz,
                 const rng::Splitter& splitter,
                 int block_x0, int block_y0, int block_z0,
                 int block_dx, int block_dy, int block_dz,
                 OreVeinResult* out) noexcept {
    if (!vein_toggle || !vein_ridged || !vein_gap || !out) return;
    if (nx <= 0 || ny <= 0 || nz <= 0) return;

    for (int iy = 0; iy < ny; ++iy) {
        for (int iz = 0; iz < nz; ++iz) {
            for (int ix = 0; ix < nx; ++ix) {
                const std::size_t idx = (static_cast<std::size_t>(iy) * nz + iz)
                                      * static_cast<std::size_t>(nx)
                                      + static_cast<std::size_t>(ix);
                const VeinSamples samples{
                    vein_toggle[idx],
                    vein_ridged[idx],
                    vein_gap[idx],
                };
                out[idx] = sample_at(samples, splitter,
                                     block_x0 + ix * block_dx,
                                     block_y0 + iy * block_dy,
                                     block_z0 + iz * block_dz);
            }
        }
    }
}

} // namespace lattice::world::gen::orevein
