#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cmath>
#include <cstdint>

#include "world/gen/noise/double_perlin_noise.hpp"

using namespace lattice::world::gen::noise;

namespace {

PerlinNoiseSampler oct(std::uint8_t seed, double ox = 0.0, double oy = 0.0, double oz = 0.0) {
    PerlinNoiseSampler s{};
    s.origin_x = ox; s.origin_y = oy; s.origin_z = oz;
    for (int i = 0; i < 256; ++i)
        s.permutation[i] = static_cast<std::uint8_t>(((i * 17) ^ seed) & 0xFF);
    return s;
}

} // namespace

TEST_CASE("double_perlin: zero amplitude returns 0") {
    PerlinNoiseSampler a = oct(0x01);
    PerlinNoiseSampler b = oct(0x02);
    double amps[1] = {1.0};
    DoublePerlinNoiseSampler s{};
    s.first.octaves = &a; s.first.amplitudes = amps; s.first.octave_count = 1;
    s.first.lacunarity = 1.0; s.first.persistence = 1.0;
    s.second.octaves = &b; s.second.amplitudes = amps; s.second.octave_count = 1;
    s.second.lacunarity = 1.0; s.second.persistence = 1.0;
    s.amplitude = 0.0;
    CHECK(sample(s, 1.0, 2.0, 3.0) == 0.0);
}

TEST_CASE("double_perlin: identity amplitude sums two octave samplers") {
    PerlinNoiseSampler a = oct(0x11);
    PerlinNoiseSampler b = oct(0x22);
    double amps[1] = {1.0};
    DoublePerlinNoiseSampler s{};
    s.first.octaves = &a; s.first.amplitudes = amps; s.first.octave_count = 1;
    s.first.lacunarity = 1.0; s.first.persistence = 1.0;
    s.second.octaves = &b; s.second.amplitudes = amps; s.second.octave_count = 1;
    s.second.lacunarity = 1.0; s.second.persistence = 1.0;
    s.amplitude = 1.0;

    const double x = 0.3, y = 0.7, z = -0.5;
    const double v_actual = sample(s, x, y, z);
    const double v_expected = sample(s.first, x, y, z)
                            + sample(s.second, x * kDomainScale,
                                     y * kDomainScale, z * kDomainScale);
    CHECK(v_actual == doctest::Approx(v_expected).epsilon(1e-15));
}

TEST_CASE("double_perlin: finite + bounded over a small grid") {
    PerlinNoiseSampler a = oct(0xAB, 1.1, 2.2, 3.3);
    PerlinNoiseSampler b = oct(0xCD, 4.4, 5.5, 6.6);
    double amps[1] = {1.0};
    DoublePerlinNoiseSampler s{};
    s.first.octaves = &a; s.first.amplitudes = amps; s.first.octave_count = 1;
    s.first.lacunarity = 1.0; s.first.persistence = 1.0;
    s.second.octaves = &b; s.second.amplitudes = amps; s.second.octave_count = 1;
    s.second.lacunarity = 1.0; s.second.persistence = 1.0;
    s.amplitude = create_amplitude(2);

    for (double x = -2; x <= 2; x += 0.4) {
        for (double y = -2; y <= 2; y += 0.4) {
            for (double z = -2; z <= 2; z += 0.4) {
                const double v = sample(s, x, y, z);
                CHECK(std::isfinite(v));
                CHECK(std::abs(v) < 1.0);
            }
        }
    }
}

TEST_CASE("double_perlin: create_amplitude returns octaves/6") {
    CHECK(create_amplitude(0) == 0.0);
    CHECK(create_amplitude(6) == doctest::Approx(1.0).epsilon(1e-15));
    CHECK(create_amplitude(3) == doctest::Approx(0.5).epsilon(1e-15));
}
