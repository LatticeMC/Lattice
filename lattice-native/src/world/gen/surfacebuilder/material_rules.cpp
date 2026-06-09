// MaterialRules tree evaluator. See material_rules.hpp.

#include "world/gen/surfacebuilder/material_rules.hpp"

#include <algorithm>
#include <limits>

#include "world/gen/rng/xoroshiro128pp.hpp"

namespace lattice::world::gen::surfacebuilder {

bool evaluate_condition(const Arena& a, int cond_ref,
                        const SampleContext& ctx) noexcept {
    if (cond_ref < 0 || cond_ref >= static_cast<int>(a.conditions.size())) return false;
    const Condition& c = a.conditions[cond_ref];
    switch (c.kind) {
        case ConditionKind::kAlwaysTrue:
            return true;

        case ConditionKind::kAboveY:
            return ctx.y >= c.i0;

        case ConditionKind::kAboveYWithSurface:
            return ctx.y + (c.i2 != 0 ? ctx.stone_depth_floor : 0)
                >= c.i0 + ctx.surface_depth * c.i1;

        case ConditionKind::kStoneDepth: {
            // i0 = offset, i1 = adjust_surface_depth (0/1), i2 = secondary_range, i3 = ceiling (0/1)
            const bool   ceiling     = (c.i3 != 0);
            const int    depth       = ceiling ? ctx.stone_depth_ceiling : ctx.stone_depth_floor;
            const int    surface_add = (c.i1 != 0) ? ctx.surface_depth : 0;

            int secondary_add = 0;
            if (c.i2 != 0) {
                const double t = (ctx.surface_secondary_noise + 1.0) * 0.5;
                const double mapped = t * static_cast<double>(c.i2);
                secondary_add = static_cast<int>(mapped);
            }

            return depth <= 1 + c.i0 + surface_add + secondary_add;
        }

        case ConditionKind::kVerticalGradient: {
            // True at and below i0; false at and above i1; linear gradient
            // chance in between, evaluated against the exact positional
            // Xoroshiro stream derived from the Java-side random factory.
            if (ctx.y <= c.i0) return true;
            if (ctx.y >= c.i1) return false;
            const double d = static_cast<double>(c.i1 - ctx.y)
                           / static_cast<double>(c.i1 - c.i0);
            lattice::world::gen::rng::Splitter splitter(
                static_cast<std::uint64_t>(c.s0),
                static_cast<std::uint64_t>(c.s1));
            auto random = splitter.split(ctx.x, ctx.y, ctx.z);
            return static_cast<double>(random.next_float()) < d;
        }

        case ConditionKind::kNoiseThreshold:
            return ctx.surface_noise >= c.d0 && ctx.surface_noise <= c.d1;

        case ConditionKind::kSecondaryNoiseThreshold:
            return ctx.surface_secondary_noise >= c.d0
                && ctx.surface_secondary_noise <= c.d1;

        case ConditionKind::kHole:
            return ctx.hole_at_position;

        case ConditionKind::kSteepSlope:
            return ctx.steep_slope;

        case ConditionKind::kTemperatureFrozen:
            return ctx.temperature < 0.15;

        case ConditionKind::kBiomeIs: {
            const int beg = c.pool_off;
            const int end = beg + c.pool_count;
            if (beg < 0 || end > static_cast<int>(a.int_pool.size())) return false;
            if (c.pool_count == 1) return a.int_pool[beg] == ctx.biome_id;
            if (c.i0 != 0) {
                const int* first = a.int_pool.data() + beg;
                const int* last  = a.int_pool.data() + end;
                return std::binary_search(first, last, ctx.biome_id);
            }
            for (int i = beg; i < end; ++i) {
                if (a.int_pool[i] == ctx.biome_id) return true;
            }
            return false;
        }

        case ConditionKind::kNamedNoiseThreshold: {
            if (!ctx.noise_values || c.i0 < 0 || c.i0 >= ctx.noise_value_count) return false;
            const double value = ctx.noise_values[c.i0];
            return value >= c.d0 && value <= c.d1;
        }

        case ConditionKind::kWater:
            return ctx.fluid_height == std::numeric_limits<int>::min()
                || ctx.y + (c.i2 != 0 ? ctx.stone_depth_floor : 0)
                    >= ctx.fluid_height + c.i0 + ctx.surface_depth * c.i1;

        case ConditionKind::kAbovePreliminarySurface:
            return ctx.y >= ctx.min_surface_level;

        case ConditionKind::kNot:
            return !evaluate_condition(a, c.a, ctx);
    }
    return false;
}

int evaluate_rule(const Arena& a, int rule_ref,
                  const SampleContext& ctx) noexcept {
    if (rule_ref < 0 || rule_ref >= static_cast<int>(a.rules.size())) return kNoMatch;
    const Rule& r = a.rules[rule_ref];
    switch (r.kind) {
        case RuleKind::kBlock:
            return r.block_index;
        case RuleKind::kConditional:
            if (evaluate_condition(a, r.cond_ref, ctx)) {
                return evaluate_rule(a, r.child_rule_ref, ctx);
            }
            return kNoMatch;
        case RuleKind::kSequence: {
            const int beg = r.pool_off;
            const int end = beg + r.pool_count;
            if (beg < 0 || end > static_cast<int>(a.int_pool.size())) return kNoMatch;
            for (int i = beg; i < end; ++i) {
                const int child_ref = a.int_pool[i];
                const int result = evaluate_rule(a, child_ref, ctx);
                if (result != kNoMatch) return result;
            }
            return kNoMatch;
        }
        case RuleKind::kBandlands:
            return kBandlandsSentinel;
    }
    return kNoMatch;
}

int evaluate(const Arena& a, const SampleContext& ctx) noexcept {
    return evaluate_rule(a, a.root_rule, ctx);
}

} // namespace lattice::world::gen::surfacebuilder
