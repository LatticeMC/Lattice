// Multi-octave Perlin noise. See octave_perlin_noise.hpp.
//
// Implementation note: the file defines two overloads named `sample`
// (one for OctavePerlinNoiseSampler, one for PerlinNoiseSampler in
// perlin_noise.hpp). Inside the OctavePerlin overload's body, we
// reach the per-octave Perlin sampler via the helper alias
// `perlin_sample_3` to avoid any chance of recursive self-resolution
// when the file is read by a future maintainer.

#include "world/gen/noise/octave_perlin_noise.hpp"

#include <cstddef>

namespace lattice::world::gen::noise {

namespace {
// Force-resolve the 3-arg single-octave Perlin sampler. (Overload
// resolution would also pick this correctly via parameter type, but
// the alias is explicit.)
inline double perlin_sample_3(const PerlinNoiseSampler& p,
                              double x, double y, double z) noexcept {
    return ::lattice::world::gen::noise::sample(p, x, y, z);
}

template <std::size_t Count>
inline double sample_octaves(const OctavePerlinNoiseSampler& s,
                             double x, double y, double z) noexcept {
    double result = 0.0;
    double freq   = s.lacunarity;
    double amp    = s.persistence;
    for (std::size_t i = 0; i < Count; ++i) {
        const double a = s.amplitudes[i];
        if (a != 0.0) {
            const double fx = maintain_precision(x * freq);
            const double fy = maintain_precision(y * freq);
            const double fz = maintain_precision(z * freq);
            result += a * amp * perlin_sample_3(s.octaves[i], fx, fy, fz);
        }
        freq *= 2.0;
        amp  *= 0.5;
    }
    return result;
}

template <std::size_t Count>
inline double sample_octaves_full(const OctavePerlinNoiseSampler& s,
                                  double x, double y, double z,
                                  double y_scale, double y_max,
                                  bool use_origin) noexcept {
    double result = 0.0;
    double freq   = s.lacunarity;
    double amp    = s.persistence;
    for (std::size_t i = 0; i < Count; ++i) {
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

inline double dispatch_sample(const OctavePerlinNoiseSampler& s,
                              double x, double y, double z) noexcept {
    switch (s.octave_count) {
        case 1:  return sample_octaves<1>(s, x, y, z);
        case 2:  return sample_octaves<2>(s, x, y, z);
        case 4:  return sample_octaves<4>(s, x, y, z);
        case 8:  return sample_octaves<8>(s, x, y, z);
        case 16: return sample_octaves<16>(s, x, y, z);
        default: {
            double result = 0.0;
            double freq   = s.lacunarity;
            double amp    = s.persistence;
            for (std::size_t i = 0; i < s.octave_count; ++i) {
                const double a = s.amplitudes[i];
                if (a != 0.0) {
                    const double fx = maintain_precision(x * freq);
                    const double fy = maintain_precision(y * freq);
                    const double fz = maintain_precision(z * freq);
                    result += a * amp * perlin_sample_3(s.octaves[i], fx, fy, fz);
                }
                freq *= 2.0;
                amp  *= 0.5;
            }
            return result;
        }
    }
}

inline double dispatch_sample_full(const OctavePerlinNoiseSampler& s,
                                   double x, double y, double z,
                                   double y_scale, double y_max,
                                   bool use_origin) noexcept {
    switch (s.octave_count) {
        case 1:  return sample_octaves_full<1>(s, x, y, z, y_scale, y_max, use_origin);
        case 2:  return sample_octaves_full<2>(s, x, y, z, y_scale, y_max, use_origin);
        case 4:  return sample_octaves_full<4>(s, x, y, z, y_scale, y_max, use_origin);
        case 8:  return sample_octaves_full<8>(s, x, y, z, y_scale, y_max, use_origin);
        case 16: return sample_octaves_full<16>(s, x, y, z, y_scale, y_max, use_origin);
        default: {
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
    }
}
} // namespace

double sample(const OctavePerlinNoiseSampler& s,
              double x, double y, double z) noexcept {
    if (s.octave_count == 0 || !s.octaves || !s.amplitudes) return 0.0;
    return dispatch_sample(s, x, y, z);
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
    return dispatch_sample_full(s, x, y, z, y_scale, y_max, use_origin);
}

} // namespace lattice::world::gen::noise
