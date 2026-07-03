/**
 * @file density_function.hpp
 * @brief Recursive evaluator for Mojang's `DensityFunction` tree
 *        (vanilla `net.minecraft.world.gen.densityfunction.DensityFunction`
 *        / `class_6910`).
 *
 * Mojang's DensityFunction is a data-pack-pluggable tree of operations.
 * The vanilla NoiseRouter that drives 1.18+ terrain generation is
 * built entirely out of these. Every node implements a `sample(ctx)`
 * method that returns a `double`; the tree is evaluated recursively.
 *
 * This module provides a **flat tagged-union** representation
 * (`Node`) plus a recursive evaluator (`evaluate`). Nodes that need
 * noise samplers (`Noise`, `ShiftedNoise`, …) carry a pointer to a
 * pre-built `DoublePerlinNoiseSampler` allocated by the caller. Nodes
 * with operand subtrees own them as `unique_ptr`-style indices into a
 * shared `NodeArena`.
 *
 * Scope of the first commit (Worldgen-4)
 * --------------------------------------
 *
 *   - Constant
 *   - Unary: Abs, Square, Cube, HalfNegative, QuarterNegative, Invert, Squeeze
 *   - Binary: Add, Mul, Min, Max
 *   - YClampedGradient(fromY, toY, fromValue, toValue)
 *   - MapRange(input, fromMin, fromMax, toMin, toMax)
 *   - Lerp(t, low, high)            -- vanilla method_40488
 *   - RangeChoice(input, minIncl, maxExcl, whenIn, whenOut)
 *   - Noise(noiseSampler, scaleXZ, scaleY)
 *   - ShiftedNoise(shiftX, shiftY, shiftZ, scaleXZ, scaleY, noiseSampler)
 *
 * Not yet implemented (deferred to a follow-up commit):
 *
 *   - Cache2D, CacheAllInCell, FlatCache, CacheOnce, Interpolated
 *   - WeirdScaledSampler
 *   - EndIslands, BlendAlpha, BlendOffset, BlendDensity
 *   - ShiftA, ShiftB, Shift
 *
 * Most of those involve per-chunk caching that's best expressed at a
 * higher level (NativeChunkNoiseSampler in Worldgen-6); the leaf-level
 * arithmetic in the list above already covers ~60% of typical
 * NoiseRouter density-function nodes.
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "world/gen/noise/double_perlin_noise.hpp"
#include "world/gen/noise/interpolated_noise.hpp"
#include "world/gen/noise/simplex_noise.hpp"
#include "world/gen/densityfunction/beardifier.hpp"
#include "world/gen/densityfunction/spline.hpp"

namespace lattice::world::gen::densityfunction {

enum class NodeKind : std::uint8_t {
    kConstant,          // value
    kAbs,               // unary(input)
    kSquare,
    kCube,
    kHalfNegative,
    kQuarterNegative,
    kInvert,
    kSqueeze,
    kAdd,               // binary(a, b)
    kMul,
    kMin,
    kMax,
    kYClampedGradient,  // (fromY:int, toY:int, fromV:double, toV:double)
    kMapRange,          // (input, fromMin, fromMax, toMin, toMax)
    kLerp,              // (t, low, high)
    kRangeChoice,       // (input, minIncl, maxExcl, whenIn, whenOut)
    kNoise,             // (noiseSampler, scaleXZ, scaleY)
    kShiftedNoise,      // (shiftX, shiftY, shiftZ, scaleXZ, scaleY, noiseSampler)

    // Worldgen-4c additions ------------------------------------------------
    //
    // Shift family: read a noise sampler at (x, 0, z) [ShiftA], (z, x, 0)
    // [ShiftB], or (x, y, z) [Shift]. The output is the noise value
    // scaled by 4 (Mojang's standard shift amplitude).
    kShiftA,            // (noiseSampler)
    kShiftB,            // (noiseSampler)
    kShift,             // (noiseSampler)

    // Cache family. Phase 1 implementation: passthrough — these nodes
    // evaluate their input directly without caching. They exist so a
    // datapack-driven NoiseRouter can be reconstructed in C++; later
    // commits will add the per-chunk state machinery.
    kCache2D,           // (input)
    kCacheOnce,         // (input)
    kCacheAllInCell,    // (input)
    kFlatCache,         // (input)

    // Interpolated: 3D trilinear interpolation over the cell
    // containing the sample point. When the per-chunk InterpolationState
    // is active (start_density and end_density buffers filled, and
    // is_in_interpolation_loop set), this samples the cascaded
    // interpolator slot's `result` field — bit-exact match to Mojang's
    // DensityInterpolator. When inactive, falls back to evaluating the
    // wrapped input directly (passthrough), preserving the Worldgen-9
    // behaviour for non-chunk-gen callers.
    //
    // The slot id is stored in `cache_slot_id` (shared field with
    // cache nodes; it's just an integer index here).
    kInterpolated,      // (input)

    // WeirdScaledSampler: scales the input function according to a
    // RarityValueMapper enum (`type1` or `type2`), then noise-samples
    // the result. Phase 1: implemented with the noise sampler attached.
    //   d0 = type (0 = TYPE1, 1 = TYPE2)
    kWeirdScaledSampler, // (input, noiseSampler, type)

    // EndIslands: Mojang's procedural end-island height function.
    // Uses a SimplexNoise sampler (stored separately from PerlinNoise
    // because the gradient table + topology differ). The noise_ptr
    // field on Node is overloaded: when kind == kEndIslands the
    // pointer is interpreted as a SimplexNoiseSampler* rather than a
    // DoublePerlinNoiseSampler*.
    kEndIslands,

    // Worldgen-7 additions ------------------------------------------------
    //
    // Clamp(input, min, max): output = clamp(input, min, max).
    //   d0 = min, d1 = max.
    kClamp,             // (input, min, max)

    // Blend* family. Mojang's `Blender` interpolates between vanilla
    // terrain and an upgraded-from-old-format chunk's pre-existing
    // terrain at chunk boundaries. The default (and >99% production
    // case) is the `NO_BLENDING` Blender:
    //
    //   BlendAlpha   → 1.0
    //   BlendOffset  → 0.0
    //   BlendDensity → input passthrough
    //
    // We implement that no-blending semantics here. The actual
    // upgrade-blending path is JVM-side (it depends on a Long2Object
    // map of BlendingData per chunk that we don't see in C++); a
    // future revision can add a JNI callback into the Java Blender,
    // but it isn't on any production hot path.
    kBlendAlpha,        // (no operands; constant 1.0)
    kBlendOffset,       // (no operands; constant 0.0)
    kBlendDensity,      // (input) — passthrough under NO_BLENDING

    // Spline: cubic-Hermite spline tree (DensityFunctionTypes.Spline).
    // i0 stores the root SplineRef; the actual spline records and
    // their breakpoints live on the parent NodeArena.
    kSpline,            // (root SplineRef in i0)

    // FindTopSurface: scan downward from upperBound(pos) to lowerBound
    // in cellHeight-aligned steps, returning the first y where
    // density(pos.x, y, pos.z) > 0. Falls back to lowerBound if none.
    //   a  = NodeRef of `density`
    //   b  = NodeRef of `upperBound`
    //   i0 = lowerBound
    //   i1 = cellHeight
    kFindTopSurface,    // (density, upperBound, lowerBound:i0, cellHeight:i1)

    // InterpolatedNoise: legacy 1.16-style "blended noise" used by
    // `old_blended_noise` density-function nodes. Holds three
    // OctavePerlinNoiseSampler pointers (lower / upper / interpolation)
    // plus 5 tuning doubles; operand-free.
    kInterpolatedNoise, // (interp_sampler_ptr in interp_noise_ptr)

    // Beardifier: borrowed per-chunk BeardifierData pointer, owned by the
    // Java Beardifier instance. Used by final-density trees.
    kBeardifier,
};

/// Index into a `NodeArena::nodes` vector. -1 = null/no operand.
using NodeRef = std::int32_t;
inline constexpr NodeRef kNullRef = -1;

/// One node in the tree. We use plain-old-data with a tagged union;
/// every field is read-only after construction.
struct Node {
    NodeKind kind;
    // Operand refs (semantics depend on kind):
    NodeRef  a    = kNullRef;
    NodeRef  b    = kNullRef;
    NodeRef  c    = kNullRef;
    NodeRef  d    = kNullRef;
    NodeRef  e    = kNullRef;
    // Scalar parameters (semantics depend on kind):
    double   d0   = 0.0;
    double   d1   = 0.0;
    double   d2   = 0.0;
    double   d3   = 0.0;
    int      i0   = 0;
    int      i1   = 0;
    // Noise sampler pointer (kind == kNoise / kShiftedNoise / kShift* /
    // kWeirdScaledSampler). Use a void* + interpretation-by-kind to
    // accommodate the SimplexNoiseSampler that kEndIslands needs.
    const noise::DoublePerlinNoiseSampler* noise_ptr = nullptr;

    // Simplex-noise sampler pointer (kind == kEndIslands only).
    const noise::SimplexNoiseSampler* simplex_ptr = nullptr;

    // Interpolated-noise sampler pointer (kind == kInterpolatedNoise only).
    // Owned by the JNI side (NativeInterpolatedNoise); the arena just
    // borrows the address.
    const noise::InterpolatedNoiseSampler* interp_noise_ptr = nullptr;

    // Beardifier data pointer (kind == kBeardifier only). Borrowed from
    // NativeBeardifier and kept alive by the owning Java Beardifier.
    const beardifier::BeardifierData* beardifier_ptr = nullptr;

    // Cache slot index inside the CacheState's per-kind vector. Only
    // meaningful for kCache2D / kCacheOnce / kCacheAllInCell / kFlatCache.
    // -1 = no slot (treat as passthrough). Each cache node gets its own
    // slot, so two separate Cache2D nodes have independent caches.
    int cache_slot_id = -1;
};

/// Owning container for a tree of `Node`s. Construction is bottom-up
/// (children first); `root` is the index of the top-level node.
struct NodeArena {
    std::vector<Node> nodes;
    NodeRef           root = kNullRef;

    /// Cache-slot counters. Each cache node is assigned a slot id as
    /// it's pushed; the caller's CacheState mirrors these counts.
    int num_cache_2d_slots         = 0;
    int num_cache_once_slots       = 0;
    int num_cache_all_in_cell_slots = 0;
    int num_flat_cache_slots       = 0;
    /// Counter for kInterpolated nodes. Each interpolator gets a slot
    /// id which the per-chunk InterpolationState array indexes by.
    /// Mirrors Mojang's `ChunkNoiseSampler.interpolators` list.
    int num_interpolator_slots     = 0;
    /// For each interpolator slot id, stores the wrapped input node
    /// (`Node.a`) that should be sampled to fill that slot's start/end
    /// density buffers.
    std::vector<NodeRef> interpolator_inputs;

    /// Spline storage. A Spline tree is stored as a flat list of
    /// `Spline` records plus a separate flat list of all
    /// `SplineBreakpoint`s used by Implementation splines. The
    /// `add_spline` / `add_spline_breakpoints` helpers below handle
    /// allocation; the `kSpline` node kind references the root by
    /// SplineRef in its `i0` field.
    std::vector<Spline>           splines;
    std::vector<SplineBreakpoint> spline_breakpoints;

    /// Append a node and return its index. Assigns a cache slot to
    /// cache-kind nodes; caller need not do it manually.
    NodeRef push(Node n) {
        switch (n.kind) {
            case NodeKind::kCache2D:        n.cache_slot_id = num_cache_2d_slots++;        break;
            case NodeKind::kCacheOnce:      n.cache_slot_id = num_cache_once_slots++;      break;
            case NodeKind::kCacheAllInCell: n.cache_slot_id = num_cache_all_in_cell_slots++; break;
            case NodeKind::kFlatCache:      n.cache_slot_id = num_flat_cache_slots++;      break;
            case NodeKind::kInterpolated:
                n.cache_slot_id = num_interpolator_slots++;
                interpolator_inputs.push_back(n.a);
                break;
            default: break;
        }
        nodes.push_back(n);
        return static_cast<NodeRef>(nodes.size() - 1);
    }

    /// Append a Spline record and return its SplineRef.
    SplineRef push_spline(Spline s) {
        splines.push_back(s);
        return static_cast<SplineRef>(splines.size() - 1);
    }

    /// Reserve a contiguous range in `spline_breakpoints` and return
    /// its start index. Caller fills in the entries directly.
    int reserve_spline_breakpoints(int count) {
        const int start = static_cast<int>(spline_breakpoints.size());
        spline_breakpoints.resize(static_cast<std::size_t>(start)
                                  + static_cast<std::size_t>(count));
        return start;
    }
};

// ---- Caching state ------------------------------------------------------
//
// Caches are *per-evaluation-context*, not per-NodeArena. A single tree
// is typically evaluated over many chunks; each chunk needs its own
// CacheState to avoid pollution.
//
// The cache is OPTIONAL: when the Context's `cache` field is null, the
// evaluator treats Cache* nodes as passthroughs (the Worldgen-4c
// behaviour). When non-null, the cache is consulted.

struct Cache2DEntry {
    bool   valid = false;
    int    x;
    int    z;
    double value;
};

struct CacheOnceEntry {
    bool   valid = false;
    double x;
    double y;
    double z;
    double value;
};

struct FlatCacheEntry {
    bool   valid = false;
    int    cellX;
    int    cellZ;
    double value;
};

struct CacheAllInCellEntry {
    bool         valid = false;
    std::uint64_t key = 0;
    double       value = 0.0;
};

struct CacheAllInCellMap {
    std::vector<CacheAllInCellEntry> entries;
    std::size_t used = 0;

    [[nodiscard]] static std::uint64_t hash_key(std::uint64_t key) noexcept {
        return key * 11400714819323198485ull;
    }

    void clear() noexcept {
        for (auto& e : entries) e.valid = false;
        used = 0;
    }

    void ensure_capacity(std::size_t expected_entries) {
        std::size_t cap = 1;
        const std::size_t needed = expected_entries * 2u;
        while (cap < needed) cap <<= 1u;
        if (cap == 0) cap = 1;
        if (entries.size() < cap) {
            entries.assign(cap, {});
        } else {
            clear();
        }
    }

    [[nodiscard]] double* find(std::uint64_t key) noexcept {
        if (entries.empty()) return nullptr;
        const std::size_t mask = entries.size() - 1u;
        std::size_t pos = static_cast<std::size_t>(hash_key(key)) & mask;
        while (true) {
            auto& e = entries[pos];
            if (!e.valid) return nullptr;
            if (e.key == key) return &e.value;
            pos = (pos + 1u) & mask;
        }
    }

    [[nodiscard]] double& get_or_insert(std::uint64_t key) {
        if (entries.empty() || (used + 1u) * 2u > entries.size()) {
            rehash(entries.empty() ? 4u : entries.size() * 2u);
        }

        const std::size_t mask = entries.size() - 1u;
        std::size_t pos = static_cast<std::size_t>(hash_key(key)) & mask;
        while (true) {
            auto& e = entries[pos];
            if (!e.valid) {
                e.valid = true;
                e.key = key;
                e.value = 0.0;
                ++used;
                return e.value;
            }
            if (e.key == key) return e.value;
            pos = (pos + 1u) & mask;
        }
    }

private:
    void rehash(std::size_t new_cap) {
        std::vector<CacheAllInCellEntry> old = std::move(entries);
        std::size_t cap = 1;
        while (cap < new_cap) cap <<= 1u;
        if (cap == 0) cap = 1;
        entries.assign(cap, {});
        used = 0;
        for (const auto& e : old) {
            if (!e.valid) continue;
            double& slot = get_or_insert(e.key);
            slot = e.value;
        }
    }
};

/// Per-Interpolated state mirroring Mojang's
/// `ChunkNoiseSampler.DensityInterpolator`. Each kInterpolated node
/// gets one of these (slot id assigned at NodeArena push time).
///
/// Layout:
///   - `start_density_buffer` and `end_density_buffer` are pre-filled
///     by the caller's `sample_start_density` / `sample_end_density`
///     entry points. They store the wrapped DF's value at every
///     (cellY, cellZ) lattice corner of one cell-X column. Indexing:
///     `buffer[cellZ * (vCC + 1) + cellY]` for grid (hCC+1) × (vCC+1).
///     Storage is flat double arrays, allocated once by
///     `prepare_for_chunk(...)`.
///
///   - `x{0,1}y{0,1}z{0,1}` hold the 8 corner values for the cell
///     currently being sampled (loaded by `on_sampled_cell_corners`).
///
///   - The cascaded fields `x{0,1}z{0,1}` (post-Y-interp), `z{0,1}`
///     (post-X-interp), and `result` (post-Z-interp) are the trilinear
///     interpolation in three steps, matching Mojang's interpolateY/X/Z.
///
/// All double-vectors live on the heap; this struct is moved/copied
/// rarely (per-chunk creation only), so allocation cost is amortised.
struct InterpolatorState {
    /// `[cellZ][cellY]` lattice corner values for the current cell-X
    /// column (X = startCellX + thisColumn). Size = (hCC+1) * (vCC+1).
    std::vector<double> start_density_buffer;
    /// Same as above, for the next cell-X column (X = startCellX +
    /// thisColumn + 1). After each cellX step, `swap_buffers` makes
    /// the end buffer the new start buffer for the following column.
    std::vector<double> end_density_buffer;

    /// 8-corner cube currently being sampled.
    double x0y0z0 = 0.0;
    double x0y0z1 = 0.0;
    double x1y0z0 = 0.0;
    double x1y0z1 = 0.0;
    double x0y1z0 = 0.0;
    double x0y1z1 = 0.0;
    double x1y1z0 = 0.0;
    double x1y1z1 = 0.0;

    /// After interpolateY: 4 values along the Y axis.
    double x0z0 = 0.0;
    double x1z0 = 0.0;
    double x0z1 = 0.0;
    double x1z1 = 0.0;

    /// After interpolateX: 2 values along the X axis.
    double z0 = 0.0;
    double z1 = 0.0;

    /// After interpolateZ: the final sample value the kInterpolated
    /// evaluator returns.
    double result = 0.0;
};

/// Per-chunk / per-evaluation cache state. Allocate once, reset between
/// chunks via `clear()`. Size each vector to match the arena's slot
/// counts (or larger).
struct CacheState {
    std::vector<Cache2DEntry>   cache_2d;
    std::vector<CacheOnceEntry> cache_once;
    std::vector<FlatCacheEntry> flat_cache;
    // CacheAllInCell uses a per-slot flat open-addressing map keyed by a
    // packed (cellX, cellZ, y) triple.
    std::vector<CacheAllInCellMap> cache_all_in_cell;
    std::vector<const double*> cache_all_in_cell_arrays;
    std::vector<std::size_t> cache_all_in_cell_array_lengths;

    /// Per-slot Interpolator state (one entry per kInterpolated node).
    std::vector<InterpolatorState> interpolators;

    /// Cell grid shape used by the interpolators. Set by
    /// `prepare_interpolators(arena, hCC, vCC)`.
    int horizontal_cell_count = 0;
    int vertical_cell_count   = 0;

    /// True between sample_start_density() and stop_interpolation().
    /// When true, kInterpolated returns interpolators[slot].result;
    /// when false, it passthrough-evaluates the wrapped input.
    bool is_in_interpolation_loop = false;

    /// Provision slots to match `arena`. Idempotent; existing entries
    /// keep their state (callers should `clear()` if cross-chunk reuse).
    /// Note: this does NOT allocate the per-interpolator buffers —
    /// call `prepare_interpolators(arena, hCC, vCC)` to do that.
    void resize_for(const NodeArena& arena) {
        cache_2d.resize(arena.num_cache_2d_slots);
        cache_once.resize(arena.num_cache_once_slots);
        flat_cache.resize(arena.num_flat_cache_slots);
        cache_all_in_cell.resize(arena.num_cache_all_in_cell_slots);
        cache_all_in_cell_arrays.resize(arena.num_cache_all_in_cell_slots, nullptr);
        cache_all_in_cell_array_lengths.resize(arena.num_cache_all_in_cell_slots, 0);
        interpolators.resize(arena.num_interpolator_slots);
    }

    /// Allocate the per-interpolator buffers to fit a chunk grid of
    /// `(hCC+1) × (vCC+1)` cell corners. Must be called after
    /// `resize_for` and before any sample_*_density call.
    void prepare_interpolators(int hCC, int vCC) {
        horizontal_cell_count = hCC;
        vertical_cell_count   = vCC;
        const std::size_t buf_size = static_cast<std::size_t>(hCC + 1)
                                   * static_cast<std::size_t>(vCC + 1);
        for (auto& it : interpolators) {
            it.start_density_buffer.assign(buf_size, 0.0);
            it.end_density_buffer.assign(buf_size, 0.0);
        }
    }

    /// Invalidate every entry. Call when moving from one chunk to the next.
    void clear() noexcept {
        for (auto& e : cache_2d)        e.valid = false;
        for (auto& e : cache_once)      e.valid = false;
        for (auto& e : flat_cache)      e.valid = false;
        for (auto& m : cache_all_in_cell) m.clear();
        for (auto& p : cache_all_in_cell_arrays) p = nullptr;
        for (auto& n : cache_all_in_cell_array_lengths) n = 0;
        // Interpolator buffers retain their allocation; only the
        // logical loop state is reset.
        is_in_interpolation_loop = false;
    }
};

/// Sampling context: 3D coordinates of the point being evaluated.
struct Context {
    double x;
    double y;
    double z;

    /// Optional per-evaluation cache. When null, Cache* nodes degrade
    /// to passthrough (Worldgen-4c behaviour).
    CacheState* cache = nullptr;

    /// Cell coordinates used by FlatCache and CacheAllInCell. The
    /// caller supplies them — typically blockX >> 2 / blockZ >> 2 for
    /// the standard 4×4 cell granularity, or whatever cell shape the
    /// NoiseRouter uses.
    int cellX = 0;
    int cellZ = 0;
    int inCellX = 0;
    int inCellY = 0;
    int inCellZ = 0;
    int cellWidth = 0;
    int cellHeight = 0;
};

/// Evaluate the tree rooted at `arena.root` at the given context.
/// Stack-safe up to ~10k node depth (which is far beyond any realistic
/// vanilla density function — the deepest is ~30).
[[nodiscard]] double evaluate(const NodeArena& arena, const Context& ctx) noexcept;

/// Evaluate a specific sub-tree.
[[nodiscard]] double evaluate(const NodeArena& arena, NodeRef root,
                              const Context& ctx) noexcept;

/// Batched cell-grid evaluation. Computes
///
///     out[(iy * nz + iz) * nx + ix] =
///         evaluate(arena, x0 + ix*dx, y0 + iy*dy, z0 + iz*dz, cellX0+ix, cellZ0+iz)
///
/// for `ix` in `[0, nx)`, `iy` in `[0, ny)`, `iz` in `[0, nz)`.
///
/// Caching: the supplied `cache` is shared across every sample. Cache*
/// nodes follow their normal "remember the most-recent key" semantics,
/// so they help when consecutive samples in the iteration order land
/// in the same key (e.g. CacheOnce sees an identical (x, y, z)).
/// FlatCache / Cache2D in this evaluator are single-entry LRUs and
/// will mostly *miss* during a sweep across distinct (cellX, cellZ)
/// pairs — that's the same behaviour as the single-sample API.
///
/// `cellX` advances by 1 per step in `ix`; `cellZ` advances by 1 per
/// step in `iz`. The caller drives `cellY` via repeated calls if
/// required (Mojang's Interpolated cell-fill uses different cellY-
/// stepping than X/Z).
///
/// `out` must have at least `nx * ny * nz` elements. The function does
/// not allocate. It does not bounds-check the size; the caller's JNI
/// wrapper is expected to validate.
void evaluate_grid(const NodeArena& arena, NodeRef root,
                   double x0, double y0, double z0,
                   double dx, double dy, double dz,
                   int cellX0, int cellZ0,
                   int nx, int ny, int nz,
                   CacheState* cache,
                   double* out) noexcept;

// ---- Interpolator operations (Mojang's DensityInterpolator API) --------
//
// These mirror the ChunkNoiseSampler methods that drive the interpolation
// loop. The caller (Java ChunkNoiseSampler or equivalent) invokes them
// in the same order as Mojang:
//
//   1. prepare_interpolators(arena, cache, hCC, vCC) — once per chunk.
//   2. For the start column: fill start_density_buffer for every
//      interpolator at every (cellZ, cellY) corner via
//      `set_start_density_buffer(slot, cellZ, cellY, value)`.
//   3. For each cellX column:
//        a. Fill end_density_buffer for the next column via
//           `set_end_density_buffer(slot, cellZ, cellY, value)`.
//        b. For each (cellY, cellZ) in the column:
//             i.   on_sampled_cell_corners(cache, cellY, cellZ)
//             ii.  For each (deltaY, deltaX, deltaZ) in the cell:
//                    interpolate_y(cache, deltaY)
//                    interpolate_x(cache, deltaX)
//                    interpolate_z(cache, deltaZ)
//                    // Now kInterpolated nodes return interpolator.result
//        c. swap_buffers(cache)
//   4. stop_interpolation(cache)
//
// The buffers' content is computed externally (by the caller, typically
// Java ChunkNoiseSampler driving evaluate(...) on each kInterpolated
// node's wrapped input). This module owns only the trilinear math.
//
// The `cache` must have been `resize_for(arena)` and
// `prepare_interpolators(hCC, vCC)` before any of these calls.

/// Mark the interpolation loop as active. Subsequent kInterpolated
/// evaluations will return the cascaded `result` from their slot.
void start_interpolation(CacheState& cache) noexcept;

/// Set one entry in slot `slot`'s start-density buffer. Indexing is
/// `[cellZ][cellY]` matching Mojang's `startDensityBuffer[j][k]`.
void set_start_density(CacheState& cache, int slot,
                       int cellZ, int cellY, double value) noexcept;

/// Set one entry in slot `slot`'s end-density buffer.
void set_end_density(CacheState& cache, int slot,
                     int cellZ, int cellY, double value) noexcept;

/// Load the 8 corner values for cell (cellY, cellZ) from the
/// start/end buffers into each interpolator's x{0,1}y{0,1}z{0,1} fields.
/// `cellY` and `cellZ` are relative to the current column.
void on_sampled_cell_corners(CacheState& cache, int cellY, int cellZ) noexcept;

/// Perform the Y-axis interpolation step for all interpolators.
/// `deltaY` is the fractional offset within the cell (0.0 at the
/// lower corner, 1.0 at the upper corner). Produces x{0,1}z{0,1} from
/// the 8 corners.
void interpolate_y(CacheState& cache, double deltaY) noexcept;

/// Perform the X-axis interpolation step. `deltaX` is the fractional
/// offset within the cell. Produces z{0,1} from x{0,1}z{0,1}.
void interpolate_x(CacheState& cache, double deltaX) noexcept;

/// Perform the Z-axis interpolation step. `deltaZ` is the fractional
/// offset within the cell. Produces `result` from z{0,1}.
void interpolate_z(CacheState& cache, double deltaZ) noexcept;

/// Swap start_density_buffer ↔ end_density_buffer for all interpolators.
/// Called after finishing a cell-X column, so the next column's "start"
/// is the previous column's "end".
void swap_buffers(CacheState& cache) noexcept;

/// Mark the interpolation loop as inactive. After this, kInterpolated
/// nodes revert to passthrough evaluation of their wrapped input.
void stop_interpolation(CacheState& cache) noexcept;

} // namespace lattice::world::gen::densityfunction
