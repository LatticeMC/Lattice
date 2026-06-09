/**
 * @file chunk_noise_sampler.hpp
 * @brief Top-level chunk-noise sampler. Bundles a NoiseRouter
 *        (a set of density-function trees keyed by purpose) with one
 *        per-channel CacheState and offers convenience entry points
 *        for the common sampling patterns vanilla uses.
 *
 * Mirrors `net.minecraft.world.gen.chunk.ChunkNoiseSampler` (class_6568)
 * at the *interface* level. We don't replicate vanilla's full
 * cell-grid pre-fill machinery (that's a Java-side optimisation
 * intertwined with the chunk-generation pipeline). Instead the sampler
 * exposes:
 *
 *   - `sample_final_density(x, y, z, cellX, cellZ)` — the most-used
 *     hot path; evaluates `router.final_density` with its cache.
 *   - `sample(Channel, x, y, z, cellX, cellZ)` — sample any specific
 *     NoiseRouter channel by name.
 *   - Interpolator lifecycle helpers (`prepare_interpolators`,
 *     `start_interpolation`, `set_*_density`, `interpolate_*`,
 *     `swap_buffers`, `stop_interpolation`) so callers can drive
 *     `kInterpolated` nodes through this sampler instead of reaching
 *     into per-channel CacheState objects directly.
 *   - `clear_cache()` — invalidate every channel's cache. Call between
 *     chunks.
 *   - `prepare_cache()` — must be called once after assigning a new
 *     router, to size the per-channel slot vectors.
 *
 * Why one cache per channel? Each NodeArena tree numbers its cache
 * nodes from 0 independently. If two different channels had a Cache2D
 * slot id 0, sharing a single CacheState would mix their entries.
 * Keeping caches per-channel costs ~15 small vectors and a few cache
 * lines, well below noise threshold.
 *
 * Callers should keep one ChunkNoiseSampler per worker thread (or one
 * per concurrent chunk being generated), reset its cache between
 * chunks, and rebind its chunk coordinates as needed.
 */

#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

#include "world/gen/densityfunction/density_function.hpp"

namespace lattice::world::gen::chunknoise {

/// NoiseRouter bundle. Mirrors the 15 channels Mojang's NoiseRouter
/// holds (class_7138). Any individual channel may be null; in that
/// case sampling it returns 0.
struct NoiseRouter {
    const densityfunction::NodeArena* barrier_noise = nullptr;
    const densityfunction::NodeArena* fluid_level_floodedness_noise = nullptr;
    const densityfunction::NodeArena* fluid_level_spread_noise = nullptr;
    const densityfunction::NodeArena* lava_noise = nullptr;
    const densityfunction::NodeArena* temperature = nullptr;
    const densityfunction::NodeArena* vegetation = nullptr;
    const densityfunction::NodeArena* continents = nullptr;
    const densityfunction::NodeArena* erosion = nullptr;
    const densityfunction::NodeArena* depth = nullptr;
    const densityfunction::NodeArena* ridges = nullptr;
    /// Mojang field name: `preliminarySurfaceLevel`
    /// (NoiseRouter#preliminarySurfaceLevel). Predicts a non-jaggedness-
    /// adjusted surface height so the chunk generator can skip Aquifer
    /// / OreVein sampling above it.
    const densityfunction::NodeArena* preliminary_surface_level = nullptr;
    const densityfunction::NodeArena* final_density = nullptr;
    const densityfunction::NodeArena* vein_toggle = nullptr;
    const densityfunction::NodeArena* vein_ridged = nullptr;
    const densityfunction::NodeArena* vein_gap = nullptr;
};

/// Stable enum for the 15 NoiseRouter channels. Used by `sample` so
/// callers don't need to know the field name on the C++ side. The
/// numeric values are stable across releases; the JNI wrapper
/// duplicates them on the Java side.
enum class Channel : std::uint8_t {
    kBarrierNoise = 0,
    kFluidLevelFloodednessNoise,
    kFluidLevelSpreadNoise,
    kLavaNoise,
    kTemperature,
    kVegetation,
    kContinents,
    kErosion,
    kDepth,
    kRidges,
    kPreliminarySurfaceLevel,
    kFinalDensity,
    kVeinToggle,
    kVeinRidged,
    kVeinGap,
    kCount,
};

inline constexpr std::size_t kChannelCount =
        static_cast<std::size_t>(Channel::kCount);

struct ChunkNoiseSampler {
    NoiseRouter router;

    /// Per-channel cache state. Each entry sized to its corresponding
    /// channel's arena slot counts by `prepare_cache()`. Channels with
    /// no arena keep an empty (zero-slot) CacheState.
    std::array<densityfunction::CacheState, kChannelCount> caches;

    /// Resize each channel's CacheState to fit that channel's
    /// NodeArena. Idempotent: existing entries keep their state (call
    /// `clear_cache()` to drop them). Run this once after assigning a
    /// new router (typically once per worker construction, plus once
    /// each time `router` is mutated).
    void prepare_cache();

    /// Invalidate every cached entry across every channel. Call
    /// between chunks.
    void clear_cache() noexcept;

    /// Sample `router.final_density` at (x, y, z) using its cache.
    [[nodiscard]] double sample_final_density(double x, double y, double z,
                                              int cellX, int cellZ) noexcept;

    /// Sample any named channel using that channel's cache.
    [[nodiscard]] double sample(Channel ch, double x, double y, double z,
                                int cellX, int cellZ) noexcept;

    /// Number of `kInterpolated` slots owned by `ch`'s arena.
    [[nodiscard]] int num_interpolator_slots(Channel ch) const noexcept;

    /// Allocate interpolation buffers sized for a `(hCC+1) x (vCC+1)`
    /// corner grid on one channel.
    void prepare_interpolators(Channel ch,
                               int horizontalCellCount,
                               int verticalCellCount) noexcept;

    /// Mark one channel's interpolation loop active/inactive.
    void start_interpolation(Channel ch) noexcept;
    void stop_interpolation(Channel ch) noexcept;

    /// Set one lattice-corner density value on one channel.
    void set_start_density(Channel ch, int slot,
                           int cellZ, int cellY, double value) noexcept;
    void set_end_density(Channel ch, int slot,
                         int cellZ, int cellY, double value) noexcept;

    /// Bulk write one `[cellZ][:]` row for one slot.
    void set_start_density_row(Channel ch, int slot,
                               int cellZ, std::span<const double> row) noexcept;
    void set_end_density_row(Channel ch, int slot,
                             int cellZ, std::span<const double> row) noexcept;

    /// Load the 8 corners for `(cellY, cellZ)` into every interpolator
    /// slot on one channel.
    void on_sampled_cell_corners(Channel ch, int cellY, int cellZ) noexcept;

    /// Cascade the trilinear interpolation state on one channel.
    void interpolate_y(Channel ch, double deltaY) noexcept;
    void interpolate_x(Channel ch, double deltaX) noexcept;
    void interpolate_z(Channel ch, double deltaZ) noexcept;

    /// Advance one channel's interpolation column.
    void swap_buffers(Channel ch) noexcept;

    /// Convenience helper for the common column-advance step after the
    /// next cell-X column has been pre-filled.
    void advance_column(Channel ch) noexcept;

    /// Fill every interpolator slot's start-buffer column from the
    /// wrapped input trees in `ch`'s arena.
    void fill_start_density_column(Channel ch,
                                   double x, double z,
                                   int cellX, int cellZ0,
                                   double y0, double dy,
                                   int horizontalCellCount,
                                   int verticalCellCount) noexcept;

    /// Same as above, for the end-buffer column.
    void fill_end_density_column(Channel ch,
                                 double x, double z,
                                 int cellX, int cellZ0,
                                 double y0, double dy,
                                 int horizontalCellCount,
                                 int verticalCellCount) noexcept;

    /// Pre-fill the current and next `final_density` columns in one call.
    void prime_final_density_columns(double startX, double endX,
                                     double z,
                                     int startCellX, int endCellX,
                                     int cellZ0,
                                     double y0, double dy,
                                     int horizontalCellCount,
                                     int verticalCellCount) noexcept;

    /// Advance `final_density` by swapping buffers and pre-filling the
    /// next end column.
    void advance_final_density_column(double nextX,
                                      double z,
                                      int nextCellX,
                                      int cellZ0,
                                      double y0, double dy,
                                      int horizontalCellCount,
                                      int verticalCellCount) noexcept;

    /// Generic two-column prefill for interpolated channels.
    void prime_channel_columns(Channel ch,
                               double startX, double endX,
                               double z,
                               int startCellX, int endCellX,
                               int cellZ0,
                               double y0, double dy,
                               int horizontalCellCount,
                               int verticalCellCount) noexcept;

    /// Generic one-column advance for interpolated channels.
    void advance_channel_column(Channel ch,
                                double nextX,
                                double z,
                                int nextCellX,
                                int cellZ0,
                                double y0, double dy,
                                int horizontalCellCount,
                                int verticalCellCount) noexcept;

    /// Sample `final_density` on a regular grid inside one already-
    /// prepared interpolation cell. Fractions along each axis are
    /// distributed uniformly across `[0, 1]` from the provided counts.
    void sample_final_density_cell_grid(int cellY, int cellZ,
                                        double x0, double y0, double z0,
                                        double dx, double dy, double dz,
                                        int cellX, int cellZCoord,
                                        int nx, int ny, int nz,
                                        double* out) noexcept;

    /// Generic cell-grid sampler for any interpolated channel.
    void sample_cell_grid(Channel ch,
                          int cellY, int cellZ,
                          double x0, double y0, double z0,
                          double dx, double dy, double dz,
                          int cellX, int cellZCoord,
                          int nx, int ny, int nz,
                          double* out) noexcept;
};

} // namespace lattice::world::gen::chunknoise
