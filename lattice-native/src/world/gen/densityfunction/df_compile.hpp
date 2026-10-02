#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "world/gen/densityfunction/density_function.hpp"

namespace lattice::world::gen::densityfunction::dfc {

enum class Op : std::uint8_t {
    kConstant,
    kCoordX,
    kCoordY,
    kCoordZ,
    kAbs,
    kSquare,
    kCube,
    kHalfNegative,
    kQuarterNegative,
    kInvert,
    kSqueeze,
    kAdd,
    kMul,
    kMin,
    kMax,
    kYClampedGradient,
    kMapRange,
    kLerp,
    kClamp,
    kOpaqueNoise,
    kOpaqueCache,
    kOpaqueInterpolated,
    kOpaqueSpline,
    kOpaqueBeardifier,
    kOpaqueBlend,
    kOpaqueFindTopSurface,
    kOpaqueRangeChoice,
    kOpaqueMul,
    kCacheProbe,
    kCacheStore,
    kBranchZero,
    kBranchRange,
    kJump,
    kCopy,
};

struct Instr {
    Op op = Op::kConstant;
    std::uint32_t dst = 0;
    std::uint32_t s0 = 0;
    std::uint32_t s1 = 0;
    std::uint32_t s2 = 0;
    double imm0 = 0.0;
    double imm1 = 0.0;
    double imm2 = 0.0;
    double imm3 = 0.0;
    NodeRef ref = kNullRef;
    std::uint32_t target = 0; // Absolute forward instruction index.
};

struct Program {
    std::vector<Instr> code;
    std::uint32_t value_count = 0;
    std::uint32_t result = 0;
    // Selected only when Context::cache is null; never discards warm state.
    std::shared_ptr<const Program> cacheless;
    std::size_t eliminated_caches = 0;

    [[nodiscard]] std::size_t opaque_count() const noexcept {
        std::size_t count = 0;
        for (const Instr& instr : code)
            count += instr.op >= Op::kOpaqueNoise && instr.op <= Op::kOpaqueMul;
        return count;
    }
};

enum class CompileError : std::uint8_t {
    kNone,
    kInvalidRoot,
    kUnsupportedNode,
    kInvalidOperand,
    kProgramTooLarge,
};

inline constexpr std::size_t kMaxProgramInstructions = 65536;

struct CompileResult {
    Program program;
    CompileError error = CompileError::kNone;
    NodeRef error_node = kNullRef;

    [[nodiscard]] explicit operator bool() const noexcept {
        return error == CompileError::kNone;
    }
};

/// Compile arithmetic around opaque recursive subtrees. Only fully pure
/// subtrees may reuse values. The source arena must stay frozen during use.
[[nodiscard]] CompileResult compile(const NodeArena& arena, NodeRef root) noexcept;

/// Compile and install a hybrid program on an arena after construction. Returns
/// false for unknown kinds or invalid graphs; the arena remains on the recursive
/// evaluator in that case.
[[nodiscard]] bool install(NodeArena& arena, NodeRef root) noexcept;

/// Install the frozen arena.batch_roots independently. Returns the number of
/// successful root entries (including duplicates); failed roots keep fallback.
[[nodiscard]] std::size_t install_batch(NodeArena& arena) noexcept;

[[nodiscard]] const Program* find_program(const NodeArena& arena, NodeRef root) noexcept;

/// Pass the arena from which program was compiled (or its unchanged copy).
[[nodiscard]] double evaluate(const Program& program, const NodeArena& arena, const Context& ctx) noexcept;
[[nodiscard]] double evaluate(const Program& program, const NodeArena& arena, const Context& ctx,
                              double* values, std::size_t value_capacity) noexcept;

[[nodiscard]] const char* compile_error_name(CompileError error) noexcept;

} // namespace lattice::world::gen::densityfunction::dfc
