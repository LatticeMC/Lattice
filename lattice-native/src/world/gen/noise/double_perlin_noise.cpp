// Two-sampler Perlin noise. See double_perlin_noise.hpp.

#include "world/gen/noise/double_perlin_noise.hpp"

namespace lattice::world::gen::noise {

double sample(const DoublePerlinNoiseSampler& s,
              double x, double y, double z) noexcept {
    const double sx = x * kDomainScale;
    const double sy = y * kDomainScale;
    const double sz = z * kDomainScale;
    const double v1 = sample(s.first,  x,  y,  z);
    const double v2 = sample(s.second, sx, sy, sz);
    return (v1 + v2) * s.amplitude;
}

} // namespace lattice::world::gen::noise
