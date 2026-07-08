// Bit-exact port of InterpolatedNoiseSampler.sample. See
// interpolated_noise.hpp.

#include "world/gen/noise/interpolated_noise.hpp"

#include <algorithm>
#include <cmath>

#include "world/gen/noise/perlin_noise.hpp"

namespace lattice::world::gen::noise {

namespace {

// Mojang's `MathHelper.clampedLerp` for doubles (MathHelper.java:127).
inline double clamped_lerp(double delta, double start, double end) noexcept {
    if (delta < 0.0) return start;
    if (delta > 1.0) return end;
    return start + delta * (end - start);
}

/// Vanilla's `OctavePerlinNoiseSampler.getOctave(p)` returns
/// `octaveSamplers[length - 1 - p]`. We mirror that. Returns nullptr
/// when the requested octave is out of range.
const PerlinNoiseSampler* get_octave(const OctavePerlinNoiseSampler* opn,
                                     int octave) noexcept {
    if (!opn || !opn->octaves) return nullptr;
    const int n = static_cast<int>(opn->octave_count);
    const int idx = n - 1 - octave;
    if (idx < 0 || idx >= n) return nullptr;
    // Vanilla's `octaveSamplers[…]` is `@Nullable`. We don't model
    // nullability per-slot; an unused octave has amplitude == 0 and
    // its sampler entry was either zeroed or untouched. The
    // InterpolatedNoiseSampler doesn't read amplitude, so the closest
    // analogue is to treat amplitude==0 as "null sampler".
    if (opn->amplitudes && opn->amplitudes[idx] == 0.0) return nullptr;
    return &opn->octaves[idx];
}

} // namespace

double sample(const InterpolatedNoiseSampler& s,
              double block_x, double block_y, double block_z) noexcept {
    // Vanilla:
    //   d = blockX * scaledXzScale; e = blockY * scaledYScale; f = blockZ * scaledXzScale
    //   g = d / xzFactor; h = e / yFactor; i = f / xzFactor
    //   j = scaledYScale * smearScaleMultiplier
    //   k = j / yFactor
    //   ... interpolationNoise loop on (g, h, i) with yScale=k, yMax=h ...
    //   ... lower/upper loop on (d, e, f) with yScale=j, yMax=e ...
    //
    // scaledXzScale = 684.412 * xzScale; scaledYScale = 684.412 * yScale.
    // The 684.412 constant is hard-baked in Mojang's constructor.
    constexpr double kScaleConst = 684.412;
    const double scaled_xz_scale = kScaleConst * s.xz_scale;
    const double scaled_y_scale  = kScaleConst * s.y_scale;

    const double d = block_x * scaled_xz_scale;
    const double e = block_y * scaled_y_scale;
    const double f = block_z * scaled_xz_scale;
    const double g = d / s.xz_factor;
    const double h = e / s.y_factor;
    const double i = f / s.xz_factor;
    const double j = scaled_y_scale * s.smear_scale_multiplier;
    const double k = j / s.y_factor;

    // Interpolation noise: 8 octaves; n accumulates the weighted sum.
    double n = 0.0;
    double o = 1.0;
    for (int p = 0; p < 8; ++p) {
        const PerlinNoiseSampler* lv = get_octave(s.interpolation_noise, p);
        if (lv) {
            const double sx = maintain_precision(g * o);
            const double sy = maintain_precision(h * o);
            const double sz = maintain_precision(i * o);
            n += sample_y_scaled(*lv, sx, sy, sz, k * o, h * o) / o;
        }
        o /= 2.0;
    }

    const double q   = (n / 10.0 + 1.0) / 2.0;
    const bool   bl2 = q >= 1.0;
    const bool   bl3 = q <= 0.0;

    // Lower / upper noise: 16 octaves, alternately skipped per `q`.
    double l_acc = 0.0;
    double m_acc = 0.0;
    o = 1.0;
    for (int r = 0; r < 16; ++r) {
        const double sx = maintain_precision(d * o);
        const double sy = maintain_precision(e * o);
        const double sz = maintain_precision(f * o);
        const double v  = j * o;
        const double y_max = e * o;

        if (!bl2) {
            const PerlinNoiseSampler* lv = get_octave(s.lower_interpolated_noise, r);
            if (lv) l_acc += sample_y_scaled(*lv, sx, sy, sz, v, y_max) / o;
        }
        if (!bl3) {
            const PerlinNoiseSampler* lv = get_octave(s.upper_interpolated_noise, r);
            if (lv) m_acc += sample_y_scaled(*lv, sx, sy, sz, v, y_max) / o;
        }
        o /= 2.0;
    }

    return clamped_lerp(q, l_acc / 512.0, m_acc / 512.0) / 128.0;
}

void sample_batch(const InterpolatedNoiseSampler& s,
                  const double* block_x, const double* block_y,
                  const double* block_z,
                  std::size_t count, double* out) noexcept {
    if (!block_x || !block_y || !block_z || !out) return;
    for (std::size_t i = 0; i < count; ++i) {
        out[i] = sample(s, block_x[i], block_y[i], block_z[i]);
    }
}

void sample_y_column(const InterpolatedNoiseSampler& s,
                     double block_x, double block_y0,
                     double block_z, double dy,
                     std::size_t count, double* out) noexcept {
    if (!out) return;
    constexpr double kScaleConst = 684.412;
    const double scaled_xz_scale = kScaleConst * s.xz_scale;
    const double scaled_y_scale  = kScaleConst * s.y_scale;

    const double d = block_x * scaled_xz_scale;
    const double f = block_z * scaled_xz_scale;
    const double g = d / s.xz_factor;
    const double i_coord = f / s.xz_factor;
    const double j = scaled_y_scale * s.smear_scale_multiplier;
    const double k = j / s.y_factor;

    const PerlinNoiseSampler* interpolation_octaves[8]{};
    double interpolation_o[8]{};
    double interpolation_sx[8]{};
    double interpolation_sz[8]{};
    double o = 1.0;
    for (int p = 0; p < 8; ++p) {
        interpolation_octaves[p] = get_octave(s.interpolation_noise, p);
        interpolation_o[p] = o;
        interpolation_sx[p] = maintain_precision(g * o);
        interpolation_sz[p] = maintain_precision(i_coord * o);
        o /= 2.0;
    }

    const PerlinNoiseSampler* lower_octaves[16]{};
    const PerlinNoiseSampler* upper_octaves[16]{};
    double lower_upper_o[16]{};
    double lower_upper_sx[16]{};
    double lower_upper_sz[16]{};
    o = 1.0;
    for (int r = 0; r < 16; ++r) {
        lower_octaves[r] = get_octave(s.lower_interpolated_noise, r);
        upper_octaves[r] = get_octave(s.upper_interpolated_noise, r);
        lower_upper_o[r] = o;
        lower_upper_sx[r] = maintain_precision(d * o);
        lower_upper_sz[r] = maintain_precision(f * o);
        o /= 2.0;
    }

    for (std::size_t i = 0; i < count; ++i) {
        const double block_y = block_y0 + static_cast<double>(i) * dy;
        const double e = block_y * scaled_y_scale;
        const double h = e / s.y_factor;

        double n = 0.0;
        for (int p = 0; p < 8; ++p) {
            const PerlinNoiseSampler* lv = interpolation_octaves[p];
            if (lv) {
                const double octave_scale = interpolation_o[p];
                const double sx = interpolation_sx[p];
                const double sy = maintain_precision(h * octave_scale);
                const double sz = interpolation_sz[p];
                n += sample_y_scaled(*lv, sx, sy, sz, k * octave_scale, h * octave_scale) / octave_scale;
            }
        }

        const double q   = (n / 10.0 + 1.0) / 2.0;
        const bool   bl2 = q >= 1.0;
        const bool   bl3 = q <= 0.0;

        double l_acc = 0.0;
        double m_acc = 0.0;
        for (int r = 0; r < 16; ++r) {
            const double octave_scale = lower_upper_o[r];
            const double sx = lower_upper_sx[r];
            const double sy = maintain_precision(e * octave_scale);
            const double sz = lower_upper_sz[r];
            const double v = j * octave_scale;
            const double y_max = e * octave_scale;

            if (!bl2) {
                const PerlinNoiseSampler* lv = lower_octaves[r];
                if (lv) l_acc += sample_y_scaled(*lv, sx, sy, sz, v, y_max) / octave_scale;
            }
            if (!bl3) {
                const PerlinNoiseSampler* lv = upper_octaves[r];
                if (lv) m_acc += sample_y_scaled(*lv, sx, sy, sz, v, y_max) / octave_scale;
            }
        }

        out[i] = clamped_lerp(q, l_acc / 512.0, m_acc / 512.0) / 128.0;
    }
}

} // namespace lattice::world::gen::noise
