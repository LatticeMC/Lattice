// 1:1 translation of `LevelPropagator` (`class_3554`) from the yarn-1.21.11
// decompile. The control flow below is deliberately verbose so that the
// shape lines up with the bytecode (and so reviewers can diff against the
// original `applyPendingUpdates` and `updateLevel` methods directly).

#include "world/light/level_propagator.hpp"

namespace lattice::world::light {

LevelPropagator::LevelPropagator(int level_count,
                                 std::size_t expected_level_size,
                                 std::size_t expected_total_size) noexcept
    : level_count_(level_count),
      // Vanilla: `pendingUpdates.defaultReturnValue((byte) (level_count - 1))`.
      // The "default return value" for absent ids is `level_count_` — the
      // marker value — because that's how `getPendingLevel` interprets "no
      // entry". fastutil stores a signed byte; we mirror that exactly.
      pending_updates_(expected_total_size,
                       static_cast<std::int8_t>(level_count > 127 ? 127 : level_count)) {
    queue_.initialize(level_count, expected_level_size);
}

// ---------------------------------------------------------------------------
// updateLevel — both vanilla overloads
// ---------------------------------------------------------------------------

void LevelPropagator::update_level(std::int64_t source_id, std::int64_t id,
                                   int level, bool decrease) noexcept {
    // Public override (`method_15478`): look up the current and previous
    // tentative level, then delegate to the long-form variant which does
    // its own clamping.
    if (is_marker(id)) return;
    const int current_level = get_pending_level(id);
    const int old_level     = get_level(id);
    update_level(source_id, id, level, current_level, old_level, decrease);
}

void LevelPropagator::update_level(std::int64_t source_id, std::int64_t id, int level,
                                   int current_level, int old_level, bool decrease) noexcept {
    // `method_15482 updateLevel(JJIIIZ)V`.
    //
    // The logic forks on whether the proposed `level` is an improvement
    // (smaller / brighter) or a regression compared to what's already
    // tentatively known.
    if (is_marker(id)) return;

    // The clamp on `level` is the vanilla "if (level < 0) level = 0;
    // if (level >= level_count_) level = level_count_ - 1;" pair.
    if (level < 0)                  level = 0;
    if (level >= level_count_)      level = level_count_ - 1;

    const bool has_pending = (current_level != level_count_);

    if (!has_pending) {
        // No prior tentative level. Compare against the actual stored
        // level (`old_level`) and decide whether to enqueue.
        current_level = old_level;
    }

    // Vanilla's `method_15482 updateLevel(JJIIIZ)V`: for both passes the
    // new tentative is `min(currentLevel, candidate)`. The candidate is
    // computed differently for increase vs decrease:
    //
    //   increase : candidate = clamp(getPropagatedLevel(src, id, level))
    //   decrease : candidate = level
    //
    // Lower level numbers represent brighter light, so "min" is the
    // brightest-so-far. The Math.min lookup mirrors vanilla; we hand-roll
    // the comparison because we're already in long form.
    int candidate;
    if (decrease) {
        if (source_id == id) {
            candidate = level;
        } else {
            candidate = recalculate_level(id, source_id, level);
        }
    } else {
        if (source_id == id) {
            candidate = level;  // self-seed; no propagation involved
        } else {
            candidate = get_propagated_level(source_id, id, level);
        }
    }
    if (candidate < 0)                       candidate = 0;
    else if (candidate >= level_count_)      candidate = level_count_ - 1;

    const int new_level = candidate < current_level ? candidate : current_level;
    const int current_priority = calculate_priority(old_level, current_level);
    const int new_priority = calculate_priority(old_level, new_level);

    if (old_level != new_level) {
        if (current_priority != new_priority && has_pending) {
            queue_.remove(id, current_priority, level_count_);
        }
        queue_.enqueue(id, new_priority);
        if (new_level < level_count_) {
            pending_updates_.put(id, static_cast<std::int8_t>(new_level));
        } else {
            pending_updates_.remove(id);
        }
        has_pending_updates_ = !queue_.is_empty();
    } else if (has_pending) {
        queue_.remove(id, current_priority, level_count_);
        pending_updates_.remove(id);
        has_pending_updates_ = !queue_.is_empty();
    }
}

// ---------------------------------------------------------------------------
// applyPendingUpdates
// ---------------------------------------------------------------------------

int LevelPropagator::apply_pending_updates(int max_steps) noexcept {
    // `method_15492 applyPendingUpdates(I)I`.
    //
    // Dequeue work items in ascending-level order. For each, decide
    // whether it represents an increase or a decrease relative to the
    // currently stored level, and call `propagate_level` so the subclass
    // can fan out to neighbours.
    while (max_steps > 0 && !queue_.is_empty()) {
        const std::int64_t id = queue_.dequeue();
        if (id == PendingUpdateQueue::kEmpty) break;

        // Read-and-remove the tentative level we'd previously assigned.
        // If the id was absent, `remove` returns the configured default
        // (level_count_ ≡ "no level marker"). The "& 0xFF" mirrors
        // vanilla's signed-byte → unsigned-int promotion.
        const int new_level_byte = pending_updates_.remove(id) & 0xFF;

        if (new_level_byte == level_count_) {
            // Tentative entry was the "no level" marker — nothing to do.
            --max_steps;
            continue;
        }

        // Vanilla clamps to [0, levelCount-1]; the default value is already
        // levelCount, so once we've ruled that out the clamp is a no-op for
        // valid data, but we apply it explicitly to match behaviour for
        // corrupted maps.
        int new_level = new_level_byte;
        if (new_level < 0)                new_level = 0;
        else if (new_level > level_count_ - 1) new_level = level_count_ - 1;
        const int old_level = get_level(id);

        if (new_level < old_level) {
            // Increase (brighter / closer): commit the new level and
            // propagate it to neighbours.
            set_level(id, new_level);
            propagate_level(id, id, new_level, false);
        } else if (new_level > old_level) {
            // Decrease: first clear the committed level so neighbour
            // re-evaluation observes this node as temporarily absent,
            // matching vanilla's two-phase "clear then replay" shape.
            set_level(id, level_count_);
            if (new_level < level_count_) {
                pending_updates_.put(id, static_cast<std::int8_t>(new_level));
                queue_.enqueue(id, new_level);
            }

            // Ask the subclass to retract any prior propagation that this
            // node contributed to its neighbours using the *old* level.
            propagate_level(id, id, old_level, true);
        }
        // If they're equal, the tentative collapsed back to current; no work.

        --max_steps;
    }
    has_pending_updates_ = !queue_.is_empty();
    return max_steps;
}

// ---------------------------------------------------------------------------
// recalculateLevel
// ---------------------------------------------------------------------------

int LevelPropagator::recalculate_level(std::int64_t id, std::int64_t excluded_id,
                                       int max_level) noexcept {
    // `method_15486 recalculateLevel(JJI)I`.
    //
    // Our callback topology does not let the base class enumerate
    // neighbours generically, so concrete subclasses provide the actual
    // recomputation logic via `do_recalculate_level`. The public wrapper
    // still clamps the cap first to preserve the vanilla contract.
    if (max_level < 0) max_level = 0;
    else if (max_level >= level_count_) max_level = level_count_ - 1;
    return do_recalculate_level(id, excluded_id, max_level);
}

int LevelPropagator::do_recalculate_level(std::int64_t /*id*/,
                                          std::int64_t /*excluded_id*/,
                                          int max_level) noexcept {
    return max_level;
}

// ---------------------------------------------------------------------------
// removePendingUpdate (non-template; the template variant is header-defined)
// ---------------------------------------------------------------------------

void LevelPropagator::remove_pending_update(std::int64_t id) noexcept {
    // `method_15483 removePendingUpdate(J)V`.
    const int current_level = get_pending_level(id);
    if (current_level == level_count_) return; // not in map
    const int current_priority = calculate_priority(get_level(id), current_level);
    pending_updates_.remove(id);
    queue_.remove(id, current_priority, level_count_);
    has_pending_updates_ = !queue_.is_empty();
}

} // namespace lattice::world::light
