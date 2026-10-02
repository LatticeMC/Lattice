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
};

struct Program {
    std::vector<Instr> code;
    std::uint32_t value_count = 0;
    std::uint32_t result = 0;
};

enum class CompileError : std::uint8_t {
    kNone,
    kInvalidRoot,
    kUnsupportedNode,
    kInvalidOperand,
};

struct CompileResult {
    Program program;
    CompileError error = CompileError::kNone;
    NodeRef error_node = kNullRef;

    [[nodiscard]] explicit operator bool() const noexcept {
        return error == CompileError::kNone;
    }
};

/// Compile only pure, stateless nodes. Cache/noise/spline/interpolator nodes
/// are rejected so flattening cannot change evaluation side effects.
[[nodiscard]] CompileResult compile(const NodeArena& arena, NodeRef root) noexcept;

/// Compile and install a pure program on an arena after construction. Returns
/// false for unsupported or invalid graphs; the arena remains on the recursive
/// evaluator in that case.
[[nodiscard]] bool install(NodeArena& arena, NodeRef root) noexcept;

[[nodiscard]] double evaluate(const Program& program, const Context& ctx) noexcept;
[[nodiscard]] double evaluate(const Program& program, const Context& ctx,
                              double* values, std::size_t value_capacity) noexcept;

[[nodiscard]] const char* compile_error_name(CompileError error) noexcept;

} // namespace lattice::world::gen::densityfunction::dfc
