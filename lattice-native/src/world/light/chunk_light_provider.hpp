/**
 * @file chunk_light_provider.hpp
 * @brief Bridge subclass that delegates `get_propagated_level` and the
 *        neighbour enumeration back to Java via a JNI callback.
 *
 * Vanilla layers `ChunkLightProvider` (`class_3558`) on top of
 * `LevelPropagator` and adds Minecraft-specific machinery: a block-state
 * cache (`cachedChunks` / `cachedChunkPositions`), opacity & shape
 * lookups, and the `PackedInfo` packing for the propagation argument.
 *
 * In Phase 1 we keep the world-side queries in Java — running a
 * cross-language callback per neighbour is slower than the original Java
 * code in a microbenchmark, but the **algorithmic** parts of the
 * propagator (the per-level worklist, the `pendingUpdates` map, the
 * dequeue-and-redrive loop) get the native treatment, where reduced
 * GC pressure and better hash-table layout offset the JNI cost.
 *
 * The callback shape is intentionally minimal: when the BFS needs to
 * know "what level would `target` end up at if a propagation of level
 * `level` arrived from `source`?", it calls the supplied
 * `Callbacks::get_propagated_level`. Neighbour iteration is also
 * deferred to Java because the topology of "what counts as a neighbour"
 * depends on chunk boundaries, sky-light short-circuits, and other
 * world-shape concerns that aren't worth re-implementing here.
 */

#pragma once

#include <cstdint>

#include "world/light/level_propagator.hpp"

namespace lattice::world::light {

/// Hooks the BFS needs to query the world. Concrete bindings (in
/// `jni/light_engine.cpp`) point each function at a cached JNIEnv +
/// jmethodID pair so the call cost stays at a single virtual dispatch.
struct LightProviderCallbacks {
    void* user_data = nullptr;

    /// Mirrors `getPropagatedLevel`. Returns the level that `target_id`
    /// would acquire if the propagator pushed `level` from `source_id`.
    int (*get_propagated_level)(void* user_data, std::int64_t source_id,
                                std::int64_t target_id, int level) = nullptr;

    /// Mirrors `propagateLevel(sourceId, targetId, level, decrease)` — for
    /// each neighbour of `target_id`, the Java side invokes
    /// `update_level(target_id, neighbour, level, decrease)` on the
    /// supplied propagator. Implementations enumerate neighbours
    /// directly to avoid a second callback per neighbour.
    void (*propagate_level)(void* user_data, LevelPropagator* prop,
                            std::int64_t source_id, std::int64_t target_id,
                            int level, bool decrease) = nullptr;

    /// Mirrors `isMarker`. Optional; default behaviour is "no markers".
    bool (*is_marker)(void* user_data, std::int64_t id) = nullptr;

    /// Optional recomputation hook used by decrease/replay paths that need
    /// to ask the world for the best remaining propagated level.
    int (*recalculate_level)(void* user_data, std::int64_t id,
                             std::int64_t excluded_id, int max_level) = nullptr;

    /// Optional storage hooks: when present the propagator uses these to
    /// store / fetch the *committed* light level. When null the
    /// propagator falls back to its internal map (same as the
    /// storage-less base `LevelPropagator` default).
    int  (*get_level)(void* user_data, std::int64_t id) = nullptr;
    void (*set_level)(void* user_data, std::int64_t id, int level) = nullptr;
};

class ChunkLightProvider final : public LevelPropagator {
public:
    ChunkLightProvider(int level_count,
                       std::size_t expected_level_size,
                       std::size_t expected_total_size,
                       LightProviderCallbacks callbacks) noexcept
        : LevelPropagator(level_count, expected_level_size, expected_total_size),
          cb_(callbacks) {}

    // Bring the public 3-arg propagate_level overload back into scope —
    // it would otherwise be hidden by our 4-arg override below.
    using LevelPropagator::propagate_level;

    // ---- LevelPropagator overrides ----
    [[nodiscard]] int get_propagated_level(std::int64_t source_id,
                                           std::int64_t target_id,
                                           int level) noexcept override {
        if (cb_.get_propagated_level) {
            return cb_.get_propagated_level(cb_.user_data, source_id, target_id, level);
        }
        return level_count_; // no callback ⇒ no propagation
    }

    void propagate_level(std::int64_t source_id, std::int64_t target_id,
                         int level, bool decrease) noexcept override {
        if (cb_.propagate_level) {
            cb_.propagate_level(cb_.user_data, this, source_id, target_id, level, decrease);
        }
    }

    [[nodiscard]] bool is_marker(std::int64_t id) const noexcept override {
        return cb_.is_marker ? cb_.is_marker(cb_.user_data, id) : false;
    }

    [[nodiscard]] int get_level(std::int64_t id) const noexcept override {
        return cb_.get_level ? cb_.get_level(cb_.user_data, id) : level_count_;
    }

    [[nodiscard]] int do_recalculate_level(std::int64_t id,
                                           std::int64_t excluded_id,
                                           int max_level) noexcept override {
        if (cb_.recalculate_level) {
            return cb_.recalculate_level(cb_.user_data, id, excluded_id, max_level);
        }
        return LevelPropagator::do_recalculate_level(id, excluded_id, max_level);
    }

    void set_level(std::int64_t id, int level) noexcept override {
        if (cb_.set_level) cb_.set_level(cb_.user_data, id, level);
    }

    void reset_level(std::int64_t id) noexcept override {
        if (cb_.set_level) cb_.set_level(cb_.user_data, id, level_count_ - 1);
    }

private:
    LightProviderCallbacks cb_;
};

} // namespace lattice::world::light
