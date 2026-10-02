#include <doctest/doctest.h>
#include <bit>
#include <cmath>
#include <cstdio>
#include <random>
#include "world/gen/densityfunction/df_compile.hpp"

using namespace lattice::world::gen::densityfunction;
namespace dfc = lattice::world::gen::densityfunction::dfc;
namespace {
std::uint64_t bits(double x) { return std::bit_cast<std::uint64_t>(x); }
NodeRef constant(NodeArena& arena, double value) {
    Node n{}; n.kind = NodeKind::kConstant; n.d0 = value; return arena.push(n);
}
NodeRef unary(NodeArena& arena, NodeKind kind, NodeRef a) {
    Node n{}; n.kind = kind; n.a = a; return arena.push(n);
}
NodeRef binary(NodeArena& arena, NodeKind kind, NodeRef a, NodeRef b) {
    Node n{}; n.kind = kind; n.a = a; n.b = b; return arena.push(n);
}
NodeRef gradient(NodeArena& arena) {
    Node n{}; n.kind = NodeKind::kYClampedGradient; n.i0 = -16; n.i1 = 16; n.d0 = -16; n.d1 = 16;
    return arena.push(n);
}
void check_once_cache(const CacheState& actual, const CacheState& expected) {
    REQUIRE(actual.cache_once.size() == expected.cache_once.size());
    for (std::size_t i = 0; i < actual.cache_once.size(); ++i) {
        const auto& a = actual.cache_once[i]; const auto& b = expected.cache_once[i];
        CHECK(a.valid == b.valid);
        if (!a.valid || !b.valid) continue;
        CHECK(bits(a.x) == bits(b.x)); CHECK(bits(a.y) == bits(b.y)); CHECK(bits(a.z) == bits(b.z));
        CHECK(bits(a.value) == bits(b.value));
    }
}
}

TEST_CASE("density optimizer: structural CSE and dead pure code are observable") {
    NodeArena arena;
    const auto a = unary(arena, NodeKind::kSquare, gradient(arena));
    const auto b = unary(arena, NodeKind::kSquare, gradient(arena));
    const auto root = binary(arena, NodeKind::kAdd, a, b);
    const auto compiled = dfc::compile(arena, root); REQUIRE(compiled);
    CHECK(compiled.program.code.size() == 3); CHECK(compiled.program.cse_hits >= 2);
    for (const double y : {-3.5, 0.0, 7.25})
        CHECK(bits(dfc::evaluate(compiled.program, arena, Context{0, y, 0})) == bits(evaluate_node(arena, root, Context{0, y, 0})));
    NodeArena folded;
    const auto sum = binary(folded, NodeKind::kAdd, constant(folded, 2), constant(folded, 3));
    const auto product = binary(folded, NodeKind::kMul, sum, constant(folded, 4));
    const auto dead = dfc::compile(folded, product); REQUIRE(dead);
    CHECK(dead.program.code.size() == 1); CHECK(dead.program.dead_instructions >= 3);
    CHECK(dfc::evaluate(dead.program, folded, Context{}) == 20);
}

TEST_CASE("density optimizer: CSE retains operand order signed zero and branch dominance") {
    NodeArena arena;
    const auto y = gradient(arena), c = constant(arena, 3);
    const auto a = binary(arena, NodeKind::kAdd, y, c);
    const auto b = binary(arena, NodeKind::kAdd, c, y);
    const auto root = binary(arena, NodeKind::kAdd, a, b);
    const auto compiled = dfc::compile(arena, root); REQUIRE(compiled);
    unsigned additions = 0;
    for (const auto& instr : compiled.program.code) additions += instr.op == dfc::Op::kAdd;
    CHECK(additions == 3);
    NodeArena zeros;
    const auto pos = unary(zeros, NodeKind::kCacheOnce, constant(zeros, 0.0));
    const auto neg = unary(zeros, NodeKind::kCacheOnce, constant(zeros, -0.0));
    const auto zero_root = binary(zeros, NodeKind::kAdd, pos, neg);
    const auto zero_program = dfc::compile(zeros, zero_root); REQUIRE(zero_program);
    CacheState cache; cache.resize_for(zeros);
    CHECK(dfc::evaluate(zero_program.program, zeros, Context{0, 0, 0, &cache}) == 0);
    CHECK(bits(cache.cache_once[0].value) == bits(0.0)); CHECK(bits(cache.cache_once[1].value) == bits(-0.0));
    NodeArena branch;
    Node n{}; n.kind = NodeKind::kRangeChoice; n.a = gradient(branch);
    n.b = unary(branch, NodeKind::kSquare, gradient(branch)); n.c = constant(branch, 7); n.d0 = 0; n.d1 = 1;
    const auto choice = branch.push(n);
    const auto repeated = unary(branch, NodeKind::kSquare, gradient(branch));
    const auto branch_root = binary(branch, NodeKind::kAdd, choice, repeated);
    const auto branch_program = dfc::compile(branch, branch_root); REQUIRE(branch_program);
    for (const double yy : {2.0, 0.5, -1.0})
        CHECK(bits(dfc::evaluate(branch_program.program, branch, Context{0, yy, 0})) == bits(evaluate_node(branch, branch_root, Context{0, yy, 0})));
}

TEST_CASE("density optimizer: constant branches eliminate only skipped work") {
    for (const bool multiply : {false, true}) {
        NodeArena arena;
        const auto effect = unary(arena, NodeKind::kCacheOnce, constant(arena, 99));
        Node n{}; n.kind = multiply ? NodeKind::kMul : NodeKind::kRangeChoice;
        n.a = constant(arena, -0.0); n.b = effect; n.c = constant(arena, 7); n.d0 = 1; n.d1 = 2;
        const auto root = arena.push(n);
        const auto compiled = dfc::compile(arena, root); REQUIRE(compiled);
        CHECK(compiled.program.code.size() == 1);
        CacheState cache; cache.resize_for(arena);
        CHECK(bits(dfc::evaluate(compiled.program, arena, Context{0, 0, 0, &cache})) == bits(multiply ? 0.0 : 7.0));
        CHECK_FALSE(cache.cache_once[0].valid);
    }
}

TEST_CASE("density optimizer: optional spline coefficients widen immediately and quantify error") {
    NodeArena arena;
    const auto location = unary(arena, NodeKind::kCacheOnce, gradient(arena));
    Spline fixed{}; fixed.kind = SplineKind::kFixedFloat;
    const auto zero = arena.push_spline(fixed);
    fixed.fixed_value = 1; const auto one = arena.push_spline(fixed);
    Spline spline{}; spline.kind = SplineKind::kImpl; spline.location_function = location;
    spline.breakpoints_start = arena.reserve_spline_breakpoints(2); spline.breakpoint_count = 2;
    arena.spline_breakpoints[0] = {0, 0.1f, zero}; arena.spline_breakpoints[1] = {10, 0.1f, one};
    Node node{}; node.kind = NodeKind::kSpline; node.i0 = arena.push_spline(spline);
    const auto root = arena.push(node);
    const auto normal = dfc::compile(arena, root); REQUIRE(normal);
    CHECK(normal.program.spline_coefficients.empty()); CHECK(normal.program.spline_candidates == 0);
    const auto experiment = dfc::compile(arena, root, {.experimental_spline_coefficients_f32 = true}); REQUIRE(experiment);
    CHECK(experiment.program.spline_candidates == 1); REQUIRE(experiment.program.spline_coefficients.size() == 1);
    CHECK(std::bit_cast<std::uint32_t>(static_cast<float>(dfc::evaluate(normal.program, arena, Context{0, 9, 0}))) == 0x3f666666u);
    CHECK(std::bit_cast<std::uint32_t>(static_cast<float>(dfc::evaluate(experiment.program, arena, Context{0, 9, 0}))) == 0x3f666667u);
    REQUIRE(dfc::install(arena, root)); CHECK(arena.compiled_program->spline_coefficients.empty());
    const double extra = 0x1p-35;
    const auto outer = binary(arena, NodeKind::kAdd, root, constant(arena, extra));
    const auto widened = dfc::compile(arena, outer, {.experimental_spline_coefficients_f32 = true}); REQUIRE(widened);
    const auto raw = dfc::evaluate(experiment.program, arena, Context{0, 9, 0});
    CHECK(bits(dfc::evaluate(widened.program, arena, Context{0, 9, 0})) == bits(raw + extra));
    CHECK(raw + extra != static_cast<double>(static_cast<float>(raw + extra)));
    const auto wrapped = unary(arena, NodeKind::kCacheOnce, root);
    const auto specialized = dfc::compile(arena, wrapped, {.experimental_spline_coefficients_f32 = true}); REQUIRE(specialized);
    REQUIRE(specialized.program.cacheless); CHECK(specialized.program.cacheless->spline_coefficients.size() == 1);
    CacheState actual, expected; actual.resize_for(arena); expected.resize_for(arena);
    for (const float yy : {-1.0f, 0.0f, std::nextafter(0.0f, 1.0f), std::nextafter(10.0f, 0.0f), 10.0f, 11.0f}) {
        const Context ac{0, double(yy), 0, &actual}, ec{0, double(yy), 0, &expected};
        const double got = dfc::evaluate(experiment.program, arena, ac), want = evaluate_node(arena, root, ec);
        CHECK(std::abs(got - want) <= 1e-6); check_once_cache(actual, expected);
        CHECK(bits(dfc::evaluate(normal.program, arena, ec)) == bits(want));
    }
    std::mt19937 random(0xF32C0EF);
    unsigned changed = 0, sign_flips = 0, nonfinite_changes = 0;
    std::uint64_t max_ulp = 0; double max_error = 0, squared_error = 0;
    const auto ordered = [](float f) { const auto v = std::bit_cast<std::uint32_t>(f); return (v & 0x80000000u) ? ~v : (v | 0x80000000u); };
    for (unsigned i = 0; i < 4096; ++i) {
        const Context ctx{0, -2.0 + 14.0 * double(random()) / double(std::mt19937::max()), 0};
        const double got = dfc::evaluate(experiment.program, arena, ctx), want = evaluate_node(arena, root, ctx);
        changed += bits(got) != bits(want); sign_flips += (got < 0) != (want < 0);
        nonfinite_changes += std::isfinite(got) != std::isfinite(want);
        const auto a = ordered(static_cast<float>(got)), b = ordered(static_cast<float>(want));
        max_ulp = std::max(max_ulp, std::uint64_t(a > b ? a - b : b - a));
        const double error = std::abs(got - want); max_error = std::max(max_error, error); squared_error += error * error;
    }
    CHECK(changed > 0); CHECK(max_error <= 1e-6); CHECK(sign_flips == 0); CHECK(nonfinite_changes == 0);
    std::printf("SPLINE_F32 samples=4096 changed=%u maxAbs=%.12g rmsAbs=%.12g maxFloatUlp=%llu signFlips=%u nonfiniteChanges=%u\n",
        changed, max_error, std::sqrt(squared_error / 4096.0), static_cast<unsigned long long>(max_ulp), sign_flips, nonfinite_changes);
}

TEST_CASE("density optimizer: ineligible spline coefficients retain the vanilla path") {
    for (int reason = 0; reason < 4; ++reason) {
        NodeArena arena;
        Spline fixed{}; fixed.kind = SplineKind::kFixedFloat; fixed.fixed_value = 1;
        const auto value = arena.push_spline(fixed);
        Spline nested{}; nested.kind = SplineKind::kImpl; nested.location_function = gradient(arena);
        const auto nested_ref = arena.push_spline(nested);
        Spline spline{}; spline.kind = SplineKind::kImpl; spline.location_function = gradient(arena);
        spline.breakpoints_start = arena.reserve_spline_breakpoints(2); spline.breakpoint_count = reason == 3 ? 1 : 2;
        arena.spline_breakpoints[0] = {0, 0, value};
        arena.spline_breakpoints[1] = {reason == 0 ? 0.0f : 10.0f,
            reason == 1 ? std::numeric_limits<float>::infinity() : 0.0f, reason == 2 ? nested_ref : value};
        Node n{}; n.kind = NodeKind::kSpline; n.i0 = arena.push_spline(spline); const auto root = arena.push(n);
        const auto result = dfc::compile(arena, root, {.experimental_spline_coefficients_f32 = true}); REQUIRE(result);
        CHECK(result.program.spline_coefficients.empty());
        CHECK(bits(dfc::evaluate(result.program, arena, Context{0, -1, 0})) == bits(evaluate_node(arena, root, Context{0, -1, 0})));
    }
}

TEST_CASE("density optimizer: empty cell-cache miss constants cannot escape their branch") {
    NodeArena arena;
    Node cell{}; cell.kind = NodeKind::kCacheAllInCell;
    const auto leaf = arena.push(cell);
    const auto root = binary(arena, NodeKind::kAdd, leaf, constant(arena, 0));
    const auto compiled = dfc::compile(arena, root); REQUIRE(compiled);
    const double bound = 19;
    for (const bool bind : {false, true}) {
        CacheState state; state.resize_for(arena);
        if (bind) { state.cache_all_in_cell_arrays[0] = &bound; state.cache_all_in_cell_array_lengths[0] = 1; }
        const Context point{0, 0, 0, &state, 0, 0, 0, 0, 0, 1, 1};
        std::vector<double> registers(compiled.program.value_count, -12345);
        CHECK(dfc::evaluate(compiled.program, arena, point, registers.data(), registers.size()) == (bind ? 19 : 0));
        const std::array<Context, 5> points{point, point, point, point, point};
        std::array<double, 5> output;
        dfc::BatchScratch scratch; scratch.values.assign(compiled.program.value_count, -9876);
        for (int repeat = 0; repeat < 2; ++repeat) {
            dfc::evaluate_batch(compiled.program, arena, points.data(), points.size(), output.data(), scratch);
            for (const auto value : output) CHECK(value == (bind ? 19 : 0));
        }
    }
}

TEST_CASE("density optimizer: spline coefficients cover nonlinear segments batch and nonfinite location") {
    NodeArena arena;
    const auto location = unary(arena, NodeKind::kCacheOnce, gradient(arena));
    const std::array<float, 3> positions{-3.5f, 1.25f, 7.75f};
    const std::array<float, 3> values{-0.8f, 2.3f, -1.1f};
    const std::array<float, 3> derivatives{0.0f, 1.75f, 0.0f};
    Spline spline{}; spline.kind = SplineKind::kImpl; spline.location_function = location;
    spline.breakpoints_start = arena.reserve_spline_breakpoints(3); spline.breakpoint_count = 3;
    for (int i = 0; i < 3; ++i) {
        Spline fixed{}; fixed.kind = SplineKind::kFixedFloat; fixed.fixed_value = values[i];
        arena.spline_breakpoints[i] = {positions[i], derivatives[i], arena.push_spline(fixed)};
    }
    Node node{}; node.kind = NodeKind::kSpline; node.i0 = arena.push_spline(spline);
    const auto root = arena.push(node);
    const auto wrapped = unary(arena, NodeKind::kCacheOnce, root);
    const auto compiled = dfc::compile(arena, wrapped, {.experimental_spline_coefficients_f32 = true}); REQUIRE(compiled);
    REQUIRE(compiled.program.cacheless); REQUIRE(compiled.program.cacheless->spline_coefficients.size() == 1);
    const auto expected_formula = [&](float x) {
        if (x < positions[0]) return values[0];
        if (!(x < positions[2])) return values[2];
        const int i = x < positions[1] ? 0 : 1;
        const float span = positions[i + 1] - positions[i];
        const float k = (x - positions[i]) * (1.0f / span);
        const float delta = values[i + 1] - values[i];
        const float p = derivatives[i] * span - delta;
        const float q = -derivatives[i + 1] * span + delta;
        return values[i] + k * delta + k * (1 - k) * (p + k * (q - p));
    };
    for (const float x : {-9.0f, -3.5f, std::nextafter(-3.5f, 0.0f), -1.0f, 1.25f,
                          std::nextafter(1.25f, 0.0f), 4.0f, 7.75f, 10.0f}) {
        CHECK(bits(dfc::evaluate(compiled.program, arena, Context{0, x, 0})) == bits(double(expected_formula(x))));
    }
    CacheState actual, expected; actual.resize_for(arena); expected.resize_for(arena);
    const std::array<Context, 5> points{{{0, -1, 0, &actual}, {0, 4, 0, &actual},
        {0, 4, 0, &actual}, {0, 1.25, 0, &actual}, {0, -3.5, 0, &actual}}};
    std::array<double, 5> output; dfc::BatchScratch scratch;
    dfc::evaluate_batch(compiled.program, arena, points.data(), points.size(), output.data(), scratch);
    for (std::size_t i = 0; i < points.size(); ++i) {
        auto point = points[i]; point.cache = &expected;
        CHECK(bits(output[i]) == bits(dfc::evaluate(compiled.program, arena, point)));
    }
    check_once_cache(actual, expected);
    const auto experiment = dfc::compile(arena, root, {.experimental_spline_coefficients_f32 = true}); REQUIRE(experiment);
    for (const double value : {std::numeric_limits<double>::quiet_NaN(), std::numeric_limits<double>::infinity(), -std::numeric_limits<double>::infinity()}) {
        CacheState cache; cache.resize_for(arena);
        auto& entry = cache.cache_once[0]; entry.valid = true; entry.value = value;
        const Context ctx{0, 0, 0, &cache};
        CHECK(bits(dfc::evaluate(experiment.program, arena, ctx)) == bits(evaluate_node(arena, root, ctx)));
    }
    arena.batch_roots = {root, wrapped};
    REQUIRE(dfc::install(arena, root)); REQUIRE(dfc::install_batch(arena) == 2);
    for (const auto ref : arena.batch_roots) {
        const auto* program = dfc::find_program(arena, ref); REQUIRE(program);
        CHECK(program->spline_coefficients.empty());
        if (program->cacheless) CHECK(program->cacheless->spline_coefficients.empty());
    }
}
