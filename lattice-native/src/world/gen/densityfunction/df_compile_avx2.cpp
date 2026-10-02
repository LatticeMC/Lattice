#include "world/gen/densityfunction/df_compile.hpp"

#include <immintrin.h>
#include <cmath>
#include <limits>

#if defined(_MSC_VER)
#pragma float_control(precise, on)
#pragma fp_contract(off)
#endif

namespace lattice::world::gen::densityfunction::dfc {
namespace {
__m256d splat(double x) noexcept { return _mm256_set1_pd(x); }
__m256d minimum(__m256d a, __m256d b) noexcept {
    return _mm256_blendv_pd(a, b, _mm256_cmp_pd(b, a, _CMP_LT_OQ));
}
__m256d maximum(__m256d a, __m256d b) noexcept {
    return _mm256_blendv_pd(a, b, _mm256_cmp_pd(a, b, _CMP_LT_OQ));
}
__m256d clamp(__m256d x, double lo, double hi) noexcept {
    return maximum(splat(lo), minimum(splat(hi), x));
}
bool finite(__m256d x) noexcept {
    const auto magnitude = _mm256_andnot_pd(splat(-0.0), x);
    return _mm256_movemask_pd(_mm256_cmp_pd(magnitude, splat(std::numeric_limits<double>::max()), _CMP_LE_OQ)) == 15;
}
} // namespace

bool evaluate_math_avx2(const Instr& instr, const Context* ctx, const double* a_values,
                        const double* b_values, const double* c_values, double* output) noexcept {
    // Operand pointers are null for leaves; never load an unused register.
    const __m256d a = a_values ? _mm256_loadu_pd(a_values) : _mm256_setzero_pd();
    const __m256d b = b_values ? _mm256_loadu_pd(b_values) : _mm256_setzero_pd();
    const __m256d c = c_values ? _mm256_loadu_pd(c_values) : _mm256_setzero_pd();
    // NaN payload selection can differ between scalar and packed codegen.
    // Keep the scalar implementation as the oracle for nonfinite arithmetic.
    if (!finite(a) || !finite(b) || !finite(c)
        || !std::isfinite(instr.imm0) || !std::isfinite(instr.imm1)
        || !std::isfinite(instr.imm2) || !std::isfinite(instr.imm3)) return false;
    __m256d value;
    switch (instr.op) {
        case Op::kConstant: value = splat(instr.imm0); break;
        case Op::kCoordX: value = _mm256_setr_pd(ctx[0].x, ctx[1].x, ctx[2].x, ctx[3].x); break;
        case Op::kCoordY: value = _mm256_setr_pd(ctx[0].y, ctx[1].y, ctx[2].y, ctx[3].y); break;
        case Op::kCoordZ: value = _mm256_setr_pd(ctx[0].z, ctx[1].z, ctx[2].z, ctx[3].z); break;
        case Op::kAbs: value = _mm256_andnot_pd(splat(-0.0), a); break;
        case Op::kSquare: value = _mm256_mul_pd(a, a); break;
        case Op::kCube: value = _mm256_mul_pd(_mm256_mul_pd(a, a), a); break;
        case Op::kHalfNegative: case Op::kQuarterNegative:
            value = _mm256_blendv_pd(a, _mm256_mul_pd(a, splat(instr.op == Op::kHalfNegative ? 0.5 : 0.25)),
                _mm256_cmp_pd(a, _mm256_setzero_pd(), _CMP_LT_OQ)); break;
        case Op::kInvert: value = _mm256_div_pd(splat(1.0), a); break;
        case Op::kSqueeze: {
            const auto x = clamp(a, -1.0, 1.0);
            value = _mm256_sub_pd(_mm256_mul_pd(x, splat(0.5)),
                _mm256_div_pd(_mm256_mul_pd(_mm256_mul_pd(x, x), x), splat(24.0))); break;
        }
        case Op::kAdd: value = _mm256_add_pd(a, b); break;
        case Op::kMul:
            value = _mm256_blendv_pd(_mm256_mul_pd(a, b), _mm256_setzero_pd(),
                _mm256_cmp_pd(a, _mm256_setzero_pd(), _CMP_EQ_OQ)); break;
        case Op::kMin: value = minimum(a, b); break;
        case Op::kMax: value = maximum(a, b); break;
        case Op::kYClampedGradient: {
            const double dy = instr.imm1 - instr.imm0;
            if (dy == 0.0) { value = splat((instr.imm2 + instr.imm3) * 0.5); break; }
            const auto y = _mm256_setr_pd(ctx[0].y, ctx[1].y, ctx[2].y, ctx[3].y);
            if (!finite(y)) return false;
            const auto t = _mm256_div_pd(_mm256_sub_pd(clamp(y, instr.imm0, instr.imm1), splat(instr.imm0)), splat(dy));
            value = _mm256_add_pd(splat(instr.imm2), _mm256_mul_pd(t, splat(instr.imm3 - instr.imm2))); break;
        }
        case Op::kMapRange: {
            const auto t = _mm256_div_pd(_mm256_sub_pd(a, splat(instr.imm0)), splat(instr.imm1 - instr.imm0));
            value = _mm256_add_pd(splat(instr.imm2), _mm256_mul_pd(t, splat(instr.imm3 - instr.imm2))); break;
        }
        case Op::kLerp: value = _mm256_add_pd(b, _mm256_mul_pd(a, _mm256_sub_pd(c, b))); break;
        case Op::kClamp: value = clamp(a, instr.imm0, instr.imm1); break;
        default: return false;
    }
    _mm256_storeu_pd(output, value);
    return true;
}
} // namespace lattice::world::gen::densityfunction::dfc
