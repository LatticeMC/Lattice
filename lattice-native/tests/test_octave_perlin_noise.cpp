#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cmath>
#include <cstdint>
#include <vector>

#include "world/gen/noise/octave_perlin_noise.hpp"

using namespace lattice::world::gen::noise;

namespace {

PerlinNoiseSampler make_octave(double ox, double oy, double oz, std::uint8_t seed) {
    PerlinNoiseSampler s{};
    s.origin_x = ox; s.origin_y = oy; s.origin_z = oz;
    // Deterministic per-seed permutation.
    for (int i = 0; i < 256; ++i) {
        s.permutation[i] = static_cast<std::uint8_t>(((i * 31) ^ seed) & 0xFF);
    }
    return s;
}

} // namespace

TEST_CASE("octave: zero octaves returns 0") {
    OctavePerlinNoiseSampler s{};
    CHECK(sample(s, 1.0, 2.0, 3.0) == 0.0);
}

TEST_CASE("octave: single non-zero octave matches plain perlin") {
    auto p = make_octave(0.0, 0.0, 0.0, 0x7F);
    const double amp[1] = {1.0};
    OctavePerlinNoiseSampler s{};
    s.octaves       = &p;
    s.amplitudes    = amp;
    s.octave_count  = 1;
    s.lacunarity    = 1.0;
    s.persistence   = 1.0;

    // single-octave OPN with amp=1, persistence=1, lacunarity=1, useOrigin=true
    // should equal perlin sample at maintain_precision(x), maintain_precision(y), maintain_precision(z).
    const double x = 0.5, y = 0.7, z = -1.3;
    const double expected = sample(p, maintain_precision(x),
                                   maintain_precision(y),
                                   maintain_precision(z));
    CHECK(sample(s, x, y, z) == doctest::Approx(expected).epsilon(1e-15));
}

TEST_CASE("octave: skipped octave (amplitude = 0)") {
    auto p0 = make_octave(0.0, 0.0, 0.0, 0x11);
    auto p1 = make_octave(0.0, 0.0, 0.0, 0x22);
    PerlinNoiseSampler octs[2] = {p0, p1};
    const double amp[2] = {0.0, 1.0}; // skip first
    OctavePerlinNoiseSampler s{};
    s.octaves       = octs;
    s.amplitudes    = amp;
    s.octave_count  = 2;
    s.lacunarity    = 1.0;
    s.persistence   = 1.0;
    // Result should equal scale-2-frequency p1 sample with persistence/2 amplitude.
    const double x = 0.5, y = 0.7, z = -1.3;
    // Octave 0 skipped → freq becomes 2, amp becomes 0.5 for octave 1.
    const double fx = maintain_precision(x * 2.0);
    const double fy = maintain_precision(y * 2.0);
    const double fz = maintain_precision(z * 2.0);
    const double expected = 1.0 * 0.5 * sample(p1, fx, fy, fz);
    CHECK(sample(s, x, y, z) == doctest::Approx(expected).epsilon(1e-15));
}

TEST_CASE("octave: maintain_precision round-trips integers ≤ 2^25") {
    CHECK(maintain_precision(0.0) == 0.0);
    CHECK(maintain_precision(1.0) == 1.0);
    CHECK(maintain_precision(1e6) == 1e6);
    // Wrap behaviour: a value > 2^25 / 2 = 16777216 should wrap.
    CHECK(std::abs(maintain_precision(3.0e7)) < 3.5e7);
}

TEST_CASE("octave: full 4-octave sample finite") {
    PerlinNoiseSampler oct[4] = {
        make_octave(1.1, 2.2, 3.3, 0x01),
        make_octave(4.4, 5.5, 6.6, 0x02),
        make_octave(7.7, 8.8, 9.9, 0x03),
        make_octave(0.1, 0.2, 0.3, 0x04),
    };
    const double amps[4] = {1.0, 0.5, 0.25, 0.125};
    OctavePerlinNoiseSampler s{};
    s.octaves       = oct;
    s.amplitudes    = amps;
    s.octave_count  = 4;
    s.lacunarity    = 1.0;
    s.persistence   = 1.0;
    for (double t = -5.0; t <= 5.0; t += 0.7) {
        double v = sample(s, t, t * 0.5, t * 1.3);
        CHECK(std::isfinite(v));
    }
}

// ---- sample_full / useOrigin semantics ----
//
// Mojang's OctavePerlinNoiseSampler.sample(x, y, z, yScale, yMax,
// useOrigin) feeds either `-lv.originY` (useOrigin=true) or
// `maintainPrecision(y * freq)` (useOrigin=false) as the Y argument
// to the inner 5-arg PerlinNoiseSampler.sample. The per-octave x and
// z origins are ALWAYS used by the inner sample. We exercise both
// paths and confirm useOrigin=true makes the result independent of y.

TEST_CASE("octave: sample_full useOrigin=true makes y irrelevant") {
    // Single octave; yScale=0, yMax=0 (they only matter when yScale≠0).
    auto p = make_octave(1.1, 2.2, 3.3, 0x42);
    PerlinNoiseSampler oct[1] = {p};
    const double amps[1] = {1.0};
    OctavePerlinNoiseSampler s{};
    s.octaves       = oct;
    s.amplitudes    = amps;
    s.octave_count  = 1;
    s.lacunarity    = 1.0;
    s.persistence   = 1.0;

    // useOrigin=true: y=`-originY` so inner `y += originY` cancels to
    // exactly 0 — the sample becomes constant in y.
    const double v_y0   = sample_full(s, 0.5, 0.0,   0.7, 0.0, 0.0, /*useOrigin=*/true);
    const double v_y100 = sample_full(s, 0.5, 100.0, 0.7, 0.0, 0.0, /*useOrigin=*/true);
    const double v_yneg = sample_full(s, 0.5, -42.7, 0.7, 0.0, 0.0, /*useOrigin=*/true);
    CHECK(v_y0   == v_y100);
    CHECK(v_y0   == v_yneg);
}

TEST_CASE("octave: sample_full useOrigin=false varies with y") {
    // Same single-octave config; useOrigin=false → y feeds
    // maintain_precision(y * freq) as before, so different y values
    // generally produce different samples.
    auto p = make_octave(1.1, 2.2, 3.3, 0x42);
    PerlinNoiseSampler oct[1] = {p};
    const double amps[1] = {1.0};
    OctavePerlinNoiseSampler s{};
    s.octaves       = oct;
    s.amplitudes    = amps;
    s.octave_count  = 1;
    s.lacunarity    = 1.0;
    s.persistence   = 1.0;

    const double v_y0   = sample_full(s, 0.5, 0.0,   0.7, 0.0, 0.0, /*useOrigin=*/false);
    const double v_y100 = sample_full(s, 0.5, 100.0, 0.7, 0.0, 0.0, /*useOrigin=*/false);
    CHECK(v_y0 != v_y100);
}

TEST_CASE("octave: sample_full uses per-octave x/z origins on both paths") {
    // Two octaves with the same permutation but different (originX, originZ).
    // The samples must differ even with useOrigin=true: vanilla
    // doesn't zero the x/z origins, only fixes the y residual.
    //
    // We deliberately choose x/z sample coordinates that, after
    // applying each octave's origin, do NOT land on integer lattice
    // corners (which would zero out grad and make both samples
    // trivially equal).
    auto p_a = make_octave(/*ox=*/0.0, /*oy=*/0.0, /*oz=*/0.0, 0x55);
    auto p_b = make_octave(/*ox=*/0.3, /*oy=*/0.0, /*oz=*/0.4, 0x55); // same perm, shifted origin
    {
        PerlinNoiseSampler oct[1] = {p_a};
        const double amps[1] = {1.0};
        OctavePerlinNoiseSampler s_a{};
        s_a.octaves = oct; s_a.amplitudes = amps;
        s_a.octave_count = 1; s_a.lacunarity = 1.0; s_a.persistence = 1.0;
        const double v_a = sample_full(s_a, 0.5, 0.0, 0.5, 0.0, 0.0, true);

        oct[0] = p_b;
        OctavePerlinNoiseSampler s_b{};
        s_b.octaves = oct; s_b.amplitudes = amps;
        s_b.octave_count = 1; s_b.lacunarity = 1.0; s_b.persistence = 1.0;
        const double v_b = sample_full(s_b, 0.5, 0.0, 0.5, 0.0, 0.0, true);

        // Different x/z origin on a non-degenerate permutation
        // should produce a different sample value.
        CHECK(v_a != v_b);
    }
}
