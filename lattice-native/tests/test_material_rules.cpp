#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <array>
#include <cstdint>
#include <limits>

#include "world/gen/surfacebuilder/material_rules.hpp"
#include "world/gen/rng/xoroshiro128pp.hpp"

using namespace lattice::world::gen::surfacebuilder;

namespace {

SampleContext base_ctx() {
    SampleContext c{};
    c.x = 0; c.y = 64; c.z = 0;
    c.surface_y = 64;
    c.fluid_height = 0;
    c.stone_depth_floor = 0;
    c.stone_depth_ceiling = 0;
    c.biome_id = 0;
    c.surface_depth = 0;
    c.min_surface_level = 0;
    c.temperature = 0.5;
    c.surface_noise = 0.0;
    c.surface_secondary_noise = 0.0;
    c.hole_at_position = false;
    c.steep_slope = false;
    return c;
}

} // namespace

TEST_CASE("material: lone block rule returns its index") {
    Arena a;
    Rule r{}; r.kind = RuleKind::kBlock; r.block_index = 17;
    a.root_rule = static_cast<int>(a.rules.size());
    a.rules.push_back(r);
    CHECK(evaluate(a, base_ctx()) == 17);
}

TEST_CASE("material: conditional rule respects condition") {
    Arena a;
    // cond: aboveY(70)
    Condition c{}; c.kind = ConditionKind::kAboveY; c.i0 = 70;
    int cond_ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(c);

    // rule: block(99) if cond else NoMatch
    Rule blk{}; blk.kind = RuleKind::kBlock; blk.block_index = 99;
    int blk_ref = static_cast<int>(a.rules.size());
    a.rules.push_back(blk);

    Rule cond_rule{}; cond_rule.kind = RuleKind::kConditional;
    cond_rule.cond_ref = cond_ref; cond_rule.child_rule_ref = blk_ref;
    a.root_rule = static_cast<int>(a.rules.size());
    a.rules.push_back(cond_rule);

    SampleContext ctx = base_ctx();
    ctx.y = 50; // below threshold → no match
    CHECK(evaluate(a, ctx) == kNoMatch);
    ctx.y = 100; // above → 99
    CHECK(evaluate(a, ctx) == 99);
}

TEST_CASE("material: sequence picks first matching rule") {
    Arena a;
    // Cond above100
    Condition c1{}; c1.kind = ConditionKind::kAboveY; c1.i0 = 100;
    int c1_ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(c1);
    // Cond above50
    Condition c2{}; c2.kind = ConditionKind::kAboveY; c2.i0 = 50;
    int c2_ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(c2);

    // block 1
    Rule b1{}; b1.kind = RuleKind::kBlock; b1.block_index = 1;
    int b1_ref = static_cast<int>(a.rules.size());
    a.rules.push_back(b1);
    // block 2
    Rule b2{}; b2.kind = RuleKind::kBlock; b2.block_index = 2;
    int b2_ref = static_cast<int>(a.rules.size());
    a.rules.push_back(b2);

    // cond_rule_1: above100 → block1
    Rule cr1{}; cr1.kind = RuleKind::kConditional;
    cr1.cond_ref = c1_ref; cr1.child_rule_ref = b1_ref;
    int cr1_ref = static_cast<int>(a.rules.size());
    a.rules.push_back(cr1);
    // cond_rule_2: above50 → block2
    Rule cr2{}; cr2.kind = RuleKind::kConditional;
    cr2.cond_ref = c2_ref; cr2.child_rule_ref = b2_ref;
    int cr2_ref = static_cast<int>(a.rules.size());
    a.rules.push_back(cr2);

    // Sequence [cr1, cr2]
    Rule seq{}; seq.kind = RuleKind::kSequence;
    seq.pool_off = static_cast<int>(a.int_pool.size());
    a.int_pool.push_back(cr1_ref);
    a.int_pool.push_back(cr2_ref);
    seq.pool_count = 2;
    a.root_rule = static_cast<int>(a.rules.size());
    a.rules.push_back(seq);

    SampleContext ctx = base_ctx();
    ctx.y = 200; CHECK(evaluate(a, ctx) == 1); // cr1 hits
    ctx.y = 60;  CHECK(evaluate(a, ctx) == 2); // cr1 misses, cr2 hits
    ctx.y = 20;  CHECK(evaluate(a, ctx) == kNoMatch); // both miss
}

TEST_CASE("material: NOT condition") {
    Arena a;
    // inner: aboveY(100)
    Condition inner{}; inner.kind = ConditionKind::kAboveY; inner.i0 = 100;
    int inner_ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(inner);
    // outer: NOT(inner) = belowOrEqual(99) i.e. y < 100
    Condition outer{}; outer.kind = ConditionKind::kNot; outer.a = inner_ref;
    int outer_ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(outer);

    SampleContext ctx = base_ctx();
    ctx.y = 50;
    CHECK(evaluate_condition(a, outer_ref, ctx) == true);
    ctx.y = 150;
    CHECK(evaluate_condition(a, outer_ref, ctx) == false);
}

TEST_CASE("material: noise threshold") {
    Arena a;
    Condition nt{}; nt.kind = ConditionKind::kNoiseThreshold;
    nt.d0 = -0.1; nt.d1 = 0.1;
    int nt_ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(nt);

    SampleContext ctx = base_ctx();
    ctx.surface_noise = 0.0;  CHECK(evaluate_condition(a, nt_ref, ctx));
    ctx.surface_noise = 0.05; CHECK(evaluate_condition(a, nt_ref, ctx));
    ctx.surface_noise = 0.5;  CHECK(!evaluate_condition(a, nt_ref, ctx));
    ctx.surface_noise = -0.2; CHECK(!evaluate_condition(a, nt_ref, ctx));
}

TEST_CASE("material: biome_is") {
    Arena a;
    Condition bi{}; bi.kind = ConditionKind::kBiomeIs;
    bi.pool_off = static_cast<int>(a.int_pool.size());
    a.int_pool.push_back(7);
    a.int_pool.push_back(11);
    a.int_pool.push_back(42);
    bi.pool_count = 3;
    int bi_ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(bi);

    SampleContext ctx = base_ctx();
    ctx.biome_id = 11; CHECK(evaluate_condition(a, bi_ref, ctx));
    ctx.biome_id = 99; CHECK(!evaluate_condition(a, bi_ref, ctx));
}

TEST_CASE("material: biome_is supports sorted fast path") {
    Arena a;
    Condition bi{}; bi.kind = ConditionKind::kBiomeIs;
    bi.i0 = 1;
    bi.pool_off = static_cast<int>(a.int_pool.size());
    a.int_pool.push_back(7);
    a.int_pool.push_back(11);
    a.int_pool.push_back(42);
    bi.pool_count = 3;
    int bi_ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(bi);

    SampleContext ctx = base_ctx();
    ctx.biome_id = 42; CHECK(evaluate_condition(a, bi_ref, ctx));
    ctx.biome_id = 8;  CHECK(!evaluate_condition(a, bi_ref, ctx));
}

TEST_CASE("material: biome_is single-entry fast path") {
    Arena a;
    Condition bi{}; bi.kind = ConditionKind::kBiomeIs;
    bi.i0 = 1;
    bi.pool_off = static_cast<int>(a.int_pool.size());
    a.int_pool.push_back(11);
    bi.pool_count = 1;
    int bi_ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(bi);

    SampleContext ctx = base_ctx();
    ctx.biome_id = 11; CHECK(evaluate_condition(a, bi_ref, ctx));
    ctx.biome_id = 12; CHECK(!evaluate_condition(a, bi_ref, ctx));
}

TEST_CASE("material: stone depth matches vanilla threshold shape") {
    Arena a;
    Condition floor{};
    floor.kind = ConditionKind::kStoneDepth;
    floor.i0 = 0;
    floor.i1 = 0;
    floor.i2 = 0;
    floor.i3 = 0;
    int floor_ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(floor);

    SampleContext ctx = base_ctx();
    ctx.stone_depth_floor = 1;
    CHECK(evaluate_condition(a, floor_ref, ctx));
    ctx.stone_depth_floor = 2;
    CHECK(!evaluate_condition(a, floor_ref, ctx));

    Condition adjusted = floor;
    adjusted.i1 = 1;
    adjusted.i2 = 6;
    int adjusted_ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(adjusted);

    ctx.stone_depth_floor = 6;
    ctx.surface_depth = 2;
    ctx.surface_secondary_noise = 1.0;
    CHECK(evaluate_condition(a, adjusted_ref, ctx));
    ctx.stone_depth_floor = 10;
    CHECK(!evaluate_condition(a, adjusted_ref, ctx));
}

TEST_CASE("material: above_y_with_surface uses surface depth") {
    Arena a;
    Condition c{};
    c.kind = ConditionKind::kAboveYWithSurface;
    c.i0 = 60;
    c.i1 = 2;
    c.i2 = 0;
    int ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(c);

    SampleContext ctx = base_ctx();
    ctx.y = 63;
    ctx.surface_depth = 1;
    CHECK(evaluate_condition(a, ref, ctx));
    ctx.y = 61;
    CHECK(!evaluate_condition(a, ref, ctx));
}

TEST_CASE("material: above_y_with_stone_depth adds floor depth") {
    Arena a;
    Condition c{};
    c.kind = ConditionKind::kAboveYWithSurface;
    c.i0 = 64;
    c.i1 = 1;
    c.i2 = 1;
    int ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(c);

    SampleContext ctx = base_ctx();
    ctx.y = 60;
    ctx.stone_depth_floor = 5;
    ctx.surface_depth = 1;
    CHECK(evaluate_condition(a, ref, ctx));
    ctx.stone_depth_floor = 3;
    CHECK(!evaluate_condition(a, ref, ctx));
}

TEST_CASE("material: water condition matches vanilla sentinel and threshold") {
    Arena a;
    Condition c{};
    c.kind = ConditionKind::kWater;
    c.i0 = 0;
    c.i1 = 1;
    c.i2 = 0;
    int ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(c);

    SampleContext ctx = base_ctx();
    ctx.fluid_height = std::numeric_limits<int>::min();
    CHECK(evaluate_condition(a, ref, ctx));

    ctx.fluid_height = 62;
    ctx.surface_depth = 2;
    ctx.y = 64;
    CHECK(evaluate_condition(a, ref, ctx));
    ctx.y = 63;
    CHECK(!evaluate_condition(a, ref, ctx));
}

TEST_CASE("material: above preliminary surface uses precomputed min level") {
    Arena a;
    Condition c{};
    c.kind = ConditionKind::kAbovePreliminarySurface;
    int ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(c);

    SampleContext ctx = base_ctx();
    ctx.min_surface_level = 70;
    ctx.y = 70;
    CHECK(evaluate_condition(a, ref, ctx));
    ctx.y = 69;
    CHECK(!evaluate_condition(a, ref, ctx));
}

TEST_CASE("material: vertical gradient uses positional xoroshiro stream") {
    using lattice::world::gen::rng::Splitter;

    Arena a;
    Condition c{};
    c.kind = ConditionKind::kVerticalGradient;
    c.i0 = 10;
    c.i1 = 20;
    c.s0 = static_cast<std::int64_t>(0x0123456789ABCDEFULL);
    c.s1 = static_cast<std::int64_t>(0x0FEDCBA987654321ULL);
    int ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(c);

    SampleContext ctx = base_ctx();
    ctx.x = 7;
    ctx.y = 15;
    ctx.z = -3;

    const double threshold = static_cast<double>(c.i1 - ctx.y)
                           / static_cast<double>(c.i1 - c.i0);
    Splitter splitter(static_cast<std::uint64_t>(c.s0),
                      static_cast<std::uint64_t>(c.s1));
    auto random = splitter.split(ctx.x, ctx.y, ctx.z);
    const bool expected = static_cast<double>(random.next_float()) < threshold;

    CHECK(evaluate_condition(a, ref, ctx) == expected);
}

TEST_CASE("material: vertical gradient uses positional legacy stream") {
    Arena a;
    Condition c{};
    c.kind = ConditionKind::kVerticalGradient;
    c.i0 = 10;
    c.i1 = 20;
    c.i2 = 1; // LegacyRandomSource.LegacyPositionalRandomFactory
    c.s0 = 0x123456789ABCDELL;
    int ref = static_cast<int>(a.conditions.size());
    a.conditions.push_back(c);

    for (const auto [x, y, z] : std::array<std::array<int, 3>, 4>{
             std::array<int, 3>{7, 15, -3},
             std::array<int, 3>{-19, 11, 41},
             std::array<int, 3>{1024, 19, -2048},
             std::array<int, 3>{-30000, 12, 30000}}) {
        SampleContext ctx = base_ctx();
        ctx.x = x;
        ctx.y = y;
        ctx.z = z;

        constexpr std::uint64_t mask = (1ULL << 48) - 1ULL;
        constexpr std::uint64_t multiplier = 25214903917ULL;
        const std::uint64_t positional = static_cast<std::uint64_t>(
            lattice::world::gen::rng::math_helper_hash_code(x, y, z));
        std::uint64_t state = (positional ^ static_cast<std::uint64_t>(c.s0) ^ multiplier) & mask;
        state = (state * multiplier + 11ULL) & mask;
        const float random = static_cast<float>(static_cast<std::uint32_t>(state >> 24))
                           * 5.9604644775390625e-8f;
        const double threshold = static_cast<double>(c.i1 - y)
                               / static_cast<double>(c.i1 - c.i0);
        CHECK(evaluate_condition(a, ref, ctx) == (static_cast<double>(random) < threshold));
    }
}
