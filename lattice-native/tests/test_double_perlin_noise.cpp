#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <bit>
#include <cmath>
#include <cstdint>
#include <vector>

#include "lattice/dispatch.hpp"
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

#if defined(LATTICE_TEST_HAS_DOUBLE_PERLIN_AVX512)
TEST_CASE("double_perlin: tests request AVX-512 before feature initialization") {
    CHECK(lattice::cpu::configure_requested_tier("avx512"));
}

bool same_bits(double left, double right) {
    return std::bit_cast<std::uint64_t>(left) == std::bit_cast<std::uint64_t>(right);
}
#endif

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

#if defined(LATTICE_TEST_HAS_DOUBLE_PERLIN_AVX2)
TEST_CASE("double_perlin: AVX2 batch paths match scalar reference") {
    if (!lattice::cpu::initialize().avx2) return;

    PerlinNoiseSampler a = oct(0x7B, 1.1, -2.2, 3.3);
    PerlinNoiseSampler b = oct(0xC4, -4.4, 5.5, -6.6);
    double amps[1] = {1.0};
    DoublePerlinNoiseSampler s{};
    s.first.octaves = &a; s.first.amplitudes = amps; s.first.octave_count = 1;
    s.first.lacunarity = 1.0; s.first.persistence = 1.0;
    s.second.octaves = &b; s.second.amplitudes = amps; s.second.octave_count = 1;
    s.second.lacunarity = 1.0; s.second.persistence = 1.0;
    s.amplitude = create_amplitude(2);

    constexpr std::size_t count = 21;
    std::vector<double> x(count), y(count), z(count), scalar(count), avx2(count);
    for (std::size_t i = 0; i < count; ++i) {
        const double fi = static_cast<double>(i);
        x[i] = -9.0 + fi * 0.37;
        y[i] =  5.0 - fi * 0.41;
        z[i] = -3.0 + fi * 0.43;
    }

    sample_batch_scalar(s, x.data(), y.data(), z.data(), count, scalar.data());
    sample_batch_avx2(s, x.data(), y.data(), z.data(), count, avx2.data());
    for (std::size_t i = 0; i < count; ++i) CHECK(avx2[i] == scalar[i]);

    sample_y_column_scalar(s, 3.25, -8.5, 6.75, 0.125, count, scalar.data());
    sample_y_column_avx2(s, 3.25, -8.5, 6.75, 0.125, count, avx2.data());
    for (std::size_t i = 0; i < count; ++i) CHECK(avx2[i] == scalar[i]);
}
#endif

#if defined(LATTICE_TEST_HAS_DOUBLE_PERLIN_AVX512)
TEST_CASE("double_perlin: AVX-512 paths match scalar reference") {
    const auto& cpu = lattice::cpu::initialize();
    if (!cpu.avx512f || !cpu.avx512dq || !cpu.avx512vl) return;

    PerlinNoiseSampler a = oct(0x7B, 1.1, -2.2, 3.3);
    PerlinNoiseSampler b = oct(0xC4, -4.4, 5.5, -6.6);
    double amps[1] = {1.0};
    DoublePerlinNoiseSampler s{};
    s.first.octaves = &a; s.first.amplitudes = amps; s.first.octave_count = 1;
    s.first.lacunarity = 1.0; s.first.persistence = 1.0;
    s.second.octaves = &b; s.second.amplitudes = amps; s.second.octave_count = 1;
    s.second.lacunarity = 1.0; s.second.persistence = 1.0;
    s.amplitude = create_amplitude(2);

    constexpr std::size_t count = 137;
    std::vector<double> x(count), y(count), z(count), scalar(count), avx512(count), dispatched(count);
    for (std::size_t i = 0; i < count; ++i) {
        const double fi = static_cast<double>(i);
        x[i] = -9.0 + fi * 0.37;
        y[i] = 5.0 - fi * 0.41;
        z[i] = -3.0 + fi * 0.43;
    }

    sample_batch_scalar(s, x.data(), y.data(), z.data(), count, scalar.data());
    sample_batch_avx512(s, x.data(), y.data(), z.data(), count, avx512.data());
    sample_batch(s, x.data(), y.data(), z.data(), count, dispatched.data());
    for (std::size_t i = 0; i < count; ++i) {
        CHECK(same_bits(avx512[i], scalar[i]));
        CHECK(same_bits(dispatched[i], scalar[i]));
    }

    sample_y_column_scalar(s, 3.25, -8.5, 6.75, 0.125, count, scalar.data());
    sample_y_column_avx512(s, 3.25, -8.5, 6.75, 0.125, count, avx512.data());
    sample_y_column(s, 3.25, -8.5, 6.75, 0.125, count, dispatched.data());
    for (std::size_t i = 0; i < count; ++i) {
        CHECK(same_bits(avx512[i], scalar[i]));
        CHECK(same_bits(dispatched[i], scalar[i]));
    }
}
#endif
