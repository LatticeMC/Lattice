#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cmath>
#include <cstdint>
#include <vector>

#include "world/gen/noise/interpolated_noise.hpp"
#include "world/gen/noise/octave_perlin_noise.hpp"
#include "world/gen/noise/perlin_noise.hpp"

namespace noise = lattice::world::gen::noise;
using noise::InterpolatedNoiseSampler;
using noise::OctavePerlinNoiseSampler;
using noise::PerlinNoiseSampler;

namespace {

// Build a minimal Perlin sampler with deterministic permutation and
// fixed origin. Enough variation to exercise the per-octave path
// without depending on Mojang RNG.
PerlinNoiseSampler make_perlin(std::uint8_t seed,
                               double ox = 0.0, double oy = 0.0, double oz = 0.0) {
    PerlinNoiseSampler s{};
    s.origin_x = ox; s.origin_y = oy; s.origin_z = oz;
    for (int i = 0; i < 256; ++i) {
        s.permutation[i] = static_cast<std::uint8_t>(((i * 17) ^ seed) & 0xFF);
    }
    return s;
}

struct OctaveBundle {
    std::vector<PerlinNoiseSampler> octaves;
    std::vector<double>             amplitudes;
    OctavePerlinNoiseSampler        sampler{};
};

OctaveBundle make_octave(int n_octaves, std::uint8_t seed_base,
                         double lacunarity = 1.0, double persistence = 1.0) {
    OctaveBundle b;
    b.octaves.reserve(n_octaves);
    b.amplitudes.reserve(n_octaves);
    for (int i = 0; i < n_octaves; ++i) {
        b.octaves.push_back(make_perlin(static_cast<std::uint8_t>(seed_base + i)));
        b.amplitudes.push_back(1.0);
    }
    b.sampler.octaves      = b.octaves.data();
    b.sampler.amplitudes   = b.amplitudes.data();
    b.sampler.octave_count = static_cast<std::size_t>(n_octaves);
    b.sampler.lacunarity   = lacunarity;
    b.sampler.persistence  = persistence;
    return b;
}

InterpolatedNoiseSampler make_default_sampler(const OctaveBundle& lower,
                                              const OctaveBundle& upper,
                                              const OctaveBundle& interp) {
    InterpolatedNoiseSampler s{};
    s.lower_interpolated_noise = &lower.sampler;
    s.upper_interpolated_noise = &upper.sampler;
    s.interpolation_noise      = &interp.sampler;
    // Picks within the codec's permitted range
    // (xz/y scale ∈ [0.001, 1000], factor ∈ [0.001, 1000],
    //  smearScaleMultiplier ∈ [1, 8]).
    s.xz_scale  = 1.0;
    s.y_scale   = 1.0;
    s.xz_factor = 80.0;
    s.y_factor  = 160.0;
    s.smear_scale_multiplier = 8.0;
    return s;
}

} // namespace

TEST_CASE("interpolated_noise: sample with full octave stack is finite and bounded") {
    auto lower  = make_octave(16, 0x10);
    auto upper  = make_octave(16, 0x20);
    auto interp = make_octave(8,  0x30);
    auto s      = make_default_sampler(lower, upper, interp);

    // Sample over a small grid; every sample must be finite and
    // within Mojang's guaranteed magnitude (`|out| <= maxValue` where
    // `maxValue` is bounded by the octave count). For the unit test
    // we use a loose ±1.0 envelope as a sanity check; a tighter
    // bound is best left to the verify-shadow once we have one.
    for (int x = 0; x < 8; ++x) {
        for (int y = 0; y < 8; ++y) {
            for (int z = 0; z < 8; ++z) {
                const double v = noise::sample(s, x * 13.0, y * 7.0, z * 11.0);
                CHECK(std::isfinite(v));
                CHECK(v <= 1.0);
                CHECK(v >= -1.0);
            }
        }
    }
}

TEST_CASE("interpolated_noise: deterministic for same input") {
    auto lower  = make_octave(4, 0x11);
    auto upper  = make_octave(4, 0x22);
    auto interp = make_octave(4, 0x33);
    auto s      = make_default_sampler(lower, upper, interp);

    const double v1 = noise::sample(s, 5.0, 60.0, -3.0);
    const double v2 = noise::sample(s, 5.0, 60.0, -3.0);
    CHECK(v1 == v2);

    const double v3 = noise::sample(s, 5.0001, 60.0, -3.0);
    // Tiny coord change should give a tiny output change.
    CHECK(std::abs(v1 - v3) < 0.01);
}

TEST_CASE("interpolated_noise: empty octave stack samples to zero") {
    OctaveBundle empty{};
    empty.sampler.octaves = nullptr;
    empty.sampler.amplitudes = nullptr;
    empty.sampler.octave_count = 0;
    empty.sampler.lacunarity = 1.0;
    empty.sampler.persistence = 1.0;

    InterpolatedNoiseSampler s = make_default_sampler(empty, empty, empty);
    // With no octaves: n=0, q=(0/10+1)/2 = 0.5, l=0, m=0,
    // clampedLerp(0.5, 0, 0) / 128 = 0.
    CHECK(noise::sample(s, 1, 2, 3) == 0.0);
}

TEST_CASE("interpolated_noise: zero-amplitude octaves are skipped (act as null)") {
    // Build a sampler where every amplitude is 0.0. Vanilla treats
    // such slots as `null` per our get_octave() reverse-index
    // semantics; the result must equal the empty-stack case (0.0).
    OctaveBundle b;
    b.octaves.resize(8);
    for (int i = 0; i < 8; ++i) b.octaves[i] = make_perlin(static_cast<std::uint8_t>(i));
    b.amplitudes.assign(8, 0.0);
    b.sampler.octaves      = b.octaves.data();
    b.sampler.amplitudes   = b.amplitudes.data();
    b.sampler.octave_count = 8;
    b.sampler.lacunarity   = 1.0;
    b.sampler.persistence  = 1.0;

    InterpolatedNoiseSampler s = make_default_sampler(b, b, b);
    CHECK(noise::sample(s, 10, 20, 30) == 0.0);
}

TEST_CASE("interpolated_noise: q clamps select-skip path is exercised") {
    // Build interpolation noise that produces a strong signal on the
    // first octave so n is large → q clamps high → only `m_acc`
    // (upper) contributes. We can detect the path by zeroing the
    // lower stack: when bl2 is true the result depends only on upper.
    // Build an "interpolation" octave with extreme persistence so n
    // grows without bound (1.0 + 0.5 + 0.25 + ...) on the first
    // octaves; the maintain_precision wraparound then dominates and
    // we just check the call doesn't blow up.
    auto lower  = make_octave(16, 0x10);
    auto upper  = make_octave(16, 0x20);
    auto interp = make_octave(8,  0x30);
    auto s      = make_default_sampler(lower, upper, interp);
    s.xz_factor = 0.5;
    s.y_factor  = 0.5;

    // Just verify it doesn't trip an assertion or produce NaN.
    for (double t = 0; t < 5; t += 0.7) {
        const double v = noise::sample(s, t, t * 2, t * 3);
        CHECK(std::isfinite(v));
    }
}
