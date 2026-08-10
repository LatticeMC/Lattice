#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cmath>
#include <cstdint>
#include <vector>

#include "lattice/dispatch.hpp"
#include "world/gen/noise/perlin_noise.hpp"

using namespace lattice::world::gen::noise;

namespace {

// A deterministic test permutation: identity {0, 1, ..., 255}.
// Trivial but lets us verify sample() returns finite values consistent
// with the formula's algebraic structure.
PerlinNoiseSampler make_identity_sampler() {
    PerlinNoiseSampler s{};
    s.origin_x = 0.0;
    s.origin_y = 0.0;
    s.origin_z = 0.0;
    for (int i = 0; i < 256; ++i) s.permutation[i] = static_cast<std::uint8_t>(i);
    return s;
}

// A more "shuffled" permutation: bit-reversed indices. Still deterministic
// but enough variety that adjacent grad() calls don't collapse to identical
// vectors.
PerlinNoiseSampler make_shuffled_sampler() {
    PerlinNoiseSampler s{};
    s.origin_x = 12.34;
    s.origin_y = 56.78;
    s.origin_z = 90.12;
    for (int i = 0; i < 256; ++i) {
        std::uint8_t b = static_cast<std::uint8_t>(i);
        // Bit-reverse 8 bits.
        b = (b & 0xF0) >> 4 | (b & 0x0F) << 4;
        b = (b & 0xCC) >> 2 | (b & 0x33) << 2;
        b = (b & 0xAA) >> 1 | (b & 0x55) << 1;
        s.permutation[i] = b;
    }
    return s;
}

} // namespace

TEST_CASE("perlin: sample at lattice origin returns zero") {
    // At any integer-lattice point (x, y, z) ∈ ℤ³ (before origin
    // offset), all fractional parts are zero, so smoothStep(0) = 0,
    // which means u = v = w = 0; the value collapses to grad(perm[...],
    // 0, 0, 0). grad(h, 0, 0, 0) is 0 for every h. So the noise must
    // be exactly 0 at every lattice point of the un-shifted grid.
    auto s = make_identity_sampler();
    s.origin_x = s.origin_y = s.origin_z = 0.0;
    CHECK(sample(s, 0.0, 0.0, 0.0) == 0.0);
    CHECK(sample(s, 1.0, 0.0, 0.0) == 0.0);
    CHECK(sample(s, 5.0, -7.0, 12.0) == 0.0);
}

TEST_CASE("perlin: sample is finite and bounded") {
    auto s = make_shuffled_sampler();
    // Sample a small 3D grid and check every value is in (-1.5, 1.5).
    // Theoretical bound for 3D Perlin is sqrt(2)/2 ≈ 0.707, with some
    // numerical slack we use ±1.5.
    for (int x = -3; x <= 3; ++x) {
        for (int y = -3; y <= 3; ++y) {
            for (int z = -3; z <= 3; ++z) {
                double v = sample(s, x * 0.37, y * 0.41, z * 0.43);
                CHECK(std::isfinite(v));
                CHECK(v > -1.5);
                CHECK(v < 1.5);
            }
        }
    }
}

TEST_CASE("perlin: sample is continuous (small step → small delta)") {
    auto s = make_shuffled_sampler();
    const double x0 = 1.7, y0 = 2.3, z0 = -0.5;
    const double v0 = sample(s, x0, y0, z0);
    const double v1 = sample(s, x0 + 1e-6, y0, z0);
    // Smoothstep + grad linearity ⇒ Lipschitz over small deltas.
    // A 1e-6 step shouldn't change the result by more than ~1e-5
    // (assuming gradient magnitude ≲ 10).
    CHECK(std::abs(v1 - v0) < 1e-4);
}

TEST_CASE("perlin: y_scaled with yScale=0 reduces to plain sample") {
    auto s = make_shuffled_sampler();
    const double x = 1.5, y = 3.7, z = -2.1;
    CHECK(sample_y_scaled(s, x, y, z, 0.0, 10.0) == sample(s, x, y, z));
}

TEST_CASE("perlin: y_scaled with yMax >= 1 collapses offset to 0 (matches plain sample)") {
    // Mojang's algorithm caps `r = min(p, yMax)` where `p` is the
    // fractional part of (y + originY) ∈ [0, 1). When yMax >= 1,
    // `yMax < p` is always false → r = p. Then
    // s = floor(p / yScale + 1e-7) * yScale.
    //
    // For yScale = 1, that's floor(p + 1e-7) = 0 (since p < 1),
    // s = 0. Result = sample at (l, m, n, o, p, q, fadeY=p), which
    // is identical to the unscaled 3-arg sample.
    auto s = make_shuffled_sampler();
    for (double y : {-3.5, 0.0, 1.7, 10.0, 100.5}) {
        const double a = sample_y_scaled(s, 0.4, y, 0.6, 1.0, 5.0);
        const double b = sample(s, 0.4, y, 0.6);
        CHECK(a == b);
    }
}

TEST_CASE("perlin: y_scaled negative yMax skips the cap branch") {
    // When yMax < 0 the (yMax >= 0 && yMax < p) condition is false,
    // so r = p — same as the previous test. The sampler should
    // collapse to plain sample for the same yScale=1 reason.
    auto s = make_shuffled_sampler();
    for (double y : {-3.5, 0.0, 1.7, 10.0}) {
        const double a = sample_y_scaled(s, 0.4, y, 0.6, 1.0, -1.0);
        const double b = sample(s, 0.4, y, 0.6);
        CHECK(a == b);
    }
}

TEST_CASE("perlin: y_scaled small yScale shifts the lattice-Y residual") {
    // For yScale < 1 the ceil-equivalent floor() finds a nonzero
    // multiple of yScale to subtract. We verify the result is
    // finite and differs from the unscaled sample for a typical
    // case (proves the path is exercised).
    auto s = make_shuffled_sampler();
    const double v_scaled = sample_y_scaled(s, 0.4, 0.6, 0.7, 0.1, 10.0);
    const double v_plain  = sample(s, 0.4, 0.6, 0.7);
    CHECK(std::isfinite(v_scaled));
    // Lattice-Y residual is altered → sample value should change in
    // general. We accept very rare numerical coincidence by checking
    // a few different y inputs.
    bool any_diff = (v_scaled != v_plain);
    for (double y : {0.55, 0.65, 0.75}) {
        if (sample_y_scaled(s, 0.4, y, 0.7, 0.1, 10.0)
            != sample(s, 0.4, y, 0.7)) {
            any_diff = true;
        }
    }
    CHECK(any_diff);
}

TEST_CASE("perlin: y_scaled array yMax path matches scalar samples") {
    auto s = make_shuffled_sampler();
    constexpr std::size_t count = 17;
    std::vector<double> x_values(count), y(count), z_values(count), y_max(count), batch(count);
    const double x = 2.25;
    const double z = -5.75;
    const double y_scale = 0.125;
    for (std::size_t i = 0; i < count; ++i) {
        const double fi = static_cast<double>(i);
        x_values[i] = x + fi * 0.0625;
        y[i] = -4.5 + fi * 0.375;
        z_values[i] = z - fi * 0.03125;
        y_max[i] = (i % 3 == 0) ? -1.0 : 0.05 + fi * 0.03125;
    }

    sample_y_scaled_batch_ymax(s, x_values.data(), y.data(), z_values.data(), y_scale, y_max.data(), count, batch.data());
    for (std::size_t i = 0; i < count; ++i) {
        CHECK(batch[i] == sample_y_scaled(s, x_values[i], y[i], z_values[i], y_scale, y_max[i]));
    }

    sample_y_scaled_array_ymax(s, x, y.data(), z, y_scale, y_max.data(), count, batch.data());
    for (std::size_t i = 0; i < count; ++i) {
        CHECK(batch[i] == sample_y_scaled(s, x, y[i], z, y_scale, y_max[i]));
    }
}

TEST_CASE("perlin: sample_derivative returns finite gradient") {
    auto s = make_shuffled_sampler();
    double deriv[3] = {0, 0, 0};
    const double v = sample_derivative(s, 0.5, 0.7, 0.3, deriv);
    CHECK(std::isfinite(v));
    CHECK(std::isfinite(deriv[0]));
    CHECK(std::isfinite(deriv[1]));
    CHECK(std::isfinite(deriv[2]));
}

#if defined(LATTICE_TEST_HAS_PERLIN_AVX2)
TEST_CASE("perlin: AVX2 batch paths match scalar reference") {
    if (!lattice::cpu::initialize().avx2) return;

    auto s = make_shuffled_sampler();
    constexpr std::size_t count = 17;
    std::vector<double> x(count), y(count), z(count), scalar(count), avx2(count);
    for (std::size_t i = 0; i < count; ++i) {
        const double fi = static_cast<double>(i);
        x[i] = -3.25 + fi * 0.37;
        y[i] =  7.50 - fi * 0.41;
        z[i] = -1.75 + fi * 0.43;
    }

    sample_batch_scalar(s, x.data(), y.data(), z.data(), count, scalar.data());
    sample_batch_avx2(s, x.data(), y.data(), z.data(), count, avx2.data());
    for (std::size_t i = 0; i < count; ++i) CHECK(avx2[i] == scalar[i]);

    sample_y_column_scalar(s, 1.25, -4.5, 8.75, 0.125, count, scalar.data());
    sample_y_column_avx2(s, 1.25, -4.5, 8.75, 0.125, count, avx2.data());
    for (std::size_t i = 0; i < count; ++i) CHECK(avx2[i] == scalar[i]);

    sample_y_scaled_batch_scalar(s, x.data(), y.data(), z.data(), 0.125, 0.75, count, scalar.data());
    sample_y_scaled_batch_avx2(s, x.data(), y.data(), z.data(), 0.125, 0.75, count, avx2.data());
    for (std::size_t i = 0; i < count; ++i) CHECK(avx2[i] == scalar[i]);

    std::vector<double> y_max(count);
    for (std::size_t i = 0; i < count; ++i) y_max[i] = (i % 4 == 0) ? -1.0 : 0.0625 + static_cast<double>(i) * 0.03125;
    sample_y_scaled_batch_ymax_scalar(s, x.data(), y.data(), z.data(), 0.125, y_max.data(), count, scalar.data());
    sample_y_scaled_batch_ymax_avx2(s, x.data(), y.data(), z.data(), 0.125, y_max.data(), count, avx2.data());
    for (std::size_t i = 0; i < count; ++i) CHECK(avx2[i] == scalar[i]);

    sample_y_scaled_array_ymax_scalar(s, 1.25, y.data(), 8.75, 0.125, y_max.data(), count, scalar.data());
    sample_y_scaled_array_ymax_avx2(s, 1.25, y.data(), 8.75, 0.125, y_max.data(), count, avx2.data());
    for (std::size_t i = 0; i < count; ++i) CHECK(avx2[i] == scalar[i]);
}
#endif

#if defined(LATTICE_TEST_HAS_PERLIN_AVX512)
TEST_CASE("perlin: AVX-512 eight-lane paths match scalar reference") {
    const auto& cpu = lattice::cpu::initialize();
    if (!cpu.avx512f || !cpu.avx512dq) return;

    auto s = make_shuffled_sampler();
    constexpr std::size_t count = 21; // 16 vector lanes plus a five-element tail.
    std::vector<double> x(count), y(count), z(count), y_max(count), scalar(count), avx512(count);
    for (std::size_t i = 0; i < count; ++i) {
        const double fi = static_cast<double>(i);
        x[i] = -17.25 + fi * 0.37;
        y[i] = 9.50 - fi * 0.41;
        z[i] = -6.75 + fi * 0.43;
        y_max[i] = (i % 4 == 0) ? -1.0 : 0.0625 + fi * 0.03125;
    }

    sample_batch_scalar(s, x.data(), y.data(), z.data(), count, scalar.data());
    sample_batch_avx512(s, x.data(), y.data(), z.data(), count, avx512.data());
    for (std::size_t i = 0; i < count; ++i) CHECK(avx512[i] == scalar[i]);

    sample_y_column_scalar(s, 1.25, -4.5, 8.75, 0.125, count, scalar.data());
    sample_y_column_avx512(s, 1.25, -4.5, 8.75, 0.125, count, avx512.data());
    for (std::size_t i = 0; i < count; ++i) CHECK(avx512[i] == scalar[i]);

    sample_y_scaled_batch_ymax_scalar(s, x.data(), y.data(), z.data(), 0.125, y_max.data(), count, scalar.data());
    sample_y_scaled_batch_ymax_avx512(s, x.data(), y.data(), z.data(), 0.125, y_max.data(), count, avx512.data());
    for (std::size_t i = 0; i < count; ++i) CHECK(avx512[i] == scalar[i]);
}
#endif
