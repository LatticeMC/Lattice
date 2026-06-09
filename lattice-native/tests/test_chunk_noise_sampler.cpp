#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cstdint>

#include "world/gen/chunknoise/chunk_noise_sampler.hpp"
#include "world/gen/densityfunction/density_function.hpp"
#include "world/gen/noise/double_perlin_noise.hpp"
#include "world/gen/noise/perlin_noise.hpp"

using namespace lattice::world::gen;
using chunknoise::Channel;
using chunknoise::ChunkNoiseSampler;
using chunknoise::NoiseRouter;
using densityfunction::Node;
using densityfunction::NodeArena;
using densityfunction::NodeKind;
using densityfunction::NodeRef;

namespace {

NodeArena make_constant(double v) {
    NodeArena a;
    Node n{};
    n.kind = NodeKind::kConstant;
    n.d0   = v;
    a.root = a.push(n);
    return a;
}

/// Single Cache2D wrapping a constant. Cheap way to verify the
/// sampler's per-channel cache is wired up: after the first sample at
/// (x, z), mutating the underlying constant should NOT change the
/// cached return value at (x, z) — but a fresh (x, z) should re-evaluate.
NodeArena make_cache2d_of_constant(double initial_value) {
    NodeArena a;
    Node c{}; c.kind = NodeKind::kConstant; c.d0 = initial_value;
    NodeRef rc = a.push(c);
    Node cache{}; cache.kind = NodeKind::kCache2D; cache.a = rc;
    a.root = a.push(cache);
    return a;
}

} // namespace

TEST_CASE("chunknoise: empty router → all samples zero") {
    ChunkNoiseSampler s{};
    s.prepare_cache();
    CHECK(s.sample_final_density(0, 0, 0, 0, 0) == 0.0);
    for (std::uint8_t i = 0; i < static_cast<std::uint8_t>(Channel::kCount); ++i) {
        CHECK(s.sample(static_cast<Channel>(i), 0, 0, 0, 0, 0) == 0.0);
    }
}

TEST_CASE("chunknoise: routes channel to its arena") {
    NodeArena temp     = make_constant(1.0);
    NodeArena vege     = make_constant(2.0);
    NodeArena finalDen = make_constant(3.0);

    ChunkNoiseSampler s{};
    s.router.temperature   = &temp;
    s.router.vegetation    = &vege;
    s.router.final_density = &finalDen;
    s.prepare_cache();

    CHECK(s.sample(Channel::kTemperature,  10, 20, 30, 1, 1) == 1.0);
    CHECK(s.sample(Channel::kVegetation,   10, 20, 30, 1, 1) == 2.0);
    CHECK(s.sample(Channel::kFinalDensity, 10, 20, 30, 1, 1) == 3.0);
    CHECK(s.sample_final_density(10, 20, 30, 1, 1)         == 3.0);

    // Unrouted channel still returns 0.
    CHECK(s.sample(Channel::kBarrierNoise, 10, 20, 30, 1, 1) == 0.0);
}

TEST_CASE("chunknoise: sample with channel index out of range → 0") {
    ChunkNoiseSampler s{};
    s.prepare_cache();
    // C++ enum class is not bounds-checked, but our wrapper's sample()
    // guards via the kCount sentinel. Force-cast a too-large value.
    auto bogus = static_cast<Channel>(static_cast<std::uint8_t>(Channel::kCount));
    CHECK(s.sample(bogus, 0, 0, 0, 0, 0) == 0.0);
}

TEST_CASE("chunknoise: per-channel Cache2D actually caches") {
    NodeArena a = make_cache2d_of_constant(11.0);
    ChunkNoiseSampler s{};
    s.router.temperature = &a;
    s.prepare_cache();

    // Fill (x=5, z=7) at one y…
    CHECK(s.sample(Channel::kTemperature, 5.0, 0.0, 7.0, 0, 0) == 11.0);

    // …mutate the underlying constant. Cache2D keys on (x, z), so a
    // second sample at the same (x, z) should still return the cached
    // value, not the new constant. (This is how we verify the cache
    // is hooked up — without it the new value would leak through.)
    a.nodes[0].d0 = 999.0;
    CHECK(s.sample(Channel::kTemperature, 5.0, 100.0, 7.0, 0, 0) == 11.0);

    // Different (x, z) → re-evaluates → reads the new constant.
    CHECK(s.sample(Channel::kTemperature, 6.0, 0.0, 8.0, 0, 0) == 999.0);

    // clear_cache() should drop every entry; resampling at the
    // original (x, z) now picks up the mutated constant too.
    s.clear_cache();
    CHECK(s.sample(Channel::kTemperature, 5.0, 0.0, 7.0, 0, 0) == 999.0);
}

TEST_CASE("chunknoise: caches are independent per channel") {
    // Two separate arenas, each with their own slot 0 Cache2D. If the
    // sampler accidentally aliased their cache state, each arena's
    // first sample would taint the other's "first sample at (x, z)"
    // detection. We exploit that by storing distinct constants and
    // checking each channel returns its own.
    NodeArena temp = make_cache2d_of_constant(42.0);
    NodeArena vege = make_cache2d_of_constant(-7.0);

    ChunkNoiseSampler s{};
    s.router.temperature = &temp;
    s.router.vegetation  = &vege;
    s.prepare_cache();

    // Same (x, z) on both channels: would collide if sharing cache.
    CHECK(s.sample(Channel::kTemperature, 1.0, 0.0, 2.0, 0, 0) == 42.0);
    CHECK(s.sample(Channel::kVegetation,  1.0, 0.0, 2.0, 0, 0) == -7.0);

    // Mutate underlying constants; cached values stay.
    temp.nodes[0].d0 = 100.0;
    vege.nodes[0].d0 = 200.0;
    CHECK(s.sample(Channel::kTemperature, 1.0, 50.0, 2.0, 0, 0) == 42.0);
    CHECK(s.sample(Channel::kVegetation,  1.0, 50.0, 2.0, 0, 0) == -7.0);
}

TEST_CASE("chunknoise: prepare_cache resets dropped channels") {
    NodeArena a = make_cache2d_of_constant(5.0);
    ChunkNoiseSampler s{};
    s.router.temperature = &a;
    s.prepare_cache();

    CHECK(s.sample(Channel::kTemperature, 0, 0, 0, 0, 0) == 5.0);

    // Detach the channel; re-prepare; cache for that slot should
    // have been reset to empty. Sampling the now-null channel
    // returns 0 without crashing on the stale cache.
    s.router.temperature = nullptr;
    s.prepare_cache();
    CHECK(s.sample(Channel::kTemperature, 0, 0, 0, 0, 0) == 0.0);
}

TEST_CASE("chunknoise: clear_cache leaves caches resized but empty") {
    NodeArena a = make_cache2d_of_constant(13.0);
    ChunkNoiseSampler s{};
    s.router.depth = &a;
    s.prepare_cache();

    CHECK(s.sample(Channel::kDepth, 1.0, 0.0, 1.0, 0, 0) == 13.0);
    a.nodes[0].d0 = 17.0;
    // Pre-clear: cached.
    CHECK(s.sample(Channel::kDepth, 1.0, 0.0, 1.0, 0, 0) == 13.0);
    s.clear_cache();
    // Post-clear: re-evaluates.
    CHECK(s.sample(Channel::kDepth, 1.0, 0.0, 1.0, 0, 0) == 17.0);
}

TEST_CASE("chunknoise: exposes interpolator lifecycle per channel") {
    NodeArena a;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 7.0;
    NodeRef leaf_ref = a.push(leaf);
    Node interp{}; interp.kind = NodeKind::kInterpolated; interp.a = leaf_ref;
    a.root = a.push(interp);

    ChunkNoiseSampler s{};
    s.router.final_density = &a;
    s.prepare_cache();

    REQUIRE(s.num_interpolator_slots(Channel::kFinalDensity) == 1);
    s.prepare_interpolators(Channel::kFinalDensity, 1, 1);

    s.set_start_density(Channel::kFinalDensity, 0, 0, 0, 1.0);
    s.set_start_density(Channel::kFinalDensity, 0, 1, 0, 2.0);
    s.set_start_density(Channel::kFinalDensity, 0, 0, 1, 3.0);
    s.set_start_density(Channel::kFinalDensity, 0, 1, 1, 4.0);
    s.set_end_density(Channel::kFinalDensity, 0, 0, 0, 5.0);
    s.set_end_density(Channel::kFinalDensity, 0, 1, 0, 6.0);
    s.set_end_density(Channel::kFinalDensity, 0, 0, 1, 7.0);
    s.set_end_density(Channel::kFinalDensity, 0, 1, 1, 8.0);

    s.start_interpolation(Channel::kFinalDensity);
    s.on_sampled_cell_corners(Channel::kFinalDensity, 0, 0);
    s.interpolate_y(Channel::kFinalDensity, 0.0);
    s.interpolate_x(Channel::kFinalDensity, 0.0);
    s.interpolate_z(Channel::kFinalDensity, 0.0);
    CHECK(s.sample_final_density(0.0, 0.0, 0.0, 0, 0) == doctest::Approx(1.0).epsilon(1e-15));

    s.interpolate_y(Channel::kFinalDensity, 1.0);
    s.interpolate_x(Channel::kFinalDensity, 1.0);
    s.interpolate_z(Channel::kFinalDensity, 1.0);
    CHECK(s.sample_final_density(0.0, 0.0, 0.0, 0, 0) == doctest::Approx(8.0).epsilon(1e-15));

    s.swap_buffers(Channel::kFinalDensity);
    s.stop_interpolation(Channel::kFinalDensity);
    CHECK(s.sample_final_density(0.0, 0.0, 0.0, 0, 0) == doctest::Approx(7.0).epsilon(1e-15));
}

TEST_CASE("chunknoise: bulk density rows feed interpolators") {
    NodeArena a;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 2.0;
    NodeRef leaf_ref = a.push(leaf);
    Node interp{}; interp.kind = NodeKind::kInterpolated; interp.a = leaf_ref;
    a.root = a.push(interp);

    ChunkNoiseSampler s{};
    s.router.final_density = &a;
    s.prepare_cache();
    s.prepare_interpolators(Channel::kFinalDensity, 1, 1);

    const double start0[] = {1.0, 3.0};
    const double start1[] = {2.0, 4.0};
    const double end0[]   = {5.0, 7.0};
    const double end1[]   = {6.0, 8.0};
    s.set_start_density_row(Channel::kFinalDensity, 0, 0, start0);
    s.set_start_density_row(Channel::kFinalDensity, 0, 1, start1);
    s.set_end_density_row(Channel::kFinalDensity, 0, 0, end0);
    s.set_end_density_row(Channel::kFinalDensity, 0, 1, end1);

    s.start_interpolation(Channel::kFinalDensity);
    s.on_sampled_cell_corners(Channel::kFinalDensity, 0, 0);
    s.interpolate_y(Channel::kFinalDensity, 0.5);
    s.interpolate_x(Channel::kFinalDensity, 0.5);
    s.interpolate_z(Channel::kFinalDensity, 0.5);
    CHECK(s.sample_final_density(0.0, 0.0, 0.0, 0, 0) == doctest::Approx(4.5).epsilon(1e-15));

    s.advance_column(Channel::kFinalDensity);
}

TEST_CASE("chunknoise: can prefill a density column from interpolated inputs") {
    NodeArena a;
    Node grad{};
    grad.kind = NodeKind::kYClampedGradient;
    grad.i0 = 0;
    grad.i1 = 10;
    grad.d0 = 10.0;
    grad.d1 = 20.0;
    NodeRef grad_ref = a.push(grad);
    Node interp{}; interp.kind = NodeKind::kInterpolated; interp.a = grad_ref;
    a.root = a.push(interp);

    ChunkNoiseSampler s{};
    s.router.final_density = &a;
    s.prepare_cache();
    s.prepare_interpolators(Channel::kFinalDensity, 1, 1);
    s.fill_start_density_column(Channel::kFinalDensity,
                                0.0, 0.0,
                                0, 0,
                                0.0, 10.0,
                                1, 1);
    s.fill_end_density_column(Channel::kFinalDensity,
                              1.0, 0.0,
                              1, 0,
                              0.0, 10.0,
                              1, 1);

    s.start_interpolation(Channel::kFinalDensity);
    s.on_sampled_cell_corners(Channel::kFinalDensity, 0, 0);
    s.interpolate_y(Channel::kFinalDensity, 0.5);
    s.interpolate_x(Channel::kFinalDensity, 0.5);
    s.interpolate_z(Channel::kFinalDensity, 0.0);
    CHECK(s.sample_final_density(0.0, 0.0, 0.0, 0, 0) == doctest::Approx(15.0).epsilon(1e-15));
}

TEST_CASE("chunknoise: final_density column helpers prefill and advance") {
    NodeArena a;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 2.0;
    NodeRef leaf_ref = a.push(leaf);
    Node interp{}; interp.kind = NodeKind::kInterpolated; interp.a = leaf_ref;
    a.root = a.push(interp);

    ChunkNoiseSampler s{};
    s.router.final_density = &a;
    s.prepare_cache();
    s.prepare_interpolators(Channel::kFinalDensity, 1, 1);
    s.prime_final_density_columns(0.0, 1.0,
                                  0.0,
                                  0, 1,
                                  0,
                                  0.0, 1.0,
                                  1, 1);

    s.start_interpolation(Channel::kFinalDensity);
    s.on_sampled_cell_corners(Channel::kFinalDensity, 0, 0);
    s.interpolate_y(Channel::kFinalDensity, 0.0);
    s.interpolate_x(Channel::kFinalDensity, 0.0);
    s.interpolate_z(Channel::kFinalDensity, 0.0);
    CHECK(s.sample_final_density(0.0, 0.0, 0.0, 0, 0) == doctest::Approx(2.0).epsilon(1e-15));

    a.nodes[leaf_ref].d0 = 9.0;
    s.advance_final_density_column(2.0,
                                   0.0,
                                   2,
                                   0,
                                   0.0, 1.0,
                                   1, 1);
    s.on_sampled_cell_corners(Channel::kFinalDensity, 0, 0);
    s.interpolate_y(Channel::kFinalDensity, 0.0);
    s.interpolate_x(Channel::kFinalDensity, 0.0);
    s.interpolate_z(Channel::kFinalDensity, 0.0);
    CHECK(s.sample_final_density(0.0, 0.0, 0.0, 0, 0) == doctest::Approx(2.0).epsilon(1e-15));
    s.interpolate_x(Channel::kFinalDensity, 1.0);
    CHECK(s.sample_final_density(0.0, 0.0, 0.0, 0, 0) == doctest::Approx(9.0).epsilon(1e-15));
}

TEST_CASE("chunknoise: final_density cell grid helper samples one cell") {
    NodeArena a;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 5.0;
    NodeRef leaf_ref = a.push(leaf);
    Node interp{}; interp.kind = NodeKind::kInterpolated; interp.a = leaf_ref;
    a.root = a.push(interp);

    ChunkNoiseSampler s{};
    s.router.final_density = &a;
    s.prepare_cache();
    s.prepare_interpolators(Channel::kFinalDensity, 1, 1);
    s.prime_final_density_columns(0.0, 1.0,
                                  0.0,
                                  0, 1,
                                  0,
                                  0.0, 1.0,
                                  1, 1);
    s.start_interpolation(Channel::kFinalDensity);

    double out[8]{};
    s.sample_final_density_cell_grid(/*cellY=*/0, /*cellZ=*/0,
                                     /*x0=*/0.0, /*y0=*/0.0, /*z0=*/0.0,
                                     /*dx=*/1.0, /*dy=*/1.0, /*dz=*/1.0,
                                     /*cellX=*/0, /*cellZCoord=*/0,
                                     /*nx=*/2, /*ny=*/2, /*nz=*/2,
                                     out);
    for (double value : out) {
        CHECK(value == doctest::Approx(5.0).epsilon(1e-15));
    }
}

TEST_CASE("chunknoise: generic channel helpers support vein channels") {
    NodeArena a;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = -0.5;
    NodeRef leaf_ref = a.push(leaf);
    Node interp{}; interp.kind = NodeKind::kInterpolated; interp.a = leaf_ref;
    a.root = a.push(interp);

    ChunkNoiseSampler s{};
    s.router.vein_toggle = &a;
    s.prepare_cache();
    s.prepare_interpolators(Channel::kVeinToggle, 1, 1);
    s.prime_channel_columns(Channel::kVeinToggle,
                            0.0, 1.0,
                            0.0,
                            0, 1,
                            0,
                            0.0, 1.0,
                            1, 1);
    s.start_interpolation(Channel::kVeinToggle);

    double out[8]{};
    s.sample_cell_grid(Channel::kVeinToggle,
                       /*cellY=*/0, /*cellZ=*/0,
                       /*x0=*/0.0, /*y0=*/0.0, /*z0=*/0.0,
                       /*dx=*/1.0, /*dy=*/1.0, /*dz=*/1.0,
                       /*cellX=*/0, /*cellZCoord=*/0,
                       /*nx=*/2, /*ny=*/2, /*nz=*/2,
                       out);
    for (double value : out) {
        CHECK(value == doctest::Approx(-0.5).epsilon(1e-15));
    }

    a.nodes[leaf_ref].d0 = 0.75;
    s.advance_channel_column(Channel::kVeinToggle,
                             2.0,
                             0.0,
                             2,
                             0,
                             0.0, 1.0,
                             1, 1);
    s.on_sampled_cell_corners(Channel::kVeinToggle, 0, 0);
    s.interpolate_y(Channel::kVeinToggle, 0.0);
    s.interpolate_x(Channel::kVeinToggle, 1.0);
    s.interpolate_z(Channel::kVeinToggle, 0.0);
    CHECK(s.sample(Channel::kVeinToggle, 0.0, 0.0, 0.0, 0, 0) == doctest::Approx(0.75).epsilon(1e-15));
}
