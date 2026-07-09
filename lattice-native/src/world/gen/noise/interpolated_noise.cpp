// Bit-exact port of InterpolatedNoiseSampler.sample. See
// interpolated_noise.hpp.

#include "world/gen/noise/interpolated_noise.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

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

struct InterpolatedColumnScratch {
    std::vector<double> y;
    std::vector<double> y_max;
    std::vector<double> tmp;
    std::vector<double> e;
    std::vector<double> h;
    std::vector<double> interpolation;
    std::vector<double> lower;
    std::vector<double> upper;
    std::vector<double> q;

    void resize(std::size_t count) {
        y.resize(count);
        y_max.resize(count);
        tmp.resize(count);
        e.resize(count);
        h.resize(count);
        interpolation.assign(count, 0.0);
        lower.assign(count, 0.0);
        upper.assign(count, 0.0);
        q.resize(count);
    }
};

thread_local InterpolatedColumnScratch g_interpolated_column_scratch;

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
    if (count == 0) return;
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

    InterpolatedColumnScratch& scratch = g_interpolated_column_scratch;
    scratch.resize(count);

    const double e_step = dy * scaled_y_scale;
    const double inv_y_factor = 1.0 / s.y_factor;
    double e_value = block_y0 * scaled_y_scale;
    for (std::size_t i = 0; i < count; ++i) {
        scratch.e[i] = e_value;
        scratch.h[i] = e_value * inv_y_factor;
        e_value += e_step;
    }

    for (int p = 0; p < 8; ++p) {
        const PerlinNoiseSampler* lv = interpolation_octaves[p];
        if (!lv) continue;
        const double octave_scale = interpolation_o[p];
        const double sx = interpolation_sx[p];
        const double sz = interpolation_sz[p];
        for (std::size_t i = 0; i < count; ++i) {
            scratch.y[i] = maintain_precision(scratch.h[i] * octave_scale);
            scratch.y_max[i] = scratch.h[i] * octave_scale;
        }
        sample_y_scaled_array_ymax(*lv,
                                   sx, scratch.y.data(), sz,
                                   k * octave_scale, scratch.y_max.data(),
                                   count, scratch.tmp.data());
        for (std::size_t i = 0; i < count; ++i) scratch.interpolation[i] += scratch.tmp[i] / octave_scale;
    }

    bool any_lower = false;
    bool any_upper = false;
    bool all_lower = true;
    bool all_upper = true;
    for (std::size_t i = 0; i < count; ++i) {
        const double q = (scratch.interpolation[i] / 10.0 + 1.0) / 2.0;
        scratch.q[i] = q;
        const bool needs_lower = q < 1.0;
        const bool needs_upper = q > 0.0;
        any_lower = any_lower || needs_lower;
        any_upper = any_upper || needs_upper;
        all_lower = all_lower && needs_lower;
        all_upper = all_upper && needs_upper;
    }

    for (int r = 0; r < 16; ++r) {
        const PerlinNoiseSampler* lower = lower_octaves[r];
        const PerlinNoiseSampler* upper = upper_octaves[r];
        const bool need_lower = any_lower && lower;
        const bool need_upper = any_upper && upper;
        if (!need_lower && !need_upper) continue;

        const double octave_scale = lower_upper_o[r];
        const double sx = lower_upper_sx[r];
        const double sz = lower_upper_sz[r];
        for (std::size_t i = 0; i < count; ++i) {
            scratch.y[i] = maintain_precision(scratch.e[i] * octave_scale);
            scratch.y_max[i] = scratch.e[i] * octave_scale;
        }

        if (need_lower) {
            sample_y_scaled_array_ymax(*lower,
                                       sx, scratch.y.data(), sz,
                                       j * octave_scale, scratch.y_max.data(),
                                       count, scratch.tmp.data());
            if (all_lower) {
                for (std::size_t i = 0; i < count; ++i) scratch.lower[i] += scratch.tmp[i] / octave_scale;
            } else {
                for (std::size_t i = 0; i < count; ++i) {
                    if (scratch.q[i] < 1.0) scratch.lower[i] += scratch.tmp[i] / octave_scale;
                }
            }
        }
        if (need_upper) {
            sample_y_scaled_array_ymax(*upper,
                                       sx, scratch.y.data(), sz,
                                       j * octave_scale, scratch.y_max.data(),
                                       count, scratch.tmp.data());
            if (all_upper) {
                for (std::size_t i = 0; i < count; ++i) scratch.upper[i] += scratch.tmp[i] / octave_scale;
            } else {
                for (std::size_t i = 0; i < count; ++i) {
                    if (scratch.q[i] > 0.0) scratch.upper[i] += scratch.tmp[i] / octave_scale;
                }
            }
        }
    }

    for (std::size_t i = 0; i < count; ++i) {
        out[i] = clamped_lerp(scratch.q[i], scratch.lower[i] / 512.0, scratch.upper[i] / 512.0) / 128.0;
    }
}

} // namespace lattice::world::gen::noise
