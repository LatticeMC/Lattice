#pragma once

#include <cmath>
#include <limits>

#include "world/gen/densityfunction/density_function.hpp"

namespace lattice::world::gen::densityfunction::dfc::detail {

struct NoisePoint {
    double x, y, z, scale = 1.0;
};

inline NoisePoint noise_point(const Node& n, const Context& ctx, double a, double b, double c) noexcept {
    switch (n.kind) {
        case NodeKind::kShiftA: return {ctx.x * 0.25, 0.0, ctx.z * 0.25, 4.0};
        case NodeKind::kShiftB: return {ctx.z * 0.25, ctx.x * 0.25, 0.0, 4.0};
        case NodeKind::kShift: return {ctx.x * 0.25, ctx.y * 0.25, ctx.z * 0.25, 4.0};
        case NodeKind::kShiftedNoise:
            return {ctx.x * n.d0 + a, ctx.y * n.d1 + b, ctx.z * n.d0 + c};
        case NodeKind::kWeirdScaledSampler: {
            const int type = static_cast<int>(n.d0);
            const double rarity = type == 1
                ? (a < -0.75 ? 0.5 : a < -0.5 ? 0.75 : a < 0.5 ? 1.0 : a < 0.75 ? 2.0 : 3.0)
                : (a < -0.5 ? 0.75 : a < 0.0 ? 1.0 : a < 0.5 ? 1.5 : 2.0);
            return {ctx.x / rarity, ctx.y / rarity, ctx.z / rarity, rarity};
        }
        case NodeKind::kInterpolatedNoise: return {ctx.x, ctx.y, ctx.z};
        default: return {ctx.x * n.d0, ctx.y * n.d1, ctx.z * n.d0};
    }
}

inline double finish_noise(const Node& n, double value, double scale) noexcept {
    if (n.kind == NodeKind::kWeirdScaledSampler) return std::abs(value) * scale;
    if (n.kind == NodeKind::kShiftA || n.kind == NodeKind::kShiftB || n.kind == NodeKind::kShift)
        return value * scale;
    return value;
}

inline double scalar_noise(const Node& n, const Context& ctx, double a, double b, double c) noexcept {
    const auto p = noise_point(n, ctx, a, b, c);
    const double value = n.kind == NodeKind::kInterpolatedNoise
        ? noise::sample(*n.interp_noise_ptr, p.x, p.y, p.z)
        : noise::sample(*n.noise_ptr, p.x, p.y, p.z);
    return finish_noise(n, value, p.scale);
}

// Eligibility for speculative sampling, not a replacement for runtime errors.
// Follow the actual coordinate transformation through every active octave.
// Noneligible lanes remain unevaluated until the original CFG reaches them.
inline bool safe_lattice(const noise::PerlinNoiseSampler& s, double x, double y, double z) noexcept {
    for (const double value : {x + s.origin_x, y + s.origin_y, z + s.origin_z}) {
        if (!std::isfinite(value) || value < -2147483000.0 || value > 2147483000.0) return false;
    }
    return true;
}

inline bool safe_octaves(const noise::OctavePerlinNoiseSampler& s, double x, double y, double z) noexcept {
    // The existing sampler treats either missing array as an empty sampler.
    if (!s.octaves || !s.amplitudes) return true;
    double frequency = s.lacunarity;
    for (std::size_t i = 0; i < s.octave_count; ++i) {
        if (s.amplitudes[i] != 0.0 && !safe_lattice(s.octaves[i],
            noise::maintain_precision(x * frequency), noise::maintain_precision(y * frequency),
            noise::maintain_precision(z * frequency))) return false;
        frequency *= 2.0;
    }
    return true;
}

inline bool safe_legacy_octaves(const noise::OctavePerlinNoiseSampler* s, int count,
                               double x, double y, double z) noexcept {
    if (!s || !s->octaves) return true;
    double frequency = 1.0;
    for (int i = 0; i < count; ++i) {
        if (static_cast<std::size_t>(i) < s->octave_count
            && (!s->amplitudes || s->amplitudes[s->octave_count - 1 - i] != 0.0)
            && !safe_lattice(s->octaves[s->octave_count - 1 - i],
                noise::maintain_precision(x * frequency), noise::maintain_precision(y * frequency),
                noise::maintain_precision(z * frequency))) return false;
        frequency /= 2.0;
    }
    return true;
}

inline bool safe_noise(const Node& n, const NoisePoint& p) noexcept {
    if (n.kind == NodeKind::kInterpolatedNoise) {
        const auto& s = *n.interp_noise_ptr;
        const double x = p.x * (684.412 * s.xz_scale);
        const double y = p.y * (684.412 * s.y_scale);
        const double z = p.z * (684.412 * s.xz_scale);
        return safe_legacy_octaves(s.interpolation_noise, 8, x / s.xz_factor, y / s.y_factor, z / s.xz_factor)
            && safe_legacy_octaves(s.lower_interpolated_noise, 16, x, y, z)
            && safe_legacy_octaves(s.upper_interpolated_noise, 16, x, y, z);
    }
    return safe_octaves(n.noise_ptr->first, p.x, p.y, p.z)
        && safe_octaves(n.noise_ptr->second, p.x * noise::kDomainScale,
                        p.y * noise::kDomainScale, p.z * noise::kDomainScale);
}

} // namespace lattice::world::gen::densityfunction::dfc::detail
