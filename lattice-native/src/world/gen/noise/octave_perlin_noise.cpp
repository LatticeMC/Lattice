// Multi-octave Perlin noise. See octave_perlin_noise.hpp.
//
// Implementation note: the file defines two overloads named `sample`
// (one for OctavePerlinNoiseSampler, one for PerlinNoiseSampler in
// perlin_noise.hpp). Inside the OctavePerlin overload's body, we
// reach the per-octave Perlin sampler via the helper alias
// `perlin_sample_3` to avoid any chance of recursive self-resolution
// when the file is read by a future maintainer.

#include "world/gen/noise/octave_perlin_noise.hpp"

namespace lattice::world::gen::noise {

namespace {
// Force-resolve the 3-arg single-octave Perlin sampler. (Overload
// resolution would also pick this correctly via parameter type, but
// the alias is explicit.)
inline double perlin_sample_3(const PerlinNoiseSampler& p,
                              double x, double y, double z) noexcept {
    return ::lattice::world::gen::noise::sample(p, x, y, z);
}
} // namespace

double sample(const OctavePerlinNoiseSampler& s,
              double x, double y, double z) noexcept {
    if (s.octave_count == 0 || !s.octaves || !s.amplitudes) return 0.0;

    double result = 0.0;
    double freq   = s.lacunarity;
    double amp    = s.persistence;
    for (std::size_t i = 0; i < s.octave_count; ++i) {
        const double a = s.amplitudes[i];
        if (a != 0.0) {
            const double fx = maintain_precision(x * freq);
            const double fy = maintain_precision(y * freq);
            const double fz = maintain_precision(z * freq);
            // Vanilla 3-arg path: sample(x, y, z, 0.0, 0.0, false),
            // which feeds maintain_precision(y * freq) as y and
            // delegates to the 5-arg lv.sample with yScale=0 (i.e.,
            // s_offset = 0 — collapses to the unscaled lattice eval).
            result += a * amp * perlin_sample_3(s.octaves[i], fx, fy, fz);
        }
        freq *= 2.0;
        amp  *= 0.5;
    }
    return result;
}

double sample_full(const OctavePerlinNoiseSampler& s,
                   double x, double y, double z,
                   double y_scale, double y_max,
                   bool use_origin) noexcept {
    // Mojang's `OctavePerlinNoiseSampler.sample(x, y, z, yScale, yMax,
    // useOrigin)` (yarn-1.21.11 OctavePerlinNoiseSampler.java:137-151):
    //
    //   for each non-null octaveSamplers[l]:
    //       freq = lacunarity * 2^l
    //       amp  = persistence / 2^l
    //       m = lv.sample(
    //           maintainPrecision(x * freq),
    //           useOrigin ? -lv.originY : maintainPrecision(y * freq),
    //           maintainPrecision(z * freq),
    //           yScale * freq, yMax * freq);
    //       i += amplitudes[l] * m * amp;
    //
    // Critical points:
    //   * `useOrigin == true` does NOT mean "use the per-octave origin".
    //     It means "force the lattice-Y to zero by feeding the inverse
    //     of originY as the y argument" — the inner sample's `y +=
    //     originY` then cancels to 0. This produces a deterministic
    //     y=0 sample line per octave, which is what terrain noise
    //     wants when y is supposed to be ignored.
    //   * `useOrigin == false` is the regular case: maintain-precision
    //     wraps the geometric y * freq.
    //   * The 5-arg `lv.sample(...)` overload runs y-scaled lattice
    //     sampling at `yScale * freq` / `yMax * freq`.
    //   * The per-octave originX/Z are ALWAYS used (the inner
    //     `lv.sample` adds them inside). Our previous "zero out the
    //     origins when use_origin == false" was wrong on both axes.
    if (s.octave_count == 0 || !s.octaves || !s.amplitudes) return 0.0;

    double result = 0.0;
    double freq   = s.lacunarity;
    double amp    = s.persistence;
    for (std::size_t i = 0; i < s.octave_count; ++i) {
        const double a = s.amplitudes[i];
        if (a != 0.0) {
            const PerlinNoiseSampler& lv = s.octaves[i];
            const double fx = maintain_precision(x * freq);
            const double fz = maintain_precision(z * freq);
            const double fy = use_origin
                ? -lv.origin_y
                :  maintain_precision(y * freq);
            const double m = sample_y_scaled(lv, fx, fy, fz,
                                             y_scale * freq, y_max * freq);
            result += a * amp * m;
        }
        freq *= 2.0;
        amp  *= 0.5;
    }
    return result;
}

} // namespace lattice::world::gen::noise
