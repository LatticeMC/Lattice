#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <bit>
#include <cstdint>
#include <limits>
#include <string>

#include <doctest/doctest.h>

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
    CHECK(bits(dfc::evaluate(compiled.program, ctx)) == bits(evaluate(arena, ctx)));
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
        CHECK(bits(dfc::evaluate(compiled.program, ctx)) == bits(evaluate(arena, ctx)));
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
        CHECK(bits(dfc::evaluate(compiled.program, ctx)) == bits(evaluate(arena, ctx)));
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
    CHECK(bits(dfc::evaluate(compiled.program, Context{0.0, 4.0, 0.0}))
          == bits(evaluate(arena, Context{0.0, 4.0, 0.0})));
}

TEST_CASE("density compiler: unsupported stateful nodes fail explicitly") {
    NodeArena arena;
    const NodeRef value = constant(arena, 2.0);
    Node cache{};
    cache.kind = NodeKind::kCacheOnce;
    cache.a = value;
    const NodeRef root = arena.push(cache);

    const dfc::CompileResult compiled = dfc::compile(arena, root);
    CHECK_FALSE(compiled);
    CHECK(compiled.error == dfc::CompileError::kUnsupportedNode);
    CHECK(std::string(dfc::compile_error_name(compiled.error)) == "unsupported-node");
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
    CHECK(bits(dfc::evaluate(compiled.program, ctx)) == bits(evaluate(arena, ctx)));

    NodeArena zero_arena;
    const NodeRef negative_zero = constant(zero_arena, -0.0);
    const NodeRef squeeze_ref = unary(zero_arena, NodeKind::kSqueeze, negative_zero);
    zero_arena.root = squeeze_ref;
    const dfc::CompileResult zero_compiled = dfc::compile(zero_arena, squeeze_ref);
    REQUIRE(zero_compiled);
    CHECK(bits(dfc::evaluate(zero_compiled.program, ctx)) == bits(evaluate(zero_arena, ctx)));
}
