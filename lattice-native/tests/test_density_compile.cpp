#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <bit>
#include <array>
#include <cstdint>
#include <limits>
#include <random>
#include <string>

#include <doctest/doctest.h>
#include "lattice/dispatch.hpp"

#include "world/gen/densityfunction/df_compile.hpp"

using namespace lattice::world::gen::densityfunction;
namespace dfc = lattice::world::gen::densityfunction::dfc;

namespace {

std::uint64_t bits(double value) noexcept {
    return std::bit_cast<std::uint64_t>(value);
}

NodeRef constant(NodeArena& arena, double value) {
    Node n{};
    n.kind = NodeKind::kConstant;
    n.d0 = value;
    return arena.push(n);
}

NodeRef unary(NodeArena& arena, NodeKind kind, NodeRef input) {
    Node n{};
    n.kind = kind;
    n.a = input;
    return arena.push(n);
}

NodeRef binary(NodeArena& arena, NodeKind kind, NodeRef left, NodeRef right) {
    Node n{};
    n.kind = kind;
    n.a = left;
    n.b = right;
    return arena.push(n);
}

NodeRef gradient(NodeArena& arena) {
    Node n{};
    n.kind = NodeKind::kYClampedGradient;
    n.i0 = -16; n.i1 = 16; n.d0 = -16; n.d1 = 16;
    return arena.push(n);
}

void check_once_cache(const CacheState& actual, const CacheState& expected) {
    REQUIRE(actual.cache_once.size() == expected.cache_once.size());
    for (std::size_t i = 0; i < actual.cache_once.size(); ++i) {
        const auto& a = actual.cache_once[i];
        const auto& b = expected.cache_once[i];
        CHECK(a.valid == b.valid);
        if (!a.valid || !b.valid) continue;
        CHECK(bits(a.x) == bits(b.x)); CHECK(bits(a.y) == bits(b.y)); CHECK(bits(a.z) == bits(b.z));
        CHECK(bits(a.value) == bits(b.value));
    }
}

void check_batch_cache(const CacheState& actual, const CacheState& expected) {
    check_once_cache(actual, expected);
    REQUIRE(actual.cache_2d.size() == expected.cache_2d.size());
    for (std::size_t i = 0; i < actual.cache_2d.size(); ++i) {
        const auto& a = actual.cache_2d[i];
        const auto& b = expected.cache_2d[i];
        CHECK(a.valid == b.valid);
        if (!a.valid || !b.valid) continue;
        CHECK(a.x == b.x); CHECK(a.z == b.z); CHECK(bits(a.value) == bits(b.value));
    }
    REQUIRE(actual.flat_cache.size() == expected.flat_cache.size());
    for (std::size_t i = 0; i < actual.flat_cache.size(); ++i) {
        const auto& a = actual.flat_cache[i];
        const auto& b = expected.flat_cache[i];
        CHECK(a.valid == b.valid);
        if (!a.valid || !b.valid) continue;
        CHECK(a.cellX == b.cellX); CHECK(a.cellZ == b.cellZ); CHECK(bits(a.value) == bits(b.value));
    }
}

} // namespace

TEST_CASE("density compiler: flattened arithmetic preserves bits") {
    NodeArena arena;
    const NodeRef x = constant(arena, 1.25);
    const NodeRef y = constant(arena, -2.5);
    const NodeRef sum = binary(arena, NodeKind::kAdd, x, y);
    const NodeRef square = unary(arena, NodeKind::kSquare, sum);
    const NodeRef root = binary(arena, NodeKind::kMul, square, square);
    arena.root = root;

    const dfc::CompileResult compiled = dfc::compile(arena, root);
    REQUIRE(compiled);
    CHECK(compiled.program.code.size() == 1);
    CHECK(compiled.program.value_count == 1);

    const Context ctx{17.0, -31.0, 5.0};
    CHECK(bits(dfc::evaluate(compiled.program, arena, ctx)) == bits(evaluate(arena, ctx)));
}

TEST_CASE("density compiler: coordinate and scalar transforms preserve bits") {
    NodeArena arena;
    Node gradient{};
    gradient.kind = NodeKind::kYClampedGradient;
    gradient.i0 = -32;
    gradient.i1 = 64;
    gradient.d0 = -2.0;
    gradient.d1 = 3.0;
    const NodeRef gradient_ref = arena.push(gradient);

    Node clamp{};
    clamp.kind = NodeKind::kClamp;
    clamp.a = gradient_ref;
    clamp.d0 = -1.0;
    clamp.d1 = 1.0;
    const NodeRef root = arena.push(clamp);
    arena.root = root;

    const dfc::CompileResult compiled = dfc::compile(arena, root);
    REQUIRE(compiled);
    for (const Context ctx : {Context{0.0, -64.0, 0.0}, Context{0.0, 0.0, 0.0}, Context{0.0, 128.0, 0.0}}) {
        CHECK(bits(dfc::evaluate(compiled.program, arena, ctx)) == bits(evaluate(arena, ctx)));
    }
}

TEST_CASE("density compiler: nonconstant instruction stream preserves bits") {
    NodeArena arena;
    Node gradient{};
    gradient.kind = NodeKind::kYClampedGradient;
    gradient.i0 = -10;
    gradient.i1 = 10;
    gradient.d0 = -2.0;
    gradient.d1 = 3.0;
    const NodeRef source = arena.push(gradient);
    const NodeRef abs_ref = unary(arena, NodeKind::kAbs, source);
    const NodeRef square_ref = unary(arena, NodeKind::kSquare, abs_ref);
    const NodeRef cube_ref = unary(arena, NodeKind::kCube, square_ref);
    const NodeRef half_ref = unary(arena, NodeKind::kHalfNegative, cube_ref);
    const NodeRef quarter_ref = unary(arena, NodeKind::kQuarterNegative, half_ref);
    const NodeRef invert_ref = unary(arena, NodeKind::kInvert, quarter_ref);
    const NodeRef squeeze_ref = unary(arena, NodeKind::kSqueeze, invert_ref);

    Node map{};
    map.kind = NodeKind::kMapRange;
    map.a = source;
    map.d0 = -2.0;
    map.d1 = 3.0;
    map.d2 = -1.0;
    map.d3 = 1.0;
    const NodeRef map_ref = arena.push(map);

    const NodeRef add_ref = binary(arena, NodeKind::kAdd, squeeze_ref, map_ref);
    const NodeRef mul_ref = binary(arena, NodeKind::kMul, add_ref, source);
    const NodeRef min_ref = binary(arena, NodeKind::kMin, mul_ref, map_ref);
    const NodeRef max_ref = binary(arena, NodeKind::kMax, min_ref, source);

    Node lerp{};
    lerp.kind = NodeKind::kLerp;
    lerp.a = source;
    lerp.b = map_ref;
    lerp.c = max_ref;
    const NodeRef lerp_ref = arena.push(lerp);
    Node clamp{};
    clamp.kind = NodeKind::kClamp;
    clamp.a = lerp_ref;
    clamp.d0 = -0.75;
    clamp.d1 = 0.75;
    const NodeRef root = arena.push(clamp);
    arena.root = root;

    const dfc::CompileResult compiled = dfc::compile(arena, root);
    REQUIRE(compiled);
    CHECK(compiled.program.code.size() > 1);
    for (const Context ctx : {Context{0.0, -20.0, 0.0}, Context{0.0, -5.0, 0.0},
                              Context{0.0, 0.0, 0.0}, Context{0.0, 7.0, 0.0},
                              Context{0.0, 20.0, 0.0}}) {
        CHECK(bits(dfc::evaluate(compiled.program, arena, ctx)) == bits(evaluate(arena, ctx)));
    }
}

TEST_CASE("density compiler: DAG reuse and dead nodes are compacted") {
    NodeArena arena;
    Node gradient{};
    gradient.kind = NodeKind::kYClampedGradient;
    gradient.i0 = 0;
    gradient.i1 = 10;
    gradient.d0 = -1.0;
    gradient.d1 = 1.0;
    const NodeRef shared = arena.push(gradient);
    const NodeRef root = binary(arena, NodeKind::kAdd, shared, shared);
    arena.root = root;
    static_cast<void>(constant(arena, 123.0));

    const dfc::CompileResult compiled = dfc::compile(arena, root);
    REQUIRE(compiled);
    CHECK(compiled.program.code.size() == 2);
    CHECK(compiled.program.value_count == 2);
    CHECK(bits(dfc::evaluate(compiled.program, arena, Context{0.0, 4.0, 0.0}))
          == bits(evaluate(arena, Context{0.0, 4.0, 0.0})));
}

TEST_CASE("density compiler: installed program is used by the production evaluator") {
    NodeArena arena;
    Node gradient{};
    gradient.kind = NodeKind::kYClampedGradient;
    gradient.i0 = -8;
    gradient.i1 = 8;
    gradient.d0 = -2.0;
    gradient.d1 = 2.0;
    const NodeRef source = arena.push(gradient);
    const NodeRef root = unary(arena, NodeKind::kSqueeze, source);
    arena.root = root;

    const Context ctx{3.0, 5.0, -7.0};
    const std::uint64_t recursive_bits = bits(evaluate(arena, root, ctx));
    CHECK(dfc::install(arena, root));
    REQUIRE(arena.compiled_program);
    CHECK(arena.compiled_program_root == root);
    CHECK(bits(evaluate(arena, root, ctx)) == recursive_bits);
    CHECK(bits(evaluate(arena, ctx)) == recursive_bits);
}

TEST_CASE("density compiler: scalar program evaluation reuses CacheState registers") {
    NodeArena arena;
    Node gradient{};
    gradient.kind = NodeKind::kYClampedGradient;
    gradient.i0 = -8;
    gradient.i1 = 8;
    gradient.d0 = -2.0;
    gradient.d1 = 2.0;
    const NodeRef source = arena.push(gradient);
    const NodeRef root = unary(arena, NodeKind::kSqueeze, source);
    arena.root = root;
    REQUIRE(dfc::install(arena, root));

    CacheState cache;
    cache.resize_for(arena);
    Context ctx{3.0, 5.0, -7.0};
    ctx.cache = &cache;
    const double first = evaluate(arena, root, ctx);
    const auto* registers = cache.program_batch.values.data();
    const auto capacity = cache.program_batch.values.size();
    REQUIRE(capacity >= arena.compiled_program->value_count);
    CHECK(bits(evaluate(arena, root, ctx)) == bits(first));
    CHECK(cache.program_batch.values.data() == registers);
    CHECK(cache.program_batch.values.size() == capacity);
}

TEST_CASE("density compiler: cache root installs with a cacheless specialization") {
    NodeArena arena;
    const NodeRef value = constant(arena, 2.0);
    Node cache{};
    cache.kind = NodeKind::kCacheOnce;
    cache.a = value;
    const NodeRef root = arena.push(cache);

    const dfc::CompileResult compiled = dfc::compile(arena, root);
    REQUIRE(compiled);
    CHECK(compiled.program.code.size() == 3);
    CHECK(compiled.program.opaque_count() == 0);
    REQUIRE(compiled.program.cacheless);
    CHECK(compiled.program.cacheless->code.size() == 1);
    REQUIRE(dfc::install(arena, root));
    CHECK(evaluate(arena, root, Context{0, 0, 0}) == 2.0);
}

TEST_CASE("density compiler: malformed roots and cycles fail explicitly") {
    NodeArena invalid_root;
    CHECK(dfc::compile(invalid_root, 0).error == dfc::CompileError::kInvalidRoot);

    NodeArena invalid_operand;
    Node unary_node{};
    unary_node.kind = NodeKind::kAbs;
    unary_node.a = 7;
    const NodeRef invalid_ref = invalid_operand.push(unary_node);
    CHECK(dfc::compile(invalid_operand, invalid_ref).error == dfc::CompileError::kInvalidOperand);

    NodeArena cycle;
    Node cycle_node{};
    cycle_node.kind = NodeKind::kAbs;
    cycle_node.a = 0;
    const NodeRef cycle_ref = cycle.push(cycle_node);
    CHECK(dfc::compile(cycle, cycle_ref).error == dfc::CompileError::kInvalidOperand);
}

TEST_CASE("density compiler: clamp and squeeze preserve NaN and signed zero") {
    NodeArena arena;
    const NodeRef nan_value = constant(arena, std::numeric_limits<double>::quiet_NaN());
    Node clamp{};
    clamp.kind = NodeKind::kClamp;
    clamp.a = nan_value;
    clamp.d0 = -1.0;
    clamp.d1 = 1.0;
    const NodeRef root = arena.push(clamp);
    arena.root = root;

    const dfc::CompileResult compiled = dfc::compile(arena, root);
    REQUIRE(compiled);
    const Context ctx{0.0, 0.0, 0.0};
    CHECK(bits(dfc::evaluate(compiled.program, arena, ctx)) == bits(evaluate(arena, ctx)));

    NodeArena zero_arena;
    const NodeRef negative_zero = constant(zero_arena, -0.0);
    const NodeRef squeeze_ref = unary(zero_arena, NodeKind::kSqueeze, negative_zero);
    zero_arena.root = squeeze_ref;
    const dfc::CompileResult zero_compiled = dfc::compile(zero_arena, squeeze_ref);
    REQUIRE(zero_compiled);
    CHECK(bits(dfc::evaluate(zero_compiled.program, zero_arena, ctx)) == bits(evaluate(zero_arena, ctx)));
}

TEST_CASE("density compiler: mixed arithmetic folds constants while preserving cache control flow") {
    NodeArena arena;
    const auto sum = binary(arena, NodeKind::kAdd, constant(arena, 2), constant(arena, 3));
    const auto cached = unary(arena, NodeKind::kCacheOnce, gradient(arena));
    const auto root = binary(arena, NodeKind::kAdd, sum, cached);
    const auto compiled = dfc::compile(arena, root);
    REQUIRE(compiled);
    CHECK(compiled.program.code.size() == 5);
    CHECK(compiled.program.opaque_count() == 0);
    CHECK(compiled.program.code[0].op == dfc::Op::kConstant);
    CHECK(compiled.program.code[0].imm0 == 5.0);
    std::array<double, 3> scratch; // Intentionally uninitialised; no leaf reads operands.
    CHECK(dfc::evaluate(compiled.program, arena, Context{0, 4, 0}, scratch.data(), scratch.size()) == 9.0);
}

TEST_CASE("density compiler: multiplication and range preserve lazy cache effects") {
    for (const bool range : {false, true}) {
        NodeArena arena;
        const auto source = gradient(arena);
        const auto in = unary(arena, NodeKind::kCacheOnce, constant(arena, 7));
        const auto out = unary(arena, NodeKind::kCacheOnce, constant(arena, -3));
        Node n{};
        n.kind = range ? NodeKind::kRangeChoice : NodeKind::kMul;
        n.a = source; n.b = in; n.c = out; n.d0 = 0; n.d1 = 1;
        const auto root = arena.push(n);
        REQUIRE(dfc::install(arena, root));
        for (const double y : {-1.0, -0.0, 0.0, 0.5, 1.0}) {
            CacheState expected, actual;
            expected.resize_for(arena); actual.resize_for(arena);
            const auto want = evaluate_node(arena, root, Context{1, y, 2, &expected});
            const auto got = evaluate(arena, root, Context{1, y, 2, &actual});
            CHECK(bits(got) == bits(want));
            check_once_cache(actual, expected);
            const bool choose_in = y >= 0 && y < 1;
            CHECK(actual.cache_once[0].valid == (range ? choose_in : y != 0));
            CHECK(actual.cache_once[1].valid == (range && !choose_in));
        }
    }
}

TEST_CASE("density compiler: repeated stateful DAG observes intervening coordinate changes") {
    NodeArena arena;
    const auto cached = unary(arena, NodeKind::kCacheOnce, gradient(arena));
    const auto shared = unary(arena, NodeKind::kHalfNegative, cached);
    Node find{};
    find.kind = NodeKind::kFindTopSurface;
    find.a = cached; find.b = constant(arena, 8); find.i0 = 0; find.i1 = 4;
    const auto surface = arena.push(find);
    Node lerp{};
    lerp.kind = NodeKind::kLerp; lerp.a = shared; lerp.b = surface; lerp.c = shared;
    const auto root = arena.push(lerp);
    REQUIRE(dfc::install(arena, root));
    CHECK(arena.compiled_program->opaque_count() == 1);
    CacheState expected, actual;
    expected.resize_for(arena); actual.resize_for(arena);
    CHECK(bits(evaluate(arena, root, Context{1, 2, 3, &actual}))
          == bits(evaluate_node(arena, root, Context{1, 2, 3, &expected})));
    check_once_cache(actual, expected);
    CHECK(actual.cache_once[0].y == 2);
    CHECK(actual.cache_once[0].value == 2);
}

TEST_CASE("density compiler: cache hits never pre-evaluate wrapped inputs") {
    for (const auto kind : {NodeKind::kCache2D, NodeKind::kCacheOnce, NodeKind::kFlatCache,
                            NodeKind::kCacheAllInCell, NodeKind::kInterpolated}) {
        NodeArena arena;
        const auto inner = unary(arena, NodeKind::kCacheOnce, gradient(arena));
        const auto root = unary(arena, kind, inner);
        REQUIRE(dfc::install(arena, root));
        CacheState expected, actual;
        expected.resize_for(arena); actual.resize_for(arena);
        const int slot = arena.nodes[root].cache_slot_id;
        const std::array<double, 1> cell_values{123};
        if (kind == NodeKind::kCacheAllInCell) {
            for (auto* cache : {&actual, &expected}) {
                cache->cache_all_in_cell_arrays[slot] = cell_values.data();
                cache->cache_all_in_cell_array_lengths[slot] = cell_values.size();
            }
        }
        if (kind == NodeKind::kInterpolated) {
            for (auto* cache : {&actual, &expected}) {
                cache->is_in_interpolation_loop = true;
                cache->interpolators[slot].result = 456;
            }
        }
        for (const double y : {2.0, 2.0, 3.0}) {
            actual.cache_once[0].valid = false;
            expected.cache_once[0].valid = false;
            Context ac{1, y, 3, &actual, 0, 0, 0, 0, 0, 1, 1};
            Context ec = ac; ec.cache = &expected;
            CHECK(bits(evaluate(arena, root, ac)) == bits(evaluate_node(arena, root, ec)));
            check_once_cache(actual, expected);
            if (kind == NodeKind::kCacheAllInCell || kind == NodeKind::kInterpolated)
                CHECK_FALSE(actual.cache_once[0].valid);
        }
        CHECK(bits(evaluate(arena, root, Context{1, 4, 3})) == bits(evaluate_node(arena, root, Context{1, 4, 3})));
    }
}

TEST_CASE("density compiler: input-free cell leaf and moved arenas preserve source references") {
    NodeArena arena;
    Node n{}; n.kind = NodeKind::kCacheAllInCell;
    arena.root = arena.push(n);
    REQUIRE(dfc::install(arena, arena.root));
    NodeArena copy = arena;
    NodeArena moved = std::move(arena);
    const std::array<double, 1> cell{19};
    for (auto* source : {&copy, &moved}) {
        CacheState cache; cache.resize_for(*source);
        cache.cache_all_in_cell_arrays[0] = cell.data();
        cache.cache_all_in_cell_array_lengths[0] = 1;
        CHECK(evaluate(*source, Context{0, 0, 0, &cache, 0, 0, 0, 0, 0, 1, 1}) == 19);
        CHECK(evaluate(*source, Context{0, 0, 0}) == 0);
        constant(*source, 3);
        CHECK_FALSE(source->compiled_program);
    }
}

TEST_CASE("density compiler: same-ref binary operations preserve signed zero and NaN") {
    for (const double value : {-0.0, 0.0, std::numeric_limits<double>::infinity(),
                               std::numeric_limits<double>::quiet_NaN()}) {
        for (const auto kind : {NodeKind::kAdd, NodeKind::kMul, NodeKind::kMin, NodeKind::kMax}) {
            NodeArena arena;
            const auto child = unary(arena, NodeKind::kCacheOnce, constant(arena, value));
            const auto root = binary(arena, kind, child, child);
            REQUIRE(dfc::install(arena, root));
            CHECK(arena.compiled_program->opaque_count() == 0);
            CHECK(bits(evaluate(arena, root, Context{0, 0, 0})) == bits(evaluate_node(arena, root, Context{0, 0, 0})));
        }
    }
}

TEST_CASE("density compiler: all opaque families preserve seeded arithmetic parity") {
    namespace noise = lattice::world::gen::noise;
    noise::PerlinNoiseSampler perlin{};
    for (int i = 0; i < 256; ++i) perlin.permutation[i] = static_cast<std::uint8_t>(i * 23);
    const std::array<noise::PerlinNoiseSampler, 1> octaves{perlin};
    const std::array<double, 1> amplitudes{1};
    noise::OctavePerlinNoiseSampler octave{octaves.data(), amplitudes.data(), 1, 1, 1};
    noise::DoublePerlinNoiseSampler sampler{octave, octave, 1};
    noise::SimplexNoiseSampler simplex{};
    for (int i = 0; i < 256; ++i) simplex.permutation[i] = static_cast<std::uint8_t>(i * 23);
    noise::InterpolatedNoiseSampler old_noise{&octave, &octave, &octave, 1, 1, 80, 160, 8};
    beardifier::BeardifierData beard;
    beard.junctions.push_back({2, 4, 6});
    std::mt19937 random(0xDFC);
    for (const auto kind : {NodeKind::kNoise, NodeKind::kShiftedNoise, NodeKind::kShiftA, NodeKind::kShiftB,
                            NodeKind::kShift, NodeKind::kWeirdScaledSampler, NodeKind::kEndIslands,
                            NodeKind::kInterpolatedNoise, NodeKind::kBeardifier, NodeKind::kBlendAlpha,
                            NodeKind::kBlendOffset, NodeKind::kBlendDensity, NodeKind::kSpline}) {
        NodeArena arena;
        const auto y = gradient(arena);
        Spline fixed{}; fixed.kind = SplineKind::kFixedFloat; fixed.fixed_value = 2;
        const auto fixed_ref = arena.push_spline(fixed);
        Spline spline{}; spline.kind = SplineKind::kImpl; spline.location_function = y;
        spline.breakpoint_count = 1; spline.breakpoints_start = arena.reserve_spline_breakpoints(1);
        arena.spline_breakpoints[0] = {0, 0.5f, fixed_ref};
        Node n{}; n.kind = kind; n.a = y; n.b = constant(arena, 0); n.c = y;
        n.d0 = 0.5; n.d1 = 0.25; n.noise_ptr = &sampler; n.simplex_ptr = &simplex;
        n.interp_noise_ptr = &old_noise; n.beardifier_ptr = &beard; n.i0 = arena.push_spline(spline);
        auto root = arena.push(n);
        std::vector<NodeRef> refs{y, root};
        for (int i = 0; i < 12; ++i) {
            root = binary(arena, i % 2 == 0 ? NodeKind::kAdd : NodeKind::kMin,
                          root, refs[random() % refs.size()]);
            refs.push_back(root);
        }
        REQUIRE(dfc::install(arena, root));
        CHECK((arena.compiled_program->opaque_count() > 0) == (kind == NodeKind::kEndIslands
            || kind == NodeKind::kBeardifier || kind == NodeKind::kBlendAlpha
            || kind == NodeKind::kBlendOffset || kind == NodeKind::kSpline));
        for (int i = 0; i < 32; ++i) {
            const Context ctx{static_cast<double>(random() % 40), static_cast<double>(random() % 32) - 16,
                              -static_cast<double>(random() % 40)};
            CHECK(bits(evaluate(arena, root, ctx)) == bits(evaluate_node(arena, root, ctx)));
        }
    }
}

TEST_CASE("density compiler: validates operands and cycles inside opaque graphs") {
    NodeArena arena;
    Node n{}; n.kind = NodeKind::kCacheOnce; n.a = 99;
    const auto root = arena.push(n);
    CHECK(dfc::compile(arena, root).error == dfc::CompileError::kInvalidOperand);
    arena.nodes[root].a = root;
    CHECK(dfc::compile(arena, root).error == dfc::CompileError::kInvalidOperand);
    arena.nodes[root].kind = static_cast<NodeKind>(255);
    CHECK(dfc::compile(arena, root).error == dfc::CompileError::kUnsupportedNode);

    NodeArena spline_arena;
    Spline spline{}; spline.kind = SplineKind::kImpl; spline.breakpoint_count = 1;
    spline.breakpoints_start = spline_arena.reserve_spline_breakpoints(1);
    spline.location_function = 0;
    const auto spline_ref = spline_arena.push_spline(spline);
    spline_arena.spline_breakpoints[0] = {0, 0, spline_ref};
    Node spline_node{}; spline_node.kind = NodeKind::kSpline; spline_node.i0 = spline_ref;
    const auto spline_root = spline_arena.push(spline_node);
    CHECK(dfc::compile(spline_arena, spline_root).error == dfc::CompileError::kInvalidOperand);
    spline_arena.splines[0].breakpoint_count = 0;
    CHECK(dfc::compile(spline_arena, spline_root).error == dfc::CompileError::kInvalidOperand);
    spline_arena.splines[0].location_function = kNullRef;
    REQUIRE(dfc::install(spline_arena, spline_root));
    CHECK(evaluate(spline_arena, spline_root, Context{0, 0, 0}) == 0);
    spline_arena.splines[0].breakpoint_count = 1;
    spline_arena.splines[0].location_function = constant(spline_arena, 1);
    CHECK(dfc::compile(spline_arena, spline_root).error == dfc::CompileError::kInvalidOperand);
    spline_arena.splines[0].breakpoints_start = 100;
    CHECK(dfc::compile(spline_arena, spline_root).error == dfc::CompileError::kInvalidOperand);
}

TEST_CASE("density compiler: both column entries execute programs with scalar state order") {
    NodeArena arena;
    const auto y = gradient(arena);
    const auto cache2d = unary(arena, NodeKind::kCache2D, y);
    const auto cached = unary(arena, NodeKind::kCacheOnce, y);
    Node choice{};
    choice.kind = NodeKind::kRangeChoice; choice.a = y; choice.b = cache2d;
    choice.c = constant(arena, -5); choice.d0 = 0; choice.d1 = 4;
    const auto range = arena.push(choice);
    Node find{}; find.kind = NodeKind::kFindTopSurface;
    find.a = cached; find.b = constant(arena, 8); find.i0 = 0; find.i1 = 4;
    const auto surface = arena.push(find);
    Node lerp{}; lerp.kind = NodeKind::kLerp;
    lerp.a = cached; lerp.b = surface; lerp.c = cached;
    const auto repeated = arena.push(lerp);
    const auto product = binary(arena, NodeKind::kMul, y,
        unary(arena, NodeKind::kCacheOnce, constant(arena, 7)));
    const auto root = binary(arena, NodeKind::kAdd, product, binary(arena, NodeKind::kAdd, range, repeated));
    REQUIRE(dfc::install(arena, root));

    for (bool fallback : {false, true}) {
        for (int count : {1, 5, 9}) {
            for (double dy : {0.0, -0.5, 0.25, 1.0}) {
                for (bool with_cache : {false, true}) {
                    CacheState actual, expected;
                    actual.resize_for(arena); expected.resize_for(arena);
                    REQUIRE(actual.set_execution_stats_enabled(true));
                    std::vector<double> out(static_cast<std::size_t>(count));
                    if (fallback) evaluate_y_column_fallback(arena, root, -3, 0, 4, dy, -1, 2, count,
                                                            with_cache ? &actual : nullptr, out.data());
                    else evaluate_y_column(arena, root, -3, 0, 4, dy, -1, 2, count,
                                           with_cache ? &actual : nullptr, out.data());
                    for (int iy = 0; iy < count; ++iy) {
                        Context ctx{-3, 0.0 + static_cast<double>(iy) * dy, 4,
                                    with_cache ? &expected : nullptr, -1, 2};
                        CHECK(bits(out[iy]) == bits(evaluate_node(arena, root, ctx)));
                    }
                    check_batch_cache(actual, expected);
                    CHECK(actual.execution_stats->compiled_column_calls == (with_cache ? 1 : 0));
                    CHECK(actual.execution_stats->compiled_points == (with_cache ? count : 0));
                    CHECK(actual.execution_stats->avx2_success == 0);
                    CHECK(actual.execution_stats->generic_success == 0);
                    CHECK(actual.execution_stats->point_fallback == 0);
                    if (with_cache && dy == 0) CHECK_FALSE(actual.cache_once[1].valid);
                }
            }
        }
    }
}

TEST_CASE("density compiler: grid preserves layout warm caches and active interpolation") {
    NodeArena arena;
    const auto y = gradient(arena);
    const auto cached = unary(arena, NodeKind::kCache2D, y);
    const auto flat = unary(arena, NodeKind::kFlatCache, y);
    const auto interpolated = unary(arena, NodeKind::kInterpolated, unary(arena, NodeKind::kCacheOnce, y));
    const auto root = binary(arena, NodeKind::kAdd, cached, binary(arena, NodeKind::kAdd, flat, interpolated));
    REQUIRE(dfc::install(arena, root));
    for (bool with_cache : {false, true}) {
        CacheState actual, expected;
        actual.resize_for(arena); expected.resize_for(arena);
        for (auto* cache : {&actual, &expected}) {
            cache->cache_2d[0].valid = true;
            cache->cache_2d[0].x = -4; cache->cache_2d[0].z = 4; cache->cache_2d[0].value = 900;
            cache->is_in_interpolation_loop = true;
            cache->interpolators[0].result = 100;
        }
        REQUIRE(actual.set_execution_stats_enabled(true));
        std::array<double, 18> out{};
        evaluate_grid(arena, root, -3.25, 2, 4.75, 0.5, -0.25, -1, -1, 2, 3, 3, 2,
                      with_cache ? &actual : nullptr, out.data());
        for (int iy = 0; iy < 3; ++iy) {
            for (int iz = 0; iz < 2; ++iz) {
                for (int ix = 0; ix < 3; ++ix) {
                    Context ctx{-3.25 + static_cast<double>(ix) * 0.5,
                                2.0 + static_cast<double>(iy) * -0.25,
                                4.75 + static_cast<double>(iz) * -1.0,
                                with_cache ? &expected : nullptr, -1 + ix, 2 + iz};
                    CHECK(bits(out[(iy * 2 + iz) * 3 + ix]) == bits(evaluate_node(arena, root, ctx)));
                }
            }
        }
        check_batch_cache(actual, expected);
        CHECK_FALSE(actual.cache_once[0].valid);
        CHECK(actual.is_in_interpolation_loop);
        CHECK(actual.execution_stats->cache_clears == 0);
        CHECK(actual.execution_stats->compiled_grid_calls == (with_cache ? 1 : 0));
        CHECK(actual.execution_stats->compiled_points == (with_cache ? 18 : 0));
        std::array<std::int64_t, kExecutionStatsLongCount> snapshot{};
        snapshot_execution_stats(actual, snapshot.data(), snapshot.size());
        CHECK(snapshot[kExecutionStatsBaseLongCount] == (with_cache ? 1 : 0));
        CHECK(snapshot[kExecutionStatsBaseLongCount + 1] == 0);
        CHECK(snapshot[kExecutionStatsBaseLongCount + 2] == (with_cache ? 18 : 0));
    }
}

TEST_CASE("density compiler: multi-root programs preserve XZY ordering and reuse registers") {
    NodeArena arena;
    const auto cached = unary(arena, NodeKind::kCache2D, gradient(arena));
    const auto small = constant(arena, 3);
    const auto first = binary(arena, NodeKind::kAdd, cached, small);
    const auto second = binary(arena, NodeKind::kAdd, first, unary(arena, NodeKind::kFlatCache, gradient(arena)));
    Node unknown{}; unknown.kind = static_cast<NodeKind>(255);
    const auto failed = arena.push(unknown);
    arena.root = small;
    REQUIRE(dfc::install(arena, small));
    arena.batch_roots = {first, second, first, failed, small};
    REQUIRE(dfc::install_batch(arena) == 4);
    CHECK(dfc::find_program(arena, small) == arena.compiled_program.get());
    REQUIRE(dfc::find_program(arena, first));
    REQUIRE(dfc::find_program(arena, second));
    CHECK_FALSE(dfc::find_program(arena, failed));
    CHECK_FALSE(dfc::find_program(arena, -1));
    CHECK_FALSE(dfc::find_program(arena, 10000));

    CacheState scratch;
    scratch.resize_for(arena);
    std::array<double, 5> column{};
    evaluate_y_column(arena, small, 1, 2, 3, 1, 0, 0, 5, &scratch, column.data());
    CHECK(scratch.program_batch.values.size() == 1);
    evaluate_y_column(arena, second, 1, 2, 3, 1, 0, 0, 5, &scratch, column.data());
    const auto size = scratch.program_batch.values.size();
    const auto* registers = scratch.program_batch.values.data();
    CHECK(size > 1);
    scratch.clear();
    evaluate_y_column(arena, small, 1, 2, 3, 1, 0, 0, 5, &scratch, column.data());
    CHECK(scratch.program_batch.values.size() == size);
    CHECK(scratch.program_batch.values.data() == registers);

    CacheState actual, expected;
    actual.resize_for(arena); expected.resize_for(arena);
    REQUIRE(actual.set_execution_stats_enabled(true));
    const std::array<NodeRef, 6> roots{first, second, first, small, failed, kNullRef};
    std::array<double, 6 * 2 * 2 * 5> out{};
    evaluate_grid_roots(arena, roots.data(), static_cast<int>(roots.size()),
                        1.25, 3, -2.5, 1, -0.5, 1, -1, 2, 2, 5, 2, &actual, out.data());
    for (int ix = 0; ix < 2; ++ix) {
        for (int iz = 0; iz < 2; ++iz) {
            expected.clear_evaluation_caches();
            for (std::size_t r = 0; r < roots.size(); ++r) {
                for (int iy = 0; iy < 5; ++iy) {
                    Context ctx{1.25 + static_cast<double>(ix), 3.0 + static_cast<double>(iy) * -0.5,
                                -2.5 + static_cast<double>(iz), &expected, -1 + ix, 2 + iz};
                    CHECK(bits(out[r * 20 + (ix * 2 + iz) * 5 + iy]) == bits(evaluate_node(arena, roots[r], ctx)));
                }
            }
        }
    }
    check_batch_cache(actual, expected);
    CHECK(actual.execution_stats->compiled_column_calls == 16);
    CHECK(actual.execution_stats->compiled_points == 80);
    CHECK(actual.execution_stats->point_fallback == 4);

    NodeArena copied = arena;
    CHECK(dfc::find_program(copied, second) == dfc::find_program(arena, second));
    constant(arena, 7);
    CHECK_FALSE(dfc::find_program(arena, small));
    CHECK_FALSE(dfc::find_program(arena, second));
    CHECK(arena.compiled_batch_programs.empty());
    REQUIRE(dfc::install(copied, small));
    CHECK_FALSE(dfc::find_program(copied, second));
}

TEST_CASE("density compiler: empty batches and invalid roots never execute a program") {
    NodeArena arena;
    const auto root = constant(arena, 3);
    REQUIRE(dfc::install(arena, root));
    CacheState cache;
    REQUIRE(cache.set_execution_stats_enabled(true));
    std::array<double, 3> out{9, 9, 9};
    evaluate_y_column(arena, root, 0, 0, 0, 1, 0, 0, 0, &cache, out.data());
    evaluate_y_column_fallback(arena, root, 0, 0, 0, 1, 0, 0, 3, &cache, nullptr);
    evaluate_grid(arena, root, 0, 0, 0, 1, 1, 1, 0, 0, 1, 0, 1, &cache, out.data());
    CHECK(out[0] == 9);
    evaluate_y_column(arena, -1, 0, 0, 0, 1, 0, 0, 3, &cache, out.data());
    CHECK(out == std::array<double, 3>{0, 0, 0});
    evaluate_grid(arena, 10000, 0, 0, 0, 1, 1, 1, 0, 0, 3, 1, 1, &cache, out.data());
    CHECK(out == std::array<double, 3>{0, 0, 0});
    CHECK(cache.execution_stats->compiled_points == 0);
    CHECK(cache.program_batch.values.empty());
}


TEST_CASE("density compiler: cache and branch joins never reuse skipped definitions") {
    for (int shape = 0; shape < 3; ++shape) {
        NodeArena arena;
        const auto y = gradient(arena);
        const auto cached = unary(arena, NodeKind::kCacheOnce, y);
        NodeRef left = cached;
        if (shape != 0) {
            Node branch{}; branch.kind = NodeKind::kRangeChoice;
            branch.a = y; branch.b = cached; branch.c = unary(arena, NodeKind::kSquare, y);
            branch.d0 = 0; branch.d1 = 1;
            left = arena.push(branch);
            if (shape == 2) left = binary(arena, NodeKind::kMul, y, left);
        }
        const auto root = binary(arena, NodeKind::kAdd, left, y);
        const auto compiled = dfc::compile(arena, root);
        REQUIRE(compiled);
        CacheState actual, expected;
        actual.resize_for(arena); expected.resize_for(arena);
        for (auto* state : {&actual, &expected}) {
            auto& entry = state->cache_once[0];
            entry.valid = true; entry.x = 1; entry.y = 0; entry.z = 2; entry.value = 900;
        }
        std::vector<double> scratch(compiled.program.value_count, -12345);
        for (const double yy : {0.0, 1.0, 0.5, -1.0, 0.0}) {
            CHECK(bits(dfc::evaluate(compiled.program, arena, Context{1, yy, 2, &actual}, scratch.data(), scratch.size()))
                == bits(evaluate_node(arena, root, Context{1, yy, 2, &expected})));
            check_once_cache(actual, expected);
        }
    }
}

TEST_CASE("density compiler: cacheless elimination folds while warm state remains authoritative") {
    for (const auto kind : {NodeKind::kCache2D, NodeKind::kCacheOnce, NodeKind::kFlatCache,
                            NodeKind::kCacheAllInCell, NodeKind::kInterpolated}) {
        NodeArena arena;
        const auto inner = binary(arena, NodeKind::kAdd, constant(arena, 2), constant(arena, 3));
        const auto cached = unary(arena, kind, inner);
        const auto root = binary(arena, NodeKind::kAdd, cached, constant(arena, 7));
        const auto compiled = dfc::compile(arena, root);
        REQUIRE(compiled); REQUIRE(compiled.program.cacheless);
        CHECK(compiled.program.cacheless->code.size() == 1);
        CHECK(compiled.program.cacheless->eliminated_caches == 1);
        CHECK(dfc::evaluate(compiled.program, arena, Context{0, 0, 0}) == 12);
        CacheState actual, expected;
        actual.resize_for(arena); expected.resize_for(arena);
        for (auto* state : {&actual, &expected}) {
            if (kind == NodeKind::kCache2D) { auto& e = state->cache_2d[0]; e.valid = true; e.value = 900; }
            if (kind == NodeKind::kCacheOnce) { auto& e = state->cache_once[0]; e.valid = true; e.value = 900; }
            if (kind == NodeKind::kFlatCache) { auto& e = state->flat_cache[0]; e.valid = true; e.value = 900; }
            if (kind == NodeKind::kCacheAllInCell) state->cache_all_in_cell[0].get_or_insert(0) = 900;
            if (kind == NodeKind::kInterpolated) { state->is_in_interpolation_loop = true; state->interpolators[0].result = 900; }
        }
        CHECK(dfc::evaluate(compiled.program, arena, Context{0, 0, 0, &actual}) == 907);
        CHECK(evaluate_node(arena, root, Context{0, 0, 0, &expected}) == 907);
        check_batch_cache(actual, expected);
    }
}

TEST_CASE("density compiler: nested shared cache slots and truncated cell keys preserve state") {
    for (const auto kind : {NodeKind::kCache2D, NodeKind::kCacheOnce, NodeKind::kFlatCache, NodeKind::kCacheAllInCell}) {
        NodeArena arena;
        const auto y = gradient(arena);
        const auto a = unary(arena, kind, y);
        const auto b = unary(arena, kind, binary(arena, NodeKind::kAdd, a, constant(arena, 7)));
        arena.nodes[b].cache_slot_id = arena.nodes[a].cache_slot_id;
        const auto root = binary(arena, NodeKind::kAdd, b, a);
        const auto compiled = dfc::compile(arena, root); REQUIRE(compiled);
        CacheState actual, expected; actual.resize_for(arena); expected.resize_for(arena);
        for (int i = 0; i < 40; ++i) {
            const double yv = (i % 5 == 0) ? 65536.0 : static_cast<double>(i % 13);
            Context ac{-0.75, yv, 2.5, &actual, i % 4, i % 7};
            Context ec = ac; ec.cache = &expected;
            CHECK(bits(dfc::evaluate(compiled.program, arena, ac)) == bits(evaluate_node(arena, root, ec)));
            check_batch_cache(actual, expected);
            if (kind == NodeKind::kCacheAllInCell) {
                const auto& want = expected.cache_all_in_cell[0];
                CHECK(actual.cache_all_in_cell[0].used == want.used);
                for (const auto& e : want.entries) if (e.generation == want.generation) {
                    auto* v = actual.cache_all_in_cell[0].find(e.key); REQUIRE(v); CHECK(bits(*v) == bits(e.value));
                }
            }
        }
    }
}


TEST_CASE("density compiler: shared branch DAG expansion is bounded") {
    NodeArena same;
    const auto selector = unary(same, NodeKind::kCacheOnce, gradient(same));
    auto root = constant(same, 7);
    for (int i = 0; i < 24; ++i) {
        Node n{}; n.kind = NodeKind::kRangeChoice; n.a = selector; n.b = root; n.c = root; n.d0 = 0; n.d1 = 1;
        root = same.push(n);
    }
    const auto compiled = dfc::compile(same, root); REQUIRE(compiled);
    CHECK(compiled.program.code.size() < 100);
    CacheState actual, expected; actual.resize_for(same); expected.resize_for(same);
    CHECK(bits(dfc::evaluate(compiled.program, same, Context{0, 0, 0, &actual}))
        == bits(evaluate_node(same, root, Context{0, 0, 0, &expected})));
    check_once_cache(actual, expected);

    NodeArena exponential;
    const auto y = gradient(exponential);
    const auto one = constant(exponential, 1);
    root = constant(exponential, 7);
    for (int i = 0; i < 20; ++i) {
        Node n{}; n.kind = NodeKind::kRangeChoice; n.a = y; n.b = root;
        n.c = binary(exponential, NodeKind::kAdd, root, one); n.d0 = 0; n.d1 = 1;
        root = exponential.push(n);
    }
    const auto rejected = dfc::compile(exponential, root);
    CHECK(rejected.error == dfc::CompileError::kProgramTooLarge);
    CHECK(rejected.program.code.size() <= dfc::kMaxProgramInstructions);
    CHECK_FALSE(dfc::install(exponential, root));
    CHECK_FALSE(exponential.compiled_program);
    CHECK(evaluate(exponential, root, Context{0, 0, 0}) == 7);
}


TEST_CASE("density compiler: batch noise kernels match point-order oracle including tails") {
    namespace noise = lattice::world::gen::noise;
    noise::PerlinNoiseSampler perlin{};
    perlin.origin_x = 12.3; perlin.origin_y = 34.5; perlin.origin_z = 56.7;
    for (int i = 0; i < 256; ++i) perlin.permutation[i] = static_cast<std::uint8_t>(i * 23);
    const std::array<noise::PerlinNoiseSampler, 2> octaves{perlin, perlin};
    const std::array<double, 2> amplitudes{1, 0.5};
    noise::OctavePerlinNoiseSampler octave{octaves.data(), amplitudes.data(), 2, 0.5, 0.75};
    noise::DoublePerlinNoiseSampler sampler{octave, octave, 1.125};
    noise::InterpolatedNoiseSampler legacy{&octave, &octave, &octave, 1, 1, 80, 160, 8};
    for (auto kind : {NodeKind::kNoise, NodeKind::kShiftA, NodeKind::kShiftB, NodeKind::kShift,
                      NodeKind::kShiftedNoise, NodeKind::kWeirdScaledSampler, NodeKind::kInterpolatedNoise}) {
        for (const bool wrapped : {false, true}) {
            NodeArena arena;
            Node n{}; n.kind = kind; n.noise_ptr = &sampler; n.interp_noise_ptr = &legacy;
            n.a = gradient(arena); n.b = constant(arena, 0); n.c = n.a;
            n.d0 = 0.123; n.d1 = 0.234;
            auto root = arena.push(n);
            root = binary(arena, NodeKind::kAdd, unary(arena, NodeKind::kSquare, root), gradient(arena));
            if (wrapped) root = unary(arena, NodeKind::kCache2D, root);
            const auto compiled = dfc::compile(arena, root); REQUIRE(compiled);
            CacheState actual, expected; actual.resize_for(arena); expected.resize_for(arena);
            actual.execution_stats = std::make_unique<ExecutionStats>();
            std::array<Context, 7> points;
            std::array<double, 7> output;
            dfc::BatchScratch scratch;
            for (int repetition = 0; repetition < 2; ++repetition) {
                for (std::size_t i = 0; i < points.size(); ++i)
                    points[i] = Context{1.25 + double(i / 3), 7.75 - double(i) * 0.3, -4.5, &actual};
                dfc::evaluate_batch(compiled.program, arena, points.data(), points.size(), output.data(), scratch);
                for (std::size_t i = 0; i < points.size(); ++i) {
                    auto point = points[i]; point.cache = &expected;
                    CHECK(bits(output[i]) == bits(evaluate_node(arena, root, point)));
                }
                check_batch_cache(actual, expected);
            }
            CHECK(actual.execution_stats->compiled_noise_batches > 0);
            CHECK(actual.execution_stats->compiled_noise_points >= 7);
        }
    }
}

TEST_CASE("density compiler: batch state barriers retain whole-point order") {
    for (bool two_d : {false, true}) {
        NodeArena arena;
        const auto cache = unary(arena, two_d ? NodeKind::kCache2D : NodeKind::kCacheOnce, gradient(arena));
        const auto root = two_d ? cache : binary(arena, NodeKind::kAdd, cache, unary(arena, NodeKind::kAbs, cache));
        const auto compiled = dfc::compile(arena, root); REQUIRE(compiled);
        CacheState actual, expected; actual.resize_for(arena); expected.resize_for(arena);
        if (!two_d) for (auto* state : {&actual, &expected}) {
            auto& entry = state->cache_once[0]; entry.valid = true; entry.value = 900;
        }
        const std::array<Context, 4> points{{{0, 0, 0, &actual}, {0, 1, 0, &actual}, {0, 0, 0, &actual}, {0, -1, 0, &actual}}};
        std::array<double, 4> output;
        dfc::BatchScratch scratch;
        dfc::evaluate_batch(compiled.program, arena, points.data(), points.size(), output.data(), scratch);
        for (std::size_t i = 0; i < points.size(); ++i) {
            auto point = points[i]; point.cache = &expected;
            CHECK(bits(output[i]) == bits(evaluate_node(arena, root, point)));
        }
        CHECK(output[0] == (two_d ? 0.0 : 1800.0));
        CHECK(output[1] == (two_d ? 0.0 : 2.0));
        check_batch_cache(actual, expected);
    }
}

TEST_CASE("density compiler: skipped nonfinite shifted-noise lanes are not speculatively sampled") {
    namespace noise = lattice::world::gen::noise;
    noise::PerlinNoiseSampler perlin{};
    for (int i = 0; i < 256; ++i) perlin.permutation[i] = static_cast<std::uint8_t>(i * 23);
    const double amplitude = 1;
    noise::OctavePerlinNoiseSampler octave{&perlin, &amplitude, 1, 1, 1};
    noise::DoublePerlinNoiseSampler sampler{octave, octave, 1};
    NodeArena arena;
    const auto y = gradient(arena);
    Node n{}; n.kind = NodeKind::kShiftedNoise; n.noise_ptr = &sampler; n.d0 = 1; n.d1 = 1;
    n.a = unary(arena, NodeKind::kInvert, y); n.b = constant(arena, 0); n.c = n.b;
    const auto noisy = arena.push(n);
    Node choice{}; choice.kind = NodeKind::kRangeChoice; choice.a = y; choice.b = noisy;
    choice.c = constant(arena, 7); choice.d0 = 0.1; choice.d1 = 16;
    const auto root = arena.push(choice);
    const auto compiled = dfc::compile(arena, root); REQUIRE(compiled);
    CacheState cache; cache.resize_for(arena); cache.execution_stats = std::make_unique<ExecutionStats>();
    const std::array<Context, 4> points{{{1, 1, 2, &cache}, {1, 0, 2, &cache}, {1, -1, 2, &cache}, {1, 2, 2, &cache}}};
    std::array<double, 4> output;
    dfc::BatchScratch scratch;
    dfc::evaluate_batch(compiled.program, arena, points.data(), points.size(), output.data(), scratch);
    for (std::size_t i = 0; i < points.size(); ++i)
        CHECK(bits(output[i]) == bits(evaluate_node(arena, root, points[i])));
    CHECK(output[1] == 7);
    CHECK(cache.execution_stats->compiled_noise_batches == 1);
    CHECK(cache.execution_stats->compiled_noise_points == 3);
}


TEST_CASE("density compiler: batch noise retains empty octave-array semantics") {
    namespace noise = lattice::world::gen::noise;
    const noise::PerlinNoiseSampler perlin{};
    const double amplitude = 1;
    for (int missing = 0; missing < 3; ++missing) {
        noise::OctavePerlinNoiseSampler octave{missing == 0 ? &perlin : nullptr,
            missing == 1 ? &amplitude : nullptr, 1, 1, 1};
        noise::DoublePerlinNoiseSampler sampler{octave, octave, 1};
        NodeArena arena;
        Node n{}; n.kind = NodeKind::kNoise; n.noise_ptr = &sampler; n.d0 = 1; n.d1 = 1;
        const auto root = arena.push(n);
        const auto compiled = dfc::compile(arena, root); REQUIRE(compiled);
        const std::array<Context, 4> points{{{1, 2, 3}, {2, 3, 4}, {-1, 0, 1}, {7, 8, 9}}};
        std::array<double, 4> output;
        dfc::BatchScratch scratch;
        dfc::evaluate_batch(compiled.program, arena, points.data(), points.size(), output.data(), scratch);
        for (std::size_t i = 0; i < points.size(); ++i) {
            CHECK(bits(output[i]) == bits(evaluate_node(arena, root, points[i])));
            CHECK(output[i] == 0);
        }
    }
}


TEST_CASE("density compiler: AVX2 arithmetic preserves every scalar opcode bit pattern") {
    NodeArena arena;
    const double nan = std::bit_cast<double>(std::uint64_t{0x7ff8000000000042});
    const double nan2 = std::bit_cast<double>(std::uint64_t{0x7ff8000000000013});
    const double inf = std::numeric_limits<double>::infinity();
    for (unsigned opcode = 0; opcode <= static_cast<unsigned>(dfc::Op::kClamp); ++opcode) {
        dfc::Program program;
        for (unsigned i = 0; i < 3; ++i) {
            dfc::Instr coordinate{}; coordinate.op = static_cast<dfc::Op>(static_cast<unsigned>(dfc::Op::kCoordX) + i);
            coordinate.dst = i; program.code.push_back(coordinate);
        }
        dfc::Instr instr{}; instr.op = static_cast<dfc::Op>(opcode); instr.dst = 3;
        instr.s0 = 0; instr.s1 = 1; instr.s2 = 2;
        instr.imm0 = -1.3; instr.imm1 = 9.7; instr.imm2 = -2.1; instr.imm3 = 3.4;
        program.code.push_back(instr); program.value_count = 4; program.result = 3;
        program.pure_definitions = {0, 1, 2, 3};
        for (int group = 0; group < 5; ++group) {
            CacheState state; state.execution_stats = std::make_unique<ExecutionStats>();
            std::array<Context, 7> points{{{nan, nan2, 1, &state}, {-0.0, 0.0, -0.0, &state},
                {inf, -inf, 0, &state}, {1.2345678901, -9.8765432109, 0.3, &state},
                {-0.3, 0.7, 1.1, &state}, {0, 0, 0, &state}, {-inf, nan, 0, &state}}};
            if (group == 1) for (auto& point : points) std::swap(point.x, point.y);
            if (group == 2) for (std::size_t i = 0; i < points.size(); ++i) {
                points[i].x = 0.17 * double(i) - 0.37; points[i].y = -0.3 * double(i); points[i].z = 0.33;
            }
            if (group == 3) for (std::size_t i = 0; i < points.size(); ++i) {
                points[i].x = i % 2 ? 0.0 : -0.0; points[i].y = i % 2 ? -0.0 : 0.0; points[i].z = -0.0;
            }
            if (group == 4) for (std::size_t i = 0; i < points.size(); ++i) {
                points[i].x = i % 2 ? std::numeric_limits<double>::max() : -std::numeric_limits<double>::max();
                points[i].y = points[i].x; points[i].z = 0.0;
            }
            std::array<double, 7> output, scalar_batch;
            dfc::BatchScratch scratch;
            dfc::evaluate_batch(program, arena, points.data(), points.size(), output.data(), scratch);
            CHECK((state.execution_stats->compiled_avx2_ops > 0) == lattice::cpu::features().avx2);
            const auto ops = state.execution_stats->compiled_avx2_ops;
            if (group >= 2) CHECK(ops == (lattice::cpu::features().avx2 ? 4 : 0)); // Three coordinates AND the target opcode.
            dfc::evaluate_batch(program, arena, points.data(), points.size(), scalar_batch.data(), scratch, dfc::BatchBackend::kScalar);
            CHECK(state.execution_stats->compiled_avx2_ops == ops);
            for (std::size_t i = 0; i < points.size(); ++i) {
                INFO(opcode, " group=", group, " lane=", i);
                CHECK(bits(output[i]) == bits(dfc::evaluate(program, arena, points[i])));
                CHECK(bits(output[i]) == bits(scalar_batch[i]));
            }
        }
    }
}
