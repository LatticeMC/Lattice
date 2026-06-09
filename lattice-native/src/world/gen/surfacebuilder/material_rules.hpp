/**
 * @file material_rules.hpp
 * @brief Evaluator for Mojang's `MaterialRules` tree (vanilla
 *        `net.minecraft.world.gen.surfacebuilder.MaterialRules` /
 *        `class_6686`).
 *
 * MaterialRules is the data-pack-pluggable tree used by SurfaceBuilder
 * to decide which block to place at each (x, y, z) during chunk
 * generation. It has two node families:
 *
 *   - **Condition** (`class_6693`): a predicate over a sample context.
 *     Examples: aboveY, biomeIs, noiseThreshold, hole, steepSlope,
 *     temperature, verticalGradient, stoneDepth, water,
 *     abovePreliminarySurface, not.
 *
 *   - **Rule** (`class_6708`): produces a BlockState id (or "no match").
 *     Examples: block, condition(c, r), sequence([r1, r2, ...]),
 *     terracottaBands.
 *
 * Java provides a `SampleContext` populated for the (x, y, z) being
 * evaluated: pre-computed biome id, stone depth, surface depth, etc.
 * Native runs the tree against the context and returns either a
 * BlockState index (caller-defined; usually a registry index) or
 * `kNoMatch` (no block selected — Java falls back to the column's
 * default).
 *
 * The evaluator intentionally works on a flattened sample context so the
 * Java side can precompute anything that would otherwise require callbacks
 * into world state. That keeps the hot path in native code while still
 * matching vanilla `SurfaceRules` semantics closely.
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace lattice::world::gen::surfacebuilder {

inline constexpr std::int32_t kNoMatch = -1;
inline constexpr std::int32_t kBandlandsSentinel = -2;

// ---- Sample context ------------------------------------------------------

/// Pre-computed per-(x, y, z) inputs to the rule tree. Java fills this
/// in once per sample point; the evaluator references the fields from
/// each Condition without going back through JNI.
struct SampleContext {
    int    x;
    int    y;
    int    z;
    int    surface_y;          // top solid y in this column (caller-owned extra metadata)
    int    fluid_height;       // topmost fluid y; Integer.MIN_VALUE means "no water"
    int    stone_depth_floor;  // vanilla floor-side stone depth (SurfaceRules.Context.stoneDepthAbove)
    int    stone_depth_ceiling; // vanilla ceiling-side stone depth (SurfaceRules.Context.stoneDepthBelow)
    int    biome_id;           // caller-defined biome enum index
    int    surface_depth;      // vanilla Context.surfaceDepth
    int    min_surface_level;  // vanilla Context.getMinSurfaceLevel()
    double temperature;        // [0..1] Mojang-conventional climate
    double surface_noise;
    double surface_secondary_noise;
    bool   hole_at_position;   // SurfaceBuilder's "hole" flag
    bool   steep_slope;        // pre-computed steep-slope flag
    const double* noise_values = nullptr; // resolved per-noise samples for named noise conditions
    int    noise_value_count = 0;
};

// ---- Conditions ----------------------------------------------------------

enum class ConditionKind : std::uint8_t {
    kAlwaysTrue,           // (no params)
    kAboveY,               // i0 = minYInclusive
    kAboveYWithSurface,    // i0 = minY, i1 = surfaceDepthMultiplier, i2 = addStoneDepth(bool)
    kStoneDepth,           // i0 = offset, i1 = adjustSurfaceDepth(bool), i2 = secondaryDepthRange, i3 = ceiling(bool)
    kVerticalGradient,     // i0 = trueAtAndBelow, i1 = falseAtAndAbove, s0/s1 = positional factory seed words
    kNoiseThreshold,       // d0 = lowerBound, d1 = upperBound; uses `surface_noise`
    kSecondaryNoiseThreshold,
    kHole,                 // (no params)
    kSteepSlope,           // (no params)
    kTemperatureFrozen,    // temperature < 0.15 (Mojang's "freeze" threshold)
    kBiomeIs,              // pool_off + pool_count into shared int pool; i0=1 means ids sorted
    kWater,                // i0 = offset, i1 = surfaceDepthMultiplier, i2 = addStoneDepth(bool)
    kAbovePreliminarySurface, // (no params)
    kNamedNoiseThreshold,  // i0 = noise slot, d0 = lowerBound, d1 = upperBound
    kNot,                  // operand a = inner condition
};

struct Condition {
    ConditionKind kind;
    std::int32_t  a       = -1; // inner condition (Not)
    int           i0      = 0;
    int           i1      = 0;
    int           i2      = 0;
    int           i3      = 0;
    std::int64_t  s0      = 0;
    std::int64_t  s1      = 0;
    double        d0      = 0.0;
    double        d1      = 0.0;
    // For kBiomeIs: offset + count into the arena's `int_pool`.
    int           pool_off   = 0;
    int           pool_count = 0;
};

// ---- Rules ---------------------------------------------------------------

enum class RuleKind : std::uint8_t {
    kBlock,                // i0 = block_index, the value returned on match
    kConditional,          // (cond, rule_if_true)
    kSequence,             // a_int_list = ordered child rule refs (in int_pool)
    kBandlands,            // special sentinel resolved by the Java bridge
};

struct Rule {
    RuleKind kind;
    int      block_index    = kNoMatch; // kBlock
    int      cond_ref       = -1;       // kConditional
    int      child_rule_ref = -1;       // kConditional
    int      pool_off       = 0;        // kSequence
    int      pool_count     = 0;
};

// ---- Arena ---------------------------------------------------------------

struct Arena {
    std::vector<Condition>  conditions;
    std::vector<Rule>       rules;
    std::vector<int>        int_pool;  // shared by kBiomeIs + kSequence
    int                     root_rule = -1;
};

/// Evaluate `arena.root_rule` against `ctx`. Returns the matched
/// block index, or `kNoMatch` if no rule produced a block.
[[nodiscard]] int evaluate(const Arena& arena, const SampleContext& ctx) noexcept;

/// Evaluate a specific rule sub-tree.
[[nodiscard]] int evaluate_rule(const Arena& arena, int rule_ref,
                                const SampleContext& ctx) noexcept;

/// Evaluate a condition sub-tree. Public for testing.
[[nodiscard]] bool evaluate_condition(const Arena& arena, int cond_ref,
                                      const SampleContext& ctx) noexcept;

} // namespace lattice::world::gen::surfacebuilder
