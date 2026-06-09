#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cmath>
#include <cstdint>

#include "world/gen/orevein/ore_vein.hpp"
#include "world/gen/rng/xoroshiro128pp.hpp"

using namespace lattice::world::gen::orevein;
using lattice::world::gen::rng::Splitter;

namespace {

// Convenience: a Splitter with two non-trivial seeds.
Splitter test_splitter() {
    return Splitter{0xDEADBEEFCAFEBABEULL, 0x0123456789ABCDEFULL};
}

} // namespace

TEST_CASE("ore_vein: y outside iron layer returns kNone") {
    // Iron vein extents: minY=-60, maxY=-8.
    // Pick veinToggle < 0 so iron layer is selected.
    VeinSamples s{-0.5, -1.0, 0.0};
    auto sp = test_splitter();

    // y=0 (above maxY=-8) → k = 0 - (-60) = 60, j = -8 - 0 = -8 → j<0 → kNone.
    CHECK(sample_at(s, sp, 0, 0, 0)   == OreVeinResult::kNone);
    // y=-100 (below minY=-60) → k = -100-(-60) = -40 → k<0 → kNone.
    CHECK(sample_at(s, sp, 0, -100, 0) == OreVeinResult::kNone);
}

TEST_CASE("ore_vein: y outside copper layer returns kNone") {
    // Copper vein extents: minY=0, maxY=50. veinToggle > 0 selects copper.
    VeinSamples s{0.5, -1.0, 0.0};
    auto sp = test_splitter();

    // y=-1 → k = -1, kNone.
    CHECK(sample_at(s, sp, 0, -1, 0) == OreVeinResult::kNone);
    // y=51 → j = -1, kNone.
    CHECK(sample_at(s, sp, 0, 51, 0) == OreVeinResult::kNone);
}

TEST_CASE("ore_vein: weak density inside layer returns kNone") {
    // |veinToggle| = 0.1 — well below 0.4 threshold, plus the
    // intrusion bonus at y near the centre is at most 0.0 (still < 0.4).
    VeinSamples s{0.1, -1.0, 0.0};
    auto sp = test_splitter();
    CHECK(sample_at(s, sp, 0, 25, 0) == OreVeinResult::kNone);
}

TEST_CASE("ore_vein: ridged >= 0 short-circuits to kNone") {
    // Strong toggle so we pass the density gate, but veinRidged is
    // positive — vanilla bails before drawing the ore.
    VeinSamples s{0.7, 0.5, 0.0};
    auto sp = test_splitter();
    // y=25 is well inside the copper layer. Should still return kNone
    // because vein_ridged is positive.
    CHECK(sample_at(s, sp, 12345, 25, 67890) == OreVeinResult::kNone);
}

TEST_CASE("ore_vein: deterministic for a given (x,y,z) and seeds") {
    VeinSamples s{0.7, -0.5, 0.0};
    auto sp = test_splitter();
    const auto a = sample_at(s, sp, 100, 25, 200);
    const auto b = sample_at(s, sp, 100, 25, 200);
    CHECK(a == b);
}

TEST_CASE("ore_vein: copper bias selected for positive toggle") {
    // Force a strong copper signal that should generate something
    // other than kNone for at least one (x,z) over a small grid.
    auto sp = test_splitter();
    bool saw_copper = false;
    bool saw_iron   = false;
    for (int x = 0; x < 32 && !(saw_copper && saw_iron); ++x) {
        for (int z = 0; z < 32; ++z) {
            // Strong copper toggle at copper-layer depth.
            VeinSamples cs{0.8, -0.5, 0.5};
            const auto cr = sample_at(cs, sp, x, 25, z);
            if (cr == OreVeinResult::kCopperOre
                || cr == OreVeinResult::kRawCopperBlock
                || cr == OreVeinResult::kCopperFiller) {
                saw_copper = true;
            }
            // Strong iron toggle at iron-layer depth.
            VeinSamples is_{-0.8, -0.5, 0.5};
            const auto ir = sample_at(is_, sp, x, -30, z);
            if (ir == OreVeinResult::kIronOre
                || ir == OreVeinResult::kRawIronBlock
                || ir == OreVeinResult::kIronFiller) {
                saw_iron = true;
            }
        }
    }
    CHECK(saw_copper);
    CHECK(saw_iron);
}

TEST_CASE("ore_vein: vein_gap <= -0.3 forces filler over inner ore") {
    // With strong density and ridged < 0, vanilla then checks
    //   r.nextFloat() < g  AND  vein_gap > -0.3
    // for the inner ore branch. If vein_gap is <= -0.3, the inner
    // condition fails and we get filler regardless of the random
    // float. We exercise that by setting vein_gap deeply negative.
    VeinSamples s{0.55, -0.5, -1.0};
    auto sp = test_splitter();

    // Sweep enough positions to enter the post-gate branch a few
    // times; every entry that gets that far must produce a filler,
    // never an ore or raw block.
    int saw_filler = 0;
    int saw_ore_or_raw = 0;
    for (int x = 0; x < 64; ++x) {
        for (int z = 0; z < 64; ++z) {
            const auto r = sample_at(s, sp, x, 25, z);
            if (r == OreVeinResult::kCopperFiller
                || r == OreVeinResult::kIronFiller) {
                ++saw_filler;
            } else if (r == OreVeinResult::kCopperOre
                       || r == OreVeinResult::kIronOre
                       || r == OreVeinResult::kRawCopperBlock
                       || r == OreVeinResult::kRawIronBlock) {
                ++saw_ore_or_raw;
            }
        }
    }
    CHECK(saw_filler > 0);
    CHECK(saw_ore_or_raw == 0);
}

TEST_CASE("ore_vein: clamped_map matches Mojang's MathHelper") {
    // value below oldStart → clamps to newStart.
    CHECK(clamped_map(-5.0, 0.0, 10.0, 0.0, 100.0)  == 0.0);
    // value above oldEnd → clamps to newEnd.
    CHECK(clamped_map(15.0, 0.0, 10.0, 0.0, 100.0)  == 100.0);
    // Linear midpoint.
    CHECK(clamped_map(5.0,  0.0, 10.0, 0.0, 100.0)  == 50.0);
    // Negative-direction remap.
    CHECK(clamped_map(0.5,  0.0,  1.0, -0.2, 0.0)   == doctest::Approx(-0.1));
}

TEST_CASE("ore_vein: sample_grid matches point sampling") {
    auto sp = test_splitter();
    constexpr int nx = 2, ny = 2, nz = 2;
    const double vt[nx * ny * nz] = {0.8, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8};
    const double vr[nx * ny * nz] = {-0.5, -0.5, -0.5, -0.5, -0.5, -0.5, -0.5, -0.5};
    const double vg[nx * ny * nz] = {0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5};
    OreVeinResult out[nx * ny * nz]{};
    sample_grid(vt, vr, vg,
                nx, ny, nz,
                sp,
                10, 20, 30,
                1, 1, 1,
                out);

    for (int iy = 0; iy < ny; ++iy) {
        for (int iz = 0; iz < nz; ++iz) {
            for (int ix = 0; ix < nx; ++ix) {
                const std::size_t idx = (static_cast<std::size_t>(iy) * nz + iz)
                                      * static_cast<std::size_t>(nx)
                                      + static_cast<std::size_t>(ix);
                VeinSamples samples{vt[idx], vr[idx], vg[idx]};
                CHECK(out[idx] == sample_at(samples, sp, 10 + ix, 20 + iy, 30 + iz));
            }
        }
    }
}
