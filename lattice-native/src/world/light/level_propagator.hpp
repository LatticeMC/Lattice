/**
 * @file level_propagator.hpp
 * @brief BFS-style level propagator. 1:1 with
 *        `net.minecraft.world.chunk.light.LevelPropagator` (`class_3554`).
 *
 * The propagator maintains a per-id "current level" and a worklist of
 * pending updates organised by tentative level. Iterating the worklist
 * from low levels to high — `apply_pending_updates(max_steps)` — drives
 * the system to a fixed point where every id's level equals the highest
 * level reachable from any source via `get_propagated_level`.
 *
 * Levels are non-decreasing integers in `[0, level_count)`. Vanilla uses
 * `level_count = MAX_LEVEL + 2 = 17` for both block-light (MAX=15+1) and
 * sky-light (MAX=15+1). The "marker" level `level_count` represents "no
 * level / unset".
 *
 * Virtual hooks
 * -------------
 * Concrete propagators override:
 *
 *   - `get_propagated_level(source_id, target_id, level)` — what level
 *     would `target_id` end up at if a propagation of strength `level`
 *     arrived from `source_id`? Vanilla's analogue is
 *     `method_15488 getPropagatedLevel`.
 *
 *   - `propagate_level(source_id, target_id, level, decrease)` —
 *     enumerate neighbours of `target_id` and call
 *     `update_level(target_id, neighbour, level, decrease)` on each.
 *     Vanilla's analogue is `method_15484`. The default implementation
 *     calls `for_each_neighbour` (must be supplied).
 *
 *   - `is_marker(id)` — returns true if `id` is a meaningless / dummy
 *     entry that should be skipped. Vanilla's `method_15494`.
 *
 * Concrete subclasses live in `chunk_light_provider.{hpp,cpp}` and
 * `block_light_provider.{hpp,cpp}` / `sky_light_provider.{hpp,cpp}`.
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdlib>   // std::malloc / std::free for the remove_if helper

#include "world/light/long_to_byte_map.hpp"
#include "world/light/pending_update_queue.hpp"

namespace lattice::world::light {

class LevelPropagator {
public:
    LevelPropagator(int level_count,
                    std::size_t expected_level_size,
                    std::size_t expected_total_size) noexcept;
    virtual ~LevelPropagator() noexcept = default;

    LevelPropagator(const LevelPropagator&)            = delete;
    LevelPropagator& operator=(const LevelPropagator&) = delete;

    // ---- Vanilla API surface -------------------------------------------

    /// `method_15489 hasPendingUpdates()`
    [[nodiscard]] bool has_pending_updates() const noexcept {
        return has_pending_updates_;
    }

    /// `method_24208 getPendingUpdateCount()`
    [[nodiscard]] std::size_t get_pending_update_count() const noexcept {
        return pending_updates_.size();
    }

    /// `method_15492 applyPendingUpdates(int)`.
    /// Drives the BFS until either the queue drains or `max_steps`
    /// dequeue operations have been performed; returns the number of
    /// remaining steps (`max_steps - n_processed`). The vanilla return
    /// is exactly that quantity.
    int apply_pending_updates(int max_steps) noexcept;

    /// `method_15487 propagateLevel(id, level, decrease)` — public entry
    /// used by ChunkLightProvider seeds (a "source" whose own level is
    /// fixed and from which we want to push). Calls
    /// `propagate_level(id, *, level, decrease)` for each neighbour.
    void propagate_level(std::int64_t id, int level, bool decrease) noexcept {
        propagate_level(id, id, level, decrease);
    }

    /// `method_15485 setLevel(id, level)`. Imperative override of an id's
    /// level; bypasses the BFS. Vanilla uses this from sub-classes for
    /// initialisation paths. Default is storage-less; subclasses with
    /// explicit storage override.
    virtual void set_level(std::int64_t /*id*/, int /*level*/) noexcept {}

    /// `method_15480 getLevel(id)`. Returns the propagator's view of the
    /// current level. Default: storage-less, returns `level_count_` (the
    /// marker). Subclasses override.
    [[nodiscard]] virtual int get_level(std::int64_t /*id*/) const noexcept {
        return level_count_;
    }

    /// `method_15486 recalculateLevel(id, excludedId, maxLevel)`.
    /// Recomputes the best level for `id` considering all neighbours
    /// except `excluded_id`, capped at `max_level`. Used during a
    /// decrease pass to find the new equilibrium for a node whose
    /// strongest source just dropped out.
    int recalculate_level(std::int64_t id, std::int64_t excluded_id, int max_level) noexcept;

    /// `method_50014 calculateLevel(a, b)`. Returns the *combined* level
    /// when two propagations meet at a node — vanilla takes the smaller
    /// of the two (lower-numbered levels mean brighter/closer-to-source).
    [[nodiscard]] static constexpr int calculate_level(int a, int b) noexcept {
        return a < b ? a : b;
    }

    /// `method_15478 updateLevel(sourceId, id, level, decrease)`.
    /// Public entry for enqueueing a candidate update onto the worklist.
    void update_level(std::int64_t source_id, std::int64_t id,
                      int level, bool decrease) noexcept;

    /// `method_24206 removePendingUpdateIf(predicate)`. Predicate runs
    /// over every id currently in the pending-updates map; ids for which
    /// `p(id)` returns true are removed in a separate pass (mutating the
    /// map during iteration would be undefined).
    template <class Predicate>
    void remove_pending_update_if(Predicate p) noexcept {
        // Inline small-buffer optimisation. 256 ids ≈ 2 KB on the stack.
        constexpr std::size_t kStackBuf = 256;
        std::int64_t inline_buf[kStackBuf];
        std::int64_t* victims = inline_buf;
        std::size_t victims_size = 0;
        std::size_t victims_cap = kStackBuf;

        pending_updates_.for_each([&](std::int64_t k, std::int8_t /*v*/) noexcept {
            if (!p(k)) return;
            if (victims_size == victims_cap) {
                const std::size_t new_cap = victims_cap * 2;
                std::int64_t* fresh = static_cast<std::int64_t*>(
                    std::malloc(new_cap * sizeof(std::int64_t)));
                if (!fresh) return; // best-effort: drop this victim
                for (std::size_t i = 0; i < victims_size; ++i) fresh[i] = victims[i];
                if (victims != inline_buf) std::free(victims);
                victims = fresh;
                victims_cap = new_cap;
            }
            victims[victims_size++] = k;
        });

        for (std::size_t i = 0; i < victims_size; ++i) {
            remove_pending_update(victims[i]);
        }
        if (victims != inline_buf) std::free(victims);
    }

    /// `method_15483 removePendingUpdate(id)`.
    void remove_pending_update(std::int64_t id) noexcept;

    /// `method_15491 resetLevel(id)`. Set the stored level to the
    /// "marker" value (i.e. unset). Default no-op (storage-less); subclasses override.
    virtual void reset_level(std::int64_t /*id*/) noexcept {}

    [[nodiscard]] int level_count() const noexcept { return level_count_; }

    static constexpr int kMaxLevel = 15;   // `field_31706 MAX_LEVEL = 15`

protected:
    // ---- Hooks for concrete subclasses ---------------------------------

    [[nodiscard]] int calculate_priority(int current_level, int pending_level) const noexcept {
        int a = current_level;
        int b = pending_level;
        if (a < 0) a = 0;
        else if (a >= level_count_) a = level_count_ - 1;
        if (b < 0) b = 0;
        else if (b >= level_count_) b = level_count_ - 1;
        return a < b ? a : b;
    }

    /// `method_15488 getPropagatedLevel(sourceId, targetId, level)`. Pure.
    [[nodiscard]] virtual int get_propagated_level(std::int64_t source_id,
                                                   std::int64_t target_id,
                                                   int level) noexcept = 0;

    [[nodiscard]] virtual int do_recalculate_level(std::int64_t id,
                                                   std::int64_t excluded_id,
                                                   int max_level) noexcept;

    /// `method_15484 propagateLevel(sourceId, targetId, level, decrease)` — for each
    /// neighbour of `target_id`, call `update_level(target_id, n, level, decrease)`.
    /// Concrete subclasses know the neighbour topology (6 cardinal block
    /// neighbours for light); they implement this directly to avoid the
    /// indirection of a separate `for_each_neighbour` callback.
    virtual void propagate_level(std::int64_t source_id, std::int64_t target_id,
                                 int level, bool decrease) noexcept = 0;

    /// `method_15494 isMarker(id)`. Default: false. Override to skip
    /// "invalid" ids like out-of-world block positions.
    [[nodiscard]] virtual bool is_marker(std::int64_t /*id*/) const noexcept {
        return false;
    }

    [[nodiscard]] int min_pending_level() const noexcept {
        // Diagnostic accessor; primarily for tests.
        // The queue itself manages this.
        return queue_.is_empty() ? level_count_ : 0;
    }

    // ---- BFS internals --------------------------------------------------
private:
    /// `method_15482 updateLevel(sourceId, id, level, currentLevel, oldLevel, decrease)`.
    void update_level(std::int64_t source_id, std::int64_t id, int level,
                      int current_level, int old_level, bool decrease) noexcept;

    /// Compute `pending_updates_.get_or_default(id)` clamped to a valid level.
    [[nodiscard]] int get_pending_level(std::int64_t id) const noexcept {
        // Stored as a signed byte; cast back to int and clamp at level_count_.
        const std::int8_t v = pending_updates_.get_or_default(id);
        const int iv = static_cast<int>(v) & 0xFF; // unsigned promotion to [0,255]
        return iv;
    }

protected:
    // `field_15783 levelCount`
    int level_count_;
    // `field_15782 hasPendingUpdates`
    bool has_pending_updates_ = false;
    // `field_15784 pendingUpdates` — tentative level per id.
    Long2ByteMap pending_updates_;
    // `field_43396 pendingUpdateQueue`
    PendingUpdateQueue queue_;
};

} // namespace lattice::world::light
