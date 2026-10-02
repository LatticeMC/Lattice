#include "world/gen/densityfunction/df_compile.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <memory>

namespace lattice::world::gen::densityfunction::dfc {

namespace {

inline double half_negative(double v) noexcept;
inline double quarter_negative(double v) noexcept;
inline double squeeze(double v) noexcept;
inline double map_range(double v, double a, double b, double c, double d) noexcept;
inline double clamp_d(double v, double lo, double hi) noexcept;

struct Builder {
    const NodeArena& arena;
    CompileResult result;
    std::vector<std::uint32_t> slots;
    std::vector<std::uint8_t> visiting;
    std::vector<std::uint8_t> is_constant;
    std::vector<double> constant_values;

    explicit Builder(const NodeArena& input) : arena(input), visiting(input.nodes.size(), 0) {}

    std::uint32_t fail(CompileError error, NodeRef node) noexcept {
        if (result.error == CompileError::kNone) {
            result.error = error;
            result.error_node = node;
        }
        return 0;
    }

    bool valid(NodeRef ref) const noexcept {
        return ref >= 0 && ref < static_cast<NodeRef>(arena.nodes.size());
    }

    std::uint32_t emit(const Instr& instr) {
        Instr copy = instr;
        bool fold = false;
        double folded = 0.0;
        const auto source = [&](std::uint32_t slot, double& value) {
            if (slot >= is_constant.size() || !is_constant[slot]) return false;
            value = constant_values[slot];
            return true;
        };
        double a = 0.0, b = 0.0, c = 0.0;
        switch (copy.op) {
            case Op::kAbs: fold = source(copy.s0, a); if (fold) folded = std::abs(a); break;
            case Op::kSquare: fold = source(copy.s0, a); if (fold) folded = a * a; break;
            case Op::kCube: fold = source(copy.s0, a); if (fold) folded = a * a * a; break;
            case Op::kHalfNegative: fold = source(copy.s0, a); if (fold) folded = half_negative(a); break;
            case Op::kQuarterNegative: fold = source(copy.s0, a); if (fold) folded = quarter_negative(a); break;
            case Op::kInvert: fold = source(copy.s0, a); if (fold) folded = 1.0 / a; break;
            case Op::kSqueeze: fold = source(copy.s0, a); if (fold) folded = squeeze(a); break;
            case Op::kAdd: case Op::kMul: case Op::kMin: case Op::kMax:
                fold = source(copy.s0, a) && source(copy.s1, b);
                if (fold) {
                    if (copy.op == Op::kAdd) folded = a + b;
                    else if (copy.op == Op::kMul) folded = a == 0.0 ? 0.0 : a * b;
                    else if (copy.op == Op::kMin) folded = std::min(a, b);
                    else folded = std::max(a, b);
                }
                break;
            case Op::kMapRange:
                fold = source(copy.s0, a);
                if (fold) folded = map_range(a, copy.imm0, copy.imm1, copy.imm2, copy.imm3);
                break;
            case Op::kLerp:
                fold = source(copy.s0, a) && source(copy.s1, b) && source(copy.s2, c);
                if (fold) folded = b + a * (c - b);
                break;
            case Op::kClamp:
                fold = source(copy.s0, a);
                if (fold) folded = clamp_d(a, copy.imm0, copy.imm1);
                break;
            default: break;
        }
        if (fold) {
            copy = {};
            copy.op = Op::kConstant;
            copy.imm0 = folded;
        }
        copy.dst = result.program.value_count++;
        result.program.code.push_back(copy);
        is_constant.push_back(copy.op == Op::kConstant);
        constant_values.push_back(copy.op == Op::kConstant ? copy.imm0 : 0.0);
        return copy.dst;
    }

    std::uint32_t child(NodeRef ref) {
        if (!valid(ref)) return fail(CompileError::kInvalidOperand, ref);
        return compile_node(ref);
    }

    std::uint32_t compile_node(NodeRef ref) {
        if (!valid(ref)) return fail(CompileError::kInvalidOperand, ref);
        const std::size_t index = static_cast<std::size_t>(ref);
        if (slots.size() <= index) slots.resize(index + 1, std::numeric_limits<std::uint32_t>::max());
        if (slots[index] != std::numeric_limits<std::uint32_t>::max()) return slots[index];
        if (visiting[index] != 0) return fail(CompileError::kInvalidOperand, ref);
        visiting[index] = 1;
        const Node& node = arena.nodes[index];
        Instr instr{};
        std::uint32_t value = 0;
        switch (node.kind) {
            case NodeKind::kConstant:
                instr.op = Op::kConstant; instr.imm0 = node.d0; value = emit(instr); break;
            case NodeKind::kYClampedGradient:
                instr.op = Op::kYClampedGradient;
                instr.imm0 = static_cast<double>(node.i0); instr.imm1 = static_cast<double>(node.i1);
                instr.imm2 = node.d0; instr.imm3 = node.d1; value = emit(instr); break;
            case NodeKind::kAbs: case NodeKind::kSquare: case NodeKind::kCube:
            case NodeKind::kHalfNegative: case NodeKind::kQuarterNegative:
            case NodeKind::kInvert: case NodeKind::kSqueeze: {
                instr.op = static_cast<Op>(static_cast<std::uint8_t>(Op::kAbs)
                    + (static_cast<std::uint8_t>(node.kind) - static_cast<std::uint8_t>(NodeKind::kAbs)));
                instr.s0 = child(node.a); value = emit(instr); break;
            }
            case NodeKind::kAdd: case NodeKind::kMul: case NodeKind::kMin: case NodeKind::kMax: {
                instr.op = static_cast<Op>(static_cast<std::uint8_t>(Op::kAdd)
                    + (static_cast<std::uint8_t>(node.kind) - static_cast<std::uint8_t>(NodeKind::kAdd)));
                instr.s0 = child(node.a); instr.s1 = child(node.b); value = emit(instr); break;
            }
            case NodeKind::kMapRange:
                instr.op = Op::kMapRange; instr.s0 = child(node.a);
                instr.imm0 = node.d0; instr.imm1 = node.d1; instr.imm2 = node.d2; instr.imm3 = node.d3;
                value = emit(instr); break;
            case NodeKind::kLerp:
                instr.op = Op::kLerp; instr.s0 = child(node.a); instr.s1 = child(node.b); instr.s2 = child(node.c);
                value = emit(instr); break;
            case NodeKind::kClamp:
                instr.op = Op::kClamp; instr.s0 = child(node.a); instr.imm0 = node.d0; instr.imm1 = node.d1;
                value = emit(instr); break;
            default:
                value = fail(CompileError::kUnsupportedNode, ref); break;
        }
        visiting[index] = 0;
        if (result.error != CompileError::kNone) return value;
        slots[index] = value;
        return value;
    }
};

inline double half_negative(double v) noexcept { return v < 0.0 ? v * 0.5 : v; }
inline double quarter_negative(double v) noexcept { return v < 0.0 ? v * 0.25 : v; }
inline double clamp_d(double v, double lo, double hi) noexcept {
    return std::max(lo, std::min(hi, v));
}
inline double squeeze(double v) noexcept {
    const double x = clamp_d(v, -1.0, 1.0);
    return x * 0.5 - x * x * x / 24.0;
}
inline double map_range(double v, double a, double b, double c, double d) noexcept {
    const double t = (v - a) / (b - a);
    return c + t * (d - c);
}
inline double y_gradient(double from_y, double to_y, double from_v, double to_v, double y) noexcept {
    const double dy = to_y - from_y;
    if (dy == 0.0) return (from_v + to_v) * 0.5;
    const double clamped = std::max(from_y, std::min(to_y, y));
    const double t = (clamped - from_y) / dy;
    return from_v + t * (to_v - from_v);
}

} // namespace

CompileResult compile(const NodeArena& arena, NodeRef root) noexcept {
    Builder builder(arena);
    if (root < 0 || root >= static_cast<NodeRef>(arena.nodes.size())) {
        builder.result.error = CompileError::kInvalidRoot;
        builder.result.error_node = root;
        return builder.result;
    }
    builder.result.program.result = builder.compile_node(root);
    if (builder.result.error != CompileError::kNone) return builder.result;

    Program& program = builder.result.program;
    std::vector<std::uint8_t> used(program.value_count, 0);
    used[program.result] = 1;
    for (auto it = program.code.rbegin(); it != program.code.rend(); ++it) {
        if (!used[it->dst]) continue;
        switch (it->op) {
            case Op::kAbs: case Op::kSquare: case Op::kCube:
            case Op::kHalfNegative: case Op::kQuarterNegative: case Op::kInvert:
            case Op::kSqueeze: case Op::kMapRange: case Op::kClamp:
                used[it->s0] = 1;
                break;
            case Op::kAdd: case Op::kMul: case Op::kMin: case Op::kMax:
                used[it->s0] = 1;
                used[it->s1] = 1;
                break;
            case Op::kLerp:
                used[it->s0] = 1;
                used[it->s1] = 1;
                used[it->s2] = 1;
                break;
            default: break;
        }
    }

    std::vector<std::uint32_t> remap(program.value_count, std::numeric_limits<std::uint32_t>::max());
    std::vector<Instr> compact;
    compact.reserve(program.code.size());
    for (const Instr& instr : program.code) {
        if (!used[instr.dst]) continue;
        Instr copy = instr;
        copy.dst = static_cast<std::uint32_t>(compact.size());
        remap[instr.dst] = copy.dst;
        compact.push_back(copy);
    }
    for (Instr& instr : compact) {
        switch (instr.op) {
            case Op::kAbs: case Op::kSquare: case Op::kCube:
            case Op::kHalfNegative: case Op::kQuarterNegative: case Op::kInvert:
            case Op::kSqueeze: case Op::kMapRange: case Op::kClamp:
                instr.s0 = remap[instr.s0];
                break;
            case Op::kAdd: case Op::kMul: case Op::kMin: case Op::kMax:
                instr.s0 = remap[instr.s0];
                instr.s1 = remap[instr.s1];
                break;
            case Op::kLerp:
                instr.s0 = remap[instr.s0];
                instr.s1 = remap[instr.s1];
                instr.s2 = remap[instr.s2];
                break;
            default: break;
        }
    }
    program.result = remap[program.result];
    program.value_count = static_cast<std::uint32_t>(compact.size());
    program.code = std::move(compact);
    return builder.result;
}

bool install(NodeArena& arena, NodeRef root) noexcept {
    arena.compiled_program.reset();
    arena.compiled_program_root = kNullRef;
    const CompileResult compiled = compile(arena, root);
    if (!compiled) return false;
    arena.compiled_program = std::make_shared<Program>(compiled.program);
    arena.compiled_program_root = root;
    return true;
}

double evaluate(const Program& program, const Context& ctx,
                double* values, std::size_t value_capacity) noexcept {
    if (!values || value_capacity < program.value_count
        || program.value_count == 0 || program.result >= program.value_count) return 0.0;
    const auto read_slot = [&](std::uint32_t slot) noexcept {
        return slot < program.value_count ? values[slot] : 0.0;
    };
    for (const Instr& instr : program.code) {
        double value = 0.0;
        const double a = read_slot(instr.s0);
        const double b = read_slot(instr.s1);
        const double c = read_slot(instr.s2);
        switch (instr.op) {
            case Op::kConstant: value = instr.imm0; break;
            case Op::kCoordX: value = ctx.x; break;
            case Op::kCoordY: value = ctx.y; break;
            case Op::kCoordZ: value = ctx.z; break;
            case Op::kAbs: value = std::abs(a); break;
            case Op::kSquare: value = a * a; break;
            case Op::kCube: value = a * a * a; break;
            case Op::kHalfNegative: value = half_negative(a); break;
            case Op::kQuarterNegative: value = quarter_negative(a); break;
            case Op::kInvert: value = 1.0 / a; break;
            case Op::kSqueeze: value = squeeze(a); break;
            case Op::kAdd: value = a + b; break;
            case Op::kMul: value = a == 0.0 ? 0.0 : a * b; break;
            case Op::kMin: value = std::min(a, b); break;
            case Op::kMax: value = std::max(a, b); break;
            case Op::kYClampedGradient: value = y_gradient(instr.imm0, instr.imm1, instr.imm2, instr.imm3, ctx.y); break;
            case Op::kMapRange: value = map_range(a, instr.imm0, instr.imm1, instr.imm2, instr.imm3); break;
            case Op::kLerp: value = b + a * (c - b); break;
            case Op::kClamp: value = clamp_d(a, instr.imm0, instr.imm1); break;
        }
        if (instr.dst < program.value_count) values[instr.dst] = value;
    }
    return values[program.result];
}

double evaluate(const Program& program, const Context& ctx) noexcept {
    std::vector<double> values(program.value_count, 0.0);
    return evaluate(program, ctx, values.data(), values.size());
}

const char* compile_error_name(CompileError error) noexcept {
    switch (error) {
        case CompileError::kNone: return "none";
        case CompileError::kInvalidRoot: return "invalid-root";
        case CompileError::kUnsupportedNode: return "unsupported-node";
        case CompileError::kInvalidOperand: return "invalid-operand";
    }
    return "unknown";
}

} // namespace lattice::world::gen::densityfunction::dfc
