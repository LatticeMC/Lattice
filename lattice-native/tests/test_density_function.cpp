#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cmath>
#include <cstdint>
#include <vector>

#include "lattice/dispatch.hpp"
#include "world/gen/densityfunction/density_function.hpp"
#include "world/gen/noise/double_perlin_noise.hpp"
#include "world/gen/noise/simplex_noise.hpp"

using namespace lattice::world::gen::densityfunction;
namespace noise = lattice::world::gen::noise;
using noise::PerlinNoiseSampler;
using noise::OctavePerlinNoiseSampler;
using noise::DoublePerlinNoiseSampler;
using noise::SimplexNoiseSampler;

namespace {

NodeArena make_constant(double v) {
    NodeArena a;
    Node n{};
    n.kind = NodeKind::kConstant;
    n.d0   = v;
    a.root = a.push(n);
    return a;
}

PerlinNoiseSampler oct(std::uint8_t seed) {
    PerlinNoiseSampler s{};
    for (int i = 0; i < 256; ++i)
        s.permutation[i] = static_cast<std::uint8_t>(((i * 23) ^ seed) & 0xFF);
    return s;
}

} // namespace

TEST_CASE("density: constant") {
    auto a = make_constant(42.0);
    CHECK(evaluate(a, {0, 0, 0}) == 42.0);
    CHECK(evaluate(a, {-100, 500, 1e6}) == 42.0);
}

TEST_CASE("density: add of two constants") {
    NodeArena a;
    Node c1{}; c1.kind = NodeKind::kConstant; c1.d0 = 3.0;
    Node c2{}; c2.kind = NodeKind::kConstant; c2.d0 = 4.0;
    Node add{}; add.kind = NodeKind::kAdd;
    add.a = a.push(c1);
    add.b = a.push(c2);
    a.root = a.push(add);
    CHECK(evaluate(a, {0, 0, 0}) == 7.0);
}

TEST_CASE("density: add same-ref fast path preserves result") {
    NodeArena a;
    Node c{}; c.kind = NodeKind::kConstant; c.d0 = 3.5;
    NodeRef rc = a.push(c);
    Node add{}; add.kind = NodeKind::kAdd; add.a = rc; add.b = rc;
    a.root = a.push(add);
    CHECK(evaluate(a, {0, 0, 0}) == 7.0);
}

TEST_CASE("density: nested unary chain (square of negative constant)") {
    NodeArena a;
    Node c{}; c.kind = NodeKind::kConstant; c.d0 = -5.0;
    Node sq{}; sq.kind = NodeKind::kSquare;
    sq.a = a.push(c);
    a.root = a.push(sq);
    CHECK(evaluate(a, {0, 0, 0}) == 25.0);
}

TEST_CASE("density: invert returns reciprocal") {
    NodeArena a;
    Node c{}; c.kind = NodeKind::kConstant; c.d0 = 4.0;
    Node inv{}; inv.kind = NodeKind::kInvert;
    inv.a = a.push(c);
    a.root = a.push(inv);
    CHECK(evaluate(a, {0, 0, 0}) == doctest::Approx(0.25).epsilon(1e-15));
}

TEST_CASE("density: min/max") {
    NodeArena a;
    Node c3{}; c3.kind = NodeKind::kConstant; c3.d0 = 3.0;
    Node c5{}; c5.kind = NodeKind::kConstant; c5.d0 = 5.0;
    Node mn{}; mn.kind = NodeKind::kMin;
    Node mx{}; mx.kind = NodeKind::kMax;
    NodeRef rc3 = a.push(c3);
    NodeRef rc5 = a.push(c5);
    mn.a = rc3; mn.b = rc5;
    mx.a = rc3; mx.b = rc5;
    NodeRef rmn = a.push(mn);
    NodeRef rmx = a.push(mx);
    a.root = rmn;
    CHECK(evaluate(a, {0, 0, 0}) == 3.0);
    a.root = rmx;
    CHECK(evaluate(a, {0, 0, 0}) == 5.0);
}

TEST_CASE("density: min/max same-ref fast path preserves result") {
    NodeArena a;
    Node c{}; c.kind = NodeKind::kConstant; c.d0 = -2.0;
    NodeRef rc = a.push(c);

    Node mn{}; mn.kind = NodeKind::kMin; mn.a = rc; mn.b = rc;
    a.root = a.push(mn);
    CHECK(evaluate(a, {0, 0, 0}) == -2.0);

    Node mx{}; mx.kind = NodeKind::kMax; mx.a = rc; mx.b = rc;
    a.root = a.push(mx);
    CHECK(evaluate(a, {0, 0, 0}) == -2.0);
}

TEST_CASE("density: y_clamped_gradient") {
    NodeArena a;
    Node n{};
    n.kind = NodeKind::kYClampedGradient;
    n.i0 = 0;
    n.i1 = 100;
    n.d0 = 0.0;
    n.d1 = 1.0;
    a.root = a.push(n);
    // y = 0    → 0.0
    CHECK(evaluate(a, {0, 0, 0}) == 0.0);
    // y = 100  → 1.0
    CHECK(evaluate(a, {0, 100, 0}) == doctest::Approx(1.0).epsilon(1e-15));
    // y = 50   → 0.5
    CHECK(evaluate(a, {0, 50, 0}) == doctest::Approx(0.5).epsilon(1e-15));
    // y = -10 (below from_y) → clamped to from_v
    CHECK(evaluate(a, {0, -10, 0}) == 0.0);
    // y = 200 (above to_y) → clamped to to_v
    CHECK(evaluate(a, {0, 200, 0}) == doctest::Approx(1.0).epsilon(1e-15));
}

TEST_CASE("density: map_range linear remap") {
    NodeArena a;
    Node c{}; c.kind = NodeKind::kConstant; c.d0 = 0.5;
    Node mr{}; mr.kind = NodeKind::kMapRange;
    mr.a  = a.push(c);
    mr.d0 = 0.0; mr.d1 = 1.0;   // from
    mr.d2 = 10.0; mr.d3 = 20.0; // to
    a.root = a.push(mr);
    CHECK(evaluate(a, {0, 0, 0}) == doctest::Approx(15.0).epsilon(1e-15));
}

TEST_CASE("density: lerp") {
    NodeArena a;
    Node t{}; t.kind = NodeKind::kConstant; t.d0 = 0.25;
    Node lo{}; lo.kind = NodeKind::kConstant; lo.d0 = 4.0;
    Node hi{}; hi.kind = NodeKind::kConstant; hi.d0 = 8.0;
    Node ln{}; ln.kind = NodeKind::kLerp;
    ln.a = a.push(t);
    ln.b = a.push(lo);
    ln.c = a.push(hi);
    a.root = a.push(ln);
    // 4 + 0.25 * (8-4) = 5.0
    CHECK(evaluate(a, {0, 0, 0}) == doctest::Approx(5.0).epsilon(1e-15));
}

TEST_CASE("density: range_choice picks correct branch") {
    NodeArena a;
    Node input{}; input.kind = NodeKind::kConstant; input.d0 = 3.0;
    Node win{};   win.kind   = NodeKind::kConstant; win.d0 = 100.0;
    Node wout{};  wout.kind  = NodeKind::kConstant; wout.d0 = -100.0;
    Node rc{}; rc.kind = NodeKind::kRangeChoice;
    rc.a  = a.push(input);
    rc.b  = a.push(win);
    rc.c  = a.push(wout);
    rc.d0 = 0.0;  // min inclusive
    rc.d1 = 10.0; // max exclusive
    a.root = a.push(rc);
    CHECK(evaluate(a, {0, 0, 0}) == 100.0);

    // Change input to be out of range.
    a.nodes[0].d0 = 50.0;
    CHECK(evaluate(a, {0, 0, 0}) == -100.0);
}

TEST_CASE("density: Cache* nodes pass through (phase-1)") {
    NodeArena a;
    Node c{}; c.kind = NodeKind::kConstant; c.d0 = 7.0;
    NodeRef rc = a.push(c);

    for (auto kind : { NodeKind::kCache2D, NodeKind::kCacheOnce,
                       NodeKind::kCacheAllInCell, NodeKind::kFlatCache,
                       NodeKind::kInterpolated }) {
        Node wrap{};
        wrap.kind = kind;
        wrap.a    = rc;
        a.root    = a.push(wrap);
        CHECK(evaluate(a, {0, 0, 0}) == 7.0);
    }
}

TEST_CASE("density: shift family scales noise by 4") {
    PerlinNoiseSampler oa = oct(0x77);
    PerlinNoiseSampler ob = oct(0x88);
    double amps[1] = {1.0};
    DoublePerlinNoiseSampler dpn{};
    dpn.first.octaves = &oa;  dpn.first.amplitudes = amps;
    dpn.first.octave_count = 1; dpn.first.lacunarity = 1.0; dpn.first.persistence = 1.0;
    dpn.second.octaves = &ob; dpn.second.amplitudes = amps;
    dpn.second.octave_count = 1; dpn.second.lacunarity = 1.0; dpn.second.persistence = 1.0;
    dpn.amplitude = 1.0;

    NodeArena a;
    Node n{}; n.kind = NodeKind::kShiftA; n.noise_ptr = &dpn;
    a.root = a.push(n);
    const double v = evaluate(a, {5.0, 99.0, -3.0});
    // ShiftA samples at (x*0.25, 0, z*0.25), scaled by 4.
    const double expected = noise::sample(dpn, 5.0 * 0.25, 0.0, -3.0 * 0.25) * 4.0;
    CHECK(v == expected);
}

TEST_CASE("density: noise node uses the bound sampler") {
    // Construct a minimal DoublePerlinNoiseSampler.
    PerlinNoiseSampler oa = oct(0x55);
    PerlinNoiseSampler ob = oct(0xAA);
    double amps[1] = {1.0};
    DoublePerlinNoiseSampler dpn{};
    dpn.first.octaves = &oa; dpn.first.amplitudes = amps;
    dpn.first.octave_count = 1; dpn.first.lacunarity = 1.0; dpn.first.persistence = 1.0;
    dpn.second.octaves = &ob; dpn.second.amplitudes = amps;
    dpn.second.octave_count = 1; dpn.second.lacunarity = 1.0; dpn.second.persistence = 1.0;
    dpn.amplitude = 1.0;

    NodeArena a;
    Node n{}; n.kind = NodeKind::kNoise;
    n.d0 = 1.0; n.d1 = 1.0;
    n.noise_ptr = &dpn;
    a.root = a.push(n);

    const double v = evaluate(a, {0.5, 0.7, -0.3});
    CHECK(std::isfinite(v));
    CHECK(v == noise::sample(dpn, 0.5, 0.7, -0.3));
}

#if defined(LATTICE_TEST_HAS_DENSITY_AVX2)
TEST_CASE("density: AVX2 weird_scaled uniform rarity column matches scalar") {
    if (!lattice::cpu::initialize().avx2) return;

    PerlinNoiseSampler oa = oct(0x31);
    PerlinNoiseSampler ob = oct(0xC7);
    double amps[1] = {1.0};
    DoublePerlinNoiseSampler dpn{};
    dpn.first.octaves = &oa; dpn.first.amplitudes = amps;
    dpn.first.octave_count = 1; dpn.first.lacunarity = 1.0; dpn.first.persistence = 1.0;
    dpn.second.octaves = &ob; dpn.second.amplitudes = amps;
    dpn.second.octave_count = 1; dpn.second.lacunarity = 1.0; dpn.second.persistence = 1.0;
    dpn.amplitude = 1.0;

    NodeArena a;
    Node input{}; input.kind = NodeKind::kConstant; input.d0 = 0.25; // Type1 rarity = 1.5 for the whole column.
    Node weird{}; weird.kind = NodeKind::kWeirdScaledSampler;
    weird.a = a.push(input);
    weird.noise_ptr = &dpn;
    weird.d0 = 0.0;
    a.root = a.push(weird);

    constexpr int count = 17;
    const double x = 12.25;
    const double y0 = -16.0;
    const double z = 33.75;
    const double dy = 3.5;
    std::vector<double> column(static_cast<std::size_t>(count));

    REQUIRE(evaluate_y_column_avx2(a, a.root, x, y0, z, dy, 0, 0, count, nullptr, column.data()));
    for (int i = 0; i < count; ++i) {
        Context ctx{};
        ctx.x = x;
        ctx.y = y0 + static_cast<double>(i) * dy;
        ctx.z = z;
        CHECK(column[static_cast<std::size_t>(i)] == doctest::Approx(evaluate(a, ctx)).epsilon(1e-12));
    }
}
#endif

TEST_CASE("density: Cache2D returns cached value on (x,z) hit") {
    // Build a tree that COUNTS evaluations via a global counter wired
    // through a custom node. We can't easily mock counter inside the
    // C++ evaluator without adding test hooks, so instead verify
    // behaviour: same (x,z) returns the same value, and the value
    // matches a passthrough on the *first* call.
    NodeArena arena;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 42.0;
    NodeRef leaf_ref = arena.push(leaf);
    Node c2d{}; c2d.kind = NodeKind::kCache2D; c2d.a = leaf_ref;
    arena.root = arena.push(c2d);

    CacheState cs;
    cs.resize_for(arena);

    Context ctx{};
    ctx.cache = &cs;

    ctx.x = 1.0; ctx.y = 1.0; ctx.z = 1.0;
    CHECK(evaluate(arena, ctx) == 42.0);
    // Same (x, z) — should still be 42 (cache hit).
    ctx.y = 1000.0;  // y differs, but Cache2D ignores y.
    CHECK(evaluate(arena, ctx) == 42.0);
    // Different (x, z) — recompute, still 42 since the leaf is constant.
    ctx.x = 2.0;
    CHECK(evaluate(arena, ctx) == 42.0);

    // Verify the cache state actually has 2 distinct entries.
    CHECK(cs.cache_2d.size() == 1);
    CHECK(cs.cache_2d[0].valid == true);
}

TEST_CASE("density: Cache2D uses floor coordinates for negative positions") {
    NodeArena a;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 11.0;
    NodeRef leaf_ref = a.push(leaf);
    Node c2d{}; c2d.kind = NodeKind::kCache2D; c2d.a = leaf_ref;
    a.root = a.push(c2d);

    CacheState cs;
    cs.resize_for(a);

    Context ctx{};
    ctx.cache = &cs;
    ctx.x = -0.2;
    ctx.y = 0.0;
    ctx.z = -0.8;

    CHECK(evaluate(a, ctx) == 11.0);

    a.nodes[leaf_ref].d0 = 37.0;
    ctx.x = -0.9;
    ctx.z = -0.1;
    CHECK(evaluate(a, ctx) == 11.0);

    ctx.x = 0.1;
    CHECK(evaluate(a, ctx) == 37.0);
}

TEST_CASE("density: CacheAllInCell stores and retrieves per key") {
    NodeArena arena;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 11.0;
    NodeRef leaf_ref = arena.push(leaf);
    Node cell{}; cell.kind = NodeKind::kCacheAllInCell; cell.a = leaf_ref;
    arena.root = arena.push(cell);

    CacheState cs;
    cs.resize_for(arena);

    Context ctx{};
    ctx.cache = &cs;
    ctx.x = 1.0;
    ctx.y = 5.0;
    ctx.z = 2.0;
    ctx.cellX = 7;
    ctx.cellZ = 9;

    CHECK(evaluate(arena, ctx) == 11.0);
    CHECK(cs.cache_all_in_cell.size() == 1);
    CHECK(cs.cache_all_in_cell[0].used == 1);

    ctx.y = 6.0;
    CHECK(evaluate(arena, ctx) == 11.0);
    CHECK(cs.cache_all_in_cell[0].used == 2);

    ctx.y = 5.0;
    CHECK(evaluate(arena, ctx) == 11.0);
    CHECK(cs.cache_all_in_cell[0].used == 2);
}

TEST_CASE("density: mul short-circuits when left side is zero") {
    NodeArena arena;
    Node left{}; left.kind = NodeKind::kConstant; left.d0 = 0.0;
    Node right{}; right.kind = NodeKind::kConstant; right.d0 = 123.0;
    Node mul{}; mul.kind = NodeKind::kMul;
    mul.a = arena.push(left);
    mul.b = arena.push(right);
    arena.root = arena.push(mul);
    CHECK(evaluate(arena, {0, 0, 0}) == 0.0);
}

TEST_CASE("density: mul same-ref fast path preserves result") {
    NodeArena arena;
    Node c{}; c.kind = NodeKind::kConstant; c.d0 = -3.0;
    NodeRef rc = arena.push(c);
    Node mul{}; mul.kind = NodeKind::kMul; mul.a = rc; mul.b = rc;
    arena.root = arena.push(mul);
    CHECK(evaluate(arena, {0, 0, 0}) == 9.0);
}

TEST_CASE("density: CacheOnce slot is per-node, not shared") {
    NodeArena arena;
    Node leafA{}; leafA.kind = NodeKind::kConstant; leafA.d0 = 1.0;
    Node leafB{}; leafB.kind = NodeKind::kConstant; leafB.d0 = 2.0;
    NodeRef rA = arena.push(leafA);
    NodeRef rB = arena.push(leafB);

    Node cA{}; cA.kind = NodeKind::kCacheOnce; cA.a = rA;
    Node cB{}; cB.kind = NodeKind::kCacheOnce; cB.a = rB;
    NodeRef rcA = arena.push(cA);
    NodeRef rcB = arena.push(cB);

    Node add{}; add.kind = NodeKind::kAdd; add.a = rcA; add.b = rcB;
    arena.root = arena.push(add);

    CacheState cs;
    cs.resize_for(arena);
    CHECK(cs.cache_once.size() == 2);   // two slots, not shared

    Context ctx{};
    ctx.cache = &cs;
    ctx.x = 0.0; ctx.y = 0.0; ctx.z = 0.0;
    CHECK(evaluate(arena, ctx) == 3.0);
}

TEST_CASE("density: cache passthrough when CacheState is null") {
    NodeArena arena;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 99.0;
    NodeRef leaf_ref = arena.push(leaf);
    Node c2d{}; c2d.kind = NodeKind::kCache2D; c2d.a = leaf_ref;
    arena.root = arena.push(c2d);

    Context ctx{}; // no cache
    CHECK(evaluate(arena, ctx) == 99.0);
}

TEST_CASE("density: EndIslands produces sensible base height") {
    // EndIslands uses a SimplexNoise sampler.
    SimplexNoiseSampler simplex{};
    simplex.origin_x = 0.0; simplex.origin_y = 0.0; simplex.origin_z = 0.0;
    for (int i = 0; i < 256; ++i) simplex.permutation[i] = (i * 31) & 0xFF;

    NodeArena a;
    Node n{};
    n.kind = NodeKind::kEndIslands;
    n.simplex_ptr = &simplex;
    a.root = a.push(n);

    // At the origin: base height = -8 * 0 / 4096 + 100 = 100, clamped to 80.
    // Then scan 13×13 chunk centers; bonus islands may bring it higher.
    const double h0 = evaluate(a, {0, 0, 0});
    CHECK(std::isfinite(h0));
    CHECK(h0 <= 80.0); // can't exceed the +80 cap and the +9..+22 island bonus
                       // (since the simplex with our test permutation rarely passes the -0.9 gate)

    // Far out (~ 5000 blocks from origin): base height ≈ -8 * (5000²+5000²) / 4096 + 100
    // ≈ -8 * 50000000 / 4096 + 100 ≈ -97558 ... clamped to -100. Should clamp.
    const double h_far = evaluate(a, {5000, 0, 5000});
    CHECK(h_far >= -100.0);
    CHECK(h_far <= 80.0);
}

// ---- Worldgen-7: Clamp / BlendAlpha / BlendOffset / BlendDensity ----------

TEST_CASE("density: clamp clips to [min, max]") {
    NodeArena a;
    Node c{}; c.kind = NodeKind::kConstant; c.d0 = 50.0;
    Node clamp{}; clamp.kind = NodeKind::kClamp;
    clamp.a  = a.push(c);
    clamp.d0 = 0.0;   // min
    clamp.d1 = 10.0;  // max
    a.root = a.push(clamp);

    // 50 → clamp to 10.
    CHECK(evaluate(a, {0, 0, 0}) == 10.0);
    // Mutate the input to a value below min.
    a.nodes[0].d0 = -5.0;
    CHECK(evaluate(a, {0, 0, 0}) == 0.0);
    // Inside range → unchanged.
    a.nodes[0].d0 = 7.5;
    CHECK(evaluate(a, {0, 0, 0}) == 7.5);
    // Boundaries are inclusive.
    a.nodes[0].d0 = 0.0;
    CHECK(evaluate(a, {0, 0, 0}) == 0.0);
    a.nodes[0].d0 = 10.0;
    CHECK(evaluate(a, {0, 0, 0}) == 10.0);
}

TEST_CASE("density: blend_alpha is constant 1.0 (no-blending semantics)") {
    NodeArena a;
    Node n{}; n.kind = NodeKind::kBlendAlpha;
    a.root = a.push(n);
    // Independent of (x, y, z).
    CHECK(evaluate(a, {0, 0, 0})            == 1.0);
    CHECK(evaluate(a, {-1e6, 0, 1e6})       == 1.0);
    CHECK(evaluate(a, {123, 456, 789})      == 1.0);
}

TEST_CASE("density: blend_offset is constant 0.0 (no-blending semantics)") {
    NodeArena a;
    Node n{}; n.kind = NodeKind::kBlendOffset;
    a.root = a.push(n);
    CHECK(evaluate(a, {0, 0, 0})            == 0.0);
    CHECK(evaluate(a, {-1e6, 0, 1e6})       == 0.0);
    CHECK(evaluate(a, {1, 2, 3})            == 0.0);
}

TEST_CASE("density: blend_density is passthrough of input") {
    NodeArena a;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 42.0;
    NodeRef leaf_ref = a.push(leaf);
    Node bd{}; bd.kind = NodeKind::kBlendDensity; bd.a = leaf_ref;
    a.root = a.push(bd);
    CHECK(evaluate(a, {0, 0, 0}) == 42.0);
    a.nodes[0].d0 = -7.5;
    CHECK(evaluate(a, {1, 2, 3}) == -7.5);
}

// ---- Worldgen-9: batched cell-grid evaluation ----------------------------

TEST_CASE("density: evaluate_grid fills constant tree uniformly") {
    auto a = make_constant(7.5);
    constexpr int nx = 4, ny = 3, nz = 5;
    double out[nx * ny * nz];
    evaluate_grid(a, a.root,
                  0.0, 0.0, 0.0,
                  1.0, 1.0, 1.0,
                  0, 0, nx, ny, nz,
                  nullptr, out);
    for (int i = 0; i < nx * ny * nz; ++i) {
        CHECK(out[i] == 7.5);
    }
}

TEST_CASE("density: evaluate_grid coordinates map correctly") {
    // Use a YClampedGradient that returns y as a fraction of [0, 100].
    NodeArena a;
    Node n{};
    n.kind = NodeKind::kYClampedGradient;
    n.i0 = 0; n.i1 = 100; n.d0 = 0.0; n.d1 = 100.0; // identity in [0,100]
    a.root = a.push(n);

    constexpr int nx = 2, ny = 4, nz = 3;
    double out[nx * ny * nz];
    evaluate_grid(a, a.root,
                  /*x0,y0,z0*/ 0.0, 10.0, 0.0,
                  /*dx,dy,dz*/ 1.0,  5.0, 1.0,
                  0, 0, nx, ny, nz,
                  nullptr, out);

    // Layout: out[(iy*nz + iz)*nx + ix]. Independent of x and z.
    for (int iy = 0; iy < ny; ++iy) {
        const double expected = 10.0 + iy * 5.0;
        for (int iz = 0; iz < nz; ++iz) {
            for (int ix = 0; ix < nx; ++ix) {
                const int idx = (iy * nz + iz) * nx + ix;
                CHECK(out[idx] == doctest::Approx(expected).epsilon(1e-12));
            }
        }
    }
}

TEST_CASE("density: evaluate_grid honours FlatCache within an iz step") {
    // Wrap a leaf in FlatCache. Our FlatCache is a single-entry LRU
    // keyed on (cellX, cellZ): once it caches a value at one
    // (cellX, cellZ), the next sample at the SAME cell returns the
    // cached value, but stepping to a different cell evicts the
    // entry. So a grid fill mostly re-evaluates every cell — the
    // value of FlatCache is across multiple sub-block samples
    // landing in the same cell, not across grid points.
    //
    // We test the documented contract: within a fixed (cellX, cellZ)
    // (so nx=ny=1, nz=1, cell0 fixed), repeating an evaluate_grid
    // call hits the cached value even after the underlying constant
    // mutates.
    NodeArena a;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 1.0;
    NodeRef leaf_ref = a.push(leaf);
    Node fc{}; fc.kind = NodeKind::kFlatCache; fc.a = leaf_ref;
    a.root = a.push(fc);

    CacheState cs;
    cs.resize_for(a);

    // First call: fills the FlatCache slot with 1.0 at (cellX=10, cellZ=20).
    double out1[1];
    evaluate_grid(a, a.root,
                  0.0, 0.0, 0.0,
                  1.0, 1.0, 1.0,
                  /*cellX0*/ 10, /*cellZ0*/ 20,
                  1, 1, 1, &cs, out1);
    CHECK(out1[0] == 1.0);

    // Mutate; same cell hit on second call should still return 1.0.
    a.nodes[0].d0 = 999.0;
    double out2[1];
    evaluate_grid(a, a.root,
                  0.0, 0.0, 0.0,
                  1.0, 1.0, 1.0,
                  /*cellX0*/ 10, /*cellZ0*/ 20,
                  1, 1, 1, &cs, out2);
    CHECK(out2[0] == 1.0);

    // Different cell evicts the slot and re-evaluates.
    double out3[1];
    evaluate_grid(a, a.root,
                  0.0, 0.0, 0.0,
                  1.0, 1.0, 1.0,
                  /*cellX0*/ 11, /*cellZ0*/ 20,
                  1, 1, 1, &cs, out3);
    CHECK(out3[0] == 999.0);
}

TEST_CASE("density: evaluate_grid with null root zero-fills") {
    NodeArena empty;
    constexpr int n = 6;
    double out[n] = {1, 2, 3, 4, 5, 6};
    evaluate_grid(empty, /*root*/ -1,
                  0, 0, 0, 1, 1, 1, 0, 0,
                  n, 1, 1, nullptr, out);
    for (int i = 0; i < n; ++i) CHECK(out[i] == 0.0);
}

TEST_CASE("density: evaluate_grid with non-positive dims is a no-op") {
    auto a = make_constant(123.0);
    double out[1] = {-1.0};
    evaluate_grid(a, a.root, 0,0,0, 1,1,1, 0,0,
                  /*nx*/ 0, /*ny*/ 1, /*nz*/ 1,
                  nullptr, out);
    CHECK(out[0] == -1.0); // untouched
}

// ---- Worldgen-10: Spline -------------------------------------------------

TEST_CASE("spline: fixed float returns the constant") {
    NodeArena a;
    SplineRef s = a.push_spline({SplineKind::kFixedFloat, 7.5f, -1, 0, 0});
    Node n{}; n.kind = NodeKind::kSpline; n.i0 = static_cast<int>(s);
    a.root = a.push(n);
    CHECK(evaluate(a, {0, 0, 0}) == doctest::Approx(7.5).epsilon(1e-6));
}

TEST_CASE("spline: linear interpolation between two breakpoints") {
    NodeArena a;
    // Use a YClampedGradient as the location function so the input
    // is just `y` (in [0, 100]).
    Node loc{}; loc.kind = NodeKind::kYClampedGradient;
    loc.i0 = 0; loc.i1 = 100; loc.d0 = 0.0; loc.d1 = 100.0;
    NodeRef loc_ref = a.push(loc);

    // Build two FixedFloat sub-splines: at x=0 → 0.0, at x=100 → 50.0.
    SplineRef sLow  = a.push_spline({SplineKind::kFixedFloat, 0.0f,  -1, 0, 0});
    SplineRef sHigh = a.push_spline({SplineKind::kFixedFloat, 50.0f, -1, 0, 0});

    // Reserve 2 breakpoints, fill them in.
    const int start = a.reserve_spline_breakpoints(2);
    a.spline_breakpoints[start + 0] = {0.0f,    0.0f, sLow };
    a.spline_breakpoints[start + 1] = {100.0f,  0.0f, sHigh};

    Spline impl{};
    impl.kind = SplineKind::kImpl;
    impl.location_function = loc_ref;
    impl.breakpoints_start = start;
    impl.breakpoint_count  = 2;
    SplineRef root = a.push_spline(impl);

    Node n{}; n.kind = NodeKind::kSpline; n.i0 = static_cast<int>(root);
    a.root = a.push(n);

    // Both derivatives are 0 → cubic-Hermite collapses to plain lerp.
    // y=0 → 0.0; y=100 → 50.0; y=50 → 25.0.
    CHECK(evaluate(a, {0, 0,   0}) == doctest::Approx(0.0).epsilon(1e-6));
    CHECK(evaluate(a, {0, 100, 0}) == doctest::Approx(50.0).epsilon(1e-6));
    CHECK(evaluate(a, {0, 50,  0}) == doctest::Approx(25.0).epsilon(1e-6));
    // Below range: linear extrapolation with derivative 0 → returns
    // the boundary value (50.0 below the first point would be 0 because
    // first derivative is 0; same here).
    CHECK(evaluate(a, {0, -10, 0}) == doctest::Approx(0.0).epsilon(1e-6));
}

TEST_CASE("spline: outside-range linear extrapolation honours derivative") {
    NodeArena a;
    Node loc{}; loc.kind = NodeKind::kYClampedGradient;
    loc.i0 = -1000; loc.i1 = 1000; loc.d0 = -1000.0; loc.d1 = 1000.0;
    NodeRef loc_ref = a.push(loc);

    // Single breakpoint at x=0 → 5.0 with derivative 2.0.
    SplineRef leaf = a.push_spline({SplineKind::kFixedFloat, 5.0f, -1, 0, 0});
    const int start = a.reserve_spline_breakpoints(1);
    a.spline_breakpoints[start + 0] = {0.0f, 2.0f, leaf};

    Spline impl{};
    impl.kind = SplineKind::kImpl;
    impl.location_function = loc_ref;
    impl.breakpoints_start = start;
    impl.breakpoint_count  = 1;
    SplineRef root = a.push_spline(impl);

    Node n{}; n.kind = NodeKind::kSpline; n.i0 = static_cast<int>(root);
    a.root = a.push(n);

    // y=0 (at the boundary) → 5.0
    CHECK(evaluate(a, {0, 0,   0}) == doctest::Approx(5.0).epsilon(1e-6));
    // y=10 (above) → 5 + 2*(10-0) = 25
    CHECK(evaluate(a, {0, 10,  0}) == doctest::Approx(25.0).epsilon(1e-6));
    // y=-3 (below) → 5 + 2*(-3-0) = -1
    CHECK(evaluate(a, {0, -3,  0}) == doctest::Approx(-1.0).epsilon(1e-6));
}

TEST_CASE("spline: cubic-Hermite interior matches Mojang formula") {
    // Two-point spline with non-zero derivatives.
    // location 0 → value 0, derivative 1
    // location 1 → value 1, derivative 1
    // For k in [0, 1]:
    //   p = 1*(1-0) - (1-0) = 0
    //   q = -1*(1-0) + (1-0) = 0
    //   result = lerp(k, 0, 1) + k*(1-k)*lerp(k, 0, 0) = k.
    // i.e. with these specific derivatives it degenerates back to
    // lerp. We pick this because we can hand-verify.
    NodeArena a;
    Node loc{}; loc.kind = NodeKind::kYClampedGradient;
    loc.i0 = 0; loc.i1 = 1; loc.d0 = 0.0; loc.d1 = 1.0;
    NodeRef loc_ref = a.push(loc);

    SplineRef lo = a.push_spline({SplineKind::kFixedFloat, 0.0f, -1, 0, 0});
    SplineRef hi = a.push_spline({SplineKind::kFixedFloat, 1.0f, -1, 0, 0});

    const int start = a.reserve_spline_breakpoints(2);
    a.spline_breakpoints[start + 0] = {0.0f, 1.0f, lo};
    a.spline_breakpoints[start + 1] = {1.0f, 1.0f, hi};

    Spline impl{};
    impl.kind = SplineKind::kImpl;
    impl.location_function = loc_ref;
    impl.breakpoints_start = start;
    impl.breakpoint_count  = 2;
    SplineRef root = a.push_spline(impl);

    Node n{}; n.kind = NodeKind::kSpline; n.i0 = static_cast<int>(root);
    a.root = a.push(n);

    // YClampedGradient with i0=0, i1=1, d0=0, d1=1 maps y∈[0,1] linearly
    // to [0,1]. So for y=0.25, location=0.25, expected output=0.25.
    CHECK(evaluate(a, {0, 0.25, 0}) == doctest::Approx(0.25).epsilon(1e-6));
    CHECK(evaluate(a, {0, 0.5,  0}) == doctest::Approx(0.5).epsilon(1e-6));
    CHECK(evaluate(a, {0, 0.75, 0}) == doctest::Approx(0.75).epsilon(1e-6));
}

TEST_CASE("spline: empty arena root yields 0") {
    NodeArena a;
    Node n{}; n.kind = NodeKind::kSpline; n.i0 = -1;  // null ref
    a.root = a.push(n);
    CHECK(evaluate(a, {0, 0, 0}) == 0.0);
}

// ---- Worldgen-11: FindTopSurface -----------------------------------------

TEST_CASE("find_top_surface: returns floor(upper/cellH)*cellH when density positive") {
    // density: constant 1.0 (always > 0 → first y tested wins).
    // upper:   constant 73 (so floor(73/8)*8 = 72).
    NodeArena a;
    Node dens{}; dens.kind = NodeKind::kConstant; dens.d0 = 1.0;
    Node upr{};  upr.kind  = NodeKind::kConstant; upr.d0  = 73.0;
    NodeRef d_ref = a.push(dens);
    NodeRef u_ref = a.push(upr);

    Node fts{};
    fts.kind = NodeKind::kFindTopSurface;
    fts.a = d_ref; fts.b = u_ref;
    fts.i0 = -64;  // lowerBound
    fts.i1 = 8;    // cellHeight
    a.root = a.push(fts);

    CHECK(evaluate(a, {0, 0, 0}) == 72.0);
}

TEST_CASE("find_top_surface: returns lowerBound when density never positive") {
    // density: constant -1.0 → never > 0.
    NodeArena a;
    Node dens{}; dens.kind = NodeKind::kConstant; dens.d0 = -1.0;
    Node upr{};  upr.kind  = NodeKind::kConstant; upr.d0  = 100.0;
    NodeRef d_ref = a.push(dens);
    NodeRef u_ref = a.push(upr);

    Node fts{};
    fts.kind = NodeKind::kFindTopSurface;
    fts.a = d_ref; fts.b = u_ref;
    fts.i0 = -64; fts.i1 = 8;
    a.root = a.push(fts);

    CHECK(evaluate(a, {0, 0, 0}) == -64.0);
}

TEST_CASE("find_top_surface: short-circuits when upper <= lowerBound") {
    NodeArena a;
    Node dens{}; dens.kind = NodeKind::kConstant; dens.d0 = 1.0; // would match
    Node upr{};  upr.kind  = NodeKind::kConstant; upr.d0  = -100.0; // way below
    NodeRef d_ref = a.push(dens);
    NodeRef u_ref = a.push(upr);

    Node fts{};
    fts.kind = NodeKind::kFindTopSurface;
    fts.a = d_ref; fts.b = u_ref;
    fts.i0 = -64; fts.i1 = 8;
    a.root = a.push(fts);

    // floor(-100/8) * 8 = -13 * 8 = -104, which is <= -64 → returns lowerBound
    // without ever sampling density.
    CHECK(evaluate(a, {0, 0, 0}) == -64.0);
}

TEST_CASE("find_top_surface: scans downward and snaps to first cell with positive density") {
    // density: y_clamped_gradient that's positive iff y < 50:
    //   maps y=[0,100] → density=[1, -1] linearly. So density>0 when y < 50.
    //
    // upper: 96 → floor(96/8)*8 = 96 (top of scan).
    // cellHeight = 8, lowerBound = 0.
    //
    // Scan: 96, 88, 80, 72, 64, 56 (all > 50 → density <= 0)
    //       48 (< 50 → density > 0). Returns 48.
    NodeArena a;
    Node dens{}; dens.kind = NodeKind::kYClampedGradient;
    dens.i0 = 0; dens.i1 = 100; dens.d0 = 1.0; dens.d1 = -1.0;
    Node upr{};  upr.kind  = NodeKind::kConstant; upr.d0  = 96.0;
    NodeRef d_ref = a.push(dens);
    NodeRef u_ref = a.push(upr);

    Node fts{};
    fts.kind = NodeKind::kFindTopSurface;
    fts.a = d_ref; fts.b = u_ref;
    fts.i0 = 0; fts.i1 = 8;
    a.root = a.push(fts);

    CHECK(evaluate(a, {0, 0, 0}) == 48.0);
}

TEST_CASE("find_top_surface: cellHeight <= 0 returns lowerBound (no infinite loop)") {
    NodeArena a;
    Node dens{}; dens.kind = NodeKind::kConstant; dens.d0 = 1.0;
    Node upr{};  upr.kind  = NodeKind::kConstant; upr.d0  = 100.0;
    NodeRef d_ref = a.push(dens);
    NodeRef u_ref = a.push(upr);

    Node fts{};
    fts.kind = NodeKind::kFindTopSurface;
    fts.a = d_ref; fts.b = u_ref;
    fts.i0 = -64;
    fts.i1 = 0;  // bogus
    a.root = a.push(fts);

    CHECK(evaluate(a, {0, 0, 0}) == -64.0);
}

// ---- Worldgen-13: Interpolator (DensityInterpolator) -------------------

TEST_CASE("interpolator: slot id assigned per kInterpolated node") {
    NodeArena a;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 0.0;
    NodeRef leaf_ref = a.push(leaf);

    Node n0{}; n0.kind = NodeKind::kInterpolated; n0.a = leaf_ref;
    Node n1{}; n1.kind = NodeKind::kInterpolated; n1.a = leaf_ref;
    Node n2{}; n2.kind = NodeKind::kInterpolated; n2.a = leaf_ref;
    NodeRef r0 = a.push(n0);
    NodeRef r1 = a.push(n1);
    NodeRef r2 = a.push(n2);

    CHECK(a.num_interpolator_slots == 3);
    CHECK(a.nodes[r0].cache_slot_id == 0);
    CHECK(a.nodes[r1].cache_slot_id == 1);
    CHECK(a.nodes[r2].cache_slot_id == 2);
}

TEST_CASE("interpolator: passthrough when loop inactive") {
    // kInterpolated with no active interpolation should fall through
    // to the wrapped input — preserves Worldgen-9 behaviour.
    NodeArena a;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 42.0;
    NodeRef leaf_ref = a.push(leaf);
    Node ip{}; ip.kind = NodeKind::kInterpolated; ip.a = leaf_ref;
    a.root = a.push(ip);

    // No CacheState supplied → passthrough.
    CHECK(evaluate(a, {0, 0, 0}) == 42.0);

    // Cache supplied but loop inactive → still passthrough.
    CacheState cs;
    cs.resize_for(a);
    cs.prepare_interpolators(/*hCC=*/2, /*vCC=*/2);
    Context ctx{};
    ctx.cache = &cs;
    CHECK(evaluate(a, ctx) == 42.0);
}

TEST_CASE("interpolator: returns trilinear blend during loop") {
    // Build a DF with one Interpolated node (input doesn't matter
    // during the loop — we read the cascaded result directly).
    NodeArena a;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = -999.0;
    NodeRef leaf_ref = a.push(leaf);
    Node ip{}; ip.kind = NodeKind::kInterpolated; ip.a = leaf_ref;
    a.root = a.push(ip);

    // Single 1x1x1 cell. Buffers are 2x2 corners on each face.
    CacheState cs;
    cs.resize_for(a);
    cs.prepare_interpolators(/*hCC=*/1, /*vCC=*/1);

    // Fill the start (cellX=0) and end (cellX=1) buffers so that the
    // 8 corners hold values 1..8 in a known order:
    //   x0y0z0 = 1
    //   x0y0z1 = 2
    //   x1y0z0 = 3
    //   x1y0z1 = 4
    //   x0y1z0 = 5
    //   x0y1z1 = 6
    //   x1y1z0 = 7
    //   x1y1z1 = 8
    set_start_density(cs, 0, /*cellZ=*/0, /*cellY=*/0, 1.0);  // x0y0z0
    set_start_density(cs, 0, /*cellZ=*/1, /*cellY=*/0, 2.0);  // x0y0z1
    set_end_density  (cs, 0, /*cellZ=*/0, /*cellY=*/0, 3.0);  // x1y0z0
    set_end_density  (cs, 0, /*cellZ=*/1, /*cellY=*/0, 4.0);  // x1y0z1
    set_start_density(cs, 0, /*cellZ=*/0, /*cellY=*/1, 5.0);  // x0y1z0
    set_start_density(cs, 0, /*cellZ=*/1, /*cellY=*/1, 6.0);  // x0y1z1
    set_end_density  (cs, 0, /*cellZ=*/0, /*cellY=*/1, 7.0);  // x1y1z0
    set_end_density  (cs, 0, /*cellZ=*/1, /*cellY=*/1, 8.0);  // x1y1z1

    start_interpolation(cs);
    on_sampled_cell_corners(cs, /*cellY=*/0, /*cellZ=*/0);

    Context ctx{};
    ctx.cache = &cs;

    // 8 corners: each delta in {0, 1} should pick out one corner exactly.
    // (deltaY, deltaX, deltaZ) = (0, 0, 0) → x0y0z0 = 1.
    interpolate_y(cs, 0.0); interpolate_x(cs, 0.0); interpolate_z(cs, 0.0);
    CHECK(evaluate(a, ctx) == 1.0);

    interpolate_y(cs, 0.0); interpolate_x(cs, 0.0); interpolate_z(cs, 1.0);
    CHECK(evaluate(a, ctx) == 2.0);

    interpolate_y(cs, 0.0); interpolate_x(cs, 1.0); interpolate_z(cs, 0.0);
    CHECK(evaluate(a, ctx) == 3.0);

    interpolate_y(cs, 0.0); interpolate_x(cs, 1.0); interpolate_z(cs, 1.0);
    CHECK(evaluate(a, ctx) == 4.0);

    interpolate_y(cs, 1.0); interpolate_x(cs, 0.0); interpolate_z(cs, 0.0);
    CHECK(evaluate(a, ctx) == 5.0);

    interpolate_y(cs, 1.0); interpolate_x(cs, 0.0); interpolate_z(cs, 1.0);
    CHECK(evaluate(a, ctx) == 6.0);

    interpolate_y(cs, 1.0); interpolate_x(cs, 1.0); interpolate_z(cs, 0.0);
    CHECK(evaluate(a, ctx) == 7.0);

    interpolate_y(cs, 1.0); interpolate_x(cs, 1.0); interpolate_z(cs, 1.0);
    CHECK(evaluate(a, ctx) == 8.0);

    // Cell centre: trilinear interp of all 8 corners → average = 4.5.
    interpolate_y(cs, 0.5); interpolate_x(cs, 0.5); interpolate_z(cs, 0.5);
    CHECK(evaluate(a, ctx) == doctest::Approx(4.5).epsilon(1e-15));

    stop_interpolation(cs);
    // After stop, kInterpolated falls back to passthrough → leaf = -999.
    CHECK(evaluate(a, ctx) == -999.0);
}

TEST_CASE("interpolator: swap_buffers makes end buffer the new start") {
    NodeArena a;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 0.0;
    NodeRef leaf_ref = a.push(leaf);
    Node ip{}; ip.kind = NodeKind::kInterpolated; ip.a = leaf_ref;
    a.root = a.push(ip);

    CacheState cs;
    cs.resize_for(a);
    cs.prepare_interpolators(1, 1);

    // Start buffer: 1, 2, 5, 6 ; End buffer: 3, 4, 7, 8 (same as the
    // test above).
    set_start_density(cs, 0, 0, 0, 1.0);
    set_start_density(cs, 0, 1, 0, 2.0);
    set_end_density  (cs, 0, 0, 0, 3.0);
    set_end_density  (cs, 0, 1, 0, 4.0);
    set_start_density(cs, 0, 0, 1, 5.0);
    set_start_density(cs, 0, 1, 1, 6.0);
    set_end_density  (cs, 0, 0, 1, 7.0);
    set_end_density  (cs, 0, 1, 1, 8.0);

    swap_buffers(cs);

    // After swap, what was the end buffer is now the start buffer.
    // Loading corners and sampling at (deltaY, deltaX, deltaZ)=(0,0,0)
    // should now return 3.0 (was the end's x?y0z0 → after swap is the
    // start's, i.e. x0y0z0 of the post-swap buffers).
    start_interpolation(cs);
    on_sampled_cell_corners(cs, 0, 0);
    interpolate_y(cs, 0.0); interpolate_x(cs, 0.0); interpolate_z(cs, 0.0);

    Context ctx{};
    ctx.cache = &cs;
    CHECK(evaluate(a, ctx) == 3.0);
}

TEST_CASE("interpolator: prepare_interpolators sizes buffers correctly") {
    NodeArena a;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 0.0;
    NodeRef leaf_ref = a.push(leaf);
    Node ip{}; ip.kind = NodeKind::kInterpolated; ip.a = leaf_ref;
    a.root = a.push(ip);

    CacheState cs;
    cs.resize_for(a);
    cs.prepare_interpolators(/*hCC=*/16, /*vCC=*/24);

    REQUIRE(cs.interpolators.size() == 1);
    const auto& it = cs.interpolators[0];
    CHECK(it.start_density_buffer.size() == static_cast<std::size_t>((16 + 1) * (24 + 1)));
    CHECK(it.end_density_buffer.size()   == static_cast<std::size_t>((16 + 1) * (24 + 1)));
    CHECK(cs.horizontal_cell_count == 16);
    CHECK(cs.vertical_cell_count   == 24);
}

TEST_CASE("interpolator: independent slots") {
    // Two kInterpolated nodes, each with its own slot. Pre-fill both
    // with distinct constants so we can verify each slot's result is
    // returned for the right node.
    NodeArena a;
    Node leaf{}; leaf.kind = NodeKind::kConstant; leaf.d0 = 0.0;
    NodeRef leaf_ref = a.push(leaf);
    Node ip0{}; ip0.kind = NodeKind::kInterpolated; ip0.a = leaf_ref;
    Node ip1{}; ip1.kind = NodeKind::kInterpolated; ip1.a = leaf_ref;
    NodeRef r0 = a.push(ip0);
    NodeRef r1 = a.push(ip1);

    REQUIRE(a.nodes[r0].cache_slot_id == 0);
    REQUIRE(a.nodes[r1].cache_slot_id == 1);

    CacheState cs;
    cs.resize_for(a);
    cs.prepare_interpolators(1, 1);

    // Slot 0: all corners = 7. Slot 1: all corners = 13.
    for (int cz = 0; cz < 2; ++cz) {
        for (int cy = 0; cy < 2; ++cy) {
            set_start_density(cs, 0, cz, cy, 7.0);
            set_end_density  (cs, 0, cz, cy, 7.0);
            set_start_density(cs, 1, cz, cy, 13.0);
            set_end_density  (cs, 1, cz, cy, 13.0);
        }
    }
    start_interpolation(cs);
    on_sampled_cell_corners(cs, 0, 0);
    interpolate_y(cs, 0.5); interpolate_x(cs, 0.5); interpolate_z(cs, 0.5);

    Context ctx{};
    ctx.cache = &cs;
    a.root = r0; CHECK(evaluate(a, ctx) == 7.0);
    a.root = r1; CHECK(evaluate(a, ctx) == 13.0);
}
