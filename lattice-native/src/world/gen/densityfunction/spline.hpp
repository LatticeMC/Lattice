/**
 * @file spline.hpp
 * @brief Bit-exact port of `net.minecraft.util.math.Spline` as used by
 *        density-function trees (`DensityFunctionTypes.Spline`).
 *
 * Mojang's Spline is a recursive cubic-Hermite interpolation tree:
 *
 *   - `FixedFloatFunction(value)` — a leaf returning a constant.
 *   - `Implementation(locationFn, points...)` — a list of breakpoints
 *     {location: f32, value: Spline, derivative: f32} sampled by
 *     evaluating `locationFn(x)` and finding the surrounding pair.
 *     Outside the registered range it linearly extrapolates with the
 *     boundary derivative; inside, it cubic-Hermite-blends between
 *     `value[i]` and `value[i+1]` with their derivatives.
 *
 * In our C++ representation a Spline tree shares the parent
 * `NodeArena`: an array of `Spline` records plus an array of
 * `SplineBreakpoint` records. Each breakpoint references another
 * `SplineRef`, which is an index back into the same arena's
 * `splines` vector. A `FixedFloat` spline is a single record; a
 * non-trivial spline is one record + N breakpoints.
 *
 * Construction is bottom-up: leaves first, then the composing
 * Implementation. The DF NodeKind that wraps a spline (`kSpline`)
 * stores a root SplineRef in `n.i0`.
 *
 * The location function for `Implementation` is a regular DF NodeRef,
 * evaluated with the same Context that the spline is being evaluated
 * with. Mojang's `SplinePos` is a thin wrapper around `NoisePos`; we
 * just pass `Context` directly.
 */

#pragma once

#include <cstdint>

namespace lattice::world::gen::densityfunction {

/// Index into `NodeArena::splines`. -1 = invalid.
using SplineRef = std::int32_t;
inline constexpr SplineRef kNullSplineRef = -1;

enum class SplineKind : std::uint8_t {
    kFixedFloat,
    kImpl,
};

/// One breakpoint in an Implementation spline. `value` is itself a
/// SplineRef (so breakpoints can hold sub-splines); `derivative` is
/// the slope at this anchor.
struct SplineBreakpoint {
    float     location;
    float     derivative;
    SplineRef value;
};

struct Spline {
    SplineKind kind;
    // FixedFloat:
    float      fixed_value = 0.0f;
    // Impl:
    /// NodeRef of the location-function DF (-1 = invalid).
    std::int32_t location_function = -1;
    /// Range [breakpoints_start, breakpoints_start + breakpoint_count)
    /// in the arena's `spline_breakpoints` vector.
    int          breakpoints_start = 0;
    int          breakpoint_count  = 0;
};

} // namespace lattice::world::gen::densityfunction
