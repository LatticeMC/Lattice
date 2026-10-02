#include "world/gen/densityfunction/df_compile.hpp"
#include "world/gen/densityfunction/df_compile_noise.hpp"
#include "world/gen/densityfunction/df_compile_spline.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <memory>
#include <bit>
#include <map>
#include "lattice/dispatch.hpp"

namespace lattice::world::gen::densityfunction::dfc {

#if defined(LATTICE_HAS_DENSITY_AVX2)
bool evaluate_math_avx2(const Instr&, const Context*, const double*, const double*, const double*, double*) noexcept;
#endif

namespace {

inline double half_negative(double v) noexcept;
inline double quarter_negative(double v) noexcept;
inline double squeeze(double v) noexcept;
inline double map_range(double v, double a, double b, double c, double d) noexcept;
inline double clamp_d(double v, double lo, double hi) noexcept;
unsigned operands(Op op) noexcept;

bool pure_operation(Op op) noexcept {
    return op <= Op::kClamp || (op >= Op::kNoise && op <= Op::kInterpolatedNoise);
}

struct Builder {
    const NodeArena& arena;
    bool cacheless;
    CompileOptions options;
    CompileResult result;
    std::vector<std::uint32_t> slots;
    std::vector<std::uint8_t> visiting;
    std::vector<std::uint8_t> pure;
    std::vector<std::uint8_t> spline_visiting;
    std::vector<std::uint8_t> is_constant;
    std::vector<double> constant_values;
    std::vector<std::uint8_t> pure_values;
    using Expression = std::array<std::uint64_t, 9>;
    std::map<Expression, std::uint32_t> expressions;

    explicit Builder(const NodeArena& input, bool without_cache, CompileOptions settings) : arena(input), cacheless(without_cache), options(settings),
        slots(input.nodes.size(), std::numeric_limits<std::uint32_t>::max()),
        visiting(input.nodes.size(), 0), pure(input.nodes.size(), 0),
        spline_visiting(input.splines.size(), 0) {}

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

    // Validate through opaque boundaries too: splines can link back to the
    // density graph and cycles must never reach the recursive evaluator.
    void inspect_spline(SplineRef ref, NodeRef owner) {
        if (ref < 0 || static_cast<std::size_t>(ref) >= arena.splines.size()) {
            fail(CompileError::kInvalidOperand, owner); return;
        }
        auto& state = spline_visiting[ref];
        if (state == 2) return;
        if (state == 1) { fail(CompileError::kInvalidOperand, owner); return; }
        state = 1;
        const Spline& spline = arena.splines[ref];
        if (spline.kind == SplineKind::kImpl) {
            // The interpreter evaluates the location even with no breakpoints.
            if (spline.location_function != kNullRef) inspect(spline.location_function);
            const auto start = static_cast<std::size_t>(spline.breakpoints_start);
            const auto count = static_cast<std::size_t>(spline.breakpoint_count);
            if (start > arena.spline_breakpoints.size()
                || count > arena.spline_breakpoints.size() - start) {
                fail(CompileError::kInvalidOperand, owner);
            } else if (count > 0) {
                for (std::size_t i = 0; i < count; ++i)
                    inspect_spline(arena.spline_breakpoints[start + i].value, owner);
            }
        } else if (spline.kind != SplineKind::kFixedFloat) {
            fail(CompileError::kUnsupportedNode, owner);
        }
        state = 2;
    }

    bool inspect(NodeRef ref) {
        if (!valid(ref)) { fail(CompileError::kInvalidOperand, ref); return false; }
        auto& state = visiting[ref];
        if (state == 2) return pure[ref] != 0;
        if (state == 1) { fail(CompileError::kInvalidOperand, ref); return false; }
        state = 1;
        const Node& node = arena.nodes[ref];
        bool is_pure = false;
        switch (node.kind) {
            case NodeKind::kConstant: case NodeKind::kYClampedGradient:
                is_pure = true; break;
            case NodeKind::kAbs: case NodeKind::kSquare: case NodeKind::kCube:
            case NodeKind::kHalfNegative: case NodeKind::kQuarterNegative:
            case NodeKind::kInvert: case NodeKind::kSqueeze:
            case NodeKind::kMapRange: case NodeKind::kClamp:
                is_pure = inspect(node.a); break;
            case NodeKind::kAdd: case NodeKind::kMul: case NodeKind::kMin: case NodeKind::kMax:
                is_pure = inspect(node.a); is_pure &= inspect(node.b); break;
            case NodeKind::kLerp:
                is_pure = inspect(node.a); is_pure &= inspect(node.b); is_pure &= inspect(node.c); break;
            case NodeKind::kRangeChoice: case NodeKind::kShiftedNoise:
                inspect(node.a); inspect(node.b); inspect(node.c); break;
            case NodeKind::kCache2D: case NodeKind::kCacheOnce: case NodeKind::kFlatCache:
            case NodeKind::kInterpolated: case NodeKind::kBlendDensity: case NodeKind::kWeirdScaledSampler:
                inspect(node.a); break;
            case NodeKind::kCacheAllInCell:
                // JNI also creates input-free leaves backed by cell arrays.
                if (node.a != kNullRef) inspect(node.a);
                break;
            case NodeKind::kFindTopSurface:
                inspect(node.a); inspect(node.b); break;
            case NodeKind::kSpline:
                inspect_spline(node.i0, ref); break;
            case NodeKind::kNoise: case NodeKind::kShiftA: case NodeKind::kShiftB: case NodeKind::kShift:
            case NodeKind::kInterpolatedNoise: case NodeKind::kEndIslands:
            case NodeKind::kBeardifier: case NodeKind::kBlendAlpha: case NodeKind::kBlendOffset:
                break;
            default: fail(CompileError::kUnsupportedNode, ref); break;
        }
        pure[ref] = is_pure;
        state = 2;
        return is_pure;
    }

    std::uint32_t emit(const Instr& instr) {
        if (result.error != CompileError::kNone) return 0;
        if (result.program.code.size() >= kMaxProgramInstructions)
            return fail(CompileError::kProgramTooLarge, kNullRef);
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
        bool reusable = pure_operation(copy.op);
        const auto n = operands(copy.op);
        if (n > 0) reusable &= pure_values[copy.s0] != 0;
        if (n > 1) reusable &= pure_values[copy.s1] != 0;
        if (n > 2) reusable &= pure_values[copy.s2] != 0;
        const Expression key{static_cast<std::uint64_t>(copy.op),
            n > 0 ? copy.s0 : 0u, n > 1 ? copy.s1 : 0u, n > 2 ? copy.s2 : 0u,
            std::bit_cast<std::uint64_t>(copy.imm0), std::bit_cast<std::uint64_t>(copy.imm1),
            std::bit_cast<std::uint64_t>(copy.imm2), std::bit_cast<std::uint64_t>(copy.imm3),
            copy.op <= Op::kClamp ? 0u : static_cast<std::uint32_t>(copy.ref)};
        if (reusable) {
            const auto found = expressions.find(key);
            if (found != expressions.end()) { ++result.program.cse_hits; return found->second; }
        }
        copy.dst = result.program.value_count++;
        result.program.code.push_back(copy);
        is_constant.push_back(copy.op == Op::kConstant);
        constant_values.push_back(copy.op == Op::kConstant ? copy.imm0 : 0.0);
        pure_values.push_back(reusable);
        if (reusable) expressions.emplace(key, copy.dst);
        return copy.dst;
    }

    std::uint32_t child(NodeRef ref) {
        if (!valid(ref)) return fail(CompileError::kInvalidOperand, ref);
        return compile_node(ref);
    }

    // A conditionally executed definition cannot dominate code after the join.
    std::uint32_t conditional_child(NodeRef ref) {
        const auto before = slots;
        const auto before_expressions = expressions;
        const auto value = child(ref);
        slots = before;
        expressions = before_expressions;
        return value;
    }

    void write_result(Op op, std::uint32_t dst, std::uint32_t src, NodeRef ref = kNullRef) {
        Instr instr{};
        instr.op = op; instr.dst = dst; instr.s0 = src; instr.ref = ref;
        append(instr);
    }

    void append(const Instr& instr) {
        if (result.error != CompileError::kNone) return;
        if (result.program.code.size() >= kMaxProgramInstructions) {
            fail(CompileError::kProgramTooLarge, instr.ref); return;
        }
        result.program.code.push_back(instr);
    }

    std::uint32_t compile_node(NodeRef ref) {
        if (result.error != CompileError::kNone) return 0;
        if (!valid(ref)) return fail(CompileError::kInvalidOperand, ref);
        const std::size_t index = static_cast<std::size_t>(ref);
        if (pure[index] && slots[index] != std::numeric_limits<std::uint32_t>::max()) return slots[index];
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
                if (node.kind == NodeKind::kMul && node.a != node.b && !pure[node.b]) {
                    instr.s0 = child(node.a); instr.op = Op::kBranchZero;
                    if (result.error != CompileError::kNone) return 0;
                    if (is_constant[instr.s0] && constant_values[instr.s0] == 0.0) {
                        Instr zero{}; value = emit(zero); break;
                    }
                    const auto branch = result.program.code.size();
                    value = emit(instr);
                    const auto rhs = conditional_child(node.b);
                    if (result.error != CompileError::kNone) return 0;
                    Instr multiply{}; multiply.op = Op::kMul;
                    multiply.dst = value; multiply.s0 = instr.s0; multiply.s1 = rhs;
                    append(multiply);
                    result.program.code[branch].target = static_cast<std::uint32_t>(result.program.code.size());
                    break;
                }
                instr.op = static_cast<Op>(static_cast<std::uint8_t>(Op::kAdd)
                    + (static_cast<std::uint8_t>(node.kind) - static_cast<std::uint8_t>(NodeKind::kAdd)));
                instr.s0 = child(node.a);
                instr.s1 = node.a == node.b ? instr.s0 : child(node.b);
                if (node.kind == NodeKind::kMul && node.a == node.b) instr.op = Op::kSquare;
                value = emit(instr); break;
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
            case NodeKind::kNoise: case NodeKind::kShiftA: case NodeKind::kShiftB: case NodeKind::kShift:
                instr.op = node.noise_ptr ? Op::kNoise : Op::kConstant;
                instr.ref = ref; value = emit(instr); break;
            case NodeKind::kShiftedNoise:
                if (!node.noise_ptr) { value = emit(instr); break; }
                instr.op = Op::kShiftedNoise; instr.ref = ref;
                instr.s0 = child(node.a); instr.s1 = child(node.b); instr.s2 = child(node.c);
                value = emit(instr); break;
            case NodeKind::kWeirdScaledSampler:
                if (!node.noise_ptr) { value = child(node.a); break; }
                instr.op = Op::kWeirdNoise; instr.ref = ref; instr.s0 = child(node.a);
                value = emit(instr); break;
            case NodeKind::kInterpolatedNoise:
                instr.op = node.interp_noise_ptr ? Op::kInterpolatedNoise : Op::kConstant;
                instr.ref = ref; value = emit(instr); break;
            case NodeKind::kEndIslands:
                instr.op = Op::kOpaqueNoise; instr.ref = ref; value = emit(instr); break;
            case NodeKind::kCache2D: case NodeKind::kCacheOnce:
            case NodeKind::kCacheAllInCell: case NodeKind::kFlatCache:
            case NodeKind::kInterpolated: {
                if (cacheless || node.cache_slot_id < 0) {
                    ++result.program.eliminated_caches;
                    if (node.a == kNullRef) { instr.op = Op::kConstant; value = emit(instr); }
                    else value = child(node.a);
                    break;
                }
                instr.op = Op::kCacheProbe; instr.ref = ref;
                const auto probe = result.program.code.size();
                value = emit(instr);
                std::uint32_t input;
                if (node.a == kNullRef) {
                    const auto before = expressions;
                    Instr zero{}; input = emit(zero);
                    expressions = before;
                }
                else input = conditional_child(node.a);
                if (result.error != CompileError::kNone) return 0;
                write_result(Op::kCacheStore, value, input, ref);
                result.program.code[probe].target = static_cast<std::uint32_t>(result.program.code.size());
                break;
            }
            case NodeKind::kSpline:
                if (options.experimental_spline_coefficients_f32) {
                    ++result.program.spline_candidates;
                    auto coefficients = detail::spline_coefficients(arena, node.i0);
                    if (coefficients) {
                        instr.op = Op::kSplineCoefficientsF32; instr.ref = ref;
                        instr.auxiliary = static_cast<std::uint32_t>(result.program.spline_coefficients.size());
                        result.program.spline_coefficients.push_back(std::move(*coefficients));
                        value = emit(instr); break;
                    }
                }
                instr.op = Op::kOpaqueSpline; instr.ref = ref; value = emit(instr); break;
            case NodeKind::kBeardifier:
                instr.op = Op::kOpaqueBeardifier; instr.ref = ref; value = emit(instr); break;
            case NodeKind::kBlendDensity:
                value = child(node.a); break; // Current arena contract is NO_BLENDING.
            case NodeKind::kBlendAlpha: case NodeKind::kBlendOffset:
                instr.op = Op::kOpaqueBlend; instr.ref = ref; value = emit(instr); break;
            case NodeKind::kFindTopSurface:
                instr.op = Op::kOpaqueFindTopSurface; instr.ref = ref; value = emit(instr); break;
            case NodeKind::kRangeChoice: {
                instr.s0 = child(node.a); instr.op = Op::kBranchRange;
                if (result.error != CompileError::kNone) return 0;
                if (node.b == node.c) { value = child(node.b); break; }
                if (is_constant[instr.s0]) {
                    const auto selector = constant_values[instr.s0];
                    value = child(selector >= node.d0 && selector < node.d1 ? node.b : node.c);
                    break;
                }
                instr.imm0 = node.d0; instr.imm1 = node.d1;
                const auto branch = result.program.code.size();
                value = emit(instr);
                const auto yes = conditional_child(node.b);
                if (result.error != CompileError::kNone) return 0;
                write_result(Op::kCopy, value, yes);
                const auto jump = result.program.code.size();
                Instr exit{}; exit.op = Op::kJump; emit(exit);
                if (result.error != CompileError::kNone) return 0;
                result.program.code[branch].target = static_cast<std::uint32_t>(result.program.code.size());
                const auto no = conditional_child(node.c);
                if (result.error != CompileError::kNone) return 0;
                write_result(Op::kCopy, value, no);
                result.program.code[jump].target = static_cast<std::uint32_t>(result.program.code.size());
                break;
            }
            default:
                value = fail(CompileError::kUnsupportedNode, ref); break;
        }
        if (result.error != CompileError::kNone) return value;
        if (pure[index]) slots[index] = value;
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

namespace {
unsigned operands(Op op) noexcept {
    switch (op) {
        case Op::kAbs: case Op::kSquare: case Op::kCube: case Op::kHalfNegative:
        case Op::kQuarterNegative: case Op::kInvert: case Op::kSqueeze:
        case Op::kMapRange: case Op::kClamp: case Op::kCopy: case Op::kCacheStore:
        case Op::kBranchZero: case Op::kBranchRange: case Op::kWeirdNoise: return 1;
        case Op::kAdd: case Op::kMul: case Op::kMin: case Op::kMax: return 2;
        case Op::kLerp: case Op::kShiftedNoise: return 3;
        default: return 0;
    }
}
bool jumps(Op op) noexcept {
    return op == Op::kCacheProbe || op == Op::kBranchZero
        || op == Op::kBranchRange || op == Op::kJump;
}

CompileResult compile_one(const NodeArena& arena, NodeRef root, bool cacheless, CompileOptions options) {
    Builder builder(arena, cacheless, options);
    if (root < 0 || root >= static_cast<NodeRef>(arena.nodes.size())) {
        builder.result.error = CompileError::kInvalidRoot;
        builder.result.error_node = root;
        return builder.result;
    }
    builder.inspect(root);
    if (builder.result.error != CompileError::kNone) return builder.result;
    builder.result.program.result = builder.compile_node(root);
    if (builder.result.error != CompileError::kNone) return builder.result;

    Program& program = builder.result.program;
    std::vector<std::uint8_t> used(program.value_count, 0);
    used[program.result] = 1;
    // State and control instructions are roots of liveness in their own right.
    for (const auto& instr : program.code)
        if (!pure_operation(instr.op) && instr.op != Op::kCopy) used[instr.dst] = 1;
    for (auto it = program.code.rbegin(); it != program.code.rend(); ++it) {
        if (!used[it->dst]) continue;
        const unsigned n = operands(it->op);
        if (n > 0) used[it->s0] = 1;
        if (n > 1) used[it->s1] = 1;
        if (n > 2) used[it->s2] = 1;
    }

    constexpr auto absent = std::numeric_limits<std::uint32_t>::max();
    std::vector<std::uint32_t> remap(program.value_count, absent);
    std::vector<std::uint32_t> pc_map(program.code.size() + 1);
    std::vector<Instr> compact;
    std::uint32_t count = 0;
    for (std::size_t pc = 0; pc < program.code.size(); ++pc) {
        pc_map[pc] = static_cast<std::uint32_t>(compact.size());
        const auto& instr = program.code[pc];
        if (!used[instr.dst]) continue;
        Instr copy = instr;
        if (remap[instr.dst] == absent) remap[instr.dst] = count++;
        copy.dst = remap[instr.dst];
        compact.push_back(copy);
    }
    pc_map.back() = static_cast<std::uint32_t>(compact.size());
    for (Instr& instr : compact) {
        const unsigned n = operands(instr.op);
        if (n > 0) instr.s0 = remap[instr.s0];
        if (n > 1) instr.s1 = remap[instr.s1];
        if (n > 2) instr.s2 = remap[instr.s2];
        if (jumps(instr.op)) instr.target = pc_map[instr.target];
    }
    program.result = remap[program.result];
    program.value_count = count;
    program.dead_instructions = program.code.size() - compact.size();
    program.code = std::move(compact);
    std::vector<unsigned> definitions(count, 0);
    for (const auto& instr : program.code) ++definitions[instr.dst];
    program.pure_definitions.assign(count, absent);
    for (std::size_t pc = 0; pc < program.code.size(); ++pc) {
        const auto& instr = program.code[pc];
        if (definitions[instr.dst] != 1) continue;
        if (!pure_operation(instr.op)) continue;
        const auto n = operands(instr.op);
        if (n > 0 && program.pure_definitions[instr.s0] == absent) continue;
        if (n > 1 && program.pure_definitions[instr.s1] == absent) continue;
        if (n > 2 && program.pure_definitions[instr.s2] == absent) continue;
        program.pure_definitions[instr.dst] = static_cast<std::uint32_t>(pc);
    }
    return std::move(builder.result);
}
} // namespace

CompileResult compile(const NodeArena& arena, NodeRef root, CompileOptions options) noexcept {
    auto result = compile_one(arena, root, false, options);
    if (result) {
        auto no_cache = compile_one(arena, root, true, options);
        if (no_cache && no_cache.program.eliminated_caches > result.program.eliminated_caches)
            result.program.cacheless = std::make_shared<Program>(std::move(no_cache.program));
    }
    return result;
}

bool install(NodeArena& arena, NodeRef root) noexcept {
    arena.compiled_program.reset();
    arena.compiled_program_root = kNullRef;
    arena.compiled_batch_programs.clear();
    const CompileResult compiled = compile(arena, root);
    if (!compiled) return false;
    arena.compiled_program = std::make_shared<Program>(compiled.program);
    arena.compiled_program_root = root;
    return true;
}

std::size_t install_batch(NodeArena& arena) noexcept {
    arena.compiled_batch_programs.clear();
    if (arena.batch_roots.empty()) return 0;
    arena.compiled_batch_programs.resize(arena.nodes.size());
    std::size_t installed = 0;
    for (const NodeRef root : arena.batch_roots) {
        if (root < 0 || static_cast<std::size_t>(root) >= arena.nodes.size()) continue;
        auto& program = arena.compiled_batch_programs[root];
        if (!program) {
            if (arena.compiled_program && root == arena.compiled_program_root) {
                program = arena.compiled_program;
            } else {
                auto compiled = compile(arena, root);
                if (compiled) program = std::make_shared<Program>(std::move(compiled.program));
            }
        }
        if (program) ++installed;
    }
    return installed;
}

const Program* find_program(const NodeArena& arena, NodeRef root) noexcept {
    if (root < 0 || static_cast<std::size_t>(root) >= arena.nodes.size()) return nullptr;
    if (arena.compiled_program && root == arena.compiled_program_root) return arena.compiled_program.get();
    return static_cast<std::size_t>(root) < arena.compiled_batch_programs.size()
        ? arena.compiled_batch_programs[root].get() : nullptr;
}

namespace {
std::uint64_t cell_key(const Context& ctx) noexcept {
    return (static_cast<std::uint64_t>(static_cast<std::uint32_t>(ctx.cellX) & 0xFFFFFFu) << 40)
        | (static_cast<std::uint64_t>(static_cast<std::uint32_t>(ctx.cellZ) & 0xFFFFFFu) << 16)
        | (static_cast<std::uint64_t>(static_cast<std::uint32_t>(static_cast<int>(ctx.y)) & 0xFFFFu));
}

bool cache_probe(const Node& n, const Context& ctx, double& value) noexcept {
    if (!ctx.cache || n.cache_slot_id < 0) return false;
    const auto id = static_cast<std::size_t>(n.cache_slot_id);
    switch (n.kind) {
        case NodeKind::kCache2D:
            if (id < ctx.cache->cache_2d.size()) {
                const auto& s = ctx.cache->cache_2d[id];
                if (s.valid && s.x == static_cast<int>(std::floor(ctx.x))
                    && s.z == static_cast<int>(std::floor(ctx.z))) { value = s.value; return true; }
            }
            break;
        case NodeKind::kCacheOnce:
            if (id < ctx.cache->cache_once.size()) {
                const auto& s = ctx.cache->cache_once[id];
                if (s.valid && s.x == ctx.x && s.y == ctx.y && s.z == ctx.z) { value = s.value; return true; }
            }
            break;
        case NodeKind::kFlatCache:
            if (id < ctx.cache->flat_cache.size()) {
                const auto& s = ctx.cache->flat_cache[id];
                if (s.valid && s.cellX == ctx.cellX && s.cellZ == ctx.cellZ) { value = s.value; return true; }
            }
            break;
        case NodeKind::kInterpolated:
            if (ctx.cache->is_in_interpolation_loop && id < ctx.cache->interpolators.size()) {
                value = ctx.cache->interpolators[id].result; return true;
            }
            break;
        case NodeKind::kCacheAllInCell:
            if (id >= ctx.cache->cache_all_in_cell.size()) break;
            if (id < ctx.cache->cache_all_in_cell_arrays.size()) {
                const auto* data = ctx.cache->cache_all_in_cell_arrays[id];
                const auto length = ctx.cache->cache_all_in_cell_array_lengths[id];
                const auto offset = ctx.cache->cache_all_in_cell_array_offsets[id];
                if (data && ctx.inCellX >= 0 && ctx.inCellY >= 0 && ctx.inCellZ >= 0
                    && ctx.inCellX < ctx.cellWidth && ctx.inCellY < ctx.cellHeight && ctx.inCellZ < ctx.cellWidth) {
                    const auto index = (static_cast<std::size_t>(ctx.cellHeight - 1 - ctx.inCellY)
                        * static_cast<std::size_t>(ctx.cellWidth) + static_cast<std::size_t>(ctx.inCellX))
                        * static_cast<std::size_t>(ctx.cellWidth) + static_cast<std::size_t>(ctx.inCellZ);
                    if (offset <= length && index < length - offset) { value = data[offset + index]; return true; }
                }
            }
            if (n.a == kNullRef) { value = 0.0; return true; }
            if (const auto* found = ctx.cache->cache_all_in_cell[id].find(cell_key(ctx))) {
                value = *found; return true;
            }
            break;
        default: break;
    }
    return false;
}

void cache_store(const Node& n, const Context& ctx, double value) noexcept {
    if (!ctx.cache || n.cache_slot_id < 0) return;
    const auto id = static_cast<std::size_t>(n.cache_slot_id);
    switch (n.kind) {
        case NodeKind::kCache2D:
            if (id < ctx.cache->cache_2d.size()) {
                auto& s = ctx.cache->cache_2d[id]; s.valid = true;
                s.x = static_cast<int>(std::floor(ctx.x)); s.z = static_cast<int>(std::floor(ctx.z)); s.value = value;
            }
            break;
        case NodeKind::kCacheOnce:
            if (id < ctx.cache->cache_once.size()) {
                auto& s = ctx.cache->cache_once[id]; s.valid = true;
                s.x = ctx.x; s.y = ctx.y; s.z = ctx.z; s.value = value;
            }
            break;
        case NodeKind::kFlatCache:
            if (id < ctx.cache->flat_cache.size()) {
                auto& s = ctx.cache->flat_cache[id]; s.valid = true;
                s.cellX = ctx.cellX; s.cellZ = ctx.cellZ; s.value = value;
            }
            break;
        case NodeKind::kCacheAllInCell:
            if (n.a != kNullRef && id < ctx.cache->cache_all_in_cell.size())
                ctx.cache->cache_all_in_cell[id].get_or_insert(cell_key(ctx)) = value;
            break;
        default: break;
    }
}
} // namespace

namespace {
double scalar_math(const Instr& instr, const Context& ctx, double a, double b, double c) noexcept {
    switch (instr.op) {
            case Op::kConstant: return instr.imm0;
            case Op::kCoordX: return ctx.x;
            case Op::kCoordY: return ctx.y;
            case Op::kCoordZ: return ctx.z;
            case Op::kAbs: return std::abs(a);
            case Op::kSquare: return a * a;
            case Op::kCube: return a * a * a;
            case Op::kHalfNegative: return half_negative(a);
            case Op::kQuarterNegative: return quarter_negative(a);
            case Op::kInvert: return 1.0 / a;
            case Op::kSqueeze: return squeeze(a);
            case Op::kAdd: return a + b;
            case Op::kMul: return a == 0.0 ? 0.0 : a * b;
            case Op::kMin: return std::min(a, b);
            case Op::kMax: return std::max(a, b);
            case Op::kYClampedGradient: return y_gradient(instr.imm0, instr.imm1, instr.imm2, instr.imm3, ctx.y);
            case Op::kMapRange: return map_range(a, instr.imm0, instr.imm1, instr.imm2, instr.imm3);
            case Op::kLerp: return b + a * (c - b);
            case Op::kClamp: return clamp_d(a, instr.imm0, instr.imm1);
        default: return 0.0;
    }
}

struct BatchState {
    const Program& program;
    const NodeArena& arena;
    const Context* contexts;
    std::size_t count;
    BatchScratch& scratch;
    bool vector_math;

    bool is_pure(std::uint32_t slot) const noexcept {
        return slot < program.pure_definitions.size()
            && program.pure_definitions[slot] != std::numeric_limits<std::uint32_t>::max();
    }

    void prepare(std::uint32_t slot) noexcept {
        if (!is_pure(slot)) return;
        const unsigned all = (1u << count) - 1u;
        if (scratch.ready[slot] == all) return;
        const Instr& instr = program.code[program.pure_definitions[slot]];
        const auto n = operands(instr.op);
        unsigned mask = all;
        const std::uint32_t inputs[]{instr.s0, instr.s1, instr.s2};
        for (unsigned i = 0; i < n; ++i) {
            prepare(inputs[i]); mask &= scratch.ready[inputs[i]];
        }
        mask &= ~scratch.ready[slot];
        if (mask == 0) return;
        const auto source = [&](unsigned i, std::size_t lane) {
            return i < n ? scratch.memo[inputs[i] * 4u + lane] : 0.0;
        };
        if (instr.op >= Op::kNoise && instr.op <= Op::kInterpolatedNoise) {
            const Node& node = arena.nodes[instr.ref];
            if (node.kind == NodeKind::kWeirdScaledSampler
                && (!std::isfinite(node.d0) || node.d0 < -2147483648.0 || node.d0 >= 2147483648.0)) return;
            double x[4], y[4], z[4], scales[4], output[4];
            std::size_t lanes[4], active = 0;
            for (std::size_t lane = 0; lane < count; ++lane) {
                if (!(mask & (1u << lane))) continue;
                const auto point = detail::noise_point(node, contexts[lane], source(0, lane), source(1, lane), source(2, lane));
                if (!detail::safe_noise(node, point)) continue;
                lanes[active] = lane; x[active] = point.x; y[active] = point.y;
                z[active] = point.z; scales[active++] = point.scale;
            }
            if (active == 0) return;
            if (node.kind == NodeKind::kInterpolatedNoise)
                noise::sample_batch(*node.interp_noise_ptr, x, y, z, active, output);
            else noise::sample_batch(*node.noise_ptr, x, y, z, active, output);
            for (std::size_t i = 0; i < active; ++i) {
                scratch.memo[slot * 4u + lanes[i]] = detail::finish_noise(node, output[i], scales[i]);
                scratch.ready[slot] |= static_cast<std::uint8_t>(1u << lanes[i]);
            }
            if (contexts[0].cache && contexts[0].cache->execution_stats) {
                auto& stats = *contexts[0].cache->execution_stats;
                ++stats.compiled_noise_batches; stats.compiled_noise_points += active;
            }
        } else {
#if defined(LATTICE_HAS_DENSITY_AVX2)
            if (vector_math && count == 4 && mask == 15
                && evaluate_math_avx2(instr, contexts,
                    n > 0 ? &scratch.memo[inputs[0] * 4u] : nullptr,
                    n > 1 ? &scratch.memo[inputs[1] * 4u] : nullptr,
                    n > 2 ? &scratch.memo[inputs[2] * 4u] : nullptr,
                    &scratch.memo[slot * 4u])) {
                scratch.ready[slot] = 15;
                if (contexts[0].cache && contexts[0].cache->execution_stats) {
                    auto& stats = *contexts[0].cache->execution_stats;
                    ++stats.compiled_avx2_ops; stats.compiled_avx2_lanes += 4;
                }
                return;
            }
#endif
            for (std::size_t lane = 0; lane < count; ++lane) if (mask & (1u << lane)) {
                scratch.memo[slot * 4u + lane] = scalar_math(instr, contexts[lane], source(0, lane), source(1, lane), source(2, lane));
                scratch.ready[slot] |= static_cast<std::uint8_t>(1u << lane);
            }
        }
    }
};

double run(const Program& program, const NodeArena& arena, const Context& ctx,
           double* values, std::size_t value_capacity, BatchState* batch, std::size_t lane) noexcept {
    if (!values || value_capacity < program.value_count
        || program.value_count == 0 || program.result >= program.value_count) return 0.0;
    const auto read_slot = [&](std::uint32_t slot) noexcept {
        return slot < program.value_count ? values[slot] : 0.0;
    };
    for (std::size_t pc = 0; pc < program.code.size();) {
        const Instr& instr = program.code[pc++];
        if (batch && batch->is_pure(instr.dst)) {
            batch->prepare(instr.dst);
            if (batch->scratch.ready[instr.dst] & (1u << lane)) {
                values[instr.dst] = batch->scratch.memo[instr.dst * 4u + lane];
                continue;
            }
        }
        double value = 0.0;
        // Read only real operands: leaf/opaque instructions can be first in a
        // caller-provided, uninitialised scratch buffer.
        double a = 0.0, b = 0.0, c = 0.0;
        switch (instr.op) {
            case Op::kShiftedNoise:
                a = read_slot(instr.s0); b = read_slot(instr.s1); c = read_slot(instr.s2); break;
            case Op::kLerp: c = read_slot(instr.s2); [[fallthrough]];
            case Op::kAdd: case Op::kMul: case Op::kMin: case Op::kMax:
                b = read_slot(instr.s1); [[fallthrough]];
            case Op::kAbs: case Op::kSquare: case Op::kCube:
            case Op::kHalfNegative: case Op::kQuarterNegative: case Op::kInvert:
            case Op::kSqueeze: case Op::kMapRange: case Op::kClamp:
            case Op::kWeirdNoise: case Op::kCacheStore: case Op::kBranchZero: case Op::kBranchRange: case Op::kCopy:
                a = read_slot(instr.s0); break;
            default: break;
        }
        switch (instr.op) {
            case Op::kCopy: value = a; break;
            case Op::kCacheProbe:
                if (cache_probe(arena.nodes[instr.ref], ctx, value)) pc = instr.target;
                else continue;
                break;
            case Op::kCacheStore:
                value = a; cache_store(arena.nodes[instr.ref], ctx, value); break;
            case Op::kBranchZero:
                if (a == 0.0) { value = 0.0; pc = instr.target; }
                else continue;
                break;
            case Op::kBranchRange:
                if (!(a >= instr.imm0 && a < instr.imm1)) pc = instr.target;
                continue;
            case Op::kJump: pc = instr.target; continue;
            case Op::kOpaqueNoise: case Op::kOpaqueCache: case Op::kOpaqueInterpolated:
            case Op::kOpaqueSpline: case Op::kOpaqueBeardifier: case Op::kOpaqueBlend:
            case Op::kOpaqueFindTopSurface: case Op::kOpaqueRangeChoice: case Op::kOpaqueMul:
                value = evaluate_node(arena, instr.ref, ctx); break;
            case Op::kNoise: case Op::kShiftedNoise: case Op::kWeirdNoise: case Op::kInterpolatedNoise:
                value = detail::scalar_noise(arena.nodes[instr.ref], ctx, a, b, c); break;
            case Op::kSplineCoefficientsF32:
                value = detail::evaluate_spline_coefficients(program.spline_coefficients[instr.auxiliary],
                    arena, arena.nodes[instr.ref].i0, ctx); break;
            default: value = scalar_math(instr, ctx, a, b, c); break;
        }
        if (instr.dst < program.value_count) values[instr.dst] = value;
        if (batch && batch->is_pure(instr.dst)) {
            batch->scratch.memo[instr.dst * 4u + lane] = value;
            batch->scratch.ready[instr.dst] |= static_cast<std::uint8_t>(1u << lane);
        }
    }
    return values[program.result];
}

} // namespace

double evaluate(const Program& program, const NodeArena& arena, const Context& ctx,
                double* values, std::size_t value_capacity) noexcept {
    const auto& selected = !ctx.cache && program.cacheless ? *program.cacheless : program;
    return run(selected, arena, ctx, values, value_capacity, nullptr, 0);
}

void evaluate_batch(const Program& program, const NodeArena& arena,
                    const Context* contexts, std::size_t count, double* out, BatchScratch& scratch,
                    BatchBackend backend) noexcept {
    if (!contexts || !out || count == 0) return;
    for (std::size_t first = 0; first < count;) {
        const bool cacheless = !contexts[first].cache && program.cacheless;
        std::size_t lanes = 1;
        while (lanes < 4 && first + lanes < count
            && (!contexts[first + lanes].cache && program.cacheless) == cacheless) ++lanes;
        const Program& selected = cacheless ? *program.cacheless : program;
        if (scratch.values.size() < selected.value_count) scratch.values.resize(selected.value_count);
        if (scratch.memo.size() < selected.value_count * 4u) scratch.memo.resize(selected.value_count * 4u);
        scratch.ready.assign(selected.value_count, 0);
        BatchState state{selected, arena, contexts + first, lanes, scratch,
            backend == BatchBackend::kAuto && lattice::cpu::features().avx2};
        for (std::size_t lane = 0; lane < lanes; ++lane)
            out[first + lane] = run(selected, arena, contexts[first + lane], scratch.values.data(), scratch.values.size(), &state, lane);
        first += lanes;
    }
}

double evaluate(const Program& program, const NodeArena& arena, const Context& ctx) noexcept {
    std::vector<double> values(program.value_count, 0.0);
    return evaluate(program, arena, ctx, values.data(), values.size());
}

const char* compile_error_name(CompileError error) noexcept {
    switch (error) {
        case CompileError::kNone: return "none";
        case CompileError::kInvalidRoot: return "invalid-root";
        case CompileError::kUnsupportedNode: return "unsupported-node";
        case CompileError::kInvalidOperand: return "invalid-operand";
        case CompileError::kProgramTooLarge: return "program-too-large";
    }
    return "unknown";
}

} // namespace lattice::world::gen::densityfunction::dfc
