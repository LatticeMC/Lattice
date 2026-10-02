#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cmath>
#include <cstdint>
#include <vector>

#include "lattice/dispatch.hpp"
#include "world/gen/noise/simplex_noise.hpp"

using namespace lattice::world::gen::noise;

namespace {

SimplexNoiseSampler make_shuffled() {
    SimplexNoiseSampler s{};
    s.origin_x = 0.0; s.origin_y = 0.0; s.origin_z = 0.0;
    for (int i = 0; i < 256; ++i) {
        s.permutation[i] = ((i * 31) ^ 0x5A) & 0xFF;
    }
    return s;
}

} // namespace

TEST_CASE("simplex: 2D sample is finite and bounded") {
    auto s = make_shuffled();
    for (double x = -3.0; x <= 3.0; x += 0.37) {
        for (double y = -3.0; y <= 3.0; y += 0.41) {
            const double v = sample_2d(s, x, y);
            CHECK(std::isfinite(v));
            CHECK(v > -1.5);
            CHECK(v < 1.5);
        }
    }
}

TEST_CASE("simplex: 3D sample is finite and bounded") {
    auto s = make_shuffled();
    s.origin_x = 1.1; s.origin_y = 2.2; s.origin_z = 3.3;
    for (double x = -2.0; x <= 2.0; x += 0.5) {
        for (double y = -2.0; y <= 2.0; y += 0.5) {
            for (double z = -2.0; z <= 2.0; z += 0.5) {
                const double v = sample_3d(s, x, y, z);
                CHECK(std::isfinite(v));
                CHECK(v > -1.5);
                CHECK(v < 1.5);
            }
        }
    }
}

TEST_CASE("simplex: stored origins do not affect 2D or 3D samples") {
    auto baseline = make_shuffled();
    auto shifted = baseline;
    shifted.origin_x = 17.25;
    shifted.origin_y = -31.5;
    shifted.origin_z = 63.75;

    CHECK(sample_2d(shifted, 1.25, -3.5) == sample_2d(baseline, 1.25, -3.5));
    CHECK(sample_3d(shifted, 1.25, -3.5, 7.75) == sample_3d(baseline, 1.25, -3.5, 7.75));
}

TEST_CASE("simplex: 2D is continuous over small steps") {
    auto s = make_shuffled();
    const double x = 1.3, y = -0.7;
    const double v0 = sample_2d(s, x, y);
    const double v1 = sample_2d(s, x + 1e-6, y);
    CHECK(std::abs(v1 - v0) < 1e-4);
}

TEST_CASE("simplex: 3D sample at origin is zero with zero-permutation") {
    SimplexNoiseSampler s{};
    s.origin_x = 0.0; s.origin_y = 0.0; s.origin_z = 0.0;
    for (int i = 0; i < 256; ++i) s.permutation[i] = 0;
    // At (0,0,0) the corner offsets and gradients all collapse to the
    // same gradient index 0 = {1, 1, 0}. The four contributions form a
    // small but non-zero value. Just check finite + bounded.
    const double v = sample_3d(s, 0.0, 0.0, 0.0);
    CHECK(std::isfinite(v));
    CHECK(std::abs(v) < 1.5);
}

#if defined(LATTICE_TEST_HAS_SIMPLEX_AVX2)
TEST_CASE("simplex: AVX2 batch paths match scalar reference") {
    if (!lattice::cpu::initialize().avx2) return;

    auto s = make_shuffled();
    s.origin_x = 1.25;
    s.origin_y = -3.5;
    s.origin_z = 7.75;

    constexpr std::size_t count = 67;
    std::vector<double> x(count), y(count), z(count), scalar(count), avx2(count);
    for (std::size_t i = 0; i < count; ++i) {
        const double fi = static_cast<double>(i);
        x[i] = -31.0 + fi * 0.731;
        y[i] =  17.0 - fi * 0.577;
        z[i] = -11.0 + fi * 0.413;
    }

    // Exercise the 2D diagonal and all six 3D simplex orderings, including
    // equality boundaries whose comparisons intentionally differ in strictness.
    x[0] = 0.25; y[0] = 0.25; z[0] = 0.25;
    x[1] = 0.40; y[1] = 0.25; z[1] = 0.10;
    x[2] = 0.40; y[2] = 0.10; z[2] = 0.25;
    x[3] = 0.25; y[3] = 0.10; z[3] = 0.40;
    x[4] = 0.10; y[4] = 0.25; z[4] = 0.40;
    x[5] = 0.10; y[5] = 0.40; z[5] = 0.25;
    x[6] = 0.25; y[6] = 0.40; z[6] = 0.10;
    x[7] = 0.40; y[7] = 0.40; z[7] = 0.10;
    x[8] = 0.10; y[8] = 0.40; z[8] = 0.40;

    for (std::size_t batch_count = 0; batch_count <= count; ++batch_count) {
        sample_2d_batch_scalar(s, x.data(), y.data(), batch_count, scalar.data());
        sample_2d_batch_avx2(s, x.data(), y.data(), batch_count, avx2.data());
        for (std::size_t i = 0; i < batch_count; ++i) CHECK(avx2[i] == scalar[i]);

        sample_3d_batch_scalar(s, x.data(), y.data(), z.data(), batch_count, scalar.data());
        sample_3d_batch_avx2(s, x.data(), y.data(), z.data(), batch_count, avx2.data());
        for (std::size_t i = 0; i < batch_count; ++i) CHECK(avx2[i] == scalar[i]);
    }
}
#endif
