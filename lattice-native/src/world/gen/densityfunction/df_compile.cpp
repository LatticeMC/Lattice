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
    bool cacheless;
    CompileResult result;
    std::vector<std::uint32_t> slots;
    std::vector<std::uint8_t> visiting;
    std::vector<std::uint8_t> pure;
    std::vector<std::uint8_t> spline_visiting;
    std::vector<std::uint8_t> is_constant;
    std::vector<double> constant_values;

    explicit Builder(const NodeArena& input, bool without_cache = false) : arena(input), cacheless(without_cache),
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

    // A conditionally executed definition cannot dominate code after the join.
    std::uint32_t conditional_child(NodeRef ref) {
        const auto before = slots;
        const auto value = child(ref);
        slots = before;
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
            case NodeKind::kNoise: case NodeKind::kShiftedNoise:
            case NodeKind::kShiftA: case NodeKind::kShiftB: case NodeKind::kShift:
            case NodeKind::kWeirdScaledSampler: case NodeKind::kEndIslands: case NodeKind::kInterpolatedNoise:
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
                if (node.a == kNullRef) { Instr zero{}; input = emit(zero); }
                else input = conditional_child(node.a);
                if (result.error != CompileError::kNone) return 0;
                write_result(Op::kCacheStore, value, input, ref);
                result.program.code[probe].target = static_cast<std::uint32_t>(result.program.code.size());
                break;
            }
            case NodeKind::kSpline:
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
                if (node.b == node.c) { value = child(node.b); break; }
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
        case Op::kBranchZero: case Op::kBranchRange: return 1;
        case Op::kAdd: case Op::kMul: case Op::kMin: case Op::kMax: return 2;
        case Op::kLerp: return 3;
        default: return 0;
    }
}
bool jumps(Op op) noexcept {
    return op == Op::kCacheProbe || op == Op::kBranchZero
        || op == Op::kBranchRange || op == Op::kJump;
}

CompileResult compile_one(const NodeArena& arena, NodeRef root, bool cacheless) {
    Builder builder(arena, cacheless);
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
        if (instr.op >= Op::kOpaqueNoise && instr.op != Op::kCopy) used[instr.dst] = 1;
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
    program.code = std::move(compact);
    return std::move(builder.result);
}
} // namespace

CompileResult compile(const NodeArena& arena, NodeRef root) noexcept {
    auto result = compile_one(arena, root, false);
    if (result) {
        auto no_cache = compile_one(arena, root, true);
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

double evaluate(const Program& program, const NodeArena& arena, const Context& ctx,
                double* values, std::size_t value_capacity) noexcept {
    if (!ctx.cache && program.cacheless)
        return evaluate(*program.cacheless, arena, ctx, values, value_capacity);
    if (!values || value_capacity < program.value_count
        || program.value_count == 0 || program.result >= program.value_count) return 0.0;
    const auto read_slot = [&](std::uint32_t slot) noexcept {
        return slot < program.value_count ? values[slot] : 0.0;
    };
    for (std::size_t pc = 0; pc < program.code.size();) {
        const Instr& instr = program.code[pc++];
        double value = 0.0;
        // Read only real operands: leaf/opaque instructions can be first in a
        // caller-provided, uninitialised scratch buffer.
        double a = 0.0, b = 0.0, c = 0.0;
        switch (instr.op) {
            case Op::kLerp: c = read_slot(instr.s2); [[fallthrough]];
            case Op::kAdd: case Op::kMul: case Op::kMin: case Op::kMax:
                b = read_slot(instr.s1); [[fallthrough]];
            case Op::kAbs: case Op::kSquare: case Op::kCube:
            case Op::kHalfNegative: case Op::kQuarterNegative: case Op::kInvert:
            case Op::kSqueeze: case Op::kMapRange: case Op::kClamp:
            case Op::kCacheStore: case Op::kBranchZero: case Op::kBranchRange: case Op::kCopy:
                a = read_slot(instr.s0); break;
            default: break;
        }
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
        }
        if (instr.dst < program.value_count) values[instr.dst] = value;
    }
    return values[program.result];
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
