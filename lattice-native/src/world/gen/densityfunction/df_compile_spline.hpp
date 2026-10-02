#pragma once

#include <cmath>
#include <optional>

#include "world/gen/densityfunction/df_compile.hpp"

namespace lattice::world::gen::densityfunction::dfc::detail {

inline std::optional<SplineCoefficientsF32> spline_coefficients(const NodeArena& arena, SplineRef ref) {
    const Spline& spline = arena.splines[ref]; // Graph validation precedes this pass.
    if (spline.kind != SplineKind::kImpl || spline.breakpoint_count < 2) return std::nullopt;
    const auto* points = arena.spline_breakpoints.data() + spline.breakpoints_start;
    SplineCoefficientsF32 result;
    for (int i = 0; i < spline.breakpoint_count; ++i) {
        const auto& p = points[i];
        if (arena.splines[p.value].kind != SplineKind::kFixedFloat
            || !std::isfinite(arena.splines[p.value].fixed_value)
            || !std::isfinite(p.location) || !std::isfinite(p.derivative)) return std::nullopt;
        if (i == 0) continue;
        const auto& previous = points[i - 1];
        const float span = p.location - previous.location;
        if (!(p.location > previous.location) || !std::isfinite(span)) return std::nullopt;
        const SplineSegmentF32 segment{1.0f / span, previous.derivative * span, -p.derivative * span};
        if (segment.reciprocal_span == 0.0f || !std::isfinite(segment.reciprocal_span)
            || !std::isfinite(segment.left_slope_span) || !std::isfinite(segment.neg_right_slope_span)) return std::nullopt;
        result.segments.push_back(segment);
    }
    return result;
}

inline double evaluate_spline_coefficients(const SplineCoefficientsF32& coefficients,
                                           const NodeArena& arena, SplineRef ref, const Context& ctx) noexcept {
    const Spline& spline = arena.splines[ref];
    // Only these Spline operations use float, as in the vanilla Spline oracle.
    const float f = static_cast<float>(spline.location_function >= 0
        ? evaluate_node(arena, spline.location_function, ctx) : 0.0);
    const auto* points = arena.spline_breakpoints.data() + spline.breakpoints_start;
    int lo = 0, hi = spline.breakpoint_count;
    while (lo < hi) {
        const int mid = (lo + hi) >> 1;
        if (f < points[mid].location) hi = mid;
        else lo = mid + 1;
    }
    const int i = lo - 1;
    if (i < 0 || i == spline.breakpoint_count - 1) {
        const auto& p = points[i < 0 ? 0 : i];
        const float value = arena.splines[p.value].fixed_value;
        const float output = p.derivative == 0.0f ? value : value + p.derivative * (f - p.location);
        return static_cast<double>(output);
    }
    const auto& left = points[i];
    const auto& right = points[i + 1];
    const auto& segment = coefficients.segments[i];
    const float k = (f - left.location) * segment.reciprocal_span;
    const float a = arena.splines[left.value].fixed_value;
    const float b = arena.splines[right.value].fixed_value;
    const float p = segment.left_slope_span - (b - a);
    const float q = segment.neg_right_slope_span + (b - a);
    const float output = (a + k * (b - a)) + k * (1.0f - k) * (p + k * (q - p));
    return static_cast<double>(output);
}
} // namespace lattice::world::gen::densityfunction::dfc::detail
